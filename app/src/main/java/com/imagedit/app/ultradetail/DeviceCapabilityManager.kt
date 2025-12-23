/**
 * DeviceCapabilityManager.kt - Device capability detection and thermal management
 * 
 * Provides:
 * 1. Device capability detection (RAM, processor tier)
 * 2. Thermal monitoring with pause/resume callbacks
 * 3. Adaptive configuration based on device tier
 * 
 * Based on consultant recommendations for Android optimization.
 */

package com.imagedit.app.ultradetail

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

private const val TAG = "DeviceCapabilityManager"

/**
 * Device performance tier based on hardware capabilities
 */
enum class DeviceTier {
    /** Budget devices: 4GB RAM, older processors */
    BUDGET,
    /** Mid-range devices: 6-8GB RAM, recent mid-tier processors */
    MID_RANGE,
    /** Flagship devices: 8GB+ RAM, flagship processors */
    FLAGSHIP
}

/**
 * Thermal state of the device
 */
enum class ThermalState {
    /** Normal operating temperature (<40°C) */
    NORMAL,
    /** Elevated temperature (40-45°C) - reduce workload */
    WARM,
    /** High temperature (>45°C) - pause processing */
    HOT,
    /** Critical temperature (>50°C) - abort processing */
    CRITICAL
}

/**
 * Device capability information
 */
data class DeviceCapability(
    val tier: DeviceTier,
    val totalRamMb: Long,
    val availableRamMb: Long,
    val processorName: String,
    val coreCount: Int,
    val recommendedTileSize: Int,
    val recommendedFrameCount: Int,
    val recommendedThreadCount: Int,
    val maxMemoryBudgetMb: Long
) {
    companion object {
        fun unknown() = DeviceCapability(
            tier = DeviceTier.MID_RANGE,
            totalRamMb = 4096,
            availableRamMb = 2048,
            processorName = "Unknown",
            coreCount = 4,
            recommendedTileSize = 256,
            recommendedFrameCount = 8,
            recommendedThreadCount = 2,
            maxMemoryBudgetMb = 400
        )
    }
}

/**
 * Thermal status with temperature reading
 */
data class ThermalStatus(
    val state: ThermalState,
    val temperatureCelsius: Float,
    val shouldPause: Boolean,
    val shouldAbort: Boolean,
    val message: String
)

/**
 * Callback interface for thermal events
 */
interface ThermalCallback {
    fun onThermalStateChanged(status: ThermalStatus)
    fun onPauseRequested(reason: String)
    fun onResumeAllowed()
    fun onAbortRequired(reason: String)
}

/**
 * Device capability manager for adaptive processing
 */
class DeviceCapabilityManager(private val context: Context) {
    
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    
    private val _thermalState = MutableStateFlow(ThermalStatus(
        state = ThermalState.NORMAL,
        temperatureCelsius = 30f,
        shouldPause = false,
        shouldAbort = false,
        message = "Normal"
    ))
    val thermalState: StateFlow<ThermalStatus> = _thermalState.asStateFlow()
    
    private var thermalCallback: ThermalCallback? = null
    
    // Thermal thresholds (Celsius)
    companion object {
        const val TEMP_NORMAL_MAX = 40f
        const val TEMP_WARM_MAX = 45f
        const val TEMP_HOT_MAX = 50f
        const val TEMP_RESUME_THRESHOLD = 40f  // Resume when cooled to this
        
        // Polling interval for thermal monitoring
        const val THERMAL_POLL_INTERVAL_MS = 2000L
    }
    
