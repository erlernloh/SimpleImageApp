/**
 * NativeMFSRPipeline.kt - Kotlin wrapper for native TiledMFSRPipeline
 * 
 * Provides a Kotlin-friendly interface to the C++ tile-based MFSR pipeline.
 */

package com.imagedit.app.ultradetail

import android.graphics.Bitmap
import android.util.Log
import java.nio.ByteBuffer

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
    val robustness: MFSRRobustness = MFSRRobustness.HUBER,  // HUBER is gentler than TUKEY for low-diversity frames
    val robustnessThreshold: Float = 0.8f,  // Higher threshold allows more frame contribution
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
        val homArray = homographiesToFloatArray(homographies)
        return processBitmaps(inputBitmaps, referenceIndex, homArray, outputBitmap, progressCallback)
    }
    
    /**
     * Process bitmaps with quality mask for pixel weighting
     * 
     * @param inputBitmaps Array of input bitmaps (burst frames)
     * @param referenceIndex Index of the reference frame
     * @param homographies Optional gyro homographies
     * @param qualityMask Per-pixel quality weights (0-1), same size as input frames
     * @param outputBitmap Pre-allocated output bitmap (scaled size)
     * @param progressCallback Optional progress callback
     * @return Result code (0 = success)
     */
    fun processBitmapsWithQualityMask(
        inputBitmaps: Array<Bitmap>,
        referenceIndex: Int = inputBitmaps.size / 2,
        homographies: List<Homography>?,
        qualityMask: FloatArray?,
        maskWidth: Int,
        maskHeight: Int,
        outputBitmap: Bitmap,
        progressCallback: MFSRProgressCallback? = null
    ): Int {
        check(nativeHandle != 0L) { "Pipeline has been destroyed" }
        
        val homArray = homographiesToFloatArray(homographies)
        
        // If quality mask is provided, use the new native method
        if (qualityMask != null && qualityMask.isNotEmpty()) {
            Log.d(TAG, "Processing with quality mask: ${maskWidth}x${maskHeight} (${qualityMask.size} pixels)")
            return nativeProcessBitmapsWithMask(
                nativeHandle,
                inputBitmaps,
                referenceIndex,
                homArray,
                qualityMask,
                maskWidth,
                maskHeight,
                outputBitmap,
                progressCallback
            )
        } else {
            // Fall back to standard processing
            return nativeProcessBitmaps(
                nativeHandle,
                inputBitmaps,
                referenceIndex,
                homArray,
                outputBitmap,
                progressCallback
            )
        }
    }
    
    /**
     * Process YUV frames directly through the MFSR pipeline.
     * This avoids the ~360MB memory spike from converting all frames to RGB upfront.
     * 
     * @param frames List of captured frames with YUV data
     * @param referenceIndex Index of reference frame, or -1 for auto-select (lowest gyro rotation)
     * @param homographies Optional gyro homographies
     * @param outputBitmap Pre-allocated output bitmap (scaled size)
     * @param progressCallback Optional progress callback
     * @return Selected reference index on success, negative error code on failure
     */
    fun processYUV(
        frames: List<CapturedFrame>,
        referenceIndex: Int = -1,  // -1 = auto-select best frame
        homographies: List<Homography>?,
        outputBitmap: Bitmap,
        progressCallback: MFSRProgressCallback? = null
    ): Int {
        check(nativeHandle != 0L) { "Pipeline has been destroyed" }
        
        val numFrames = frames.size
        if (numFrames < 2) {
            Log.e(TAG, "Need at least 2 frames for MFSR")
            return -2
        }
        
        // Prepare YUV plane arrays
        val yPlanes = frames.map { it.yPlane }.toTypedArray()
        val uPlanes = frames.map { it.uPlane }.toTypedArray()
        val vPlanes = frames.map { it.vPlane }.toTypedArray()
        val yRowStrides = frames.map { it.yRowStride }.toIntArray()
        val uvRowStrides = frames.map { it.uvRowStride }.toIntArray()
        val uvPixelStrides = frames.map { it.uvPixelStride }.toIntArray()
        
        val width = frames[0].width
        val height = frames[0].height
        
        // Convert homographies to flat array
        val homArray = homographiesToFloatArray(homographies)
        
        // Compute gyro magnitudes for smart reference selection
        val gyroMagnitudes = frames.map { frame ->
            computeGyroMagnitude(frame.gyroSamples)
        }.toFloatArray()
        
        Log.d(TAG, "Processing $numFrames YUV frames (${width}x${height}), " +
                   "ref=${if (referenceIndex < 0) "auto" else referenceIndex}")
        
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
            referenceIndex,
            homArray,
            gyroMagnitudes,
            outputBitmap,
            progressCallback
        )
    }
    
    /**
     * Compute total gyro rotation magnitude from samples
     */
    private fun computeGyroMagnitude(samples: List<GyroSample>): Float {
        if (samples.size < 2) return 0f
        
        var totalRotation = 0f
        for (i in 1 until samples.size) {
            val dt = (samples[i].timestamp - samples[i - 1].timestamp) / 1e9f
            val avgX = (samples[i].rotationX + samples[i - 1].rotationX) * 0.5f
            val avgY = (samples[i].rotationY + samples[i - 1].rotationY) * 0.5f
            val avgZ = (samples[i].rotationZ + samples[i - 1].rotationZ) * 0.5f
            
            // Magnitude of rotation during this interval
            val mag = kotlin.math.sqrt((avgX * avgX + avgY * avgY + avgZ * avgZ).toDouble()).toFloat() * dt
            totalRotation += mag
        }
        
        return totalRotation
    }
    
    /**
     * Convert Homography list to flat float array
     */
    private fun homographiesToFloatArray(homographies: List<Homography>?): FloatArray? {
        return homographies?.let { homs ->
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
    
    private external fun nativeProcessBitmapsWithMask(
        handle: Long,
        inputBitmaps: Array<Bitmap>,
        referenceIndex: Int,
        homographies: FloatArray?,
        qualityMask: FloatArray,
        maskWidth: Int,
        maskHeight: Int,
        outputBitmap: Bitmap,
        callback: MFSRProgressCallback?
    ): Int
    
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
        referenceIndex: Int,
        homographies: FloatArray?,
        gyroMagnitudes: FloatArray,
        outputBitmap: Bitmap,
        callback: MFSRProgressCallback?
    ): Int
    
    private external fun nativeGetResultInfo(
        handle: Long,
        outputInfo: FloatArray
    ): Int
    
    companion object {
        private var libraryLoaded = false
        private var libraryLoadError: String? = null
        
        init {
            try {
                System.loadLibrary("ultradetail")
                libraryLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                libraryLoadError = "Native MFSR library not available: ${e.message}"
                Log.e(TAG, libraryLoadError!!)
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
         * Create a new MFSR pipeline
         * @throws IllegalStateException if native library is not available
         */
        fun create(config: NativeMFSRConfig = NativeMFSRConfig()): NativeMFSRPipeline {
            if (!libraryLoaded) {
                throw IllegalStateException(libraryLoadError ?: "Native MFSR library not loaded")
            }
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
        
        // ==================== Phase 1: Enhancement Functions ====================
        
        /**
         * Apply frequency separation enhancement to a bitmap
         * Separates image into low/high frequency, boosts high-freq with edge protection
         * 
         * @param input Input bitmap (ARGB_8888)
         * @param output Output bitmap (same size, ARGB_8888)
         * @param lowPassSigma Gaussian sigma for low-frequency extraction (default 2.0)
         * @param highBoost High-frequency amplification factor (default 1.5)
         * @param edgeProtection Edge protection strength 0-1 (default 0.8)
         * @param blendStrength Final blend with original 0-1 (default 1.0)
         * @return 0 on success, negative on error
         */
        @JvmStatic
        external fun nativeApplyFreqSeparation(
            input: Bitmap,
            output: Bitmap,
            lowPassSigma: Float,
            highBoost: Float,
            edgeProtection: Float,
            blendStrength: Float
        ): Int
        
        /**
         * Apply frequency separation with default parameters
         */
        fun applyFreqSeparation(
            input: Bitmap,
            output: Bitmap,
            params: FreqSeparationConfig = FreqSeparationConfig()
        ): Boolean {
            if (!libraryLoaded) return false
            val result = nativeApplyFreqSeparation(
                input, output,
                params.lowPassSigma,
                params.highBoost,
                params.edgeProtection,
                params.blendStrength
            )
            return result == 0
        }
        
        /**
         * Apply anisotropic filtering (edge-aware smoothing)
         * Blends along edges, not across them - preserves sharpness while reducing noise
         * 
         * @param input Input bitmap (ARGB_8888)
         * @param output Output bitmap (same size, ARGB_8888)
         * @param kernelSigma Base sigma for anisotropic kernel (default 1.5)
         * @param elongation Kernel elongation along edges (default 3.0)
         * @param noiseThreshold Below this, use isotropic kernel (default 0.01)
         * @return 0 on success, negative on error
         */
        @JvmStatic
        external fun nativeApplyAnisotropicFilter(
            input: Bitmap,
            output: Bitmap,
            kernelSigma: Float,
            elongation: Float,
            noiseThreshold: Float
        ): Int
        
        /**
         * Apply anisotropic filtering with default parameters
         */
        fun applyAnisotropicFilter(
            input: Bitmap,
            output: Bitmap,
            params: AnisotropicFilterConfig = AnisotropicFilterConfig()
        ): Boolean {
            if (!libraryLoaded) return false
            val result = nativeApplyAnisotropicFilter(
                input, output,
                params.kernelSigma,
                params.elongation,
                params.noiseThreshold
            )
            return result == 0
        }
        
        /**
         * Select best reference frame based on gyro stability
         */
        @JvmStatic
        external fun nativeSelectReferenceFrame(gyroMagnitudes: FloatArray): Int
        
        // ==================== Phase 2: Native Methods ====================
        
        /**
         * Align two bitmaps using ORB feature matching
         */
        @JvmStatic
        external fun nativeAlignORB(
            referenceBitmap: Bitmap,
            frameBitmap: Bitmap,
            homographyOut: FloatArray,
            maxKeypoints: Int,
            ransacThreshold: Float
        ): Int
        
        /**
         * Apply Drizzle algorithm to combine multiple frames
         */
        @JvmStatic
        external fun nativeDrizzle(
            inputBitmaps: Array<Bitmap>,
            shifts: FloatArray,
            outputBitmap: Bitmap,
            scaleFactor: Int,
            pixfrac: Float
        ): Int
        
        /**
         * Correct rolling shutter distortion using gyro data
         */
        @JvmStatic
        external fun nativeCorrectRollingShutter(
            inputBitmap: Bitmap,
            outputBitmap: Bitmap,
            gyroData: FloatArray,
            readoutTimeMs: Float,
            focalLengthPx: Float
        ): Int
        
        // ==================== Phase 3: Native Methods ====================
        
        /**
         * Fuse gyro and optical flow using Kalman filter
         */
        @JvmStatic
        external fun nativeKalmanFusion(
            gyroData: FloatArray,
            flowData: FloatArray,
            numFrames: Int,
            outputMotion: FloatArray,
            gyroWeight: Float,
            flowWeight: Float
        ): Int
        
        /**
         * Synthesize texture details for an image
         */
        @JvmStatic
        external fun nativeTextureSynthesis(
            inputBitmap: Bitmap,
            outputBitmap: Bitmap,
            patchSize: Int,
            searchRadius: Int,
            blendWeight: Float
        ): Int
        
        /**
         * Transfer texture from source to target regions
         */
        @JvmStatic
        external fun nativeTextureTransfer(
            targetBitmap: Bitmap,
            sourceBitmap: Bitmap,
            maskData: FloatArray,
            outputBitmap: Bitmap,
            blendWeight: Float
        ): Int
    }
}

/**
 * Configuration for frequency separation enhancement
 */
data class FreqSeparationConfig(
    val lowPassSigma: Float = 2.0f,      // Gaussian sigma for low-frequency
    val highBoost: Float = 1.5f,          // High-frequency amplification
    val edgeProtection: Float = 0.8f,     // Reduce boost near edges (0-1)
    val blendStrength: Float = 1.0f       // Blend with original (0-1)
)

/**
 * Configuration for anisotropic filtering
 */
data class AnisotropicFilterConfig(
    val kernelSigma: Float = 1.5f,        // Base kernel sigma
    val elongation: Float = 3.0f,         // Stretch along edges
    val noiseThreshold: Float = 0.01f     // Isotropic below this
)

// ==================== Phase 2: ORB Alignment ====================

/**
 * Configuration for ORB feature alignment
 */
data class ORBAlignmentConfig(
    val maxKeypoints: Int = 500,          // Maximum keypoints to detect
    val ransacThreshold: Float = 3.0f     // RANSAC inlier threshold (pixels)
)

/**
 * Result of ORB alignment
 */
data class ORBAlignmentResult(
    val homography: FloatArray,           // 3x3 homography matrix (row-major)
    val inlierCount: Int,                 // Number of RANSAC inliers
    val success: Boolean                  // Whether alignment succeeded
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ORBAlignmentResult) return false
        return homography.contentEquals(other.homography) && inlierCount == other.inlierCount && success == other.success
    }
    override fun hashCode(): Int = homography.contentHashCode() + inlierCount + success.hashCode()
}

/**
 * Align two bitmaps using ORB feature matching
 * 
 * @param reference Reference frame
 * @param frame Frame to align to reference
 * @param config ORB alignment configuration
 * @return Alignment result with homography matrix
 */
fun alignWithORB(
    reference: Bitmap,
    frame: Bitmap,
    config: ORBAlignmentConfig = ORBAlignmentConfig()
): ORBAlignmentResult {
    val homography = FloatArray(9)
    val inliers = NativeMFSRPipeline.nativeAlignORB(
        reference, frame, homography,
        config.maxKeypoints, config.ransacThreshold
    )
    return ORBAlignmentResult(
        homography = homography,
        inlierCount = if (inliers > 0) inliers else 0,
        success = inliers > 0
    )
}

// ==================== Phase 2: Drizzle Algorithm ====================

/**
 * Configuration for Drizzle algorithm
 */
data class DrizzleConfig(
    val scaleFactor: Int = 2,             // Output scale (2x, 3x, 4x)
    val pixfrac: Float = 0.7f             // Drop size fraction (0.1-1.0)
)

/**
 * Sub-pixel shift for a frame
 */
data class SubPixelShift(
    val dx: Float,                        // X shift in pixels
    val dy: Float,                        // Y shift in pixels
    val weight: Float = 1.0f              // Frame quality weight
)

/**
 * Apply Drizzle algorithm to combine multiple frames
 * 
 * @param frames Input frames (aligned)
 * @param shifts Sub-pixel shifts for each frame
 * @param output Pre-allocated output bitmap (scaled size)
 * @param config Drizzle configuration
 * @return true on success
 */
fun applyDrizzle(
    frames: Array<Bitmap>,
    shifts: List<SubPixelShift>,
    output: Bitmap,
    config: DrizzleConfig = DrizzleConfig()
): Boolean {
    if (frames.size != shifts.size) return false
    
    // Flatten shifts to array [dx0, dy0, w0, dx1, dy1, w1, ...]
    val shiftArray = FloatArray(shifts.size * 3)
    shifts.forEachIndexed { i, shift ->
        shiftArray[i * 3] = shift.dx
        shiftArray[i * 3 + 1] = shift.dy
        shiftArray[i * 3 + 2] = shift.weight
    }
    
    val result = NativeMFSRPipeline.nativeDrizzle(
        frames, shiftArray, output,
        config.scaleFactor, config.pixfrac
    )
    return result == 0
}

// ==================== Phase 2: Rolling Shutter Correction ====================

/**
 * Configuration for rolling shutter correction
 */
data class RollingShutterConfig(
    val readoutTimeMs: Float = 33.0f,     // Frame readout time (ms)
    val focalLengthPx: Float = 3000.0f    // Focal length in pixels
)

/**
 * Gyro sample for rolling shutter correction
 */
data class GyroSampleRS(
    val timestamp: Float,                 // Time in seconds
    val rotX: Float,                      // Angular velocity X (rad/s)
    val rotY: Float,                      // Angular velocity Y (rad/s)
    val rotZ: Float                       // Angular velocity Z (rad/s)
)

/**
 * Correct rolling shutter distortion using gyro data
 * 
 * @param input Input bitmap with RS distortion
 * @param output Output bitmap (same size)
 * @param gyroSamples Gyroscope samples during exposure
 * @param config Rolling shutter configuration
 * @return true on success
 */
fun correctRollingShutter(
    input: Bitmap,
    output: Bitmap,
    gyroSamples: List<GyroSampleRS>,
    config: RollingShutterConfig = RollingShutterConfig()
): Boolean {
    // Flatten gyro data [t0, rx0, ry0, rz0, t1, rx1, ...]
    val gyroArray = FloatArray(gyroSamples.size * 4)
    gyroSamples.forEachIndexed { i, sample ->
        gyroArray[i * 4] = sample.timestamp
        gyroArray[i * 4 + 1] = sample.rotX
        gyroArray[i * 4 + 2] = sample.rotY
        gyroArray[i * 4 + 3] = sample.rotZ
    }
    
    val result = NativeMFSRPipeline.nativeCorrectRollingShutter(
        input, output, gyroArray,
        config.readoutTimeMs, config.focalLengthPx
    )
    return result == 0
}

// ==================== Phase 3: Kalman Fusion ====================

/**
 * Configuration for Kalman fusion
 */
data class KalmanFusionConfig(
    val gyroWeight: Float = 0.7f,         // Weight for gyro measurements
    val flowWeight: Float = 0.3f          // Weight for optical flow
)

/**
 * Gyro measurement for Kalman fusion
 */
data class GyroMeasurementKF(
    val timestamp: Float,                 // Time in seconds
    val rotX: Float,                      // Angular velocity X (rad/s)
    val rotY: Float,                      // Angular velocity Y (rad/s)
    val rotZ: Float,                      // Angular velocity Z (rad/s)
    val dt: Float                         // Time delta from previous
)

/**
 * Optical flow measurement for Kalman fusion
 */
data class FlowMeasurementKF(
    val dx: Float,                        // X displacement (pixels)
    val dy: Float,                        // Y displacement (pixels)
    val confidence: Float = 1.0f          // Measurement confidence
)

/**
 * Fused motion result
 */
data class FusedMotion(
    val x: Float,                         // Position X
    val y: Float,                         // Position Y
    val vx: Float,                        // Velocity X
    val vy: Float,                        // Velocity Y
    val uncertainty: Float                // Motion uncertainty
)

/**
 * Fuse gyro and optical flow using Kalman filter
 * 
 * @param gyroSamples All gyro samples across frames
 * @param flowMeasurements Optical flow between frame pairs
 * @param numFrames Number of frames
 * @param config Kalman fusion configuration
 * @return List of fused motion estimates
 */
fun kalmanFusion(
    gyroSamples: List<GyroMeasurementKF>,
    flowMeasurements: List<FlowMeasurementKF>,
    numFrames: Int,
    config: KalmanFusionConfig = KalmanFusionConfig()
): List<FusedMotion> {
    if (flowMeasurements.isEmpty()) return emptyList()
    
    // Flatten gyro data [t, rx, ry, rz, dt, ...]
    val gyroArray = FloatArray(gyroSamples.size * 5)
    gyroSamples.forEachIndexed { i, sample ->
        gyroArray[i * 5] = sample.timestamp
        gyroArray[i * 5 + 1] = sample.rotX
        gyroArray[i * 5 + 2] = sample.rotY
        gyroArray[i * 5 + 3] = sample.rotZ
        gyroArray[i * 5 + 4] = sample.dt
    }
    
    // Flatten flow data [dx, dy, confidence, ...]
    val flowArray = FloatArray(flowMeasurements.size * 3)
    flowMeasurements.forEachIndexed { i, flow ->
        flowArray[i * 3] = flow.dx
        flowArray[i * 3 + 1] = flow.dy
        flowArray[i * 3 + 2] = flow.confidence
    }
    
    // Output array [x, y, vx, vy, uncertainty, ...]
    val outputArray = FloatArray(flowMeasurements.size * 5)
    
    val count = NativeMFSRPipeline.nativeKalmanFusion(
        gyroArray, flowArray, numFrames, outputArray,
        config.gyroWeight, config.flowWeight
    )
    
    if (count <= 0) return emptyList()
    
    return (0 until count).map { i ->
        FusedMotion(
            x = outputArray[i * 5],
            y = outputArray[i * 5 + 1],
            vx = outputArray[i * 5 + 2],
            vy = outputArray[i * 5 + 3],
            uncertainty = outputArray[i * 5 + 4]
        )
    }
}

// ==================== Phase 3: Texture Synthesis ====================

/**
 * Configuration for texture synthesis
 */
data class TextureSynthConfig(
    val patchSize: Int = 7,               // Synthesis patch size
    val searchRadius: Int = 32,           // Search radius for patches
    val blendWeight: Float = 0.5f         // Blend weight for detail
)

/**
 * Synthesize texture details for an image
 * 
 * @param input Input bitmap (potentially lacking detail)
 * @param output Output bitmap with synthesized details
 * @param config Texture synthesis configuration
 * @return true on success
 */
fun synthesizeTexture(
    input: Bitmap,
    output: Bitmap,
    config: TextureSynthConfig = TextureSynthConfig()
): Boolean {
    val result = NativeMFSRPipeline.nativeTextureSynthesis(
        input, output,
        config.patchSize, config.searchRadius, config.blendWeight
    )
    return result == 0
}

/**
 * Transfer texture from source to target regions
 * 
 * @param target Target bitmap to enhance
 * @param source Source bitmap with texture
 * @param mask Mask indicating where to transfer (0-1 per pixel)
 * @param output Output enhanced bitmap
 * @param blendWeight Blend weight
 * @return true on success
 */
fun transferTexture(
    target: Bitmap,
    source: Bitmap,
    mask: FloatArray,
    output: Bitmap,
    blendWeight: Float = 0.5f
): Boolean {
    val result = NativeMFSRPipeline.nativeTextureTransfer(
        target, source, mask, output, blendWeight
    )
    return result == 0
}
