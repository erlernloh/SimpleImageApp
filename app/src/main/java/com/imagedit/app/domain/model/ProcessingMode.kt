package com.imagedit.app.domain.model

/**
 * Processing quality modes for smart enhancement features.
 * Allows users to balance speed, battery life, and image quality.
 */
enum class ProcessingMode {
    /**
     * Lite mode: Fastest processing with lowest battery usage.
     * - Max resolution: 1080p
     * - Simplified algorithms
     * - Optimized for speed and battery life
     */
    LITE,
    
    /**
     * Medium mode: Balanced performance and quality.
     * - Max resolution: 1440p  
     * - Standard algorithms
     * - Good balance of speed and quality
     */
    MEDIUM,
    
    /**
     * Advanced mode: Highest quality processing.
     * - Full resolution processing
     * - Complex algorithms
     * - Best quality regardless of processing time
     */
    ADVANCED
}