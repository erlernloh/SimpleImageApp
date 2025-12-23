/**
 * BurstCaptureController.kt - CameraX burst capture controller
 * 
 * Manages burst capture of 8-12 YUV frames with locked AE/AF
 * for Ultra Detail+ processing.
 */

package com.imagedit.app.ultradetail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.Image
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private const val TAG = "BurstCaptureController"

/**
 * Capture quality mode for burst capture
 */
enum class CaptureQuality {
    /** Use ImageAnalysis - faster but preview quality frames */
    PREVIEW,
    /** Use ImageCapture - slower but full sensor quality frames */
    HIGH_QUALITY
}

/**
 * Burst capture configuration
 */
data class BurstCaptureConfig(
    val frameCount: Int = 8,
    val targetResolution: Size = Size(4000, 3000), // ~12MP
    val lockAeAf: Boolean = true,
    val frameIntervalMs: Long = 150, // Time between frames - 150ms allows for hand micro-movements
    val captureQuality: CaptureQuality = CaptureQuality.PREVIEW, // Quality mode for capture
    // Enhanced capture settings for ULTRA preset
    val minFrameDiversity: Float = 0.3f,      // Minimum sub-pixel shift threshold (pixels)
    val adaptiveInterval: Boolean = false,     // Adjust timing based on detected motion
    val intervalRangeMs: IntRange = 30..200,   // Range for adaptive interval
    val diversityCheckEnabled: Boolean = false, // Enable real-time diversity validation
    // Exposure bracketing for detail capture
    val useExposureBracketing: Boolean = false, // Vary exposure across frames for HDR+detail
    val exposureBracketStops: Float = 1.0f      // EV variation (±1 stop = 2x range)
) {
    init {
        require(frameCount in 2..20) { "Frame count must be between 2 and 20" }
        require(minFrameDiversity >= 0f) { "minFrameDiversity must be non-negative" }
    }
    
    companion object {
        /** Standard config for MAX preset (8 frames, fast) */
        fun forMaxPreset() = BurstCaptureConfig(
            frameCount = 8,
            frameIntervalMs = 150,
            captureQuality = CaptureQuality.HIGH_QUALITY,
            adaptiveInterval = false,
            diversityCheckEnabled = false
        )
        
        /** Enhanced config for ULTRA preset (12+ frames, diversity check, exposure bracketing) */
        fun forUltraPreset() = BurstCaptureConfig(
            frameCount = 10,  // Reduced to 10 for faster capture with bracketing
            frameIntervalMs = 80,  // Faster interval since exposure changes add delay
            captureQuality = CaptureQuality.HIGH_QUALITY,
            minFrameDiversity = 0.3f,
            adaptiveInterval = true,
            intervalRangeMs = 30..150,
            diversityCheckEnabled = true,
            useExposureBracketing = true,  // Enable exposure variation for HDR detail
            exposureBracketStops = 1.5f    // ±1.5 EV for better shadow/highlight recovery
        )
    }
}

/**
 * Frame diversity metrics - measures how much sub-pixel variation exists between frames
 */
data class FrameDiversityMetrics(
    val estimatedSubPixelShift: Float,    // Average estimated sub-pixel shift in pixels
    val gyroMagnitude: Float,             // Average gyro rotation magnitude (rad/s)
    val frameSpread: Float,               // Spread of motion across frames (0-1)
    val isDiversityGood: Boolean,         // Whether diversity meets threshold
    val recommendation: String            // User-facing recommendation
) {
    companion object {
        fun fromGyroSamples(
            samples: List<GyroSample>,
            focalLengthPx: Float = 3000f,  // Approximate focal length in pixels
            threshold: Float = 0.3f
        ): FrameDiversityMetrics {
            if (samples.isEmpty()) {
                return FrameDiversityMetrics(
                    estimatedSubPixelShift = 0f,
                    gyroMagnitude = 0f,
                    frameSpread = 0f,
                    isDiversityGood = false,
                    recommendation = "No motion data available"
                )
            }
            
            // Calculate average gyro magnitude
            var sumMagnitude = 0f
            var maxMagnitude = 0f
            samples.forEach { sample ->
                val mag = kotlin.math.sqrt(
                    sample.rotationX * sample.rotationX +
                    sample.rotationY * sample.rotationY +
                    sample.rotationZ * sample.rotationZ
                )
                sumMagnitude += mag
                maxMagnitude = kotlin.math.max(maxMagnitude, mag)
            }
            val avgMagnitude = sumMagnitude / samples.size
            
            // Estimate sub-pixel shift from gyro rotation
            // Angular velocity (rad/s) * exposure time (~30ms) * focal length = pixel shift
            val exposureTimeSec = 0.03f  // Approximate exposure time
            val estimatedShift = avgMagnitude * exposureTimeSec * focalLengthPx
            
            // Calculate frame spread (variance in motion)
            val variance = samples.map { sample ->
                val mag = kotlin.math.sqrt(
                    sample.rotationX * sample.rotationX +
                    sample.rotationY * sample.rotationY +
                    sample.rotationZ * sample.rotationZ
                )
                (mag - avgMagnitude) * (mag - avgMagnitude)
            }.average().toFloat()
            val spread = kotlin.math.sqrt(variance) / (avgMagnitude + 0.001f)
            
            val isDiversityGood = estimatedShift >= threshold
            
            val recommendation = when {
                estimatedShift < threshold * 0.3f -> "Hold device more loosely for natural hand tremor"
                estimatedShift < threshold -> "Slight movement detected - try gentle hand motion"
                estimatedShift > threshold * 3f -> "Too much motion - try to hold steadier"
                else -> "Good frame diversity for super-resolution"
            }
            
            return FrameDiversityMetrics(
                estimatedSubPixelShift = estimatedShift,
                gyroMagnitude = avgMagnitude,
                frameSpread = spread.coerceIn(0f, 1f),
                isDiversityGood = isDiversityGood,
                recommendation = recommendation
            )
        }
    }
}

