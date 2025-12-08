package com.imagedit.app.util.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Utility class for generating and caching photo thumbnails
 */
@Singleton
class ThumbnailGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bitmapPool: BitmapPool
) {
    companion object {
        private const val TAG = "ThumbnailGenerator"
        private const val THUMBNAIL_DIR = "photo_thumbnails"
        private const val THUMBNAIL_QUALITY = 75 // Reduced for faster generation
        private const val DEFAULT_THUMBNAIL_SIZE = 300 // Reduced from 400 for faster processing
    }
    
    private val thumbnailCacheDir: File by lazy {
        File(context.cacheDir, THUMBNAIL_DIR).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }
    
    /**
     * Generate a thumbnail for the given source URI
     * PERFORMANCE: Optimized for fast generation with minimal memory usage
     * @param sourceUri The URI of the source image
     * @param maxSize Maximum dimension (width or height) in pixels
     * @return URI of the cached thumbnail, or null if generation failed
     */
    suspend fun generateThumbnail(
        sourceUri: Uri,
        maxSize: Int = DEFAULT_THUMBNAIL_SIZE
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            // Load bitmap from URI
            val inputStream = context.contentResolver.openInputStream(sourceUri)
                ?: return@withContext null
            
            // Decode with inJustDecodeBounds to get dimensions
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.close()
            
            // Calculate inSampleSize
            val (width, height) = options.outWidth to options.outHeight
            var inSampleSize = 1
            
            if (height > maxSize || width > maxSize) {
                val halfHeight = height / 2
                val halfWidth = width / 2
                
                while ((halfHeight / inSampleSize) >= maxSize && 
                       (halfWidth / inSampleSize) >= maxSize) {
                    inSampleSize *= 2
                }
            }
            
            // Decode with inSampleSize
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.RGB_565 // Use less memory
            }
            
            val inputStream2 = context.contentResolver.openInputStream(sourceUri)
                ?: return@withContext null
            
            val bitmap = BitmapFactory.decodeStream(inputStream2, null, decodeOptions)
            inputStream2.close()
            
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode bitmap from URI: $sourceUri")
                return@withContext null
            }
            
            // Read EXIF orientation and rotate bitmap if needed
            val rotatedBitmap = try {
                val exifInputStream = context.contentResolver.openInputStream(sourceUri)
                val exif = exifInputStream?.let { ExifInterface(it) }
                exifInputStream?.close()
                
                val orientation = exif?.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                ) ?: ExifInterface.ORIENTATION_NORMAL
                
                val rotationDegrees = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
                
                if (rotationDegrees != 0f) {
                    val matrix = Matrix().apply {
                        postRotate(rotationDegrees)
                    }
                    Bitmap.createBitmap(
                        bitmap,
                        0,
                        0,
                        bitmap.width,
                        bitmap.height,
                        matrix,
                        true
                    ).also {
                        if (it != bitmap) bitmap.recycle()
                    }
                } else {
                    bitmap
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read EXIF orientation, using bitmap as-is", e)
                bitmap
            }
            
            // Scale bitmap if still too large
            val scaledBitmap = if (rotatedBitmap.width > maxSize || rotatedBitmap.height > maxSize) {
                val scale = minOf(
                    maxSize.toFloat() / rotatedBitmap.width,
                    maxSize.toFloat() / rotatedBitmap.height
                )
                val scaledWidth = (rotatedBitmap.width * scale).toInt()
                val scaledHeight = (rotatedBitmap.height * scale).toInt()
                
                Bitmap.createScaledBitmap(rotatedBitmap, scaledWidth, scaledHeight, true).also {
                    if (it != rotatedBitmap) rotatedBitmap.recycle()
                }
            } else {
                rotatedBitmap
            }
            
            // Save to cache directory
            val thumbnailFile = File(
                thumbnailCacheDir,
                "thumb_${System.currentTimeMillis()}_${sourceUri.lastPathSegment}.jpg"
            )
            
            FileOutputStream(thumbnailFile).use { out ->
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, out)
            }
            
            scaledBitmap.recycle()
            
            Log.d(TAG, "Thumbnail generated: ${thumbnailFile.absolutePath}")
            Uri.fromFile(thumbnailFile)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error generating thumbnail", e)
            null
        }
    }
    
    /**
     * Clear all cached thumbnails and bitmap pool
     */
    fun clearThumbnailCache() {
        try {
            thumbnailCacheDir.listFiles()?.forEach { file ->
                file.delete()
            }
            // Clear bitmap pool as well
            bitmapPool.clear()
            Log.d(TAG, "Thumbnail cache and bitmap pool cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing thumbnail cache", e)
        }
    }
    
    /**
     * Get the size of the thumbnail cache in bytes
     */
    fun getCacheSize(): Long {
        return thumbnailCacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }
    
    /**
     * Delete old thumbnails (older than specified time)
     */
    fun deleteOldThumbnails(olderThanMillis: Long = 24 * 60 * 60 * 1000) { // 24 hours default
        try {
            val currentTime = System.currentTimeMillis()
            thumbnailCacheDir.listFiles()?.forEach { file ->
                if (currentTime - file.lastModified() > olderThanMillis) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting old thumbnails", e)
        }
    }
}
