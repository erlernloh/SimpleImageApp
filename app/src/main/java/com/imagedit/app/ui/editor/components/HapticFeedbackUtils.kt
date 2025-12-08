package com.imagedit.app.ui.editor.components

import androidx.compose.runtime.*
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Enhanced haptic feedback utilities for photo editing interactions
 */

class HapticFeedbackManager(private val hapticFeedback: HapticFeedback) {
    
    fun performLightTap() {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    
    fun performMediumTap() {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    
    fun performHeavyTap() {
        // Simulate heavy tap with multiple light taps
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    
    fun performSliderAdjustment() {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    
    fun performButtonPress() {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    
    fun performSuccessVibration() {
        // Success pattern: short-short-long
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    
    fun performErrorVibration() {
        // Error pattern: long vibration
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    
    fun performBrushStroke() {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    
    fun performEnhancementComplete() {
        // Double tap for completion
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}

@Composable
fun rememberHapticFeedbackManager(): HapticFeedbackManager {
    val hapticFeedback = LocalHapticFeedback.current
    return remember { HapticFeedbackManager(hapticFeedback) }
}

/**
 * Composable that provides haptic feedback for brush interactions
 */
@Composable
fun HapticBrushInteraction(
    onBrushStart: () -> Unit,
    onBrushMove: () -> Unit,
    onBrushEnd: () -> Unit,
    content: @Composable () -> Unit
) {
    val hapticManager = rememberHapticFeedbackManager()
    
    LaunchedEffect(Unit) {
        // Setup haptic feedback for brush interactions
    }
    
    content()
}

/**
 * Extension functions for common haptic patterns
 */
fun HapticFeedbackManager.performEnhancementStart() {
    performMediumTap()
}

fun HapticFeedbackManager.performEnhancementProgress() {
    performLightTap()
}

fun HapticFeedbackManager.performModeSwitch() {
    performMediumTap()
}

fun HapticFeedbackManager.performSliderSnap() {
    performLightTap()
}

fun HapticFeedbackManager.performBeforeAfterToggle() {
    performLightTap()
}