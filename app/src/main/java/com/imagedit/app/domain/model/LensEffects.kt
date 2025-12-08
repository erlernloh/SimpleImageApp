package com.imagedit.app.domain.model

data class LensEffects(
    val vignetteAmount: Float = 0f,
    val vignetteMidpoint: Float = 0.5f,
    val vignetteFeather: Float = 0.5f,
    val chromaticAberration: Float = 0f,
    val distortion: Float = 0f
) {
    companion object {
        val NONE = LensEffects()
        
        fun default() = NONE
    }
}
