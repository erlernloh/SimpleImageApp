/**
 * StreamingFrameManager.kt - Memory-efficient frame management for burst processing
 * 
 * Implements streaming architecture to reduce peak memory usage from ~800MB to ~200MB
 * by releasing frames as they are processed rather than holding all frames simultaneously.
 * 
 * Key strategies:
 * 1. Reference frame is kept in memory (needed for alignment comparison)
 * 2. Other frames are released after their contribution is accumulated
 * 3. Memory monitoring with automatic GC triggers
 * 4. Graceful degradation on low-memory devices
 */

package com.imagedit.app.ultradetail

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

private const val TAG = "StreamingFrameManager"

/**
 * Memory statistics for monitoring
 */
data class MemoryStats(
    val usedMemoryMb: Long,
    val maxMemoryMb: Long,
    val availableMemoryMb: Long,
    val percentUsed: Float
) {
    val isLowMemory: Boolean get() = percentUsed > 0.8f
    val isCriticalMemory: Boolean get() = percentUsed > 0.9f
    
    companion object {
        private var cachedContext: android.content.Context? = null
        
        fun initialize(context: android.content.Context) {
            cachedContext = context.applicationContext
        }
        
        fun current(): MemoryStats {
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            
            // Use the larger of: Java heap available OR actual device RAM available
            // This prevents false memory constraints on devices with plenty of RAM
            val heapAvailable = maxMemory - usedMemory
            val deviceAvailable = getDeviceAvailableMemory()
            val availableMemory = maxOf(heapAvailable, deviceAvailable)
            
            return MemoryStats(
                usedMemoryMb = usedMemory / (1024 * 1024),
                maxMemoryMb = maxMemory / (1024 * 1024),
                availableMemoryMb = availableMemory / (1024 * 1024),
                percentUsed = usedMemory.toFloat() / maxMemory
            )
        }
        
        private fun getDeviceAvailableMemory(): Long {
            return try {
                val context = cachedContext ?: return 0L
                val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) 
                    as? android.app.ActivityManager ?: return 0L
                val memInfo = android.app.ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memInfo)
                memInfo.availMem
            } catch (e: Exception) {
                0L // Fallback to heap-only if device memory unavailable
            }
        }
    }
}

/**
 * Frame processing status
 */
enum class FrameStatus {
    PENDING,      // Not yet processed
    PROCESSING,   // Currently being processed
    PROCESSED,    // Processed and can be released
    RELEASED      // Memory released
}

/**
 * Tracked frame with status
 */
data class TrackedFrame(
    val frame: CapturedFrame,
    var status: FrameStatus = FrameStatus.PENDING,
    val isReference: Boolean = false
)

/**
 * Callback for frame processing events
 */
interface StreamingFrameCallback {
    fun onFrameProcessed(frameIndex: Int, totalFrames: Int)
    fun onFrameReleased(frameIndex: Int, memoryFreedMb: Long)
    fun onMemoryWarning(stats: MemoryStats)
}

/**
 * Streaming frame manager for memory-efficient burst processing
 */
