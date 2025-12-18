/**
 * MFSRPipeline.kt - Multi-Frame Super-Resolution Pipeline Orchestrator
 * 
 * Coordinates the complete MFSR pipeline:
 * 1. Burst capture with gyro data
 * 2. Gyro-based coarse alignment
 * 3. Dense optical flow alignment
 * 4. Classical MFSR (tile-based Lanczos scatter)
 * 5. Neural refinement (optional)
 * 
 * This is the main entry point for Ultra Detail+ 2.0
 */

package com.imagedit.app.ultradetail

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "MFSRPipeline"

/**
 * MFSR pipeline configuration
 */
data class MFSRPipelineConfig(
    // Capture settings
    val frameCount: Int = 8,
    val frameDelayMs: Long = 50,
    
    // Processing settings
    val scaleFactor: Int = 2,           // 2x upscale (12MP -> 48MP)
    val tileSize: Int = 256,            // Tile size for processing
    val tileOverlap: Int = 32,          // Overlap for seamless blending
    
    // Alignment settings
    val useGyroInit: Boolean = true,    // Use gyro for flow initialization
    val flowPyramidLevels: Int = 3,     // Optical flow pyramid levels
    val flowWindowSize: Int = 11,       // Lucas-Kanade window size
    
    // Robustness settings
    val robustnessMethod: RobustnessMethod = RobustnessMethod.HUBER,  // HUBER is gentler than TUKEY
    val robustnessThreshold: Float = 0.8f,  // Higher threshold allows more frame contribution
    
    // Refinement settings
    val enableRefinement: Boolean = true,
    val refinementBlendStrength: Float = 0.7f,  // 0=original, 1=fully refined
    
    // Fallback settings
    val maxAllowedMotion: Float = 50f,  // Max motion before fallback (pixels)
    val minCoverage: Float = 0.5f       // Min coverage before fallback
)

/**
 * Robustness method for outlier rejection
 */
enum class RobustnessMethod {
    NONE,   // Simple averaging
    HUBER,  // Mild outlier rejection
    TUKEY   // Aggressive outlier rejection
}

/**
 * Pipeline processing stage
 */
enum class MFSRStage(val description: String) {
    IDLE("Idle"),
    CAPTURING("Capturing burst frames"),
    COMPUTING_GYRO("Computing gyro alignment"),
    COMPUTING_FLOW("Computing optical flow"),
    MFSR_PROCESSING("Processing MFSR tiles"),
    REFINING("Applying neural refinement"),
    COMPLETE("Complete"),
    FAILED("Failed")
}

/**
 * Pipeline progress callback
 */
typealias MFSRPipelineProgressCallback = (stage: MFSRStage, progress: Float, message: String) -> Unit

/**
 * Pipeline result
 */
data class MFSRPipelineResult(
    val outputBitmap: Bitmap?,
    val inputWidth: Int,
    val inputHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val framesUsed: Int,
    val tilesProcessed: Int,
    val averageFlow: Float,
    val processingTimeMs: Long,
    val usedFallback: Boolean,
    val fallbackReason: String?,
    val success: Boolean
)

/**
 * Multi-Frame Super-Resolution Pipeline
 * 
 * Orchestrates the complete MFSR process from burst capture to refined output.
 */
