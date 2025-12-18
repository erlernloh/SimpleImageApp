/**
 * UltraDetailPipeline.kt - Main orchestrator for Ultra Detail+ processing
 * 
 * Coordinates burst capture, native processing, and super-resolution
 * with progress reporting and error handling.
 */

package com.imagedit.app.ultradetail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

private const val TAG = "UltraDetailPipeline"

/**
 * Ultra Detail+ processing preset
 */
enum class UltraDetailPreset {
    /** Stage 1 only: Burst merge for denoising */
    FAST,
    /** Stages 1 + 2: Burst merge + edge-aware detail mask */
    BALANCED,
    /** Stages 1 + 2 + 3: Full pipeline with super-resolution */
    MAX,
    /** Stage 4: Full MFSR pipeline with neural refinement (Ultra Detail 2.0) */
    ULTRA
}

/**
 * Pipeline processing state
 */
sealed class PipelineState {
    object Idle : PipelineState()
    data class CapturingBurst(val framesCollected: Int, val totalFrames: Int) : PipelineState()
    data class ProcessingBurst(val stage: ProcessingStage, val progress: Float, val message: String) : PipelineState()
    data class ApplyingSuperResolution(val tilesProcessed: Int, val totalTiles: Int) : PipelineState()
    data class ProcessingMFSR(val tilesProcessed: Int, val totalTiles: Int, val progress: Float) : PipelineState()
    data class RefiningMFSR(val tilesProcessed: Int, val totalTiles: Int) : PipelineState()
    data class Complete(val result: Bitmap, val processingTimeMs: Long, val framesUsed: Int = 0) : PipelineState()
    data class Error(val message: String, val fallbackResult: Bitmap?) : PipelineState()
}

/**
 * Pipeline result
 */
data class UltraDetailResult(
    val bitmap: Bitmap,
    val processingTimeMs: Long,
    val framesUsed: Int,
    val detailTilesCount: Int,
    val srTilesProcessed: Int,
    val preset: UltraDetailPreset,
    val mfsrApplied: Boolean = false,
    val mfsrScaleFactor: Int = 1,
    val mfsrCoveragePercent: Float = 0f,
    val alignmentQuality: Float = 0f  // RGB quality mask alignment percentage (0-100)
)

/**
 * Ultra Detail+ pipeline orchestrator
 * 
 * Manages the complete processing flow from burst capture to final output.
 */
