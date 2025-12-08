package com.imagedit.app.util.image

import android.graphics.*
import com.imagedit.app.domain.model.BrushStroke
import com.imagedit.app.domain.model.HealingBrush as HealingBrushModel
import kotlin.math.*

/**
 * Implements healing brush system for area selection with touch-based painting
 */
class HealingBrush {

    companion object {
        private const val PRESSURE_SENSITIVITY_FACTOR = 0.3f
        private const val MIN_STROKE_DISTANCE = 2f
        private const val BRUSH_ALPHA = 128 // Semi-transparent for preview
    }

    private val currentStroke = mutableListOf<PointF>()
    private val allStrokes = mutableListOf<BrushStroke>()
    private var lastPoint: PointF? = null

    /**
     * Starts a new brush stroke at the given point
     */
    fun startStroke(x: Float, y: Float, pressure: Float = 1.0f) {
        currentStroke.clear()
        currentStroke.add(PointF(x, y))
        lastPoint = PointF(x, y)
    }

    /**
     * Adds a point to the current brush stroke
     */
    fun addStrokePoint(x: Float, y: Float, pressure: Float = 1.0f): Boolean {
        val currentPoint = PointF(x, y)
        
        // Check minimum distance to avoid too many points
        lastPoint?.let { last ->
            val distance = sqrt((x - last.x).pow(2) + (y - last.y).pow(2))
            if (distance < MIN_STROKE_DISTANCE) {
                return false
            }
        }
        
        currentStroke.add(currentPoint)
        lastPoint = currentPoint
        return true
    }

    /**
     * Finishes the current stroke and adds it to the collection
     */
    fun finishStroke(brushSettings: HealingBrushModel): BrushStroke? {
        if (currentStroke.size < 2) {
            currentStroke.clear()
            return null
        }
        
        val stroke = BrushStroke(
            points = currentStroke.toList(),
            brushSize = brushSettings.size,
            pressure = 1.0f, // Average pressure for the stroke
            timestamp = System.currentTimeMillis()
        )
        
        allStrokes.add(stroke)
        currentStroke.clear()
        return stroke
    }

    /**
     * Creates a mask bitmap from all brush strokes
     */
    fun createMaskBitmap(
        width: Int,
        height: Int,
        brushSettings: HealingBrushModel
    ): Bitmap {
        val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(maskBitmap)
        
        // Clear the canvas
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        
        // Draw all strokes
        allStrokes.forEach { stroke ->
            drawStrokeOnCanvas(canvas, stroke, brushSettings)
        }
        
        return maskBitmap
    }

    /**
     * Creates a preview bitmap showing the current brush stroke
     */
    fun createPreviewBitmap(
        width: Int,
        height: Int,
        brushSettings: HealingBrushModel
    ): Bitmap {
        val previewBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(previewBitmap)
        
        // Clear the canvas
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        
        // Draw existing strokes with lower opacity
        val existingPaint = createBrushPaint(brushSettings, BRUSH_ALPHA / 2)
        allStrokes.forEach { stroke ->
            drawStrokeOnCanvas(canvas, stroke, brushSettings, existingPaint)
        }
        
        // Draw current stroke with full opacity
        if (currentStroke.size >= 2) {
            val currentPaint = createBrushPaint(brushSettings, BRUSH_ALPHA)
            drawCurrentStrokeOnCanvas(canvas, brushSettings, currentPaint)
        }
        
        return previewBitmap
    }

    /**
     * Draws a brush stroke on the canvas
     */
    private fun drawStrokeOnCanvas(
        canvas: Canvas,
        stroke: BrushStroke,
        brushSettings: HealingBrushModel,
        paint: Paint? = null
    ) {
        val strokePaint = paint ?: createBrushPaint(brushSettings)
        
        if (stroke.points.size < 2) return
        
        val path = Path()
        path.moveTo(stroke.points[0].x, stroke.points[0].y)
        
        // Create smooth path through all points
        for (i in 1 until stroke.points.size) {
            val point = stroke.points[i]
            
            if (i == 1) {
                path.lineTo(point.x, point.y)
            } else {
                val prevPoint = stroke.points[i - 1]
                val controlX = (prevPoint.x + point.x) / 2
                val controlY = (prevPoint.y + point.y) / 2
                path.quadTo(prevPoint.x, prevPoint.y, controlX, controlY)
            }
        }
        
        canvas.drawPath(path, strokePaint)
        
        // Draw circles at each point for better coverage
        stroke.points.forEach { point ->
            val adjustedSize = if (brushSettings.pressureSensitive) {
                stroke.brushSize * (0.7f + 0.3f * stroke.pressure)
            } else {
                stroke.brushSize
            }
            canvas.drawCircle(point.x, point.y, adjustedSize / 2, strokePaint)
        }
    }

    /**
     * Draws the current stroke being painted
     */
    private fun drawCurrentStrokeOnCanvas(
        canvas: Canvas,
        brushSettings: HealingBrushModel,
        paint: Paint
    ) {
        if (currentStroke.size < 2) return
        
        val path = Path()
        path.moveTo(currentStroke[0].x, currentStroke[0].y)
        
        for (i in 1 until currentStroke.size) {
            val point = currentStroke[i]
            path.lineTo(point.x, point.y)
        }
        
        canvas.drawPath(path, paint)
        
        // Draw circles for better coverage
        currentStroke.forEach { point ->
            canvas.drawCircle(point.x, point.y, brushSettings.size / 2, paint)
        }
    }

