package com.imagedit.app.ui.editor.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.imagedit.app.ui.editor.CropAspectRatio
import kotlin.math.abs

/**
 * Interactive crop overlay with draggable handles and visual feedback
 */
@Composable
fun CropOverlay(
    modifier: Modifier = Modifier,
    cropRect: Rect,
    imageBounds: Rect,
    aspectRatio: CropAspectRatio,
    onCropRectChange: (Rect) -> Unit
) {
    var currentCropRect by remember(cropRect) { mutableStateOf(cropRect) }
    var activeHandle by remember { mutableStateOf<CropHandle?>(null) }
    
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(imageBounds, aspectRatio) {
                detectDragGestures(
                    onDragStart = { offset ->
                        activeHandle = detectHandle(offset, currentCropRect)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val handle = activeHandle ?: return@detectDragGestures
                        
                        val newRect = calculateNewRect(
                            currentRect = currentCropRect,
                            handle = handle,
                            dragAmount = dragAmount,
                            imageBounds = imageBounds,
                            aspectRatio = aspectRatio
                        )
                        
                        currentCropRect = newRect
                        onCropRectChange(newRect)
                    },
                    onDragEnd = {
                        activeHandle = null
                    }
                )
            }
    ) {
        // Draw dimmed overlay outside crop area
        drawDimmedOverlay(currentCropRect, imageBounds)
        
        // Draw crop rectangle border
        drawCropBorder(currentCropRect)
        
        // Draw grid lines (rule of thirds)
        drawGridLines(currentCropRect)
        
        // Draw corner handles
        drawCornerHandles(currentCropRect, activeHandle)
        
        // Draw edge handles
        drawEdgeHandles(currentCropRect, activeHandle)
    }
}

/**
 * Types of crop handles
 */
private sealed class CropHandle {
    // Corner handles
    object TopLeft : CropHandle()
    object TopRight : CropHandle()
    object BottomLeft : CropHandle()
    object BottomRight : CropHandle()
    
    // Edge handles
    object Top : CropHandle()
    object Bottom : CropHandle()
    object Left : CropHandle()
    object Right : CropHandle()
    
    // Move entire rectangle
    object Move : CropHandle()
}

/**
 * Detect which handle (if any) was touched
 */
private fun detectHandle(touchPoint: Offset, cropRect: Rect): CropHandle? {
    val handleRadius = 48f // Touch target size (increased for better UX)
    val edgeHandleSize = 80f // Increased for easier grabbing
    
    // Check corner handles first (higher priority)
    if (isNear(touchPoint, Offset(cropRect.left, cropRect.top), handleRadius)) {
        return CropHandle.TopLeft
    }
    if (isNear(touchPoint, Offset(cropRect.right, cropRect.top), handleRadius)) {
        return CropHandle.TopRight
    }
    if (isNear(touchPoint, Offset(cropRect.left, cropRect.bottom), handleRadius)) {
        return CropHandle.BottomLeft
    }
    if (isNear(touchPoint, Offset(cropRect.right, cropRect.bottom), handleRadius)) {
        return CropHandle.BottomRight
    }
    
    // Check edge handles
    val centerTop = Offset(cropRect.center.x, cropRect.top)
    val centerBottom = Offset(cropRect.center.x, cropRect.bottom)
    val centerLeft = Offset(cropRect.left, cropRect.center.y)
    val centerRight = Offset(cropRect.right, cropRect.center.y)
    
    if (isNear(touchPoint, centerTop, edgeHandleSize)) {
        return CropHandle.Top
    }
    if (isNear(touchPoint, centerBottom, edgeHandleSize)) {
        return CropHandle.Bottom
    }
    if (isNear(touchPoint, centerLeft, edgeHandleSize)) {
        return CropHandle.Left
    }
    if (isNear(touchPoint, centerRight, edgeHandleSize)) {
        return CropHandle.Right
    }
    
    // Check if inside crop area (for moving)
    if (cropRect.contains(touchPoint)) {
        return CropHandle.Move
    }
    
    return null
}

/**
 * Check if point is near target within radius
 */
private fun isNear(point: Offset, target: Offset, radius: Float): Boolean {
    val dx = point.x - target.x
    val dy = point.y - target.y
    return (dx * dx + dy * dy) <= (radius * radius)
}

/**
 * Calculate new crop rectangle based on handle drag
 */