class MFSRPipeline(
    private val context: Context,
    private val config: MFSRPipelineConfig = MFSRPipelineConfig()
) : AutoCloseable {
    
    // Native processor for MFSR (lazy initialized)
    private var nativeProcessor: NativeBurstProcessor? = null
    
    // Neural refiner (lazy initialized)
    private var refiner: MFSRRefiner? = null
    
    // Gyro alignment helper
    private val gyroHelper = GyroAlignmentHelper()
    
    // Current state
    private var currentStage = MFSRStage.IDLE
    
    /**
     * Initialize the pipeline
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Initialize native processor with MFSR enabled
            nativeProcessor = NativeBurstProcessor.create(
                BurstProcessorParams(
                    alignmentTileSize = config.tileSize,
                    pyramidLevels = config.flowPyramidLevels,
                    enableMFSR = true,
                    mfsrScaleFactor = config.scaleFactor
                )
            )
            
            // Initialize refiner if enabled
            if (config.enableRefinement) {
                refiner = MFSRRefiner(context, RefinerConfig(
                    tileSize = 128,
                    overlap = 16,
                    useGpu = true,
                    blendStrength = config.refinementBlendStrength
                ))
                
                val refinerReady = refiner?.initialize() ?: false
                if (!refinerReady) {
                    Log.w(TAG, "Refiner initialization failed, will skip refinement")
                }
            }
            
            Log.i(TAG, "MFSRPipeline initialized: scale=${config.scaleFactor}x, " +
                      "tiles=${config.tileSize}, refinement=${config.enableRefinement}")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline initialization failed", e)
            false
        }
    }
    
    /**
     * Process captured burst frames
     * 
     * @param frames List of captured frames with gyro data
     * @param progressCallback Progress callback
     * @return Pipeline result
     */
    suspend fun process(
        frames: List<CapturedFrame>,
        progressCallback: MFSRPipelineProgressCallback? = null
    ): MFSRPipelineResult = withContext(Dispatchers.Default) {
        
        val startTime = System.currentTimeMillis()
        
        if (frames.isEmpty()) {
            return@withContext MFSRPipelineResult(
                outputBitmap = null,
                inputWidth = 0, inputHeight = 0,
                outputWidth = 0, outputHeight = 0,
                framesUsed = 0, tilesProcessed = 0,
                averageFlow = 0f, processingTimeMs = 0,
                usedFallback = false, fallbackReason = "No frames provided",
                success = false
            )
        }
        
        try {
            // Stage 1: Compute gyro homographies
            currentStage = MFSRStage.COMPUTING_GYRO
            progressCallback?.invoke(currentStage, 0.1f, "Computing gyro alignment...")
            
            val homographies = if (config.useGyroInit) {
                gyroHelper.computeAllHomographies(frames)
            } else {
                null
            }
            
            Log.d(TAG, "Gyro homographies computed: ${homographies?.size ?: 0}")
            
            // Stage 2: Process through native MFSR pipeline
            currentStage = MFSRStage.MFSR_PROCESSING
            progressCallback?.invoke(currentStage, 0.2f, "Processing MFSR...")
            
            // Convert frames to native format and process
            val mfsrResult = processNativeMFSR(frames, homographies) { tileProgress ->
                val overallProgress = 0.2f + tileProgress * 0.6f
                progressCallback?.invoke(currentStage, overallProgress, 
                    "Processing tile ${(tileProgress * 100).toInt()}%")
            }
            
            if (mfsrResult == null) {
                return@withContext MFSRPipelineResult(
                    outputBitmap = null,
                    inputWidth = frames[0].width, inputHeight = frames[0].height,
                    outputWidth = 0, outputHeight = 0,
                    framesUsed = frames.size, tilesProcessed = 0,
                    averageFlow = 0f, processingTimeMs = System.currentTimeMillis() - startTime,
                    usedFallback = true, fallbackReason = "Native MFSR processing failed",
                    success = false
                )
            }
            
            // Stage 3: Neural refinement (optional)
            val finalOutput = if (config.enableRefinement && refiner?.isReady() == true) {
                currentStage = MFSRStage.REFINING
                progressCallback?.invoke(currentStage, 0.8f, "Applying neural refinement...")
                
                refiner?.refine(mfsrResult.bitmap) { processed, total, msg ->
                    val refineProgress = processed.toFloat() / total
                    val overallProgress = 0.8f + refineProgress * 0.15f
                    progressCallback?.invoke(currentStage, overallProgress, msg)
                } ?: mfsrResult.bitmap
            } else {
                mfsrResult.bitmap
            }
            
            // Complete
            currentStage = MFSRStage.COMPLETE
            val totalTime = System.currentTimeMillis() - startTime
            progressCallback?.invoke(currentStage, 1.0f, "Complete in ${totalTime}ms")
            
            Log.i(TAG, "MFSR pipeline complete: ${mfsrResult.inputWidth}x${mfsrResult.inputHeight} -> " +
                      "${finalOutput.width}x${finalOutput.height}, time=${totalTime}ms")
            
            MFSRPipelineResult(
                outputBitmap = finalOutput,
                inputWidth = mfsrResult.inputWidth,
                inputHeight = mfsrResult.inputHeight,
                outputWidth = finalOutput.width,
                outputHeight = finalOutput.height,
                framesUsed = frames.size,
                tilesProcessed = mfsrResult.tilesProcessed,
                averageFlow = mfsrResult.averageFlow,
                processingTimeMs = totalTime,
                usedFallback = mfsrResult.usedFallback,
                fallbackReason = mfsrResult.fallbackReason,
                success = true
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline processing failed", e)
            currentStage = MFSRStage.FAILED
            
            MFSRPipelineResult(
                outputBitmap = null,
                inputWidth = frames.getOrNull(0)?.width ?: 0,
                inputHeight = frames.getOrNull(0)?.height ?: 0,
                outputWidth = 0, outputHeight = 0,
                framesUsed = frames.size, tilesProcessed = 0,
                averageFlow = 0f, processingTimeMs = System.currentTimeMillis() - startTime,
                usedFallback = false, fallbackReason = e.message,
                success = false
            )
        }
    }
    
    /**
     * Native MFSR result wrapper
     */
    private data class NativeMFSRResult(
        val bitmap: Bitmap,
        val inputWidth: Int,
        val inputHeight: Int,
        val tilesProcessed: Int,
        val averageFlow: Float,
        val usedFallback: Boolean,
        val fallbackReason: String?
    )
    
    /**
     * Process frames through native MFSR pipeline
     */
    private suspend fun processNativeMFSR(
        frames: List<CapturedFrame>,
        homographies: List<Homography>?,
        progressCallback: (Float) -> Unit
    ): NativeMFSRResult? = withContext(Dispatchers.IO) {
        
        val processor = nativeProcessor ?: return@withContext null
        
        try {
            if (frames.isEmpty()) return@withContext null
            
            val referenceIndex = frames.size / 2
            val referenceFrame = frames[referenceIndex]
            val width = referenceFrame.width
            val height = referenceFrame.height
            
            // Calculate output size based on scale factor
            val outputWidth = width * config.scaleFactor
            val outputHeight = height * config.scaleFactor
            
            // Create output bitmap
            val outputBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
            
            // Prepare YUV plane arrays for native processing
            val yPlanes = frames.map { it.yPlane }.toTypedArray()
            val uPlanes = frames.map { it.uPlane }.toTypedArray()
            val vPlanes = frames.map { it.vPlane }.toTypedArray()
            val yRowStrides = frames.map { it.yRowStride }.toIntArray()
            val uvRowStrides = frames.map { it.uvRowStride }.toIntArray()
            val uvPixelStrides = frames.map { it.uvPixelStride }.toIntArray()
            
            // Process through native pipeline
            val result = processor.processYUV(
                yPlanes = yPlanes,
                uPlanes = uPlanes,
                vPlanes = vPlanes,
                yRowStrides = yRowStrides,
                uvRowStrides = uvRowStrides,
                uvPixelStrides = uvPixelStrides,
                width = width,
                height = height,
                outputBitmap = outputBitmap,
                progressCallback = { stage, progress, message ->
                    progressCallback(progress)
                }
            )
            
            if (result == 0) {
                // Get MFSR info if available
                val mfsrInfo = processor.getMFSRInfo()
                
                NativeMFSRResult(
                    bitmap = outputBitmap,
                    inputWidth = width,
                    inputHeight = height,
                    tilesProcessed = (width / config.tileSize) * (height / config.tileSize),
                    averageFlow = 0f,  // TODO: Get from native
                    usedFallback = mfsrInfo?.applied != true,
                    fallbackReason = if (mfsrInfo?.applied != true) "MFSR not applied" else null
                )
            } else {
                outputBitmap.recycle()
                Log.e(TAG, "Native processing failed with code: $result")
                null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Native MFSR failed", e)
            null
        }
    }
    
    /**
     * Get current processing stage
     */
    fun getCurrentStage(): MFSRStage = currentStage
    
    /**
     * Release resources
     */
    override fun close() {
        nativeProcessor?.close()
        nativeProcessor = null
        
        refiner?.close()
        refiner = null
        
        currentStage = MFSRStage.IDLE
        Log.d(TAG, "MFSRPipeline closed")
    }
}
