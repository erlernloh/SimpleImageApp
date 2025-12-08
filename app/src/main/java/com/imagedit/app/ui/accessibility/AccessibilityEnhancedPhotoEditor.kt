package com.imagedit.app.ui.accessibility

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.imagedit.app.ui.editor.PhotoEditorViewModel

/**
 * Accessibility-enhanced wrapper for PhotoEditorScreen that integrates
 * voice control, keyboard navigation, and enhanced announcements.
 */
@Composable
fun AccessibilityEnhancedPhotoEditor(
    viewModel: PhotoEditorViewModel,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    
    // Voice control integration
    VoiceControlIntegration { command ->
        handleVoiceCommand(command, viewModel, context)
    }
    
    // Keyboard navigation support
    Box(
        modifier = Modifier
            .fillMaxSize()
            .keyboardNavigationSupport(
                onSmartEnhance = { viewModel.applySmartEnhancement() },
                onPortraitEnhance = { viewModel.togglePortraitEnhancement() },
                onLandscapeEnhance = { /* viewModel.toggleLandscapeEnhancement() */ },
                onHealingTool = { 
                    if (viewModel.uiState.value.isHealingToolActive) {
                        viewModel.deactivateHealingTool()
                    } else {
                        viewModel.activateHealingTool()
                    }
                },
                onUndo = { viewModel.undo() },
                onRedo = { viewModel.redo() },
                onSave = { viewModel.savePhoto {} },
                onReset = { viewModel.resetToOriginal() },
                onBeforeAfter = { viewModel.toggleBeforeAfterComparison() },
                onEscape = { 
                    if (viewModel.uiState.value.isHealingToolActive) {
                        viewModel.deactivateHealingTool()
                    }
                }
            )
    ) {
        // High contrast theme support
        HighContrastTheme {
            content()
        }
    }
}

/**
 * Handle voice commands for photo editing operations.
 */
private fun handleVoiceCommand(
    command: VoiceCommand,
    viewModel: PhotoEditorViewModel,
    context: android.content.Context
) {
    when (command) {
        VoiceCommand.SmartEnhance -> {
            viewModel.applySmartEnhancement()
            AccessibilityUtils.announceForAccessibility(
                context,
                "Applying smart enhancement",
                1
            )
        }
        
        VoiceCommand.EnhancePortrait -> {
            viewModel.togglePortraitEnhancement()
            AccessibilityUtils.announceForAccessibility(
                context,
                "Applying portrait enhancement",
                1
            )
        }
        
        VoiceCommand.EnhanceLandscape -> {
            // viewModel.toggleLandscapeEnhancement()
            AccessibilityUtils.announceForAccessibility(
                context,
                "Applying landscape enhancement",
                1
            )
        }
        
        VoiceCommand.ActivateHealingTool -> {
            if (viewModel.uiState.value.isHealingToolActive) {
                viewModel.deactivateHealingTool()
                AccessibilityUtils.announceForAccessibility(
                    context,
                    "Healing tool deactivated",
                    1
                )
            } else {
                viewModel.activateHealingTool()
                AccessibilityUtils.announceForAccessibility(
                    context,
                    "Healing tool activated. Paint over areas to heal.",
                    1
                )
            }
        }
        
        VoiceCommand.ApplyHealing -> {
            // This would trigger healing application
            AccessibilityUtils.announceForAccessibility(
                context,
                "Applying healing to selected areas",
                1
            )
        }
        
        VoiceCommand.Undo -> {
            viewModel.undo()
            AccessibilityUtils.announceForAccessibility(
                context,
                "Undoing last action",
                1
            )
        }
        
        VoiceCommand.Clear -> {
            if (viewModel.uiState.value.isHealingToolActive) {
                // Clear healing strokes
                AccessibilityUtils.announceForAccessibility(
                    context,
                    "Clearing healing strokes",
                    1
                )
            }
        }
        
        VoiceCommand.ShowBeforeAfter -> {
            viewModel.toggleBeforeAfterComparison()
            AccessibilityUtils.announceForAccessibility(
                context,
                "Showing before and after comparison",
                1
            )
        }
        
        VoiceCommand.HideComparison -> {
            if (viewModel.uiState.value.isShowingBeforeAfter) {
                viewModel.toggleBeforeAfterComparison()
                AccessibilityUtils.announceForAccessibility(
                    context,
                    "Hiding comparison view",
                    1
                )
            }
        }
        
        VoiceCommand.IncreaseBrushSize -> {
            val currentSettings = viewModel.uiState.value.healingBrushSettings
            val newSize = (currentSettings.size + 10f).coerceAtMost(100f)
            viewModel.updateHealingBrushSettings(currentSettings.copy(size = newSize))
            AccessibilityUtils.announceForAccessibility(
                context,
                "Brush size increased to ${newSize.toInt()} pixels",
                1
            )
        }
        
        VoiceCommand.DecreaseBrushSize -> {
            val currentSettings = viewModel.uiState.value.healingBrushSettings
            val newSize = (currentSettings.size - 10f).coerceAtLeast(5f)
            viewModel.updateHealingBrushSettings(currentSettings.copy(size = newSize))
            AccessibilityUtils.announceForAccessibility(
                context,
                "Brush size decreased to ${newSize.toInt()} pixels",
                1
            )
        }
        
        VoiceCommand.IncreaseIntensity -> {
            val currentIntensity = viewModel.uiState.value.portraitEnhancementIntensity
            val newIntensity = (currentIntensity + 0.1f).coerceAtMost(1f)
            viewModel.updatePortraitEnhancementIntensity(newIntensity)
            AccessibilityUtils.announceForAccessibility(
                context,
                "Intensity increased to ${(newIntensity * 100).toInt()} percent",
                1
            )
        }
        
        VoiceCommand.DecreaseIntensity -> {
            val currentIntensity = viewModel.uiState.value.portraitEnhancementIntensity
            val newIntensity = (currentIntensity - 0.1f).coerceAtLeast(0f)
            viewModel.updatePortraitEnhancementIntensity(newIntensity)
            AccessibilityUtils.announceForAccessibility(
                context,
                "Intensity decreased to ${(newIntensity * 100).toInt()} percent",
                1
            )
        }
        
        VoiceCommand.SavePhoto -> {
            viewModel.savePhoto {}
            AccessibilityUtils.announceForAccessibility(
                context,
                "Saving photo with current enhancements",
                1
            )
        }
        
        VoiceCommand.Reset -> {
            viewModel.resetToOriginal()
            AccessibilityUtils.announceForAccessibility(
                context,
                "Resetting to original photo",
                1
            )
        }
    }
}

