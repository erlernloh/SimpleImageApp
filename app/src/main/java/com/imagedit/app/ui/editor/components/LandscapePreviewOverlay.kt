package com.imagedit.app.ui.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.imagedit.app.domain.model.LandscapeAnalysis
import com.imagedit.app.domain.model.LandscapeElement

/**
 * Preview overlay that shows detected landscape elements and analysis results.
 */
@Composable
fun LandscapePreviewOverlay(
    analysis: LandscapeAnalysis,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            )
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header with confidence
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Landscape Detected",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                Text(
                    text = "${(analysis.landscapeConfidence * 100).toInt()}% confidence",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Detected elements
            if (analysis.skyPercentage > 0f || analysis.foliagePercentage > 0f || 
                analysis.waterPercentage > 0f || analysis.rockPercentage > 0f) {
                
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Detected Elements:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    
                    // Element indicators
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (analysis.skyPercentage > 0.05f) {
                            LandscapeElementChip(
                                element = LandscapeElement.SKY,
                                percentage = analysis.skyPercentage,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        
                        if (analysis.foliagePercentage > 0.05f) {
                            LandscapeElementChip(
                                element = LandscapeElement.FOLIAGE,
                                percentage = analysis.foliagePercentage,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    
                    if (analysis.waterPercentage > 0.05f || analysis.rockPercentage > 0.05f) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (analysis.waterPercentage > 0.05f) {
                                LandscapeElementChip(
                                    element = LandscapeElement.WATER,
                                    percentage = analysis.waterPercentage,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            
                            if (analysis.rockPercentage > 0.05f) {
                                LandscapeElementChip(
                                    element = LandscapeElement.ROCK,
                                    percentage = analysis.rockPercentage,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
            
            // Horizon line info
            if (analysis.horizonLine != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Landscape,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Horizon detected (${(analysis.horizonLine.confidence * 100).toInt()}% confidence)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Dominant element
            if (analysis.dominantElement != LandscapeElement.UNKNOWN) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = getElementIcon(analysis.dominantElement),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "Dominant: ${getElementName(analysis.dominantElement)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Individual chip showing a detected landscape element with its percentage.
 */
@Composable
private fun LandscapeElementChip(
    element: LandscapeElement,
    percentage: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(getElementColor(element).copy(alpha = 0.2f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = getElementIcon(element),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = getElementColor(element)
            )
            Text(
                text = "${(percentage * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = getElementColor(element)
            )
        }
    }
}

/**
 * Gets the appropriate icon for a landscape element.
 */
private fun getElementIcon(element: LandscapeElement): ImageVector {
    return when (element) {
        LandscapeElement.SKY -> Icons.Default.Cloud
        LandscapeElement.FOLIAGE -> Icons.Default.Park
        LandscapeElement.WATER -> Icons.Default.Water
        LandscapeElement.ROCK -> Icons.Default.Terrain
        LandscapeElement.UNKNOWN -> Icons.Default.Help
    }
}

/**
 * Gets the appropriate color for a landscape element.
 */
@Composable
private fun getElementColor(element: LandscapeElement): Color {
    return when (element) {
        LandscapeElement.SKY -> Color(0xFF2196F3) // Blue
        LandscapeElement.FOLIAGE -> Color(0xFF4CAF50) // Green
        LandscapeElement.WATER -> Color(0xFF00BCD4) // Cyan
        LandscapeElement.ROCK -> Color(0xFF795548) // Brown
        LandscapeElement.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/**
 * Gets the display name for a landscape element.
 */
private fun getElementName(element: LandscapeElement): String {
    return when (element) {
        LandscapeElement.SKY -> "Sky"
        LandscapeElement.FOLIAGE -> "Foliage"
        LandscapeElement.WATER -> "Water"
        LandscapeElement.ROCK -> "Rock/Mountain"
        LandscapeElement.UNKNOWN -> "Unknown"
    }
}