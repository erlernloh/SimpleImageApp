/**
 * NativeBurstProcessor.kt - JNI bridge for native burst processing
 * 
 * Provides Kotlin interface to the C++ Ultra Detail+ pipeline.
 */

package com.imagedit.app.ultradetail

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * Merge method for frame combination
 */
enum class MergeMethod(val value: Int) {
    AVERAGE(0),
    TRIMMED_MEAN(1),
    M_ESTIMATOR(2),
    MEDIAN(3)
}

/**
 * Processing stage for progress reporting
 */
enum class ProcessingStage(val value: Int) {
    IDLE(0),
    CONVERTING_YUV(1),
    BUILDING_PYRAMIDS(2),
    ALIGNING_FRAMES(3),
    MERGING_FRAMES(4),
    COMPUTING_EDGES(5),
    GENERATING_MASK(6),
    MULTI_FRAME_SR(7),
    COMPLETE(8),
    ERROR(9);
    
    companion object {
        fun fromValue(value: Int): ProcessingStage {
            return entries.find { it.value == value } ?: IDLE
        }
    }
}

/**
 * Progress callback interface for native processing
 */
interface NativeProgressCallback {
    fun onProgress(stage: Int, progress: Float, message: String)
}

/**
 * Native burst processor parameters
 */
data class BurstProcessorParams(
    val alignmentTileSize: Int = 32,
    val searchRadius: Int = 8,
    val pyramidLevels: Int = 4,
    val mergeMethod: MergeMethod = MergeMethod.TRIMMED_MEAN,
    val trimRatio: Float = 0.2f,
    val applyWiener: Boolean = true,
    val detailTileSize: Int = 64,
    val detailThreshold: Float = 25f,
    val enableMFSR: Boolean = false,
    val mfsrScaleFactor: Int = 2
)

/**
 * MFSR result information
 */
data class MFSRInfo(
    val applied: Boolean,
    val scaleFactor: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val coveragePercent: Float
)

/**
 * Native burst processor wrapper
 * 
 * Manages the lifecycle of the native BurstProcessor and provides
 * a Kotlin-friendly interface for burst capture processing.
 */
