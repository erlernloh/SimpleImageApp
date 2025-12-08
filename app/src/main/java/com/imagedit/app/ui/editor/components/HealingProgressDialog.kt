package com.imagedit.app.ui.editor.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
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
import androidx.compose.ui.window.DialogProperties
import com.imagedit.app.ui.accessibility.AccessibilityUtils
import com.imagedit.app.ui.accessibility.enhancedAccessibility
import com.imagedit.app.ui.accessibility.accessibleProgress

/**
 * Dialog showing progress for healing operations with cancellation support
 */
@Composable
fun HealingProgressDialog(
    isVisible: Boolean,
    progress: Float,
    onCancel: () -> Unit,
    canCancel: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    if (isVisible) {
        // Announce progress updates
        LaunchedEffect(progress) {
            if (progress >= 0f) {
                val progressPercent = (progress * 100).toInt()
                if (progressPercent % 25 == 0 && progressPercent > 0) {
                    AccessibilityUtils.announceForAccessibility(
                        context,
                        "Healing progress: $progressPercent percent complete",
                        1
                    )
                }
            }
        }
        
        // Announce when dialog appears
        LaunchedEffect(isVisible) {
            AccessibilityUtils.announceForAccessibility(
                context,
                "Healing operation started. Processing texture patterns.",
                2
            )
        }
        
        Dialog(
            onDismissRequest = { if (canCancel) onCancel() },
            properties = DialogProperties(
                dismissOnBackPress = canCancel,
                dismissOnClickOutside = false
            )
        ) {
            Card(
                modifier = modifier
                    .padding(16.dp)
                    .semantics {
                        contentDescription = "Healing progress dialog"
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
                    Text(
                        text = "Processing Healing",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics {
                            heading()
                        }
                    )
                    
                    Text(
                        text = "Analyzing texture patterns and applying healing algorithms...",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics {
                            contentDescription = "Current operation: Analyzing texture patterns and applying healing algorithms"
                        }
                    )
                    
                    // Progress indicator
                    if (progress >= 0) {
                        // Determinate progress
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LinearProgressIndicator(
                                progress = progress,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .accessibleProgress(
                                        progress = progress,
                                        operationType = "Healing operation",
                                        isIndeterminate = false
                                    ),
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            
                            Text(
                                text = "${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.semantics {
                                    contentDescription = "${(progress * 100).toInt()} percent complete"
                                }
                            )
                        }
                    } else {
                        // Indeterminate progress
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .accessibleProgress(
                                    progress = 0f,
                                    operationType = "Healing operation",
                                    isIndeterminate = true
                                ),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                    
                    // Cancel button
                    if (canCancel) {
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier
                                .fillMaxWidth()
                                .enhancedAccessibility(
                                    contentDescription = "Cancel healing operation",
                                    role = Role.Button,
                                    onClick = onCancel
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cancel,
                                contentDescription = "Cancel",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Simplified progress indicator for inline use
 */
@Composable
fun HealingProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val progressDescription = if (progress >= 0) {
        "Healing progress: ${(progress * 100).toInt()} percent complete"
    } else {
        "Healing in progress"
    }
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = progressDescription
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (progress >= 0) {
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .accessibleProgress(
                        progress = progress,
                        operationType = "Healing",
                        isIndeterminate = false
                    ),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            
            Text(
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics {
                    contentDescription = "${(progress * 100).toInt()} percent complete"
                }
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .accessibleProgress(
                        progress = 0f,
                        operationType = "Healing",
                        isIndeterminate = true
                    ),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            
            Text(
                text = "Processing...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics {
                    contentDescription = "Processing healing operation"
                }
            )
        }
    }
}