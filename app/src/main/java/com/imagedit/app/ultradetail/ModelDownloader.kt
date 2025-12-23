/**
 * ModelDownloader.kt - Downloads ML models on-demand
 * 
 * Handles downloading large ML models (like ESRGAN) from remote sources
 * to keep the initial APK size small.
 */

package com.imagedit.app.ultradetail

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

private const val TAG = "ModelDownloader"

/**
 * Download progress state
 */
sealed class DownloadState {
    object Idle : DownloadState()
    object Checking : DownloadState()
    data class Downloading(val progress: Float, val downloadedMB: Float, val totalMB: Float) : DownloadState()
    object Extracting : DownloadState()
    object Complete : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/**
 * Model information
 */
data class ModelInfo(
    val name: String,
    val fileName: String,
    val downloadUrl: String,
    val expectedSizeBytes: Long,
    val description: String,
    val runtime: ModelRuntime = ModelRuntime.TFLITE
)

/**
 * Model type for different runtimes
 */
enum class ModelRuntime {
    TFLITE,  // TensorFlow Lite models
    ONNX     // ONNX Runtime models
}

/**
 * Available models for download
 */
object AvailableModels {
    // TFLite models (legacy)
    val ESRGAN_FP16 = ModelInfo(
        name = "ESRGAN Super-Resolution (Float16)",
        fileName = "esrgan.tflite",
        downloadUrl = "https://github.com/margaretmz/esrgan-e2e-tflite-tutorial/releases/download/v0.1.0/esrgan_fp16.tar.gz",
        expectedSizeBytes = 20_000_000L, // ~20MB compressed
        description = "High-quality 4x upscaling model. Downloads ~20MB.",
        runtime = ModelRuntime.TFLITE
    )
    
    val ESRGAN_INT8 = ModelInfo(
        name = "ESRGAN Super-Resolution (Int8)",
        fileName = "esrgan.tflite",
        downloadUrl = "https://github.com/margaretmz/esrgan-e2e-tflite-tutorial/releases/download/v0.1.0/esrgan_int8.tar.gz",
        expectedSizeBytes = 5_000_000L, // ~5MB compressed
        description = "Faster 4x upscaling model. Downloads ~5MB.",
        runtime = ModelRuntime.TFLITE
    )
    
    // ONNX models (Real-ESRGAN)
    // TODO: Replace with your actual hosting URL (GitHub Releases, CDN, etc.)
    val REAL_ESRGAN_X4_FP16 = ModelInfo(
        name = "Real-ESRGAN x4plus (FP16)",
        fileName = "realesrgan_x4plus_fp16.onnx",
        downloadUrl = "https://github.com/YOUR_USERNAME/YOUR_REPO/releases/download/v1.0.0/realesrgan_x4plus_fp16.onnx",
        expectedSizeBytes = 33_000_000L, // ~33MB
        description = "Best quality 4x upscaling. Downloads ~33MB.",
        runtime = ModelRuntime.ONNX
    )
    
    val REAL_ESRGAN_X4_ANIME_FP16 = ModelInfo(
        name = "Real-ESRGAN x4plus Anime (FP16)",
        fileName = "realesrgan_x4plus_anime_fp16.onnx",
        downloadUrl = "https://github.com/YOUR_USERNAME/YOUR_REPO/releases/download/v1.0.0/realesrgan_x4plus_anime_fp16.onnx",
        expectedSizeBytes = 33_000_000L, // ~33MB
        description = "Sharper 4x upscaling for anime/illustrations. Downloads ~33MB.",
        runtime = ModelRuntime.ONNX
    )
    
    val SWINIR_X4_FP16 = ModelInfo(
        name = "SwinIR x4 (FP16)",
        fileName = "swinir_x4_fp16.onnx",
        downloadUrl = "https://github.com/YOUR_USERNAME/YOUR_REPO/releases/download/v1.0.0/swinir_x4_fp16.onnx",
        expectedSizeBytes = 12_000_000L, // ~12MB
        description = "Transformer-based SR. Downloads ~12MB.",
        runtime = ModelRuntime.ONNX
    )
}

/**
 * Model downloader utility
 */
class ModelDownloader(private val context: Context) {
    
    private val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }
    
    /**
     * Check if a model is available locally
     */
    fun isModelAvailable(model: ModelInfo): Boolean {
        val modelFile = File(modelsDir, model.fileName)
        // Check if file exists and is reasonably sized (not a placeholder)
        return modelFile.exists() && modelFile.length() > 100_000 // > 100KB
    }
    
    /**
     * Get the local path to a model file
     */
    fun getModelPath(model: ModelInfo): String? {
        val modelFile = File(modelsDir, model.fileName)
        return if (modelFile.exists() && modelFile.length() > 100_000) {
            modelFile.absolutePath
        } else {
            null
        }
    }
    
