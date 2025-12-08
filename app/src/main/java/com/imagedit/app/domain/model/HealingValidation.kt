package com.imagedit.app.domain.model

/**
 * Data class for healing area validation results
 */
data class HealingValidation(
    val isValid: Boolean,
    val errorMessage: String,
    val isWarning: Boolean = false
)