private fun calculateNewRect(
    currentRect: Rect,
    handle: CropHandle,
    dragAmount: Offset,
    imageBounds: Rect,
    aspectRatio: CropAspectRatio
): Rect {
    val minSize = 100f // Minimum crop size in pixels
    
    var newRect = when (handle) {
        is CropHandle.TopLeft -> Rect(
            left = (currentRect.left + dragAmount.x).coerceIn(imageBounds.left, currentRect.right - minSize),
            top = (currentRect.top + dragAmount.y).coerceIn(imageBounds.top, currentRect.bottom - minSize),
            right = currentRect.right,
            bottom = currentRect.bottom
        )
        is CropHandle.TopRight -> Rect(
            left = currentRect.left,
            top = (currentRect.top + dragAmount.y).coerceIn(imageBounds.top, currentRect.bottom - minSize),
            right = (currentRect.right + dragAmount.x).coerceIn(currentRect.left + minSize, imageBounds.right),
            bottom = currentRect.bottom
        )
        is CropHandle.BottomLeft -> Rect(
            left = (currentRect.left + dragAmount.x).coerceIn(imageBounds.left, currentRect.right - minSize),
            top = currentRect.top,
            right = currentRect.right,
            bottom = (currentRect.bottom + dragAmount.y).coerceIn(currentRect.top + minSize, imageBounds.bottom)
        )
        is CropHandle.BottomRight -> Rect(
            left = currentRect.left,
            top = currentRect.top,
            right = (currentRect.right + dragAmount.x).coerceIn(currentRect.left + minSize, imageBounds.right),
            bottom = (currentRect.bottom + dragAmount.y).coerceIn(currentRect.top + minSize, imageBounds.bottom)
        )
        is CropHandle.Top -> Rect(
            left = currentRect.left,
            top = (currentRect.top + dragAmount.y).coerceIn(imageBounds.top, currentRect.bottom - minSize),
            right = currentRect.right,
            bottom = currentRect.bottom
        )
        is CropHandle.Bottom -> Rect(
            left = currentRect.left,
            top = currentRect.top,
            right = currentRect.right,
            bottom = (currentRect.bottom + dragAmount.y).coerceIn(currentRect.top + minSize, imageBounds.bottom)
        )
        is CropHandle.Left -> Rect(
            left = (currentRect.left + dragAmount.x).coerceIn(imageBounds.left, currentRect.right - minSize),
            top = currentRect.top,
            right = currentRect.right,
            bottom = currentRect.bottom
        )
        is CropHandle.Right -> Rect(
            left = currentRect.left,
            top = currentRect.top,
            right = (currentRect.right + dragAmount.x).coerceIn(currentRect.left + minSize, imageBounds.right),
            bottom = currentRect.bottom
        )
        is CropHandle.Move -> {
            val newLeft = (currentRect.left + dragAmount.x).coerceIn(
                imageBounds.left,
                imageBounds.right - currentRect.width
            )
            val newTop = (currentRect.top + dragAmount.y).coerceIn(
                imageBounds.top,
                imageBounds.bottom - currentRect.height
            )
            Rect(
                left = newLeft,
                top = newTop,
                right = newLeft + currentRect.width,
                bottom = newTop + currentRect.height
            )
        }
    }
    
    // Apply aspect ratio constraint if not free-form
    if (aspectRatio.ratio != null && handle !is CropHandle.Move) {
        newRect = constrainToAspectRatio(newRect, aspectRatio.ratio, imageBounds, handle)
    }
    
    return newRect
}

/**
 * Constrain rectangle to maintain aspect ratio
 */
private fun constrainToAspectRatio(
    rect: Rect,
    ratio: Float,
    imageBounds: Rect,
    handle: CropHandle
): Rect {
    val currentRatio = rect.width / rect.height
    
    return if (abs(currentRatio - ratio) > 0.01f) {
        // Adjust based on which dimension changed
        when (handle) {
            is CropHandle.TopLeft, is CropHandle.TopRight,
            is CropHandle.BottomLeft, is CropHandle.BottomRight -> {
                // Corner handles - adjust height to match width
                val targetHeight = rect.width / ratio
                when (handle) {
                    is CropHandle.TopLeft, is CropHandle.TopRight -> {
                        val newTop = (rect.bottom - targetHeight).coerceAtLeast(imageBounds.top)
                        Rect(rect.left, newTop, rect.right, rect.bottom)
                    }
                    else -> {
                        val newBottom = (rect.top + targetHeight).coerceAtMost(imageBounds.bottom)
                        Rect(rect.left, rect.top, rect.right, newBottom)
                    }
                }
            }
            is CropHandle.Top, is CropHandle.Bottom -> {
                // Vertical edge - adjust width to match height
                val targetWidth = rect.height * ratio
                val centerX = rect.center.x
                val newLeft = (centerX - targetWidth / 2).coerceAtLeast(imageBounds.left)
                val newRight = (centerX + targetWidth / 2).coerceAtMost(imageBounds.right)
                Rect(newLeft, rect.top, newRight, rect.bottom)
            }
            is CropHandle.Left, is CropHandle.Right -> {
                // Horizontal edge - adjust height to match width
                val targetHeight = rect.width / ratio
                val centerY = rect.center.y
                val newTop = (centerY - targetHeight / 2).coerceAtLeast(imageBounds.top)
                val newBottom = (centerY + targetHeight / 2).coerceAtMost(imageBounds.bottom)
                Rect(rect.left, newTop, rect.right, newBottom)
            }
            else -> rect
        }
    } else {
        rect
    }
}

/**
 * Draw dimmed overlay outside crop area
 */
