package com.imagedit.app.ui.editor.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.imagedit.app.ui.accessibility.AccessibilityUtils
import com.imagedit.app.ui.accessibility.enhancedAccessibility

/**
 * Dialog for displaying smart enhancement errors with recovery options
 */
@Composable
fun SmartEnhancementErrorDialog(
    error: String?,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    if (error != null) {
        // Announce error to screen readers
        LaunchedEffect(error) {
            AccessibilityUtils.announceForAccessibility(
                context, 
                "Enhancement failed: $error", 
                2
            )
        }
        
        Dialog(
            onDismissRequest = onDismiss,
        ) {
            Card(
                modifier = modifier
                    .padding(16.dp)
                    .semantics {
                        contentDescription = "Error dialog: Enhancement failed. $error"
                    },
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Error icon
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp)
                    )
                    
                    // Title
                    Text(
                        text = "Enhancement Failed",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics {
                            heading()
                        }
                    )
                    
                    // Error message
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics {
                            contentDescription = "Error details: $error"
                        }
                    )
                    
                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .enhancedAccessibility(
                                    contentDescription = "Dismiss error dialog",
                                    role = Role.Button,
                                    onClick = onDismiss
                                )
                        ) {
                            Text("OK")
                        }
                        
                        if (onRetry != null) {
                            Button(
                                onClick = {
                                    onDismiss()
                                    onRetry()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .enhancedAccessibility(
                                        contentDescription = "Retry enhancement operation",
                                        role = Role.Button,
                                        onClick = {
                                            onDismiss()
                                            onRetry()
                                        }
                                    )
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }
        }
    }
}