    /**
     * Detect device capabilities and return recommended configuration
     */
    fun detectCapabilities(): DeviceCapability {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val totalRamMb = memInfo.totalMem / (1024 * 1024)
        val availableRamMb = memInfo.availMem / (1024 * 1024)
        val coreCount = Runtime.getRuntime().availableProcessors()
        
        // Detect processor from Build info
        val processorName = detectProcessorName()
        
        // Determine device tier
        val tier = when {
            totalRamMb >= 8192 && isHighEndProcessor(processorName) -> DeviceTier.FLAGSHIP
            totalRamMb >= 6144 -> DeviceTier.MID_RANGE
            else -> DeviceTier.BUDGET
        }
        
        // Calculate recommended settings based on tier
        val (tileSize, frameCount, threadCount, memoryBudget) = when (tier) {
            DeviceTier.FLAGSHIP -> Quadruple(512, 12, minOf(coreCount, 8), 700L)
            DeviceTier.MID_RANGE -> Quadruple(256, 8, minOf(coreCount, 4), 500L)
            DeviceTier.BUDGET -> Quadruple(128, 6, minOf(coreCount, 2), 300L)
        }
        
        val capability = DeviceCapability(
            tier = tier,
            totalRamMb = totalRamMb,
            availableRamMb = availableRamMb,
            processorName = processorName,
            coreCount = coreCount,
            recommendedTileSize = tileSize,
            recommendedFrameCount = frameCount,
            recommendedThreadCount = threadCount,
            maxMemoryBudgetMb = memoryBudget
        )
        
        Log.i(TAG, "Device capability detected: tier=${tier}, RAM=${totalRamMb}MB, " +
                   "cores=$coreCount, processor=$processorName, " +
                   "recommended: tile=${tileSize}, frames=$frameCount, threads=$threadCount")
        
        return capability
    }
    
    /**
     * Read current device temperature from thermal zones
     */
    fun readTemperature(): Float {
        // Try multiple thermal zone paths
        val thermalPaths = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/devices/virtual/thermal/thermal_zone0/temp",
            "/sys/class/hwmon/hwmon0/temp1_input"
        )
        
