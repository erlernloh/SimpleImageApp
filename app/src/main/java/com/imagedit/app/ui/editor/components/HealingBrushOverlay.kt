package com.imagedit.app.ui.editor.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.imagedit.app.domain.model.HealingBrush
import kotlin.math.sqrt

/**
 * Overlay component that provides visual feedback for healing brush interactions
 */
@Composable
fun HealingBrushOverlay(
    brushSettings: HealingBrush,
    onBrushStart: (Float, Float, Float) -> Unit,
    onBrushMove: (Float, Float, Float) -> Unit,
    onBrushEnd: () -> Unit,
    strokePath: Path?,
    currentStrokePath: Path?,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var currentPosition by remember { mutableStateOf<Offset?>(null) }
    var isDragging by remember { mutableStateOf(false) }
    
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        currentPosition = offset
                        isDragging = true
                        onBrushStart(offset.x, offset.y, 1.0f) // Default pressure
                    },
                    onDrag = { _, dragAmount ->
                        currentPosition?.let { current ->
                            val newPosition = current + dragAmount
                            currentPosition = newPosition
                            onBrushMove(newPosition.x, newPosition.y, 1.0f)
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                        currentPosition = null
                        onBrushEnd()
                    }
                )
            }
    ) {
        // Draw existing strokes
        strokePath?.let { path ->
            drawPath(
                path = path,
                color = Color.Red.copy(alpha = 0.3f),
                style = Stroke(
                    width = with(density) { brushSettings.size.dp.toPx() },
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
        
        // Draw current stroke being painted
        currentStrokePath?.let { path ->
            drawPath(
                path = path,
                color = Color.Red.copy(alpha = 0.6f),
                style = Stroke(
                    width = with(density) { brushSettings.size.dp.toPx() },
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
        
        // Draw brush cursor
        currentPosition?.let { position ->
            drawBrushCursor(
                position = position,
                brushSettings = brushSettings,
                density = density
            )
        }
    }
}

/**
 * Draws the brush cursor showing size and hardness
 */
private fun DrawScope.drawBrushCursor(
    position: Offset,
    brushSettings: HealingBrush,
    density: androidx.compose.ui.unit.Density
) {
    val brushRadius = with(density) { (brushSettings.size / 2).dp.toPx() }
    
    // Outer circle (full brush size)
    drawCircle(
        color = Color.White,
        radius = brushRadius,
        center = position,
        style = Stroke(width = 2.dp.toPx())
    )
    
    drawCircle(
        color = Color.Black,
        radius = brushRadius,
        center = position,
        style = Stroke(width = 1.dp.toPx())
    )
    
    // Inner circle (hardness indicator)
    if (brushSettings.hardness < 1.0f) {
        val hardnessRadius = brushRadius * brushSettings.hardness
        drawCircle(
            color = Color.Gray.copy(alpha = 0.5f),
            radius = hardnessRadius,
            center = position,
            style = Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))
            )
        )
    }
    
    // Center dot
    drawCircle(
        color = Color.Red,
        radius = 2.dp.toPx(),
        center = position
    )
}