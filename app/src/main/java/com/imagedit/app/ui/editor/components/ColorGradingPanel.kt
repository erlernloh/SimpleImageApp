package com.imagedit.app.ui.editor.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.imagedit.app.domain.model.ColorGradingParameters

/**
 * Color grading panel for landscape-specific color adjustments.
 */
@Composable
fun ColorGradingPanel(
    parameters: ColorGradingParameters,
    onParametersChange: (ColorGradingParameters) -> Unit,
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
                    text = "Natural Color Grading",
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
                // Blue boost
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Blue Tones",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${(parameters.blueBoost * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Slider(
                        value = parameters.blueBoost,
                        onValueChange = { 
                            onParametersChange(parameters.copy(blueBoost = it))
                        },
                        valueRange = 0f..1f,
                        steps = 19,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                // Green boost
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Green Tones",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${(parameters.greenBoost * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Slider(
                        value = parameters.greenBoost,
                        onValueChange = { 
                            onParametersChange(parameters.copy(greenBoost = it))
                        },
                        valueRange = 0f..1f,
                        steps = 19,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                // Earth tone enhancement
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Earth Tones",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${(parameters.earthToneEnhancement * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Slider(
                        value = parameters.earthToneEnhancement,
                        onValueChange = { 
                            onParametersChange(parameters.copy(earthToneEnhancement = it))
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
                    
                    // Warmth adjustment
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Warmth",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "${(parameters.warmthAdjustment * 100).toInt()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Slider(
                            value = parameters.warmthAdjustment,
                            onValueChange = { 
                                onParametersChange(parameters.copy(warmthAdjustment = it))
                            },
                            valueRange = -1f..1f,
                            steps = 39, // 40 steps for -100 to +100
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    
                    // Tint adjustment
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Tint",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "${(parameters.tintAdjustment * 100).toInt()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Slider(
                            value = parameters.tintAdjustment,
                            onValueChange = { 
                                onParametersChange(parameters.copy(tintAdjustment = it))
                            },
                            valueRange = -1f..1f,
                            steps = 39, // 40 steps for -100 to +100
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    
                    // Split toning section
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Split Toning",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Switch(
                                checked = parameters.applySplitToning,
                                onCheckedChange = { 
                                    onParametersChange(parameters.copy(applySplitToning = it))
                                },
                                enabled = enabled
                            )
                        }
                        
                        // Split toning intensity (only when enabled)
                        if (parameters.applySplitToning) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Split Intensity",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "${(parameters.splitToningIntensity * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                
                                Slider(
                                    value = parameters.splitToningIntensity,
                                    onValueChange = { 
                                        onParametersChange(parameters.copy(splitToningIntensity = it))
                                    },
                                    valueRange = 0f..1f,
                                    steps = 19,
                                    enabled = enabled,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}