class UltraDetailPipeline(
    private val context: Context
) : AutoCloseable {
    
    private val _state = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val state: StateFlow<PipelineState> = _state.asStateFlow()
    
    private var nativeProcessor: NativeBurstProcessor? = null
    private var srProcessor: SuperResolutionProcessor? = null
    
    // MFSR pipeline components (for ULTRA preset)
    private var mfsrPipeline: NativeMFSRPipeline? = null
    private var mfsrRefiner: MFSRRefiner? = null
    private val gyroHelper = GyroAlignmentHelper()
    
    // Advanced alignment components
    // NOTE: alignmentWatermark disabled - pure Kotlin NCC is O(n^4), too slow
    // private val alignmentWatermark = AlignmentWatermark()
    private val rgbQualityMask = RGBQualityMask()
    
    private var currentJob: Job? = null
    
    /**
     * Initialize the pipeline
     * 
     * @param preset Processing preset to use
     */
    suspend fun initialize(preset: UltraDetailPreset): Boolean = withContext(Dispatchers.IO) {
        try {
            // Check if native library is available
            if (!NativeMFSRPipeline.isAvailable() && (preset == UltraDetailPreset.MAX || preset == UltraDetailPreset.ULTRA)) {
                Log.e(TAG, "Native MFSR library not available: ${NativeMFSRPipeline.getLoadError()}")
                return@withContext false
            }
            if (!NativeBurstProcessor.isAvailable() && (preset == UltraDetailPreset.FAST || preset == UltraDetailPreset.BALANCED)) {
                Log.e(TAG, "Native burst processor not available: ${NativeBurstProcessor.getLoadError()}")
                return@withContext false
            }
            
            // MAX and ULTRA presets use dedicated MFSR pipeline for high-quality processing
            if (preset == UltraDetailPreset.MAX || preset == UltraDetailPreset.ULTRA) {
                // Initialize tile-based MFSR pipeline
                // Use larger tiles (512x512) to reduce processing time
                // This reduces tile count from ~20 to ~6 for a 1280x960 image
                mfsrPipeline = NativeMFSRPipeline.create(NativeMFSRConfig(
                    tileWidth = 512,
                    tileHeight = 512,
                    overlap = 64,
                    scaleFactor = 2,
                    robustness = MFSRRobustness.HUBER,  // HUBER is gentler than TUKEY for low-diversity frames
                    robustnessThreshold = 0.8f,  // Higher threshold allows more frame contribution
                    useGyroInit = true
                ))
                
                // Initialize neural refiner only for ULTRA preset
                if (preset == UltraDetailPreset.ULTRA) {
                    try {
                        mfsrRefiner = MFSRRefiner(context, RefinerConfig(
                            tileSize = 128,
                            overlap = 16,
                            useGpu = true,
                            blendStrength = 0.7f
                        ))
                        
                        if (!mfsrRefiner!!.initialize()) {
                            Log.w(TAG, "MFSR refiner initialization failed, will skip refinement")
                            mfsrRefiner = null
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "MFSR refiner creation failed: ${e.message}")
                        mfsrRefiner = null
                    }
                }
                
                Log.i(TAG, "Pipeline initialized with $preset preset (MFSR${if (preset == UltraDetailPreset.ULTRA) " + refinement" else ""})")
                return@withContext true
            }
            
            // Other presets use the original native processor
            val nativeParams = when (preset) {
                UltraDetailPreset.FAST -> BurstProcessorParams(
                    pyramidLevels = 3,
                    mergeMethod = MergeMethod.AVERAGE,
                    applyWiener = false
                )
                UltraDetailPreset.BALANCED -> BurstProcessorParams(
                    pyramidLevels = 4,
                    mergeMethod = MergeMethod.TRIMMED_MEAN,
                    applyWiener = true
                )
                UltraDetailPreset.MAX -> BurstProcessorParams(
                    pyramidLevels = 4,
                    mergeMethod = MergeMethod.TRIMMED_MEAN,
                    applyWiener = true,
                    detailTileSize = 64,
                    detailThreshold = 25f,
                    enableMFSR = true,        // Enable multi-frame super-resolution
                    mfsrScaleFactor = 2       // 2x upscale from stacked frames
                )
                UltraDetailPreset.ULTRA -> BurstProcessorParams() // Handled above
            }
            
            nativeProcessor = NativeBurstProcessor.create(nativeParams)
            
            // Initialize SR processor for MAX preset
            // Only initialize if the downloaded model is available (not the placeholder)
            if (preset == UltraDetailPreset.MAX) {
                val modelDownloader = ModelDownloader(context)
                if (modelDownloader.isModelAvailable(AvailableModels.ESRGAN_FP16)) {
                    try {
                        srProcessor = SuperResolutionProcessor(
                            context,
                            SRConfig(
                                scaleFactor = SRScaleFactor.X4,  // ESRGAN is 4x
                                tileSize = 50,                    // ESRGAN input size
                                overlap = 8,
                                acceleration = SRAcceleration.CPU,  // Use CPU to avoid GPU delegate issues
                                useExternalModel = true  // Load from downloaded file
                            )
                        )
                        
                        if (!srProcessor!!.initialize()) {
                            Log.w(TAG, "SR processor initialization failed, will use bicubic fallback")
                            srProcessor = null
                        } else {
                            Log.i(TAG, "SR processor initialized with downloaded model")
                        }
                    } catch (e: Throwable) {
                        // Catch Throwable to handle NoClassDefFoundError and other class loading issues
                        Log.w(TAG, "SR processor creation failed: ${e.message}, will use bicubic fallback")
                        srProcessor = null
                    }
                } else {
                    Log.i(TAG, "SR model not downloaded, will use bicubic 4x upscale")
                }
            }
            
            Log.i(TAG, "Pipeline initialized with preset: $preset")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline initialization failed", e)
            false
        }
    }
    
    /**
     * Set refinement blend strength (ULTRA preset only)
     * 
     * @param strength 0.0 = original MFSR output, 1.0 = fully refined
     */
    fun setRefinementStrength(strength: Float) {
        mfsrRefiner?.setBlendStrength(strength)
    }
    
    /**
     * Get current refinement blend strength
     */
    fun getRefinementStrength(): Float = mfsrRefiner?.getBlendStrength() ?: 0.7f
    
    /**
     * Process captured burst frames
     * 
     * @param frames List of captured YUV frames
     * @param preset Processing preset
     * @param scope Coroutine scope for processing
     * @return Processing result
     */
    suspend fun process(
        frames: List<CapturedFrame>,
        preset: UltraDetailPreset,
        scope: CoroutineScope
    ): UltraDetailResult? = withContext(Dispatchers.Default) {
        
        val startTime = System.currentTimeMillis()
        
        if (frames.isEmpty()) {
            _state.value = PipelineState.Error("No frames to process", null)
            return@withContext null
        }
        
        // Initialize if needed
        val needsInit = when (preset) {
            UltraDetailPreset.ULTRA -> mfsrPipeline == null
            else -> nativeProcessor == null
        }
        
        if (needsInit) {
            if (!initialize(preset)) {
                _state.value = PipelineState.Error("Failed to initialize pipeline", null)
                return@withContext null
            }
        }
        
        try {
            // Get frame dimensions
            val width = frames[0].width
            val height = frames[0].height
            
            // ULTRA preset uses dedicated MFSR pipeline
            if (preset == UltraDetailPreset.ULTRA) {
                return@withContext processUltraPreset(frames, width, height, startTime)
            }
            
            // Determine output size based on preset (MFSR for MAX preset)
            val mfsrScale = if (preset == UltraDetailPreset.MAX) 2 else 1
            val outputWidth = width * mfsrScale
            val outputHeight = height * mfsrScale
            
            Log.i(TAG, "Processing ${frames.size} frames (${width}x${height}) with preset $preset, output: ${outputWidth}x${outputHeight}")
            
            // Stage 1: Native burst processing (alignment + merge + optional MFSR)
            _state.value = PipelineState.ProcessingBurst(
                ProcessingStage.CONVERTING_YUV, 0f, "Starting burst processing..."
            )
            
            // Prepare frame data for native processing
            val yPlanes = frames.map { it.yPlane }.toTypedArray()
            val uPlanes = frames.map { it.uPlane }.toTypedArray()
            val vPlanes = frames.map { it.vPlane }.toTypedArray()
            val yRowStrides = frames.map { it.yRowStride }.toIntArray()
            val uvRowStrides = frames.map { it.uvRowStride }.toIntArray()
            val uvPixelStrides = frames.map { it.uvPixelStride }.toIntArray()
            
            // Create output bitmap (larger for MFSR)
            val mergedBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
            
            // Process with native code
            val result = nativeProcessor!!.processYUV(
                yPlanes, uPlanes, vPlanes,
                yRowStrides, uvRowStrides, uvPixelStrides,
                width, height,
                mergedBitmap
            ) { stage, progress, message ->
                _state.value = PipelineState.ProcessingBurst(stage, progress, message)
            }
            
            if (result != 0) {
                _state.value = PipelineState.Error(
                    "Native processing failed with code $result",
                    mergedBitmap
                )
                return@withContext UltraDetailResult(
                    bitmap = mergedBitmap,
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    framesUsed = frames.size,
                    detailTilesCount = 0,
                    srTilesProcessed = 0,
                    preset = preset
                )
            }
            
            // For FAST preset, return merged result with rotation
            if (preset == UltraDetailPreset.FAST) {
                val processingTime = System.currentTimeMillis() - startTime
                val rotatedBitmap = rotateBitmap(mergedBitmap, 90f)
                if (rotatedBitmap !== mergedBitmap) {
                    mergedBitmap.recycle()
                }
                _state.value = PipelineState.Complete(rotatedBitmap, processingTime, frames.size)
                
                return@withContext UltraDetailResult(
                    bitmap = rotatedBitmap,
                    processingTimeMs = processingTime,
                    framesUsed = frames.size,
                    detailTilesCount = 0,
                    srTilesProcessed = 0,
                    preset = preset
                )
            }
            
            // Stage 2: Get detail mask
            val maskTileSize = 64
            val maskTilesX = (width + maskTileSize - 1) / maskTileSize
            val maskTilesY = (height + maskTileSize - 1) / maskTileSize
            val detailMask = ByteArray(maskTilesX * maskTilesY)
            val dimensions = IntArray(2)
            
            val detailTileCount = nativeProcessor!!.getDetailMask(detailMask, dimensions)
            
            // For BALANCED preset, return merged result with rotation (mask computed but not used for SR)
            if (preset == UltraDetailPreset.BALANCED) {
                val processingTime = System.currentTimeMillis() - startTime
                val rotatedBitmap = rotateBitmap(mergedBitmap, 90f)
                if (rotatedBitmap !== mergedBitmap) {
                    mergedBitmap.recycle()
                }
                _state.value = PipelineState.Complete(rotatedBitmap, processingTime, frames.size)
                
                return@withContext UltraDetailResult(
                    bitmap = rotatedBitmap,
                    processingTimeMs = processingTime,
                    framesUsed = frames.size,
                    detailTilesCount = if (detailTileCount >= 0) detailTileCount else 0,
                    srTilesProcessed = 0,
                    preset = preset
                )
            }
            
            // Stage 3: Super-resolution (MAX preset only)
            _state.value = PipelineState.ApplyingSuperResolution(0, 0)
            
            var srTilesProcessed = 0
            
            val finalBitmap = try {
                // Skip SR if no detail tiles detected - use fast bicubic instead
                if (detailTileCount <= 0) {
                    Log.i(TAG, "No detail tiles detected, using bicubic 4x upscale (faster)")
                    Bitmap.createScaledBitmap(
                        mergedBitmap,
                        mergedBitmap.width * 4,
                        mergedBitmap.height * 4,
                        true
                    )
                } else {
                    // Only run SR on detail tiles
                    Log.i(TAG, "Processing $detailTileCount detail tiles with SR")
                    srProcessor?.process(
                        mergedBitmap,
                        detailMask,
                        maskTileSize
                    ) { processed, total, _ ->
                        srTilesProcessed = processed
                        _state.value = PipelineState.ApplyingSuperResolution(processed, total)
                    } ?: run {
                        // SR processor not available, use bicubic upscale (4x to match SR)
                        Log.w(TAG, "SR processor not available, using bicubic 4x upscale")
                        Bitmap.createScaledBitmap(
                            mergedBitmap,
                            mergedBitmap.width * 4,
                            mergedBitmap.height * 4,
                            true
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SR processing failed, using merged result", e)
                mergedBitmap
            }
            
            // Cleanup merged bitmap if we created a new one
            if (finalBitmap !== mergedBitmap) {
                mergedBitmap.recycle()
            }
            
            val processingTime = System.currentTimeMillis() - startTime
            _state.value = PipelineState.Complete(finalBitmap, processingTime, frames.size)
            
            // Apply rotation correction (camera sensor is typically rotated 90 degrees)
            val rotatedBitmap = rotateBitmap(finalBitmap, 90f)
            if (rotatedBitmap !== finalBitmap) {
                finalBitmap.recycle()
            }
            
            // Get MFSR info if available
            val mfsrInfo = nativeProcessor?.getMFSRInfo()
            
            Log.i(TAG, "Pipeline complete: ${processingTime}ms, ${rotatedBitmap.width}x${rotatedBitmap.height}, MFSR=${mfsrInfo?.applied ?: false}")
            
            UltraDetailResult(
                bitmap = rotatedBitmap,
                processingTimeMs = processingTime,
                framesUsed = frames.size,
                detailTilesCount = if (detailTileCount >= 0) detailTileCount else 0,
                srTilesProcessed = srTilesProcessed,
                preset = preset,
                mfsrApplied = mfsrInfo?.applied ?: false,
                mfsrScaleFactor = mfsrInfo?.scaleFactor ?: 1,
                mfsrCoveragePercent = mfsrInfo?.coveragePercent ?: 0f
            )
            
        } catch (e: CancellationException) {
            Log.d(TAG, "Processing cancelled")
            _state.value = PipelineState.Idle
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline processing failed", e)
            _state.value = PipelineState.Error(e.message ?: "Unknown error", null)
            null
        }
    }
    
    /**
     * Process high-quality captured frames (Bitmap-based).
     * This is the preferred path when ImageCapture is used for full-resolution capture.
     * 
     * @param frames List of high-quality captured frames (Bitmaps)
     * @param preset Processing preset
     * @param scope Coroutine scope for processing
     * @return Processing result
     */
    suspend fun processHighQuality(
        frames: List<HighQualityCapturedFrame>,
        preset: UltraDetailPreset,
        scope: CoroutineScope
    ): UltraDetailResult? = withContext(Dispatchers.Default) {
        
        val startTime = System.currentTimeMillis()
        
        if (frames.isEmpty()) {
            _state.value = PipelineState.Error("No frames to process", null)
            return@withContext null
        }
        
        // Initialize MFSR pipeline for high-quality processing
        if (mfsrPipeline == null) {
            if (!initialize(preset)) {
                _state.value = PipelineState.Error("Failed to initialize pipeline", null)
                return@withContext null
            }
        }
        
        try {
            val width = frames[0].width
            val height = frames[0].height
            
            Log.i(TAG, "Processing ${frames.size} HQ frames (${width}x${height}) with preset $preset")
            
            // Analyze frame diversity
            val frameDiversity = analyzeFrameDiversityHQ(frames)
            Log.i(TAG, "HQ MFSR frame diversity: avgGyroMag=${"%.4f".format(frameDiversity.avgGyroMagnitude)}, " +
                       "estimatedPixelShift=${"%.2f".format(frameDiversity.estimatedPixelShift)}px")
            
            if (frameDiversity.estimatedPixelShift < 0.5f) {
                Log.w(TAG, "⚠️ Low frame diversity in HQ capture! Consider more hand movement.")
            }
            
            // Select best reference frame based on sharpness and stability
            val bestFrameIndex = selectBestFrameHQ(frames)
            Log.i(TAG, "Selected HQ reference frame: $bestFrameIndex")
            
            // Determine processing path based on preset
            return@withContext when (preset) {
                UltraDetailPreset.FAST, UltraDetailPreset.BALANCED -> {
                    // Simple merge - use best frame with optional averaging
                    processHQSimpleMerge(frames, bestFrameIndex, preset, startTime)
                }
                UltraDetailPreset.MAX, UltraDetailPreset.ULTRA -> {
                    // Full MFSR processing with Bitmaps
                    processHQWithMFSR(frames, bestFrameIndex, preset, startTime)
                }
            }
            
        } catch (e: CancellationException) {
            Log.d(TAG, "HQ Processing cancelled")
            _state.value = PipelineState.Idle
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "HQ Pipeline processing failed", e)
            _state.value = PipelineState.Error(e.message ?: "Unknown error", null)
            null
        }
    }
    
    /**
     * Simple merge for FAST/BALANCED presets with high-quality frames
     */
    private suspend fun processHQSimpleMerge(
        frames: List<HighQualityCapturedFrame>,
        referenceIndex: Int,
        preset: UltraDetailPreset,
        startTime: Long
    ): UltraDetailResult {
        _state.value = PipelineState.ProcessingBurst(
            ProcessingStage.MERGING_FRAMES, 0f, "Merging high-quality frames..."
        )
        
        // For simple merge, use the best frame directly (already high quality)
        val bestFrame = frames[referenceIndex]
        
        // Create a copy of the bitmap (don't use the original which may be recycled)
        val resultBitmap = bestFrame.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        
        val processingTime = System.currentTimeMillis() - startTime
        _state.value = PipelineState.Complete(resultBitmap, processingTime, frames.size)
        
        Log.i(TAG, "HQ Simple merge complete: ${processingTime}ms, ${resultBitmap.width}x${resultBitmap.height}")
        
        return UltraDetailResult(
            bitmap = resultBitmap,
            processingTimeMs = processingTime,
            framesUsed = frames.size,
            detailTilesCount = 0,
            srTilesProcessed = 0,
            preset = preset,
            mfsrApplied = false,
            mfsrScaleFactor = 1,
            mfsrCoveragePercent = 100f
        )
    }
    
    /**
     * Full MFSR processing for MAX/ULTRA presets with high-quality frames
     * 
     * Enhanced pipeline with Phase 1-3 modules:
     * - Phase 1: Frequency separation, anisotropic merge
     * - Phase 2: ORB alignment, Drizzle, Rolling shutter correction
     * - Phase 3: Kalman fusion, Texture synthesis
     */
    private suspend fun processHQWithMFSR(
        frames: List<HighQualityCapturedFrame>,
        referenceIndex: Int,
        preset: UltraDetailPreset,
        startTime: Long
    ): UltraDetailResult? {
        val pipeline = mfsrPipeline ?: return null
        
        val width = frames[0].width
        val height = frames[0].height
        val scaleFactor = 2
        val outputWidth = width * scaleFactor
        val outputHeight = height * scaleFactor
        val inputMegapixels = (width * height) / 1_000_000f
        val outputMegapixels = (outputWidth * outputHeight) / 1_000_000f
        
        // Determine if we should use enhanced ULTRA features
        val useEnhancedPipeline = preset == UltraDetailPreset.ULTRA
        
        Log.i(TAG, "═══════════════════════════════════════════════════════════")
        Log.i(TAG, "║ HQ MFSR PIPELINE START")
        Log.i(TAG, "║ Input: ${width}x${height} (${"%.2f".format(inputMegapixels)}MP) x ${frames.size} frames")
        Log.i(TAG, "║ Output: ${outputWidth}x${outputHeight} (${"%.2f".format(outputMegapixels)}MP) @ ${scaleFactor}x scale")
        Log.i(TAG, "║ Preset: $preset, Reference frame: $referenceIndex")
        Log.i(TAG, "║ Enhanced pipeline: $useEnhancedPipeline")
        Log.i(TAG, "═══════════════════════════════════════════════════════════")
        
        // Stage 1: Compute gyro homographies + optional ORB refinement (ULTRA)
        val stage1Start = System.currentTimeMillis()
        _state.value = PipelineState.ProcessingBurst(
            ProcessingStage.ALIGNING_FRAMES, 0.1f, "Computing alignment..."
        )
        
        var homographies = computeHomographiesFromHQFrames(frames)
        
        // ULTRA: Refine alignment with ORB feature matching + Kalman fusion
        if (useEnhancedPipeline && frames.size >= 2) {
            Log.i(TAG, "║ Stage 1a: ORB feature alignment refinement...")
            val orbConfig = ORBAlignmentConfig(maxKeypoints = 500, ransacThreshold = 3.0f)
            val refBitmap = frames[referenceIndex].bitmap
            
            // Collect ORB-based flow measurements for Kalman fusion
            val flowMeasurements = mutableListOf<FlowMeasurementKF>()
            val gyroMeasurements = mutableListOf<GyroMeasurementKF>()
            
            homographies = homographies.mapIndexed { idx, gyroH ->
                if (idx == referenceIndex) {
                    gyroH // Reference stays identity
                } else {
                    try {
                        val orbResult = alignWithORB(refBitmap, frames[idx].bitmap, orbConfig)
                        if (orbResult.success && orbResult.inlierCount > 20) {
                            // Extract translation from ORB homography for Kalman fusion
                            flowMeasurements.add(FlowMeasurementKF(
                                dx = orbResult.homography[2],  // h02 = tx
                                dy = orbResult.homography[5],  // h12 = ty
                                confidence = orbResult.inlierCount.toFloat() / orbConfig.maxKeypoints
                            ))
                            
                            // Blend ORB with gyro homography (ORB more accurate for translation)
                            val blendedH = blendHomographies(gyroH, orbResult.homography, 0.7f)
                            Log.d(TAG, "║   Frame $idx: ORB refined (${orbResult.inlierCount} inliers)")
                            blendedH
                        } else {
                            // Add zero flow with low confidence
                            flowMeasurements.add(FlowMeasurementKF(0f, 0f, 0.1f))
                            Log.d(TAG, "║   Frame $idx: ORB failed, using gyro only")
                            gyroH
                        }
                    } catch (e: Exception) {
                        flowMeasurements.add(FlowMeasurementKF(0f, 0f, 0f))
                        Log.w(TAG, "║   Frame $idx: ORB error, using gyro", e)
                        gyroH
                    }
                }
            }
            
            // Stage 1b: Kalman fusion for refined motion estimation
            if (flowMeasurements.isNotEmpty() && frames.any { it.gyroSamples.isNotEmpty() }) {
                Log.i(TAG, "║ Stage 1b: Kalman fusion for motion refinement...")
                
                // Collect all gyro samples
                frames.forEachIndexed { idx, frame ->
                    if (idx != referenceIndex) {
                        frame.gyroSamples.forEachIndexed { sIdx, sample ->
                            val dt = if (sIdx > 0) {
                                (sample.timestamp - frame.gyroSamples[sIdx - 1].timestamp).toFloat() / 1_000_000_000f
                            } else 0.01f
                            
                            gyroMeasurements.add(GyroMeasurementKF(
                                timestamp = sample.timestamp.toFloat() / 1_000_000_000f,
                                rotX = sample.rotationX,
                                rotY = sample.rotationY,
                                rotZ = sample.rotationZ,
                                dt = dt
                            ))
                        }
                    }
                }
                
                try {
                    val fusedMotion = kalmanFusion(
                        gyroSamples = gyroMeasurements,
                        flowMeasurements = flowMeasurements,
                        numFrames = frames.size,
                        config = KalmanFusionConfig(gyroWeight = 0.6f, flowWeight = 0.4f)
                    )
                    
                    if (fusedMotion.isNotEmpty()) {
                        Log.d(TAG, "║   Kalman fusion: ${fusedMotion.size} motion estimates")
                        fusedMotion.forEachIndexed { i, motion ->
                            Log.d(TAG, "║     Motion $i: (${motion.x}, ${motion.y}) ± ${motion.uncertainty}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "║   Kalman fusion failed", e)
                }
            }
        }
        
        val stage1Time = System.currentTimeMillis() - stage1Start
        Log.i(TAG, "║ Stage 1: Alignment computed in ${stage1Time}ms")
        Log.i(TAG, "║   - Homographies: ${homographies.size} (${frames.size} frames)")
        
        // Stage 1.5: Rolling Shutter Correction (ULTRA only)
        var correctedFrames = frames
        var stage15Time = 0L
        
        if (useEnhancedPipeline && frames.any { it.gyroSamples.isNotEmpty() }) {
            val stage15Start = System.currentTimeMillis()
            _state.value = PipelineState.ProcessingBurst(
                ProcessingStage.ALIGNING_FRAMES, 0.25f, "Correcting rolling shutter..."
            )
            
            Log.i(TAG, "║ Stage 1.5: Rolling shutter correction...")
            
            val rsConfig = RollingShutterConfig(
                readoutTimeMs = 33.0f,  // Typical for mobile sensors
                focalLengthPx = 3000f
            )
            
            correctedFrames = frames.mapIndexed { idx, frame ->
                if (frame.gyroSamples.isEmpty()) {
                    frame // No gyro data, skip correction
                } else {
                    try {
                        val correctedBitmap = Bitmap.createBitmap(
                            frame.width, frame.height, Bitmap.Config.ARGB_8888
                        )
                        
                        // Convert gyro samples to RS format
                        val gyroSamplesRS = frame.gyroSamples.map { sample ->
                            GyroSampleRS(
                                timestamp = sample.timestamp.toFloat(),
                                rotX = sample.rotationX,
                                rotY = sample.rotationY,
                                rotZ = sample.rotationZ
                            )
                        }
                        
                        if (correctRollingShutter(frame.bitmap, correctedBitmap, gyroSamplesRS, rsConfig)) {
                            Log.d(TAG, "║   Frame $idx: RS corrected")
                            HighQualityCapturedFrame(
                                bitmap = correctedBitmap,
                                width = frame.width,
                                height = frame.height,
                                timestamp = frame.timestamp,
                                gyroSamples = frame.gyroSamples
                            )
                        } else {
                            correctedBitmap.recycle()
                            frame
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "║   Frame $idx: RS correction failed", e)
                        frame
                    }
                }
            }
            
            stage15Time = System.currentTimeMillis() - stage15Start
            Log.i(TAG, "║ Stage 1.5: Rolling shutter correction completed in ${stage15Time}ms")
        }
        
        // Stage 2: Compute RGB quality mask for pixel selection
        val stage2Start = System.currentTimeMillis()
        _state.value = PipelineState.ProcessingBurst(
            ProcessingStage.ALIGNING_FRAMES, 0.4f, "Computing RGB quality mask..."
        )
        
        val qualityResult = rgbQualityMask.computeFromMultipleFrames(correctedFrames.map { it.bitmap })
        val stage2Time = System.currentTimeMillis() - stage2Start
        Log.i(TAG, "║ Stage 2: RGB Quality Mask computed in ${stage2Time}ms")
        Log.i(TAG, "║   - Alignment: ${"%.1f".format(qualityResult.alignmentPercentage)}% pixels well-aligned")
        Log.i(TAG, "║   - Mask size: ${qualityResult.width}x${qualityResult.height} (${qualityResult.qualityMask.size} pixels)")
        
        // Log quality mask statistics
        val maskMin = qualityResult.qualityMask.minOrNull() ?: 0f
        val maskMax = qualityResult.qualityMask.maxOrNull() ?: 0f
        val maskAvg = qualityResult.qualityMask.average().toFloat()
        Log.i(TAG, "║   - Quality range: min=${"%.3f".format(maskMin)}, max=${"%.3f".format(maskMax)}, avg=${"%.3f".format(maskAvg)}")
        
        // Stage 3: Process through native MFSR pipeline with quality mask
        val stage3Start = System.currentTimeMillis()
        _state.value = PipelineState.ProcessingMFSR(0, 0, 0f)
        
        val outputBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        Log.i(TAG, "║ Stage 3: Native MFSR processing starting...")
        Log.i(TAG, "║   - Input frames: ${frames.size}")
        Log.i(TAG, "║   - Output bitmap allocated: ${outputWidth}x${outputHeight}")
        Log.i(TAG, "║   - Quality mask: ${qualityResult.width}x${qualityResult.height} passed to native")
        
        // Use corrected bitmaps for MFSR processing with quality mask for pixel weighting
        val bitmapArray = correctedFrames.map { it.bitmap }.toTypedArray()
        
        val mfsrResult = pipeline.processBitmapsWithQualityMask(
            inputBitmaps = bitmapArray,
            referenceIndex = referenceIndex,
            homographies = homographies,
            qualityMask = qualityResult.qualityMask,
            maskWidth = qualityResult.width,
            maskHeight = qualityResult.height,
            outputBitmap = outputBitmap,
            progressCallback = object : MFSRProgressCallback {
                override fun onProgress(tilesProcessed: Int, totalTiles: Int, message: String, progress: Float) {
                    _state.value = PipelineState.ProcessingMFSR(tilesProcessed, totalTiles, progress)
                    if (tilesProcessed == totalTiles || tilesProcessed % 3 == 0) {
                        Log.d(TAG, "║   - MFSR tile progress: $tilesProcessed/$totalTiles (${"%.0f".format(progress * 100)}%)")
                    }
                }
            }
        )
        val stage3Time = System.currentTimeMillis() - stage3Start
        Log.i(TAG, "║ Stage 3: Native MFSR completed in ${stage3Time}ms (result=$mfsrResult)")
        
        if (mfsrResult < 0) {
            Log.e(TAG, "HQ MFSR processing failed with code: $mfsrResult")
            _state.value = PipelineState.Error("MFSR processing failed", outputBitmap)
            return UltraDetailResult(
                bitmap = outputBitmap,
                processingTimeMs = System.currentTimeMillis() - startTime,
                framesUsed = frames.size,
                detailTilesCount = 0,
                srTilesProcessed = 0,
                preset = preset,
                mfsrApplied = false,
                mfsrScaleFactor = scaleFactor,
                mfsrCoveragePercent = 0f,
                alignmentQuality = qualityResult.alignmentPercentage
            )
        }
        
        // Stage 3.5: Enhanced detail processing (ULTRA only)
        var enhancedBitmap = outputBitmap
        var stage35Time = 0L
        
        if (useEnhancedPipeline) {
            val stage35Start = System.currentTimeMillis()
            _state.value = PipelineState.ProcessingBurst(
                ProcessingStage.MERGING_FRAMES, 0.8f, "Enhancing details..."
            )
            
            try {
                // Step 1: Frequency separation with adaptive sharpening
                Log.i(TAG, "║ Stage 3.5a: Frequency separation...")
                val freqOutput = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
                val freqConfig = FreqSeparationConfig(
                    lowPassSigma = 2.0f,
                    highBoost = 1.3f,
                    edgeProtection = 0.8f
                )
                
                if (NativeMFSRPipeline.applyFreqSeparation(enhancedBitmap, freqOutput, freqConfig)) {
                    if (enhancedBitmap !== outputBitmap) enhancedBitmap.recycle()
                    enhancedBitmap = freqOutput
                    Log.d(TAG, "║   - Frequency separation applied")
                } else {
                    freqOutput.recycle()
                    Log.w(TAG, "║   - Frequency separation failed, skipping")
                }
                
                // Step 2: Anisotropic edge-aware filtering
                Log.i(TAG, "║ Stage 3.5b: Anisotropic filtering...")
                val anisoOutput = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
                val anisoConfig = AnisotropicFilterConfig(
                    kernelSigma = 1.5f,
                    elongation = 2.5f,
                    noiseThreshold = 0.015f
                )
                
                if (NativeMFSRPipeline.applyAnisotropicFilter(enhancedBitmap, anisoOutput, anisoConfig)) {
                    if (enhancedBitmap !== outputBitmap) enhancedBitmap.recycle()
                    enhancedBitmap = anisoOutput
                    Log.d(TAG, "║   - Anisotropic filtering applied")
                } else {
                    anisoOutput.recycle()
                    Log.w(TAG, "║   - Anisotropic filtering failed, skipping")
                }
                
                // Step 3: Drizzle sub-pixel enhancement (if we have multiple aligned frames)
                if (correctedFrames.size >= 3) {
                    Log.i(TAG, "║ Stage 3.5c: Drizzle sub-pixel enhancement...")
                    
                    // Use Drizzle to combine sub-pixel information from aligned frames
                    val drizzleOutput = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
                    val drizzleConfig = DrizzleConfig(
                        scaleFactor = 2,
                        pixfrac = 0.7f
                    )
                    
                    // Convert homographies to sub-pixel shifts (extract translation component)
                    val shifts = homographies.map { h ->
                        SubPixelShift(
                            dx = h.m02,  // Translation X
                            dy = h.m12,  // Translation Y
                            weight = 1.0f
                        )
                    }
                    
                    try {
                        if (applyDrizzle(
                            correctedFrames.map { it.bitmap }.toTypedArray(),
                            shifts,
                            drizzleOutput,
                            drizzleConfig
                        )) {
                            // Blend Drizzle result with MFSR output for best of both
                            blendBitmaps(enhancedBitmap, drizzleOutput, 0.3f)
                            Log.d(TAG, "║   - Drizzle enhancement applied")
                        }
                        drizzleOutput.recycle()
                    } catch (e: Exception) {
                        drizzleOutput.recycle()
                        Log.w(TAG, "║   - Drizzle failed, skipping", e)
                    }
                }
                
                // Step 4: Texture synthesis for low-detail regions
                Log.i(TAG, "║ Stage 3.5d: Texture synthesis...")
                val texOutput = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
                val texConfig = TextureSynthConfig(
                    patchSize = 7,
                    searchRadius = 24,
                    blendWeight = 0.4f
                )
                
                if (synthesizeTexture(enhancedBitmap, texOutput, texConfig)) {
                    if (enhancedBitmap !== outputBitmap) enhancedBitmap.recycle()
                    enhancedBitmap = texOutput
                    Log.d(TAG, "║   - Texture synthesis applied")
                } else {
                    texOutput.recycle()
                    Log.w(TAG, "║   - Texture synthesis failed, skipping")
                }
                
            } catch (e: Exception) {
                Log.w(TAG, "║ Stage 3.5: Enhanced processing failed", e)
                // Continue with whatever we have
            }
            
            stage35Time = System.currentTimeMillis() - stage35Start
            Log.i(TAG, "║ Stage 3.5: Enhanced detail processing completed in ${stage35Time}ms")
        }
        
        // Stage 4: Neural refinement (optional, ULTRA preset only)
        val stage4Start = System.currentTimeMillis()
        val finalBitmap = if (mfsrRefiner?.isReady() == true && preset == UltraDetailPreset.ULTRA) {
            Log.i(TAG, "║ Stage 4: Neural refinement starting (ULTRA preset)...")
            _state.value = PipelineState.RefiningMFSR(0, 0)
            
            var lastRefineUpdate = 0L
            try {
                mfsrRefiner!!.refine(enhancedBitmap) { processed, total, _ ->
                    // Throttle UI updates to reduce main thread load (max 10/sec)
                    val now = System.currentTimeMillis()
                    if (now - lastRefineUpdate >= 100 || processed == total) {
                        _state.value = PipelineState.RefiningMFSR(processed, total)
                        lastRefineUpdate = now
                    }
                }.also {
                    if (it !== enhancedBitmap && enhancedBitmap !== outputBitmap) {
                        enhancedBitmap.recycle()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "║ Stage 4: Refinement failed, using enhanced output", e)
                enhancedBitmap
            }
        } else {
            Log.i(TAG, "║ Stage 4: Neural refinement SKIPPED (preset=$preset, refiner ready=${mfsrRefiner?.isReady()})")
            enhancedBitmap
        }
        val stage4Time = System.currentTimeMillis() - stage4Start
        if (preset == UltraDetailPreset.ULTRA) {
            Log.i(TAG, "║ Stage 4: Neural refinement completed in ${stage4Time}ms")
        }
        
        val processingTime = System.currentTimeMillis() - startTime
        _state.value = PipelineState.Complete(finalBitmap, processingTime, frames.size)
        
        val finalMegapixels = (finalBitmap.width * finalBitmap.height) / 1_000_000f
        Log.i(TAG, "═══════════════════════════════════════════════════════════")
        Log.i(TAG, "║ HQ MFSR PIPELINE COMPLETE")
        Log.i(TAG, "║ Total time: ${processingTime}ms")
        Log.i(TAG, "║ Output: ${finalBitmap.width}x${finalBitmap.height} (${"%.2f".format(finalMegapixels)}MP)")
        Log.i(TAG, "║ Stages: Align=${stage1Time}ms, RS=${stage15Time}ms, Mask=${stage2Time}ms, MFSR=${stage3Time}ms, Enhance=${stage35Time}ms, Refine=${stage4Time}ms")
        Log.i(TAG, "═══════════════════════════════════════════════════════════")
        
        // Calculate actual scale factor (ULTRA = MFSR 2x * ESRGAN 4x = 8x)
        val actualScaleFactor = if (preset == UltraDetailPreset.ULTRA && mfsrRefiner?.isReady() == true) {
            scaleFactor * mfsrRefiner!!.getScaleFactor()
        } else {
            scaleFactor
        }
        
        return UltraDetailResult(
            bitmap = finalBitmap,
            processingTimeMs = processingTime,
            framesUsed = frames.size,
            detailTilesCount = 0,
            srTilesProcessed = (width / 256) * (height / 256),
            preset = preset,
            mfsrApplied = true,
            mfsrScaleFactor = actualScaleFactor,
            mfsrCoveragePercent = 100f,
            alignmentQuality = qualityResult.alignmentPercentage
        )
    }
    
    /**
     * Compute homographies from high-quality frames using gyro data
     */
    private fun computeHomographiesFromHQFrames(frames: List<HighQualityCapturedFrame>): List<Homography> {
        if (frames.isEmpty()) return emptyList()
        
        val homographies = mutableListOf<Homography>()
        val referenceFrame = frames[0]
        
        for (frame in frames) {
            if (frame === referenceFrame) {
                homographies.add(Homography.identity())
            } else {
                // Compute homography from gyro samples
                val allSamples = mutableListOf<GyroSample>()
                allSamples.addAll(referenceFrame.gyroSamples)
                allSamples.addAll(frame.gyroSamples)
                allSamples.sortBy { it.timestamp }
                
                val rotation = gyroHelper.integrateGyroRotation(allSamples)
                val homography = gyroHelper.rotationToHomography(rotation, frame.width, frame.height)
                homographies.add(homography)
            }
        }
        
        return homographies
    }
    
    /**
     * Blend two homographies with a weight factor
     * @param gyroH Gyro-based homography
     * @param orbH ORB-based homography (as FloatArray)
     * @param orbWeight Weight for ORB homography (0-1)
     */
    private fun blendHomographies(gyroH: Homography, orbH: FloatArray, orbWeight: Float): Homography {
        val gyroWeight = 1f - orbWeight
        val blended = FloatArray(9)
        
        // Get gyro homography values
        val gyroValues = floatArrayOf(
            gyroH.m00, gyroH.m01, gyroH.m02,
            gyroH.m10, gyroH.m11, gyroH.m12,
            gyroH.m20, gyroH.m21, gyroH.m22
        )
        
        // Blend each element
        for (i in 0 until 9) {
            blended[i] = gyroValues[i] * gyroWeight + orbH[i] * orbWeight
        }
        
        // Normalize so h22 = 1
        if (kotlin.math.abs(blended[8]) > 1e-6f) {
            val scale = 1f / blended[8]
            for (i in 0 until 9) blended[i] *= scale
        }
        
        return Homography(
            blended[0], blended[1], blended[2],
            blended[3], blended[4], blended[5],
            blended[6], blended[7], blended[8]
        )
    }
    
    /**
     * Blend two bitmaps in-place (modifies target)
     * @param target Target bitmap to blend into
     * @param source Source bitmap to blend from
     * @param sourceWeight Weight for source (0-1)
     */
    private fun blendBitmaps(target: Bitmap, source: Bitmap, sourceWeight: Float) {
        if (target.width != source.width || target.height != source.height) {
            Log.w(TAG, "blendBitmaps: Size mismatch, skipping")
            return
        }
        
        val targetWeight = 1f - sourceWeight
        val width = target.width
        val height = target.height
        val pixels = IntArray(width)
        val srcPixels = IntArray(width)
        
        for (y in 0 until height) {
            target.getPixels(pixels, 0, width, 0, y, width, 1)
            source.getPixels(srcPixels, 0, width, 0, y, width, 1)
            
            for (x in 0 until width) {
                val tp = pixels[x]
                val sp = srcPixels[x]
                
                val r = ((tp shr 16 and 0xFF) * targetWeight + (sp shr 16 and 0xFF) * sourceWeight).toInt()
                val g = ((tp shr 8 and 0xFF) * targetWeight + (sp shr 8 and 0xFF) * sourceWeight).toInt()
                val b = ((tp and 0xFF) * targetWeight + (sp and 0xFF) * sourceWeight).toInt()
                val a = tp shr 24 and 0xFF
                
                pixels[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            
            target.setPixels(pixels, 0, width, 0, y, width, 1)
        }
    }
    
    /**
     * Select best frame from high-quality captures based on sharpness and gyro stability
     */
    private fun selectBestFrameHQ(frames: List<HighQualityCapturedFrame>): Int {
        if (frames.size <= 1) return 0
        
        val metrics = frames.mapIndexed { index, frame ->
            val gyroMag = computeGyroMagnitude(frame.gyroSamples)
            val sharpness = computeBitmapSharpness(frame.bitmap)
            Triple(index, gyroMag, sharpness)
        }
        
        // Normalize and score
        val maxGyro = metrics.maxOfOrNull { it.second } ?: 1f
        val minGyro = metrics.minOfOrNull { it.second } ?: 0f
        val gyroRange = (maxGyro - minGyro).coerceAtLeast(0.001f)
        
        val maxSharp = metrics.maxOfOrNull { it.third } ?: 1f
        val minSharp = metrics.minOfOrNull { it.third } ?: 0f
        val sharpRange = (maxSharp - minSharp).coerceAtLeast(0.001f)
        
        var bestIndex = 0
        var bestScore = Float.MIN_VALUE
        
        metrics.forEach { (index, gyro, sharpness) ->
            val gyroScore = 1f - (gyro - minGyro) / gyroRange
            val sharpScore = (sharpness - minSharp) / sharpRange
            val combinedScore = 0.4f * gyroScore + 0.6f * sharpScore
            
            Log.d(TAG, "HQ Frame $index: gyro=${"%.4f".format(gyro)}, sharp=${"%.1f".format(sharpness)}, score=${"%.3f".format(combinedScore)}")
            
            if (combinedScore > bestScore) {
                bestScore = combinedScore
                bestIndex = index
            }
        }
        
        return bestIndex
    }
    
    /**
     * Compute sharpness of a Bitmap using Laplacian variance
     */
    private fun computeBitmapSharpness(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        
        val sampleSize = minOf(128, width / 4, height / 4)
        val startX = (width - sampleSize) / 2
        val startY = (height - sampleSize) / 2
        
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        
        for (y in startY until (startY + sampleSize - 2) step 2) {
            for (x in startX until (startX + sampleSize - 2) step 2) {
                val center = getGrayscale(bitmap, x, y)
                val up = getGrayscale(bitmap, x, y - 1)
                val down = getGrayscale(bitmap, x, y + 1)
                val left = getGrayscale(bitmap, x - 1, y)
                val right = getGrayscale(bitmap, x + 1, y)
                
                val laplacian = 4f * center - up - down - left - right
                sum += laplacian
                sumSq += laplacian * laplacian
                count++
            }
        }
        
        if (count == 0) return 0f
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        return variance.toFloat().coerceAtLeast(0f)
    }
    
    private fun getGrayscale(bitmap: Bitmap, x: Int, y: Int): Float {
        val pixel = bitmap.getPixel(x, y)
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }
    
    /**
     * Analyze frame diversity for high-quality frames
     */
    private fun analyzeFrameDiversityHQ(frames: List<HighQualityCapturedFrame>): FrameDiversityAnalysis {
        if (frames.isEmpty()) {
            return FrameDiversityAnalysis(0f, 0f, 0f)
        }
        
        val gyroMagnitudes = frames.map { computeGyroMagnitude(it.gyroSamples) }
        val avgGyro = gyroMagnitudes.average().toFloat()
        val maxGyro = gyroMagnitudes.maxOrNull() ?: 0f
        
        val focalLengthPx = 3000f
        val estimatedPixelShift = maxGyro * focalLengthPx
        
        return FrameDiversityAnalysis(
            avgGyroMagnitude = avgGyro,
            maxGyroMagnitude = maxGyro,
            estimatedPixelShift = estimatedPixelShift
        )
    }
    
    /**
     * Process frames using ULTRA preset (MFSR + neural refinement)
     * 
     * Uses direct YUV processing to avoid ~360MB memory spike from RGB conversion.
     * Auto-selects the most stable frame (lowest gyro rotation) as reference.
     */
    private suspend fun processUltraPreset(
        frames: List<CapturedFrame>,
        width: Int,
        height: Int,
        startTime: Long
    ): UltraDetailResult? {
        val pipeline = mfsrPipeline ?: return null
        
        val scaleFactor = 2
        val outputWidth = width * scaleFactor
        val outputHeight = height * scaleFactor
        
        Log.i(TAG, "Processing ${frames.size} frames with ULTRA preset: ${width}x${height} -> ${outputWidth}x${outputHeight}")
        
        // Analyze frame diversity for MFSR effectiveness
        val frameDiversity = analyzeFrameDiversity(frames)
        Log.i(TAG, "MFSR frame diversity analysis: avgGyroMag=${"%.4f".format(frameDiversity.avgGyroMagnitude)}, " +
                   "maxGyroMag=${"%.4f".format(frameDiversity.maxGyroMagnitude)}, " +
                   "estimatedPixelShift=${"%.2f".format(frameDiversity.estimatedPixelShift)}px")
        
        if (frameDiversity.estimatedPixelShift < 0.5f) {
            Log.w(TAG, "⚠️ Low frame diversity detected! Hand movement may be insufficient for effective MFSR. " +
                       "Estimated sub-pixel shift: ${"%.2f".format(frameDiversity.estimatedPixelShift)}px (need >0.5px)")
        }
        
        // Stage 1: Compute gyro homographies
        _state.value = PipelineState.ProcessingBurst(
            ProcessingStage.ALIGNING_FRAMES, 0f, "Computing gyro alignment..."
        )
        
        val homographies = gyroHelper.computeAllHomographies(frames)
        
        // Stage 2: Process through MFSR pipeline (direct YUV - saves ~360MB RAM)
        _state.value = PipelineState.ProcessingMFSR(0, 0, 0f)
        
        val outputBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        
        // Use -1 for referenceIndex to auto-select the most stable frame
        val mfsrResult = pipeline.processYUV(
            frames = frames,
            referenceIndex = -1,  // Auto-select: lowest gyro rotation
            homographies = homographies,
            outputBitmap = outputBitmap,
            progressCallback = object : MFSRProgressCallback {
                override fun onProgress(tilesProcessed: Int, totalTiles: Int, message: String, progress: Float) {
                    _state.value = PipelineState.ProcessingMFSR(tilesProcessed, totalTiles, progress)
                }
            }
        )
        
        // mfsrResult >= 0 is the selected reference index, < 0 is error
        if (mfsrResult < 0) {
            Log.e(TAG, "MFSR processing failed with code: $mfsrResult")
            _state.value = PipelineState.Error("MFSR processing failed", outputBitmap)
            return UltraDetailResult(
                bitmap = outputBitmap,
                processingTimeMs = System.currentTimeMillis() - startTime,
                framesUsed = frames.size,
                detailTilesCount = 0,
                srTilesProcessed = 0,
                preset = UltraDetailPreset.ULTRA,
                mfsrApplied = false,
                mfsrScaleFactor = scaleFactor,
                mfsrCoveragePercent = 0f
            )
        }
        
        val selectedRefIndex = mfsrResult
        Log.d(TAG, "MFSR used reference frame $selectedRefIndex")
        
        // Stage 3: Neural refinement (optional)
        val finalBitmap = if (mfsrRefiner?.isReady() == true) {
            _state.value = PipelineState.RefiningMFSR(0, 0)
            
            var lastRefineUpdate2 = 0L
            try {
                mfsrRefiner!!.refine(outputBitmap) { processed, total, _ ->
                    // Throttle UI updates to reduce main thread load (max 10/sec)
                    val now = System.currentTimeMillis()
                    if (now - lastRefineUpdate2 >= 100 || processed == total) {
                        _state.value = PipelineState.RefiningMFSR(processed, total)
                        lastRefineUpdate2 = now
                    }
                }.also {
                    if (it !== outputBitmap) {
                        outputBitmap.recycle()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Refinement failed, using MFSR output", e)
                outputBitmap
            }
        } else {
            outputBitmap
        }
        
        // Apply rotation correction
        val rotatedBitmap = rotateBitmap(finalBitmap, 90f)
        if (rotatedBitmap !== finalBitmap) {
            finalBitmap.recycle()
        }
        
        val processingTime = System.currentTimeMillis() - startTime
        _state.value = PipelineState.Complete(rotatedBitmap, processingTime, frames.size)
        
        Log.i(TAG, "ULTRA pipeline complete: ${processingTime}ms, ${rotatedBitmap.width}x${rotatedBitmap.height}")
        
        return UltraDetailResult(
            bitmap = rotatedBitmap,
            processingTimeMs = processingTime,
            framesUsed = frames.size,
            detailTilesCount = 0,
            srTilesProcessed = (width / 256) * (height / 256),
            preset = UltraDetailPreset.ULTRA,
            mfsrApplied = true,
            mfsrScaleFactor = scaleFactor,
            mfsrCoveragePercent = 100f
        )
    }
    
    /**
     * Generate a quick preview bitmap from the reference frame.
     * Shows the user what was captured immediately, before full processing.
     * 
     * @param frames List of captured frames
     * @return Preview bitmap (center crop of best frame), or null on failure
     */
    suspend fun generateQuickPreview(frames: List<CapturedFrame>): Bitmap? = withContext(Dispatchers.Default) {
        if (frames.isEmpty()) return@withContext null
        
        try {
            // Select the most stable frame (lowest gyro rotation)
            val bestFrameIndex = selectBestFrame(frames)
            val bestFrame = frames[bestFrameIndex]
            
            // Create a smaller preview (center crop, 1/4 size)
            val previewWidth = bestFrame.width / 2
            val previewHeight = bestFrame.height / 2
            val offsetX = bestFrame.width / 4
            val offsetY = bestFrame.height / 4
            
            val preview = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
            convertYUVToBitmapCrop(bestFrame, preview, offsetX, offsetY, previewWidth, previewHeight)
            
            // Rotate to match final output orientation
            val rotated = rotateBitmap(preview, 90f)
            if (rotated !== preview) {
                preview.recycle()
            }
            
            Log.d(TAG, "Generated quick preview from frame $bestFrameIndex: ${rotated.width}x${rotated.height}")
            rotated
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate preview", e)
            null
        }
    }
    
    /**
     * Select the best frame based on gyro stability AND image sharpness
     * 
     * Combines two metrics:
     * 1. Gyro stability (lower rotation = better)
     * 2. Image sharpness (higher Laplacian variance = sharper)
     * 
     * This avoids selecting a stable but blurry frame.
     */
    private fun selectBestFrame(frames: List<CapturedFrame>): Int {
        if (frames.size <= 1) return 0
        
        // Compute metrics for each frame
        val metrics = frames.mapIndexed { index, frame ->
            val gyroMag = computeGyroMagnitude(frame.gyroSamples)
            val sharpness = computeFrameSharpness(frame)
            Triple(index, gyroMag, sharpness)
        }
        
        // Normalize metrics (0-1 range)
        val maxGyro = metrics.maxOfOrNull { it.second } ?: 1f
        val minGyro = metrics.minOfOrNull { it.second } ?: 0f
        val gyroRange = (maxGyro - minGyro).coerceAtLeast(0.001f)
        
        val maxSharp = metrics.maxOfOrNull { it.third } ?: 1f
        val minSharp = metrics.minOfOrNull { it.third } ?: 0f
        val sharpRange = (maxSharp - minSharp).coerceAtLeast(0.001f)
        
        // Score each frame: lower gyro is better, higher sharpness is better
        // Weight: 40% gyro stability, 60% sharpness (sharpness is more important for quality)
        var bestIndex = 0
        var bestScore = Float.MIN_VALUE
        
        metrics.forEach { (index, gyro, sharpness) ->
            val gyroScore = 1f - (gyro - minGyro) / gyroRange  // Invert: lower gyro = higher score
            val sharpScore = (sharpness - minSharp) / sharpRange
            val combinedScore = 0.4f * gyroScore + 0.6f * sharpScore
            
            Log.d(TAG, "Frame $index: gyro=${"%.4f".format(gyro)}, sharp=${"%.1f".format(sharpness)}, score=${"%.3f".format(combinedScore)}")
            
            if (combinedScore > bestScore) {
                bestScore = combinedScore
                bestIndex = index
            }
        }
        
        Log.i(TAG, "Selected best frame: $bestIndex (score: ${"%.3f".format(bestScore)})")
        return bestIndex
    }
    
    /**
     * Compute image sharpness using Laplacian variance on Y channel
     * Higher value = sharper image
     */
    private fun computeFrameSharpness(frame: CapturedFrame): Float {
        val yBuffer = frame.yPlane
        yBuffer.rewind()
        
        val width = frame.width
        val height = frame.height
        val rowStride = frame.yRowStride
        
        // Sample center region (avoid edges which may have vignetting)
        val sampleSize = 128
        val startX = (width - sampleSize) / 2
        val startY = (height - sampleSize) / 2
        
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        
        // Compute Laplacian (second derivative) at sampled points
        for (y in startY until (startY + sampleSize - 2) step 2) {
            for (x in startX until (startX + sampleSize - 2) step 2) {
                val idx = y * rowStride + x
                val idxUp = (y - 1) * rowStride + x
                val idxDown = (y + 1) * rowStride + x
                val idxLeft = y * rowStride + (x - 1)
                val idxRight = y * rowStride + (x + 1)
                
                // Bounds check
                if (idxUp < 0 || idxDown >= yBuffer.capacity() || 
                    idxLeft < 0 || idxRight >= yBuffer.capacity()) continue
                
                val center = (yBuffer.get(idx).toInt() and 0xFF).toFloat()
                val up = (yBuffer.get(idxUp).toInt() and 0xFF).toFloat()
                val down = (yBuffer.get(idxDown).toInt() and 0xFF).toFloat()
                val left = (yBuffer.get(idxLeft).toInt() and 0xFF).toFloat()
                val right = (yBuffer.get(idxRight).toInt() and 0xFF).toFloat()
                
                // Laplacian = 4*center - up - down - left - right
                val laplacian = 4f * center - up - down - left - right
                
                sum += laplacian
                sumSq += laplacian * laplacian
                count++
            }
        }
        
        if (count == 0) return 0f
        
        // Variance of Laplacian (higher = sharper)
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        
        return variance.toFloat().coerceAtLeast(0f)
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
            
            val mag = kotlin.math.sqrt((avgX * avgX + avgY * avgY + avgZ * avgZ).toDouble()).toFloat() * dt
            totalRotation += mag
        }
        
        return totalRotation
    }
    
    /**
     * Frame diversity analysis result
     */
    private data class FrameDiversityAnalysis(
        val avgGyroMagnitude: Float,
        val maxGyroMagnitude: Float,
        val estimatedPixelShift: Float  // Estimated sub-pixel shift in pixels
    )
    
    /**
     * Analyze frame diversity to predict MFSR effectiveness
     * 
     * MFSR requires sub-pixel shifts between frames to reconstruct higher resolution.
     * If all frames are nearly identical (no hand movement), MFSR degrades to simple averaging.
     * 
     * @return Analysis with estimated pixel shift and gyro statistics
     */
    private fun analyzeFrameDiversity(frames: List<CapturedFrame>): FrameDiversityAnalysis {
        if (frames.isEmpty()) {
            return FrameDiversityAnalysis(0f, 0f, 0f)
        }
        
        // Compute gyro magnitude for each frame
        val gyroMagnitudes = frames.map { computeGyroMagnitude(it.gyroSamples) }
        val avgGyro = gyroMagnitudes.average().toFloat()
        val maxGyro = gyroMagnitudes.maxOrNull() ?: 0f
        
        // Estimate pixel shift from gyro rotation
        // Rough approximation: rotation (rad) * focal_length (px) = pixel shift
        // Typical smartphone focal length ~3000px for 12MP sensor
        val focalLengthPx = 3000f
        val estimatedPixelShift = maxGyro * focalLengthPx
        
        return FrameDiversityAnalysis(
            avgGyroMagnitude = avgGyro,
            maxGyroMagnitude = maxGyro,
            estimatedPixelShift = estimatedPixelShift
        )
    }
    
    /**
     * Convert a cropped region of YUV CapturedFrame to RGB Bitmap
     */
    private fun convertYUVToBitmapCrop(
        frame: CapturedFrame, 
        output: Bitmap,
        offsetX: Int,
        offsetY: Int,
        cropWidth: Int,
        cropHeight: Int
    ) {
        val pixels = IntArray(cropWidth * cropHeight)
        
        val yBuffer = frame.yPlane
        val uBuffer = frame.uPlane
        val vBuffer = frame.vPlane
        
        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()
        
        for (y in 0 until cropHeight) {
            for (x in 0 until cropWidth) {
                val srcX = offsetX + x
                val srcY = offsetY + y
                
                val yIndex = srcY * frame.yRowStride + srcX
                val uvIndex = (srcY / 2) * frame.uvRowStride + (srcX / 2) * frame.uvPixelStride
                
                val yVal = (yBuffer.get(yIndex).toInt() and 0xFF) - 16
                val uVal = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
                val vVal = (vBuffer.get(uvIndex).toInt() and 0xFF) - 128
                
                var r = (1.164f * yVal + 1.596f * vVal).toInt()
                var g = (1.164f * yVal - 0.813f * vVal - 0.391f * uVal).toInt()
                var b = (1.164f * yVal + 2.018f * uVal).toInt()
                
                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)
                
                pixels[y * cropWidth + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        
        output.setPixels(pixels, 0, cropWidth, 0, 0, cropWidth, cropHeight)
    }
    
    /**
     * Convert YUV CapturedFrame to RGB Bitmap
     */
    private fun convertYUVToBitmap(frame: CapturedFrame, output: Bitmap) {
        val width = frame.width
        val height = frame.height
        val pixels = IntArray(width * height)
        
        val yBuffer = frame.yPlane
        val uBuffer = frame.uPlane
        val vBuffer = frame.vPlane
        
        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val yIndex = y * frame.yRowStride + x
                val uvIndex = (y / 2) * frame.uvRowStride + (x / 2) * frame.uvPixelStride
                
                val yVal = (yBuffer.get(yIndex).toInt() and 0xFF) - 16
                val uVal = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
                val vVal = (vBuffer.get(uvIndex).toInt() and 0xFF) - 128
                
                // YUV to RGB conversion
                var r = (1.164f * yVal + 1.596f * vVal).toInt()
                var g = (1.164f * yVal - 0.813f * vVal - 0.391f * uVal).toInt()
                var b = (1.164f * yVal + 2.018f * uVal).toInt()
                
                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)
                
                pixels[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        
        output.setPixels(pixels, 0, width, 0, 0, width, height)
    }
    
    /**
     * Cancel ongoing processing
     */
    fun cancel() {
        currentJob?.cancel()
        nativeProcessor?.cancel()
        _state.value = PipelineState.Idle
    }
    
    /**
     * Reset pipeline state
     */
    fun reset() {
        _state.value = PipelineState.Idle
    }
    
    override fun close() {
        cancel()
        nativeProcessor?.close()
        srProcessor?.close()
        mfsrPipeline?.close()
        mfsrRefiner?.close()
        nativeProcessor = null
        srProcessor = null
        mfsrPipeline = null
        mfsrRefiner = null
    }
    
    /**
     * Rotate a bitmap by the specified degrees
     */
    private fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return source
        
        val matrix = Matrix().apply {
            postRotate(degrees)
        }
        
        return Bitmap.createBitmap(
            source, 0, 0,
            source.width, source.height,
            matrix, true
        )
    }
}
