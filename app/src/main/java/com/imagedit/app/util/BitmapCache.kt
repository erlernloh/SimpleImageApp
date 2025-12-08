package com.imagedit.app.util

import android.graphics.Bitmap
import android.util.LruCache
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Simple in-memory cache for processed bitmaps to reduce GC pressure
 * and avoid reprocessing the same image multiple times
 */
@Singleton
class BitmapCache @Inject constructor() {
    
    // Cache size: 25% of available memory, in kilobytes
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 4
    
    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            // Cache size is measured in kilobytes rather than number of items
            return bitmap.byteCount / 1024
        }
        
        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: Bitmap,
            newValue: Bitmap?
        ) {
            // Recycle evicted bitmaps if they're not being replaced
            if (evicted && newValue == null && !oldValue.isRecycled) {
                oldValue.recycle()
            }
        }
    }
    
    /**
     * Add a bitmap to the cache
     */
    fun put(key: String, bitmap: Bitmap) {
        if (get(key) == null) {
            memoryCache.put(key, bitmap)
        }
    }
    
    /**
     * Get a bitmap from the cache
     */
    fun get(key: String): Bitmap? {
        return memoryCache.get(key)
    }
    
    /**
     * Remove a specific bitmap from cache
     */
    fun remove(key: String) {
        memoryCache.remove(key)
    }
    
    /**
     * Clear all cached bitmaps
     */
    fun clear() {
        memoryCache.evictAll()
    }
    
    /**
     * Get cache statistics
     */
    fun getStats(): CacheStats {
        return CacheStats(
            size = memoryCache.size(),
            maxSize = cacheSize,
            hitCount = memoryCache.hitCount(),
            missCount = memoryCache.missCount(),
            evictionCount = memoryCache.evictionCount()
        )
    }
}

data class CacheStats(
    val size: Int,
    val maxSize: Int,
    val hitCount: Int,
    val missCount: Int,
    val evictionCount: Int
)
