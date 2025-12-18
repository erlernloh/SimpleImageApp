/**
 * MFSRRefiner.kt - Neural upscaling and refinement for MFSR output
 * 
 * For ULTRA preset: MFSR 2x → ESRGAN 4x = 8x total upscale
 * 
 * This takes the clean MFSR output (which has real sub-pixel detail from
 * multi-frame fusion) and further upscales it using ESRGAN's neural
 * texture synthesis. The result is massive resolution with both:
 * - Real detail from MFSR multi-frame processing
 * - Neural texture enhancement from ESRGAN
 * - CPU refinement polish (sharpen + denoise)
 * 
 * Pipeline:
 * 1. MFSR 2x upscale (multi-frame fusion with real sub-pixel info)
 * 2. ESRGAN 4x upscale (neural texture synthesis)
 * 3. CPU refinement (unsharp mask + edge-preserving denoise)
 * 
 * Memory safety: Output capped at 100MP to prevent OOM
 */

package com.imagedit.app.ultradetail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.ceil
import kotlin.math.min

private const val TAG = "MFSRRefiner"

/**
 * Neural model type for ULTRA upscaling
 */
enum class RefinerModel(val assetPath: String, val description: String) {
    // ESRGAN 4x upscaler - used for ULTRA preset (MFSR 2x + ESRGAN 4x = 8x total)
    ESRGAN_REFINE("models/esrgan.tflite", "ESRGAN 4x neural upscaler"),
    
    // Placeholder for future dedicated 1:1 refinement model
    LIGHTWEIGHT_REFINE("models/refiner.tflite", "Lightweight denoising/sharpening")
}

/**
 * Refiner configuration
 */
data class RefinerConfig(
    val model: RefinerModel = RefinerModel.ESRGAN_REFINE,
    val tileSize: Int = 128,          // Process in 128x128 tiles
    val overlap: Int = 16,            // Overlap for seamless blending
    val useGpu: Boolean = true,       // Use GPU acceleration
    val numThreads: Int = 4,          // CPU threads if GPU unavailable
    val blendStrength: Float = 0.7f   // Blend refined with original (0=original, 1=refined)
)

/**
 * Progress callback for refinement
 */
typealias RefinerProgressCallback = (tilesProcessed: Int, totalTiles: Int, message: String) -> Unit

/**
 * MFSR output refiner using TensorFlow Lite
 * 
 * Applies neural enhancement to the classical MFSR output.
 * Processes tile-by-tile to manage memory on mobile devices.
 */
