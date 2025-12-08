package com.imagedit.app.ui.editor.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.imagedit.app.ui.accessibility.AccessibilityUtils
import com.imagedit.app.ui.accessibility.accessibleProgress

/**
 * Inline progress indicator for smart enhancement operations
 */
@Composable
fun SmartEnhancementInlineProgress(
    operation: String,
    progress: Float? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // Announce progress updates
    LaunchedEffect(progress) {
        if (progress != null && progress > 0f) {
            val progressPercent = (progress * 100).toInt()
            if (progressPercent % 25 == 0) { // Announce at 25%, 50%, 75%, 100%
                AccessibilityUtils.announceForAccessibility(
                    context,
                    AccessibilityUtils.createProgressDescription(operation, progress),
                    1
                )
            }
        }
    }
    
    val progressDescription = if (progress != null && progress >= 0f) {
        AccessibilityUtils.createProgressDescription(operation, progress)
    } else {
        "$operation in progress"
    }
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = progressDescription
                liveRegion = LiveRegionMode.Polite
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = operation,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics {
                    contentDescription = "Current operation: $operation"
                }
            )
            
            if (progress != null && progress >= 0f) {
                // Determinate progress
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .accessibleProgress(
                                progress = progress,
                                operationType = operation,
                                isIndeterminate = false
                            ),
                        trackColor = MaterialTheme.colorScheme.surface
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
                        .height(6.dp)
                        .accessibleProgress(
                            progress = 0f,
                            operationType = operation,
                            isIndeterminate = true
                        ),
                    trackColor = MaterialTheme.colorScheme.surface
                )
            }
        }
    }
}