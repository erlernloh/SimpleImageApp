package com.imagedit.app.ui.editor.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.imagedit.app.ui.accessibility.AccessibilityUtils
import com.imagedit.app.ui.accessibility.enhancedAccessibility
import com.imagedit.app.ui.accessibility.AccessibilityAnnouncement

/**
 * Button component for smart enhancement functionality.
 */
@Composable
fun SmartEnhanceButton(
    onClick: () -> Unit,
    isProcessing: Boolean,
    isApplied: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val context = LocalContext.current
    
    // Announce state changes
    LaunchedEffect(isApplied) {
        if (isApplied) {
            AccessibilityUtils.announceForAccessibility(
                context, 
                "Smart enhancement applied successfully", 
                1
            )
        }
    }
    
    LaunchedEffect(isProcessing) {
        if (isProcessing) {
            AccessibilityUtils.announceForAccessibility(
                context, 
                "Smart enhancement processing started", 
                1
            )
        }
    }
    
    val contentDescription = when {
        isProcessing -> "Smart enhancement in progress, please wait"
        isApplied -> "Smart enhancement applied, tap to reapply or adjust"
        else -> "Apply smart enhancement to automatically improve photo"
    }
    
    val stateDescription = when {
        isProcessing -> "Processing"
        isApplied -> "Applied"
        !enabled -> "Disabled"
        else -> "Ready"
    }
    
    Button(
        onClick = onClick,
        enabled = enabled && !isProcessing,
        modifier = modifier.enhancedAccessibility(
            contentDescription = contentDescription,
            role = Role.Button,
            onClick = onClick,
            stateDescription = stateDescription,
            disabled = !enabled || isProcessing
        ),
        colors = if (isApplied) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        } else {
            ButtonDefaults.buttonColors()
        }
    ) {
        if (isProcessing) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(16.dp)
                    .semantics {
                        this.contentDescription = "Smart enhancement processing"
                    },
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Enhancing...")
        } else {
            Icon(
                imageVector = if (isApplied) Icons.Default.Check else Icons.Default.AutoAwesome,
                contentDescription = if (isApplied) "Enhancement applied" else "Smart enhancement",
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                if (isApplied) "Smart Enhancement Applied" else "Smart Enhance"
            )
        }
    }
}