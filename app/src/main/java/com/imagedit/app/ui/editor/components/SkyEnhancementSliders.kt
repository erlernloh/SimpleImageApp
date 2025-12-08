package com.imagedit.app.ui.editor.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.imagedit.app.domain.model.SkyEnhancementSettings

/**
 * Sky enhancement sliders for controlling sky-specific adjustments.
 */
@Composable
fun SkyEnhancementSliders(
    settings: SkyEnhancementSettings,
    onSettingsChange: (SkyEnhancementSettings) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Sky Enhancement",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                TextButton(
                    onClick = { isExpanded = !isExpanded }
                ) {
                    Text(
                        text = if (isExpanded) "Less" else "More",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            
            // Basic controls (always visible)
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Contrast boost
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Sky Contrast",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${(settings.contrastBoost * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Slider(
                        value = settings.contrastBoost,
                        onValueChange = { 
                            onSettingsChange(settings.copy(contrastBoost = it))
                        },
                        valueRange = 0f..1f,
                        steps = 19,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                // Saturation boost
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Sky Saturation",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${(settings.saturationBoost * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Slider(
                        value = settings.saturationBoost,
                        onValueChange = { 
                            onSettingsChange(settings.copy(saturationBoost = it))
                        },
                        valueRange = 0f..1f,
                        steps = 19,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            
            // Advanced controls (expandable)
            if (isExpanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Divider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    
                    // Clarity enhancement
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Sky Clarity",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "${(settings.clarityEnhancement * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Slider(
                            value = settings.clarityEnhancement,
                            onValueChange = { 
                                onSettingsChange(settings.copy(clarityEnhancement = it))
                            },
                            valueRange = 0f..1f,
                            steps = 19,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    
                    // Cloud enhancement
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Cloud Definition",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "${(settings.cloudEnhancement * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Slider(
                            value = settings.cloudEnhancement,
                            onValueChange = { 
                                onSettingsChange(settings.copy(cloudEnhancement = it))
                            },
                            valueRange = 0f..1f,
                            steps = 19,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    
                    // Toggle switches
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Enhance Blue Channel",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = settings.enhanceBlueChannel,
                                onCheckedChange = { 
                                    onSettingsChange(settings.copy(enhanceBlueChannel = it))
                                },
                                enabled = enabled
                            )
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Enhance Gradients",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = settings.enhanceGradients,
                                onCheckedChange = { 
                                    onSettingsChange(settings.copy(enhanceGradients = it))
                                },
                                enabled = enabled
                            )
                        }
                    }
                }
            }
        }
    }
}