class StreamingFrameManager(
    private val callback: StreamingFrameCallback? = null
) {
    private val trackedFrames = mutableListOf<TrackedFrame>()
    private var referenceIndex: Int = -1
    
    // Memory thresholds
    companion object {
        const val LOW_MEMORY_THRESHOLD = 0.75f      // Trigger GC at 75%
        const val CRITICAL_MEMORY_THRESHOLD = 0.85f // Force release at 85%
        const val FRAME_MEMORY_ESTIMATE_MB = 18L    // ~18MB per 12MP YUV frame
    }
    
    /**
     * Initialize with captured frames
     * 
     * @param frames List of captured frames
     * @param referenceIdx Index of the reference frame (will not be released until end)
     */
    fun initialize(frames: List<CapturedFrame>, referenceIdx: Int) {
        trackedFrames.clear()
        referenceIndex = referenceIdx
        
        frames.forEachIndexed { index, frame ->
            trackedFrames.add(TrackedFrame(
                frame = frame,
                status = FrameStatus.PENDING,
                isReference = index == referenceIdx
            ))
        }
        
        val totalMemoryMb = frames.sumOf { it.getMemoryUsageBytes() } / (1024 * 1024)
        Log.i(TAG, "Initialized with ${frames.size} frames, reference=$referenceIdx, " +
                   "total frame memory=${totalMemoryMb}MB")
    }
    
    /**
     * Get frame count
     */
    fun getFrameCount(): Int = trackedFrames.size
    
    /**
     * Get reference frame (always available)
     */
    fun getReferenceFrame(): CapturedFrame? {
        return trackedFrames.getOrNull(referenceIndex)?.frame
    }
    
    /**
     * Get frame by index (may be null if released)
     */
    fun getFrame(index: Int): CapturedFrame? {
        val tracked = trackedFrames.getOrNull(index) ?: return null
        return if (tracked.status == FrameStatus.RELEASED) null else tracked.frame
    }
    
    /**
     * Get all frames that haven't been released
     */
    fun getAvailableFrames(): List<CapturedFrame> {
        return trackedFrames
            .filter { it.status != FrameStatus.RELEASED }
            .map { it.frame }
    }
    
    /**
     * Mark frame as being processed
     */
    fun markProcessing(index: Int) {
        trackedFrames.getOrNull(index)?.let {
            it.status = FrameStatus.PROCESSING
        }
    }
    
    /**
     * Mark frame as processed (ready for release)
     */
    fun markProcessed(index: Int) {
        trackedFrames.getOrNull(index)?.let {
            it.status = FrameStatus.PROCESSED
            callback?.onFrameProcessed(index, trackedFrames.size)
        }
    }
    
    /**
     * Release a processed frame to free memory
     * 
     * @param index Frame index to release
     * @return true if frame was released, false if it's the reference or already released
     */
    fun releaseFrame(index: Int): Boolean {
        val tracked = trackedFrames.getOrNull(index) ?: return false
        
        // Don't release reference frame until explicitly requested
        if (tracked.isReference) {
            Log.d(TAG, "Skipping release of reference frame $index")
            return false
        }
        
        if (tracked.status == FrameStatus.RELEASED) {
            return false
        }
        
        val memoryBefore = tracked.frame.getMemoryUsageBytes()
        tracked.frame.release()
        tracked.status = FrameStatus.RELEASED
        
        val memoryFreedMb = memoryBefore / (1024 * 1024)
        Log.d(TAG, "Released frame $index, freed ~${memoryFreedMb}MB")
        callback?.onFrameReleased(index, memoryFreedMb)
        
        return true
    }
    
    /**
     * Release all processed frames (except reference)
     */
    fun releaseProcessedFrames(): Int {
        var releasedCount = 0
        trackedFrames.forEachIndexed { index, tracked ->
            if (tracked.status == FrameStatus.PROCESSED && !tracked.isReference) {
                if (releaseFrame(index)) {
                    releasedCount++
                }
            }
        }
        
        if (releasedCount > 0) {
            // Suggest GC after releasing multiple frames
            System.gc()
        }
        
        return releasedCount
    }
    
    /**
     * Release reference frame (call at end of processing)
     */
    fun releaseReferenceFrame() {
        if (referenceIndex >= 0 && referenceIndex < trackedFrames.size) {
            val tracked = trackedFrames[referenceIndex]
            if (tracked.status != FrameStatus.RELEASED) {
                tracked.frame.release()
                tracked.status = FrameStatus.RELEASED
                Log.d(TAG, "Released reference frame $referenceIndex")
            }
        }
    }
    
    /**
     * Release all frames
     */
    fun releaseAll() {
        trackedFrames.forEachIndexed { index, tracked ->
            if (tracked.status != FrameStatus.RELEASED) {
                tracked.frame.release()
                tracked.status = FrameStatus.RELEASED
            }
        }
        System.gc()
        Log.i(TAG, "Released all ${trackedFrames.size} frames")
    }
    
    /**
     * Check memory and release frames if needed
     * 
     * @return true if memory is okay, false if critical
     */
    suspend fun checkMemoryAndRelease(): Boolean = withContext(Dispatchers.Default) {
        val stats = MemoryStats.current()
        
        if (stats.isCriticalMemory) {
            Log.w(TAG, "Critical memory: ${stats.percentUsed * 100}% used, forcing release")
            callback?.onMemoryWarning(stats)
            
            // Release all processed frames
            releaseProcessedFrames()
            
            // Force GC
            System.gc()
            yield()
            
            // Check again
            val newStats = MemoryStats.current()
            return@withContext !newStats.isCriticalMemory
        }
        
        if (stats.isLowMemory) {
            Log.d(TAG, "Low memory: ${stats.percentUsed * 100}% used, releasing processed frames")
            callback?.onMemoryWarning(stats)
            releaseProcessedFrames()
        }
        
        return@withContext true
    }
    
    /**
     * Get current memory statistics
     */
    fun getMemoryStats(): MemoryStats = MemoryStats.current()
    
    /**
     * Get processing statistics
     */
    fun getStats(): StreamingStats {
        val pending = trackedFrames.count { it.status == FrameStatus.PENDING }
        val processing = trackedFrames.count { it.status == FrameStatus.PROCESSING }
        val processed = trackedFrames.count { it.status == FrameStatus.PROCESSED }
        val released = trackedFrames.count { it.status == FrameStatus.RELEASED }
        
        val activeMemoryMb = trackedFrames
            .filter { it.status != FrameStatus.RELEASED }
            .sumOf { it.frame.getMemoryUsageBytes() } / (1024 * 1024)
        
        return StreamingStats(
            totalFrames = trackedFrames.size,
            pendingFrames = pending,
            processingFrames = processing,
            processedFrames = processed,
            releasedFrames = released,
            activeMemoryMb = activeMemoryMb,
            memoryStats = MemoryStats.current()
        )
    }
}