    /**
     * Download a model with progress updates
     */
    fun downloadModel(model: ModelInfo): Flow<DownloadState> = flow {
        emit(DownloadState.Checking)
        
        try {
            val targetFile = File(modelsDir, model.fileName)
            
            // Check if already downloaded
            if (targetFile.exists() && targetFile.length() > 100_000) {
                Log.i(TAG, "Model already exists: ${targetFile.absolutePath}")
                emit(DownloadState.Complete)
                return@flow
            }
            
            // Determine if file is compressed based on URL
            val isCompressed = model.downloadUrl.endsWith(".tar.gz") || model.downloadUrl.endsWith(".gz")
            val tempFile = if (isCompressed) {
                File(context.cacheDir, "model_download.tar.gz")
            } else {
                File(context.cacheDir, "model_download.tmp")
            }
            
            // Download the file
            Log.i(TAG, "Downloading ${model.name} from: ${model.downloadUrl}")
            
            val url = URL(model.downloadUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            
            // Handle redirects manually for GitHub releases
            var finalConnection = connection
            var redirectCount = 0
            while (redirectCount < 5) {
                finalConnection.connect()
                val responseCode = finalConnection.responseCode
                if (responseCode in 300..399) {
                    val redirectUrl = finalConnection.getHeaderField("Location")
                    finalConnection.disconnect()
                    finalConnection = URL(redirectUrl).openConnection() as HttpURLConnection
                    finalConnection.connectTimeout = 30_000
                    finalConnection.readTimeout = 60_000
                    redirectCount++
                } else {
                    break
                }
            }
            
            val totalBytes = finalConnection.contentLength.toLong()
            val totalMB = totalBytes / (1024f * 1024f)
            
            Log.i(TAG, "Download size: $totalBytes bytes (${totalMB}MB)")
            
            var downloadedBytes = 0L
            val buffer = ByteArray(8192)
            
            finalConnection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        
                        val progress = if (totalBytes > 0) {
                            downloadedBytes.toFloat() / totalBytes
                        } else {
                            0f
                        }
                        val downloadedMB = downloadedBytes / (1024f * 1024f)
                        
                        emit(DownloadState.Downloading(progress, downloadedMB, totalMB))
                    }
                }
            }
            
            finalConnection.disconnect()
            
            // Process based on file type
            if (isCompressed) {
                Log.i(TAG, "Download complete, extracting...")
                emit(DownloadState.Extracting)
                
                // Extract the tar.gz file (for TFLite models)
                extractTarGz(tempFile, modelsDir, model.fileName)
                tempFile.delete()
            } else {
                Log.i(TAG, "Download complete, moving to models directory...")
                emit(DownloadState.Extracting)
                
                // Direct copy for ONNX models (not compressed)
                tempFile.renameTo(targetFile)
            }
            
            // Verify the model file
            if (targetFile.exists() && targetFile.length() > 100_000) {
                Log.i(TAG, "Model ready: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                emit(DownloadState.Complete)
            } else {
                throw Exception("Model file is missing or too small")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${model.name}", e)
            emit(DownloadState.Error(e.message ?: "Download failed"))
        }
    }.flowOn(Dispatchers.IO)
    
    /**
     * Extract a tar.gz file and find the .tflite model
     */
    private fun extractTarGz(tarGzFile: File, outputDir: File, targetFileName: String) {
        // For simplicity, we'll use a basic approach:
        // The tar.gz contains a single .tflite file, so we can extract it directly
        
        GZIPInputStream(tarGzFile.inputStream()).use { gzipIn ->
            // TAR format: 512-byte header blocks followed by file content
            val header = ByteArray(512)
            
            while (true) {
                val headerRead = gzipIn.read(header)
                if (headerRead < 512) break
                
                // Check for empty block (end of archive)
                if (header.all { it == 0.toByte() }) break
                
                // Extract filename from header (bytes 0-99)
                val fileNameBytes = header.sliceArray(0 until 100)
                val fileName = String(fileNameBytes).trim('\u0000', ' ')
                
                if (fileName.isEmpty()) break
                
                // Extract file size from header (bytes 124-135, octal)
                val sizeBytes = header.sliceArray(124 until 136)
                val sizeStr = String(sizeBytes).trim('\u0000', ' ')
                val fileSize = if (sizeStr.isNotEmpty()) {
                    try {
                        sizeStr.toLong(8) // Octal
                    } catch (e: NumberFormatException) {
                        0L
                    }
                } else {
                    0L
                }
                
                Log.d(TAG, "TAR entry: $fileName, size: $fileSize")
                
                // Read file content
                if (fileSize > 0) {
                    val content = ByteArray(fileSize.toInt())
                    var totalRead = 0
                    while (totalRead < fileSize) {
                        val read = gzipIn.read(content, totalRead, (fileSize - totalRead).toInt())
                        if (read < 0) break
                        totalRead += read
                    }
                    
                    // Check if this is a .tflite file
                    if (fileName.endsWith(".tflite")) {
                        val outputFile = File(outputDir, targetFileName)
                        FileOutputStream(outputFile).use { out ->
                            out.write(content)
                        }
                        Log.i(TAG, "Extracted: $fileName -> ${outputFile.absolutePath}")
                    }
                    
                    // Skip padding to 512-byte boundary
                    val padding = (512 - (fileSize % 512)) % 512
                    if (padding > 0) {
                        gzipIn.skip(padding)
                    }
                }
            }
        }
    }
    
    /**
     * Delete a downloaded model
     */
    fun deleteModel(model: ModelInfo): Boolean {
        val modelFile = File(modelsDir, model.fileName)
        return if (modelFile.exists()) {
            modelFile.delete()
        } else {
            true
        }
    }
}
