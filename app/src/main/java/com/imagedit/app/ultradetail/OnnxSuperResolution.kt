/**
 * OnnxSuperResolution.kt - ONNX Runtime-based super-resolution for Real-ESRGAN
 * 
 * This class provides ONNX Runtime inference for Real-ESRGAN and other ONNX-based
 * super-resolution models. It works alongside the existing TFLite-based MFSRRefiner.
 * 
 * Supported models:
 * - Real-ESRGAN x4plus (general photos)
 * - Real-ESRGAN x4plus anime (sharper, for anime/illustrations)
 * - SwinIR (future support)
 * 
 * Memory management:
 * - Tile-based processing to handle large images
 * - Configurable tile size and overlap
 * - Automatic memory monitoring and GC triggers
 */

package com.imagedit.app.ultradetail

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.min

private const val TAG = "OnnxSuperResolution"

/**
 * ONNX model configuration
 */
enum class OnnxSRModel(
    val assetPath: String,
    val scaleFactor: Int,
    val description: String,
    val recommendedTileSize: Int
) {
    REAL_ESRGAN_X4(
        "realesrgan_x4plus_fp32.onnx",
        4,
        "Real-ESRGAN x4plus (best quality)",
        256
    ),
    REAL_ESRGAN_X4_ANIME(
        "realesrgan_x4plus_anime_fp32.onnx",
        4,
        "Real-ESRGAN x4plus anime",
        256
    ),
    SWINIR_X4(
        "swinir_x4_fp16.onnx",
        4,
        "SwinIR x4 (transformer-based)",
        128
    )
}

/**
 * ONNX SR configuration
 */
data class OnnxSRConfig(
    val model: OnnxSRModel = OnnxSRModel.REAL_ESRGAN_X4,
    val tileSize: Int = model.recommendedTileSize,
    val overlap: Int = 16,
    val useGpu: Boolean = true,
    val numThreads: Int = 4,
    val modelFilePath: String? = null  // Optional: load from file instead of assets
)

/**
 * ONNX Runtime-based super-resolution processor
 */
