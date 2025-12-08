package com.imagedit.app.ui.common

enum class ExportFormat {
    JPEG,
    PNG,
    WEBP
}

enum class ExportQuality {
    LOW,      // 60%
    MEDIUM,   // 80%
    HIGH,     // 95%
    MAXIMUM   // 100%
}

data class ExportOptions(
    val format: ExportFormat = ExportFormat.JPEG,
    val quality: ExportQuality = ExportQuality.HIGH,
    val resizePercentage: Int = 100 // 100%, 75%, 50%, 25%
)
