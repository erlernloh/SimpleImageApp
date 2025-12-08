/**
 * MFSRRefiner.kt - Neural refinement for classical MFSR output
 * 
 * Applies a lightweight neural network to clean up and enhance the
 * output from the classical multi-frame super-resolution pipeline.
 * 
 * This is NOT a full super-resolution model - the classical MFSR has
 * already done the upscaling. This refiner:
 * - Removes residual noise
 * - Sharpens edges
 * - Recovers fine textures
 * - Removes artifacts from the scatter/accumulate process
 * 
 * Design based on review feedback:
 * - Treat as "refiner" not temporal merger
 * - Single image input (MFSR result), single image output
 * - Tile-based processing for memory efficiency
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
 * Refinement model type
 */
enum class RefinerModel(val assetPath: String, val description: String) {
    // Use existing ESRGAN as 1:1 refiner (no upscaling, just enhancement)
    ESRGAN_REFINE("models/esrgan.tflite", "ESRGAN-based refinement"),
    
    // Placeholder for future dedicated refinement model
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
            
            val inputShape = inputTensor.shape()   // [1, H, W, 3]
            val outputShape = outputTensor.shape() // [1, H', W', 3]
            
            modelInputHeight = inputShape[1]
            modelInputWidth = inputShape[2]
            modelOutputHeight = outputShape[1]
            modelOutputWidth = outputShape[2]
            modelScaleFactor = if (modelInputHeight > 0) modelOutputHeight / modelInputHeight else 1
            
            Log.d(TAG, "Refiner model: input=${modelInputWidth}x${modelInputHeight}, " +
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
     * Refine MFSR output
     * 
     * @param mfsrOutput The output from classical MFSR (already upscaled)
     * @param progressCallback Optional progress callback
     * @return Refined bitmap, or original if refinement fails
     */
    suspend fun refine(
        mfsrOutput: Bitmap,
        progressCallback: RefinerProgressCallback? = null
    ): Bitmap = withContext(Dispatchers.Default) {
        
        if (!isInitialized) {
            Log.w(TAG, "Refiner not initialized, returning original")
            return@withContext mfsrOutput
        }
        
        val width = mfsrOutput.width
        val height = mfsrOutput.height
        
        // Handle model scale factor
        // If model upscales (e.g., 4x), we need to process at 1/scale and then the output matches
        // For a true refiner (1x), input and output are same size
        val processWidth = width / modelScaleFactor
        val processHeight = height / modelScaleFactor
        
        // If model upscales, we need to downscale input first
        val inputBitmap = if (modelScaleFactor > 1) {
            Log.d(TAG, "Model has ${modelScaleFactor}x scale, downscaling input for processing")
            Bitmap.createScaledBitmap(mfsrOutput, processWidth, processHeight, true)
        } else {
            mfsrOutput
        }
        
        // Create output bitmap (same size as original mfsrOutput)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
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
                    val dstRight = min(dstX + tileWidth * modelScaleFactor, width)
                    val dstBottom = min(dstY + tileHeight * modelScaleFactor, height)
                    
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
        
        // Clean up downscaled input if we created one
        if (modelScaleFactor > 1 && inputBitmap != mfsrOutput) {
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
            
            // Fill input buffer
            inBuf.rewind()
            val pixels = IntArray(modelInputWidth * modelInputHeight)
            tileBitmap.getPixels(pixels, 0, modelInputWidth, 0, 0, modelInputWidth, modelInputHeight)
            
            for (pixel in pixels) {
                val r = ((pixel shr 16) and 0xFF) / 255.0f
                val g = ((pixel shr 8) and 0xFF) / 255.0f
                val b = (pixel and 0xFF) / 255.0f
                inBuf.putFloat(r)
                inBuf.putFloat(g)
                inBuf.putFloat(b)
            }
            
            tileBitmap.recycle()
            
            // Run inference
            outBuf.rewind()
            interp.run(inBuf, outBuf)
            
            // Extract output
            outBuf.rewind()
            val outputBitmap = Bitmap.createBitmap(modelOutputWidth, modelOutputHeight, Bitmap.Config.ARGB_8888)
            val outputPixels = IntArray(modelOutputWidth * modelOutputHeight)
            
            for (i in outputPixels.indices) {
                val r = (outBuf.float.coerceIn(0f, 1f) * 255).toInt()
                val g = (outBuf.float.coerceIn(0f, 1f) * 255).toInt()
                val b = (outBuf.float.coerceIn(0f, 1f) * 255).toInt()
                outputPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
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
        val refinedPixels = IntArray(width * height)
        val originalPixels = IntArray(width * height)
        
        refined.getPixels(refinedPixels, 0, width, 0, 0, width, height)
        original.getPixels(originalPixels, 0, width, 0, 0, width, height)
        
        val origWeight = 1.0f - strength
        
        for (i in refinedPixels.indices) {
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
        
        refined.setPixels(refinedPixels, 0, width, 0, 0, width, height)
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
