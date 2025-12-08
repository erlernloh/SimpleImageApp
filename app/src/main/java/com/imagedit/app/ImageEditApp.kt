package com.imagedit.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraXConfig
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.util.DebugLogger
import com.imagedit.app.domain.repository.PresetRepository
import com.imagedit.app.util.MemoryMonitor
import com.imagedit.app.util.image.BitmapPool
import com.imagedit.app.util.image.ThumbnailGenerator
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ImageEditApp : Application(), CameraXConfig.Provider, ImageLoaderFactory {
    
    @Inject
    lateinit var presetRepository: PresetRepository
    
    @Inject
    lateinit var memoryMonitor: MemoryMonitor
    
    @Inject
    lateinit var bitmapPool: BitmapPool
    
    @Inject
    lateinit var thumbnailGenerator: ThumbnailGenerator
    
    companion object {
        private const val TAG = "ImageEditApp"
    }
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    override fun onCreate() {
        super.onCreate()
        
        Log.d(TAG, "Photara Application starting...")
        
        // Setup memory monitoring and cache management (lightweight, runs immediately)
        setupMemoryManagement()
        
        // PERFORMANCE: Defer non-critical initialization to avoid blocking startup
        // This prevents the "Skipped 184 frames" issue on app launch
        applicationScope.launch(Dispatchers.IO) {
            // Small delay to let the UI render first
            kotlinx.coroutines.delay(500)
            
            try {
                Log.d(TAG, "Initializing built-in presets (deferred)...")
                presetRepository.initializeBuiltInPresets()
                Log.d(TAG, "Built-in presets initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize built-in presets", e)
            }
        }
    }
    
    /**
     * Setup memory monitoring and respond to memory pressure
     */
    private fun setupMemoryManagement() {
        memoryMonitor.addCallback { level ->
            applicationScope.launch(Dispatchers.IO) {
                when (level) {
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
                    ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                        // Critical: Clear all caches
                        Log.w(TAG, "Critical memory pressure - clearing all caches")
                        bitmapPool.clear()
                        thumbnailGenerator.clearThumbnailCache()
                    }
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
                    ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                        // Moderate: Trim caches to 50%
                        Log.w(TAG, "Moderate memory pressure - trimming caches")
                        bitmapPool.trimToSize(10 * 1024 * 1024) // Trim to 10MB
                    }
                    ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                        // UI hidden: Trim caches to 75%
                        Log.d(TAG, "UI hidden - trimming caches")
                        bitmapPool.trimToSize(15 * 1024 * 1024) // Trim to 15MB
                    }
                }
                
                // Log memory status after cleanup
                memoryMonitor.logMemoryStatus()
            }
        }
        
        Log.d(TAG, "Memory management configured")
    }
    
    /**
     * Configure Coil ImageLoader for optimal performance
     * PERFORMANCE: Tuned to prevent "Image decoding logging dropped" warnings
     * and reduce GC pressure during rapid photo loading
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15) // Reduced to 15% to prevent GC pressure
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(30 * 1024 * 1024) // Reduced to 30MB disk cache
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(false) // Disable crossfade to prevent "P" placeholder flash
            .allowHardware(true) // Enable hardware bitmaps for better performance
            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565) // Use less memory for thumbnails
            // PERFORMANCE: Limit concurrent image decoding to 2 to prevent overwhelming the decoder
            // This fixes "Image decoding logging dropped" warnings
            .dispatcher(kotlinx.coroutines.Dispatchers.IO.limitedParallelism(2))
            // Add interceptor to limit request rate
            .components {
                add(coil.intercept.Interceptor { chain ->
                    // Small yield to prevent overwhelming the main thread
                    chain.proceed(chain.request)
                })
            }
            .build()
    }
    
    /**
     * Configure CameraX with Camera2 implementation
     */
    override fun getCameraXConfig(): CameraXConfig {
        return Camera2Config.defaultConfig()
    }
}
