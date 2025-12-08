package com.imagedit.app.util.image

import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bitmap pool for reusing bitmap memory to reduce GC pressure
 * Thread-safe implementation using ConcurrentHashMap
 */
@Singleton
class BitmapPool @Inject constructor() {
    private val maxSizeBytes: Long = 20 * 1024 * 1024 // 20MB default (reduced for better performance)
    private val pool = ConcurrentHashMap<String, MutableList<Bitmap>>()
    private var currentSizeBytes: Long = 0
    
    companion object {
        private const val TAG = "BitmapPool"
    }
    
    /**
     * Get a bitmap from the pool or create a new one
     */
    @Synchronized
    fun get(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap? {
        val key = makeKey(width, height, config)
        val bitmaps = pool[key]
        
        return if (bitmaps != null && bitmaps.isNotEmpty()) {
            val bitmap = bitmaps.removeAt(bitmaps.lastIndex)
            currentSizeBytes -= getBitmapSize(bitmap)
            Log.d(TAG, "Reused bitmap from pool: ${width}x${height}, pool size: ${currentSizeBytes / 1024}KB")
            bitmap
        } else {
            null
        }
    }
    
    /**
     * Return a bitmap to the pool for reuse
     */
    @Synchronized
    fun put(bitmap: Bitmap) {
        if (bitmap.isRecycled) {
            return
        }
        
        val size = getBitmapSize(bitmap)
        
        // Check if adding this bitmap would exceed max size
        if (currentSizeBytes + size > maxSizeBytes) {
            // Evict oldest bitmaps until we have space
            evictOldest(size)
        }
        
        val key = makeKey(bitmap.width, bitmap.height, bitmap.config)
        val bitmaps = pool.getOrPut(key) { mutableListOf() }
        
        bitmaps.add(bitmap)
        currentSizeBytes += size
        
        Log.d(TAG, "Added bitmap to pool: ${bitmap.width}x${bitmap.height}, pool size: ${currentSizeBytes / 1024}KB")
    }
    
    /**
     * Clear all bitmaps from the pool
     */
    @Synchronized
    fun clear() {
        pool.values.forEach { bitmaps ->
            bitmaps.forEach { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
        pool.clear()
        currentSizeBytes = 0
        Log.d(TAG, "Bitmap pool cleared")
    }
    
    /**
     * Get current pool size in bytes
     */
    fun getCurrentSize(): Long = currentSizeBytes
    
    /**
     * Get number of bitmaps in pool
     */
    fun getBitmapCount(): Int = pool.values.sumOf { it.size }
    
    /**
     * Trim pool to specified size in bytes
     * Useful for responding to memory pressure
     */
    @Synchronized
    fun trimToSize(maxBytes: Long) {
        if (currentSizeBytes <= maxBytes) {
            return
        }
        
        var freedBytes = 0L
        val iterator = pool.entries.iterator()
        
        while (iterator.hasNext() && currentSizeBytes > maxBytes) {
            val entry = iterator.next()
            val bitmaps = entry.value
            
            while (bitmaps.isNotEmpty() && currentSizeBytes > maxBytes) {
                val bitmap = bitmaps.removeAt(0)
                val size = getBitmapSize(bitmap)
                freedBytes += size
                currentSizeBytes -= size
                
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
            
            if (bitmaps.isEmpty()) {
                iterator.remove()
            }
        }
        
        Log.d(TAG, "Trimmed pool by ${freedBytes / 1024 / 1024}MB, new size: ${currentSizeBytes / 1024 / 1024}MB")
    }
    
    /**
     * Evict oldest bitmaps to make space
     */
    private fun evictOldest(requiredSpace: Long) {
        var freedSpace = 0L
        val iterator = pool.entries.iterator()
        
        while (iterator.hasNext() && currentSizeBytes + requiredSpace - freedSpace > maxSizeBytes) {
            val entry = iterator.next()
            val bitmaps = entry.value
            
            if (bitmaps.isNotEmpty()) {
                val bitmap = bitmaps.removeAt(0)
                val size = getBitmapSize(bitmap)
                freedSpace += size
                currentSizeBytes -= size
                
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
                
                if (bitmaps.isEmpty()) {
                    iterator.remove()
                }
            }
        }
        
        Log.d(TAG, "Evicted ${freedSpace / 1024}KB from pool")
    }
    
    /**
     * Calculate bitmap size in bytes
     */
    private fun getBitmapSize(bitmap: Bitmap): Long {
        return (bitmap.allocationByteCount).toLong()
    }
    
    /**
     * Create a unique key for bitmap dimensions and config
     */
    private fun makeKey(width: Int, height: Int, config: Bitmap.Config): String {
        return "${width}x${height}_${config.name}"
    }
}
