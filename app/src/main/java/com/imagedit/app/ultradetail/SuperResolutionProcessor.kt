/**
 * SuperResolutionProcessor.kt - TFLite super-resolution with tiling
 * 
 * Implements selective super-resolution using a lightweight TFLite model
 * with tile-based processing and detail mask optimization.
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
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.ceil
import kotlin.math.min

private const val TAG = "SuperResolutionProcessor"

/**
 * Super-resolution scale factor
 */
enum class SRScaleFactor(val value: Int) {
    X2(2),
    X4(4)
}

/**
 * Hardware acceleration mode
 */
enum class SRAcceleration {
    CPU,
    GPU,
    NNAPI
}

/**
 * Super-resolution configuration
 * 
 * Default values are configured for the ESRGAN model:
 * - Input: 50x50 RGB tiles
 * - Output: 200x200 RGB (4x upscale)
 * - Model: models/esrgan.tflite
 */
data class SRConfig(
    val scaleFactor: SRScaleFactor = SRScaleFactor.X4,
    val tileSize: Int = 50,  // ESRGAN uses 50x50 input
    val overlap: Int = 8,     // Overlap for seamless tiling
    val acceleration: SRAcceleration = SRAcceleration.GPU,
    val numThreads: Int = 4,
    val modelPath: String = "models/esrgan.tflite"
)

/**
 * Progress callback for SR processing
 */
typealias SRProgressCallback = (tilesProcessed: Int, totalTiles: Int, message: String) -> Unit

/**
 * Super-resolution processor using TensorFlow Lite
 * 
 * Processes images tile-by-tile, applying neural network super-resolution
 * only to detail-rich regions identified by the detail mask.
 */
