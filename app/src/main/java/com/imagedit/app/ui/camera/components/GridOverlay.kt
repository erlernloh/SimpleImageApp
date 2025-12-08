package com.imagedit.app.ui.camera.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import com.imagedit.app.ui.camera.GridType

@Composable
fun GridOverlay(
    gridType: GridType,
    modifier: Modifier = Modifier
) {
    if (gridType == GridType.NONE) return
    
    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        
        val gridColor = Color.White.copy(alpha = 0.5f)
        val strokeWidth = 2f
        val pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
        
        when (gridType) {
            GridType.RULE_OF_THIRDS -> {
                // Vertical lines at 1/3 and 2/3
                val x1 = width / 3f
                val x2 = width * 2f / 3f
                
                drawLine(
                    color = gridColor,
                    start = Offset(x1, 0f),
                    end = Offset(x1, height),
                    strokeWidth = strokeWidth
                )
                drawLine(
                    color = gridColor,
                    start = Offset(x2, 0f),
                    end = Offset(x2, height),
                    strokeWidth = strokeWidth
                )
                
                // Horizontal lines at 1/3 and 2/3
                val y1 = height / 3f
                val y2 = height * 2f / 3f
                
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y1),
                    end = Offset(width, y1),
                    strokeWidth = strokeWidth
                )
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y2),
                    end = Offset(width, y2),
                    strokeWidth = strokeWidth
                )
            }
            
            GridType.GOLDEN_RATIO -> {
                // Golden ratio ≈ 1.618
                val goldenRatio = 1.618f
                
                // Vertical lines
                val x1 = width / goldenRatio
                val x2 = width - (width / goldenRatio)
                
                drawLine(
                    color = gridColor,
                    start = Offset(x1, 0f),
                    end = Offset(x1, height),
                    strokeWidth = strokeWidth,
                    pathEffect = pathEffect
                )
                drawLine(
                    color = gridColor,
                    start = Offset(x2, 0f),
                    end = Offset(x2, height),
                    strokeWidth = strokeWidth,
                    pathEffect = pathEffect
                )
                
                // Horizontal lines
                val y1 = height / goldenRatio
                val y2 = height - (height / goldenRatio)
                
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y1),
                    end = Offset(width, y1),
                    strokeWidth = strokeWidth,
                    pathEffect = pathEffect
                )
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y2),
                    end = Offset(width, y2),
                    strokeWidth = strokeWidth,
                    pathEffect = pathEffect
                )
            }
            
            GridType.CENTER_CROSS -> {
                val centerX = width / 2f
                val centerY = height / 2f
                
                // Vertical center line
                drawLine(
                    color = gridColor,
                    start = Offset(centerX, 0f),
                    end = Offset(centerX, height),
                    strokeWidth = strokeWidth
                )
                
                // Horizontal center line
                drawLine(
                    color = gridColor,
                    start = Offset(0f, centerY),
                    end = Offset(width, centerY),
                    strokeWidth = strokeWidth
                )
            }
            
            GridType.NONE -> {
                // Do nothing
            }
        }
    }
}