/**
 * Gyroscope sample with timestamp
 */
data class GyroSample(
    val timestamp: Long,      // Nanoseconds (same timebase as camera frames)
    val rotationX: Float,     // Rotation rate around X axis (rad/s)
    val rotationY: Float,     // Rotation rate around Y axis (rad/s)
    val rotationZ: Float      // Rotation rate around Z axis (rad/s)
)

/**
 * Captured frame data with associated gyro samples
 * 
 * Supports streaming memory management - call release() after processing
 * to free the ByteBuffer memory immediately rather than waiting for GC.
 */
data class CapturedFrame(
    val yPlane: ByteBuffer,
    val uPlane: ByteBuffer,
    val vPlane: ByteBuffer,
    val yRowStride: Int,
    val uvRowStride: Int,
    val uvPixelStride: Int,
    val width: Int,
    val height: Int,
    val timestamp: Long,
    val gyroSamples: List<GyroSample> = emptyList(),  // Gyro samples during this frame's exposure
    @Volatile var isReleased: Boolean = false  // Track if frame has been released
) {
    /**
     * Release the frame's memory buffers.
     * Call this after the frame has been processed to reduce memory pressure.
     * 
     * Note: DirectByteBuffers are not immediately freed by this call,
     * but marking as released prevents further use and helps GC prioritize.
     */
    fun release() {
        if (!isReleased) {
            isReleased = true
            // Clear buffer references to help GC
            // Note: DirectByteBuffer memory is freed when the buffer is GC'd
            // We can't explicitly free it, but clearing helps signal to GC
            yPlane.clear()
            uPlane.clear()
            vPlane.clear()
        }
    }
    
    /**
     * Get estimated memory usage in bytes
     */
    fun getMemoryUsageBytes(): Long {
        return yPlane.capacity().toLong() + uPlane.capacity() + vPlane.capacity()
    }
    
    companion object {
        /**
         * Create from CameraX Image
         */
        fun fromImage(image: Image): CapturedFrame {
            require(image.format == ImageFormat.YUV_420_888) {
                "Expected YUV_420_888, got ${image.format}"
            }
            
            val planes = image.planes
            
            // Copy plane data to direct ByteBuffers (image will be closed)
            val yBuffer = copyToDirectBuffer(planes[0].buffer)
            val uBuffer = copyToDirectBuffer(planes[1].buffer)
            val vBuffer = copyToDirectBuffer(planes[2].buffer)
            
            return CapturedFrame(
                yPlane = yBuffer,
                uPlane = uBuffer,
                vPlane = vBuffer,
                yRowStride = planes[0].rowStride,
                uvRowStride = planes[1].rowStride,
                uvPixelStride = planes[1].pixelStride,
                width = image.width,
                height = image.height,
                timestamp = image.timestamp
            )
        }
        
        private fun copyToDirectBuffer(source: ByteBuffer): ByteBuffer {
            // Check available memory before allocating
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val availableMemory = maxMemory - usedMemory
            val requiredBytes = source.remaining()
            
            // If less than 20% memory available and buffer is large, request GC
            if (availableMemory < maxMemory * 0.2 && requiredBytes > 100_000) {
                System.gc()
            }
            
            val copy = ByteBuffer.allocateDirect(requiredBytes)
            copy.put(source)
            copy.rewind()
            return copy
        }
    }
}

/**
 * High-quality captured frame using Bitmap instead of YUV planes.
 * Used when ImageCapture is available for full-resolution capture.
 */
data class HighQualityCapturedFrame(
    val bitmap: android.graphics.Bitmap,
    val width: Int,
    val height: Int,
    val timestamp: Long,
    val gyroSamples: List<GyroSample> = emptyList(),
    val exposureTimeNs: Long = 0,
    val iso: Int = 0,
    val exposureCompensation: Int = 0  // EV compensation index (0 = base, negative = darker, positive = brighter)
) {
    /**
     * Recycle the bitmap to free memory
     */
    fun recycle() {
        if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
    }
}

