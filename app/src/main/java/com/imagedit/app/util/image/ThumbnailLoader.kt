package com.imagedit.app.util.image

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thumbnail loader with semaphore to prevent too many concurrent image decoding operations
 * This prevents "Image decoding logging dropped" warnings in logcat
 */
@Singleton
class ThumbnailLoader @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "ThumbnailLoader"
        private const val MAX_CONCURRENT_LOADS = 3
        private const val DEFAULT_THUMBNAIL_SIZE = 300
    }
    
    // Semaphore to limit concurrent thumbnail loads
    // Max 3 concurrent to prevent image decoding overload
    private val loadingSemaphore = Semaphore(MAX_CONCURRENT_LOADS)
    
    /**
     * Load thumbnail with automatic throttling
     * Only 3 thumbnails will load concurrently, others will wait
     */
    suspend fun loadThumbnail(
        uri: Uri,
        maxWidth: Int = DEFAULT_THUMBNAIL_SIZE,
        maxHeight: Int = DEFAULT_THUMBNAIL_SIZE
    ): Bitmap? {
        return loadingSemaphore.withPermit {
            try {
                Log.d(TAG, "Loading thumbnail: $uri (${getCurrentPermits()} permits available)")
                ImageUtils.loadBitmap(context, uri, maxWidth, maxHeight)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading thumbnail: $uri", e)
                null
            }
        }
    }
    
    /**
     * Load thumbnail from URI string
     */
    suspend fun loadThumbnail(
        uriString: String,
        maxWidth: Int = DEFAULT_THUMBNAIL_SIZE,
        maxHeight: Int = DEFAULT_THUMBNAIL_SIZE
    ): Bitmap? {
        return try {
            val uri = Uri.parse(uriString)
            loadThumbnail(uri, maxWidth, maxHeight)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing URI: $uriString", e)
            null
        }
    }
    
    /**
     * Get current available permits (for debugging)
     */
    private fun getCurrentPermits(): Int {
        return loadingSemaphore.availablePermits
    }
}
