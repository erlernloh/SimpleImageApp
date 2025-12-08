package com.imagedit.app.ui.editor.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
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
 * Dialog for validating healing operations and showing warnings
 */
@Composable
fun HealingValidationDialog(
    isVisible: Boolean,
    validationType: HealingValidationType,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    if (isVisible) {
        // Announce validation issue to screen readers
        LaunchedEffect(validationType) {
            val announcement = if (validationType.isWarning) {
                "Warning: ${validationType.title}. ${validationType.message}"
            } else {
                "Validation issue: ${validationType.title}. ${validationType.message}"
            }
            AccessibilityUtils.announceForAccessibility(
                context,
                announcement,
                2
            )
        }
        
        Dialog(onDismissRequest = onCancel) {
            Card(
                modifier = modifier
                    .padding(16.dp)
                    .semantics {
                        contentDescription = "Healing validation dialog: ${validationType.title}"
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
                    // Warning icon
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = if (validationType.isWarning) "Warning" else "Information",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp)
                    )
                    
                    // Title
                    Text(
                        text = validationType.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics {
                            heading()
                            contentDescription = "Validation issue: ${validationType.title}"
                        }
                    )
                    
                    // Message
                    Text(
                        text = validationType.message,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics {
                            contentDescription = "Details: ${validationType.message}"
                        }
                    )
                    
                    // Additional info if available
                    validationType.additionalInfo?.let { info ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.semantics {
                                contentDescription = "Additional information: $info"
                            }
                        ) {
                            Text(
                                text = info,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    
                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier
                                .weight(1f)
                                .enhancedAccessibility(
                                    contentDescription = "${validationType.cancelText}. Cancel the healing operation.",
                                    role = Role.Button,
                                    onClick = onCancel
                                )
                        ) {
                            Text(validationType.cancelText)
                        }
                        
                        Button(
                            onClick = onConfirm,
                            modifier = Modifier
                                .weight(1f)
                                .enhancedAccessibility(
                                    contentDescription = "${validationType.confirmText}. ${if (validationType.isWarning) "Proceed despite warning." else "Confirm action."}",
                                    role = Role.Button,
                                    onClick = onConfirm
                                ),
                            colors = if (validationType.isWarning) {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            } else {
                                ButtonDefaults.buttonColors()
                            }
                        ) {
                            Text(validationType.confirmText)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Types of healing validation dialogs
 */
sealed class HealingValidationType(
    val title: String,
    val message: String,
    val confirmText: String,
    val cancelText: String,
    val isWarning: Boolean = false,
    val additionalInfo: String? = null
) {
    object AreaTooLarge : HealingValidationType(
        title = "Large Area Selected",
        message = "The selected area is quite large. Healing large areas may take longer and produce less accurate results.",
        confirmText = "Continue Anyway",
        cancelText = "Adjust Selection",
        isWarning = true,
        additionalInfo = "For best results, try selecting smaller areas or break large areas into multiple healing operations."
    )
    
    object NoSourceFound : HealingValidationType(
        title = "No Suitable Source Found",
        message = "Unable to find a suitable source area for healing. The algorithm couldn't locate similar texture patterns.",
        confirmText = "Try Manual Selection",
        cancelText = "Cancel",
        additionalInfo = "You can manually select a source area by tapping and holding on a similar texture region."
    )
    
    object LowQualityMatch : HealingValidationType(
        title = "Low Quality Match",
        message = "The best matching source area has low similarity. The healing result may not blend seamlessly.",
        confirmText = "Proceed",
        cancelText = "Try Different Area",
        isWarning = true,
        additionalInfo = "Consider selecting a different area or manually choosing a source region with similar texture."
    )
    
    object InsufficientMemory : HealingValidationType(
        title = "Insufficient Memory",
        message = "Not enough memory available for this healing operation. Try reducing the selected area size.",
        confirmText = "Reduce Quality",
        cancelText = "Cancel",
        additionalInfo = "The operation can proceed with reduced processing quality to save memory."
    )
    
    object EdgeArea : HealingValidationType(
        title = "Edge Area Selected",
        message = "The selected area is near the image edge. Healing may be less effective due to limited surrounding context.",
        confirmText = "Continue",
        cancelText = "Adjust Selection",
        isWarning = true
    )
    
    data class CustomValidation(
        val customTitle: String,
        val customMessage: String,
        val customConfirmText: String = "Continue",
        val customCancelText: String = "Cancel",
        val customIsWarning: Boolean = false,
        val customAdditionalInfo: String? = null
    ) : HealingValidationType(
        title = customTitle,
        message = customMessage,
        confirmText = customConfirmText,
        cancelText = customCancelText,
        isWarning = customIsWarning,
        additionalInfo = customAdditionalInfo
    )
}