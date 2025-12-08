package com.imagedit.app.ui.accessibility

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import android.content.Context
import android.os.Build
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Utility functions and extensions for accessibility support in smart photo enhancement features.
 */
object AccessibilityUtils {
    
    /**
     * Announces text to screen readers with optional priority.
     */
    fun announceForAccessibility(context: Context, text: String, priority: Int = 0) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) 
                as? android.view.accessibility.AccessibilityManager
            
            if (accessibilityManager?.isEnabled == true) {
                val event = android.view.accessibility.AccessibilityEvent.obtain().apply {
                    eventType = android.view.accessibility.AccessibilityEvent.TYPE_ANNOUNCEMENT
                    className = javaClass.name
                    packageName = context.packageName
                    getText().add(text)
                }
                accessibilityManager.sendAccessibilityEvent(event)
            }
        }
    }
    
    /**
     * Creates semantic description for enhancement progress.
     */
    fun createProgressDescription(
        operationType: String,
        progress: Float,
        isComplete: Boolean = false
    ): String {
        return when {
            isComplete -> "$operationType completed successfully"
            progress > 0f -> "$operationType in progress, ${(progress * 100).toInt()} percent complete"
            else -> "$operationType starting"
        }
    }
    
    /**
     * Creates semantic description for slider values.
     */
    fun createSliderDescription(
        parameterName: String,
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        unit: String = "percent"
    ): String {
        val percentage = ((value - range.start) / (range.endInclusive - range.start) * 100).toInt()
        return "$parameterName set to $percentage $unit"
    }
    
    /**
     * Creates semantic description for enhancement results.
     */
    fun createEnhancementResultDescription(
        enhancementType: String,
        isApplied: Boolean,
        confidence: Float? = null
    ): String {
        return when {
            isApplied && confidence != null -> 
                "$enhancementType applied with ${(confidence * 100).toInt()} percent confidence"
            isApplied -> "$enhancementType applied successfully"
            else -> "$enhancementType not applied"
        }
    }
    
    /**
     * Creates semantic description for scene detection results.
     */
    fun createSceneDetectionDescription(
        sceneType: String,
        confidence: Float
    ): String {
        val confidenceLevel = when {
            confidence >= 0.8f -> "high"
            confidence >= 0.6f -> "medium"
            else -> "low"
        }
        return "Scene detected as $sceneType with $confidenceLevel confidence, ${(confidence * 100).toInt()} percent"
    }
    
    /**
     * Creates semantic description for brush tool settings.
     */
    fun createBrushSettingsDescription(
        size: Float,
        hardness: Float,
        opacity: Float,
        pressureSensitive: Boolean
    ): String {
        return "Brush settings: size ${size.toInt()} pixels, " +
                "hardness ${(hardness * 100).toInt()} percent, " +
                "opacity ${(opacity * 100).toInt()} percent" +
                if (pressureSensitive) ", pressure sensitive enabled" else ", pressure sensitive disabled"
    }
}

/**
 * Modifier extension for enhanced accessibility support.
 */
fun Modifier.enhancedAccessibility(
    contentDescription: String,
    role: Role? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    stateDescription: String? = null,
    disabled: Boolean = false
): Modifier = this.semantics {
    this.contentDescription = contentDescription
    role?.let { this.role = it }
    stateDescription?.let { this.stateDescription = it }
    if (disabled) {
        this.disabled()
    }
    onClick?.let { 
        this.onClick(label = contentDescription) { 
            it()
            true 
        } 
    }
    onLongClick?.let { 
        this.onLongClick(label = "Long press for options") { 
            it()
            true 
        } 
    }
}

/**
 * Modifier extension for slider accessibility with custom actions.
 */
fun Modifier.accessibleSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    parameterName: String,
    onValueChange: (Float) -> Unit,
    steps: Int = 0,
    unit: String = "percent"
): Modifier = this.semantics {
    this.contentDescription = AccessibilityUtils.createSliderDescription(
        parameterName, value, valueRange, unit
    )
    this.stateDescription = "${((value - valueRange.start) / (valueRange.endInclusive - valueRange.start) * 100).toInt()} $unit"
    
    // Custom actions for keyboard/voice control
    this.customActions = listOf(
        CustomAccessibilityAction("Increase $parameterName") {
            val step = (valueRange.endInclusive - valueRange.start) / (if (steps > 0) steps else 20)
            val newValue = (value + step).coerceIn(valueRange)
            if (newValue != value) {
                onValueChange(newValue)
                true
            } else false
        },
        CustomAccessibilityAction("Decrease $parameterName") {
            val step = (valueRange.endInclusive - valueRange.start) / (if (steps > 0) steps else 20)
            val newValue = (value - step).coerceIn(valueRange)
            if (newValue != value) {
                onValueChange(newValue)
                true
            } else false
        },
        CustomAccessibilityAction("Set $parameterName to minimum") {
            if (value != valueRange.start) {
                onValueChange(valueRange.start)
                true
            } else false
        },
        CustomAccessibilityAction("Set $parameterName to maximum") {
            if (value != valueRange.endInclusive) {
                onValueChange(valueRange.endInclusive)
                true
            } else false
        }
    )
}

/**
 * Modifier extension for progress indicators with announcements.
 */
fun Modifier.accessibleProgress(
    progress: Float,
    operationType: String,
    isIndeterminate: Boolean = false
): Modifier = this.semantics {
    this.contentDescription = if (isIndeterminate) {
        "$operationType in progress"
    } else {
        AccessibilityUtils.createProgressDescription(operationType, progress)
    }
    if (!isIndeterminate) {
        this.stateDescription = "${(progress * 100).toInt()} percent complete"
    }
}

/**
 * Composable for accessibility announcements.
 */
@Composable
fun AccessibilityAnnouncement(
    message: String,
    priority: Int = 0
) {
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    
    // Announce to screen readers
    AccessibilityUtils.announceForAccessibility(context, message, priority)
    
    // Provide haptic feedback for important announcements
    if (priority > 0) {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}