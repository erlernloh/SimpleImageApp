package com.imagedit.app.util

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Size
import com.imagedit.app.domain.model.ProcessingMode
import com.imagedit.app.domain.model.ProcessingOperation
import com.imagedit.app.domain.model.ProcessingRecommendation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages processing performance optimization based on device capabilities and user preferences.
 * Provides intelligent recommendations for processing modes and parameters.
 */
@Singleton
class PerformanceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    
    // Device capability thresholds
    private val lowEndMemoryThreshold = 3 * 1024 * 1024 * 1024L // 3GB
    private val midRangeMemoryThreshold = 6 * 1024 * 1024 * 1024L // 6GB
    
    // Processing size limits for each mode
    private val liteModeMaxSize = Size(1920, 1080) // 1080p
    private val mediumModeMaxSize = Size(2560, 1440) // 1440p
    // Advanced mode uses full resolution
    
    /**
     * Calculates the optimal processing size based on the original size and processing mode.
     */
    fun getOptimalProcessingSize(originalSize: Size, mode: ProcessingMode): Size {
        return when (mode) {
            ProcessingMode.LITE -> {
                if (exceedsSize(originalSize, liteModeMaxSize)) {
                    scaleToFit(originalSize, liteModeMaxSize)
                } else {
                    originalSize
                }
            }
            ProcessingMode.MEDIUM -> {
                if (exceedsSize(originalSize, mediumModeMaxSize)) {
                    scaleToFit(originalSize, mediumModeMaxSize)
                } else {
                    originalSize
                }
            }
            ProcessingMode.ADVANCED -> originalSize
        }
    }
    
    /**
     * Determines whether to use simplified algorithms based on the operation and mode.
     */
    fun shouldUseSimplifiedAlgorithm(operation: ProcessingOperation, mode: ProcessingMode): Boolean {
        return when (mode) {
            ProcessingMode.LITE -> true
            ProcessingMode.MEDIUM -> when (operation) {
                ProcessingOperation.HEALING -> true // Use faster healing in medium mode
                else -> false
            }
            ProcessingMode.ADVANCED -> false
        }
    }
    
    /**
     * Estimates processing time for a given bitmap and operation.
     */
    fun estimateProcessingTime(bitmap: Bitmap, operation: ProcessingOperation): Duration {
        val pixelCount = bitmap.width * bitmap.height
        val devicePerformanceMultiplier = getDevicePerformanceMultiplier()
        
        val baseTimeMs = when (operation) {
            ProcessingOperation.SMART_ENHANCE -> (pixelCount / 1_000_000f * 800f).toLong()
            ProcessingOperation.PORTRAIT_ENHANCE -> (pixelCount / 1_000_000f * 1200f).toLong()
            ProcessingOperation.LANDSCAPE_ENHANCE -> (pixelCount / 1_000_000f * 1000f).toLong()
            ProcessingOperation.HEALING_TOOL -> (pixelCount / 1_000_000f * 1800f).toLong()
            ProcessingOperation.HEALING -> (pixelCount / 1_000_000f * 2000f).toLong()
            ProcessingOperation.SCENE_ANALYSIS -> (pixelCount / 1_000_000f * 300f).toLong()
            ProcessingOperation.FILTER_APPLICATION -> (pixelCount / 1_000_000f * 400f).toLong()
            ProcessingOperation.HISTOGRAM_ANALYSIS -> (pixelCount / 1_000_000f * 200f).toLong()
            ProcessingOperation.SKIN_DETECTION -> (pixelCount / 1_000_000f * 500f).toLong()
            ProcessingOperation.COLOR_BALANCE_ANALYSIS -> (pixelCount / 1_000_000f * 250f).toLong()
            ProcessingOperation.EXPOSURE_ANALYSIS -> (pixelCount / 1_000_000f * 150f).toLong()
            ProcessingOperation.DYNAMIC_RANGE_ANALYSIS -> (pixelCount / 1_000_000f * 180f).toLong()
        }
        
        return (baseTimeMs * devicePerformanceMultiplier).toLong().milliseconds
    }
    
    /**
     * Provides processing recommendations based on device capabilities and image characteristics.
     */
    fun adaptToDeviceCapabilities(bitmap: Bitmap): ProcessingRecommendation {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        val totalMemory = getTotalMemory()
        val availableMemory = memoryInfo.availMem
        val pixelCount = bitmap.width * bitmap.height
        val estimatedMemoryUsage = estimateMemoryUsage(bitmap)
        
        val recommendedMode = when {
            totalMemory < lowEndMemoryThreshold || availableMemory < 512 * 1024 * 1024 -> {
                ProcessingMode.LITE
            }
            totalMemory < midRangeMemoryThreshold || pixelCount > 8_000_000 -> {
                ProcessingMode.MEDIUM
            }
            else -> ProcessingMode.ADVANCED
        }
        
        val optimalSize = getOptimalProcessingSize(Size(bitmap.width, bitmap.height), recommendedMode)
        val shouldDownsample = optimalSize.width < bitmap.width || optimalSize.height < bitmap.height
        
        val estimatedTime = estimateProcessingTime(bitmap, ProcessingOperation.SMART_ENHANCE)
        
        val reason = when (recommendedMode) {
            ProcessingMode.LITE -> "Optimized for device performance and battery life"
            ProcessingMode.MEDIUM -> "Balanced performance for your device"
            ProcessingMode.ADVANCED -> "Full quality processing available"
        }
        
        return ProcessingRecommendation(
            recommendedMode = recommendedMode,
            optimalSize = optimalSize,
            estimatedTime = estimatedTime,
            reason = reason,
            shouldDownsample = shouldDownsample,
            estimatedMemoryUsage = estimatedMemoryUsage
        )
    }
    
    /**
     * Gets the optimal portrait enhancement quality mode based on device capabilities.
     */
    fun getPortraitQualityMode(): ProcessingMode {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        val totalMemory = getTotalMemory()
        val availableMemory = memoryInfo.availMem
        
        return when {
            // Low-end devices: Use simple Gaussian blur instead of bilateral filter
            totalMemory < lowEndMemoryThreshold || availableMemory < 512 * 1024 * 1024 -> {
                ProcessingMode.LITE
            }
            // Mid-range devices: Use reduced kernel size (5x5 instead of 15x15)
            totalMemory < midRangeMemoryThreshold -> {
                ProcessingMode.MEDIUM
            }
            // High-end devices: Use full bilateral filter with current parameters
            else -> ProcessingMode.ADVANCED
        }
    }
    
    /**
     * Gets the appropriate bilateral filter kernel size based on processing mode.
     */
    fun getPortraitKernelSize(mode: ProcessingMode): Int {
        return when (mode) {
            ProcessingMode.LITE -> 0 // Skip bilateral filter, use Gaussian blur
            ProcessingMode.MEDIUM -> 5 // Reduced kernel size for faster processing
            ProcessingMode.ADVANCED -> 15 // Full kernel size for best quality
        }
    }
    
    /**
     * Estimates portrait enhancement time based on quality mode.
     */
    fun estimatePortraitEnhancementTime(bitmap: Bitmap, mode: ProcessingMode): Duration {
        val pixelCount = bitmap.width * bitmap.height
        val devicePerformanceMultiplier = getDevicePerformanceMultiplier()
        
        val baseTimeMs = when (mode) {
            ProcessingMode.LITE -> (pixelCount / 1_000_000f * 200f).toLong() // Simple Gaussian blur
            ProcessingMode.MEDIUM -> (pixelCount / 1_000_000f * 600f).toLong() // 5x5 kernel
            ProcessingMode.ADVANCED -> (pixelCount / 1_000_000f * 1200f).toLong() // 15x15 kernel
        }
        
        return (baseTimeMs * devicePerformanceMultiplier).toLong().milliseconds
    }
    
    /**
     * Checks if processing should be cancelled due to low memory conditions.
     */
    fun shouldCancelForMemory(): Boolean {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        // Cancel if available memory is less than 256MB
        return memoryInfo.availMem < 256 * 1024 * 1024
    }
    
    /**
     * Gets the recommended batch size for processing multiple images.
     */
    fun getRecommendedBatchSize(imageSize: Size): Int {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        val availableMemoryMB = memoryInfo.availMem / (1024 * 1024)
        val estimatedImageMemoryMB = (imageSize.width * imageSize.height * 4) / (1024 * 1024) // ARGB_8888
        
        return when {
            availableMemoryMB > 1024 -> min(4, 1024 / estimatedImageMemoryMB)
            availableMemoryMB > 512 -> min(2, 512 / estimatedImageMemoryMB)
            else -> 1
        }.coerceAtLeast(1)
    }
    
    private fun exceedsSize(size: Size, maxSize: Size): Boolean {
        return size.width > maxSize.width || size.height > maxSize.height
    }
    
    private fun scaleToFit(originalSize: Size, maxSize: Size): Size {
        val scaleX = maxSize.width.toFloat() / originalSize.width
        val scaleY = maxSize.height.toFloat() / originalSize.height
        val scale = min(scaleX, scaleY)
        
        return Size(
            (originalSize.width * scale).toInt(),
            (originalSize.height * scale).toInt()
        )
    }
    
    private fun getDevicePerformanceMultiplier(): Float {
        val totalMemory = getTotalMemory()
        val cpuCores = Runtime.getRuntime().availableProcessors()
        
        return when {
            totalMemory >= midRangeMemoryThreshold && cpuCores >= 8 -> 0.7f // High-end device
            totalMemory >= lowEndMemoryThreshold && cpuCores >= 6 -> 1.0f // Mid-range device
            else -> 1.5f // Low-end device
        }
    }
    
    private fun getTotalMemory(): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            memoryInfo.totalMem
        } else {
            // Fallback for older devices
            Runtime.getRuntime().maxMemory()
        }
    }
    
    private fun estimateMemoryUsage(bitmap: Bitmap): Float {
        // Estimate memory usage in MB for processing
        val bitmapMemory = (bitmap.width * bitmap.height * 4) / (1024f * 1024f) // ARGB_8888
        val processingOverhead = bitmapMemory * 2.5f // Processing typically needs 2-3x bitmap memory
        
        return processingOverhead
    }
}