class SuperResolutionProcessor(
    private val context: Context,
    private val config: SRConfig = SRConfig()
) : AutoCloseable {
    
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnapiDelegate: NnApiDelegate? = null
    
    // These will be set from the model's actual input/output shapes (may be non-square)
    private var inputHeight = config.tileSize
    private var inputWidth = config.tileSize
    private var outputHeight = config.tileSize * config.scaleFactor.value
    private var outputWidth = config.tileSize * config.scaleFactor.value
    private var actualScaleFactor = config.scaleFactor.value
    
    // Reusable buffers
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: ByteBuffer? = null
    
    /**
     * Initialize the TFLite interpreter
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            val model = loadModel(config.modelPath)
            
            val options = Interpreter.Options().apply {
                setNumThreads(config.numThreads)
                
                when (config.acceleration) {
                    SRAcceleration.GPU -> {
                        try {
                            gpuDelegate = GpuDelegate()
                            addDelegate(gpuDelegate)
                            Log.d(TAG, "Using GPU delegate")
                        } catch (e: Throwable) {
                            // Catch Throwable to handle NoClassDefFoundError when GPU library is missing
                            Log.w(TAG, "GPU delegate not available, falling back to CPU: ${e.message}")
                        }
                    }
                    SRAcceleration.NNAPI -> {
                        try {
                            nnapiDelegate = NnApiDelegate()
                            addDelegate(nnapiDelegate)
                            Log.d(TAG, "Using NNAPI delegate")
                        } catch (e: Throwable) {
                            // Catch Throwable to handle NoClassDefFoundError when NNAPI library is missing
                            Log.w(TAG, "NNAPI delegate not available, falling back to CPU: ${e.message}")
                        }
                    }
                    SRAcceleration.CPU -> {
                        Log.d(TAG, "Using CPU")
                    }
                }
            }
            
            interpreter = Interpreter(model, options)
            
            // Query actual model input/output shapes
            val inputTensor = interpreter!!.getInputTensor(0)
            val outputTensor = interpreter!!.getOutputTensor(0)
            
            val inputShape = inputTensor.shape()  // [1, H, W, 3]
            val outputShape = outputTensor.shape()  // [1, H*scale, W*scale, 3]
            
            // Update sizes based on actual model shape (handle non-square)
            inputHeight = inputShape[1]
            inputWidth = inputShape[2]
            outputHeight = outputShape[1]
            outputWidth = outputShape[2]
            actualScaleFactor = if (inputHeight > 0) outputHeight / inputHeight else config.scaleFactor.value
            
            Log.d(TAG, "Model input shape: ${inputShape.contentToString()}, output shape: ${outputShape.contentToString()}")
            
            // Allocate buffers based on actual model sizes
            // Input: [1, H, W, 3] float32
            inputBuffer = ByteBuffer.allocateDirect(1 * inputHeight * inputWidth * 3 * 4)
                .order(ByteOrder.nativeOrder())
            
            // Output: [1, H*scale, W*scale, 3] float32
            outputBuffer = ByteBuffer.allocateDirect(1 * outputHeight * outputWidth * 3 * 4)
                .order(ByteOrder.nativeOrder())
            
            Log.i(TAG, "SR processor initialized: ${actualScaleFactor}x, input=${inputWidth}x${inputHeight}, output=${outputWidth}x${outputHeight}")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SR processor", e)
            false
        }
    }
    
    /**
     * Process image with selective super-resolution
     * 
     * @param input Input bitmap
     * @param detailMask Tile mask (255 = detail, 0 = smooth), or null for full SR
     * @param maskTileSize Size of tiles in the detail mask
     * @param progressCallback Optional progress callback
     * @return Upscaled bitmap
     */
    suspend fun process(
        input: Bitmap,
        detailMask: ByteArray? = null,
        maskTileSize: Int = 64,
        progressCallback: SRProgressCallback? = null
    ): Bitmap = withContext(Dispatchers.Default) {
        
        val scale = actualScaleFactor
        val finalOutputWidth = input.width * scale
        val finalOutputHeight = input.height * scale
        
        // Create output bitmap
        val output = Bitmap.createBitmap(finalOutputWidth, finalOutputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        
        // Calculate tiles using model's input dimensions (may be non-square)
        val effectiveTileWidth = inputWidth - config.overlap
        val effectiveTileHeight = inputHeight - config.overlap
        val tilesX = ceil(input.width.toFloat() / effectiveTileWidth).toInt()
        val tilesY = ceil(input.height.toFloat() / effectiveTileHeight).toInt()
        val totalTiles = tilesX * tilesY
        
        // Calculate mask dimensions
        val maskTilesX = if (detailMask != null) {
            ceil(input.width.toFloat() / maskTileSize).toInt()
        } else 0
        
        Log.d(TAG, "Processing ${tilesX}x${tilesY} tiles, output: ${finalOutputWidth}x${finalOutputHeight}")
        
        var tilesProcessed = 0
        var srTiles = 0
        var bicubicTiles = 0
        
        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                // Calculate tile bounds using model's input dimensions
                val srcX = tx * effectiveTileWidth
                val srcY = ty * effectiveTileHeight
                val srcRight = min(srcX + inputWidth, input.width)
                val srcBottom = min(srcY + inputHeight, input.height)
                val tileWidth = srcRight - srcX
                val tileHeight = srcBottom - srcY
                
                // Check if tile is detail-rich
                val isDetailTile = if (detailMask != null) {
                    isDetailRegion(detailMask, maskTilesX, srcX, srcY, tileWidth, tileHeight, maskTileSize)
                } else {
                    true // No mask = process all with SR
                }
                
                // Extract tile
                val tileBitmap = Bitmap.createBitmap(input, srcX, srcY, tileWidth, tileHeight)
                
                // Process tile
                val upscaledTile = if (isDetailTile && interpreter != null) {
                    // Neural network super-resolution
                    srTiles++
                    processTileWithSR(tileBitmap)
                } else {
                    // Bicubic interpolation
                    bicubicTiles++
                    processTileWithBicubic(tileBitmap)
                }
                
                // Calculate output position
                val dstX = srcX * scale
                val dstY = srcY * scale
                
                // Draw tile with feathering at overlaps
                drawTileWithBlending(canvas, upscaledTile, dstX, dstY, tx, ty, tilesX, tilesY, paint)
                
                // Cleanup
                tileBitmap.recycle()
                upscaledTile.recycle()
                
                tilesProcessed++
                progressCallback?.invoke(tilesProcessed, totalTiles, "Processing tiles...")
            }
        }
        
        Log.i(TAG, "SR complete: $srTiles SR tiles, $bicubicTiles bicubic tiles")
        
        output
    }
    
    /**
     * Check if a region contains detail based on the mask
     */
    private fun isDetailRegion(
        mask: ByteArray,
        maskTilesX: Int,
        x: Int, y: Int,
        width: Int, height: Int,
        maskTileSize: Int
    ): Boolean {
        // Check all mask tiles that overlap with this region
        val startTileX = x / maskTileSize
        val startTileY = y / maskTileSize
        val endTileX = (x + width - 1) / maskTileSize
        val endTileY = (y + height - 1) / maskTileSize
        
        for (ty in startTileY..endTileY) {
            for (tx in startTileX..endTileX) {
                val idx = ty * maskTilesX + tx
                if (idx < mask.size && mask[idx] != 0.toByte()) {
                    return true
                }
            }
        }
        
        return false
    }
    
    /**
     * Process tile with neural network super-resolution
     */
    private fun processTileWithSR(tile: Bitmap): Bitmap {
        val interp = interpreter ?: return processTileWithBicubic(tile)
        
        // Pad tile to model's input size if needed (may be non-square)
        val paddedTile = if (tile.width < inputWidth || tile.height < inputHeight) {
            val padded = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(padded)
            canvas.drawBitmap(tile, 0f, 0f, null)
            padded
        } else {
            tile
        }
        
        // Fill input buffer (height x width order for TFLite)
        inputBuffer?.rewind()
        for (y in 0 until inputHeight) {
            for (x in 0 until inputWidth) {
                val pixel = paddedTile.getPixel(x, y)
                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f
                
                inputBuffer?.putFloat(r)
                inputBuffer?.putFloat(g)
                inputBuffer?.putFloat(b)
            }
        }
        
        // Run inference
        outputBuffer?.rewind()
        interp.run(inputBuffer, outputBuffer)
        
        // Create output bitmap (non-square)
        val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        outputBuffer?.rewind()
        
        for (y in 0 until outputHeight) {
            for (x in 0 until outputWidth) {
                val r = (outputBuffer?.float?.coerceIn(0f, 1f) ?: 0f) * 255
                val g = (outputBuffer?.float?.coerceIn(0f, 1f) ?: 0f) * 255
                val b = (outputBuffer?.float?.coerceIn(0f, 1f) ?: 0f) * 255
                
                val pixel = (0xFF shl 24) or
                           (r.toInt() shl 16) or
                           (g.toInt() shl 8) or
                           b.toInt()
                
                output.setPixel(x, y, pixel)
            }
        }
        
        // Crop to actual output size if input was padded
        val scale = actualScaleFactor
        val actualOutWidth = tile.width * scale
        val actualOutHeight = tile.height * scale
        
        if (paddedTile !== tile) {
            paddedTile.recycle()
        }
        
        return if (actualOutWidth < outputWidth || actualOutHeight < outputHeight) {
            val cropped = Bitmap.createBitmap(output, 0, 0, actualOutWidth, actualOutHeight)
            output.recycle()
            cropped
        } else {
            output
        }
    }
    
    /**
     * Process tile with bicubic interpolation (fallback)
     */
    private fun processTileWithBicubic(tile: Bitmap): Bitmap {
        val scale = actualScaleFactor
        return Bitmap.createScaledBitmap(
            tile,
            tile.width * scale,
            tile.height * scale,
            true // Bilinear filtering (closest to bicubic available)
        )
    }
    
    /**
     * Draw tile with feathering at overlaps
     */
    private fun drawTileWithBlending(
        canvas: Canvas,
        tile: Bitmap,
        x: Int, y: Int,
        tileX: Int, tileY: Int,
        tilesX: Int, tilesY: Int,
        paint: Paint
    ) {
        val scale = config.scaleFactor.value
        val overlap = config.overlap * scale
        
        // Simple drawing without complex blending for now
        // Full implementation would use alpha gradients at overlaps
        
        val srcRect = Rect(0, 0, tile.width, tile.height)
        val dstRect = Rect(x, y, x + tile.width, y + tile.height)
        
        // Adjust for overlaps (crop overlap region except at edges)
        if (tileX > 0) {
            srcRect.left = overlap / 2
            dstRect.left = x + overlap / 2
        }
        if (tileY > 0) {
            srcRect.top = overlap / 2
            dstRect.top = y + overlap / 2
        }
        if (tileX < tilesX - 1) {
            srcRect.right = tile.width - overlap / 2
            dstRect.right = x + tile.width - overlap / 2
        }
        if (tileY < tilesY - 1) {
            srcRect.bottom = tile.height - overlap / 2
            dstRect.bottom = y + tile.height - overlap / 2
        }
        
        canvas.drawBitmap(tile, srcRect, dstRect, paint)
    }
    
    /**
     * Load TFLite model from assets
     */
    private fun loadModel(modelPath: String): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
    
    override fun close() {
        interpreter?.close()
        gpuDelegate?.close()
        nnapiDelegate?.close()
        interpreter = null
        gpuDelegate = null
        nnapiDelegate = null
        inputBuffer = null
        outputBuffer = null
    }
}
