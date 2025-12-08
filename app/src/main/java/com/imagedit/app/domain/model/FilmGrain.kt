package com.imagedit.app.domain.model

data class FilmGrain(
    val amount: Float = 0f,
    val size: Float = 1f,
    val roughness: Float = 0.5f
) {
    companion object {
        val NONE = FilmGrain()
        
        fun default() = NONE
    }
}
