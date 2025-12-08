package com.imagedit.app.util.image

import android.graphics.*
import com.imagedit.app.domain.model.*
import com.imagedit.app.util.PerformanceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import com.imagedit.app.domain.model.HealingBrush as HealingBrushModel

/**
 * Main healing tool implementation that coordinates all healing operations
 */
class HealingTool(
    private val performanceManager: PerformanceManager
) {
    
    private val textureSynthesizer = TextureSynthesizer()
    private val sourceRegionDetector = SourceRegionDetector()
    private val healingBrush = HealingBrush()
    
    companion object {
        private const val MAX_AREA_SIZE = 10000 // Maximum pixels for healing area
        private const val MIN_SIMILARITY_THRESHOLD = 0.6f
        private const val EDGE_PROXIMITY_THRESHOLD = 50 // Pixels from edge
    }

    /**
     * Performs healing operation on the specified area
     */
    suspend fun healArea(
        bitmap: Bitmap,
        targetArea: Rect,
        mode: ProcessingMode = ProcessingMode.MEDIUM,
        progressCallback: ((Float) -> Unit)? = null
    ): Result<HealingResult> = withContext(Dispatchers.Default) {
        try {
            progressCallback?.invoke(0.1f)
            
            // Validate the healing area
            val validation = validateHealingArea(bitmap, targetArea)
            if (!validation.isValid) {
                return@withContext Result.failure(
                    SmartProcessingError.AlgorithmFailure(
                        "HealingTool", 
                        Exception(validation.errorMessage)
                    )
                )
            }
            
            progressCallback?.invoke(0.2f)
            
            // Find best source region
            val sourceRegion = sourceRegionDetector.findBestSourceRegion(
                bitmap, 
                targetArea,
                excludeAreas = listOf(targetArea)
            ) ?: return@withContext Result.failure(
                SmartProcessingError.AlgorithmFailure(
                    "HealingTool",
                    Exception("No suitable source region found")
                )
            )
            
            progressCallback?.invoke(0.4f)
            
            // Create healing region
            val healingRegion = HealingRegion(
                id = generateHealingId(),
                targetArea = targetArea,
                targetPath = createPathFromRect(targetArea),
                sourceArea = sourceRegion,
                confidence = calculateHealingConfidence(bitmap, targetArea, sourceRegion),
                isUserDefined = false
            )
            
            progressCallback?.invoke(0.6f)
            
            // Perform the actual healing
            val healedBitmap = performHealing(bitmap, healingRegion, mode, progressCallback)
            
            progressCallback?.invoke(1.0f)
            
            val result = HealingResult(
                operationId = healingRegion.id,
                healedBitmap = healedBitmap,
                healedRegion = healingRegion,
                processingTimeMs = System.currentTimeMillis(),
                success = true,
                undoData = createUndoData(bitmap, targetArea)
            )
            
            Result.success(result)
            
        } catch (e: Exception) {
            Result.failure(
                SmartProcessingError.AlgorithmFailure("HealingTool", e)
            )
        }
    }

    /**
     * Heals area using brush strokes with cancellation support
     */
    suspend fun healWithBrush(
        bitmap: Bitmap,
        brushStrokes: List<BrushStroke>,
        brushSettings: HealingBrushModel,
        mode: ProcessingMode = ProcessingMode.MEDIUM,
        progressCallback: ((Float) -> Unit)? = null,
        cancellationToken: (() -> Boolean)? = null
    ): Result<HealingResult> = withContext(Dispatchers.Default) {
        try {
            progressCallback?.invoke(0.1f)
            
            // Check for cancellation
            if (cancellationToken?.invoke() == true) {
                return@withContext Result.failure(
                    SmartProcessingError.AlgorithmFailure("HealingTool", Exception("Operation cancelled"))
                )
            }
            
            // Optimize memory usage based on performance mode
            val optimizedBitmap = optimizeBitmapForHealing(bitmap, mode)
            val scaleFactor = optimizedBitmap.width.toFloat() / bitmap.width.toFloat()
            
            // Scale brush strokes if bitmap was resized
            val scaledStrokes = if (scaleFactor != 1.0f) {
                scaleStrokes(brushStrokes, scaleFactor)
            } else {
                brushStrokes
            }
            
            // Create mask from brush strokes
            val maskBitmap = createMaskFromStrokes(optimizedBitmap, scaledStrokes, brushSettings)
            val targetArea = calculateBoundingRect(scaledStrokes)
            
            progressCallback?.invoke(0.3f)
            
            // Check for cancellation
            if (cancellationToken?.invoke() == true) {
                return@withContext Result.failure(
                    SmartProcessingError.AlgorithmFailure("HealingTool", Exception("Operation cancelled"))
                )
            }
            
            // Validate the area
            val validation = validateHealingArea(optimizedBitmap, targetArea)
            if (!validation.isValid) {
                return@withContext Result.failure(
                    SmartProcessingError.AlgorithmFailure(
                        "HealingTool",
                        Exception(validation.errorMessage)
                    )
                )
            }
            
            progressCallback?.invoke(0.5f)
            
            // Check for cancellation before expensive operation
            if (cancellationToken?.invoke() == true) {
                return@withContext Result.failure(
                    SmartProcessingError.AlgorithmFailure("HealingTool", Exception("Operation cancelled"))
                )
            }
            
            // Perform texture synthesis healing with batching for large areas
            val healedBitmap = if (targetArea.width() * targetArea.height() > MAX_AREA_SIZE / 2) {
                performBatchedHealing(optimizedBitmap, targetArea, maskBitmap, mode, progressCallback, cancellationToken)
            } else {
                textureSynthesizer.synthesizeTexture(optimizedBitmap, targetArea, maskBitmap)
            }
            
            progressCallback?.invoke(0.9f)
            
            // Scale back to original size if needed
            val finalBitmap = if (scaleFactor != 1.0f) {
                Bitmap.createScaledBitmap(healedBitmap, bitmap.width, bitmap.height, true)
            } else {
                healedBitmap
            }
            
            val healingRegion = HealingRegion(
                id = generateHealingId(),
                targetArea = targetArea,
                targetPath = createPathFromStrokes(brushStrokes),
                brushStrokes = brushStrokes,
                confidence = 0.8f // Default confidence for brush-based healing
            )
            
            val result = HealingResult(
                operationId = healingRegion.id,
                healedBitmap = healedBitmap,
                healedRegion = healingRegion,
                processingTimeMs = System.currentTimeMillis(),
                success = true,
                undoData = createUndoData(bitmap, targetArea)
            )
            
            progressCallback?.invoke(1.0f)
            Result.success(result)
            
        } catch (e: Exception) {
            Result.failure(
                SmartProcessingError.AlgorithmFailure("HealingTool", e)
            )
        }
    }

    /**
     * Validates if a healing area is suitable for processing
     */
    fun validateHealingArea(bitmap: Bitmap, targetArea: Rect): HealingValidation {
        // Check area size
        val areaSize = targetArea.width() * targetArea.height()
        if (areaSize > MAX_AREA_SIZE) {
            return HealingValidation(
                false,
                "Selected area is too large. Maximum area is ${MAX_AREA_SIZE} pixels."
            )
        }
        
        // Check if area is within bounds
        if (targetArea.left < 0 || targetArea.top < 0 ||
            targetArea.right > bitmap.width || targetArea.bottom > bitmap.height) {
            return HealingValidation(
                false,
                "Selected area extends beyond image boundaries."
            )
        }
        
        // Check proximity to edges
        val nearEdge = targetArea.left < EDGE_PROXIMITY_THRESHOLD ||
                targetArea.top < EDGE_PROXIMITY_THRESHOLD ||
                (bitmap.width - targetArea.right) < EDGE_PROXIMITY_THRESHOLD ||
                (bitmap.height - targetArea.bottom) < EDGE_PROXIMITY_THRESHOLD
        
        if (nearEdge) {
            return HealingValidation(
                true,
                "Area is near image edge. Healing quality may be reduced.",
                isWarning = true
            )
        }
        
        return HealingValidation(true, "Area is suitable for healing.")
    }

    /**
     * Finds source region candidates for manual selection
     */
    suspend fun findSourceCandidates(
        bitmap: Bitmap,
        targetArea: Rect,
        maxCandidates: Int = 5
    ): List<SourceRegionDetector.SourceCandidate> = withContext(Dispatchers.Default) {
        sourceRegionDetector.findSourceRegionCandidates(
            bitmap,
            targetArea,
            excludeAreas = listOf(targetArea),
            maxCandidates = maxCandidates
        )
    }

    /**
     * Performs the actual healing operation
     */
    private suspend fun performHealing(
        bitmap: Bitmap,
        healingRegion: HealingRegion,
        mode: ProcessingMode,
        progressCallback: ((Float) -> Unit)?
    ): Bitmap = withContext(Dispatchers.Default) {
        
        val sourceArea = healingRegion.sourceArea
            ?: throw IllegalArgumentException("No source area specified")
        
        // Optimize processing based on performance mode
        val processingBitmap = when (mode) {
            ProcessingMode.LITE -> {
                // Reduce resolution for faster processing
                val scaleFactor = performanceManager.getOptimalProcessingSize(
                    android.util.Size(bitmap.width, bitmap.height),
                    mode
                )
                if (scaleFactor.width < bitmap.width) {
                    Bitmap.createScaledBitmap(bitmap, scaleFactor.width, scaleFactor.height, true)
                } else {
                    bitmap
                }
            }
            else -> bitmap
        }
        
        progressCallback?.invoke(0.7f)
        
        // Create mask for the target area
        val maskBitmap = createMaskFromRect(processingBitmap, healingRegion.targetArea)
        
        progressCallback?.invoke(0.8f)
        
        // Perform texture synthesis
        val result = textureSynthesizer.synthesizeTexture(
            processingBitmap,
            healingRegion.targetArea,
            maskBitmap
        )
        
        // Scale back up if we downscaled
        if (processingBitmap != bitmap) {
            Bitmap.createScaledBitmap(result, bitmap.width, bitmap.height, true)
        } else {
            result
        }
    }

    /**
     * Creates a mask bitmap from brush strokes
     */
    private fun createMaskFromStrokes(
        bitmap: Bitmap,
        brushStrokes: List<BrushStroke>,
        brushSettings: HealingBrushModel
    ): Bitmap {
        val maskBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(maskBitmap)
        
        val paint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        
        brushStrokes.forEach { stroke ->
            val path = Path()
            if (stroke.points.isNotEmpty()) {
                path.moveTo(stroke.points[0].x, stroke.points[0].y)
                stroke.points.drop(1).forEach { point ->
                    path.lineTo(point.x, point.y)
                }
                canvas.drawPath(path, paint)
            }
        }
        
        return maskBitmap
    }

    /**
     * Creates a mask bitmap from a rectangular area
     */
    private fun createMaskFromRect(bitmap: Bitmap, rect: Rect): Bitmap {
        val maskBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(maskBitmap)
        
        val paint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        
        canvas.drawRect(rect, paint)
        return maskBitmap
    }

    /**
     * Calculates bounding rectangle for brush strokes
     */
    private fun calculateBoundingRect(brushStrokes: List<BrushStroke>): Rect {
        if (brushStrokes.isEmpty()) return Rect()
        
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        
        brushStrokes.forEach { stroke ->
            stroke.points.forEach { point ->
                minX = min(minX, point.x)
                minY = min(minY, point.y)
                maxX = max(maxX, point.x)
                maxY = max(maxY, point.y)
            }
        }
        
        return Rect(
            minX.toInt(),
            minY.toInt(),
            maxX.toInt(),
            maxY.toInt()
        )
    }

    /**
     * Creates a path from brush strokes
     */
    private fun createPathFromStrokes(brushStrokes: List<BrushStroke>): Path {
        val path = Path()
        
        brushStrokes.forEach { stroke ->
            if (stroke.points.isNotEmpty()) {
                path.moveTo(stroke.points[0].x, stroke.points[0].y)
                stroke.points.drop(1).forEach { point ->
                    path.lineTo(point.x, point.y)
                }
            }
        }
        
        return path
    }

    /**
     * Creates a path from a rectangle
     */
    private fun createPathFromRect(rect: Rect): Path {
        val path = Path()
        path.addRect(
            rect.left.toFloat(),
            rect.top.toFloat(),
            rect.right.toFloat(),
            rect.bottom.toFloat(),
            Path.Direction.CW
        )
        return path
    }

    /**
     * Calculates confidence score for healing operation
     */
    private fun calculateHealingConfidence(
        bitmap: Bitmap,
        targetArea: Rect,
        sourceArea: Rect
    ): Float {
        // Simplified confidence calculation based on area similarity
        val targetPixels = extractPixels(bitmap, targetArea)
        val sourcePixels = extractPixels(bitmap, sourceArea)
        
        if (targetPixels.size != sourcePixels.size) return 0.5f
        
        var similarity = 0f
        for (i in targetPixels.indices) {
            val targetGray = getGrayscaleValue(targetPixels[i])
            val sourceGray = getGrayscaleValue(sourcePixels[i])
            similarity += 1f - kotlin.math.abs(targetGray - sourceGray) / 255f
        }
        
        return (similarity / targetPixels.size).coerceIn(0f, 1f)
    }

    /**
     * Extracts pixels from a rectangular area
     */
    private fun extractPixels(bitmap: Bitmap, rect: Rect): IntArray {
        val pixels = IntArray(rect.width() * rect.height())
        bitmap.getPixels(
            pixels, 0, rect.width(),
            rect.left, rect.top,
            rect.width(), rect.height()
        )
        return pixels
    }

    /**
     * Converts color to grayscale
     */
    private fun getGrayscaleValue(pixel: Int): Float {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    /**
     * Creates undo data for the operation
     */
    private fun createUndoData(bitmap: Bitmap, area: Rect): HealingUndoData {
        val originalPixels = Bitmap.createBitmap(
            bitmap,
            area.left,
            area.top,
            area.width(),
            area.height()
        )
        
        return HealingUndoData(
            originalArea = area,
            originalPixels = originalPixels
        )
    }

    /**
     * Optimizes bitmap size for healing based on performance mode
     */
    private fun optimizeBitmapForHealing(bitmap: Bitmap, mode: ProcessingMode): Bitmap {
        val optimalSize = performanceManager.getOptimalProcessingSize(
            android.util.Size(bitmap.width, bitmap.height),
            mode
        )
        
        return if (optimalSize.width < bitmap.width || optimalSize.height < bitmap.height) {
            Bitmap.createScaledBitmap(bitmap, optimalSize.width, optimalSize.height, true)
        } else {
            bitmap
        }
    }
    
    /**
     * Scales brush strokes to match resized bitmap
     */
    private fun scaleStrokes(strokes: List<BrushStroke>, scaleFactor: Float): List<BrushStroke> {
        return strokes.map { stroke ->
            stroke.copy(
                points = stroke.points.map { point ->
                    android.graphics.PointF(point.x * scaleFactor, point.y * scaleFactor)
                },
                brushSize = stroke.brushSize * scaleFactor
            )
        }
    }
    
    /**
     * Performs healing in batches for large areas to manage memory
     */
    private suspend fun performBatchedHealing(
        bitmap: Bitmap,
        targetArea: Rect,
        maskBitmap: Bitmap,
        mode: ProcessingMode,
        progressCallback: ((Float) -> Unit)?,
        cancellationToken: (() -> Boolean)?
    ): Bitmap = withContext(Dispatchers.Default) {
        val result = bitmap.copy(bitmap.config, true)
        val batchSize = when (mode) {
            ProcessingMode.LITE -> 64
            ProcessingMode.MEDIUM -> 128
            ProcessingMode.ADVANCED -> 256
        }
        
        val batches = createHealingBatches(targetArea, batchSize)
        val totalBatches = batches.size
        
        batches.forEachIndexed { index, batchArea ->
            // Check for cancellation
            if (cancellationToken?.invoke() == true) {
                throw Exception("Operation cancelled")
            }
            
            // Process batch
            val batchMask = Bitmap.createBitmap(
                maskBitmap,
                batchArea.left,
                batchArea.top,
                batchArea.width(),
                batchArea.height()
            )
            
            val healedBatch = textureSynthesizer.synthesizeTexture(
                bitmap,
                batchArea,
                batchMask
            )
            
            // Copy healed batch back to result
            val canvas = Canvas(result)
            canvas.drawBitmap(
                healedBatch,
                batchArea.left.toFloat(),
                batchArea.top.toFloat(),
                null
            )
            
            // Update progress
            val batchProgress = 0.5f + (index + 1).toFloat() / totalBatches * 0.4f
            progressCallback?.invoke(batchProgress)
            
            // Clean up batch resources
            batchMask.recycle()
            if (healedBatch != bitmap) {
                healedBatch.recycle()
            }
        }
        
        result
    }
    
    /**
     * Creates healing batches for large areas
     */
    private fun createHealingBatches(targetArea: Rect, batchSize: Int): List<Rect> {
        val batches = mutableListOf<Rect>()
        
        var y = targetArea.top
        while (y < targetArea.bottom) {
            var x = targetArea.left
            while (x < targetArea.right) {
                val batchRect = Rect(
                    x,
                    y,
                    min(x + batchSize, targetArea.right),
                    min(y + batchSize, targetArea.bottom)
                )
                batches.add(batchRect)
                x += batchSize
            }
            y += batchSize
        }
        
        return batches
    }
    
    /**
     * Manages memory during healing operations
     */
    private fun manageHealingMemory() {
        // Force garbage collection for large operations
        System.gc()
        
        // Check available memory
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val availableMemory = maxMemory - usedMemory
        
        // If memory is low, suggest reducing quality
        if (availableMemory < maxMemory * 0.2) {
            throw OutOfMemoryError("Insufficient memory for healing operation")
        }
    }
    
    /**
     * Generates unique ID for healing operations
     */
    private fun generateHealingId(): String {
        return "healing_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}"
    }
}