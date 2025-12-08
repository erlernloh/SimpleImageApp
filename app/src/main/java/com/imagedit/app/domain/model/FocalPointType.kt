package com.imagedit.app.domain.model

/**
 * Types of focal points detected in image composition analysis
 */
enum class FocalPointType {
    FACE,       // Human face or skin tones
    COLOR,      // High saturation or contrasting colors
    CONTRAST,   // High contrast areas or edges
    HIGHLIGHT,  // Bright areas that draw attention
    TEXTURE,    // Areas with interesting texture patterns
    GEOMETRIC   // Geometric shapes or patterns
}