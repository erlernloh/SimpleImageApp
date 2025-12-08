package com.imagedit.app.util.image

import android.graphics.*
import com.imagedit.app.domain.model.TextureFeatures
import kotlin.math.*

/**
 * Implements intelligent source region detection for automatic healing source area finding
 */
class SourceRegionDetector {

    companion object {
        private const val MIN_DISTANCE_FACTOR = 2.0f // Minimum distance as factor of target size
        private const val MAX_SEARCH_RADIUS_FACTOR = 4.0f // Maximum search radius as factor of target size
        private const val SIMILARITY_WEIGHT = 0.4f
        private const val DISTANCE_WEIGHT = 0.3f
        private const val EDGE_WEIGHT = 0.3f
        private const val MIN_SIMILARITY_THRESHOLD = 0.6f
        private const val MAX_CANDIDATES = 20
    }

    /**
     * Finds the best source region for healing a target area
     */
    fun findBestSourceRegion(
        bitmap: Bitmap,
        targetArea: Rect,
        excludeAreas: List<Rect> = emptyList()
    ): Rect? {
        val targetFeatures = analyzeTargetArea(bitmap, targetArea)
        val candidates = generateSourceCandidates(bitmap, targetArea, excludeAreas)
        
        return selectBestCandidate(bitmap, targetArea, targetFeatures, candidates)
    }

    /**
     * Finds multiple source region candidates ranked by quality
     */
    fun findSourceRegionCandidates(
        bitmap: Bitmap,
        targetArea: Rect,
        excludeAreas: List<Rect> = emptyList(),
        maxCandidates: Int = 5
    ): List<SourceCandidate> {
        val targetFeatures = analyzeTargetArea(bitmap, targetArea)
        val candidates = generateSourceCandidates(bitmap, targetArea, excludeAreas)
        
        return candidates
            .map { candidateRect ->
                val score = calculateCandidateScore(bitmap, targetArea, candidateRect, targetFeatures)
                SourceCandidate(candidateRect, score)
            }
            .filter { it.score > MIN_SIMILARITY_THRESHOLD }
            .sortedByDescending { it.score }
            .take(maxCandidates)
    }

    /**
     * Analyzes the target area to extract features for matching
     */
    private fun analyzeTargetArea(bitmap: Bitmap, targetArea: Rect): TargetAnalysis {
        // Analyze border pixels to understand what we're trying to match
        val borderPixels = extractBorderPixels(bitmap, targetArea)
        val centerPixels = extractCenterPixels(bitmap, targetArea)
        
        return TargetAnalysis(
            averageColor = calculateAverageColor(borderPixels),
            colorVariance = calculateColorVariance(borderPixels),
            edgePattern = analyzeEdgePattern(bitmap, targetArea),
            textureComplexity = calculateTextureComplexity(centerPixels),
            dominantDirection = calculateDominantDirection(bitmap, targetArea)
        )
    }

    /**
     * Extracts pixels from the border of the target area
     */
    private fun extractBorderPixels(bitmap: Bitmap, targetArea: Rect): IntArray {
        val borderPixels = mutableListOf<Int>()
        val borderWidth = 3 // Width of border to analyze
        
        // Top and bottom borders
        for (x in targetArea.left until targetArea.right) {
            for (i in 0 until borderWidth) {
                // Top border
                val topY = (targetArea.top - borderWidth + i).coerceIn(0, bitmap.height - 1)
                if (topY >= 0) borderPixels.add(bitmap.getPixel(x, topY))
                
                // Bottom border
                val bottomY = (targetArea.bottom + i).coerceIn(0, bitmap.height - 1)
                if (bottomY < bitmap.height) borderPixels.add(bitmap.getPixel(x, bottomY))
            }
        }
        
        // Left and right borders
        for (y in targetArea.top until targetArea.bottom) {
            for (i in 0 until borderWidth) {
                // Left border
                val leftX = (targetArea.left - borderWidth + i).coerceIn(0, bitmap.width - 1)
                if (leftX >= 0) borderPixels.add(bitmap.getPixel(leftX, y))
                
                // Right border
                val rightX = (targetArea.right + i).coerceIn(0, bitmap.width - 1)
                if (rightX < bitmap.width) borderPixels.add(bitmap.getPixel(rightX, y))
            }
        }
        
        return borderPixels.toIntArray()
    }

