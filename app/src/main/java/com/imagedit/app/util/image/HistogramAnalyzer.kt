package com.imagedit.app.util.image

import android.graphics.Bitmap
import android.graphics.Color
import com.imagedit.app.domain.model.*
import kotlin.math.*
import javax.inject.Inject

/**
 * Analyzes image characteristics using histogram analysis for intelligent enhancement decisions.
 * Implements exposure analysis, color balance detection, dynamic range analysis, and skin tone detection.
 */
class HistogramAnalyzer @Inject constructor() {
    
    companion object {
        private const val HISTOGRAM_SIZE = 256
        private const val SHADOW_THRESHOLD = 16
        private const val HIGHLIGHT_THRESHOLD = 240
        private const val MIDTONE_START = 64
        private const val MIDTONE_END = 192
        private const val CLIPPING_THRESHOLD = 0.05f
        
        // Skin tone HSV ranges (adaptive thresholds)
        private const val SKIN_HUE_MIN = 0f
        private const val SKIN_HUE_MAX = 50f
        private const val SKIN_SATURATION_MIN = 0.2f
        private const val SKIN_SATURATION_MAX = 0.8f
        private const val SKIN_VALUE_MIN = 0.3f
        private const val SKIN_VALUE_MAX = 0.95f
    }
    
    /**
     * Analyzes exposure characteristics using histogram peak detection.
     */
    fun analyzeExposure(bitmap: Bitmap): ExposureAnalysis {
        val luminanceHistogram = calculateLuminanceHistogram(bitmap)
        val totalPixels = bitmap.width * bitmap.height
        
        // Calculate percentages for different brightness ranges
        val shadowPixels = (0 until SHADOW_THRESHOLD).sumOf { luminanceHistogram[it] }
        val highlightPixels = (HIGHLIGHT_THRESHOLD until HISTOGRAM_SIZE).sumOf { luminanceHistogram[it] }
        val midtonePixels = (MIDTONE_START..MIDTONE_END).sumOf { luminanceHistogram[it] }
        
        val shadowPercentage = shadowPixels.toFloat() / totalPixels
        val highlightPercentage = highlightPixels.toFloat() / totalPixels
        val midtonePercentage = midtonePixels.toFloat() / totalPixels
        
        // Check for clipping
        val shadowsClipped = luminanceHistogram[0].toFloat() / totalPixels > CLIPPING_THRESHOLD
        val highlightsClipped = luminanceHistogram[255].toFloat() / totalPixels > CLIPPING_THRESHOLD
        
        // Find histogram peak
        val histogramPeak = luminanceHistogram.indices.maxByOrNull { luminanceHistogram[it] } ?: 128
        
        // Calculate exposure level based on histogram distribution
        val exposureLevel = calculateExposureLevel(luminanceHistogram, totalPixels)
        
        // Suggest correction based on exposure analysis
        val suggestedCorrection = calculateExposureCorrection(exposureLevel, shadowsClipped, highlightsClipped)
        
        return ExposureAnalysis(
            exposureLevel = exposureLevel,
            shadowPercentage = shadowPercentage,
            highlightPercentage = highlightPercentage,
            midtonePercentage = midtonePercentage,
            shadowsClipped = shadowsClipped,
            highlightsClipped = highlightsClipped,
            histogramPeak = histogramPeak,
            suggestedCorrection = suggestedCorrection
        )
    }
    
    /**
     * Analyzes color balance using gray world and max RGB algorithms.
     */
    fun analyzeColorBalance(bitmap: Bitmap): ColorBalanceAnalysis {
        val colorStats = calculateColorChannelStats(bitmap)
        
        // Gray world white point estimation
        val grayWorldWhitePoint = calculateGrayWorldWhitePoint(colorStats)
        
        // Max RGB white point estimation
        val maxRgbWhitePoint = calculateMaxRgbWhitePoint(colorStats)
        
        // Calculate color biases
        val redBias = ((colorStats.redMean - colorStats.grayMean) / 255.0).toFloat()
        val greenBias = ((colorStats.greenMean - colorStats.grayMean) / 255.0).toFloat()
        val blueBias = ((colorStats.blueMean - colorStats.grayMean) / 255.0).toFloat()
        
        // Estimate color temperature and tint
        val colorTemperature = estimateColorTemperature(redBias, blueBias)
        val tint = estimateTint(greenBias, redBias, blueBias)
        
        // Calculate suggested correction
        val suggestedCorrection = calculateColorCorrection(grayWorldWhitePoint, maxRgbWhitePoint)
        
        return ColorBalanceAnalysis(
            redBias = redBias.coerceIn(-1f, 1f),
            greenBias = greenBias.coerceIn(-1f, 1f),
            blueBias = blueBias.coerceIn(-1f, 1f),
            grayWorldWhitePoint = grayWorldWhitePoint,
            maxRgbWhitePoint = maxRgbWhitePoint,
            colorTemperature = colorTemperature,
            tint = tint.coerceIn(-1f, 1f),
            suggestedCorrection = suggestedCorrection
        )
    }
    
