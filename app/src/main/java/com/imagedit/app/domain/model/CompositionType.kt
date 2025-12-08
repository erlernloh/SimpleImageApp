package com.imagedit.app.domain.model

/**
 * Types of photo composition for analysis
 */
enum class CompositionType {
    /**
     * Portrait orientation or portrait subject
     */
    PORTRAIT,
    
    /**
     * Landscape orientation or landscape subject
     */
    LANDSCAPE,
    
    /**
     * Square aspect ratio composition
     */
    SQUARE,
    
    /**
     * Close-up or macro composition
     */
    CLOSEUP,
    
    /**
     * Wide-angle or panoramic composition
     */
    WIDE_ANGLE,
    
    /**
     * Unknown or mixed composition
     */
    UNKNOWN
}