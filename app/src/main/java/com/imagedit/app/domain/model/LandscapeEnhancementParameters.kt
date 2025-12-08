package com.imagedit.app.domain.model

/**
 * User-adjustable parameters for landscape enhancement operations.
 * These parameters allow fine-tuning of the enhancement process.
 */
data class LandscapeEnhancementParameters(
    /**
     * Sky enhancement settings.
     */
    val skySettings: SkyEnhancementSettings = SkyEnhancementSettings(),
    
    /**
     * Foliage enhancement settings.
     */
    val foliageSettings: FoliageEnhancementSettings = FoliageEnhancementSettings(),
    
    /**
     * Color grading parameters for natural landscape colors.
     */
    val colorGrading: ColorGradingParameters = ColorGradingParameters(),
    
    /**
     * Overall enhancement intensity (0.0 to 1.0).
     * Acts as a master control for all landscape enhancements.
     */
    val overallIntensity: Float = 0.7f,
    
    /**
     * Whether to apply automatic region detection.
     * If false, enhancements are applied globally.
     */
    val useRegionDetection: Boolean = true,
    
    /**
     * Whether to preserve original colors while enhancing.
     * If true, maintains color relationships while boosting vibrancy.
     */
    val preserveColorHarmony: Boolean = true,
    
    /**
     * Clarity enhancement intensity (0.0 to 1.0).
     * Enhances detail and sharpness across the landscape.
     */
    val clarityIntensity: Float = 0.3f,
    
    /**
     * Vibrance boost intensity (0.0 to 1.0).
     * Selectively enhances less saturated colors.
     */
    val vibranceBoost: Float = 0.4f
) {
    init {
        require(overallIntensity in 0f..1f) { "Overall intensity must be between 0.0 and 1.0" }
        require(clarityIntensity in 0f..1f) { "Clarity intensity must be between 0.0 and 1.0" }
        require(vibranceBoost in 0f..1f) { "Vibrance boost must be between 0.0 and 1.0" }
    }
    
    /**
     * Creates a copy with scaled intensities based on the overall intensity.
     */
    fun withScaledIntensity(): LandscapeEnhancementParameters {
        return copy(
            skySettings = skySettings.copy(
                contrastBoost = skySettings.contrastBoost * overallIntensity,
                saturationBoost = skySettings.saturationBoost * overallIntensity,
                clarityEnhancement = skySettings.clarityEnhancement * overallIntensity
            ),
            foliageSettings = foliageSettings.copy(
                greenSaturation = foliageSettings.greenSaturation * overallIntensity,
                detailEnhancement = foliageSettings.detailEnhancement * overallIntensity,
                vibranceBoost = foliageSettings.vibranceBoost * overallIntensity
            ),
            colorGrading = colorGrading.copy(
                blueBoost = colorGrading.blueBoost * overallIntensity,
                greenBoost = colorGrading.greenBoost * overallIntensity,
                earthToneEnhancement = colorGrading.earthToneEnhancement * overallIntensity
            ),
            clarityIntensity = clarityIntensity * overallIntensity,
            vibranceBoost = vibranceBoost * overallIntensity
        )
    }
}

/**
 * Settings specific to sky enhancement in landscape photos.
 */
data class SkyEnhancementSettings(
    /**
     * Contrast boost for sky regions (0.0 to 1.0).
     * Enhances cloud definition and sky drama.
     */
    val contrastBoost: Float = 0.4f,
    
    /**
     * Saturation boost for sky colors (0.0 to 1.0).
     * Enhances blue tones and sunset colors.
     */
    val saturationBoost: Float = 0.3f,
    
    /**
     * Clarity enhancement for sky details (0.0 to 1.0).
     * Brings out cloud textures and atmospheric details.
     */
    val clarityEnhancement: Float = 0.25f,
    
    /**
     * Whether to enhance blue channel specifically.
     * Boosts blue tones for more vivid skies.
     */
    val enhanceBlueChannel: Boolean = true,
    
    /**
     * Whether to apply gradient enhancement.
     * Enhances natural sky gradients from horizon to zenith.
     */
    val enhanceGradients: Boolean = true,
    
    /**
     * Cloud enhancement intensity (0.0 to 1.0).
     * Specifically targets cloud formations for better definition.
     */
    val cloudEnhancement: Float = 0.35f
) {
    init {
        require(contrastBoost in 0f..1f) { "Contrast boost must be between 0.0 and 1.0" }
        require(saturationBoost in 0f..1f) { "Saturation boost must be between 0.0 and 1.0" }
        require(clarityEnhancement in 0f..1f) { "Clarity enhancement must be between 0.0 and 1.0" }
        require(cloudEnhancement in 0f..1f) { "Cloud enhancement must be between 0.0 and 1.0" }
    }
}