class MFSRRefiner(
    private val context: Context,
    private var config: RefinerConfig = RefinerConfig()
) : AutoCloseable {
    
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    
    // Model dimensions (queried at runtime)
    private var modelInputWidth = config.tileSize
    private var modelInputHeight = config.tileSize
    private var modelOutputWidth = config.tileSize
    private var modelOutputHeight = config.tileSize
    private var modelScaleFactor = 1  // Expected to be 1 for refinement
    
    // Reusable buffers
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: ByteBuffer? = null
    
    private var isInitialized = false
    
    /**
     * Get current blend strength
     */
    fun getBlendStrength(): Float = config.blendStrength
    
    /**
     * Set blend strength dynamically
     * @param strength 0.0 = original MFSR output, 1.0 = fully refined
     */
    fun setBlendStrength(strength: Float) {
        config = config.copy(blendStrength = strength.coerceIn(0f, 1f))
        Log.d(TAG, "Blend strength set to: ${config.blendStrength}")
    }
    
    /**
     * Initialize the refiner
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext true
        
        try {
            val model = loadModel(config.model.assetPath)
            if (model == null) {
                Log.w(TAG, "Model not found: ${config.model.assetPath}, refinement will be skipped")
                return@withContext false
            }
            
            val options = Interpreter.Options().apply {
                setNumThreads(config.numThreads)
                
                if (config.useGpu) {
                    try {
                        gpuDelegate = GpuDelegate()
                        addDelegate(gpuDelegate)
                        Log.d(TAG, "Using GPU delegate for refinement")
                    } catch (e: Throwable) {
                        Log.w(TAG, "GPU not available for refiner: ${e.message}")
                    }
                }
            }
            
            interpreter = Interpreter(model, options)
            
            // Query model dimensions
            val inputTensor = interpreter!!.getInputTensor(0)
            val outputTensor = interpreter!!.getOutputTensor(0)
            
            val inputShape = inputTensor.shape()   // [1, H, W, 3] or [1, 3, H, W]
            val outputShape = outputTensor.shape() // [1, H', W', 3] or [1, 3, H', W']
            
            Log.d(TAG, "Model input shape: ${inputShape.contentToString()}")
            Log.d(TAG, "Model output shape: ${outputShape.contentToString()}")
            
            // Detect NCHW vs NHWC format
            // NHWC: [1, H, W, 3] - last dim is 3
            // NCHW: [1, 3, H, W] - second dim is 3
            val isNCHW = inputShape.size == 4 && inputShape[1] == 3
            
            if (isNCHW) {
                // NCHW format: [1, 3, H, W]
                modelInputHeight = inputShape[2]
                modelInputWidth = inputShape[3]
                modelOutputHeight = outputShape[2]
                modelOutputWidth = outputShape[3]
                Log.d(TAG, "Detected NCHW format")
            } else {
                // NHWC format: [1, H, W, 3]
                modelInputHeight = inputShape[1]
                modelInputWidth = inputShape[2]
                modelOutputHeight = outputShape[1]
                modelOutputWidth = outputShape[2]
                Log.d(TAG, "Detected NHWC format")
            }
            
            modelScaleFactor = if (modelInputHeight > 0) modelOutputHeight / modelInputHeight else 1
            
            Log.i(TAG, "Refiner model: input=${modelInputWidth}x${modelInputHeight}, " +
                      "output=${modelOutputWidth}x${modelOutputHeight}, scale=${modelScaleFactor}x")
            
            // Allocate buffers
            inputBuffer = ByteBuffer.allocateDirect(1 * modelInputHeight * modelInputWidth * 3 * 4)
                .order(ByteOrder.nativeOrder())
            
            outputBuffer = ByteBuffer.allocateDirect(1 * modelOutputHeight * modelOutputWidth * 3 * 4)
                .order(ByteOrder.nativeOrder())
            
            isInitialized = true
            Log.i(TAG, "MFSRRefiner initialized successfully")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize refiner", e)
            false
        }
    }
    
    /**
     * Get the model's upscale factor
     */
    fun getScaleFactor(): Int = modelScaleFactor
    
    /**
     * Upscale and enhance MFSR output using ESRGAN
     * 
     * TRUE ULTRA PIPELINE: MFSR 2x → ESRGAN 4x = 8x total upscale
     * 
     * This takes the clean MFSR output (which has real sub-pixel detail from
     * multi-frame fusion) and further upscales it using ESRGAN's neural
     * texture synthesis. The result is massive resolution with both:
     * - Real detail from MFSR multi-frame processing
     * - Neural texture enhancement from ESRGAN
     * 
     * @param mfsrOutput The output from classical MFSR (already 2x upscaled)
     * @param progressCallback Optional progress callback
     * @return Upscaled bitmap (4x larger than input, 8x larger than original)
     */
    suspend fun refine(
        mfsrOutput: Bitmap,
        progressCallback: RefinerProgressCallback? = null
    ): Bitmap = withContext(Dispatchers.Default) {
        
        if (!isInitialized) {
            Log.w(TAG, "Refiner not initialized, returning original")
            return@withContext mfsrOutput
        }
        
        val inputWidth = mfsrOutput.width
        val inputHeight = mfsrOutput.height
        
        // For ESRGAN 4x upscaler: apply it to MFSR output for 8x total upscale
        // MFSR 2x already provides clean, detailed base - ESRGAN adds texture synthesis
        if (modelScaleFactor > 1) {
            Log.i(TAG, "ULTRA mode: MFSR output ${inputWidth}x${inputHeight} → ESRGAN ${modelScaleFactor}x upscale")
            
            // Cap maximum output to avoid OOM (max ~100MP)
            val maxOutputPixels = 100_000_000L
            val projectedPixels = inputWidth.toLong() * inputHeight * modelScaleFactor * modelScaleFactor
            
            if (projectedPixels > maxOutputPixels) {
                // Scale down input to fit within memory limits
                val scaleFactor = kotlin.math.sqrt(maxOutputPixels.toDouble() / projectedPixels).toFloat()
                val scaledWidth = (inputWidth * scaleFactor).toInt()
                val scaledHeight = (inputHeight * scaleFactor).toInt()
                Log.w(TAG, "Output would be ${projectedPixels/1_000_000}MP, scaling input to ${scaledWidth}x${scaledHeight}")
                
                val scaledInput = Bitmap.createScaledBitmap(mfsrOutput, scaledWidth, scaledHeight, true)
                val result = applyEsrganUpscale(scaledInput, progressCallback)
                if (scaledInput != mfsrOutput) scaledInput.recycle()
                
                // Apply CPU refinement for final polish
                val refined = applyCpuRefinement(result, null)
                if (refined != result) result.recycle()
                return@withContext refined
            }
            
            // Apply ESRGAN 4x upscale
            val upscaled = applyEsrganUpscale(mfsrOutput, progressCallback)
            
            // Apply CPU refinement for final polish (sharpen + denoise)
            Log.i(TAG, "Applying CPU refinement for final polish...")
            val refined = applyCpuRefinement(upscaled, null)
            if (refined != upscaled) upscaled.recycle()
            
            return@withContext refined
        }
        
        // For true 1:1 refiner models, use neural network directly
        val inputBitmap = mfsrOutput
        
        // Create output bitmap (same size as original mfsrOutput)
        val output = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        
        // Calculate tiles
        val effectiveTileWidth = modelInputWidth - config.overlap
        val effectiveTileHeight = modelInputHeight - config.overlap
        val tilesX = ceil(inputBitmap.width.toFloat() / effectiveTileWidth).toInt()
        val tilesY = ceil(inputBitmap.height.toFloat() / effectiveTileHeight).toInt()
        val totalTiles = tilesX * tilesY
        
        Log.d(TAG, "Refining ${tilesX}x${tilesY} tiles")
        
        var tilesProcessed = 0
        
        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                // Calculate tile bounds in input space
                val srcX = tx * effectiveTileWidth
                val srcY = ty * effectiveTileHeight
                val srcRight = min(srcX + modelInputWidth, inputBitmap.width)
                val srcBottom = min(srcY + modelInputHeight, inputBitmap.height)
                val tileWidth = srcRight - srcX
                val tileHeight = srcBottom - srcY
                
                // Process tile
                val refinedTile = processTile(inputBitmap, srcX, srcY, tileWidth, tileHeight)
                
                if (refinedTile != null) {
                    // Calculate destination in output space
                    val dstX = srcX * modelScaleFactor
                    val dstY = srcY * modelScaleFactor
                    val dstRight = dstX + refinedTile.width
                    val dstBottom = dstY + refinedTile.height
                    
                    // Draw refined tile
                    canvas.drawBitmap(
                        refinedTile,
                        Rect(0, 0, refinedTile.width, refinedTile.height),
                        Rect(dstX, dstY, dstRight, dstBottom),
                        paint
                    )
                    
                    refinedTile.recycle()
                } else {
                    // Fallback: copy original region
                    val dstX = srcX * modelScaleFactor
                    val dstY = srcY * modelScaleFactor
                    val dstRight = min(dstX + tileWidth * modelScaleFactor, inputWidth)
                    val dstBottom = min(dstY + tileHeight * modelScaleFactor, inputHeight)
                    
                    canvas.drawBitmap(
                        mfsrOutput,
                        Rect(dstX, dstY, dstRight, dstBottom),
                        Rect(dstX, dstY, dstRight, dstBottom),
                        paint
                    )
                }
                
                tilesProcessed++
                progressCallback?.invoke(tilesProcessed, totalTiles, "Refining tile $tilesProcessed/$totalTiles")
            }
        }
        
        // Clean up input if we created a copy
        if (inputBitmap != mfsrOutput) {
            inputBitmap.recycle()
        }
        
        // Blend with original if configured
        if (config.blendStrength < 1.0f) {
            blendWithOriginal(output, mfsrOutput, config.blendStrength)
        }
        
        Log.i(TAG, "Refinement complete: $tilesProcessed tiles processed")
        output
    }
    
    /**
     * Apply ESRGAN 4x upscale to input bitmap
     * Processes tile-by-tile to manage memory
     */
    private suspend fun applyEsrganUpscale(
        input: Bitmap,
        progressCallback: RefinerProgressCallback?
    ): Bitmap = withContext(Dispatchers.Default) {
        val inputWidth = input.width
        val inputHeight = input.height
        val outputWidth = inputWidth * modelScaleFactor
        val outputHeight = inputHeight * modelScaleFactor
        
        Log.i(TAG, "ESRGAN upscale: ${inputWidth}x${inputHeight} → ${outputWidth}x${outputHeight}")
        
        // Create output bitmap
        val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        
        // Calculate tiles with overlap for seamless blending
        val effectiveTileWidth = modelInputWidth - config.overlap
        val effectiveTileHeight = modelInputHeight - config.overlap
        val tilesX = ceil(inputWidth.toFloat() / effectiveTileWidth).toInt()
        val tilesY = ceil(inputHeight.toFloat() / effectiveTileHeight).toInt()
        val totalTiles = tilesX * tilesY
        
        Log.d(TAG, "Processing ${tilesX}x${tilesY} = $totalTiles tiles")
        
        var tilesProcessed = 0
        
        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                // Calculate tile bounds in input space
                val srcX = tx * effectiveTileWidth
                val srcY = ty * effectiveTileHeight
                val srcRight = min(srcX + modelInputWidth, inputWidth)
                val srcBottom = min(srcY + modelInputHeight, inputHeight)
                val tileWidth = srcRight - srcX
                val tileHeight = srcBottom - srcY
                
                // Process tile through ESRGAN
                val upscaledTile = processTile(input, srcX, srcY, tileWidth, tileHeight)
                
                if (upscaledTile != null) {
                    // Calculate destination in output space (scaled by modelScaleFactor)
                    val dstX = srcX * modelScaleFactor
                    val dstY = srcY * modelScaleFactor
                    
                    // Draw upscaled tile
                    canvas.drawBitmap(
                        upscaledTile,
                        Rect(0, 0, upscaledTile.width, upscaledTile.height),
                        Rect(dstX, dstY, dstX + upscaledTile.width, dstY + upscaledTile.height),
                        paint
                    )
                    
                    upscaledTile.recycle()
                } else {
                    // Fallback: bilinear upscale of original region
                    val dstX = srcX * modelScaleFactor
                    val dstY = srcY * modelScaleFactor
                    val dstWidth = tileWidth * modelScaleFactor
                    val dstHeight = tileHeight * modelScaleFactor
                    
                    canvas.drawBitmap(
                        input,
                        Rect(srcX, srcY, srcRight, srcBottom),
                        Rect(dstX, dstY, dstX + dstWidth, dstY + dstHeight),
                        paint
                    )
                }
                
                tilesProcessed++
                progressCallback?.invoke(tilesProcessed, totalTiles, "ESRGAN tile $tilesProcessed/$totalTiles")
            }
        }
        
        val outputMP = (outputWidth.toLong() * outputHeight) / 1_000_000f
        Log.i(TAG, "ESRGAN upscale complete: ${outputWidth}x${outputHeight} (${"%,.1f".format(outputMP)}MP)")
        output
    }
    
    /**
     * Process a single tile through the neural network
     */
    private fun processTile(
        input: Bitmap,
        srcX: Int,
        srcY: Int,
        tileWidth: Int,
        tileHeight: Int
    ): Bitmap? {
        val interp = interpreter ?: return null
        val inBuf = inputBuffer ?: return null
        val outBuf = outputBuffer ?: return null
        
        try {
            // Extract tile (pad if necessary)
            val tileBitmap = Bitmap.createBitmap(modelInputWidth, modelInputHeight, Bitmap.Config.ARGB_8888)
            val tileCanvas = Canvas(tileBitmap)
            
            // Draw the source region (may be smaller than model input)
            val srcRight = min(srcX + tileWidth, input.width)
            val srcBottom = min(srcY + tileHeight, input.height)
            
            tileCanvas.drawBitmap(
                input,
                Rect(srcX, srcY, srcRight, srcBottom),
                Rect(0, 0, tileWidth, tileHeight),
                null
            )
            
            // Fill input buffer - ESRGAN expects 0-255 float range (industry standard)
            // Reference: TensorFlow official ESRGAN example uses tf.cast(lr, tf.float32) without /255
            inBuf.rewind()
            val pixels = IntArray(modelInputWidth * modelInputHeight)
            tileBitmap.getPixels(pixels, 0, modelInputWidth, 0, 0, modelInputWidth, modelInputHeight)
            
            // Check if model expects NCHW format (channels first)
            val inputShape = interpreter?.getInputTensor(0)?.shape()
            val isNCHW = inputShape != null && inputShape.size == 4 && inputShape[1] == 3
            
            if (isNCHW) {
                // NCHW: write all R, then all G, then all B (0-255 range)
                for (pixel in pixels) {
                    inBuf.putFloat(((pixel shr 16) and 0xFF).toFloat())
                }
                for (pixel in pixels) {
                    inBuf.putFloat(((pixel shr 8) and 0xFF).toFloat())
                }
                for (pixel in pixels) {
                    inBuf.putFloat((pixel and 0xFF).toFloat())
                }
            } else {
                // NHWC: interleaved RGB (0-255 range)
                for (pixel in pixels) {
                    val r = ((pixel shr 16) and 0xFF).toFloat()
                    val g = ((pixel shr 8) and 0xFF).toFloat()
                    val b = (pixel and 0xFF).toFloat()
                    inBuf.putFloat(r)
                    inBuf.putFloat(g)
                    inBuf.putFloat(b)
                }
            }
            
            tileBitmap.recycle()
            
            // Run inference - IMPORTANT: rewind input buffer after filling it!
            inBuf.rewind()
            outBuf.rewind()
            interp.run(inBuf, outBuf)
            
            // Extract output
            outBuf.rewind()
            val outputBitmap = Bitmap.createBitmap(modelOutputWidth, modelOutputHeight, Bitmap.Config.ARGB_8888)
            val outputPixels = IntArray(modelOutputWidth * modelOutputHeight)
            
            // Check output format
            val outputShape = interpreter?.getOutputTensor(0)?.shape()
            val isOutputNCHW = outputShape != null && outputShape.size == 4 && outputShape[1] == 3
            
            if (isOutputNCHW) {
                // NCHW: read all R, then all G, then all B (output is 0-255 range)
                val rChannel = FloatArray(outputPixels.size)
                val gChannel = FloatArray(outputPixels.size)
                val bChannel = FloatArray(outputPixels.size)
                
                for (i in outputPixels.indices) {
                    rChannel[i] = outBuf.float
                }
                for (i in outputPixels.indices) {
                    gChannel[i] = outBuf.float
                }
                for (i in outputPixels.indices) {
                    bChannel[i] = outBuf.float
                }
                
                for (i in outputPixels.indices) {
                    // ESRGAN outputs 0-255 range, clip and round per TF official example
                    val r = rChannel[i].coerceIn(0f, 255f).toInt()
                    val g = gChannel[i].coerceIn(0f, 255f).toInt()
                    val b = bChannel[i].coerceIn(0f, 255f).toInt()
                    outputPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            } else {
                // NHWC: interleaved RGB (output is 0-255 range)
                for (i in outputPixels.indices) {
                    // ESRGAN outputs 0-255 range, clip and round per TF official example
                    val r = outBuf.float.coerceIn(0f, 255f).toInt()
                    val g = outBuf.float.coerceIn(0f, 255f).toInt()
                    val b = outBuf.float.coerceIn(0f, 255f).toInt()
                    outputPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            
            // Debug: log first pixel values to verify output
            if (outputPixels.isNotEmpty()) {
                val firstPixel = outputPixels[0]
                val r = (firstPixel shr 16) and 0xFF
                val g = (firstPixel shr 8) and 0xFF
                val b = firstPixel and 0xFF
                Log.d(TAG, "First output pixel RGB: ($r, $g, $b)") 
            }
            
            outputBitmap.setPixels(outputPixels, 0, modelOutputWidth, 0, 0, modelOutputWidth, modelOutputHeight)
            
            // Crop to actual tile size (scaled)
            val cropWidth = min(tileWidth * modelScaleFactor, modelOutputWidth)
            val cropHeight = min(tileHeight * modelScaleFactor, modelOutputHeight)
            
            return if (cropWidth == modelOutputWidth && cropHeight == modelOutputHeight) {
                outputBitmap
            } else {
                val cropped = Bitmap.createBitmap(outputBitmap, 0, 0, cropWidth, cropHeight)
                outputBitmap.recycle()
                cropped
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Tile processing failed", e)
            return null
        }
    }
    
    /**
     * Blend refined output with original
     */
    private fun blendWithOriginal(refined: Bitmap, original: Bitmap, strength: Float) {
        if (refined.width != original.width || refined.height != original.height) {
            Log.w(TAG, "Size mismatch for blending, skipping")
            return
        }
        
        val width = refined.width
        val height = refined.height
        
        // Process in row chunks to avoid OOM on large images
        // Each row is width * 4 bytes, process 64 rows at a time (~1MB for 4K width)
        val rowsPerChunk = 64
        val chunkPixelCount = width * rowsPerChunk
        val refinedPixels = IntArray(chunkPixelCount)
        val originalPixels = IntArray(chunkPixelCount)
        
        val origWeight = 1.0f - strength
        
        var y = 0
        while (y < height) {
            val rowsToProcess = minOf(rowsPerChunk, height - y)
            val pixelCount = width * rowsToProcess
            
            refined.getPixels(refinedPixels, 0, width, 0, y, width, rowsToProcess)
            original.getPixels(originalPixels, 0, width, 0, y, width, rowsToProcess)
            
            for (i in 0 until pixelCount) {
                val rRef = (refinedPixels[i] shr 16) and 0xFF
                val gRef = (refinedPixels[i] shr 8) and 0xFF
                val bRef = refinedPixels[i] and 0xFF
                
                val rOrig = (originalPixels[i] shr 16) and 0xFF
                val gOrig = (originalPixels[i] shr 8) and 0xFF
                val bOrig = originalPixels[i] and 0xFF
                
                val r = (rRef * strength + rOrig * origWeight).toInt().coerceIn(0, 255)
                val g = (gRef * strength + gOrig * origWeight).toInt().coerceIn(0, 255)
                val b = (bRef * strength + bOrig * origWeight).toInt().coerceIn(0, 255)
                
                refinedPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            
            refined.setPixels(refinedPixels, 0, width, 0, y, width, rowsToProcess)
            y += rowsToProcess
        }
    }
    
    /**
     * CPU-based refinement using unsharp masking and bilateral-like denoising
     * 
     * This is used when ESRGAN (a 4x upscaler) is the only available model,
     * since using an upscaler as a "refiner" destroys image quality.
     * 
     * Industry standard refinement techniques:
     * - Unsharp masking for edge enhancement (Adobe, Google)
     * - Bilateral filtering for edge-preserving denoising (Apple Deep Fusion)
     * - Local contrast enhancement (Samsung Scene Optimizer)
     */
    private suspend fun applyCpuRefinement(
        input: Bitmap,
        progressCallback: RefinerProgressCallback?
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = input.width
        val height = input.height
        val output = input.copy(Bitmap.Config.ARGB_8888, true)
        
        // Process in chunks to report progress and avoid ANR
        val rowsPerChunk = 64
        val totalChunks = (height + rowsPerChunk - 1) / rowsPerChunk
        var chunksProcessed = 0
        
        // Parameters for refinement
        val sharpenStrength = 0.3f  // Subtle sharpening
        val denoiseStrength = 0.15f // Light denoising
        
        val pixels = IntArray(width * rowsPerChunk)
        val tempPixels = IntArray(width * rowsPerChunk)
        
        var y = 0
        while (y < height) {
            val rowsToProcess = minOf(rowsPerChunk, height - y)
            val pixelCount = width * rowsToProcess
            
            output.getPixels(pixels, 0, width, 0, y, width, rowsToProcess)
            
            // Apply unsharp mask (sharpen) + simple denoise
            for (row in 0 until rowsToProcess) {
                for (x in 0 until width) {
                    val idx = row * width + x
                    val pixel = pixels[idx]
                    
                    var r = (pixel shr 16) and 0xFF
                    var g = (pixel shr 8) and 0xFF
                    var b = pixel and 0xFF
                    
                    // Get neighbors for local operations (with boundary handling)
                    val hasLeft = x > 0
                    val hasRight = x < width - 1
                    val hasUp = row > 0
                    val hasDown = row < rowsToProcess - 1
                    
                    // Calculate local average (3x3 neighborhood)
                    var sumR = r
                    var sumG = g
                    var sumB = b
                    var count = 1
                    
                    if (hasLeft) {
                        val left = pixels[idx - 1]
                        sumR += (left shr 16) and 0xFF
                        sumG += (left shr 8) and 0xFF
                        sumB += left and 0xFF
                        count++
                    }
                    if (hasRight) {
                        val right = pixels[idx + 1]
                        sumR += (right shr 16) and 0xFF
                        sumG += (right shr 8) and 0xFF
                        sumB += right and 0xFF
                        count++
                    }
                    if (hasUp) {
                        val up = pixels[idx - width]
                        sumR += (up shr 16) and 0xFF
                        sumG += (up shr 8) and 0xFF
                        sumB += up and 0xFF
                        count++
                    }
                    if (hasDown) {
                        val down = pixels[idx + width]
                        sumR += (down shr 16) and 0xFF
                        sumG += (down shr 8) and 0xFF
                        sumB += down and 0xFF
                        count++
                    }
                    
                    val avgR = sumR / count
                    val avgG = sumG / count
                    val avgB = sumB / count
                    
                    // Unsharp mask: output = original + strength * (original - blurred)
                    val diffR = r - avgR
                    val diffG = g - avgG
                    val diffB = b - avgB
                    
                    // Apply sharpening
                    r = (r + (diffR * sharpenStrength).toInt()).coerceIn(0, 255)
                    g = (g + (diffG * sharpenStrength).toInt()).coerceIn(0, 255)
                    b = (b + (diffB * sharpenStrength).toInt()).coerceIn(0, 255)
                    
                    // Simple edge-preserving denoise: blend toward average only if difference is small
                    val edgeThreshold = 30
                    if (kotlin.math.abs(diffR) < edgeThreshold) {
                        r = (r * (1 - denoiseStrength) + avgR * denoiseStrength).toInt().coerceIn(0, 255)
                    }
                    if (kotlin.math.abs(diffG) < edgeThreshold) {
                        g = (g * (1 - denoiseStrength) + avgG * denoiseStrength).toInt().coerceIn(0, 255)
                    }
                    if (kotlin.math.abs(diffB) < edgeThreshold) {
                        b = (b * (1 - denoiseStrength) + avgB * denoiseStrength).toInt().coerceIn(0, 255)
                    }
                    
                    tempPixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            
            output.setPixels(tempPixels, 0, width, 0, y, width, rowsToProcess)
            
            chunksProcessed++
            progressCallback?.invoke(chunksProcessed, totalChunks, "Refining chunk $chunksProcessed/$totalChunks")
            
            y += rowsToProcess
        }
        
        Log.i(TAG, "CPU refinement complete: ${width}x${height}, sharpen=$sharpenStrength, denoise=$denoiseStrength")
        output
    }
    
    /**
     * Load TFLite model from assets
     */
    private fun loadModel(assetPath: String): MappedByteBuffer? {
        return try {
            val assetManager = context.assets
            val fileDescriptor = assetManager.openFd(assetPath)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        } catch (e: Exception) {
            Log.w(TAG, "Could not load model from $assetPath: ${e.message}")
            null
        }
    }
    
    /**
     * Check if refiner is ready
     */
    fun isReady(): Boolean = isInitialized
    
    /**
     * Release resources
     */
    override fun close() {
        interpreter?.close()
        interpreter = null
        
        gpuDelegate?.close()
        gpuDelegate = null
        
        inputBuffer = null
        outputBuffer = null
        
        isInitialized = false
        Log.d(TAG, "MFSRRefiner closed")
    }
}
