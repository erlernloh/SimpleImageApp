package com.imagedit.app.domain.model

/**
 * Types of processing operations for performance management.
 * Used by PerformanceManager to make optimization decisions.
 */
enum class ProcessingOperation {
    /**
     * Smart enhancement operation using histogram analysis.
     */
    SMART_ENHANCE,
    
    /**
     * Portrait enhancement with skin detection and smoothing.
     */
    PORTRAIT_ENHANCE,
    
    /**
     * Landscape enhancement with sky and foliage optimization.
     */
    LANDSCAPE_ENHANCE,
    
    /**
     * Healing tool operation for spot removal.
     */
    HEALING_TOOL,
    
    /**
     * Healing operation for texture synthesis.
     */
    HEALING,
    
    /**
     * Scene analysis operation for automatic detection.
     */
    SCENE_ANALYSIS,
    
    /**
     * Histogram analysis operation.
     */
    HISTOGRAM_ANALYSIS,
    
    /**
     * Skin tone detection operation.
     */
    SKIN_DETECTION,
    
    /**
     * Color balance analysis operation.
     */
    COLOR_BALANCE_ANALYSIS,
    
    /**
     * Exposure analysis operation.
     */
    EXPOSURE_ANALYSIS,
    
    /**
     * Dynamic range analysis operation.
     */
    DYNAMIC_RANGE_ANALYSIS,
    
    /**
     * General image filtering.
     */
    FILTER_APPLICATION
}