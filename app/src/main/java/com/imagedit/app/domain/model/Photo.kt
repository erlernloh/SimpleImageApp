package com.imagedit.app.domain.model

import android.net.Uri

data class Photo(
    val id: String,
    val uri: Uri,
    val name: String,
    val timestamp: Long,
    val width: Int,
    val height: Int,
    val size: Long,
    val isFavorite: Boolean = false,
    val hasEdits: Boolean = false,
    val tags: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
) {
    /**
     * Check if this is an Ultra Detail+ photo based on filename
     */
    val isUltraDetail: Boolean
        get() = name.startsWith("UltraDetail_")
    
    /**
     * Check if this is an MFSR (Multi-Frame Super Resolution) upscaled photo
     */
    val isMFSR: Boolean
        get() = name.contains("_MFSR")
}
