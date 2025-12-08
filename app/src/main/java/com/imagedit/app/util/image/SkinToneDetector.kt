package com.imagedit.app.util.image

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.imagedit.app.domain.model.SkinRegion
import com.imagedit.app.domain.model.SkinToneAnalysis
import kotlin.math.*

/**
 * Advanced skin tone detection for portrait enhancement using HSV-based analysis
 * with adaptive thresholds and confidence scoring.
 */
class SkinToneDetector {
    
    companion object {
        // Base HSV ranges for skin tone detection
        private const val SKIN_HUE_MIN_1 = 0f
        private const val SKIN_HUE_MAX_1 = 50f
        private const val SKIN_HUE_MIN_2 = 340f
        private const val SKIN_HUE_MAX_2 = 360f
        
        private const val SKIN_SATURATION_MIN = 0.15f
        private const val SKIN_SATURATION_MAX = 0.85f
        private const val SKIN_VALUE_MIN = 0.25f
        private const val SKIN_VALUE_MAX = 0.95f
        
        // Adaptive threshold parameters
        private const val CONFIDENCE_THRESHOLD = 0.3f
        private const val MIN_REGION_SIZE = 25
        private const val CLUSTERING_DISTANCE = 15
        
        // Skin tone confidence weights
        private const val HUE_WEIGHT = 0.4f
        private const val SATURATION_WEIGHT = 0.3f
        private const val VALUE_WEIGHT = 0.2f
        private const val CONTEXT_WEIGHT = 0.1f
    }
    
    /**
     * Performs comprehensive skin tone detection with adaptive thresholds.
     */
    fun detectSkinTones(bitmap: Bitmap): SkinToneAnalysis {
        // Step 1: Initial pixel-level skin detection
        val skinPixels = performInitialSkinDetection(bitmap)
        
        // Step 2: Apply adaptive thresholds based on image characteristics
        val adaptiveThresholds = calculateAdaptiveThresholds(bitmap, skinPixels)
        val refinedSkinPixels = refineSkinDetection(bitmap, adaptiveThresholds)
        
        // Step 3: Perform region segmentation
        val skinRegions = segmentSkinRegions(refinedSkinPixels, bitmap.width, bitmap.height)
        
        // Step 4: Calculate overall statistics
        val totalPixels = bitmap.width * bitmap.height
        val skinPercentage = refinedSkinPixels.size.toFloat() / totalPixels
        
        val overallConfidence = if (refinedSkinPixels.isNotEmpty()) {
            refinedSkinPixels.map { it.confidence }.average().toFloat()
        } else 0f
        
        val (avgHue, avgSaturation, avgBrightness) = calculateAverageCharacteristics(refinedSkinPixels)
        
        val portraitRecommended = skinPercentage > 0.12f && 
                                 overallConfidence > 0.5f && 
                                 skinRegions.isNotEmpty()
        
        return SkinToneAnalysis(
            skinPercentage = skinPercentage,
            confidence = overallConfidence,
            skinRegions = skinRegions,
            averageSkinHue = avgHue,
            averageSkinSaturation = avgSaturation,
            averageSkinBrightness = avgBrightness,
            portraitRecommended = portraitRecommended
        )
    }
    
    /**
     * Detects skin regions with confidence scoring for selective processing.
     */
    fun detectSkinRegionsWithConfidence(bitmap: Bitmap, minConfidence: Float = 0.5f): List<SkinRegion> {
        val skinAnalysis = detectSkinTones(bitmap)
        return skinAnalysis.skinRegions.filter { it.confidence >= minConfidence }
    }
    
    /**
     * Creates a binary mask for skin areas.
     */
    fun createSkinMask(bitmap: Bitmap, minConfidence: Float = 0.4f): Bitmap {
        val maskBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ALPHA_8)
        val adaptiveThresholds = calculateAdaptiveThresholds(bitmap, emptyList())
        
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val confidence = calculateSkinConfidence(pixel, adaptiveThresholds)
                
                val alpha = if (confidence >= minConfidence) {
                    (confidence * 255).toInt().coerceIn(0, 255)
                } else 0
                
