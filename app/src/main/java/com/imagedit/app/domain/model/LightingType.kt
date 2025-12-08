package com.imagedit.app.domain.model

/**
 * Types of lighting conditions detected in photos
 */
enum class LightingType {
    /**
     * Natural daylight
     */
    DAYLIGHT,
    
    /**
     * Golden hour lighting
     */
    GOLDEN_HOUR,
    
    /**
     * Blue hour lighting
     */
    BLUE_HOUR,
    
    /**
     * Artificial indoor lighting
     */
    ARTIFICIAL,
    
    /**
     * Mixed lighting sources
     */
    MIXED,
    
    /**
     * Low light conditions
     */
    LOW_LIGHT,
    
    /**
     * Unknown lighting conditions
     */
    UNKNOWN
}