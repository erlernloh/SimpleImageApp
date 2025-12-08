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
    
    // MFSR pipeline components (for ULTRA preset)
    private var mfsrPipeline: NativeMFSRPipeline? = null
    private var mfsrRefiner: MFSRRefiner? = null
    private val gyroHelper = GyroAlignmentHelper()
    
    private var currentJob: Job? = null
    
    /**
     * Initialize the pipeline
     * 
     * @param preset Processing preset to use
     */
    suspend fun initialize(preset: UltraDetailPreset): Boolean = withContext(Dispatchers.IO) {
        try {
            // ULTRA preset uses dedicated MFSR pipeline
            if (preset == UltraDetailPreset.ULTRA) {
                // Initialize tile-based MFSR pipeline
                mfsrPipeline = NativeMFSRPipeline.create(NativeMFSRConfig(
                    tileWidth = 256,
                    tileHeight = 256,
                    overlap = 32,
                    scaleFactor = 2,
                    robustness = MFSRRobustness.TUKEY,
                    useGyroInit = true
                ))
                
                // Initialize neural refiner
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
                
                Log.i(TAG, "Pipeline initialized with ULTRA preset (MFSR + refinement)")
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
            
            try {
                mfsrRefiner!!.refine(outputBitmap) { processed, total, _ ->
                    _state.value = PipelineState.RefiningMFSR(processed, total)
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
        _state.value = PipelineState.Complete(rotatedBitmap, processingTime)
        
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
     * Select the best frame based on gyro stability (lowest total rotation)
     */
    private fun selectBestFrame(frames: List<CapturedFrame>): Int {
        if (frames.size <= 1) return 0
        
        var bestIndex = 0
        var minRotation = Float.MAX_VALUE
        
        frames.forEachIndexed { index, frame ->
            val rotation = computeGyroMagnitude(frame.gyroSamples)
            if (rotation < minRotation) {
                minRotation = rotation
                bestIndex = index
            }
        }
        
        Log.d(TAG, "Selected best frame: $bestIndex (gyro magnitude: $minRotation)")
        return bestIndex
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
