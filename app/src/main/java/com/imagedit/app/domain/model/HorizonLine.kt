package com.imagedit.app.domain.model

import android.graphics.Rect

/**
 * Represents a detected horizon line in landscape images
 */
data class HorizonLine(
    val y: Int,                 // Y coordinate of the horizon line
    val angle: Float,           // Angle of the horizon line in degrees
    val confidence: Float,      // Confidence of detection (0-1)
    val startX: Int,           // Start X coordinate of the line
    val endX: Int              // End X coordinate of the line
)

/**
 * Represents a line segment for horizon detection
 */
data class LineSegment(
    val y: Int,
    val startX: Int,
    val endX: Int,
    val angle: Float,
    val confidence: Float
)

/**
 * Represents sky replacement settings
 */
data class SkyReplacementSettings(
    val enabled: Boolean = false,
    val skyType: SkyType = SkyType.CLEAR_BLUE,
    val blendIntensity: Float = 0.8f,
    val atmosphericIntensity: Float = 0.5f,
    val colorTemperature: Float = 6500f,
    val cloudiness: Float = 0.3f,
    val timeOfDay: TimeOfDay = TimeOfDay.MIDDAY
)

/**
 * Types of replacement skies
 */
enum class SkyType {
    CLEAR_BLUE,
    PARTLY_CLOUDY,
    DRAMATIC_CLOUDS,
    SUNSET,
    SUNRISE,
    OVERCAST,
    STORMY
}

/**
 * Time of day for sky replacement
 */
enum class TimeOfDay {
    SUNRISE,
    MORNING,
    MIDDAY,
    AFTERNOON,
    SUNSET,
    TWILIGHT
}

/**
 * Advanced foliage region with detailed characteristics
 */
data class FoliageRegion(
    val bounds: Rect,
    val foliageType: FoliageType,
    val density: Float,
    val seasonality: Seasonality,
    val confidence: Float
)

/**
 * Types of foliage detected
 */
enum class FoliageType {
    DECIDUOUS_TREES,
    CONIFEROUS_TREES,
    SHRUBS,
    GRASS,
    MIXED_VEGETATION,
    AGRICULTURAL_CROPS
}

/**
 * Seasonal characteristics of foliage
 */
enum class Seasonality {
    SPRING_GREEN,
    SUMMER_LUSH,
    AUTUMN_COLORS,
    WINTER_BARE,
    EVERGREEN
}