                maskBitmap.setPixel(x, y, Color.argb(alpha, 0, 0, 0))
            }
        }
        
        return maskBitmap
    }
    
    // Private implementation methods
    
    private data class SkinPixel(
        val x: Int,
        val y: Int,
        val color: Int,
        val confidence: Float,
        val hue: Float,
        val saturation: Float,
        val value: Float
    )
    
    private data class AdaptiveThresholds(
        val hueMin1: Float,
        val hueMax1: Float,
        val hueMin2: Float,
        val hueMax2: Float,
        val saturationMin: Float,
        val saturationMax: Float,
        val valueMin: Float,
        val valueMax: Float,
        val contextualBoost: Float
    )
    
    private fun performInitialSkinDetection(bitmap: Bitmap): List<SkinPixel> {
        val skinPixels = mutableListOf<SkinPixel>()
        
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val hsv = FloatArray(3)
                Color.colorToHSV(pixel, hsv)
                
                val confidence = calculateBasicSkinConfidence(hsv[0], hsv[1], hsv[2])
                
                if (confidence > CONFIDENCE_THRESHOLD) {
                    skinPixels.add(SkinPixel(x, y, pixel, confidence, hsv[0], hsv[1], hsv[2]))
                }
            }
        }
        
        return skinPixels
    }
    
    private fun calculateBasicSkinConfidence(hue: Float, saturation: Float, value: Float): Float {
        // Check if hue falls within skin tone ranges
        val hueMatch = (hue >= SKIN_HUE_MIN_1 && hue <= SKIN_HUE_MAX_1) ||
                      (hue >= SKIN_HUE_MIN_2 && hue <= SKIN_HUE_MAX_2)
        
        if (!hueMatch) return 0f
        
        // Check saturation and value ranges
        val saturationMatch = saturation >= SKIN_SATURATION_MIN && saturation <= SKIN_SATURATION_MAX
        val valueMatch = value >= SKIN_VALUE_MIN && value <= SKIN_VALUE_MAX
        
        if (!saturationMatch || !valueMatch) return 0f
        
        // Calculate confidence based on how well values match ideal skin tone
        val hueConfidence = calculateHueConfidence(hue)
        val saturationConfidence = calculateSaturationConfidence(saturation)
        val valueConfidence = calculateValueConfidence(value)
        
        return (hueConfidence * HUE_WEIGHT + 
                saturationConfidence * SATURATION_WEIGHT + 
                valueConfidence * VALUE_WEIGHT).coerceIn(0f, 1f)
    }
    
    private fun calculateHueConfidence(hue: Float): Float {
        return when {
            hue <= 25f -> 1f - (hue / 25f) * 0.2f // Best range
            hue <= 50f -> 0.8f - ((hue - 25f) / 25f) * 0.5f // Good range
            hue >= 350f -> 1f - ((360f - hue) / 10f) * 0.2f // Wrap-around range
            hue >= 340f -> 0.8f - ((350f - hue) / 10f) * 0.5f // Wrap-around good range
            else -> 0f
        }
    }
    
    private fun calculateSaturationConfidence(saturation: Float): Float {
        val optimal = 0.45f
        val distance = abs(saturation - optimal)
        return (1f - distance * 2f).coerceIn(0f, 1f)
    }
    
    private fun calculateValueConfidence(value: Float): Float {
        val optimal = 0.65f
        val distance = abs(value - optimal)
        return (1f - distance * 1.5f).coerceIn(0f, 1f)
    }
    
    private fun calculateAdaptiveThresholds(bitmap: Bitmap, initialSkinPixels: List<SkinPixel>): AdaptiveThresholds {
        if (initialSkinPixels.isEmpty()) {
            return AdaptiveThresholds(
                SKIN_HUE_MIN_1, SKIN_HUE_MAX_1,
                SKIN_HUE_MIN_2, SKIN_HUE_MAX_2,
                SKIN_SATURATION_MIN, SKIN_SATURATION_MAX,
                SKIN_VALUE_MIN, SKIN_VALUE_MAX,
                0f
            )
        }
        
        // Analyze detected skin pixels to adapt thresholds
        val hues = initialSkinPixels.map { it.hue }
        val saturations = initialSkinPixels.map { it.saturation }
        val values = initialSkinPixels.map { it.value }
        
        // Calculate statistics for adaptive adjustment
        val hueStdDev = calculateStandardDeviation(hues)
        val saturationMean = saturations.average().toFloat()
        val valueMean = values.average().toFloat()
        
        // Adjust thresholds based on image characteristics
        val hueExpansion = (hueStdDev * 0.5f).coerceIn(0f, 10f)
        val saturationAdjustment = (saturationMean - 0.45f) * 0.3f
        val valueAdjustment = (valueMean - 0.65f) * 0.2f
        
        return AdaptiveThresholds(
            hueMin1 = (SKIN_HUE_MIN_1 - hueExpansion).coerceAtLeast(0f),
            hueMax1 = (SKIN_HUE_MAX_1 + hueExpansion).coerceAtMost(60f),
            hueMin2 = (SKIN_HUE_MIN_2 - hueExpansion).coerceAtLeast(330f),
            hueMax2 = SKIN_HUE_MAX_2,
            saturationMin = (SKIN_SATURATION_MIN + saturationAdjustment).coerceIn(0.1f, 0.4f),
            saturationMax = (SKIN_SATURATION_MAX + saturationAdjustment).coerceIn(0.6f, 0.9f),
            valueMin = (SKIN_VALUE_MIN + valueAdjustment).coerceIn(0.2f, 0.4f),
            valueMax = (SKIN_VALUE_MAX + valueAdjustment).coerceIn(0.8f, 1f),
            contextualBoost = if (initialSkinPixels.size > 1000) 0.1f else 0f
        )
    }
    
    private fun refineSkinDetection(bitmap: Bitmap, thresholds: AdaptiveThresholds): List<SkinPixel> {
        val refinedPixels = mutableListOf<SkinPixel>()
        
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val confidence = calculateSkinConfidence(pixel, thresholds)
                
                if (confidence > CONFIDENCE_THRESHOLD) {
                    val hsv = FloatArray(3)
                    Color.colorToHSV(pixel, hsv)
                    refinedPixels.add(SkinPixel(x, y, pixel, confidence, hsv[0], hsv[1], hsv[2]))
                }
            }
        }
        
        return refinedPixels
    }
    
    private fun calculateSkinConfidence(pixel: Int, thresholds: AdaptiveThresholds): Float {
        val hsv = FloatArray(3)
        Color.colorToHSV(pixel, hsv)
        
        val hue = hsv[0]
        val saturation = hsv[1]
        val value = hsv[2]
        
        // Check if pixel falls within adaptive thresholds
        val hueMatch = (hue >= thresholds.hueMin1 && hue <= thresholds.hueMax1) ||
                      (hue >= thresholds.hueMin2 && hue <= thresholds.hueMax2)
        
        if (!hueMatch) return 0f
        
        val saturationMatch = saturation >= thresholds.saturationMin && saturation <= thresholds.saturationMax
        val valueMatch = value >= thresholds.valueMin && value <= thresholds.valueMax
        
        if (!saturationMatch || !valueMatch) return 0f
        
        // Calculate refined confidence
        val hueConfidence = calculateHueConfidence(hue)
        val saturationConfidence = calculateSaturationConfidence(saturation)
        val valueConfidence = calculateValueConfidence(value)
        
        val baseConfidence = hueConfidence * HUE_WEIGHT + 
                           saturationConfidence * SATURATION_WEIGHT + 
                           valueConfidence * VALUE_WEIGHT
        
        return (baseConfidence + thresholds.contextualBoost).coerceIn(0f, 1f)
    }
    
    private fun segmentSkinRegions(skinPixels: List<SkinPixel>, width: Int, height: Int): List<SkinRegion> {
        val regions = mutableListOf<SkinRegion>()
        val processed = mutableSetOf<SkinPixel>()
        
        // Create spatial index for efficient neighbor finding
        val spatialIndex = createSpatialIndex(skinPixels, width, height)
        
        for (pixel in skinPixels) {
            if (pixel in processed) continue
            
            val regionPixels = mutableListOf<SkinPixel>()
            val queue = mutableListOf(pixel)
            
            // Flood fill to find connected skin regions
            while (queue.isNotEmpty()) {
                val current = queue.removeAt(0)
                if (current in processed) continue
                
                processed.add(current)
                regionPixels.add(current)
                
                // Find neighbors within clustering distance
                val neighbors = findNeighbors(current, spatialIndex, CLUSTERING_DISTANCE)
                for (neighbor in neighbors) {
                    if (neighbor !in processed && neighbor !in queue) {
                        queue.add(neighbor)
                    }
                }
            }
            
            // Create region if it meets minimum size requirement
            if (regionPixels.size >= MIN_REGION_SIZE) {
                val region = createSkinRegion(regionPixels)
                regions.add(region)
            }
        }
        
        return regions.sortedByDescending { it.pixelCount }
    }
    
    private fun createSpatialIndex(pixels: List<SkinPixel>, width: Int, height: Int): Map<Pair<Int, Int>, List<SkinPixel>> {
        val cellSize = 10
        val index = mutableMapOf<Pair<Int, Int>, MutableList<SkinPixel>>()
        
        for (pixel in pixels) {
            val cellX = pixel.x / cellSize
            val cellY = pixel.y / cellSize
            val key = Pair(cellX, cellY)
            
            index.getOrPut(key) { mutableListOf() }.add(pixel)
        }
        
        return index
    }
    
    private fun findNeighbors(pixel: SkinPixel, spatialIndex: Map<Pair<Int, Int>, List<SkinPixel>>, maxDistance: Int): List<SkinPixel> {
        val neighbors = mutableListOf<SkinPixel>()
        val cellSize = 10
        val searchRadius = (maxDistance / cellSize) + 1
        
        val centerCellX = pixel.x / cellSize
        val centerCellY = pixel.y / cellSize
        
        for (dx in -searchRadius..searchRadius) {
            for (dy in -searchRadius..searchRadius) {
                val cellKey = Pair(centerCellX + dx, centerCellY + dy)
                val cellPixels = spatialIndex[cellKey] ?: continue
                
                for (candidate in cellPixels) {
                    if (candidate != pixel) {
                        val distance = sqrt(
                            (candidate.x - pixel.x).toFloat().pow(2) + 
                            (candidate.y - pixel.y).toFloat().pow(2)
                        )
                        
                        if (distance <= maxDistance) {
                            neighbors.add(candidate)
                        }
                    }
                }
            }
        }
        
        return neighbors
    }
    
    private fun createSkinRegion(pixels: List<SkinPixel>): SkinRegion {
        val minX = pixels.minOf { it.x }
        val maxX = pixels.maxOf { it.x }
        val minY = pixels.minOf { it.y }
        val maxY = pixels.maxOf { it.y }
        
        val bounds = Rect(minX, minY, maxX, maxY)
        val avgConfidence = pixels.map { it.confidence }.average().toFloat()
        val avgColor = blendColors(pixels.map { it.color })
        
        return SkinRegion(
            bounds = bounds,
            confidence = avgConfidence,
            pixelCount = pixels.size,
            averageColor = avgColor
        )
    }
    
    private fun calculateAverageCharacteristics(skinPixels: List<SkinPixel>): Triple<Float, Float, Float> {
        if (skinPixels.isEmpty()) return Triple(0f, 0f, 0f)
        
        val avgHue = skinPixels.map { it.hue }.average().toFloat()
        val avgSaturation = skinPixels.map { it.saturation }.average().toFloat()
        val avgBrightness = skinPixels.map { it.value }.average().toFloat()
        
        return Triple(avgHue, avgSaturation, avgBrightness)
    }
    
    private fun calculateStandardDeviation(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        
        val mean = values.average().toFloat()
        val variance = values.map { (it - mean).pow(2) }.average().toFloat()
        return sqrt(variance)
    }
    
    private fun blendColors(colors: List<Int>): Int {
        if (colors.isEmpty()) return Color.BLACK
        
        val avgRed = colors.map { Color.red(it) }.average().toInt()
        val avgGreen = colors.map { Color.green(it) }.average().toInt()
        val avgBlue = colors.map { Color.blue(it) }.average().toInt()
        
        return Color.rgb(avgRed, avgGreen, avgBlue)
    }
}