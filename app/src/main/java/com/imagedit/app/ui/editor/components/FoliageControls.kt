package com.imagedit.app.ui.editor.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.imagedit.app.domain.model.FoliageEnhancementSettings

/**
 * Foliage enhancement controls for vegetation-specific adjustments.
 */
@Composable
fun FoliageControls(
    settings: FoliageEnhancementSettings,
    onSettingsChange: (FoliageEnhancementSettings) -> Unit,
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
                    text = "Foliage Enhancement",
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
                // Green saturation
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Green Saturation",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${(settings.greenSaturation * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Slider(
                        value = settings.greenSaturation,
                        onValueChange = { 
                            onSettingsChange(settings.copy(greenSaturation = it))
                        },
                        valueRange = 0f..1f,
                        steps = 19,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                // Detail enhancement
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Foliage Detail",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${(settings.detailEnhancement * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Slider(
                        value = settings.detailEnhancement,
                        onValueChange = { 
                            onSettingsChange(settings.copy(detailEnhancement = it))
                        },
                        valueRange = 0f..1f,
                        steps = 19,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                // Vibrance boost
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Foliage Vibrance",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${(settings.vibranceBoost * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Slider(
                        value = settings.vibranceBoost,
                        onValueChange = { 
                            onSettingsChange(settings.copy(vibranceBoost = it))
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
                    
                    // Autumn color boost
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Autumn Colors",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "${(settings.autumnColorBoost * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Slider(
                            value = settings.autumnColorBoost,
                            onValueChange = { 
                                onSettingsChange(settings.copy(autumnColorBoost = it))
                            },
                            valueRange = 0f..1f,
                            steps = 19,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    
                    // Shadow detail recovery
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Shadow Recovery",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "${(settings.shadowDetailRecovery * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Slider(
                            value = settings.shadowDetailRecovery,
                            onValueChange = { 
                                onSettingsChange(settings.copy(shadowDetailRecovery = it))
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
                                text = "Enhance Yellow-Greens",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = settings.enhanceYellowGreens,
                                onCheckedChange = { 
                                    onSettingsChange(settings.copy(enhanceYellowGreens = it))
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
                                text = "Enhance Dark Greens",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = settings.enhanceDarkGreens,
                                onCheckedChange = { 
                                    onSettingsChange(settings.copy(enhanceDarkGreens = it))
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