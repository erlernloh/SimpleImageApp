package com.imagedit.app.util.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

object ImageUtils {
    
    /**
     * Load bitmap from URI with EXIF orientation correction
     */
    suspend fun loadBitmap(
        context: Context, 
        uri: Uri, 
        maxWidth: Int = 2048, 
        maxHeight: Int = 2048
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()
            
            // Calculate sample size
            options.inSampleSize = calculateInSampleSize(options, maxWidth, maxHeight)
            options.inJustDecodeBounds = false
            
            val newInputStream = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(newInputStream, null, options)
            newInputStream?.close()
            
            // Apply EXIF orientation correction
            bitmap?.let { correctBitmapOrientation(context, uri, it) }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Correct bitmap orientation based on EXIF data
     */
    private fun correctBitmapOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val exif = ExifInterface(inputStream!!)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            inputStream.close()
            
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(-90f)
                    matrix.postScale(-1f, 1f)
                }
                else -> return bitmap
            }
            
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: IOException) {
            bitmap
        }
    }
    
    /**
     * Calculate sample size for bitmap loading
     */
    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        
        return inSampleSize
    }
    
    /**
     * Get original date taken from URI
     */
    suspend fun getOriginalDateTaken(context: Context, uri: Uri): Long? = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val exif = ExifInterface(inputStream!!)
            val dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME)
            inputStream.close()
            
            dateTime?.let {
                // Parse EXIF datetime format: "YYYY:MM:DD HH:MM:SS"
                val sdf = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                sdf.parse(it)?.time
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Copy EXIF data from source URI to destination file
     * Preserves camera info, GPS, date/time, and other metadata
     */
    suspend fun copyExifData(
        context: Context,
        sourceUri: Uri,
        destinationPath: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Read EXIF from source
            val sourceStream = context.contentResolver.openInputStream(sourceUri) ?: return@withContext false
            val sourceExif = ExifInterface(sourceStream)
            sourceStream.close()
            
            // Write EXIF to destination
            val destExif = ExifInterface(destinationPath)
            
            // Copy important EXIF tags
            val tagsToPreserve = listOf(
                // Date and time
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_DATETIME_DIGITIZED,
                
                // Camera info
                ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL,
                // TAG_SOFTWARE will be set below
                
                // Camera settings
                ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_F_NUMBER,
                ExifInterface.TAG_ISO_SPEED_RATINGS,
                ExifInterface.TAG_FOCAL_LENGTH,
                ExifInterface.TAG_FLASH,
                ExifInterface.TAG_WHITE_BALANCE,
                
                // GPS data
                ExifInterface.TAG_GPS_LATITUDE,
                ExifInterface.TAG_GPS_LATITUDE_REF,
                ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_GPS_LONGITUDE_REF,
                ExifInterface.TAG_GPS_ALTITUDE,
                ExifInterface.TAG_GPS_ALTITUDE_REF,
                ExifInterface.TAG_GPS_TIMESTAMP,
                ExifInterface.TAG_GPS_DATESTAMP,
                
                // Image dimensions (but NOT orientation - bitmap is already rotated)
                ExifInterface.TAG_IMAGE_WIDTH,
                ExifInterface.TAG_IMAGE_LENGTH,
                
                // Other metadata
                ExifInterface.TAG_ARTIST,
                ExifInterface.TAG_COPYRIGHT,
                ExifInterface.TAG_USER_COMMENT
            )
            
            // Copy each tag
            tagsToPreserve.forEach { tag ->
                sourceExif.getAttribute(tag)?.let { value ->
                    destExif.setAttribute(tag, value)
                }
            }
            
            // Set orientation to NORMAL since bitmap is already correctly rotated
            destExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            
            // Update software tag to indicate editing
            destExif.setAttribute(ExifInterface.TAG_SOFTWARE, "Photara Photo Editor")
            
            // Save EXIF data
            destExif.saveAttributes()
            
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Save bitmap to MediaStore with EXIF metadata preservation
     */
    suspend fun saveBitmap(
        context: Context,
        bitmap: Bitmap,
        filename: String,
        originalDateTaken: Long? = null,
        sourceUri: Uri? = null,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = 90,
        mimeType: String = "image/jpeg",
        description: String? = null
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Photara")
                
                // Preserve original date taken if available
                if (originalDateTaken != null) {
                    put(android.provider.MediaStore.Images.Media.DATE_TAKEN, originalDateTaken)
                }
                
                // Add description/title for identification
                if (description != null) {
                    put(android.provider.MediaStore.Images.Media.DESCRIPTION, description)
                    put(android.provider.MediaStore.Images.Media.TITLE, description)
                }
            }
            
            val uri = context.contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: throw IOException("Failed to create MediaStore entry")
            
            // Save bitmap
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(format, quality, outputStream)
            } ?: throw IOException("Failed to open output stream")
            
            // Copy EXIF data from source if available (for JPEG only)
            if (sourceUri != null && format == Bitmap.CompressFormat.JPEG) {
                try {
                    // Get file path from URI
                    val projection = arrayOf(android.provider.MediaStore.Images.Media.DATA)
                    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val columnIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATA)
                            val filePath = cursor.getString(columnIndex)
                            if (filePath != null) {
                                copyExifData(context, sourceUri, filePath)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // EXIF copy failed, but image was saved successfully
                    // Continue without failing the entire operation
                }
            }
            
            Result.success(uri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Write a bitmap to an arbitrary destination URI (e.g., SAF or share export), preserving EXIF for JPEGs.
     * For destinations without direct file path, uses a temporary file to apply EXIF, then copies bytes to dest.
     */
    suspend fun writeBitmapToUriWithExif(
        context: Context,
        bitmap: Bitmap,
        destinationUri: Uri,
        sourceUri: Uri?,
        format: Bitmap.CompressFormat,
        quality: Int
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            if (format != Bitmap.CompressFormat.JPEG || sourceUri == null) {
                // Non-JPEG or no source; write directly
                context.contentResolver.openOutputStream(destinationUri)?.use { os ->
                    bitmap.compress(format, quality, os)
                } ?: throw IOException("Failed to open output stream")
                return@withContext Result.success(destinationUri)
            }

            // JPEG with EXIF preservation: write to temp file first
            val tempFile = kotlin.runCatching {
                java.io.File.createTempFile("export_tmp_", ".jpg", context.cacheDir)
            }.getOrElse { throw IOException("Failed to create temp file") }

            // 1) Write JPEG to temp
            java.io.FileOutputStream(tempFile).use { fos ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos)) {
                    throw IOException("Failed to compress JPEG to temp file")
                }
            }

            // 2) Copy EXIF from source to temp file
            kotlin.runCatching {
                copyExifData(context, sourceUri, tempFile.absolutePath)
            }.onFailure {
                // EXIF copy failure shouldn't fail export
            }

            // 3) Copy temp bytes to destinationUri
            context.contentResolver.openOutputStream(destinationUri)?.use { dst ->
                java.io.FileInputStream(tempFile).use { fis ->
                    fis.copyTo(dst)
                }
            } ?: throw IOException("Failed to open destination output stream")

            // 4) Cleanup temp file
            kotlin.runCatching { tempFile.delete() }

            Result.success(destinationUri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
