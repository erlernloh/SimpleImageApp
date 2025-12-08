package com.imagedit.app.domain.repository

import android.graphics.Bitmap
import com.imagedit.app.domain.model.AdjustmentParameters
import com.imagedit.app.domain.model.FilmGrain
import com.imagedit.app.domain.model.LensEffects

interface ImageProcessor {
    suspend fun processImage(
        bitmap: Bitmap,
        adjustments: AdjustmentParameters,
        filmGrain: FilmGrain = FilmGrain.NONE,
        lensEffects: LensEffects = LensEffects.NONE
    ): Result<Bitmap>
    
    suspend fun applyFilter(bitmap: Bitmap, filterName: String): Result<Bitmap>
    
    // Basic image enhancement functions (NOT AI-powered)
    suspend fun autoEnhance(bitmap: Bitmap): Result<Bitmap>
    suspend fun applySmoothing(bitmap: Bitmap): Result<Bitmap>
    suspend fun applySoftening(bitmap: Bitmap, intensity: Float): Result<Bitmap>
}
