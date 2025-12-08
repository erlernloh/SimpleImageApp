package com.imagedit.app.ui.editor.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize

/**
 * Overlay component for showing before/after comparison of healing operations
 */
@Composable
fun HealingPreviewOverlay(
    originalBitmap: ImageBitmap?,
    healedBitmap: ImageBitmap?,
    healingRegions: List<androidx.compose.ui.geometry.Rect>,
    showBefore: Boolean,
    onTogglePreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Main canvas for drawing the preview
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val bitmap = if (showBefore) originalBitmap else healedBitmap
            
            bitmap?.let {
                // Draw the image
                drawImage(
                    image = it,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt())
                )
                
                // Highlight healing regions
                healingRegions.forEach { region ->
                    drawHealingRegionHighlight(region, showBefore)
                }
            }
        }
        
        // Preview toggle controls
        HealingPreviewControls(
            showBefore = showBefore,
            onTogglePreview = onTogglePreview,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        )
        
        // Before/After indicator
        HealingPreviewIndicator(
            showBefore = showBefore,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        )
    }
}

/**
 * Draws highlighting around healing regions
 */
private fun DrawScope.drawHealingRegionHighlight(
    region: androidx.compose.ui.geometry.Rect,
    showBefore: Boolean
) {
    val color = if (showBefore) Color.Red else Color.Green
    val strokeWidth = 3.dp.toPx()
    
    // Draw region outline
    drawRect(
        color = color.copy(alpha = 0.8f),
        topLeft = region.topLeft,
        size = region.size,
        style = Stroke(
            width = strokeWidth,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 5f))
        )
    )
    
    // Draw corner markers
    val cornerSize = 12.dp.toPx()
    val corners = listOf(
        region.topLeft,
        Offset(region.right - cornerSize, region.top),
        Offset(region.left, region.bottom - cornerSize),
        Offset(region.right - cornerSize, region.bottom - cornerSize)
    )
    
    corners.forEach { corner ->
        drawRect(
            color = color,
            topLeft = corner,
            size = androidx.compose.ui.geometry.Size(cornerSize, cornerSize)
        )
    }
}

/**
 * Controls for toggling between before and after preview
 */
@Composable
private fun HealingPreviewControls(
    showBefore: Boolean,
    onTogglePreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        )
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onTogglePreview,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = if (showBefore) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (showBefore) "Show after" else "Show before",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            
            Text(
                text = if (showBefore) "Before" else "After",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Indicator showing current preview state
 */
@Composable
private fun HealingPreviewIndicator(
    showBefore: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (showBefore) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            }
        )
    ) {
        Text(
            text = if (showBefore) "BEFORE" else "AFTER",
            style = MaterialTheme.typography.labelSmall,
            color = if (showBefore) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/**
 * Composable for showing healing region boundaries
 */
@Composable
fun HealingRegionOverlay(
    regions: List<androidx.compose.ui.geometry.Rect>,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    if (regions.isNotEmpty() && isActive) {
        Canvas(
            modifier = modifier.fillMaxSize()
        ) {
            regions.forEach { region ->
                drawHealingRegionBoundary(region)
            }
        }
    }
}

/**
 * Draws the boundary of a healing region
 */
private fun DrawScope.drawHealingRegionBoundary(region: androidx.compose.ui.geometry.Rect) {
    val strokeWidth = 2.dp.toPx()
    val dashLength = 8.dp.toPx()
    
    // Animated dashed outline
    drawRect(
        color = Color.Cyan.copy(alpha = 0.7f),
        topLeft = region.topLeft,
        size = region.size,
        style = Stroke(
            width = strokeWidth,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLength, dashLength / 2))
        )
    )
    
    // Semi-transparent fill
    drawRect(
        color = Color.Cyan.copy(alpha = 0.1f),
        topLeft = region.topLeft,
        size = region.size
    )
}