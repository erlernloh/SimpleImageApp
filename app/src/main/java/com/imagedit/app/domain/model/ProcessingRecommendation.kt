package com.imagedit.app.domain.model

import android.util.Size
import kotlin.time.Duration
import com.imagedit.app.domain.model.ProcessingOperation

/**
 * Recommendation for processing parameters based on device capabilities and image characteristics.
 */
data class ProcessingRecommendation(
    /**
     * Recommended processing mode for optimal performance.
     */
    val recommendedMode: ProcessingMode,
    
    /**
     * Optimal processing size for the given image and device.
     */
    val optimalSize: Size,
    
    /**
     * Estimated processing time for the recommendation.
     */
    val estimatedTime: Duration,
    
    /**
     * Reason for the recommendation.
     */
    val reason: String,
    
    /**
     * Whether downsampling is recommended.
     */
    val shouldDownsample: Boolean,
    
    /**
     * Memory usage estimate in MB.
     */
    val estimatedMemoryUsage: Float
)