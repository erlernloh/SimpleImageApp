package com.imagedit.app.domain.model

import android.graphics.Rect

/**
 * Analysis results for landscape scene detection and element identification.
 */
data class LandscapeAnalysis(
    /**
     * Detected sky regions in the image.
     * Empty list if no sky areas are detected.
     */
    val skyRegions: List<Rect> = emptyList(),
    
    /**
     * Detected foliage/vegetation regions in the image.
     * Empty list if no vegetation areas are detected.
     */
    val foliageRegions: List<Rect> = emptyList(),
    
    /**
     * Detected water body regions in the image.
     * Empty list if no water areas are detected.
     */
    val waterRegions: List<Rect> = emptyList(),
    
    /**
     * Detected mountain/rock regions in the image.
     * Empty list if no rocky areas are detected.
     */
    val rockRegions: List<Rect> = emptyList(),
    
    /**
     * Detected horizon line position.
     * Null if no clear horizon is detected.
     */
    val horizonLine: HorizonLine? = null,
    
    /**
     * Dominant colors found in the landscape scene.
     * Ordered by prevalence in the image.
     */
    val dominantColors: List<LandscapeColorCluster> = emptyList(),
    
    /**
     * Overall confidence score for landscape scene detection (0.0 to 1.0).
     * Higher values indicate stronger confidence in landscape classification.
     */
    val landscapeConfidence: Float = 0f,
    
    /**
     * Percentage of image area covered by sky (0.0 to 1.0).
     */
    val skyPercentage: Float = 0f,
    
    /**
     * Percentage of image area covered by foliage (0.0 to 1.0).
     */
    val foliagePercentage: Float = 0f,
    
    /**
     * Percentage of image area covered by water (0.0 to 1.0).
     */
    val waterPercentage: Float = 0f,
    
    /**
     * Percentage of image area covered by rocks/mountains (0.0 to 1.0).
     */
    val rockPercentage: Float = 0f,
    
    /**
     * Recommended enhancement parameters based on analysis.
     */
    val recommendedParameters: LandscapeParameters = LandscapeParameters()
) {
    init {
        require(landscapeConfidence in 0f..1f) { "Landscape confidence must be between 0.0 and 1.0" }
        require(skyPercentage in 0f..1f) { "Sky percentage must be between 0.0 and 1.0" }
        require(foliagePercentage in 0f..1f) { "Foliage percentage must be between 0.0 and 1.0" }
        require(waterPercentage in 0f..1f) { "Water percentage must be between 0.0 and 1.0" }
        require(rockPercentage in 0f..1f) { "Rock percentage must be between 0.0 and 1.0" }
    }
    
    /**
     * Returns true if this appears to be a landscape scene based on confidence threshold.
     */
    val isLandscapeScene: Boolean
        get() = landscapeConfidence >= 0.6f
    
    /**
     * Returns the most prominent landscape element type.
     */
    val dominantElement: LandscapeElement
        get() = when {
            skyPercentage >= 0.3f -> LandscapeElement.SKY
            foliagePercentage >= 0.25f -> LandscapeElement.FOLIAGE
            waterPercentage >= 0.2f -> LandscapeElement.WATER
            rockPercentage >= 0.15f -> LandscapeElement.ROCK
            else -> LandscapeElement.UNKNOWN
        }
}

// HorizonLine is now defined in HorizonLine.kt - removed duplicate

/**
 * Types of landscape elements that can be detected.
 */
enum class LandscapeElement {
    SKY,
    FOLIAGE,
    WATER,
    ROCK,
    UNKNOWN
}

/**
 * Represents a cluster of similar colors in the landscape.
 */
data class LandscapeColorCluster(
    /**
     * RGB color value of the cluster center.
     */
    val color: Int,
    
    /**
     * Percentage of image pixels belonging to this cluster (0.0 to 1.0).
     */
    val percentage: Float,
    
    /**
     * Landscape element type this color is associated with.
     */
    val elementType: LandscapeElement = LandscapeElement.UNKNOWN
) {
    init {
        require(percentage in 0f..1f) { "Percentage must be between 0.0 and 1.0" }
    }
}