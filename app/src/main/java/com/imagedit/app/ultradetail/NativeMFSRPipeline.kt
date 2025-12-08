/**
 * NativeMFSRPipeline.kt - Kotlin wrapper for native TiledMFSRPipeline
 * 
 * Provides a Kotlin-friendly interface to the C++ tile-based MFSR pipeline.
 */

package com.imagedit.app.ultradetail

import android.graphics.Bitmap
import android.util.Log

private const val TAG = "NativeMFSRPipeline"

/**
 * Robustness method for outlier rejection in MFSR
 */
enum class MFSRRobustness(val value: Int) {
    NONE(0),    // Simple averaging
    HUBER(1),   // Mild outlier rejection
    TUKEY(2)    // Aggressive outlier rejection
}

/**
 * MFSR pipeline configuration
 */
data class NativeMFSRConfig(
    val tileWidth: Int = 256,
    val tileHeight: Int = 256,
    val overlap: Int = 32,
    val scaleFactor: Int = 2,
    val robustness: MFSRRobustness = MFSRRobustness.TUKEY,
    val robustnessThreshold: Float = 0.1f,
    val useGyroInit: Boolean = true
)

/**
 * Progress callback for MFSR processing
 */
interface MFSRProgressCallback {
    fun onProgress(tilesProcessed: Int, totalTiles: Int, message: String, progress: Float)
}

/**
 * Native MFSR pipeline wrapper
 * 
 * Wraps the C++ TiledMFSRPipeline for Kotlin usage.
 */
class NativeMFSRPipeline private constructor(
    private var nativeHandle: Long
) : AutoCloseable {
    
    /**
     * Process bitmaps through the MFSR pipeline
     * 
     * @param inputBitmaps Array of input bitmaps (burst frames)
     * @param referenceIndex Index of the reference frame
     * @param homographies Optional gyro homographies (9 floats per frame, flattened)
     * @param outputBitmap Pre-allocated output bitmap (scaled size)
     * @param progressCallback Optional progress callback
     * @return Result code (0 = success)
     */
    fun processBitmaps(
        inputBitmaps: Array<Bitmap>,
        referenceIndex: Int = inputBitmaps.size / 2,
        homographies: FloatArray? = null,
        outputBitmap: Bitmap,
        progressCallback: MFSRProgressCallback? = null
    ): Int {
        check(nativeHandle != 0L) { "Pipeline has been destroyed" }
        
        return nativeProcessBitmaps(
            nativeHandle,
            inputBitmaps,
            referenceIndex,
            homographies,
            outputBitmap,
            progressCallback
        )
    }
    
    /**
     * Process bitmaps with Homography objects
     */
    fun processBitmaps(
        inputBitmaps: Array<Bitmap>,
        referenceIndex: Int = inputBitmaps.size / 2,
        homographies: List<Homography>?,
        outputBitmap: Bitmap,
        progressCallback: MFSRProgressCallback? = null
    ): Int {
        // Convert Homography objects to flat float array
        val homArray = homographies?.let { homs ->
            FloatArray(homs.size * 9) { i ->
                val homIdx = i / 9
                val elemIdx = i % 9
                val h = homs[homIdx]
                when (elemIdx) {
                    0 -> h.m00
                    1 -> h.m01
                    2 -> h.m02
                    3 -> h.m10
                    4 -> h.m11
                    5 -> h.m12
                    6 -> h.m20
                    7 -> h.m21
                    8 -> h.m22
                    else -> 0f
                }
            }
        }
        
        return processBitmaps(inputBitmaps, referenceIndex, homArray, outputBitmap, progressCallback)
    }
    
    override fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
            Log.d(TAG, "NativeMFSRPipeline closed")
        }
    }
    
    protected fun finalize() {
        close()
    }
    
    // Native methods
    private external fun nativeProcessBitmaps(
        handle: Long,
        inputBitmaps: Array<Bitmap>,
        referenceIndex: Int,
        homographies: FloatArray?,
        outputBitmap: Bitmap,
        callback: MFSRProgressCallback?
    ): Int
    
    private external fun nativeGetResultInfo(
        handle: Long,
        outputInfo: FloatArray
    ): Int
    
    companion object {
        init {
            System.loadLibrary("ultradetail")
        }
        
        /**
         * Create a new MFSR pipeline
         */
        fun create(config: NativeMFSRConfig = NativeMFSRConfig()): NativeMFSRPipeline {
            val handle = nativeCreate(
                config.tileWidth,
                config.tileHeight,
                config.overlap,
                config.scaleFactor,
                config.robustness.value,
                config.robustnessThreshold,
                config.useGyroInit
            )
            
            Log.d(TAG, "Created NativeMFSRPipeline: tile=${config.tileWidth}x${config.tileHeight}, " +
                      "scale=${config.scaleFactor}x")
            
            return NativeMFSRPipeline(handle)
        }
        
        @JvmStatic
        private external fun nativeCreate(
            tileWidth: Int,
            tileHeight: Int,
            overlap: Int,
            scaleFactor: Int,
            robustnessMethod: Int,
            robustnessThreshold: Float,
            useGyroInit: Boolean
        ): Long
        
        @JvmStatic
        private external fun nativeDestroy(handle: Long)
    }
}