class OnnxSuperResolution(
    private val context: Context,
    private val config: OnnxSRConfig = OnnxSRConfig()
) : AutoCloseable {
    
    private var ortSession: OrtSession? = null
    private var ortEnv: OrtEnvironment? = null
    private var isInitialized = false
    
    /**
     * Initialize ONNX Runtime session
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext true
        
        try {
            Log.i(TAG, "Initializing ONNX SR with model: ${config.model.description}")
            
            // Create ONNX Runtime environment
            ortEnv = OrtEnvironment.getEnvironment()
            
            // Load model from file or assets
            val modelBytes = if (config.modelFilePath != null) {
                loadModelFromFile(config.modelFilePath)
            } else {
                loadModelFromAssets(config.model.assetPath)
            }
            
            if (modelBytes == null) {
                Log.e(TAG, "Failed to load model: ${config.modelFilePath ?: config.model.assetPath}")
                return@withContext false
            }
            
            // Create session options
            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(config.numThreads)
                setInterOpNumThreads(config.numThreads)
                
                // Enable GPU acceleration if available
                if (config.useGpu) {
                    try {
                        // Try NNAPI delegate for Android GPU
                        addNnapi()
                        Log.d(TAG, "NNAPI GPU delegate enabled")
                    } catch (e: Exception) {
                        Log.w(TAG, "NNAPI not available: ${e.message}")
                    }
                }
                
                // Optimization settings
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            }
            
            // Create session
            ortSession = ortEnv!!.createSession(modelBytes, sessionOptions)
            
            // Log model info
            val inputInfo = ortSession!!.inputInfo
            val outputInfo = ortSession!!.outputInfo
            
            Log.i(TAG, "Model loaded successfully:")
            inputInfo.forEach { (name, info) ->
                Log.d(TAG, "  Input: $name, shape: ${info.info.shape.contentToString()}")
            }
            outputInfo.forEach { (name, info) ->
                Log.d(TAG, "  Output: $name, shape: ${info.info.shape.contentToString()}")
            }
            
            isInitialized = true
            Log.i(TAG, "ONNX SR initialized successfully")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ONNX SR", e)
            false
        }
    }
    
    /**
     * Upscale image using ONNX model
     */
    suspend fun upscale(
        input: Bitmap,
        progressCallback: ((Int, Int, String) -> Unit)? = null
    ): Bitmap = withContext(Dispatchers.Default) {
        
        if (!isInitialized) {
            Log.w(TAG, "ONNX SR not initialized, returning original")
            return@withContext input
        }
        
        val inputWidth = input.width
        val inputHeight = input.height
        val scaleFactor = config.model.scaleFactor
        val outputWidth = inputWidth * scaleFactor
        val outputHeight = inputHeight * scaleFactor
        
        Log.i(TAG, "Upscaling ${inputWidth}x${inputHeight} → ${outputWidth}x${outputHeight} (${scaleFactor}x)")
        
        // Create output bitmap
        val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        
        // Calculate tiles
        val tileSize = config.tileSize
        val overlap = config.overlap
        val stride = tileSize - overlap
        
        val tilesX = ceil(inputWidth.toFloat() / stride).toInt()
        val tilesY = ceil(inputHeight.toFloat() / stride).toInt()
        val totalTiles = tilesX * tilesY
        
        Log.d(TAG, "Processing ${tilesX}x${tilesY} = $totalTiles tiles (size=$tileSize, overlap=$overlap)")
        
        var tilesProcessed = 0
        
        // Process tiles
        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                yield() // Allow cancellation
                
                // Calculate tile bounds
                val x = tx * stride
                val y = ty * stride
                val w = min(tileSize, inputWidth - x)
                val h = min(tileSize, inputHeight - y)
                
                // Extract tile
                val tileBitmap = Bitmap.createBitmap(input, x, y, w, h)
                
                // Pad tile to model input size if needed
                val paddedTile = if (w < tileSize || h < tileSize) {
                    Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888).apply {
                        val canvas = android.graphics.Canvas(this)
                        canvas.drawBitmap(tileBitmap, 0f, 0f, null)
                    }
                } else {
                    tileBitmap
                }
                
                // Run inference
                val upscaledTile = runInference(paddedTile)
                
                // Crop upscaled tile if it was padded
                val finalTile = if (w < tileSize || h < tileSize) {
                    Bitmap.createBitmap(upscaledTile, 0, 0, w * scaleFactor, h * scaleFactor)
                } else {
                    upscaledTile
                }
                
                // Blend tile into output
                blendTile(output, finalTile, x * scaleFactor, y * scaleFactor, overlap * scaleFactor)
                
                // Cleanup
                tileBitmap.recycle()
                if (paddedTile != tileBitmap) paddedTile.recycle()
                if (finalTile != upscaledTile) finalTile.recycle()
                upscaledTile.recycle()
                
                tilesProcessed++
                progressCallback?.invoke(tilesProcessed, totalTiles, "Upscaling tile $tilesProcessed/$totalTiles")
                
                // Memory management
                if (tilesProcessed % 10 == 0) {
                    System.gc()
                }
            }
        }
        
        Log.i(TAG, "Upscaling complete: ${outputWidth}x${outputHeight}")
        output
    }
    
    /**
     * Run ONNX inference on a single tile
     */
    private fun runInference(tile: Bitmap): Bitmap {
        val session = ortSession ?: throw IllegalStateException("Session not initialized")
        
        val width = tile.width
        val height = tile.height
        val scaleFactor = config.model.scaleFactor
        
        // Convert bitmap to float array (NCHW format: [1, 3, H, W])
        val inputArray = bitmapToFloatArray(tile)
        
        // Create ONNX tensor
        val inputShape = longArrayOf(1, 3, height.toLong(), width.toLong())
        val inputTensor = OnnxTensor.createTensor(ortEnv, inputArray, inputShape)
        
        // Run inference
        val inputs = mapOf(session.inputNames.first() to inputTensor)
        val outputs = session.run(inputs)
        
        // Get output tensor
        val outputTensor = outputs[0].value as Array<Array<Array<FloatArray>>>
        
        // Convert output to bitmap
        val output = floatArrayToBitmap(outputTensor, width * scaleFactor, height * scaleFactor)
        
        // Cleanup
        inputTensor.close()
        outputs.close()
        
        return output
    }
    
    /**
     * Convert bitmap to float array in NCHW format
     * Input: [0, 255] RGB
     * Output: [0, 1] float normalized
     */
    private fun bitmapToFloatArray(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // NCHW: [1, 3, H, W]
        val output = Array(1) { Array(3) { Array(height) { FloatArray(width) } } }
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f
                
                output[0][0][y][x] = r
                output[0][1][y][x] = g
                output[0][2][y][x] = b
            }
        }
        
        return output
    }
    
    /**
     * Convert float array to bitmap
     * Input: [0, 1] float normalized
     * Output: [0, 255] RGB bitmap
     */
    private fun floatArrayToBitmap(
        array: Array<Array<Array<FloatArray>>>,
        width: Int,
        height: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = (array[0][0][y][x] * 255f).toInt().coerceIn(0, 255)
                val g = (array[0][1][y][x] * 255f).toInt().coerceIn(0, 255)
                val b = (array[0][2][y][x] * 255f).toInt().coerceIn(0, 255)
                
                pixels[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
    
    /**
     * Blend tile into output with feathering at edges
     */
    private fun blendTile(
        output: Bitmap,
        tile: Bitmap,
        x: Int,
        y: Int,
        overlapSize: Int
    ) {
        val tileWidth = tile.width
        val tileHeight = tile.height
        val outputWidth = output.width
        val outputHeight = output.height
        
        // Simple copy for now - can add feathering later
        val tilePixels = IntArray(tileWidth * tileHeight)
        tile.getPixels(tilePixels, 0, tileWidth, 0, 0, tileWidth, tileHeight)
        
        val outputPixels = IntArray(tileWidth * tileHeight)
        output.getPixels(outputPixels, 0, tileWidth, x, y, 
            min(tileWidth, outputWidth - x), 
            min(tileHeight, outputHeight - y))
        
        // Blend with feathering in overlap regions
        for (ty in 0 until min(tileHeight, outputHeight - y)) {
            for (tx in 0 until min(tileWidth, outputWidth - x)) {
                val idx = ty * tileWidth + tx
                
                // Calculate blend weight based on distance from tile edge
                var weight = 1f
                
                if (overlapSize > 0) {
                    if (tx < overlapSize && x > 0) {
                        weight = tx.toFloat() / overlapSize
                    }
                    if (ty < overlapSize && y > 0) {
                        weight = min(weight, ty.toFloat() / overlapSize)
                    }
                }
                
                if (weight < 1f) {
                    // Blend
                    val tilePixel = tilePixels[idx]
                    val outPixel = outputPixels[idx]
                    
                    val tr = ((tilePixel shr 16) and 0xFF)
                    val tg = ((tilePixel shr 8) and 0xFF)
                    val tb = (tilePixel and 0xFF)
                    
                    val or = ((outPixel shr 16) and 0xFF)
                    val og = ((outPixel shr 8) and 0xFF)
                    val ob = (outPixel and 0xFF)
                    
                    val r = (tr * weight + or * (1 - weight)).toInt()
                    val g = (tg * weight + og * (1 - weight)).toInt()
                    val b = (tb * weight + ob * (1 - weight)).toInt()
                    
                    outputPixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                } else {
                    outputPixels[idx] = tilePixels[idx]
                }
            }
        }
        
        output.setPixels(outputPixels, 0, tileWidth, x, y,
            min(tileWidth, outputWidth - x),
            min(tileHeight, outputHeight - y))
    }
    
    /**
     * Load model from file system
     */
    private fun loadModelFromFile(filePath: String): ByteArray? {
        return try {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "Model file not found: $filePath")
                return null
            }
            file.readBytes()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model from file: $filePath", e)
            null
        }
    }
    
    /**
     * Load model from assets
     */
    private fun loadModelFromAssets(assetPath: String): ByteArray? {
        return try {
            context.assets.open(assetPath).use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model from assets: $assetPath", e)
            null
        }
    }
    
    /**
     * Check if initialized
     */
    fun isReady(): Boolean = isInitialized
    
    /**
     * Get scale factor
     */
    fun getScaleFactor(): Int = config.model.scaleFactor
    
    /**
     * Release resources
     */
    override fun close() {
        ortSession?.close()
        ortSession = null
        
        // Note: Don't close ortEnv as it's a singleton
        
        isInitialized = false
        Log.d(TAG, "ONNX SR closed")
    }
}
