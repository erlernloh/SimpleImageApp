package com.imagedit.app.ui.accessibility

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.*

/**
 * Keyboard navigation support for smart photo enhancement features.
 * Provides keyboard shortcuts and navigation for accessibility.
 */
object KeyboardNavigationSupport {
    
    /**
     * Keyboard shortcuts for enhancement operations.
     */
    object Shortcuts {
        val SMART_ENHANCE = Key.S
        val PORTRAIT_ENHANCE = Key.P
        val LANDSCAPE_ENHANCE = Key.L
        val HEALING_TOOL = Key.H
        val UNDO = Key.Z
        val REDO = Key.Y
        val SAVE = Key.S
        val RESET = Key.R
        val BEFORE_AFTER = Key.B
        val ESCAPE = Key.Escape
        val ENTER = Key.Enter
        val SPACE = Key.Spacebar
    }
    
    /**
     * Handle keyboard shortcuts for enhancement operations.
     */
    fun handleKeyboardShortcut(
        keyEvent: KeyEvent,
        onSmartEnhance: () -> Unit = {},
        onPortraitEnhance: () -> Unit = {},
        onLandscapeEnhance: () -> Unit = {},
        onHealingTool: () -> Unit = {},
        onUndo: () -> Unit = {},
        onRedo: () -> Unit = {},
        onSave: () -> Unit = {},
        onReset: () -> Unit = {},
        onBeforeAfter: () -> Unit = {},
        onEscape: () -> Unit = {}
    ): Boolean {
        if (keyEvent.type != KeyEventType.KeyDown) return false
        
        val isCtrlPressed = keyEvent.isCtrlPressed
        val isShiftPressed = keyEvent.isShiftPressed
        
        return when (keyEvent.key) {
            Shortcuts.SMART_ENHANCE -> {
                if (isCtrlPressed && !isShiftPressed) {
                    onSmartEnhance()
                    true
                } else false
            }
            
            Shortcuts.PORTRAIT_ENHANCE -> {
                if (isCtrlPressed && !isShiftPressed) {
                    onPortraitEnhance()
                    true
                } else false
            }
            
            Shortcuts.LANDSCAPE_ENHANCE -> {
                if (isCtrlPressed && !isShiftPressed) {
                    onLandscapeEnhance()
                    true
                } else false
            }
            
            Shortcuts.HEALING_TOOL -> {
                if (isCtrlPressed && !isShiftPressed) {
                    onHealingTool()
                    true
                } else false
            }
            
            Shortcuts.UNDO -> {
                if (isCtrlPressed && !isShiftPressed) {
                    onUndo()
                    true
                } else false
            }
            
            Shortcuts.REDO -> {
                if (isCtrlPressed && !isShiftPressed) {
                    onRedo()
                    true
                } else false
            }
            
            Shortcuts.SAVE -> {
                if (isCtrlPressed && isShiftPressed) {
                    onSave()
                    true
                } else false
            }
            
            Shortcuts.RESET -> {
                if (isCtrlPressed && !isShiftPressed) {
                    onReset()
                    true
                } else false
            }
            
            Shortcuts.BEFORE_AFTER -> {
                if (isCtrlPressed && !isShiftPressed) {
                    onBeforeAfter()
                    true
                } else false
            }
            
            Shortcuts.ESCAPE -> {
                onEscape()
                true
            }
            
            else -> false
        }
    }
    
    /**
     * Get keyboard shortcut descriptions for accessibility announcements.
     */
    fun getShortcutDescriptions(): Map<String, String> {
        return mapOf(
            "Ctrl+S" to "Apply smart enhancement",
            "Ctrl+P" to "Apply portrait enhancement",
            "Ctrl+L" to "Apply landscape enhancement",
            "Ctrl+H" to "Activate healing tool",
            "Ctrl+Z" to "Undo last action",
            "Ctrl+Y" to "Redo last action",
            "Ctrl+Shift+S" to "Save photo",
            "Ctrl+R" to "Reset to original",
            "Ctrl+B" to "Toggle before/after comparison",
            "Escape" to "Cancel current operation or close dialog"
        )
    }
}

/**
 * Modifier extension for keyboard navigation support.
 */
fun Modifier.keyboardNavigationSupport(
    onSmartEnhance: () -> Unit = {},
    onPortraitEnhance: () -> Unit = {},
    onLandscapeEnhance: () -> Unit = {},
    onHealingTool: () -> Unit = {},
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    onSave: () -> Unit = {},
    onReset: () -> Unit = {},
    onBeforeAfter: () -> Unit = {},
    onEscape: () -> Unit = {}
): Modifier = this.onKeyEvent { keyEvent ->
    KeyboardNavigationSupport.handleKeyboardShortcut(
        keyEvent = keyEvent,
        onSmartEnhance = onSmartEnhance,
        onPortraitEnhance = onPortraitEnhance,
        onLandscapeEnhance = onLandscapeEnhance,
        onHealingTool = onHealingTool,
        onUndo = onUndo,
        onRedo = onRedo,
        onSave = onSave,
        onReset = onReset,
        onBeforeAfter = onBeforeAfter,
        onEscape = onEscape
    )
}

/**
 * Modifier extension for focusable elements with enhanced accessibility.
 */
fun Modifier.accessibleFocusable(
    contentDescription: String,
    onFocusChanged: (Boolean) -> Unit = {}
): Modifier = this
    .focusable()
    .onFocusChanged { focusState ->
        onFocusChanged(focusState.isFocused)
    }
    .semantics {
        this.contentDescription = contentDescription
        this.focused = true
    }

/**
 * Composable for keyboard shortcut help overlay.
 */
@Composable
fun KeyboardShortcutHelp(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isVisible) {
        val shortcuts = KeyboardNavigationSupport.getShortcutDescriptions()
        
        // This would typically be implemented as a dialog or overlay
        // showing the keyboard shortcuts in an accessible format
        LaunchedEffect(isVisible) {
            // Announce keyboard shortcuts to screen readers
            val shortcutList = shortcuts.entries.joinToString(", ") { (key, description) ->
                "$key for $description"
            }
            // This would use AccessibilityUtils.announceForAccessibility
        }
    }
}

/**
 * Focus management utilities for enhancement controls.
 */
@Composable
fun EnhancementFocusManager(
    content: @Composable () -> Unit
) {
    val focusManager = LocalFocusManager.current
    
    // Handle focus management for enhancement operations
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onKeyEvent { keyEvent ->
                when (keyEvent.key) {
                    Key.Tab -> {
                        if (keyEvent.isShiftPressed) {
                            focusManager.moveFocus(FocusDirection.Previous)
                        } else {
                            focusManager.moveFocus(FocusDirection.Next)
                        }
                        true
                    }
                    Key.Escape -> {
                        focusManager.clearFocus()
                        true
                    }
                    else -> false
                }
            }
    ) {
        content()
    }
}