    /**
     * Extracts pixels from the center of the target area
     */
    private fun extractCenterPixels(bitmap: Bitmap, targetArea: Rect): IntArray {
        val centerPixels = mutableListOf<Int>()
        val centerRect = Rect(
            targetArea.left + targetArea.width() / 4,
            targetArea.top + targetArea.height() / 4,
            targetArea.right - targetArea.width() / 4,
            targetArea.bottom - targetArea.height() / 4
        )
        
        for (y in centerRect.top until centerRect.bottom) {
            for (x in centerRect.left until centerRect.right) {
                if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                    centerPixels.add(bitmap.getPixel(x, y))
                }
            }
        }
        
        return centerPixels.toIntArray()
    }

    /**
     * Generates candidate source regions around the target area
     */
    private fun generateSourceCandidates(
        bitmap: Bitmap,
        targetArea: Rect,
        excludeAreas: List<Rect>
    ): List<Rect> {
        val candidates = mutableListOf<Rect>()
        val targetWidth = targetArea.width()
        val targetHeight = targetArea.height()
        val minDistance = (max(targetWidth, targetHeight) * MIN_DISTANCE_FACTOR).toInt()
        val maxRadius = (max(targetWidth, targetHeight) * MAX_SEARCH_RADIUS_FACTOR).toInt()
        
        val centerX = targetArea.centerX()
        val centerY = targetArea.centerY()
        
        // Generate candidates in concentric circles
        for (radius in minDistance..maxRadius step (minDistance / 2)) {
            for (angle in 0 until 360 step 20) {
                val x = centerX + (radius * cos(Math.toRadians(angle.toDouble()))).toInt()
                val y = centerY + (radius * sin(Math.toRadians(angle.toDouble()))).toInt()
                
                val candidateRect = Rect(
                    x - targetWidth / 2,
                    y - targetHeight / 2,
                    x + targetWidth / 2,
                    y + targetHeight / 2
                )
                
                // Check if candidate is valid
                if (isValidCandidate(bitmap, candidateRect, targetArea, excludeAreas)) {
                    candidates.add(candidateRect)
                }
            }
        }
        
        return candidates.take(MAX_CANDIDATES)
    }

    /**
     * Checks if a candidate region is valid for use as source
     */
    private fun isValidCandidate(
        bitmap: Bitmap,
        candidate: Rect,
        targetArea: Rect,
        excludeAreas: List<Rect>
    ): Boolean {
        // Check bounds
        if (candidate.left < 0 || candidate.top < 0 ||
            candidate.right >= bitmap.width || candidate.bottom >= bitmap.height) {
            return false
        }
        
        // Check if overlaps with target area
        if (Rect.intersects(candidate, targetArea)) {
            return false
        }
        
        // Check if overlaps with excluded areas
        excludeAreas.forEach { excludeArea ->
            if (Rect.intersects(candidate, excludeArea)) {
                return false
            }
        }
        
        return true
    }

    /**
     * Selects the best candidate based on comprehensive scoring
     */
    private fun selectBestCandidate(
        bitmap: Bitmap,
        targetArea: Rect,
        targetFeatures: TargetAnalysis,
        candidates: List<Rect>
    ): Rect? {
        var bestCandidate: Rect? = null
        var bestScore = 0f
        
        candidates.forEach { candidate ->
            val score = calculateCandidateScore(bitmap, targetArea, candidate, targetFeatures)
            if (score > bestScore && score > MIN_SIMILARITY_THRESHOLD) {
                bestScore = score
                bestCandidate = candidate
            }
        }
        
        return bestCandidate
    }

    /**
     * Calculates comprehensive score for a source candidate
     */
    private fun calculateCandidateScore(
        bitmap: Bitmap,
        targetArea: Rect,
        candidate: Rect,
        targetFeatures: TargetAnalysis
    ): Float {
        val candidateFeatures = analyzeCandidateArea(bitmap, candidate)
        
        val similarityScore = calculateSimilarityScore(targetFeatures, candidateFeatures)
        val distanceScore = calculateDistanceScore(targetArea, candidate)
        val edgeScore = calculateEdgeCompatibilityScore(targetFeatures, candidateFeatures)
        
        return SIMILARITY_WEIGHT * similarityScore +
                DISTANCE_WEIGHT * distanceScore +
                EDGE_WEIGHT * edgeScore
    }

    /**
     * Analyzes features of a candidate source area
     */
    private fun analyzeCandidateArea(bitmap: Bitmap, candidate: Rect): TargetAnalysis {
        val candidatePixels = extractAreaPixels(bitmap, candidate)
        
        return TargetAnalysis(
            averageColor = calculateAverageColor(candidatePixels),
            colorVariance = calculateColorVariance(candidatePixels),
            edgePattern = analyzeEdgePattern(bitmap, candidate),
            textureComplexity = calculateTextureComplexity(candidatePixels),
            dominantDirection = calculateDominantDirection(bitmap, candidate)
        )
    }

    /**
     * Extracts all pixels from an area
     */
    private fun extractAreaPixels(bitmap: Bitmap, area: Rect): IntArray {
        val pixels = IntArray(area.width() * area.height())
        bitmap.getPixels(
            pixels, 0, area.width(),
            area.left, area.top,
            area.width(), area.height()
        )
        return pixels
    }

    /**
     * Calculates color similarity between target and candidate
     */
    private fun calculateSimilarityScore(
        target: TargetAnalysis,
        candidate: TargetAnalysis
    ): Float {
        val colorDistance = calculateColorDistance(target.averageColor, candidate.averageColor)
        val varianceDistance = abs(target.colorVariance - candidate.colorVariance)
        val complexityDistance = abs(target.textureComplexity - candidate.textureComplexity)
        
        // Normalize and invert distances to get similarity scores
        val colorSimilarity = 1f - (colorDistance / 255f).coerceIn(0f, 1f)
        val varianceSimilarity = 1f - (varianceDistance / 10000f).coerceIn(0f, 1f)
        val complexitySimilarity = 1f - (complexityDistance / 100f).coerceIn(0f, 1f)
        
        return (colorSimilarity + varianceSimilarity + complexitySimilarity) / 3f
    }

    /**
     * Calculates distance-based score (closer is better, but not too close)
     */
    private fun calculateDistanceScore(targetArea: Rect, candidate: Rect): Float {
        val distance = sqrt(
            (targetArea.centerX() - candidate.centerX()).toDouble().pow(2) +
            (targetArea.centerY() - candidate.centerY()).toDouble().pow(2)
        ).toFloat()
        
        val targetSize = max(targetArea.width(), targetArea.height())
        val optimalDistance = targetSize * 2f
        val maxDistance = targetSize * 4f
        
        return when {
            distance < optimalDistance -> distance / optimalDistance
            distance < maxDistance -> 1f - (distance - optimalDistance) / (maxDistance - optimalDistance)
            else -> 0f
        }
    }

    /**
     * Calculates edge compatibility score
     */
    private fun calculateEdgeCompatibilityScore(
        target: TargetAnalysis,
        candidate: TargetAnalysis
    ): Float {
        val directionDiff = abs(target.dominantDirection - candidate.dominantDirection)
        val normalizedDiff = min(directionDiff, 2 * PI.toFloat() - directionDiff) / PI.toFloat()
        
        return 1f - normalizedDiff
    }

    /**
     * Calculates average color of pixel array
     */
    private fun calculateAverageColor(pixels: IntArray): Int {
        if (pixels.isEmpty()) return Color.BLACK
        
        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        
        pixels.forEach { pixel ->
            totalR += Color.red(pixel)
            totalG += Color.green(pixel)
            totalB += Color.blue(pixel)
        }
        
        return Color.rgb(
            (totalR / pixels.size).toInt(),
            (totalG / pixels.size).toInt(),
            (totalB / pixels.size).toInt()
        )
    }

    /**
     * Calculates color variance of pixel array
     */
    private fun calculateColorVariance(pixels: IntArray): Float {
        if (pixels.isEmpty()) return 0f
        
        val avgColor = calculateAverageColor(pixels)
        val avgR = Color.red(avgColor)
        val avgG = Color.green(avgColor)
        val avgB = Color.blue(avgColor)
        
        var variance = 0f
        pixels.forEach { pixel ->
            val rDiff = Color.red(pixel) - avgR
            val gDiff = Color.green(pixel) - avgG
            val bDiff = Color.blue(pixel) - avgB
            variance += (rDiff * rDiff + gDiff * gDiff + bDiff * bDiff).toFloat()
        }
        
        return variance / pixels.size
    }

    /**
     * Analyzes edge patterns in an area
     */
    private fun analyzeEdgePattern(bitmap: Bitmap, area: Rect): Float {
        var edgeStrength = 0f
        var pixelCount = 0
        
        for (y in area.top + 1 until area.bottom - 1) {
            for (x in area.left + 1 until area.right - 1) {
                if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                    val center = bitmap.getPixel(x, y)
                    val right = bitmap.getPixel(x + 1, y)
                    val bottom = bitmap.getPixel(x, y + 1)
                    
                    val gradX = getGrayscaleValue(right) - getGrayscaleValue(center)
                    val gradY = getGrayscaleValue(bottom) - getGrayscaleValue(center)
                    
                    edgeStrength += sqrt(gradX * gradX + gradY * gradY).toFloat()
                    pixelCount++
                }
            }
        }
        
        return if (pixelCount > 0) edgeStrength / pixelCount else 0f
    }

    /**
     * Calculates texture complexity
     */
    private fun calculateTextureComplexity(pixels: IntArray): Float {
        if (pixels.size < 4) return 0f
        
        var complexity = 0f
        for (i in 1 until pixels.size) {
            val diff = abs(getGrayscaleValue(pixels[i]) - getGrayscaleValue(pixels[i - 1]))
            complexity += diff.toFloat()
        }
        
        return complexity / pixels.size
    }

    /**
     * Calculates dominant direction of texture patterns
     */
    private fun calculateDominantDirection(bitmap: Bitmap, area: Rect): Float {
        // Simplified gradient direction analysis
        var sumX = 0.0
        var sumY = 0.0
        var count = 0
        
        for (y in area.top + 1 until area.bottom - 1) {
            for (x in area.left + 1 until area.right - 1) {
                if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                    val center = bitmap.getPixel(x, y)
                    val right = bitmap.getPixel(x + 1, y)
                    val bottom = bitmap.getPixel(x, y + 1)
                    
                    val gradX = getGrayscaleValue(right) - getGrayscaleValue(center)
                    val gradY = getGrayscaleValue(bottom) - getGrayscaleValue(center)
                    
                    if (abs(gradX) + abs(gradY) > 10) { // Only consider significant gradients
                        sumX += gradX
                        sumY += gradY
                        count++
                    }
                }
            }
        }
        
        return if (count > 0) {
            atan2(sumY / count, sumX / count).toFloat()
        } else {
            0f
        }
    }

    /**
     * Calculates color distance between two colors
     */
    private fun calculateColorDistance(color1: Int, color2: Int): Float {
        val r1 = Color.red(color1)
        val g1 = Color.green(color1)
        val b1 = Color.blue(color1)
        val r2 = Color.red(color2)
        val g2 = Color.green(color2)
        val b2 = Color.blue(color2)
        
        return sqrt(
            ((r1 - r2) * (r1 - r2) + (g1 - g2) * (g1 - g2) + (b1 - b2) * (b1 - b2)).toDouble()
        ).toFloat()
    }

    /**
     * Converts color to grayscale value
     */
    private fun getGrayscaleValue(pixel: Int): Double {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    /**
     * Data class for target area analysis
     */
    private data class TargetAnalysis(
        val averageColor: Int,
        val colorVariance: Float,
        val edgePattern: Float,
        val textureComplexity: Float,
        val dominantDirection: Float
    )

    /**
     * Data class for source region candidates with scores
     */
    data class SourceCandidate(
        val region: Rect,
        val score: Float
    )
}