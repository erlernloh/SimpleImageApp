package com.imagedit.app.domain.model

/**
 * Parameters for landscape-specific photo enhancements.
 */
data class LandscapeParameters(
    /**
     * Sky enhancement intensity from 0.0 to 1.0.
     * Enhances blue channel and contrast in sky areas.
     */
    val skyEnhancement: Float = 0.3f,
    
    /**
     * Foliage enhancement intensity from 0.0 to 1.0.
     * Boosts green channel saturation and clarity for vegetation.
     */
    val foliageEnhancement: Float = 0.4f,
    
    /**
     * Clarity boost intensity from 0.0 to 1.0.
     * Enhances detail and sharpness in landscape elements.
     */
    val clarityBoost: Float = 0.2f,
    
    /**
     * Whether to apply natural color grading.
     * Enhances earth tones while preserving color harmony.
     */
    val naturalColorGrading: Boolean = true,
    
    /**
     * Water enhancement intensity from 0.0 to 1.0.
     * Enhances reflections and blue tones in water bodies.
     */
    val waterEnhancement: Float = 0.25f,
    
    /**
     * Mountain/rock enhancement intensity from 0.0 to 1.0.
     * Enhances texture and contrast in rocky surfaces.
     */
    val rockEnhancement: Float = 0.15f
) {
    init {
        require(skyEnhancement in 0f..1f) { "Sky enhancement must be between 0.0 and 1.0" }
        require(foliageEnhancement in 0f..1f) { "Foliage enhancement must be between 0.0 and 1.0" }
        require(clarityBoost in 0f..1f) { "Clarity boost must be between 0.0 and 1.0" }
        require(waterEnhancement in 0f..1f) { "Water enhancement must be between 0.0 and 1.0" }
        require(rockEnhancement in 0f..1f) { "Rock enhancement must be between 0.0 and 1.0" }
    }
}