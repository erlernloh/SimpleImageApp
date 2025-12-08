package com.imagedit.app.domain.model

import android.graphics.Bitmap
import kotlin.time.Duration

/**
 * Result of smart enhancement processing containing the enhanced image and metadata.
 */
data class EnhancementResult(
    /**
     * The enhanced bitmap image.
     */
    val enhancedBitmap: Bitmap,
    
    /**
     * The adjustment parameters that were applied during enhancement.
     * These values will be reflected in the UI sliders.
     */
    val appliedAdjustments: AdjustmentParameters,
    
    /**
     * Time taken to process the enhancement.
     */
    val processingTime: Duration,
    
    /**
     * Quality metrics and analysis of the enhancement result.
     */
    val qualityMetrics: QualityMetrics
)

