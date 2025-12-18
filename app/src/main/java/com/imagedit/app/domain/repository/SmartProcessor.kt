package com.imagedit.app.domain.repository

import android.graphics.Bitmap
import android.graphics.Rect
import com.imagedit.app.domain.model.*

/**
 * Interface for smart photo enhancement processing using algorithmic analysis.
 * Provides intelligent enhancement capabilities without requiring external APIs or ML models.
 */
interface SmartProcessor {
    
    /**
     * Analyzes the scene characteristics of a photo using histogram and color analysis.
     * @param bitmap The input image to analyze
     * @return SceneAnalysis containing detected scene type, confidence, and enhancement suggestions
     */
    suspend fun analyzeScene(bitmap: Bitmap): Result<SceneAnalysis>
    
    /**
     * Applies intelligent one-tap enhancement using histogram analysis and heuristics.
     * @param bitmap The input image to enhance
     * @param mode Processing quality mode (Lite/Medium/Advanced)
     * @param sceneAnalysis Optional cached scene analysis to avoid re-analyzing (performance optimization)
     * @return EnhancementResult containing the enhanced image and applied adjustments
     */
    suspend fun smartEnhance(bitmap: Bitmap, mode: ProcessingMode, sceneAnalysis: SceneAnalysis? = null): Result<EnhancementResult>
    
    /**
     * Applies portrait-specific enhancements including skin smoothing and tone correction.
     * @param bitmap The input portrait image
     * @param intensity Enhancement intensity from 0.0 to 1.0
     * @param mode Processing quality mode
     * @return Enhanced portrait image
     */
    suspend fun enhancePortrait(bitmap: Bitmap, intensity: Float, mode: ProcessingMode): Result<Bitmap>
    
    /**
     * Applies landscape-specific enhancements including sky and foliage optimization.
     * @param bitmap The input landscape image
     * @param parameters Landscape enhancement parameters
     * @param mode Processing quality mode
     * @return Enhanced landscape image
     */
    suspend fun enhanceLandscape(bitmap: Bitmap, parameters: LandscapeParameters, mode: ProcessingMode): Result<Bitmap>
    
    /**
     * Removes spots and blemishes using patch-based texture synthesis.
     * @param bitmap The input image
     * @param maskArea The rectangular area to heal
     * @param mode Processing quality mode
     * @return HealingResult containing the healed image and operation details
     */
    suspend fun healArea(bitmap: Bitmap, maskArea: Rect, mode: ProcessingMode): Result<HealingResult>
    
    /**
     * Heals areas using brush strokes for precise selection.
     * @param bitmap The input image
     * @param brushStrokes List of brush strokes defining areas to heal
     * @param brushSettings Brush configuration settings
     * @param mode Processing quality mode
     * @param progressCallback Optional callback for progress updates (0.0 to 1.0)
     * @return HealingResult containing the healed image and operation details
     */
    suspend fun healWithBrush(
        bitmap: Bitmap, 
        brushStrokes: List<BrushStroke>, 
        brushSettings: HealingBrush, 
        mode: ProcessingMode,
        progressCallback: ((Float) -> Unit)? = null
    ): Result<HealingResult>
    
    /**
     * Validates if a healing area is suitable for processing.
     * @param bitmap The input image
     * @param targetArea The area to validate
     * @return Validation result with success status and any warnings
     */
    fun validateHealingArea(bitmap: Bitmap, targetArea: Rect): HealingValidation
    
    /**
     * Finds source region candidates for manual healing selection.
     * @param bitmap The input image
     * @param targetArea The area that needs healing
     * @param maxCandidates Maximum number of candidates to return
     * @return List of source region candidates with quality scores
     */
    suspend fun findSourceCandidates(
        bitmap: Bitmap, 
        targetArea: Rect, 
        maxCandidates: Int = 5
    ): List<SourceCandidate>
}