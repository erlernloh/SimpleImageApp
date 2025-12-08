package com.imagedit.app.util

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors system memory pressure and notifies callbacks
 * Helps app respond to low memory conditions proactively
 */
@Singleton
class MemoryMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) : ComponentCallbacks2 {
    
    private val callbacks = mutableListOf<(Int) -> Unit>()
    
    init {
        context.registerComponentCallbacks(this)
        Log.d(TAG, "MemoryMonitor initialized")
    }
    
    /**
     * Add a callback to be notified of memory trim events
     */
    fun addCallback(callback: (Int) -> Unit) {
        callbacks.add(callback)
    }
    
    /**
     * Remove a callback
     */
    fun removeCallback(callback: (Int) -> Unit) {
        callbacks.remove(callback)
    }
    
    override fun onTrimMemory(level: Int) {
        val levelName = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
            else -> "UNKNOWN($level)"
        }
        
        Log.w(TAG, "Memory trim requested: $levelName")
        
        // Notify all callbacks
        callbacks.forEach { callback ->
            try {
                callback.invoke(level)
            } catch (e: Exception) {
                Log.e(TAG, "Error in memory callback", e)
            }
        }
    }
    
    override fun onConfigurationChanged(newConfig: Configuration) {
        // No action needed
    }
    
    override fun onLowMemory() {
        Log.e(TAG, "Low memory warning!")
        onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }
    
    /**
     * Get current memory information
     */
    fun getMemoryInfo(): MemoryInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        val runtime = Runtime.getRuntime()
        return MemoryInfo(
            availableMemory = memoryInfo.availMem,
            totalMemory = memoryInfo.totalMem,
            threshold = memoryInfo.threshold,
            lowMemory = memoryInfo.lowMemory,
            usedMemory = runtime.totalMemory() - runtime.freeMemory(),
            maxMemory = runtime.maxMemory()
        )
    }
    
    /**
     * Log current memory status
     */
    fun logMemoryStatus() {
        val info = getMemoryInfo()
        Log.d(TAG, """
            Memory Status:
            - Used: ${info.usedMemory / 1024 / 1024}MB / ${info.maxMemory / 1024 / 1024}MB (${info.usagePercent.toInt()}%)
            - Available: ${info.availableMemory / 1024 / 1024}MB
            - Low Memory: ${info.lowMemory}
        """.trimIndent())
    }
    
    data class MemoryInfo(
        val availableMemory: Long,
        val totalMemory: Long,
        val threshold: Long,
        val lowMemory: Boolean,
        val usedMemory: Long,
        val maxMemory: Long
    ) {
        val usagePercent: Float
            get() = (usedMemory.toFloat() / maxMemory.toFloat()) * 100f
    }
    
    companion object {
        private const val TAG = "MemoryMonitor"
    }
}