/**
 * Settings specific to foliage enhancement in landscape photos.
 */
data class FoliageEnhancementSettings(
    /**
     * Green channel saturation boost (0.0 to 1.0).
     * Enhances the vibrancy of vegetation.
     */
    val greenSaturation: Float = 0.45f,
    
    /**
     * Detail enhancement for vegetation (0.0 to 1.0).
     * Brings out leaf textures and plant structures.
     */
    val detailEnhancement: Float = 0.3f,
    
    /**
     * Vibrance boost for foliage colors (0.0 to 1.0).
     * Selectively enhances less saturated greens.
     */
    val vibranceBoost: Float = 0.4f,
    
    /**
     * Whether to enhance yellow-green tones.
     * Boosts spring foliage and new growth colors.
     */
    val enhanceYellowGreens: Boolean = true,
    
    /**
     * Whether to enhance dark green tones.
     * Boosts deep forest and evergreen colors.
     */
    val enhanceDarkGreens: Boolean = true,
    
    /**
     * Autumn color enhancement (0.0 to 1.0).
     * Enhances reds, oranges, and yellows in fall foliage.
     */
    val autumnColorBoost: Float = 0.2f,
    
    /**
     * Shadow detail recovery in foliage (0.0 to 1.0).
     * Brings out details in shadowed vegetation areas.
     */
    val shadowDetailRecovery: Float = 0.25f
) {
    init {
        require(greenSaturation in 0f..1f) { "Green saturation must be between 0.0 and 1.0" }
        require(detailEnhancement in 0f..1f) { "Detail enhancement must be between 0.0 and 1.0" }
        require(vibranceBoost in 0f..1f) { "Vibrance boost must be between 0.0 and 1.0" }
        require(autumnColorBoost in 0f..1f) { "Autumn color boost must be between 0.0 and 1.0" }
        require(shadowDetailRecovery in 0f..1f) { "Shadow detail recovery must be between 0.0 and 1.0" }
    }
}

/**
 * Parameters for natural color grading in landscape photography.
 */
data class ColorGradingParameters(
    /**
     * Blue tone enhancement (0.0 to 1.0).
     * Enhances sky and water blue tones.
     */
    val blueBoost: Float = 0.3f,
    
    /**
     * Green tone enhancement (0.0 to 1.0).
     * Enhances vegetation and natural green tones.
     */
    val greenBoost: Float = 0.35f,
    
    /**
     * Earth tone enhancement (0.0 to 1.0).
     * Enhances browns, tans, and rocky surfaces.
     */
    val earthToneEnhancement: Float = 0.25f,
    
    /**
     * Warm tone adjustment (-1.0 to 1.0).
     * Negative values cool the image, positive values warm it.
     */
    val warmthAdjustment: Float = 0.1f,
    
    /**
     * Tint adjustment (-1.0 to 1.0).
     * Negative values add green tint, positive values add magenta tint.
     */
    val tintAdjustment: Float = 0.0f,
    
    /**
     * Whether to apply split toning.
     * Applies different color treatments to highlights and shadows.
     */
    val applySplitToning: Boolean = false,
    
    /**
     * Highlight tint color (RGB).
     * Applied to bright areas when split toning is enabled.
     */
    val highlightTint: Int = 0xFFFFE4B5.toInt(), // Warm highlight
    
    /**
     * Shadow tint color (RGB).
     * Applied to dark areas when split toning is enabled.
     */
    val shadowTint: Int = 0xFF4682B4.toInt(), // Cool shadow
    
    /**
     * Split toning intensity (0.0 to 1.0).
     * Controls the strength of highlight/shadow tinting.
     */
    val splitToningIntensity: Float = 0.15f
) {
    init {
        require(blueBoost in 0f..1f) { "Blue boost must be between 0.0 and 1.0" }
        require(greenBoost in 0f..1f) { "Green boost must be between 0.0 and 1.0" }
        require(earthToneEnhancement in 0f..1f) { "Earth tone enhancement must be between 0.0 and 1.0" }
        require(warmthAdjustment in -1f..1f) { "Warmth adjustment must be between -1.0 and 1.0" }
        require(tintAdjustment in -1f..1f) { "Tint adjustment must be between -1.0 and 1.0" }
        require(splitToningIntensity in 0f..1f) { "Split toning intensity must be between 0.0 and 1.0" }
    }
}