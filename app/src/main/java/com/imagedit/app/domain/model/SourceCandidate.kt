package com.imagedit.app.domain.model

import android.graphics.Rect

/**
 * Data class for source region candidates with quality scores
 */
data class SourceCandidate(
    val region: Rect,
    val score: Float
)