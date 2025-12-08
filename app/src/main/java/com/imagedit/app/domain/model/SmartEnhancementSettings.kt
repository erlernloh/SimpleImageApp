package com.imagedit.app.domain.model

/**
 * Settings for smart enhancement features.
 * Manages user preferences for processing modes and enhancement behaviors.
 */
data class SmartEnhancementSettings(
    val processingMode: ProcessingMode = ProcessingMode.MEDIUM,
    val autoSceneDetection: Boolean = true,
    val portraitEnhancementIntensity: Float = 0.5f,
    val landscapeEnhancementEnabled: Boolean = true,
    val smartEnhancePreservesManualAdjustments: Boolean = true
)

/**
 * Information about a processing mode including user-friendly descriptions
 * and battery impact indicators.
 */
data class ProcessingModeInfo(
    val mode: ProcessingMode,
    val title: String,
    val description: String,
    val batteryImpact: BatteryImpact,
    val processingSpeed: ProcessingSpeed,
    val maxResolution: String,
    val features: List<String>
)

/**
 * Battery impact levels for different processing modes.
 */
enum class BatteryImpact {
    LOW,
    MEDIUM,
    HIGH
}

/**
 * Processing speed indicators for different modes.
 */
enum class ProcessingSpeed {
    FAST,
    BALANCED,
    SLOW
}