        for (path in thermalPaths) {
            try {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    val tempStr = file.readText().trim()
                    val tempValue = tempStr.toLongOrNull() ?: continue
                    
                    // Temperature is usually in millidegrees Celsius
                    val tempCelsius = if (tempValue > 1000) {
                        tempValue / 1000f
                    } else {
                        tempValue.toFloat()
                    }
                    
                    // Sanity check: reasonable temperature range
                    if (tempCelsius in 10f..100f) {
                        return tempCelsius
                    }
                }
            } catch (e: Exception) {
                // Continue to next path
            }
        }
        
        // Fallback: use battery temperature if available
        return readBatteryTemperature() ?: 35f  // Default assumption
    }
    
    /**
     * Read battery temperature as fallback
     */
    private fun readBatteryTemperature(): Float? {
        try {
            val batteryPath = "/sys/class/power_supply/battery/temp"
            val file = File(batteryPath)
            if (file.exists() && file.canRead()) {
                val tempStr = file.readText().trim()
                val tempValue = tempStr.toIntOrNull() ?: return null
                return tempValue / 10f  // Battery temp is in tenths of degrees
            }
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }
    
    /**
     * Get current thermal status
     */
    fun getThermalStatus(): ThermalStatus {
        val temp = readTemperature()
        
        val (state, shouldPause, shouldAbort, message) = when {
            temp >= TEMP_HOT_MAX -> Quadruple(
                ThermalState.CRITICAL,
                true,
                true,
                "Device critically hot (${temp.toInt()}°C). Processing aborted."
            )
            temp >= TEMP_WARM_MAX -> Quadruple(
                ThermalState.HOT,
                true,
                false,
                "Device hot (${temp.toInt()}°C). Pausing to cool down..."
            )
            temp >= TEMP_NORMAL_MAX -> Quadruple(
                ThermalState.WARM,
                false,
                false,
                "Device warm (${temp.toInt()}°C). Reducing workload."
            )
            else -> Quadruple(
                ThermalState.NORMAL,
                false,
                false,
                "Temperature normal (${temp.toInt()}°C)"
            )
        }
        
        return ThermalStatus(
            state = state,
            temperatureCelsius = temp,
            shouldPause = shouldPause,
            shouldAbort = shouldAbort,
            message = message
        )
    }
    
    /**
     * Start thermal monitoring with callback
     */
    fun setThermalCallback(callback: ThermalCallback?) {
        thermalCallback = callback
    }
    
    /**
     * Check thermal status and handle pause/resume
     * Call this periodically during processing
     * 
     * @return true if processing should continue, false if paused/aborted
     */
    suspend fun checkThermalAndWait(): Boolean {
        var status = getThermalStatus()
        _thermalState.value = status
        
        // Notify callback of state change
        thermalCallback?.onThermalStateChanged(status)
        
        if (status.shouldAbort) {
            thermalCallback?.onAbortRequired(status.message)
            return false
        }
        
        if (status.shouldPause) {
            Log.w(TAG, "Thermal pause: ${status.message}")
            thermalCallback?.onPauseRequested(status.message)
            
            // Wait for temperature to drop
            var waitCount = 0
            val maxWaitSeconds = 60  // Maximum wait time
            
            while (status.temperatureCelsius > TEMP_RESUME_THRESHOLD && waitCount < maxWaitSeconds) {
                delay(THERMAL_POLL_INTERVAL_MS)
                status = getThermalStatus()
                _thermalState.value = status
                waitCount += 2
                
                if (status.shouldAbort) {
                    thermalCallback?.onAbortRequired(status.message)
                    return false
                }
                
                Log.d(TAG, "Cooling... ${status.temperatureCelsius}°C (waited ${waitCount}s)")
            }
            
            if (status.temperatureCelsius <= TEMP_RESUME_THRESHOLD) {
                Log.i(TAG, "Temperature cooled to ${status.temperatureCelsius}°C, resuming")
                thermalCallback?.onResumeAllowed()
            } else {
                Log.w(TAG, "Timeout waiting for cooldown, continuing anyway")
            }
        }
        
        return true
    }
    
    /**
     * Get adaptive tile size based on current thermal state
     */
    fun getAdaptiveTileSize(baseTileSize: Int): Int {
        val status = getThermalStatus()
        return when (status.state) {
            ThermalState.NORMAL -> baseTileSize
            ThermalState.WARM -> baseTileSize * 3 / 4  // Reduce by 25%
            ThermalState.HOT -> baseTileSize / 2       // Reduce by 50%
            ThermalState.CRITICAL -> baseTileSize / 4  // Minimal
        }
    }
    
    /**
     * Get adaptive thread count based on thermal state
     */
    fun getAdaptiveThreadCount(baseThreadCount: Int): Int {
        val status = getThermalStatus()
        return when (status.state) {
            ThermalState.NORMAL -> baseThreadCount
            ThermalState.WARM -> maxOf(baseThreadCount - 1, 1)
            ThermalState.HOT -> maxOf(baseThreadCount / 2, 1)
            ThermalState.CRITICAL -> 1
        }
    }
    
    /**
     * Detect processor name from Build info
     */
    private fun detectProcessorName(): String {
        // Try to get from Build.HARDWARE or Build.BOARD
        val hardware = Build.HARDWARE.lowercase()
        val board = Build.BOARD.lowercase()
        val model = Build.MODEL.lowercase()
        
        return when {
            hardware.contains("qcom") || hardware.contains("snapdragon") -> "Snapdragon"
            hardware.contains("exynos") -> "Exynos"
            hardware.contains("tensor") || model.contains("pixel") -> "Tensor"
            hardware.contains("mt") || hardware.contains("mediatek") -> "MediaTek"
            hardware.contains("kirin") -> "Kirin"
            else -> Build.HARDWARE
        }
    }
    
    /**
     * Check if processor is high-end based on name
     */
    private fun isHighEndProcessor(processorName: String): Boolean {
        val name = processorName.lowercase()
        return name.contains("snapdragon") ||
               name.contains("tensor") ||
               name.contains("exynos") ||
               name.contains("dimensity")
    }
    
    /**
     * Helper data class for quadruple values
     */
    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}

/**
 * Quality tier for multi-tier presets
 */
