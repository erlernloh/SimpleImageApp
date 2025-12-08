package com.imagedit.app.domain.model

/**
 * Types of scenes that can be detected in photos for intelligent enhancement
 */
enum class SceneType {
    /**
     * Portrait photos with human subjects
     */
    PORTRAIT,
    
    /**
     * Landscape and outdoor scenes
     */
    LANDSCAPE,
    
    /**
     * Food photography
     */
    FOOD,
    
    /**
     * Night and low-light photography
     */
    NIGHT,
    
    /**
     * Indoor photography
     */
    INDOOR,
    
    /**
     * Macro and close-up photography
     */
    MACRO,
    
    /**
     * Unknown or mixed scene types
     */
    UNKNOWN
}