    /**
     * Analyzes dynamic range using contrast metrics.
     */
    fun analyzeDynamicRange(bitmap: Bitmap): DynamicRangeAnalysis {
        val luminanceValues = calculateLuminanceValues(bitmap)
        
        // Calculate standard deviation
        val mean = luminanceValues.average().toFloat()
        val variance = luminanceValues.map { (it - mean).pow(2) }.average()
        val stdDev = sqrt(variance).toFloat()
        
        // Calculate percentile range (5th to 95th percentile)
        val sortedValues = luminanceValues.sorted()
        val p5Index = (sortedValues.size * 0.05).toInt()
        val p95Index = (sortedValues.size * 0.95).toInt()
        val percentileRange = (sortedValues[p95Index] - sortedValues[p5Index]).toFloat()
        
        // Calculate Michelson contrast
        val maxLuminance = sortedValues.last().toFloat()
        val minLuminance = sortedValues.first().toFloat()
        val michelsonContrast = if (maxLuminance + minLuminance > 0) {
            (maxLuminance - minLuminance) / (maxLuminance + minLuminance)
        } else 0f
        
        // Calculate RMS contrast
        val rmsContrast = stdDev / 255f
        
        // Overall contrast level
        val contrastLevel = (michelsonContrast + rmsContrast) / 2f
        
        // Determine contrast categories
        val isLowContrast = contrastLevel < 0.3f
        val isHighContrast = contrastLevel > 0.7f
        
        // Suggest adjustments
        val suggestedContrastAdjustment = when {
            isLowContrast -> 0.3f
            isHighContrast -> -0.2f
            else -> 0f
        }
        
        val suggestedClarityAdjustment = when {
            stdDev < 30f -> 0.4f
            stdDev > 80f -> -0.2f
            else -> 0f
        }
        
        return DynamicRangeAnalysis(
            contrastLevel = contrastLevel,
            brightnessStdDev = stdDev,
            percentileRange = percentileRange,
            michelsonContrast = michelsonContrast,
            rmsContrast = rmsContrast,
            isLowContrast = isLowContrast,
            isHighContrast = isHighContrast,
            suggestedContrastAdjustment = suggestedContrastAdjustment,
            suggestedClarityAdjustment = suggestedClarityAdjustment
        )
    }
    