enum class QualityTier(
    val displayName: String,
    val description: String,
    val frameCount: Int,
    val tileSize: Int,
    val useEsrgan: Boolean,
    val estimatedTimeSeconds: Int
) {
    /** Quick processing: fewer frames, smaller tiles, no ESRGAN */
    QUICK(
        displayName = "Quick",
        description = "Fast processing, good quality",
        frameCount = 4,
        tileSize = 256,
        useEsrgan = false,
        estimatedTimeSeconds = 30
    ),
    
    /** Standard quality: balanced settings */
    QUALITY(
        displayName = "Quality",
        description = "Balanced speed and quality",
        frameCount = 8,
        tileSize = 512,
        useEsrgan = false,
        estimatedTimeSeconds = 120
    ),
    
    /** Maximum quality: all features enabled */
    MAXIMUM(
        displayName = "Maximum",
        description = "Best quality, longer processing",
        frameCount = 12,
        tileSize = 512,
        useEsrgan = true,
        estimatedTimeSeconds = 300
    )
}

/**
 * Extension function to get quality tier configuration
 */
fun DeviceCapability.getQualityTierConfig(tier: QualityTier): QualityTierConfig {
    // Adjust based on device capability
    val adjustedFrameCount = when (this.tier) {
        DeviceTier.BUDGET -> minOf(tier.frameCount, 6)
        DeviceTier.MID_RANGE -> minOf(tier.frameCount, 10)
        DeviceTier.FLAGSHIP -> tier.frameCount
    }
    
    val adjustedTileSize = when (this.tier) {
        DeviceTier.BUDGET -> minOf(tier.tileSize, 256)
        DeviceTier.MID_RANGE -> minOf(tier.tileSize, 384)
        DeviceTier.FLAGSHIP -> tier.tileSize
    }
    
    val adjustedUseEsrgan = when (this.tier) {
        DeviceTier.BUDGET -> false  // Never use ESRGAN on budget devices
        DeviceTier.MID_RANGE -> tier.useEsrgan && tier != QualityTier.MAXIMUM
        DeviceTier.FLAGSHIP -> tier.useEsrgan
    }
    
    return QualityTierConfig(
        qualityTier = tier,
        frameCount = adjustedFrameCount,
        tileSize = adjustedTileSize,
        useEsrgan = adjustedUseEsrgan,
        threadCount = this.recommendedThreadCount,
        estimatedTimeSeconds = calculateEstimatedTime(tier, this.tier)
    )
}

/**
 * Calculate estimated processing time based on quality and device tier
 */
private fun calculateEstimatedTime(qualityTier: QualityTier, deviceTier: DeviceTier): Int {
    val baseTime = qualityTier.estimatedTimeSeconds
    return when (deviceTier) {
        DeviceTier.BUDGET -> baseTime * 2      // 2x slower on budget
        DeviceTier.MID_RANGE -> (baseTime * 1.3).toInt()  // 30% slower on mid-range
        DeviceTier.FLAGSHIP -> baseTime
    }
}

/**
 * Configuration for a quality tier adjusted for device capability
 */
data class QualityTierConfig(
    val qualityTier: QualityTier,
    val frameCount: Int,
    val tileSize: Int,
    val useEsrgan: Boolean,
    val threadCount: Int,
    val estimatedTimeSeconds: Int
)

fun DeviceCapability.getRawBurstBestFrameCount(preset: UltraDetailPreset): Int {
    return when (this.tier) {
        DeviceTier.FLAGSHIP -> when (preset) {
            UltraDetailPreset.MAX -> 6
            UltraDetailPreset.ULTRA -> 8
            else -> 4
        }
        DeviceTier.MID_RANGE -> when (preset) {
            UltraDetailPreset.MAX -> 4
            UltraDetailPreset.ULTRA -> 6
            else -> 3
        }
        DeviceTier.BUDGET -> when (preset) {
            UltraDetailPreset.MAX -> 3
            UltraDetailPreset.ULTRA -> 4
            else -> 2
        }
    }
}