private fun DrawScope.drawDimmedOverlay(cropRect: Rect, imageBounds: Rect) {
    val overlayColor = Color.Black.copy(alpha = 0.5f)
    
    // Top
    drawRect(
        color = overlayColor,
        topLeft = Offset(imageBounds.left, imageBounds.top),
        size = Size(imageBounds.width, cropRect.top - imageBounds.top)
    )
    
    // Bottom
    drawRect(
        color = overlayColor,
        topLeft = Offset(imageBounds.left, cropRect.bottom),
        size = Size(imageBounds.width, imageBounds.bottom - cropRect.bottom)
    )
    
    // Left
    drawRect(
        color = overlayColor,
        topLeft = Offset(imageBounds.left, cropRect.top),
        size = Size(cropRect.left - imageBounds.left, cropRect.height)
    )
    
    // Right
    drawRect(
        color = overlayColor,
        topLeft = Offset(cropRect.right, cropRect.top),
        size = Size(imageBounds.right - cropRect.right, cropRect.height)
    )
}

/**
 * Draw crop rectangle border
 */
private fun DrawScope.drawCropBorder(cropRect: Rect) {
    drawRect(
        color = Color.White,
        topLeft = cropRect.topLeft,
        size = cropRect.size,
        style = Stroke(width = 2.dp.toPx())
    )
}

/**
 * Draw grid lines (rule of thirds)
 */
private fun DrawScope.drawGridLines(cropRect: Rect) {
    val gridColor = Color.White.copy(alpha = 0.5f)
    val strokeWidth = 1.dp.toPx()
    
    // Vertical lines
    val verticalStep = cropRect.width / 3
    for (i in 1..2) {
        val x = cropRect.left + verticalStep * i
        drawLine(
            color = gridColor,
            start = Offset(x, cropRect.top),
            end = Offset(x, cropRect.bottom),
            strokeWidth = strokeWidth
        )
    }
    
    // Horizontal lines
    val horizontalStep = cropRect.height / 3
    for (i in 1..2) {
        val y = cropRect.top + horizontalStep * i
        drawLine(
            color = gridColor,
            start = Offset(cropRect.left, y),
            end = Offset(cropRect.right, y),
            strokeWidth = strokeWidth
        )
    }
}

/**
 * Draw corner handles
 */
private fun DrawScope.drawCornerHandles(cropRect: Rect, activeHandle: CropHandle?) {
    val handleRadius = 8.dp.toPx()
    val activeRadius = 12.dp.toPx()
    val handleColor = Color.White
    val handleBorder = Color.Black.copy(alpha = 0.3f)
    
    val corners = listOf(
        CropHandle.TopLeft to Offset(cropRect.left, cropRect.top),
        CropHandle.TopRight to Offset(cropRect.right, cropRect.top),
        CropHandle.BottomLeft to Offset(cropRect.left, cropRect.bottom),
        CropHandle.BottomRight to Offset(cropRect.right, cropRect.bottom)
    )
    
    corners.forEach { (handle, position) ->
        val radius = if (activeHandle == handle) activeRadius else handleRadius
        
        // Draw border
        drawCircle(
            color = handleBorder,
            radius = radius + 1.dp.toPx(),
            center = position
        )
        
        // Draw handle
        drawCircle(
            color = handleColor,
            radius = radius,
            center = position
        )
    }
}

/**
 * Draw edge handles
 */
private fun DrawScope.drawEdgeHandles(cropRect: Rect, activeHandle: CropHandle?) {
    val handleWidth = 6.dp.toPx()
    val handleLength = 40.dp.toPx()
    val activeLength = 50.dp.toPx()
    val handleColor = Color.White
    val handleBorder = Color.Black.copy(alpha = 0.3f)
    
    val edges = listOf(
        CropHandle.Top to Offset(cropRect.center.x, cropRect.top),
        CropHandle.Bottom to Offset(cropRect.center.x, cropRect.bottom),
        CropHandle.Left to Offset(cropRect.left, cropRect.center.y),
        CropHandle.Right to Offset(cropRect.right, cropRect.center.y)
    )
    
    edges.forEach { (handle, position) ->
        val length = if (activeHandle == handle) activeLength else handleLength
        val isVertical = handle is CropHandle.Top || handle is CropHandle.Bottom
        
        val size = if (isVertical) {
            Size(length, handleWidth)
        } else {
            Size(handleWidth, length)
        }
        
        val topLeft = if (isVertical) {
            Offset(position.x - length / 2, position.y - handleWidth / 2)
        } else {
            Offset(position.x - handleWidth / 2, position.y - length / 2)
        }
        
        // Draw border
        drawRect(
            color = handleBorder,
            topLeft = topLeft.copy(
                x = topLeft.x - 1.dp.toPx(),
                y = topLeft.y - 1.dp.toPx()
            ),
            size = Size(size.width + 2.dp.toPx(), size.height + 2.dp.toPx())
        )
        
        // Draw handle
        drawRect(
            color = handleColor,
            topLeft = topLeft,
            size = size
        )
    }
}