class NativeBurstProcessor private constructor(
    private var nativeHandle: Long
) : AutoCloseable {
    
    /**
     * Process YUV frames from CameraX burst capture
     * 
     * @param yPlanes Array of Y plane direct ByteBuffers
     * @param uPlanes Array of U plane direct ByteBuffers
     * @param vPlanes Array of V plane direct ByteBuffers
     * @param yRowStrides Y plane row strides
     * @param uvRowStrides UV plane row strides
     * @param uvPixelStrides UV pixel strides
     * @param width Image width
     * @param height Image height
     * @param outputBitmap Pre-allocated ARGB_8888 bitmap for output
     * @param progressCallback Optional progress callback
     * @return Result code (0 = success)
     */
    fun processYUV(
        yPlanes: Array<ByteBuffer>,
        uPlanes: Array<ByteBuffer>,
        vPlanes: Array<ByteBuffer>,
        yRowStrides: IntArray,
        uvRowStrides: IntArray,
        uvPixelStrides: IntArray,
        width: Int,
        height: Int,
        outputBitmap: Bitmap,
        progressCallback: ((ProcessingStage, Float, String) -> Unit)? = null
    ): Int {
        check(nativeHandle != 0L) { "Processor has been destroyed" }
        
        val callback = progressCallback?.let { cb ->
            object : NativeProgressCallback {
                override fun onProgress(stage: Int, progress: Float, message: String) {
                    cb(ProcessingStage.fromValue(stage), progress, message)
                }
            }
        }
        
        return nativeProcessYUV(
            nativeHandle,
            yPlanes,
            uPlanes,
            vPlanes,
            yRowStrides,
            uvRowStrides,
            uvPixelStrides,
            width,
            height,
            outputBitmap,
            callback
        )
    }
    
    /**
     * Get the detail mask from the last processing operation
     * 
     * @param outputMask Byte array to receive tile mask
     * @param dimensions Output array [tilesX, tilesY]
     * @return Number of detail tiles, or -1 on error
     */
    fun getDetailMask(outputMask: ByteArray, dimensions: IntArray): Int {
        check(nativeHandle != 0L) { "Processor has been destroyed" }
        return nativeGetDetailMask(nativeHandle, outputMask, dimensions)
    }
    
    /**
     * Get MFSR result information from the last processing operation
     * 
     * @return MFSRInfo or null if no result available
     */
    fun getMFSRInfo(): MFSRInfo? {
        check(nativeHandle != 0L) { "Processor has been destroyed" }
        val info = FloatArray(5)
        val result = nativeGetMFSRInfo(nativeHandle, info)
        if (result != 0) return null
        
        return MFSRInfo(
            applied = info[0] > 0.5f,
            scaleFactor = info[1].toInt(),
            outputWidth = info[2].toInt(),
            outputHeight = info[3].toInt(),
            coveragePercent = info[4]
        )
    }
    
    /**
     * Cancel ongoing processing
     */
    fun cancel() {
        if (nativeHandle != 0L) {
            nativeCancel(nativeHandle)
        }
    }
    
    /**
     * Compute edge mask for a single bitmap (for preview/testing)
     * 
     * @param inputBitmap Input ARGB_8888 bitmap
     * @param outputMask Byte array to receive tile mask
     * @param tileSize Tile size for classification
     * @param threshold Detail threshold
     * @return Number of detail tiles, or negative on error
     */
    fun computeEdgeMask(
        inputBitmap: Bitmap,
        outputMask: ByteArray,
        tileSize: Int = 64,
        threshold: Float = 25f
    ): Int {
        return nativeComputeEdgeMask(inputBitmap, outputMask, tileSize, threshold)
    }
    
    override fun close() {
        if (nativeHandle != 0L) {
            Companion.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }
    
    protected fun finalize() {
        close()
    }
    
    // Native methods
    private external fun nativeProcessYUV(
        handle: Long,
        yPlanes: Array<ByteBuffer>,
        uPlanes: Array<ByteBuffer>,
        vPlanes: Array<ByteBuffer>,
        yRowStrides: IntArray,
        uvRowStrides: IntArray,
        uvPixelStrides: IntArray,
        width: Int,
        height: Int,
        outputBitmap: Bitmap,
        callback: NativeProgressCallback?
    ): Int
    
    private external fun nativeGetDetailMask(
        handle: Long,
        outputMask: ByteArray,
        dimensions: IntArray
    ): Int
    
    private external fun nativeGetMFSRInfo(
        handle: Long,
        outputInfo: FloatArray
    ): Int
    
    private external fun nativeCancel(handle: Long)
    
    private external fun nativeComputeEdgeMask(
        inputBitmap: Bitmap,
        outputMask: ByteArray,
        tileSize: Int,
        threshold: Float
    ): Int
    
    companion object {
        private var libraryLoaded = false
        private var libraryLoadError: String? = null
        
        init {
            try {
                System.loadLibrary("ultradetail")
                libraryLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                libraryLoadError = "Native library not available for this device architecture: ${e.message}"
                android.util.Log.e("NativeBurstProcessor", libraryLoadError!!)
            }
        }
        
        /**
         * Check if native library is available
         */
        fun isAvailable(): Boolean = libraryLoaded
        
        /**
         * Get library load error message if any
         */
        fun getLoadError(): String? = libraryLoadError
        
        /**
         * Create a new burst processor with default parameters
         * @throws IllegalStateException if native library is not available
         */
        fun create(params: BurstProcessorParams = BurstProcessorParams()): NativeBurstProcessor {
            if (!libraryLoaded) {
                throw IllegalStateException(libraryLoadError ?: "Native library not loaded")
            }
            val handle = nativeCreate(
                params.alignmentTileSize,
                params.searchRadius,
                params.pyramidLevels,
                params.mergeMethod.value,
                params.trimRatio,
                params.applyWiener,
                params.detailTileSize,
                params.detailThreshold,
                params.enableMFSR,
                params.mfsrScaleFactor
            )
            return NativeBurstProcessor(handle)
        }
        
        @JvmStatic
        private external fun nativeCreate(
            alignmentTileSize: Int,
            searchRadius: Int,
            pyramidLevels: Int,
            mergeMethod: Int,
            trimRatio: Float,
            applyWiener: Boolean,
            detailTileSize: Int,
            detailThreshold: Float,
            enableMFSR: Boolean,
            mfsrScaleFactor: Int
        ): Long
        
        @JvmStatic
        private external fun nativeDestroy(handle: Long)
    }
}
