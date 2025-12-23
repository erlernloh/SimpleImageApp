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
    data class SynthesizingTexture(val tilesProcessed: Int, val totalTiles: Int, val cpuTiles: Int, val gpuTiles: Int) : PipelineState()
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
    
    init {
        // Initialize MemoryStats with context for device RAM detection
        MemoryStats.initialize(context)
    }
    
    private val _state = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val state: StateFlow<PipelineState> = _state.asStateFlow()
    
    private var nativeProcessor: NativeBurstProcessor? = null
    private var srProcessor: SuperResolutionProcessor? = null
    
    // MFSR pipeline components (for ULTRA preset)
    private var mfsrPipeline: NativeMFSRPipeline? = null
    private var mfsrRefiner: MFSRRefiner? = null
    private var onnxSR: OnnxSuperResolution? = null  // ONNX-based Real-ESRGAN
    private val gyroHelper = GyroAlignmentHelper()
    
    // Advanced alignment components
    // NOTE: alignmentWatermark disabled - pure Kotlin NCC is O(n^4), too slow
    // private val alignmentWatermark = AlignmentWatermark()
    private val rgbQualityMask = RGBQualityMask()
    
    // Scene classification for content-aware processing
    private val sceneClassifier = SceneClassifier(context)
    
    // Device capability and thermal management
    private val deviceCapabilityManager = DeviceCapabilityManager(context)
    private var deviceCapability: DeviceCapability = DeviceCapability.unknown()
    private var currentQualityTier: QualityTier = QualityTier.QUALITY
    
    // Thermal state for UI feedback
    private val _thermalState = MutableStateFlow<ThermalStatus?>(null)
    val thermalState: StateFlow<ThermalStatus?> = _thermalState.asStateFlow()
    
    private var currentJob: Job? = null
    
    /**
     * Initialize the pipeline
     * 
     * @param preset Processing preset to use
     * @param qualityTier Optional quality tier override (for multi-tier presets)
     */
    suspend fun initialize(
        preset: UltraDetailPreset,
        qualityTier: QualityTier? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Detect device capabilities for adaptive processing
            deviceCapability = deviceCapabilityManager.detectCapabilities()
            currentQualityTier = qualityTier ?: when (preset) {
                UltraDetailPreset.FAST -> QualityTier.QUICK
                UltraDetailPreset.BALANCED -> QualityTier.QUALITY
                UltraDetailPreset.MAX -> QualityTier.QUALITY
                UltraDetailPreset.ULTRA -> QualityTier.MAXIMUM
            }
            
            // Get device-adjusted configuration
            val tierConfig = deviceCapability.getQualityTierConfig(currentQualityTier)
            Log.i(TAG, "Device: ${deviceCapability.tier}, Quality: ${currentQualityTier}, " +
                       "Tiles: ${tierConfig.tileSize}, Frames: ${tierConfig.frameCount}, " +
                       "Threads: ${tierConfig.threadCount}")
            
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
                // Initialize tile-based MFSR pipeline with device-adaptive tile size
                val adaptiveTileSize = tierConfig.tileSize
                // Adaptive MFSR overlap based on preset
                val mfsrOverlap = when (preset) {
                    UltraDetailPreset.FAST -> adaptiveTileSize / 16      // Minimal overlap for speed
                    UltraDetailPreset.BALANCED -> adaptiveTileSize / 12  // Balanced
                    UltraDetailPreset.MAX, UltraDetailPreset.ULTRA -> adaptiveTileSize / 8  // Quality overlap
                }
                mfsrPipeline = NativeMFSRPipeline.create(NativeMFSRConfig(
                    tileWidth = adaptiveTileSize,
                    tileHeight = adaptiveTileSize,
                    overlap = mfsrOverlap,
                    scaleFactor = 2,
                    robustness = MFSRRobustness.HUBER,  // HUBER is gentler than TUKEY for low-diversity frames
                    robustnessThreshold = 0.8f,  // Higher threshold allows more frame contribution
                    useGyroInit = true
                ))
                
                // Initialize neural refiner only for ULTRA preset
                if (preset == UltraDetailPreset.ULTRA) {
                    // Try ONNX-based Real-ESRGAN first (better quality)
                    var onnxInitialized = false
                    
                    // Check if model is downloaded
                    val modelDownloader = ModelDownloader(context)
                    val modelPath = modelDownloader.getModelPath(AvailableModels.REAL_ESRGAN_X4_FP16)
                    
                    if (modelPath != null) {
                        // Model is downloaded, use it
                        try {
                            onnxSR = OnnxSuperResolution(context, OnnxSRConfig(
                                model = OnnxSRModel.REAL_ESRGAN_X4,
                                tileSize = 256,
                                overlap = 16,
                                useGpu = true,
                                numThreads = 4,
                                modelFilePath = modelPath
                            ))
                            
                            if (onnxSR!!.initialize()) {
                                onnxInitialized = true
                                Log.i(TAG, "ONNX Real-ESRGAN initialized from downloaded model: $modelPath")
                            } else {
                                Log.w(TAG, "ONNX Real-ESRGAN initialization failed, falling back to TFLite")
                                onnxSR?.close()
                                onnxSR = null
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "ONNX Real-ESRGAN failed: ${e.message}, falling back to TFLite")
                            onnxSR?.close()
                            onnxSR = null
                        }
                    } else {
                        // Model not downloaded, try loading from assets (if bundled)
                        try {
                            onnxSR = OnnxSuperResolution(context, OnnxSRConfig(
                                model = OnnxSRModel.REAL_ESRGAN_X4,
                                tileSize = 256,
                                overlap = 16,
                                useGpu = true,
                                numThreads = 4
                            ))
                            
                            if (onnxSR!!.initialize()) {
                                onnxInitialized = true
                                Log.i(TAG, "ONNX Real-ESRGAN initialized from assets")
                            } else {
                                Log.i(TAG, "ONNX Real-ESRGAN not available (model not downloaded)")
                                onnxSR?.close()
                                onnxSR = null
                            }
                        } catch (e: Throwable) {
                            Log.i(TAG, "ONNX Real-ESRGAN not available: ${e.message}")
                            onnxSR?.close()
                            onnxSR = null
                        }
                    }
                    
                    // Fall back to TFLite ESRGAN if ONNX not available
                    if (!onnxInitialized) {
                        try {
                            mfsrRefiner = MFSRRefiner(context, RefinerConfig(
                                tileSize = 128,
                                overlap = 8,  // Optimized: reduced from 16 for faster processing
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
        
        Log.i(TAG, "processHighQuality: ENTRY - frames.size=${frames.size}, preset=$preset")
        
        val startTime = System.currentTimeMillis()
        
        if (frames.isEmpty()) {
            Log.e(TAG, "processHighQuality: No frames to process!")
            _state.value = PipelineState.Error("No frames to process", null)
            return@withContext null
        }
        
        Log.d(TAG, "processHighQuality: Checking mfsrPipeline initialization")
        // Initialize MFSR pipeline for high-quality processing
        if (mfsrPipeline == null) {
            Log.w(TAG, "processHighQuality: mfsrPipeline is null, calling initialize()")
            if (!initialize(preset)) {
                Log.e(TAG, "processHighQuality: Failed to initialize pipeline")
                _state.value = PipelineState.Error("Failed to initialize pipeline", null)
                return@withContext null
            }
            Log.i(TAG, "processHighQuality: Pipeline initialized successfully")
        } else {
            Log.d(TAG, "processHighQuality: Pipeline already initialized")
        }
        
        try {
            val width = frames[0].width
            val height = frames[0].height
            
            Log.i(TAG, "processHighQuality: Processing ${frames.size} HQ frames (${width}x${height}) with preset $preset")
            
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
            
            // Lucky Imaging: Select only the sharpest frames (astronomy technique)
            // Discard motion-blurred frames to improve fusion quality
            _state.value = PipelineState.ProcessingBurst(
                ProcessingStage.ALIGNING_FRAMES, 0.05f, "Selecting sharpest frames..."
            )
            val originalReferenceFrame = frames[bestFrameIndex]
            val luckyFrames = selectLuckyFrames(frames, bestFrameIndex, preset)
            Log.i(TAG, "Lucky Imaging: Selected ${luckyFrames.size}/${frames.size} sharpest frames")
            
            // Find new index of reference frame in filtered list
            val newReferenceIndex = luckyFrames.indexOf(originalReferenceFrame)
            if (newReferenceIndex == -1) {
                Log.e(TAG, "CRITICAL: Reference frame not found in lucky frames! This should never happen.")
                return@withContext null
            }
            Log.d(TAG, "Reference frame remapped: $bestFrameIndex -> $newReferenceIndex")
            
            // Determine processing path based on preset
            return@withContext when (preset) {
                UltraDetailPreset.FAST, UltraDetailPreset.BALANCED -> {
                    // Simple merge - use best frame with optional averaging
                    processHQSimpleMerge(luckyFrames, newReferenceIndex, preset, startTime)
                }
                UltraDetailPreset.MAX, UltraDetailPreset.ULTRA -> {
                    // Full MFSR processing with Bitmaps
                    processHQWithMFSR(luckyFrames, newReferenceIndex, preset, startTime)
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
     * Single-frame enhancement fallback when MFSR can't be used (memory constraints)
     * Uses the refiner model if available, otherwise returns the original frame
     */
    private suspend fun processSingleFrameEnhancement(
        frame: HighQualityCapturedFrame,
        preset: UltraDetailPreset,
        startTime: Long
    ): UltraDetailResult {
        Log.i(TAG, "║ Single-frame enhancement mode (memory fallback)")
        
        _state.value = PipelineState.ProcessingBurst(
            ProcessingStage.MERGING_FRAMES, 0.5f, "Enhancing single frame..."
        )
        
        var resultBitmap: Bitmap = frame.bitmap
        
        // Try to apply refinement if available (ULTRA preset)
        // Priority: ONNX Real-ESRGAN > TFLite ESRGAN
        if (preset == UltraDetailPreset.ULTRA) {
            try {
                when {
                    onnxSR?.isReady() == true -> {
                        Log.i(TAG, "║ Applying Real-ESRGAN (ONNX) to single frame...")
                        val refined = onnxSR!!.upscale(frame.bitmap) { _, _, _ -> }
                        resultBitmap = refined
                        Log.i(TAG, "║ Real-ESRGAN applied: ${refined.width}x${refined.height}")
                    }
                    mfsrRefiner?.isReady() == true -> {
                        Log.i(TAG, "║ Applying TFLite ESRGAN to single frame...")
                        val refined = mfsrRefiner!!.refine(frame.bitmap) { _, _, _ -> }
                        resultBitmap = refined
                        Log.i(TAG, "║ TFLite ESRGAN applied: ${refined.width}x${refined.height}")
                    }
                    else -> {
                        Log.i(TAG, "║ No SR model available for single frame")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "║ Neural refinement failed, using original frame", e)
            }
        }
        
        val processingTime = System.currentTimeMillis() - startTime
        _state.value = PipelineState.Complete(resultBitmap, processingTime, 1)
        
        Log.i(TAG, "║ Single-frame enhancement complete: ${processingTime}ms, ${resultBitmap.width}x${resultBitmap.height}")
        Log.i(TAG, "═══════════════════════════════════════════════════════════")
        
        return UltraDetailResult(
            bitmap = resultBitmap,
            processingTimeMs = processingTime,
            framesUsed = 1,
            detailTilesCount = 0,
            srTilesProcessed = 0,
            preset = preset,
            mfsrApplied = false,
            mfsrScaleFactor = 1,
            mfsrCoveragePercent = 0f  // No MFSR was applied
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
        Log.i(TAG, "processHQWithMFSR: ENTRY - frames=${frames.size}, refIndex=$referenceIndex, preset=$preset")
        
        val pipeline = mfsrPipeline
        if (pipeline == null) {
            Log.e(TAG, "processHQWithMFSR: mfsrPipeline is NULL, returning null")
            return null
        }
        
        Log.d(TAG, "processHQWithMFSR: Pipeline OK, extracting frame dimensions")
        val width = frames[0].width
        val height = frames[0].height
        Log.i(TAG, "processHQWithMFSR: Frame dimensions: ${width}x${height}")
        
        // Smart memory management based on device capabilities and available RAM
        // Goal: Achieve full 2x resolution when possible, gracefully degrade when not
        val memStats = MemoryStats.current()
        val inputMegapixels = (width.toLong() * height.toLong()) / 1_000_000f
        val requestedOutputPixels = width.toLong() * height.toLong() * 4L  // 2x scale = 4x pixels
        
        // Estimate memory requirements:
        // - Input frames: ~4 bytes/pixel × frameCount (bitmaps already loaded)
        // - Output bitmap: ~4 bytes/pixel × outputPixels
        // - Native buffers: ~200MB for tile processing
        // - Safety margin: 200MB for system/GC
        val inputMemoryMb = (frames.size * width.toLong() * height.toLong() * 4L) / (1024 * 1024)
        val outputMemoryMb = (requestedOutputPixels * 4L) / (1024 * 1024)
        val nativeBuffersMb = 200L
        val safetyMarginMb = 200L
        val totalRequiredMb = inputMemoryMb + outputMemoryMb + nativeBuffersMb + safetyMarginMb
        
        Log.i(TAG, "║ Memory analysis: available=${memStats.availableMemoryMb}MB, " +
                   "required=${totalRequiredMb}MB (input=${inputMemoryMb}MB, output=${outputMemoryMb}MB)")
        
        val scaleFactor: Int
        val outputWidth: Int
        val outputHeight: Int
        var framesToUse = frames
        
        when {
            // Case 1: Enough memory for full resolution
            memStats.availableMemoryMb >= totalRequiredMb -> {
                scaleFactor = 2
                outputWidth = width * scaleFactor
                outputHeight = height * scaleFactor
                Log.i(TAG, "║ Memory OK: Full 2x resolution enabled")
            }
            
            // Case 2: Can achieve full resolution by reducing frame count
            // Fewer frames = less input memory, still get full output resolution
            memStats.availableMemoryMb >= (outputMemoryMb + nativeBuffersMb + safetyMarginMb + 100) -> {
                scaleFactor = 2
                outputWidth = width * scaleFactor
                outputHeight = height * scaleFactor
                
                // Calculate how many frames we can afford
                val availableForFrames = memStats.availableMemoryMb - outputMemoryMb - nativeBuffersMb - safetyMarginMb
                val frameMemoryMb = (width.toLong() * height.toLong() * 4L) / (1024 * 1024)
                val maxFrames = maxOf(3, (availableForFrames / frameMemoryMb).toInt())
                
                if (maxFrames < frames.size) {
                    // Select best frames: reference + frames with most sub-pixel diversity
                    framesToUse = selectBestFramesForMemory(frames, referenceIndex, maxFrames)
                    Log.w(TAG, "║ Memory optimization: Reduced frames from ${frames.size} to ${framesToUse.size} for full 2x output")
                }
            }
            
            // Case 3: Must reduce output resolution to fit in memory
            else -> {
                // Calculate max output size that fits in available memory
                val availableForOutput = memStats.availableMemoryMb - nativeBuffersMb - safetyMarginMb - 100
                val maxOutputPixels = (availableForOutput * 1024 * 1024) / 4
                
                if (maxOutputPixels >= width.toLong() * height.toLong()) {
                    // Can at least do 1x with multi-frame fusion
                    val safeScale = kotlin.math.sqrt(maxOutputPixels.toDouble() / (width.toLong() * height.toLong())).toFloat()
                    if (safeScale >= 1.4f) {
                        scaleFactor = 2
                        val downscaleRatio = kotlin.math.sqrt(maxOutputPixels.toDouble() / requestedOutputPixels).toFloat()
                        outputWidth = ((width * 2) * downscaleRatio).toInt()
                        outputHeight = ((height * 2) * downscaleRatio).toInt()
                        // Also reduce frames
                        framesToUse = selectBestFramesForMemory(frames, referenceIndex, 4)
                        Log.w(TAG, "║ Memory limit: Output ${outputWidth}x${outputHeight}, ${framesToUse.size} frames")
                    } else {
                        scaleFactor = 1
                        outputWidth = width
                        outputHeight = height
                        framesToUse = selectBestFramesForMemory(frames, referenceIndex, 4)
                        Log.w(TAG, "║ Memory limit: 1x scale with ${framesToUse.size} frames for fusion")
                    }
                } else {
                    // Extreme memory pressure - minimal processing
                    scaleFactor = 1
                    outputWidth = width
                    outputHeight = height
                    framesToUse = listOf(frames[referenceIndex])
                    Log.e(TAG, "║ Critical memory: Single frame only")
                }
            }
        }
        
        // Use selected frames for processing (may be reduced for memory)
        // Find new reference index in the reduced frame list
        val workingFrames = framesToUse
        val workingRefIndex = if (framesToUse === frames) {
            referenceIndex
        } else {
            // Find the original reference frame in the new list, or use first frame
            workingFrames.indexOfFirst { it === frames[referenceIndex] }.takeIf { it >= 0 } ?: 0
        }
        
        val outputMegapixels = (outputWidth * outputHeight) / 1_000_000f
        
        // Determine if we should use enhanced ULTRA features
        val useEnhancedPipeline = preset == UltraDetailPreset.ULTRA
        
        // Stage 0: Scene Classification for content-aware processing
        _state.value = PipelineState.ProcessingBurst(
            ProcessingStage.ALIGNING_FRAMES, 0.08f, "Analyzing scene content..."
        )
        val sceneClassification = sceneClassifier.classify(workingFrames[workingRefIndex].bitmap)
        Log.i(TAG, "═══════════════════════════════════════════════════════════")
        Log.i(TAG, "║ HQ MFSR PIPELINE START")
        Log.i(TAG, "║ Input: ${width}x${height} (${"%.2f".format(inputMegapixels)}MP) x ${workingFrames.size} frames (from ${frames.size})")
        Log.i(TAG, "║ Output: ${outputWidth}x${outputHeight} (${"%.2f".format(outputMegapixels)}MP) @ ${scaleFactor}x scale")
        Log.i(TAG, "║ Preset: $preset, Reference frame: $referenceIndex")
        Log.i(TAG, "║ Scene: ${sceneClassification.primaryType} (confidence=${"%.2f".format(sceneClassification.confidence)})")
        Log.i(TAG, "║ Enhanced pipeline: $useEnhancedPipeline")
        Log.i(TAG, "═══════════════════════════════════════════════════════════")
        
        // MFSR requires at least 2 frames - if we only have 1, use single-frame enhancement
        if (workingFrames.size < 2) {
            Log.w(TAG, "║ Single frame mode: MFSR requires 2+ frames, using single-frame enhancement")
            return processSingleFrameEnhancement(workingFrames[0], preset, startTime)
        }
        
        // Stage 1: Compute gyro homographies + optional ORB refinement (ULTRA)
        val stage1Start = System.currentTimeMillis()
        _state.value = PipelineState.ProcessingBurst(
            ProcessingStage.ALIGNING_FRAMES, 0.1f, "Computing alignment..."
        )
        
        var homographies = computeHomographiesFromHQFrames(workingFrames)
        
        // ULTRA: Refine alignment with ORB feature matching + Kalman fusion
        if (useEnhancedPipeline && workingFrames.size >= 2) {
            Log.i(TAG, "║ Stage 1a: ORB feature alignment refinement...")
            val orbConfig = ORBAlignmentConfig(maxKeypoints = 500, ransacThreshold = 3.0f)
            val refBitmap = workingFrames[workingRefIndex].bitmap
            
            // Collect ORB-based flow measurements for Kalman fusion
            val flowMeasurements = mutableListOf<FlowMeasurementKF>()
            val gyroMeasurements = mutableListOf<GyroMeasurementKF>()
            
            homographies = homographies.mapIndexed { idx, gyroH ->
                if (idx == workingRefIndex) {
                    gyroH // Reference stays identity
                } else {
                    try {
                        val orbResult = alignWithORB(refBitmap, workingFrames[idx].bitmap, orbConfig)
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
            if (flowMeasurements.isNotEmpty() && workingFrames.any { it.gyroSamples.isNotEmpty() }) {
                Log.i(TAG, "║ Stage 1b: Kalman fusion for motion refinement...")
                
                // Collect all gyro samples
                workingFrames.forEachIndexed { idx, frame ->
                    if (idx != workingRefIndex) {
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
                        numFrames = workingFrames.size,
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
        Log.i(TAG, "║   - Homographies: ${homographies.size} (${workingFrames.size} frames)")
        
        // Stage 1.5: Rolling Shutter Correction (ULTRA only)
        var correctedFrames = workingFrames
        var stage15Time = 0L
        
        if (useEnhancedPipeline && workingFrames.any { it.gyroSamples.isNotEmpty() }) {
            val stage15Start = System.currentTimeMillis()
            _state.value = PipelineState.ProcessingBurst(
                ProcessingStage.ALIGNING_FRAMES, 0.25f, "Correcting rolling shutter..."
            )
            
            Log.i(TAG, "║ Stage 1.5: Rolling shutter correction...")
            
            val rsConfig = RollingShutterConfig(
                readoutTimeMs = 33.0f,  // Typical for mobile sensors
                focalLengthPx = 3000f
            )
            
            correctedFrames = workingFrames.mapIndexed { idx, frame ->
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
        
        // Stage 1.7: Optional Exposure Fusion (if exposure bracketing was used)
        // This creates an HDR-like base image before MFSR processing
        var fusedBase: Bitmap? = null
        var stage17Time = 0L
        
        if (useEnhancedPipeline && workingFrames.size >= 3) {
            val stage17Start = System.currentTimeMillis()
            _state.value = PipelineState.ProcessingBurst(
                ProcessingStage.ALIGNING_FRAMES, 0.25f, "Fusing exposures for HDR detail..."
            )
            Log.i(TAG, "║ Stage 1.7: Exposure fusion for HDR detail recovery...")
            
            try {
                val fusedOutput = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val bitmapsToFuse = correctedFrames.map { it.bitmap }.toTypedArray()
                
                // Content-aware fusion weights
                val (contrastW, saturationW, exposureW) = when (sceneClassification.primaryType) {
                    SceneType.FACE -> Triple(0.8f, 1.2f, 1.0f)  // Preserve skin tones
                    SceneType.TEXT -> Triple(1.5f, 0.5f, 1.0f)  // Emphasize contrast
                    SceneType.NATURE -> Triple(1.0f, 1.5f, 1.0f) // Boost colors
                    SceneType.ARCHITECTURE -> Triple(1.2f, 0.8f, 1.0f) // Edge preservation
                    SceneType.GENERAL -> Triple(1.0f, 1.0f, 1.0f) // Balanced
                }
                
                if (fuseExposures(bitmapsToFuse, fusedOutput, contrastW, saturationW, exposureW, 5)) {
                    fusedBase = fusedOutput
                    Log.i(TAG, "║   - Exposure fusion successful (weights: C=$contrastW, S=$saturationW, E=$exposureW)")
                } else {
                    fusedOutput.recycle()
                    Log.w(TAG, "║   - Exposure fusion failed, using original frames")
                }
            } catch (e: Exception) {
                Log.w(TAG, "║   - Exposure fusion error: ${e.message}")
            }
            
            stage17Time = System.currentTimeMillis() - stage17Start
            Log.i(TAG, "║ Stage 1.7: Exposure fusion completed in ${stage17Time}ms")
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
        Log.i(TAG, "║   - Input frames: ${correctedFrames.size}")
        Log.i(TAG, "║   - Output bitmap allocated: ${outputWidth}x${outputHeight}")
        Log.i(TAG, "║   - Quality mask: ${qualityResult.width}x${qualityResult.height} passed to native")
        
        // Use corrected bitmaps for MFSR processing with quality mask for pixel weighting
        val bitmapArray = correctedFrames.map { it.bitmap }.toTypedArray()
        
        val mfsrResult = pipeline.processBitmapsWithQualityMask(
            inputBitmaps = bitmapArray,
            referenceIndex = workingRefIndex,
            homographies = homographies,
            qualityMask = qualityResult.qualityMask,
            maskWidth = qualityResult.width,
            maskHeight = qualityResult.height,
            outputBitmap = outputBitmap,
            progressCallback = object : MFSRProgressCallback {
                private var lastUpdateTime = 0L
                private var lastProgress = -1f
                
                override fun onProgress(tilesProcessed: Int, totalTiles: Int, message: String, progress: Float) {
                    val now = System.currentTimeMillis()
                    // Throttle updates to max 10/sec to prevent UI thread overload
                    // Always update on significant progress changes (>2%) or completion
                    val significantChange = kotlin.math.abs(progress - lastProgress) >= 0.02f
                    val shouldUpdate = (now - lastUpdateTime >= 100) || significantChange || progress >= 0.99f
                    
                    if (shouldUpdate) {
                        _state.value = PipelineState.ProcessingMFSR(tilesProcessed, totalTiles, progress)
                        lastUpdateTime = now
                        lastProgress = progress
                        
                        if (tilesProcessed == totalTiles || tilesProcessed % 5 == 0 || progress >= 0.9f) {
                            Log.d(TAG, "║   - MFSR progress: $tilesProcessed/$totalTiles (${"%.0f".format(progress * 100)}%) - $message")
                        }
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
                framesUsed = workingFrames.size,
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
                // Step 0: Reference-based detail transfer (NEW - Topaz Gigapixel inspired)
                // Use the sharpest original frame to transfer high-frequency detail to upscaled output
                _state.value = PipelineState.ProcessingBurst(
                    ProcessingStage.MERGING_FRAMES, 0.82f, "Transferring fine details from reference..."
                )
                Log.i(TAG, "║ Stage 3.5-pre: Reference-based detail transfer...")
                val sharpestFrame = correctedFrames[workingRefIndex].bitmap
                val refTransferOutput = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
                
                // Transfer high-frequency detail from sharpest frame
                // This extracts Laplacian (edges/texture) from reference and blends into upscaled output
                val refBlendStrength = when (preset) {
                    UltraDetailPreset.FAST -> 0.3f
                    UltraDetailPreset.BALANCED -> 0.4f
                    UltraDetailPreset.MAX, UltraDetailPreset.ULTRA -> 0.5f
                }
                
                if (transferReferenceDetail(enhancedBitmap, sharpestFrame, refTransferOutput, refBlendStrength)) {
                    if (enhancedBitmap !== outputBitmap) enhancedBitmap.recycle()
                    enhancedBitmap = refTransferOutput
                    Log.d(TAG, "║   - Reference detail transfer applied (blend=$refBlendStrength)")
                } else {
                    refTransferOutput.recycle()
                    Log.w(TAG, "║   - Reference detail transfer failed, skipping")
                }
                
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
                // Note: Drizzle works best with sub-pixel shifts. We extract the fractional
                // part of the translation to get sub-pixel offsets for interlacing.
                if (correctedFrames.size >= 3) {
                    Log.i(TAG, "║ Stage 3.5c: Drizzle sub-pixel enhancement...")
                    
                    // Use Drizzle to combine sub-pixel information from aligned frames
                    // Output size matches MFSR output (already 2x scaled)
                    val drizzleOutput = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
                    val drizzleConfig = DrizzleConfig(
                        scaleFactor = 2,
                        pixfrac = 0.8f  // Slightly larger drops for better coverage
                    )
                    
                    // Convert homographies to sub-pixel shifts
                    // Use fractional part of translation for sub-pixel interlacing
                    // If homographies are identity (no motion), use synthetic dither pattern
                    val shifts = homographies.mapIndexed { idx, h ->
                        val fracX = h.m02 - kotlin.math.floor(h.m02)
                        val fracY = h.m12 - kotlin.math.floor(h.m12)
                        
                        // Check if we have actual sub-pixel motion
                        val hasMotion = kotlin.math.abs(fracX) > 0.01 || kotlin.math.abs(fracY) > 0.01
                        
                        if (hasMotion) {
                            SubPixelShift(
                                dx = fracX.toFloat(),
                                dy = fracY.toFloat(),
                                weight = 1.0f
                            )
                        } else {
                            // Generate synthetic sub-pixel dither pattern (Bayer-like)
                            // This creates sub-pixel diversity for Drizzle when camera is stable
                            val n = correctedFrames.size
                            val angle = 2.0 * kotlin.math.PI * idx / n
                            val radius = 0.4  // Sub-pixel radius for dithering
                            SubPixelShift(
                                dx = (radius * kotlin.math.cos(angle)).toFloat(),
                                dy = (radius * kotlin.math.sin(angle)).toFloat(),
                                weight = 1.0f
                            )
                        }
                    }
                    
                    Log.d(TAG, "║   - Drizzle shifts: ${shifts.map { "(%.2f, %.2f)".format(it.dx, it.dy) }}")
                    
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
                // Phase 2: Tiled hybrid CPU-GPU processing for 3-5x additional speedup
                val outputMegapixels = (outputWidth.toLong() * outputHeight.toLong()) / 1_000_000f
                val useTiled = outputMegapixels > 20f
                
                // Quick quality check: analyze if texture synthesis is beneficial
                // This prevents wasting 4+ minutes on images that already have good detail
                val qualityScore = analyzeImageQuality(enhancedBitmap)
                Log.i(TAG, "║ Stage 3.5d: Image quality score: ${"%.2f".format(qualityScore)} (0=good detail, 1=needs synthesis)")
                
                if (qualityScore < 0.3f) {
                    Log.i(TAG, "║ Stage 3.5d: Skipping texture synthesis - image already has sufficient detail")
                } else {
                    Log.i(TAG, "║ Stage 3.5d: Texture synthesis (${if (useTiled) "Phase 2 tiled" else "Phase 1"})...")
                    val texOutput = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
                    
                    // Initialize progress state for texture synthesis
                    _state.value = PipelineState.SynthesizingTexture(0, 0, 0, 0)
                    var lastTexSynthUpdate = 0L
                    
                    val success = if (useTiled) {
                        // Adaptive config based on preset for speed/quality tradeoff
                        val (tileSize, overlap, threads) = when (preset) {
                            UltraDetailPreset.FAST -> Triple(384, 48, 2)       // Faster: larger tiles, moderate overlap
                            UltraDetailPreset.BALANCED -> Triple(448, 72, 3)   // Balanced
                            UltraDetailPreset.MAX, UltraDetailPreset.ULTRA -> Triple(512, 96, 4)  // Quality: smaller tiles, maximum overlap
                        }
                        val tiledConfig = TileSynthConfig(
                            tileSize = tileSize,
                            overlap = overlap,
                            useGPU = true,
                            numCPUThreads = threads
                        )
                        // Use progress callback version for UI feedback
                        synthesizeTextureTiledWithProgress(enhancedBitmap, texOutput, tiledConfig) { completed, total, cpuTiles, gpuTiles ->
                            // Update UI immediately for first tile, then throttle to max 20/sec
                            val now = System.currentTimeMillis()
                            if (completed == 1 || now - lastTexSynthUpdate >= 50 || completed == total) {
                                _state.value = PipelineState.SynthesizingTexture(completed, total, cpuTiles, gpuTiles)
                                lastTexSynthUpdate = now
                                // Log every 10 tiles or at completion for debugging
                                if (completed % 10 == 0 || completed == total) {
                                    Log.d(TAG, "║   - Texture synthesis progress: $completed/$total tiles (${(completed * 100 / total)}%) [CPU: $cpuTiles, GPU: $gpuTiles]")
                                }
                            }
                        }
                    } else {
                        // Content-aware texture synthesis parameters
                        val (patchSize, searchRadius, blendWeight) = when (sceneClassification.primaryType) {
                            SceneType.FACE -> Triple(5, 24, 0.3f)      // Smaller patches, gentler blending
                            SceneType.TEXT -> Triple(9, 40, 0.5f)      // Larger patches, stronger detail
                            SceneType.NATURE -> Triple(7, 32, 0.4f)    // Balanced for organic textures
                            SceneType.ARCHITECTURE -> Triple(9, 36, 0.45f) // Preserve geometric patterns
                            SceneType.GENERAL -> Triple(7, 32, 0.4f)   // Default balanced
                        }
                        
                        val texConfig = TextureSynthConfig(
                            patchSize = patchSize,
                            searchRadius = searchRadius,
                            blendWeight = blendWeight
                        )
                        Log.d(TAG, "║   - Content-aware params: patch=$patchSize, search=$searchRadius, blend=$blendWeight")
                        synthesizeTexture(enhancedBitmap, texOutput, texConfig)
                    }
                    
                    if (success) {
                        if (enhancedBitmap !== outputBitmap) enhancedBitmap.recycle()
                        enhancedBitmap = texOutput
                        Log.d(TAG, "║   - Texture synthesis applied (${if (useTiled) "tiled" else "single-pass"})")
                    } else {
                        texOutput.recycle()
                        Log.w(TAG, "║   - Texture synthesis failed, skipping")
                    }
                }
                
            } catch (e: Exception) {
                Log.w(TAG, "║ Stage 3.5: Enhanced processing failed", e)
                // Continue with whatever we have
            }
            
            stage35Time = System.currentTimeMillis() - stage35Start
            Log.i(TAG, "║ Stage 3.5: Enhanced detail processing completed in ${stage35Time}ms")
        }
        
        // Stage 4: Neural refinement (optional, ULTRA preset only)
        // Priority: ONNX Real-ESRGAN > TFLite ESRGAN > skip
        val stage4Start = System.currentTimeMillis()
        val finalBitmap = if (preset == UltraDetailPreset.ULTRA) {
            when {
                // Use ONNX Real-ESRGAN if available (best quality)
                onnxSR?.isReady() == true -> {
                    Log.i(TAG, "║ Stage 4: Real-ESRGAN (ONNX) starting...")
                    _state.value = PipelineState.RefiningMFSR(0, 0)
                    
                    var lastRefineUpdate = 0L
                    try {
                        onnxSR!!.upscale(enhancedBitmap) { processed, total, _ ->
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
                        Log.w(TAG, "║ Stage 4: Real-ESRGAN failed, using enhanced output", e)
                        enhancedBitmap
                    }
                }
                // Fall back to TFLite ESRGAN
                mfsrRefiner?.isReady() == true -> {
                    Log.i(TAG, "║ Stage 4: TFLite ESRGAN starting...")
                    _state.value = PipelineState.RefiningMFSR(0, 0)
                    
                    var lastRefineUpdate = 0L
                    try {
                        mfsrRefiner!!.refine(enhancedBitmap) { processed, total, _ ->
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
                        Log.w(TAG, "║ Stage 4: TFLite refinement failed, using enhanced output", e)
                        enhancedBitmap
                    }
                }
                else -> {
                    Log.i(TAG, "║ Stage 4: No SR model available, skipping refinement")
                    enhancedBitmap
                }
            }
        } else {
            Log.i(TAG, "║ Stage 4: Neural refinement SKIPPED (preset=$preset)")
            enhancedBitmap
        }
        val stage4Time = System.currentTimeMillis() - stage4Start
        if (preset == UltraDetailPreset.ULTRA) {
            Log.i(TAG, "║ Stage 4: Neural refinement completed in ${stage4Time}ms")
        }
        
        // Cleanup temporary resources
        fusedBase?.recycle()
        
        val processingTime = System.currentTimeMillis() - startTime
        _state.value = PipelineState.Complete(finalBitmap, processingTime, frames.size)
        
        val finalMegapixels = (finalBitmap.width * finalBitmap.height) / 1_000_000f
        Log.i(TAG, "═══════════════════════════════════════════════════════════")
        Log.i(TAG, "║ HQ MFSR PIPELINE COMPLETE")
        Log.i(TAG, "║ Total time: ${processingTime}ms")
        Log.i(TAG, "║ Output: ${finalBitmap.width}x${finalBitmap.height} (${"%.2f".format(finalMegapixels)}MP)")
        Log.i(TAG, "║ Stages: Align=${stage1Time}ms, RS=${stage15Time}ms, ExposureFusion=${stage17Time}ms, Mask=${stage2Time}ms, MFSR=${stage3Time}ms, Enhance=${stage35Time}ms, Refine=${stage4Time}ms")
        Log.i(TAG, "║ Scene: ${sceneClassification.primaryType}, Frames: ${workingFrames.size}/${frames.size}")
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
            framesUsed = workingFrames.size,
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
     * Select best frames for memory-constrained processing
     * 
     * Prioritizes:
     * 1. Reference frame (always included)
     * 2. Frames with most sub-pixel diversity (different gyro offsets)
     * 3. Sharpest frames
     * 
     * @param frames All available frames
     * @param referenceIndex Index of reference frame (must be included)
     * @param maxFrames Maximum number of frames to return
     * @return Selected frames including reference
     */
    private fun selectBestFramesForMemory(
        frames: List<HighQualityCapturedFrame>,
        referenceIndex: Int,
        maxFrames: Int
    ): List<HighQualityCapturedFrame> {
        if (frames.size <= maxFrames) return frames
        if (maxFrames <= 1) return listOf(frames[referenceIndex])
        
        // Score each frame by diversity (gyro offset from reference) and sharpness
        val refGyro = computeGyroMagnitude(frames[referenceIndex].gyroSamples)
        
        val scored = frames.mapIndexed { index, frame ->
            if (index == referenceIndex) {
                // Reference always gets highest score
                Triple(index, frame, Float.MAX_VALUE)
            } else {
                val gyro = computeGyroMagnitude(frame.gyroSamples)
                val gyroDiversity = kotlin.math.abs(gyro - refGyro)  // Different offset = good for MFSR
                val sharpness = computeBitmapSharpness(frame.bitmap)
                // Combine: diversity is more important for MFSR quality
                val score = gyroDiversity * 1000f + sharpness * 0.1f
                Triple(index, frame, score)
            }
        }
        
        // Sort by score descending and take top maxFrames
        val selected = scored
            .sortedByDescending { it.third }
            .take(maxFrames)
            .map { it.second }
        
        Log.d(TAG, "Selected ${selected.size} frames for memory-constrained processing")
        return selected
    }
    
    /**
     * Lucky Imaging: Select only the sharpest frames from burst
     * 
     * Inspired by astronomy technique - discard motion-blurred frames
     * to improve multi-frame fusion quality.
     * 
     * Also applies motion rejection threshold to discard frames with
     * excessive motion (>2px estimated from gyro).
     * 
     * @param frames All captured frames
     * @param referenceIndex Index of reference frame (always included)
     * @param preset Quality preset (determines selection ratio)
     * @return Filtered list of sharpest frames
     */
    private fun selectLuckyFrames(
        frames: List<HighQualityCapturedFrame>,
        referenceIndex: Int,
        preset: UltraDetailPreset
    ): List<HighQualityCapturedFrame> {
        if (frames.size <= 3) return frames  // Too few frames to filter
        
        // Motion rejection thresholds - TIGHTENED to reduce ghosting
        // Gyro magnitude threshold (radians) - reject frames with excessive rotation
        val gyroThreshold = when (preset) {
            UltraDetailPreset.FAST -> 0.03f        // More lenient (~1.7 degrees)
            UltraDetailPreset.BALANCED -> 0.02f    // Moderate (~1.1 degrees)
            UltraDetailPreset.MAX, UltraDetailPreset.ULTRA -> 0.01f  // Very strict (~0.6 degrees) - critical for ghosting
        }
        
        // Absolute gyro magnitude threshold - reject frames with very high motion regardless of reference
        val absoluteGyroThreshold = 0.05f  // ~2.9 degrees - stricter to avoid motion blur
        
        // First pass: Reject frames with excessive motion
        val refGyro = computeGyroMagnitude(frames[referenceIndex].gyroSamples)
        val motionFiltered = frames.filterIndexed { index, frame ->
            if (index == referenceIndex) {
                true  // Always keep reference
            } else {
                val gyro = computeGyroMagnitude(frame.gyroSamples)
                val gyroDiff = kotlin.math.abs(gyro - refGyro)
                
                // Reject if: (1) too different from reference, OR (2) absolute motion too high
                if (gyroDiff > gyroThreshold) {
                    Log.d(TAG, "Motion rejection: Frame $index rejected (gyroDiff=${"%.4f".format(gyroDiff)} > ${gyroThreshold})")
                    false
                } else if (gyro > absoluteGyroThreshold) {
                    Log.d(TAG, "Motion rejection: Frame $index rejected (absoluteGyro=${"%.4f".format(gyro)} > ${absoluteGyroThreshold})")
                    false
                } else {
                    true
                }
            }
        }
        
        val rejectedCount = frames.size - motionFiltered.size
        if (rejectedCount > 0) {
            Log.i(TAG, "Motion rejection: Rejected $rejectedCount/${frames.size} frames with excessive motion")
        }
        
        if (motionFiltered.size <= 3) return motionFiltered  // Too few frames after motion rejection
        
        // Second pass: Lucky Imaging - select sharpest frames
        val selectionRatio = when (preset) {
            UltraDetailPreset.FAST -> 0.7f        // Keep 70% (faster, less strict)
            UltraDetailPreset.BALANCED -> 0.5f    // Keep 50% (balanced)
            UltraDetailPreset.MAX, UltraDetailPreset.ULTRA -> 0.4f  // Keep 40% (quality, strict)
        }
        
        val targetCount = (motionFiltered.size * selectionRatio).toInt().coerceAtLeast(3)
        
        // Score all frames by sharpness
        val scored = motionFiltered.mapIndexed { index, frame ->
            val sharpness = computeBitmapSharpness(frame.bitmap)
            Triple(index, frame, sharpness)
        }
        
        // Sort by sharpness descending
        val sortedBySharpness = scored.sortedByDescending { it.third }
        
        // Find sharpness threshold (Nth sharpest frame)
        val threshold = sortedBySharpness[targetCount - 1].third
        
        // Select frames above threshold, always include reference
        val selected = scored.filter { (index, _, sharpness) ->
            motionFiltered[index] == frames[referenceIndex] || sharpness >= threshold
        }.map { it.second }
        
        // Log statistics
        val avgSharpness = scored.map { it.third }.average()
        val selectedAvgSharpness = selected.map { frame ->
            scored.first { it.second == frame }.third
        }.average()
        
        Log.d(TAG, "Lucky Imaging: avg sharpness before=${"%.1f".format(avgSharpness)}, " +
                   "after=${"%.1f".format(selectedAvgSharpness)}, " +
                   "improvement=${"%+.1f%%".format((selectedAvgSharpness - avgSharpness) / avgSharpness * 100)}")
        
        return selected.take(targetCount.coerceAtMost(motionFiltered.size))
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
        
        // Smart memory management based on available RAM
        // YUV frames are more memory-efficient (~1.5 bytes/pixel vs 4 bytes for ARGB)
        val memStats = MemoryStats.current()
        val requestedOutputPixels = width.toLong() * height.toLong() * 4L  // 2x scale = 4x pixels
        
        // YUV memory estimate: ~1.5 bytes/pixel per frame
        val inputMemoryMb = (frames.size * width.toLong() * height.toLong() * 3L / 2L) / (1024 * 1024)
        val outputMemoryMb = (requestedOutputPixels * 4L) / (1024 * 1024)
        val nativeBuffersMb = 200L
        val safetyMarginMb = 200L
        val totalRequiredMb = inputMemoryMb + outputMemoryMb + nativeBuffersMb + safetyMarginMb
        
        Log.i(TAG, "║ Memory analysis (YUV): available=${memStats.availableMemoryMb}MB, " +
                   "required=${totalRequiredMb}MB (input=${inputMemoryMb}MB, output=${outputMemoryMb}MB)")
        
        val scaleFactor: Int
        val outputWidth: Int
        val outputHeight: Int
        var workingFrames = frames
        
        when {
            // Case 1: Enough memory for full resolution
            memStats.availableMemoryMb >= totalRequiredMb -> {
                scaleFactor = 2
                outputWidth = width * scaleFactor
                outputHeight = height * scaleFactor
                Log.i(TAG, "║ Memory OK: Full 2x resolution enabled")
            }
            
            // Case 2: Can achieve full resolution by reducing frame count
            memStats.availableMemoryMb >= (outputMemoryMb + nativeBuffersMb + safetyMarginMb + 50) -> {
                scaleFactor = 2
                outputWidth = width * scaleFactor
                outputHeight = height * scaleFactor
                
                // Calculate how many frames we can afford (YUV is ~1.5 bytes/pixel)
                val availableForFrames = memStats.availableMemoryMb - outputMemoryMb - nativeBuffersMb - safetyMarginMb
                val frameMemoryMb = (width.toLong() * height.toLong() * 3L / 2L) / (1024 * 1024)
                val maxFrames = maxOf(3, (availableForFrames / maxOf(1, frameMemoryMb)).toInt())
                
                if (maxFrames < frames.size) {
                    workingFrames = selectBestYUVFramesForMemory(frames, maxFrames)
                    Log.w(TAG, "║ Memory optimization: Reduced frames from ${frames.size} to ${workingFrames.size} for full 2x output")
                }
            }
            
            // Case 3: Must reduce output resolution
            else -> {
                val availableForOutput = memStats.availableMemoryMb - nativeBuffersMb - safetyMarginMb - 50
                val maxOutputPixels = (availableForOutput * 1024 * 1024) / 4
                
                if (maxOutputPixels >= width.toLong() * height.toLong()) {
                    val safeScale = kotlin.math.sqrt(maxOutputPixels.toDouble() / (width.toLong() * height.toLong())).toFloat()
                    if (safeScale >= 1.4f) {
                        scaleFactor = 2
                        val downscaleRatio = kotlin.math.sqrt(maxOutputPixels.toDouble() / requestedOutputPixels).toFloat()
                        outputWidth = ((width * 2) * downscaleRatio).toInt()
                        outputHeight = ((height * 2) * downscaleRatio).toInt()
                        workingFrames = selectBestYUVFramesForMemory(frames, 4)
                        Log.w(TAG, "║ Memory limit: Output ${outputWidth}x${outputHeight}, ${workingFrames.size} frames")
                    } else {
                        scaleFactor = 1
                        outputWidth = width
                        outputHeight = height
                        workingFrames = selectBestYUVFramesForMemory(frames, 4)
                        Log.w(TAG, "║ Memory limit: 1x scale with ${workingFrames.size} frames")
                    }
                } else {
                    scaleFactor = 1
                    outputWidth = width
                    outputHeight = height
                    workingFrames = frames.take(1)
                    Log.e(TAG, "║ Critical memory: Single frame only")
                }
            }
        }
        
        Log.i(TAG, "Processing ${workingFrames.size} frames with ULTRA preset: ${width}x${height} -> ${outputWidth}x${outputHeight}")
        
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
        
        val homographies = gyroHelper.computeAllHomographies(workingFrames)
        
        // Stage 2: Process through MFSR pipeline (direct YUV - saves ~360MB RAM)
        _state.value = PipelineState.ProcessingMFSR(0, 0, 0f)
        
        val outputBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        
        // Use -1 for referenceIndex to auto-select the most stable frame
        val mfsrResult = pipeline.processYUV(
            frames = workingFrames,
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
                framesUsed = workingFrames.size,
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
        _state.value = PipelineState.Complete(rotatedBitmap, processingTime, workingFrames.size)
        
        Log.i(TAG, "ULTRA pipeline complete: ${processingTime}ms, ${rotatedBitmap.width}x${rotatedBitmap.height}")
        
        return UltraDetailResult(
            bitmap = rotatedBitmap,
            processingTimeMs = processingTime,
            framesUsed = workingFrames.size,
            detailTilesCount = 0,
            srTilesProcessed = (width / 256) * (height / 256),
            preset = UltraDetailPreset.ULTRA,
            mfsrApplied = true,
            mfsrScaleFactor = scaleFactor,
            mfsrCoveragePercent = 100f
        )
    }
    
    /**
     * Select best YUV frames for memory-constrained processing
     * Prioritizes frames with diverse gyro offsets for better MFSR quality
     */
    private fun selectBestYUVFramesForMemory(
        frames: List<CapturedFrame>,
        maxFrames: Int
    ): List<CapturedFrame> {
        if (frames.size <= maxFrames) return frames
        if (maxFrames <= 1) return frames.take(1)
        
        // Score by gyro diversity - select frames with varied offsets
        val scored = frames.mapIndexed { index, frame ->
            val gyroMag = computeGyroMagnitude(frame.gyroSamples)
            Pair(frame, gyroMag)
        }
        
        // Sort by gyro magnitude and select evenly distributed frames
        val sorted = scored.sortedBy { it.second }
        val step = sorted.size.toFloat() / maxFrames
        val selected = (0 until maxFrames).map { i ->
            sorted[minOf((i * step).toInt(), sorted.lastIndex)].first
        }
        
        Log.d(TAG, "Selected ${selected.size} YUV frames for memory-constrained processing")
        return selected
    }
    
    /**
     * Generate a quick preview bitmap from high-quality captured frames.
     * Shows the user what was captured immediately, before full processing.
     * 
     * @param frames List of high-quality captured frames (Bitmap-based)
     * @return Preview bitmap (center crop of best frame), or null on failure
     */
    suspend fun generateQuickPreview(frames: List<HighQualityCapturedFrame>): Bitmap? = withContext(Dispatchers.Default) {
        if (frames.isEmpty()) return@withContext null
        
        try {
            // Select the most stable frame (lowest gyro rotation)
            val bestFrameIndex = selectBestHQFrame(frames)
            val bestFrame = frames[bestFrameIndex]
            
            // Create a smaller preview (center crop, 1/4 size)
            val previewWidth = bestFrame.width / 2
            val previewHeight = bestFrame.height / 2
            val offsetX = bestFrame.width / 4
            val offsetY = bestFrame.height / 4
            
            // Crop from the bitmap
            val preview = Bitmap.createBitmap(
                bestFrame.bitmap,
                offsetX, offsetY,
                previewWidth, previewHeight
            )
            
            Log.d(TAG, "Generated quick preview from HQ frame $bestFrameIndex: ${preview.width}x${preview.height}")
            preview
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate HQ preview", e)
            null
        }
    }
    
    /**
     * Select the best high-quality frame based on gyro stability
     */
    private fun selectBestHQFrame(frames: List<HighQualityCapturedFrame>): Int {
        if (frames.size <= 1) return 0
        
        var bestIndex = 0
        var lowestGyro = Float.MAX_VALUE
        
        frames.forEachIndexed { index, frame ->
            val gyroMag = computeGyroMagnitude(frame.gyroSamples)
            if (gyroMag < lowestGyro) {
                lowestGyro = gyroMag
                bestIndex = index
            }
        }
        
        return bestIndex
    }
    
    /**
     * Generate a quick preview bitmap from the reference frame (YUV frames).
     * Shows the user what was captured immediately, before full processing.
     * 
     * @param frames List of captured YUV frames
     * @return Preview bitmap (center crop of best frame), or null on failure
     */
    suspend fun generateQuickPreviewYUV(frames: List<CapturedFrame>): Bitmap? = withContext(Dispatchers.Default) {
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
    
    // ==================== Device Capability & Thermal Management ====================
    
    /**
     * Get current device capability
     */
    fun getDeviceCapability(): DeviceCapability = deviceCapability
    
    /**
     * Get current quality tier
     */
    fun getCurrentQualityTier(): QualityTier = currentQualityTier
    
    /**
     * Get quality tier configuration adjusted for device
     */
    fun getQualityTierConfig(tier: QualityTier): QualityTierConfig {
        return deviceCapability.getQualityTierConfig(tier)
    }
    
    /**
     * Check thermal status and wait if necessary
     * Call this periodically during long processing operations
     * 
     * @return true if processing should continue, false if aborted
     */
    suspend fun checkThermalStatus(): Boolean {
        val status = deviceCapabilityManager.getThermalStatus()
        _thermalState.value = status
        
        if (status.shouldAbort) {
            Log.e(TAG, "Thermal abort: ${status.message}")
            return false
        }
        
        if (status.shouldPause) {
            Log.w(TAG, "Thermal pause: ${status.message}")
            return deviceCapabilityManager.checkThermalAndWait()
        }
        
        return true
    }
    
    /**
     * Get recommended frame count based on device capability and quality tier
     */
    fun getRecommendedFrameCount(): Int {
        val tierConfig = deviceCapability.getQualityTierConfig(currentQualityTier)
        return tierConfig.frameCount
    }
    
    /**
     * Get estimated processing time in seconds
     */
    fun getEstimatedProcessingTime(): Int {
        val tierConfig = deviceCapability.getQualityTierConfig(currentQualityTier)
        return tierConfig.estimatedTimeSeconds
    }
    
    /**
     * Set thermal callback for UI updates
     */
    fun setThermalCallback(callback: ThermalCallback?) {
        deviceCapabilityManager.setThermalCallback(callback)
    }
    
    override fun close() {
        cancel()
        nativeProcessor?.close()
        srProcessor?.close()
        mfsrPipeline?.close()
        mfsrRefiner?.close()
        onnxSR?.close()
        nativeProcessor = null
        srProcessor = null
        mfsrPipeline = null
        mfsrRefiner = null
        onnxSR = null
    }
    
    /**
     * Analyze image quality to determine if texture synthesis is beneficial
     * Returns a score from 0.0 (image has good detail) to 1.0 (needs synthesis)
     */
    private fun analyzeImageQuality(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        val sampleStep = 32  // Sample every 32 pixels
        val varianceRadius = 3
        
        var totalVariance = 0f
        var lowDetailPixels = 0
        var sampleCount = 0
        
        for (y in varianceRadius until height - varianceRadius step sampleStep) {
            for (x in varianceRadius until width - varianceRadius step sampleStep) {
                // Compute local variance
                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var sumR2 = 0f
                var sumG2 = 0f
                var sumB2 = 0f
                var count = 0
                
                for (dy in -varianceRadius..varianceRadius) {
                    for (dx in -varianceRadius..varianceRadius) {
                        val pixel = bitmap.getPixel(x + dx, y + dy)
                        val r = ((pixel shr 16) and 0xFF) / 255f
                        val g = ((pixel shr 8) and 0xFF) / 255f
                        val b = (pixel and 0xFF) / 255f
                        
                        sumR += r
                        sumG += g
                        sumB += b
                        sumR2 += r * r
                        sumG2 += g * g
                        sumB2 += b * b
                        count++
                    }
                }
                
                val meanR = sumR / count
                val meanG = sumG / count
                val meanB = sumB / count
                val varR = (sumR2 / count) - (meanR * meanR)
                val varG = (sumG2 / count) - (meanG * meanG)
                val varB = (sumB2 / count) - (meanB * meanB)
                val variance = (varR + varG + varB) / 3f
                
                totalVariance += variance
                if (variance < 0.005f) {  // Low detail threshold
                    lowDetailPixels++
                }
                sampleCount++
            }
        }
        
        if (sampleCount == 0) return 0f
        
        val avgVariance = totalVariance / sampleCount
        val lowDetailRatio = lowDetailPixels.toFloat() / sampleCount
        
        // Score: 0.0 = image already has good detail, 1.0 = needs synthesis
        // If >40% of samples have low variance, synthesis is beneficial
        return (lowDetailRatio * 2.5f).coerceAtMost(1.0f)
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