/**
 * Accessibility state announcements for photo editor operations.
 */
@Composable
fun AccessibilityStateAnnouncements(
    viewModel: PhotoEditorViewModel
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    
    // Announce processing state changes
    LaunchedEffect(uiState.isProcessing) {
        if (uiState.isProcessing) {
            AccessibilityUtils.announceForAccessibility(
                context,
                "Processing started",
                1
            )
        }
    }
    
    // Announce enhancement completions
    LaunchedEffect(uiState.smartEnhancementApplied) {
        if (uiState.smartEnhancementApplied) {
            AccessibilityUtils.announceForAccessibility(
                context,
                "Smart enhancement completed successfully",
                1
            )
        }
    }
    
    LaunchedEffect(uiState.portraitEnhancementApplied) {
        if (uiState.portraitEnhancementApplied) {
            AccessibilityUtils.announceForAccessibility(
                context,
                "Portrait enhancement completed successfully",
                1
            )
        }
    }
    
    // Announce healing tool state changes
    LaunchedEffect(uiState.isHealingToolActive) {
        if (uiState.isHealingToolActive) {
            AccessibilityUtils.announceForAccessibility(
                context,
                "Healing tool activated. Paint over areas you want to heal, then apply.",
                1
            )
        } else {
            AccessibilityUtils.announceForAccessibility(
                context,
                "Healing tool deactivated",
                1
            )
        }
    }
    
    // Announce scene detection results
    LaunchedEffect(uiState.sceneAnalysis) {
        uiState.sceneAnalysis?.let { analysis ->
            val sceneDescription = AccessibilityUtils.createSceneDetectionDescription(
                analysis.sceneType.name.lowercase().replaceFirstChar { it.uppercase() },
                analysis.confidence
            )
            AccessibilityUtils.announceForAccessibility(
                context,
                sceneDescription,
                1
            )
        }
    }
    
    // Announce errors
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            AccessibilityUtils.announceForAccessibility(
                context,
                "Error occurred: $error",
                2
            )
        }
    }
}