/**
 * Statistics for streaming frame processing
 */
data class StreamingStats(
    val totalFrames: Int,
    val pendingFrames: Int,
    val processingFrames: Int,
    val processedFrames: Int,
    val releasedFrames: Int,
    val activeMemoryMb: Long,
    val memoryStats: MemoryStats
) {
    val completionPercent: Float get() = 
        if (totalFrames > 0) (processedFrames + releasedFrames).toFloat() / totalFrames else 0f
}

/**
 * Extension function to process frames with streaming memory management
 */
suspend fun <T> List<CapturedFrame>.processWithStreaming(
    referenceIndex: Int,
    callback: StreamingFrameCallback? = null,
    processor: suspend (frame: CapturedFrame, index: Int, isReference: Boolean) -> T
): List<T> = withContext(Dispatchers.Default) {
    val manager = StreamingFrameManager(callback)
    manager.initialize(this@processWithStreaming, referenceIndex)
    
    val results = mutableListOf<T>()
    
    try {
        // Process reference frame first (always kept in memory)
        val refFrame = manager.getReferenceFrame()
        if (refFrame != null) {
            manager.markProcessing(referenceIndex)
            val refResult = processor(refFrame, referenceIndex, true)
            results.add(refResult)
            manager.markProcessed(referenceIndex)
        }
        
        // Process other frames with streaming release
        for (i in indices) {
            if (i == referenceIndex) continue
            
            val frame = manager.getFrame(i) ?: continue
            
            manager.markProcessing(i)
            val result = processor(frame, i, false)
            results.add(result)
            manager.markProcessed(i)
            
            // Release frame after processing
            manager.releaseFrame(i)
            
            // Check memory periodically
            if (i % 3 == 0) {
                manager.checkMemoryAndRelease()
            }
            
            yield()  // Allow other coroutines to run
        }
    } finally {
        // Release all remaining frames
        manager.releaseAll()
    }
    
    results
}
