package com.imagedit.app.domain.model

/**
 * Sealed class representing errors that can occur during smart photo processing.
 * Provides specific error types for better error handling and user feedback.
 */
sealed class SmartProcessingError : Exception() {
    
    /**
     * Insufficient memory to complete the processing operation.
     * Suggests automatic downscaling or performance mode adjustment.
     */
    object InsufficientMemory : SmartProcessingError() {
        override val message: String = "Insufficient memory for processing. Try reducing image size or switching to Lite mode."
    }
    
    /**
     * Processing operation exceeded the timeout limit.
     * Suggests fallback to simpler algorithms or performance mode adjustment.
     */
    object ProcessingTimeout : SmartProcessingError() {
        override val message: String = "Processing timeout. Try switching to a faster performance mode."
    }
    
    /**
     * The image format is not supported for smart processing.
     * Suggests converting to a supported format.
     */
    object UnsupportedImageFormat : SmartProcessingError() {
        override val message: String = "Image format not supported for smart enhancement."
    }
    
    /**
     * A specific algorithm failed during processing.
     * Provides details about which algorithm failed and the underlying cause.
     */
    data class AlgorithmFailure(
        val algorithm: String,
        override val cause: Throwable
    ) : SmartProcessingError() {
        override val message: String = "Algorithm '$algorithm' failed: ${cause.message}"
    }
    
    /**
     * The selected area for healing is too large to process effectively.
     * Suggests selecting a smaller area for better results.
     */
    object HealingAreaTooLarge : SmartProcessingError() {
        override val message: String = "Selection too large, try smaller areas for better results."
    }
    
    /**
     * No suitable source region found for healing the selected area.
     * Suggests trying a different area or manual editing.
     */
    object NoSuitableSourceRegion : SmartProcessingError() {
        override val message: String = "No suitable source region found for healing. Try selecting a different area."
    }
    
    /**
     * Scene analysis could not determine the photo type with sufficient confidence.
     * Suggests manual scene selection or using general enhancement.
     */
    object SceneAnalysisUncertain : SmartProcessingError() {
        override val message: String = "Unable to determine scene type. Try manual scene selection or general enhancement."
    }
    
    /**
     * Device capabilities are insufficient for the requested processing mode.
     * Suggests switching to a lower performance mode.
     */
    object DeviceCapabilityInsufficient : SmartProcessingError() {
        override val message: String = "Device capabilities insufficient for this processing mode. Try switching to Lite or Medium mode."
    }
}