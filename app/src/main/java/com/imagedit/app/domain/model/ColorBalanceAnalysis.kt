package com.imagedit.app.domain.model

/**
 * Results of color balance analysis using gray world and max RGB algorithms.
 */
data class ColorBalanceAnalysis(
    /**
     * Red channel bias from -1.0 to +1.0.
     */
    val redBias: Float,
    
    /**
     * Green channel bias from -1.0 to +1.0.
     */
    val greenBias: Float,
    
    /**
     * Blue channel bias from -1.0 to +1.0.
     */
    val blueBias: Float,
    
    /**
     * Estimated white point using gray world assumption.
     */
    val grayWorldWhitePoint: WhitePoint,
    
    /**
     * Estimated white point using max RGB algorithm.
     */
    val maxRgbWhitePoint: WhitePoint,
    
    /**
     * Overall color temperature in Kelvin.
     */
    val colorTemperature: Float,
    
    /**
     * Tint adjustment from -1.0 (green) to +1.0 (magenta).
     */
    val tint: Float,
    
    /**
     * Suggested color balance correction factors.
     */
    val suggestedCorrection: ColorCorrection
)

/**
 * White point estimation result.
 */
data class WhitePoint(
    /**
     * Red channel multiplier.
     */
    val red: Float,
    
    /**
     * Green channel multiplier.
     */
    val green: Float,
    
    /**
     * Blue channel multiplier.
     */
    val blue: Float,
    
    /**
     * Confidence of white point estimation from 0.0 to 1.0.
     */
    val confidence: Float
)

/**
 * Color correction factors for white balance adjustment.
 */
data class ColorCorrection(
    /**
     * Red channel correction factor.
     */
    val redCorrection: Float,
    
    /**
     * Green channel correction factor.
     */
    val greenCorrection: Float,
    
    /**
     * Blue channel correction factor.
     */
    val blueCorrection: Float
)