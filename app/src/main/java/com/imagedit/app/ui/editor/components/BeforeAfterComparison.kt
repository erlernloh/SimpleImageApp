package com.imagedit.app.ui.editor.components

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Before/After comparison component with smooth transition animations.
 * Supports toggle functionality and drag-to-compare interaction.
 */
@Composable
fun BeforeAfterComparison(
    originalBitmap: Bitmap?,
    processedBitmap: Bitmap?,
    isShowingComparison: Boolean,
    transitionProgress: Float,
    onToggleComparison: () -> Unit,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    
    // Animation for smooth transitions
    val animatedProgress by animateFloatAsState(
        targetValue = transitionProgress,
        animationSpec = tween(
            durationMillis = 300,
            easing = FastOutSlowInEasing
        ),
        label = "beforeAfterProgress"
    )
    
    Box(modifier = modifier) {
        // Main image display with before/after overlay
        if (originalBitmap != null && processedBitmap != null) {
            BeforeAfterImageDisplay(
                originalBitmap = originalBitmap,
                processedBitmap = processedBitmap,
                progress = if (isShowingComparison) animatedProgress else 1f,
                isComparisonActive = isShowingComparison,
                onProgressChange = onProgressChange,
                modifier = Modifier.fillMaxSize()
            )
        }
        
        // Before/After toggle button
        BeforeAfterToggleButton(
            isShowingComparison = isShowingComparison,
            onToggle = onToggleComparison,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        )
        
        // Progress indicator when in comparison mode
        if (isShowingComparison) {
            BeforeAfterProgressIndicator(
                progress = animatedProgress,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            )
        }
    }
}

/**
 * Image display component that handles the before/after transition effect.
 */
@Composable
private fun BeforeAfterImageDisplay(
    originalBitmap: Bitmap,
    processedBitmap: Bitmap,
    progress: Float,
    isComparisonActive: Boolean,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragProgress by remember { mutableStateOf(progress) }
    
    // Update drag progress when external progress changes
    LaunchedEffect(progress) {
        if (!isComparisonActive) {
            dragProgress = progress
        }
    }
    
    Box(
        modifier = modifier
            .pointerInput(isComparisonActive) {
                if (isComparisonActive) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragProgress = offset.x / size.width
                            onProgressChange(dragProgress)
                        },
                        onDrag = { change, dragAmount ->
                            dragProgress = (dragProgress + dragAmount.x / size.width).coerceIn(0f, 1f)
                            onProgressChange(dragProgress)
                        }
                    )
                }
            }
    ) {
        // Base image (processed)
        Image(
            bitmap = processedBitmap.asImageBitmap(),
            contentDescription = "Processed image",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
        
        // Overlay image (original) with clipping based on progress
        if (isComparisonActive) {
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                val clipWidth = size.width * (1f - dragProgress)
                
                // Calculate proper scaling to maintain aspect ratio (ContentScale.Fit behavior)
                val bitmapAspectRatio = originalBitmap.width.toFloat() / originalBitmap.height.toFloat()
                val canvasAspectRatio = size.width / size.height
                
                val (dstWidth, dstHeight, offsetX, offsetY) = if (bitmapAspectRatio > canvasAspectRatio) {
                    // Bitmap is wider - fit to width
                    val width = size.width
                    val height = size.width / bitmapAspectRatio
                    val yOffset = (size.height - height) / 2f
                    listOf(width, height, 0f, yOffset)
                } else {
                    // Bitmap is taller - fit to height
                    val height = size.height
                    val width = size.height * bitmapAspectRatio
                    val xOffset = (size.width - width) / 2f
                    listOf(width, height, xOffset, 0f)
                }
                
                // Draw original image clipped to show comparison with proper aspect ratio
                clipRect(
                    left = 0f,
                    top = 0f,
                    right = clipWidth,
                    bottom = size.height
                ) {
                    drawImage(
                        image = originalBitmap.asImageBitmap(),
                        dstOffset = androidx.compose.ui.unit.IntOffset(offsetX.toInt(), offsetY.toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(
                            dstWidth.toInt(),
                            dstHeight.toInt()
                        )
                    )
                }
                
                // Draw divider line
                if (clipWidth > 0f && clipWidth < size.width) {
                    drawLine(
                        color = Color.White,
                        start = Offset(clipWidth, 0f),
                        end = Offset(clipWidth, size.height),
                        strokeWidth = 4.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    
                    // Draw divider handle
                    drawCircle(
                        color = Color.White,
                        radius = 12.dp.toPx(),
                        center = Offset(clipWidth, size.height / 2f)
                    )
                    
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.3f),
                        radius = 8.dp.toPx(),
                        center = Offset(clipWidth, size.height / 2f)
                    )
                }
            }
        }
    }
}

/**
 * Toggle button for before/after comparison mode.
 */
@Composable
private fun BeforeAfterToggleButton(
    isShowingComparison: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    FloatingActionButton(
        onClick = onToggle,
        modifier = modifier.size(56.dp),
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
    ) {
        Icon(
            imageVector = if (isShowingComparison) Icons.Default.Visibility else Icons.Default.VisibilityOff,
            contentDescription = if (isShowingComparison) "Hide comparison" else "Show comparison",
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * Progress indicator showing before/after transition state.
 */
@Composable
private fun BeforeAfterProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "BEFORE",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (progress < 0.5f) FontWeight.Bold else FontWeight.Normal,
                    color = if (progress < 0.5f) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                ),
                fontSize = 10.sp
            )
            
            // Progress bar
            Box(
                modifier = Modifier
                    .width(60.dp)
                    .height(4.dp)
                    .background(
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        RoundedCornerShape(2.dp)
                    )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(2.dp)
                        )
                )
            }
            
            Text(
                text = "AFTER",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (progress >= 0.5f) FontWeight.Bold else FontWeight.Normal,
                    color = if (progress >= 0.5f) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                ),
                fontSize = 10.sp
            )
        }
    }
}

/**
 * Smart enhancement button with loading state and success indicator.
 */
@Composable
fun SmartEnhanceButton(
    onClick: () -> Unit,
    isProcessing: Boolean,
    isApplied: Boolean,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = !isProcessing,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isApplied) 
                MaterialTheme.colorScheme.tertiary 
            else 
                MaterialTheme.colorScheme.primary
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when {
                isProcessing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                isApplied -> {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
                else -> {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            
            Text(
                text = when {
                    isProcessing -> "Enhancing..."
                    isApplied -> "Enhanced"
                    else -> "Smart Enhance"
                }
            )
        }
    }
}