/**
 * Burst capture state
 */
sealed class BurstCaptureState {
    object Idle : BurstCaptureState()
    data class Capturing(
        val framesCollected: Int, 
        val totalFrames: Int,
        val exposureInfo: String = ""  // e.g., "EV+1.5 | 1/60s"
    ) : BurstCaptureState()
    data class Complete(val frames: List<CapturedFrame>) : BurstCaptureState()
    data class HighQualityComplete(val frames: List<HighQualityCapturedFrame>) : BurstCaptureState()
    data class Error(val message: String) : BurstCaptureState()
}

/**
 * Burst capture controller for CameraX
 * 
 * Captures multiple YUV frames in rapid succession with locked
 * exposure and focus for HDR+ style processing.
 */
class BurstCaptureController(
    private val context: Context,
    val config: BurstCaptureConfig = BurstCaptureConfig()
) : SensorEventListener {
    private val _captureState = MutableStateFlow<BurstCaptureState>(BurstCaptureState.Idle)
    val captureState: StateFlow<BurstCaptureState> = _captureState.asStateFlow()
    
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var imageAnalysis: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null  // For high-quality capture
    private var camera: Camera? = null
    
    private val capturedFrames = mutableListOf<CapturedFrame>()
    private var isCapturing = false
    private var captureJob: Job? = null
    
    // Channel for receiving frames during burst
    private var frameChannel: Channel<CapturedFrame>? = null
    
    // Gyroscope sensor for motion tracking
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    
    // Gyro sample buffer - thread-safe collection
    private val gyroBuffer = java.util.concurrent.ConcurrentLinkedQueue<GyroSample>()
    private var isGyroRecording = false
    
    // Track last frame timestamp for associating gyro samples
    private var lastFrameTimestamp: Long = 0
    
    /**
     * Setup burst capture with CameraX
     * 
     * @param cameraProvider Camera provider
     * @param lifecycleOwner Lifecycle owner for camera binding
     * @param cameraSelector Camera to use
     * @param preview Optional preview use case
     */
    fun setup(
        cameraProvider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
        preview: Preview? = null
    ) {
        // Configure resolution selector for both use cases
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    config.targetResolution,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        
        // Always create ImageAnalysis for preview-quality capture (backward compatibility)
        imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply {
                setAnalyzer(analysisExecutor) { imageProxy ->
                    handleFrame(imageProxy)
                }
            }
        
        // Build use cases list
        val useCases = mutableListOf<UseCase>()
        preview?.let { useCases.add(it) }
        
        // Add ImageCapture for high-quality mode, or ImageAnalysis for preview mode
        if (config.captureQuality == CaptureQuality.HIGH_QUALITY) {
            // Create ImageCapture for full-resolution capture
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(resolutionSelector)
                .build()
            useCases.add(imageCapture!!)
            Log.d(TAG, "High-quality capture mode enabled (ImageCapture)")
        } else {
            // Use ImageAnalysis for preview-quality capture
            useCases.add(imageAnalysis!!)
            Log.d(TAG, "Preview capture mode enabled (ImageAnalysis)")
        }
        
        cameraProvider.unbindAll()
        camera = cameraProvider.bindToLifecycle(
            lifecycleOwner,
            cameraSelector,
            *useCases.toTypedArray()
        )
        
        // Log actual resolution obtained
        val actualResolution = when (config.captureQuality) {
            CaptureQuality.HIGH_QUALITY -> imageCapture?.resolutionInfo?.resolution ?: config.targetResolution
            CaptureQuality.PREVIEW -> imageAnalysis?.resolutionInfo?.resolution ?: config.targetResolution
        }
        Log.d(TAG, "Burst capture setup complete: ${config.frameCount} frames at $actualResolution " +
                   "(requested: ${config.targetResolution}, quality: ${config.captureQuality})")
    }
    
    /**
     * Start burst capture
     * 
     * @param scope Coroutine scope for capture operation
     * @return Flow of capture state updates
     */
    suspend fun startCapture(scope: CoroutineScope): List<CapturedFrame> = suspendCoroutine { continuation ->
        if (isCapturing) {
            continuation.resume(emptyList())
            return@suspendCoroutine
        }
        
        isCapturing = true
        capturedFrames.clear()
        
        // Use bounded channel to prevent memory overflow
        frameChannel = Channel(config.frameCount + 2)
        
        // Request GC before capture to free up memory
        System.gc()
        
        _captureState.value = BurstCaptureState.Capturing(0, config.frameCount)
        
        // Start gyroscope recording for motion tracking
        startGyroRecording()
        
        captureJob = scope.launch {
            try {
                // Lock AE/AF if configured
                if (config.lockAeAf) {
                    lockExposureAndFocus()
                }
                
                // Collect frames with gyro data
                val frames = mutableListOf<CapturedFrame>()
                
                repeat(config.frameCount) { index ->
                    val frame = frameChannel?.receive()
                    if (frame != null) {
                        // Associate gyro samples with this frame
                        val gyroSamples = getGyroSamplesForFrame(frame.timestamp)
                        val frameWithGyro = frame.copy(gyroSamples = gyroSamples)
                        
                        frames.add(frameWithGyro)
                        
                        // Get exposure info for display (preview mode doesn't use bracketing)
                        val exposureInfo = "Frame ${index + 1}/${config.frameCount}"
                        
                        _captureState.value = BurstCaptureState.Capturing(index + 1, config.frameCount, exposureInfo)
                        
                        Log.d(TAG, "Captured frame ${index + 1}/${config.frameCount} with ${gyroSamples.size} gyro samples [$exposureInfo]")
                    }
                    
                    // Small delay between frames
                    if (index < config.frameCount - 1) {
                        delay(config.frameIntervalMs)
                    }
                }
                
                // Stop gyroscope recording
                stopGyroRecording()
                
                // Log gyro statistics for debugging
                logGyroStatistics(frames)
                
                // Unlock AE/AF
                if (config.lockAeAf) {
                    unlockExposureAndFocus()
                }
                
                isCapturing = false
                _captureState.value = BurstCaptureState.Complete(frames)
                continuation.resume(frames)
                
            } catch (e: Exception) {
                Log.e(TAG, "Burst capture failed", e)
                stopGyroRecording()
                isCapturing = false
                _captureState.value = BurstCaptureState.Error(e.message ?: "Unknown error")
                continuation.resume(emptyList())
            }
        }
    }
    
    /**
     * Log gyroscope statistics for debugging and validation
     */
    private fun logGyroStatistics(frames: List<CapturedFrame>) {
        val totalSamples = frames.sumOf { it.gyroSamples.size }
        val avgSamplesPerFrame = if (frames.isNotEmpty()) totalSamples / frames.size else 0
        
        if (totalSamples == 0) {
            Log.w(TAG, "Gyro stats: No gyro samples collected (gyroscope may be unavailable)")
            return
        }
        
        // Calculate average rotation rates across all samples
        var sumX = 0f; var sumY = 0f; var sumZ = 0f
        var maxRotation = 0f
        
        frames.flatMap { it.gyroSamples }.forEach { sample ->
            sumX += kotlin.math.abs(sample.rotationX)
            sumY += kotlin.math.abs(sample.rotationY)
            sumZ += kotlin.math.abs(sample.rotationZ)
            
            val magnitude = kotlin.math.sqrt(
                sample.rotationX * sample.rotationX +
                sample.rotationY * sample.rotationY +
                sample.rotationZ * sample.rotationZ
            )
            maxRotation = kotlin.math.max(maxRotation, magnitude)
        }
        
        val avgX = sumX / totalSamples
        val avgY = sumY / totalSamples
        val avgZ = sumZ / totalSamples
        
        Log.i(TAG, "Gyro stats: $totalSamples samples (~$avgSamplesPerFrame/frame)")
        Log.i(TAG, "Gyro avg rotation (rad/s): X=%.4f, Y=%.4f, Z=%.4f, max=%.4f".format(avgX, avgY, avgZ, maxRotation))
        
        // Estimate total rotation during burst (rough approximation)
        val burstDurationMs = frames.size * config.frameIntervalMs
        val estimatedRotationDeg = maxRotation * (burstDurationMs / 1000f) * (180f / Math.PI.toFloat())
        Log.i(TAG, "Gyro estimated max rotation during burst: %.2f degrees".format(estimatedRotationDeg))
    }
    
    // ==================== High-Quality Capture (ImageCapture) ====================
    
    /**
     * Start high-quality burst capture using ImageCapture.
     * This captures full-resolution frames instead of preview-quality frames.
     * 
     * @param scope Coroutine scope for capture operation
     * @return List of high-quality captured frames
     */
    suspend fun startHighQualityCapture(scope: CoroutineScope): List<HighQualityCapturedFrame> {
        if (isCapturing) {
            Log.w(TAG, "Already capturing, ignoring startHighQualityCapture")
            return emptyList()
        }
        
        if (imageCapture == null) {
            Log.e(TAG, "ImageCapture not available - was setup() called with HIGH_QUALITY mode?")
            _captureState.value = BurstCaptureState.Error("High-quality capture not configured")
            return emptyList()
        }
        
        isCapturing = true
        val frames = mutableListOf<HighQualityCapturedFrame>()
        
        try {
            // Request GC before capture to free up memory
            System.gc()
            
            _captureState.value = BurstCaptureState.Capturing(0, config.frameCount)
            
            // Start gyroscope recording for motion tracking
            startGyroRecording()
            
            // Lock AE/AF if configured
            if (config.lockAeAf) {
                lockExposureAndFocus()
            }
            
            // Capture frames sequentially with adaptive timing and optional exposure bracketing
            var currentInterval = config.frameIntervalMs
            var lowDiversityWarningShown = false
            
            // Calculate exposure compensation pattern if bracketing is enabled
            val exposurePattern = if (config.useExposureBracketing) {
                calculateExposureBracketPattern(config.frameCount, config.exposureBracketStops)
            } else {
                IntArray(config.frameCount) { 0 } // All frames at base exposure
            }
            
            repeat(config.frameCount) { index ->
                val exposureComp = exposurePattern[index]
                val frame = captureHighQualityFrame(exposureComp)
                if (frame != null) {
                    frames.add(frame)
                    
                    // Format exposure info for display with actual camera metadata
                    val evStops = exposureComp / 3.0f  // Convert index to EV stops (1/3 stop increments)
                    val evText = when {
                        exposureComp == 0 -> "Base"
                        exposureComp > 0 -> "EV+%.1f".format(evStops)
                        else -> "EV%.1f".format(evStops)
                    }
                    
                    // Format shutter speed (e.g., "1/60s")
                    val shutterText = if (frame.exposureTimeNs > 0) {
                        val exposureSec = frame.exposureTimeNs / 1_000_000_000.0
                        if (exposureSec >= 1.0) {
                            "%.1fs".format(exposureSec)
                        } else {
                            "1/%d".format((1.0 / exposureSec).toInt())
                        }
                    } else ""
                    
                    // Format ISO
                    val isoText = if (frame.iso > 0) "ISO ${frame.iso}" else ""
                    
                    // Combine all info
                    val exposureInfo = buildString {
                        append(evText)
                        if (shutterText.isNotEmpty()) append(" | $shutterText")
                        if (isoText.isNotEmpty()) append(" | $isoText")
                    }
                    
                    _captureState.value = BurstCaptureState.Capturing(index + 1, config.frameCount, exposureInfo)
                    Log.d(TAG, "HQ Captured frame ${index + 1}/${config.frameCount}: ${frame.width}x${frame.height} [$exposureInfo]")
                    
                    // Check diversity after a few frames (for ULTRA preset)
                    if (config.diversityCheckEnabled && index >= 2 && !lowDiversityWarningShown) {
                        val allGyroSamples = frames.flatMap { it.gyroSamples }
                        val diversity = FrameDiversityMetrics.fromGyroSamples(
                            allGyroSamples,
                            threshold = config.minFrameDiversity
                        )
                        
                        if (!diversity.isDiversityGood) {
                            Log.w(TAG, "Low frame diversity detected: ${diversity.recommendation}")
                            lowDiversityWarningShown = true
                            // Could emit a UI event here to prompt user
                        }
                        
                        // Adaptive interval: increase delay if motion is too low
                        if (config.adaptiveInterval) {
                            currentInterval = calculateAdaptiveInterval(diversity)
                            Log.d(TAG, "Adaptive interval: ${currentInterval}ms (shift=${diversity.estimatedSubPixelShift}px)")
                        }
                    }
                } else {
                    Log.w(TAG, "Failed to capture HQ frame ${index + 1}")
                }
                
                // Delay between frames for hand movement variation
                if (index < config.frameCount - 1) {
                    delay(currentInterval)
                }
            }
            
            // Stop gyroscope recording
            stopGyroRecording()
            
            // Log gyro statistics
            logGyroStatisticsHQ(frames)
            
            // Unlock AE/AF
            if (config.lockAeAf) {
                unlockExposureAndFocus()
            }
            
            isCapturing = false
            
            if (frames.isEmpty()) {
                _captureState.value = BurstCaptureState.Error("No frames captured")
                return emptyList()
            }
            
            _captureState.value = BurstCaptureState.HighQualityComplete(frames)
            Log.i(TAG, "High-quality burst complete: ${frames.size} frames")
            return frames
            
        } catch (e: CancellationException) {
            Log.d(TAG, "High-quality capture cancelled")
            frames.forEach { it.recycle() }
            stopGyroRecording()
            isCapturing = false
            _captureState.value = BurstCaptureState.Idle
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "High-quality burst capture failed", e)
            frames.forEach { it.recycle() }
            stopGyroRecording()
            isCapturing = false
            _captureState.value = BurstCaptureState.Error(e.message ?: "Unknown error")
            return emptyList()
        }
    }
    
    /**
     * Capture a single high-quality frame using ImageCapture with optional exposure compensation
     */
    private suspend fun captureHighQualityFrame(exposureCompensation: Int = 0): HighQualityCapturedFrame? {
        val capture = imageCapture ?: return null
        
        // Apply exposure compensation for this frame
        try {
            camera?.cameraControl?.setExposureCompensationIndex(exposureCompensation)
            if (exposureCompensation != 0) {
                delay(80) // Wait longer for non-base exposures to stabilize
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set exposure compensation: ${e.message}")
        }
        
        return suspendCancellableCoroutine { continuation ->
            // Use elapsedRealtimeNanos which matches sensor event timestamps (both use CLOCK_BOOTTIME)
            val timestamp = android.os.SystemClock.elapsedRealtimeNanos()
            
            capture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            // Extract EXIF metadata from image
                            val exifExposureTimeNs = extractExposureTimeNs(image)
                            val exifIso = extractIso(image)
                            
                            val bitmap = imageProxyToBitmap(image)
                            if (bitmap != null) {
                                val gyroSamples = getGyroSamplesForFrame(timestamp)
                                val frame = HighQualityCapturedFrame(
                                    bitmap = bitmap,
                                    width = bitmap.width,
                                    height = bitmap.height,
                                    timestamp = timestamp,
                                    gyroSamples = gyroSamples,
                                    exposureTimeNs = exifExposureTimeNs,
                                    iso = exifIso,
                                    exposureCompensation = exposureCompensation
                                )
                                continuation.resume(frame)
                            } else {
                                Log.e(TAG, "Failed to convert ImageProxy to Bitmap")
                                continuation.resume(null)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing captured image", e)
                            continuation.resume(null)
                        } finally {
                            image.close()
                        }
                    }
                    
                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "ImageCapture failed: ${exception.imageCaptureError}", exception)
                        continuation.resume(null)
                    }
                }
            )
        }
    }
    
    /**
     * Convert ImageProxy to Bitmap
     */
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            
            // Decode JPEG to Bitmap with ARGB_8888 format for consistent native processing
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            
            // Apply rotation if needed
            val rotationDegrees = image.imageInfo.rotationDegrees
            if (rotationDegrees != 0 && bitmap != null) {
                val matrix = Matrix().apply {
                    postRotate(rotationDegrees.toFloat())
                }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) {
                    bitmap.recycle()
                }
                rotated
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert ImageProxy to Bitmap", e)
            null
        }
    }
    
    /**
     * Extract exposure time in nanoseconds from ImageProxy EXIF data
     */
    private fun extractExposureTimeNs(image: ImageProxy): Long {
        return try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            buffer.rewind() // Reset buffer position for later use
            
            val inputStream = java.io.ByteArrayInputStream(bytes)
            val exif = androidx.exifinterface.media.ExifInterface(inputStream)
            
            // ExposureTime is in seconds as a rational (e.g., "1/60")
            val exposureTimeStr = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME)
            if (exposureTimeStr != null) {
                val exposureTimeSec = exposureTimeStr.toDoubleOrNull() ?: 0.0
                (exposureTimeSec * 1_000_000_000).toLong()
            } else {
                0L
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract exposure time from EXIF: ${e.message}")
            0L
        }
    }
    
    /**
     * Extract ISO sensitivity from ImageProxy EXIF data
     */
    private fun extractIso(image: ImageProxy): Int {
        return try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            buffer.rewind() // Reset buffer position for later use
            
            val inputStream = java.io.ByteArrayInputStream(bytes)
            val exif = androidx.exifinterface.media.ExifInterface(inputStream)
            
            exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract ISO from EXIF: ${e.message}")
            0
        }
    }
    
    /**
     * Calculate adaptive interval based on current frame diversity
     * 
     * If motion is low, increase interval to allow more hand tremor accumulation.
     * If motion is high, decrease interval to capture more frames quickly.
     */
    private fun calculateAdaptiveInterval(diversity: FrameDiversityMetrics): Long {
        val minInterval = config.intervalRangeMs.first.toLong()
        val maxInterval = config.intervalRangeMs.last.toLong()
        val targetShift = config.minFrameDiversity
        
        return when {
            // Very low motion - use maximum interval to accumulate more tremor
            diversity.estimatedSubPixelShift < targetShift * 0.3f -> maxInterval
            // Low motion - increase interval
            diversity.estimatedSubPixelShift < targetShift -> {
                val ratio = diversity.estimatedSubPixelShift / targetShift
                (maxInterval - (maxInterval - minInterval) * ratio).toLong()
            }
            // Good motion - use shorter interval
            diversity.estimatedSubPixelShift < targetShift * 2f -> {
                val ratio = (diversity.estimatedSubPixelShift - targetShift) / targetShift
                (config.frameIntervalMs - (config.frameIntervalMs - minInterval) * ratio * 0.5f).toLong()
            }
            // High motion - use minimum interval
            else -> minInterval
        }.coerceIn(minInterval, maxInterval)
    }
    
    /**
     * Get final diversity metrics for the captured burst
     * Call this after capture completes to get quality assessment
     */
    fun getDiversityMetrics(frames: List<HighQualityCapturedFrame>): FrameDiversityMetrics {
        val allGyroSamples = frames.flatMap { it.gyroSamples }
        return FrameDiversityMetrics.fromGyroSamples(
            allGyroSamples,
            threshold = config.minFrameDiversity
        )
    }
    
    /**
     * Log gyroscope statistics for high-quality frames
     */
    private fun logGyroStatisticsHQ(frames: List<HighQualityCapturedFrame>) {
        val totalSamples = frames.sumOf { it.gyroSamples.size }
        val avgSamplesPerFrame = if (frames.isNotEmpty()) totalSamples / frames.size else 0
        
        if (totalSamples == 0) {
            Log.w(TAG, "HQ Gyro stats: No gyro samples collected")
            return
        }
        
        var maxRotation = 0f
        frames.flatMap { it.gyroSamples }.forEach { sample ->
            val magnitude = kotlin.math.sqrt(
                sample.rotationX * sample.rotationX +
                sample.rotationY * sample.rotationY +
                sample.rotationZ * sample.rotationZ
            )
            maxRotation = kotlin.math.max(maxRotation, magnitude)
        }
        
        Log.i(TAG, "HQ Gyro stats: $totalSamples samples (~$avgSamplesPerFrame/frame), max rotation: %.4f rad/s".format(maxRotation))
    }
    
    /**
     * Cancel ongoing capture
     */
    fun cancelCapture() {
        captureJob?.cancel()
        stopGyroRecording()
        isCapturing = false
        frameChannel?.close()
        _captureState.value = BurstCaptureState.Idle
    }
    
    /**
     * Handle incoming frame from image analysis
     */
    private fun handleFrame(imageProxy: ImageProxy) {
        if (isCapturing && frameChannel?.isClosedForSend == false) {
            try {
                val frame = CapturedFrame.fromImage(imageProxy.image!!)
                frameChannel?.trySend(frame)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to capture frame", e)
            }
        }
        imageProxy.close()
    }
    
    /**
     * Lock auto-exposure and auto-focus
     * 
     * For burst capture, we need consistent exposure across all frames.
     * This locks both focus and exposure metering to prevent changes during capture.
     */
    private suspend fun lockExposureAndFocus() {
        camera?.cameraControl?.let { control ->
            try {
                // Start focus and metering at center point
                val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
                val centerPoint = factory.createPoint(0.5f, 0.5f)
                
                // Build action that locks both AE and AF
                // FLAG_AE locks auto-exposure, FLAG_AF locks auto-focus
                val action = FocusMeteringAction.Builder(centerPoint, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(10, java.util.concurrent.TimeUnit.SECONDS) // Longer duration for burst
                    .build()
                
                val result = control.startFocusAndMetering(action).await()
                
                // Wait a bit for exposure to stabilize after lock
                delay(100)
                
                Log.d(TAG, "AE/AF locked: focused=${result.isFocusSuccessful}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to lock AE/AF: ${e.message}")
            }
        }
    }
    
    /**
     * Unlock auto-exposure and auto-focus
     */
    private fun unlockExposureAndFocus() {
        camera?.cameraControl?.cancelFocusAndMetering()
        // Reset exposure compensation to 0
        camera?.cameraControl?.setExposureCompensationIndex(0)
        Log.d(TAG, "AE/AF unlocked, exposure reset")
    }
    
    /**
     * Calculate exposure bracket pattern for HDR detail capture
     * 
     * Pattern distributes exposures across the bracket range:
     * - 12 frames with ±1 EV: [0, +1, -1, +2, -2, 0, +1, -1, +2, -2, 0, 0]
     * 
     * This ensures:
     * - Multiple frames at each exposure level
     * - Base exposure frames for reference
     * - Overexposed frames capture shadow detail
     * - Underexposed frames preserve highlight detail
     */
    private fun calculateExposureBracketPattern(frameCount: Int, bracketStops: Float): IntArray {
        // Convert EV stops to exposure compensation index
        // Most cameras support ±2 EV in 1/3 or 1/2 stop increments
        // Index typically ranges from -6 to +6 (representing ±2 EV in 1/3 stops)
        val maxIndex = (bracketStops * 3).toInt().coerceIn(1, 6) // 1 EV = 3 steps in 1/3 stop increments
        
        // IMPROVED PATTERN: Prioritize base exposure frames for alignment,
        // intersperse with varied exposures for detail capture
        // Pattern: 0, -, +, 0, --, ++, 0, -, +, 0 (base frames at positions 0, 3, 6, 9)
        // This ensures good alignment reference while capturing HDR detail
        
        return when {
            frameCount <= 3 -> {
                // Simple 3-frame bracket: [0, -, +] - base first for reference
                intArrayOf(0, -maxIndex, maxIndex)
            }
            frameCount <= 5 -> {
                // 5-frame bracket: [0, -, +, 0, --] - two base frames
                intArrayOf(0, -maxIndex, maxIndex, 0, -maxIndex * 2 / 3)
            }
            frameCount <= 7 -> {
                // 7-frame bracket with multiple base exposures for alignment
                intArrayOf(0, -maxIndex, maxIndex, 0, -maxIndex * 2 / 3, maxIndex * 2 / 3, 0)
            }
            else -> {
                // 8+ frames: optimized pattern for MFSR + HDR
                // More base exposure frames = better alignment
                // Varied exposures = better detail in shadows/highlights
                val pattern = mutableListOf<Int>()
                
                // Pattern repeats: [0, -, +] with increasing EV variation
                val evLevels = listOf(0, -maxIndex / 2, maxIndex / 2, 0, -maxIndex, maxIndex, 0, -maxIndex * 2 / 3, maxIndex * 2 / 3, 0)
                
                for (i in 0 until frameCount) {
                    pattern.add(evLevels[i % evLevels.size])
                }
                
                pattern.toIntArray()
            }
        }.also { pattern ->
            Log.d(TAG, "Exposure bracket pattern (${frameCount} frames, ±${bracketStops}EV): ${pattern.joinToString()}")
        }
    }
    
    /**
     * Release resources
     */
    fun release() {
        cancelCapture()
        stopGyroRecording()
        analysisExecutor.shutdown()
        imageAnalysis = null
        camera = null
    }
    
    // ==================== Gyroscope Recording ====================
    
    /**
     * Start recording gyroscope data
     * Tries SENSOR_DELAY_FASTEST (~200Hz) first, falls back to SENSOR_DELAY_GAME (~50Hz)
     */
    private fun startGyroRecording() {
        if (gyroscope == null) {
            Log.w(TAG, "Gyroscope not available on this device")
            return
        }
        
        gyroBuffer.clear()
        isGyroRecording = true
        lastFrameTimestamp = 0
        
        // Try fastest rate first (requires HIGH_SAMPLING_RATE_SENSORS permission on Android 12+)
        var registered = try {
            sensorManager.registerListener(
                this,
                gyroscope,
                SensorManager.SENSOR_DELAY_FASTEST
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "SENSOR_DELAY_FASTEST not permitted, trying SENSOR_DELAY_GAME")
            false
        }
        
        // Fallback to game rate if fastest fails
        if (!registered) {
            registered = sensorManager.registerListener(
                this,
                gyroscope,
                SensorManager.SENSOR_DELAY_GAME  // ~50Hz, doesn't require special permission
            )
            if (registered) {
                Log.d(TAG, "Gyroscope recording started (SENSOR_DELAY_GAME fallback)")
            }
        } else {
            Log.d(TAG, "Gyroscope recording started (SENSOR_DELAY_FASTEST)")
        }
        
        if (!registered) {
            Log.e(TAG, "Failed to register gyroscope listener")
            isGyroRecording = false
        }
    }
    
    /**
     * Stop recording gyroscope data
     */
    private fun stopGyroRecording() {
        if (isGyroRecording) {
            sensorManager.unregisterListener(this)
            isGyroRecording = false
            Log.d(TAG, "Gyroscope recording stopped. Collected ${gyroBuffer.size} samples")
        }
    }
    
    /**
     * Get gyro samples between two timestamps
     * Used to associate gyro data with each captured frame
     */
    private fun getGyroSamplesForFrame(frameTimestamp: Long): List<GyroSample> {
        val samples = mutableListOf<GyroSample>()
        
        // Get samples between last frame and this frame
        val startTime = if (lastFrameTimestamp > 0) lastFrameTimestamp else frameTimestamp - 50_000_000 // 50ms before
        val endTime = frameTimestamp
        
        // Collect samples in the time window
        gyroBuffer.forEach { sample ->
            if (sample.timestamp in startTime..endTime) {
                samples.add(sample)
            }
        }
        
        // Update last frame timestamp
        lastFrameTimestamp = frameTimestamp
        
        return samples
    }
    
    // ==================== SensorEventListener Implementation ====================
    
    override fun onSensorChanged(event: SensorEvent) {
        if (!isGyroRecording || event.sensor.type != Sensor.TYPE_GYROSCOPE) {
            return
        }
        
        // Store gyro sample with timestamp
        val sample = GyroSample(
            timestamp = event.timestamp,  // Nanoseconds since boot
            rotationX = event.values[0],  // rad/s around X axis
            rotationY = event.values[1],  // rad/s around Y axis
            rotationZ = event.values[2]   // rad/s around Z axis
        )
        
        gyroBuffer.add(sample)
        
        // Limit buffer size to prevent memory issues (keep last ~2 seconds at 200Hz)
        while (gyroBuffer.size > 400) {
            gyroBuffer.poll()
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Log accuracy changes for debugging
        if (sensor.type == Sensor.TYPE_GYROSCOPE) {
            val accuracyStr = when (accuracy) {
                SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "HIGH"
                SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "MEDIUM"
                SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "LOW"
                else -> "UNRELIABLE"
            }
            Log.d(TAG, "Gyroscope accuracy changed: $accuracyStr")
        }
    }
}

/**
 * Extension to await CameraX ListenableFuture
 */
private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.await(): T {
    return suspendCoroutine { continuation ->
        addListener({
            try {
                continuation.resume(get())
            } catch (e: Exception) {
                continuation.resumeWith(Result.failure(e))
            }
        }, Executors.newSingleThreadExecutor())
    }
}