    /**
     * Creates paint for brush drawing
     */
    private fun createBrushPaint(
        brushSettings: HealingBrushModel,
        alpha: Int = 255
    ): Paint {
        return Paint().apply {
            color = Color.argb(
                (alpha * brushSettings.opacity).toInt(),
                255, 255, 255
            )
            style = Paint.Style.FILL
            isAntiAlias = true
            
            // Apply brush hardness using blur
            if (brushSettings.hardness < 1.0f) {
                val blurRadius = brushSettings.size * (1.0f - brushSettings.hardness) * 0.5f
                maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
            }
            
            strokeWidth = brushSettings.size
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }

    /**
     * Gets the bounding rectangle of all brush strokes
     */
    fun getBoundingRect(): Rect? {
        if (allStrokes.isEmpty()) return null
        
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        
        allStrokes.forEach { stroke ->
            stroke.points.forEach { point ->
                minX = min(minX, point.x - stroke.brushSize / 2)
                minY = min(minY, point.y - stroke.brushSize / 2)
                maxX = max(maxX, point.x + stroke.brushSize / 2)
                maxY = max(maxY, point.y + stroke.brushSize / 2)
            }
        }
        
        return if (minX < maxX && minY < maxY) {
            Rect(
                minX.toInt(),
                minY.toInt(),
                maxX.toInt(),
                maxY.toInt()
            )
        } else {
            null
        }
    }

    /**
     * Clears all brush strokes
     */
    fun clearStrokes() {
        allStrokes.clear()
        currentStroke.clear()
        lastPoint = null
    }

    /**
     * Removes the last brush stroke (undo)
     */
    fun removeLastStroke(): BrushStroke? {
        return if (allStrokes.isNotEmpty()) {
            allStrokes.removeLastOrNull()
        } else {
            null
        }
    }

    /**
     * Gets all completed brush strokes
     */
    fun getAllStrokes(): List<BrushStroke> = allStrokes.toList()

    /**
     * Gets the current stroke being painted
     */
    fun getCurrentStroke(): List<PointF> = currentStroke.toList()

    /**
     * Checks if there are any strokes
     */
    fun hasStrokes(): Boolean = allStrokes.isNotEmpty() || currentStroke.isNotEmpty()

    /**
     * Calculates the total area covered by brush strokes
     */
    fun calculateCoveredArea(): Float {
        var totalArea = 0f
        
        allStrokes.forEach { stroke ->
            val strokeArea = PI.toFloat() * (stroke.brushSize / 2).pow(2) * stroke.points.size
            totalArea += strokeArea
        }
        
        return totalArea
    }

    /**
     * Creates a path from all brush strokes for hit testing
     */
    fun createStrokePath(): Path {
        val path = Path()
        
        allStrokes.forEach { stroke ->
            if (stroke.points.isNotEmpty()) {
                val strokePath = Path()
                strokePath.moveTo(stroke.points[0].x, stroke.points[0].y)
                
                stroke.points.drop(1).forEach { point ->
                    strokePath.lineTo(point.x, point.y)
                }
                
                path.addPath(strokePath)
            }
        }
        
        return path
    }

    /**
     * Checks if a point is within any brush stroke
     */
    fun isPointInStroke(x: Float, y: Float): Boolean {
        allStrokes.forEach { stroke ->
            stroke.points.forEach { point ->
                val distance = sqrt((x - point.x).pow(2) + (y - point.y).pow(2))
                if (distance <= stroke.brushSize / 2) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Optimizes brush strokes by removing redundant points
     */
    fun optimizeStrokes() {
        val optimizedStrokes = mutableListOf<BrushStroke>()
        
        allStrokes.forEach { stroke ->
            val optimizedPoints = optimizeStrokePoints(stroke.points)
            if (optimizedPoints.size >= 2) {
                optimizedStrokes.add(
                    stroke.copy(points = optimizedPoints)
                )
            }
        }
        
        allStrokes.clear()
        allStrokes.addAll(optimizedStrokes)
    }

    /**
     * Optimizes points in a stroke by removing redundant ones
     */
    private fun optimizeStrokePoints(points: List<PointF>): List<PointF> {
        if (points.size <= 2) return points
        
        val optimized = mutableListOf<PointF>()
        optimized.add(points[0]) // Always keep first point
        
        for (i in 1 until points.size - 1) {
            val prev = points[i - 1]
            val current = points[i]
            val next = points[i + 1]
            
            // Calculate angle between vectors
            val angle1 = atan2(current.y - prev.y, current.x - prev.x)
            val angle2 = atan2(next.y - current.y, next.x - current.x)
            val angleDiff = abs(angle1 - angle2)
            
            // Keep point if it represents a significant direction change
            if (angleDiff > 0.1) { // ~5.7 degrees
                optimized.add(current)
            }
        }
        
        optimized.add(points.last()) // Always keep last point
        return optimized
    }
}