    /**
     * Detects skin tones using HSV-based analysis with adaptive thresholds.
     */
    fun detectSkinTones(bitmap: Bitmap): SkinToneAnalysis {
        val skinPixels = mutableListOf<SkinPixel>()
        val totalPixels = bitmap.width * bitmap.height
        
        // Analyze each pixel for skin tone characteristics
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val skinConfidence = calculateSkinConfidence(pixel)
                
                if (skinConfidence > 0.3f) {
                    skinPixels.add(SkinPixel(x, y, pixel, skinConfidence))
                }
            }
        }
        
        val skinPercentage = skinPixels.size.toFloat() / totalPixels
        
        // Calculate average skin characteristics
        val averageHue = if (skinPixels.isNotEmpty()) {
            skinPixels.map { getHue(it.color) }.average().toFloat()
        } else 0f
        
        val averageSaturation = if (skinPixels.isNotEmpty()) {
            skinPixels.map { getSaturation(it.color) }.average().toFloat()
        } else 0f
        
        val averageBrightness = if (skinPixels.isNotEmpty()) {
            skinPixels.map { getBrightness(it.color) }.average().toFloat()
        } else 0f
        
        // Calculate overall confidence
        val confidence = if (skinPixels.isNotEmpty()) {
            skinPixels.map { it.confidence }.average().toFloat()
        } else 0f
        
        // Detect skin regions (simplified clustering)
        val skinRegions = detectSkinRegions(skinPixels, bitmap.width, bitmap.height)
        
        val portraitRecommended = skinPercentage > 0.15f && confidence > 0.5f
        
        return SkinToneAnalysis(
            skinPercentage = skinPercentage,
            confidence = confidence,
            skinRegions = skinRegions,
            averageSkinHue = averageHue,
            averageSkinSaturation = averageSaturation,
            averageSkinBrightness = averageBrightness,
            portraitRecommended = portraitRecommended
        )
    }
    
    // Private helper methods
    
    private fun calculateLuminanceHistogram(bitmap: Bitmap): IntArray {
        val histogram = IntArray(HISTOGRAM_SIZE)
        
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val luminance = calculateLuminance(pixel)
                histogram[luminance.coerceIn(0, 255)]++
            }
        }
        
        return histogram
    }
    
    private fun calculateLuminance(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }
    
    private fun calculateLuminanceValues(bitmap: Bitmap): List<Int> {
        val values = mutableListOf<Int>()
        
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                values.add(calculateLuminance(pixel))
            }
        }
        
        return values
    }
    
    private fun calculateExposureLevel(histogram: IntArray, totalPixels: Int): Float {
        var weightedSum = 0.0
        for (i in histogram.indices) {
            weightedSum += i * histogram[i]
        }
        val averageBrightness = weightedSum / totalPixels
        
        // Convert to exposure stops (-2 to +2)
        return ((averageBrightness - 128) / 64).toFloat().coerceIn(-2f, 2f)
    }
    
    private fun calculateExposureCorrection(exposureLevel: Float, shadowsClipped: Boolean, highlightsClipped: Boolean): Float {
        return when {
            highlightsClipped -> -0.5f
            shadowsClipped -> 0.5f
            exposureLevel < -0.5f -> 0.3f
            exposureLevel > 0.5f -> -0.3f
            else -> 0f
        }
    }
    
    private data class ColorChannelStats(
        val redMean: Double,
        val greenMean: Double,
        val blueMean: Double,
        val grayMean: Double,
        val redMax: Int,
        val greenMax: Int,
        val blueMax: Int
    )
    
    private fun calculateColorChannelStats(bitmap: Bitmap): ColorChannelStats {
        var redSum = 0.0
        var greenSum = 0.0
        var blueSum = 0.0
        var redMax = 0
        var greenMax = 0
        var blueMax = 0
        val totalPixels = bitmap.width * bitmap.height
        
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                
                redSum += r
                greenSum += g
                blueSum += b
                
                redMax = maxOf(redMax, r)
                greenMax = maxOf(greenMax, g)
                blueMax = maxOf(blueMax, b)
            }
        }
        
        val redMean = redSum / totalPixels
        val greenMean = greenSum / totalPixels
        val blueMean = blueSum / totalPixels
        val grayMean = (redMean + greenMean + blueMean) / 3.0
        
        return ColorChannelStats(redMean, greenMean, blueMean, grayMean, redMax, greenMax, blueMax)
    }
    
    private fun calculateGrayWorldWhitePoint(stats: ColorChannelStats): WhitePoint {
        val redMultiplier = (stats.grayMean / stats.redMean).toFloat()
        val greenMultiplier = (stats.grayMean / stats.greenMean).toFloat()
        val blueMultiplier = (stats.grayMean / stats.blueMean).toFloat()
        
        // Confidence based on how close the channels are to gray
        val redDeviation = abs(stats.redMean - stats.grayMean) / 255.0
        val greenDeviation = abs(stats.greenMean - stats.grayMean) / 255.0
        val blueDeviation = abs(stats.blueMean - stats.grayMean) / 255.0
        val avgDeviation = (redDeviation + greenDeviation + blueDeviation) / 3.0
        val confidence = (1.0 - avgDeviation).toFloat().coerceIn(0f, 1f)
        
        return WhitePoint(redMultiplier, greenMultiplier, blueMultiplier, confidence)
    }
    
    private fun calculateMaxRgbWhitePoint(stats: ColorChannelStats): WhitePoint {
        val maxChannel = maxOf(stats.redMax, stats.greenMax, stats.blueMax)
        
        val redMultiplier = maxChannel.toFloat() / stats.redMax
        val greenMultiplier = maxChannel.toFloat() / stats.greenMax
        val blueMultiplier = maxChannel.toFloat() / stats.blueMax
        
        // Confidence based on how close max values are to each other
        val redDiff = abs(stats.redMax - maxChannel) / 255f
        val greenDiff = abs(stats.greenMax - maxChannel) / 255f
        val blueDiff = abs(stats.blueMax - maxChannel) / 255f
        val avgDiff = (redDiff + greenDiff + blueDiff) / 3f
        val confidence = (1f - avgDiff).coerceIn(0f, 1f)
        
        return WhitePoint(redMultiplier, greenMultiplier, blueMultiplier, confidence)
    }
    
    private fun estimateColorTemperature(redBias: Float, blueBias: Float): Float {
        // Simplified color temperature estimation (in Kelvin)
        val colorBias = redBias - blueBias
        return when {
            colorBias > 0.1f -> 3000f + (colorBias * 2000f) // Warm
            colorBias < -0.1f -> 6500f + (abs(colorBias) * 3000f) // Cool
            else -> 5500f // Neutral daylight
        }.coerceIn(2000f, 10000f)
    }
    
    private fun estimateTint(greenBias: Float, redBias: Float, blueBias: Float): Float {
        val magentaGreenBias = greenBias - ((redBias + blueBias) / 2f)
        return -magentaGreenBias // Negative for green, positive for magenta
    }
    
    private fun calculateColorCorrection(grayWorld: WhitePoint, maxRgb: WhitePoint): ColorCorrection {
        // Blend the two methods based on their confidence
        val grayWeight = grayWorld.confidence
        val maxWeight = maxRgb.confidence
        val totalWeight = grayWeight + maxWeight
        
        return if (totalWeight > 0) {
            ColorCorrection(
                redCorrection = (grayWorld.red * grayWeight + maxRgb.red * maxWeight) / totalWeight,
                greenCorrection = (grayWorld.green * grayWeight + maxRgb.green * maxWeight) / totalWeight,
                blueCorrection = (grayWorld.blue * grayWeight + maxRgb.blue * maxWeight) / totalWeight
            )
        } else {
            ColorCorrection(1f, 1f, 1f)
        }
    }
    
    private fun calculateSkinConfidence(pixel: Int): Float {
        val hsv = FloatArray(3)
        Color.colorToHSV(pixel, hsv)
        
        val hue = hsv[0]
        val saturation = hsv[1]
        val value = hsv[2]
        
        // Check if pixel falls within skin tone ranges
        val hueMatch = (hue >= SKIN_HUE_MIN && hue <= SKIN_HUE_MAX) || 
                      (hue >= 340f && hue <= 360f) // Wrap around for red hues
        val saturationMatch = saturation >= SKIN_SATURATION_MIN && saturation <= SKIN_SATURATION_MAX
        val valueMatch = value >= SKIN_VALUE_MIN && value <= SKIN_VALUE_MAX
        
        if (!hueMatch || !saturationMatch || !valueMatch) return 0f
        
        // Calculate confidence based on how well it matches ideal skin tone
        val hueConfidence = when {
            hue <= 25f -> 1f - (hue / 25f) * 0.3f
            hue <= 50f -> 0.7f - ((hue - 25f) / 25f) * 0.4f
            hue >= 340f -> 1f - ((360f - hue) / 20f) * 0.3f
            else -> 0f
        }
        
        val saturationConfidence = 1f - abs(saturation - 0.5f) * 2f
        val valueConfidence = 1f - abs(value - 0.65f) * 2f
        
        return (hueConfidence * saturationConfidence * valueConfidence).coerceIn(0f, 1f)
    }
    
    private fun getHue(color: Int): Float {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        return hsv[0]
    }
    
    private fun getSaturation(color: Int): Float {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        return hsv[1]
    }
    
    private fun getBrightness(color: Int): Float {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        return hsv[2]
    }
    
    private data class SkinPixel(
        val x: Int,
        val y: Int,
        val color: Int,
        val confidence: Float
    )
    
    private fun detectSkinRegions(skinPixels: List<SkinPixel>, width: Int, height: Int): List<SkinRegion> {
        // Simplified region detection - group nearby skin pixels
        val regions = mutableListOf<SkinRegion>()
        val processed = mutableSetOf<SkinPixel>()
        
        for (pixel in skinPixels) {
            if (pixel in processed) continue
            
            val regionPixels = mutableListOf<SkinPixel>()
            val queue = mutableListOf(pixel)
            
            while (queue.isNotEmpty()) {
                val current = queue.removeAt(0)
                if (current in processed) continue
                
                processed.add(current)
                regionPixels.add(current)
                
                // Find nearby skin pixels (simplified 8-connectivity)
                for (other in skinPixels) {
                    if (other !in processed && 
                        abs(other.x - current.x) <= 1 && 
                        abs(other.y - current.y) <= 1) {
                        queue.add(other)
                    }
                }
            }
            
            if (regionPixels.size >= 10) { // Minimum region size
                val minX = regionPixels.minOf { it.x }
                val maxX = regionPixels.maxOf { it.x }
                val minY = regionPixels.minOf { it.y }
                val maxY = regionPixels.maxOf { it.y }
                
                val bounds = android.graphics.Rect(minX, minY, maxX, maxY)
                val avgConfidence = regionPixels.map { it.confidence }.average().toFloat()
                val avgColor = blendColors(regionPixels.map { it.color })
                
                regions.add(SkinRegion(bounds, avgConfidence, regionPixels.size, avgColor))
            }
        }
        
        return regions
    }
    
    private fun blendColors(colors: List<Int>): Int {
        if (colors.isEmpty()) return Color.BLACK
        
        val avgRed = colors.map { Color.red(it) }.average().toInt()
        val avgGreen = colors.map { Color.green(it) }.average().toInt()
        val avgBlue = colors.map { Color.blue(it) }.average().toInt()
        
        return Color.rgb(avgRed, avgGreen, avgBlue)
    }
}