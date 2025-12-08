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
    MAX
}

/**
 * Pipeline processing state
 */
sealed class PipelineState {
    object Idle : PipelineState()
    data class CapturingBurst(val framesCollected: Int, val totalFrames: Int) : PipelineState()
    data class ProcessingBurst(val stage: ProcessingStage, val progress: Float, val message: String) : PipelineState()
    data class ApplyingSuperResolution(val tilesProcessed: Int, val totalTiles: Int) : PipelineState()
    data class Complete(val result: Bitmap, val processingTimeMs: Long) : PipelineState()
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
    val mfsrCoveragePercent: Float = 0f
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
    
    private var currentJob: Job? = null
    
    /**
     * Initialize the pipeline
     * 
     * @param preset Processing preset to use
     */
    suspend fun initialize(preset: UltraDetailPreset): Boolean = withContext(Dispatchers.IO) {
        try {
            // Initialize native processor
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
            }
            
            nativeProcessor = NativeBurstProcessor.create(nativeParams)
            
            // Initialize SR processor for MAX preset
            if (preset == UltraDetailPreset.MAX) {
                try {
                    srProcessor = SuperResolutionProcessor(
                        context,
                        SRConfig(
                            scaleFactor = SRScaleFactor.X4,  // ESRGAN is 4x
                            tileSize = 50,                    // ESRGAN input size
                            overlap = 8,
                            acceleration = SRAcceleration.CPU  // Use CPU to avoid GPU delegate issues
                        )
                    )
                    
                    if (!srProcessor!!.initialize()) {
                        Log.w(TAG, "SR processor initialization failed, will use bicubic fallback")
                        srProcessor = null
                    }
                } catch (e: Throwable) {
                    // Catch Throwable to handle NoClassDefFoundError and other class loading issues
                    Log.w(TAG, "SR processor creation failed: ${e.message}, will use native MFSR only")
                    srProcessor = null
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
        
        if (nativeProcessor == null) {
            if (!initialize(preset)) {
                _state.value = PipelineState.Error("Failed to initialize pipeline", null)
                return@withContext null
            }
        }
        
        try {
            // Get frame dimensions
            val width = frames[0].width
            val height = frames[0].height
            
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
                _state.value = PipelineState.Complete(rotatedBitmap, processingTime)
                
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
                _state.value = PipelineState.Complete(rotatedBitmap, processingTime)
                
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
                srProcessor?.process(
                    mergedBitmap,
                    if (detailTileCount > 0) detailMask else null,
                    maskTileSize
                ) { processed, total, _ ->
                    srTilesProcessed = processed
                    _state.value = PipelineState.ApplyingSuperResolution(processed, total)
                } ?: run {
                    // SR processor not available, use bicubic upscale
                    Log.w(TAG, "SR processor not available, using bicubic upscale")
                    Bitmap.createScaledBitmap(
                        mergedBitmap,
                        width * 2,
                        height * 2,
                        true
                    )
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
            _state.value = PipelineState.Complete(finalBitmap, processingTime)
            
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
        nativeProcessor = null
        srProcessor = null
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
