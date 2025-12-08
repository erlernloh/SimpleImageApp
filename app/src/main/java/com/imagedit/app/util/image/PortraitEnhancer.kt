package com.imagedit.app.util.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import com.imagedit.app.domain.model.SkinRegion
import com.imagedit.app.domain.model.SkinToneAnalysis
import com.imagedit.app.domain.model.LightingConditions
import com.imagedit.app.domain.model.LightingType
import com.imagedit.app.domain.model.ProcessingMode
import com.imagedit.app.util.PerformanceManager
import kotlin.math.*

/**
 * Advanced portrait enhancement with sophisticated algorithms for professional results.
 * Features advanced eye detection, lighting-based skin tone correction, selective sharpening,
 * and automatic blemish detection with healing suggestions.
 */
class PortraitEnhancer {
    
    private val skinToneDetector = SkinToneDetector()
    private lateinit var performanceManager: PerformanceManager
    
    /**
     * Sets the PerformanceManager for adaptive quality processing
     */
    fun setPerformanceManager(performanceManager: PerformanceManager) {
        this.performanceManager = performanceManager
    }
    
    companion object {
        // Advanced bilateral filter parameters
        private const val BILATERAL_SIGMA_COLOR = 80f
        private const val BILATERAL_SIGMA_SPACE = 80f
        private const val BILATERAL_KERNEL_SIZE = 15
        
        // Advanced eye detection parameters
        private const val EYE_DETECTION_THRESHOLD = 0.55f
        private const val EYE_ENHANCEMENT_RADIUS = 35
        private const val PUPIL_DETECTION_THRESHOLD = 0.7f
        private const val IRIS_ENHANCEMENT_STRENGTH = 0.4f
        
        // Advanced skin tone correction parameters
        private const val SKIN_TONE_CORRECTION_STRENGTH = 0.35f
        private const val WARMTH_ADJUSTMENT = 0.18f
        private const val LIGHTING_ADAPTATION_STRENGTH = 0.25f
        
        // Selective sharpening parameters
        private const val FACIAL_SHARPENING_STRENGTH = 0.3f
        private const val EYE_SHARPENING_STRENGTH = 0.5f
        private const val UNSHARP_MASK_RADIUS = 2f
        private const val UNSHARP_MASK_THRESHOLD = 10f
        
        // Blemish detection parameters
        private const val BLEMISH_DETECTION_THRESHOLD = 0.6f
        private const val MIN_BLEMISH_SIZE = 3
        private const val MAX_BLEMISH_SIZE = 20
        
        // Processing optimization
        private const val MAX_PROCESSING_SIZE = 1024
    }
    
    /**
     * Detects skin areas in the image with advanced color analysis.
     */
    fun detectSkinAreas(bitmap: Bitmap): List<SkinRegion> {
        val skinAnalysis = skinToneDetector.detectSkinTones(bitmap)
        return skinAnalysis.skinRegions.filter { it.confidence > 0.4f }
    }
    
    /**
     * Applies edge-preserving skin smoothing to detected skin regions only with adaptive quality.
     */
    fun applySkinSmoothing(
        bitmap: Bitmap, 
        regions: List<SkinRegion>, 
        intensity: Float
    ): Bitmap {
        if (regions.isEmpty() || intensity <= 0f) return bitmap.copy(bitmap.config, false)
        
        // Get adaptive quality mode if PerformanceManager is available
        val qualityMode = if (::performanceManager.isInitialized) {
            performanceManager.getPortraitQualityMode()
        } else {
            ProcessingMode.ADVANCED // Default to full quality if no PerformanceManager
        }
        
        // Create working bitmap at optimal size for processing
        val workingBitmap = prepareWorkingBitmap(bitmap)
        val scaleFactor = workingBitmap.width.toFloat() / bitmap.width.toFloat()
        
        // Scale regions to match working bitmap
        val scaledRegions = regions.map { region ->
            val scaledBounds = Rect(
                (region.bounds.left * scaleFactor).toInt(),
                (region.bounds.top * scaleFactor).toInt(),
                (region.bounds.right * scaleFactor).toInt(),
                (region.bounds.bottom * scaleFactor).toInt()
            )
            region.copy(bounds = scaledBounds)
        }
        
        // Calculate combined bounding box with margin for ROI processing
        val combinedBounds = calculateCombinedBounds(scaledRegions)
        
        // Apply adaptive skin smoothing based on quality mode
        val smoothedBitmap = when (qualityMode) {
            ProcessingMode.LITE -> {
                // Lite mode: Use simple Gaussian blur instead of bilateral filter
                applyGaussianBlurToRegion(workingBitmap, combinedBounds, intensity)
            }
            ProcessingMode.MEDIUM -> {
                // Medium mode: Use reduced kernel size bilateral filter
                applyBilateralFilterToRegion(workingBitmap, combinedBounds, intensity, kernelSize = 5)
            }
            ProcessingMode.ADVANCED -> {
                // Advanced mode: Use full bilateral filter with current parameters
                applyBilateralFilterToRegion(workingBitmap, combinedBounds, intensity, kernelSize = 15)
            }
        }
        
        // Create skin mask for selective blending
        val skinMask = createSkinMask(workingBitmap, scaledRegions)
        
        // Blend smoothed result with original using skin mask
        val result = blendWithMask(workingBitmap, smoothedBitmap, skinMask, intensity)
        
        // Scale back to original size if needed
        return if (scaleFactor != 1f) {
            Bitmap.createScaledBitmap(result, bitmap.width, bitmap.height, true)
        } else {
            result
        }
    }
    
    /**
     * Advanced eye detection and enhancement with pupil and iris analysis.
     */
    fun enhanceEyeAreas(bitmap: Bitmap, intensity: Float): Bitmap {
        if (intensity <= 0f) return bitmap.copy(bitmap.config, false)
        
        val workingBitmap = prepareWorkingBitmap(bitmap)
        val scaleFactor = workingBitmap.width.toFloat() / bitmap.width.toFloat()
        
        // Advanced eye detection with pupil and iris analysis
        val eyeRegions = detectAdvancedEyeRegions(workingBitmap)
        
        if (eyeRegions.isEmpty()) {
            return if (scaleFactor != 1f) {
                Bitmap.createScaledBitmap(workingBitmap, bitmap.width, bitmap.height, true)
            } else {
                workingBitmap
            }
        }
        
        // Apply comprehensive eye enhancement
        val enhancedBitmap = applyAdvancedEyeEnhancement(workingBitmap, eyeRegions, intensity)
        
        // Scale back to original size if needed
        return if (scaleFactor != 1f) {
            Bitmap.createScaledBitmap(enhancedBitmap, bitmap.width, bitmap.height, true)
        } else {
            enhancedBitmap
        }
    }

    /**
     * Detects eyes using advanced pattern recognition and color analysis
     */
    private fun detectAdvancedEyeRegions(bitmap: Bitmap): List<EyeRegion> {
        val eyeRegions = mutableListOf<EyeRegion>()
        val width = bitmap.width
        val height = bitmap.height
        
        // Focus on upper 60% of image for eye detection
        val searchHeight = (height * 0.6f).toInt()
        val minEyeSize = minOf(width, height) / 25
        val maxEyeSize = minOf(width, height) / 6
        
        // Use adaptive step size based on image resolution
        val stepSize = maxOf(minEyeSize / 3, 2)
        
        for (y in minEyeSize until searchHeight - minEyeSize step stepSize) {
            for (x in minEyeSize until width - minEyeSize step stepSize) {
                val eyeAnalysis = analyzeEyeCandidate(bitmap, x, y, minEyeSize, maxEyeSize)
                
                if (eyeAnalysis.confidence > EYE_DETECTION_THRESHOLD) {
                    val eyeRegion = EyeRegion(
                        bounds = Rect(
                            x - eyeAnalysis.radius,
                            y - eyeAnalysis.radius,
                            x + eyeAnalysis.radius,
                            y + eyeAnalysis.radius
                        ),
                        pupilCenter = eyeAnalysis.pupilCenter,
                        irisRadius = eyeAnalysis.irisRadius,
                        confidence = eyeAnalysis.confidence,
                        averageBrightness = eyeAnalysis.averageBrightness
                    )
                    
                    // Ensure bounds are within image
                    eyeRegion.bounds.intersect(0, 0, width, height)
                    
                    if (!overlapsExistingEyeRegion(eyeRegion, eyeRegions)) {
                        eyeRegions.add(eyeRegion)
                    }
                }
            }
        }
        
        return eyeRegions.sortedByDescending { it.confidence }.take(2) // Maximum 2 eyes
    }

    /**
     * Comprehensive eye candidate analysis
     */
    private fun analyzeEyeCandidate(
        bitmap: Bitmap, 
        centerX: Int, 
        centerY: Int, 
        minRadius: Int, 
        maxRadius: Int
    ): EyeCandidateAnalysis {
        var bestRadius = minRadius
        var bestConfidence = 0f
        var bestPupilCenter = Pair(centerX, centerY)
        var bestIrisRadius = minRadius / 2
        var bestBrightness = 0f
        
        // Test different radii to find optimal eye size
        for (radius in minRadius..maxRadius step 2) {
            val analysis = analyzeEyeAtRadius(bitmap, centerX, centerY, radius)
            
            if (analysis.confidence > bestConfidence) {
                bestConfidence = analysis.confidence
                bestRadius = radius
                bestPupilCenter = analysis.pupilCenter
                bestIrisRadius = analysis.irisRadius
                bestBrightness = analysis.averageBrightness
            }
        }
        
        return EyeCandidateAnalysis(
            radius = bestRadius,
            confidence = bestConfidence,
            pupilCenter = bestPupilCenter,
            irisRadius = bestIrisRadius,
            averageBrightness = bestBrightness
        )
    }

    /**
     * Analyzes eye characteristics at a specific radius
     */
    private fun analyzeEyeAtRadius(
        bitmap: Bitmap, 
        centerX: Int, 
        centerY: Int, 
        radius: Int
    ): EyeCandidateAnalysis {
        val brightnessMap = mutableMapOf<Pair<Int, Int>, Float>()
        var totalBrightness = 0f
        var pixelCount = 0
        
        // Sample pixels in circular region
        for (y in (centerY - radius)..(centerY + radius)) {
            for (x in (centerX - radius)..(centerX + radius)) {
                if (x >= 0 && x < bitmap.width && y >= 0 && y < bitmap.height) {
                    val distance = sqrt(((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY)).toFloat())
                    
                    if (distance <= radius) {
                        val pixel = bitmap.getPixel(x, y)
                        val brightness = calculatePixelBrightness(pixel)
                        
                        brightnessMap[Pair(x, y)] = brightness
                        totalBrightness += brightness
                        pixelCount++
                    }
                }
            }
        }
        
        if (pixelCount == 0) {
            return EyeCandidateAnalysis(radius, 0f, Pair(centerX, centerY), radius / 2, 0f)
        }
        
        val averageBrightness = totalBrightness / pixelCount
        
        // Find pupil (darkest region)
        val pupilCenter = findPupilCenter(brightnessMap, centerX, centerY, radius / 3)
        
        // Analyze iris region
        val irisRadius = analyzeIrisRegion(brightnessMap, pupilCenter, radius)
        
        // Calculate eye confidence based on multiple factors
        val confidence = calculateEyeConfidence(
            brightnessMap, 
            pupilCenter, 
            irisRadius, 
            averageBrightness,
            radius
        )
        
        return EyeCandidateAnalysis(
            radius = radius,
            confidence = confidence,
            pupilCenter = pupilCenter,
            irisRadius = irisRadius,
            averageBrightness = averageBrightness
        )
    }

    /**
     * Finds the pupil center by locating the darkest circular region
     */
    private fun findPupilCenter(
        brightnessMap: Map<Pair<Int, Int>, Float>,
        centerX: Int,
        centerY: Int,
        searchRadius: Int
    ): Pair<Int, Int> {
        var darkestPoint = Pair(centerX, centerY)
        var darkestBrightness = Float.MAX_VALUE
        
        for (y in (centerY - searchRadius)..(centerY + searchRadius)) {
            for (x in (centerX - searchRadius)..(centerX + searchRadius)) {
                val point = Pair(x, y)
                val brightness = brightnessMap[point]
                
                if (brightness != null && brightness < darkestBrightness) {
                    darkestBrightness = brightness
                    darkestPoint = point
                }
            }
        }
        
        return darkestPoint
    }

    /**
     * Analyzes iris region characteristics
     */
    private fun analyzeIrisRegion(
        brightnessMap: Map<Pair<Int, Int>, Float>,
        pupilCenter: Pair<Int, Int>,
        maxRadius: Int
    ): Int {
        val pupilX = pupilCenter.first
        val pupilY = pupilCenter.second
        
        // Find iris boundary by detecting brightness gradient
        var irisRadius = maxRadius / 4
        var maxGradient = 0f
        
        for (radius in 3..maxRadius / 2) {
            var gradientSum = 0f
            var sampleCount = 0
            
            // Sample points on circle at this radius
            for (angle in 0 until 360 step 15) {
                val x = pupilX + (radius * cos(Math.toRadians(angle.toDouble()))).toInt()
                val y = pupilY + (radius * sin(Math.toRadians(angle.toDouble()))).toInt()
                
                val innerX = pupilX + ((radius - 2) * cos(Math.toRadians(angle.toDouble()))).toInt()
                val innerY = pupilY + ((radius - 2) * sin(Math.toRadians(angle.toDouble()))).toInt()
                
                val outerBrightness = brightnessMap[Pair(x, y)]
                val innerBrightness = brightnessMap[Pair(innerX, innerY)]
                
                if (outerBrightness != null && innerBrightness != null) {
                    gradientSum += abs(outerBrightness - innerBrightness)
                    sampleCount++
                }
            }
            
            if (sampleCount > 0) {
                val avgGradient = gradientSum / sampleCount
                if (avgGradient > maxGradient) {
                    maxGradient = avgGradient
                    irisRadius = radius
                }
            }
        }
        
        return irisRadius
    }

    /**
     * Calculates eye detection confidence using multiple criteria
     */
    private fun calculateEyeConfidence(
        brightnessMap: Map<Pair<Int, Int>, Float>,
        pupilCenter: Pair<Int, Int>,
        irisRadius: Int,
        averageBrightness: Float,
        totalRadius: Int
    ): Float {
        val pupilX = pupilCenter.first
        val pupilY = pupilCenter.second
        
        // Calculate pupil darkness score
        var pupilBrightness = 0f
        var pupilPixels = 0
        val pupilRadius = irisRadius / 3
        
        for (y in (pupilY - pupilRadius)..(pupilY + pupilRadius)) {
            for (x in (pupilX - pupilRadius)..(pupilX + pupilRadius)) {
                val distance = sqrt(((x - pupilX) * (x - pupilX) + (y - pupilY) * (y - pupilY)).toFloat())
                if (distance <= pupilRadius) {
                    brightnessMap[Pair(x, y)]?.let { brightness ->
                        pupilBrightness += brightness
                        pupilPixels++
                    }
                }
            }
        }
        
        val avgPupilBrightness = if (pupilPixels > 0) pupilBrightness / pupilPixels else averageBrightness
        
        // Calculate iris contrast score
        var irisBrightness = 0f
        var irisPixels = 0
        
        for (angle in 0 until 360 step 10) {
            val x = pupilX + (irisRadius * cos(Math.toRadians(angle.toDouble()))).toInt()
            val y = pupilY + (irisRadius * sin(Math.toRadians(angle.toDouble()))).toInt()
            
            brightnessMap[Pair(x, y)]?.let { brightness ->
                irisBrightness += brightness
                irisPixels++
            }
        }
        
        val avgIrisBrightness = if (irisPixels > 0) irisBrightness / irisPixels else averageBrightness
        
        // Calculate confidence factors
        val pupilDarknessScore = if (avgPupilBrightness < 80f) 1f else (80f - avgPupilBrightness) / 80f
        val irisContrastScore = (avgIrisBrightness - avgPupilBrightness) / 255f
        val sizeScore = if (irisRadius in 5..totalRadius / 2) 1f else 0.5f
        val brightnessScore = if (averageBrightness in 60f..180f) 1f else 0.6f
        
        // Weighted combination
        return (pupilDarknessScore * 0.4f + 
                irisContrastScore * 0.3f + 
                sizeScore * 0.2f + 
                brightnessScore * 0.1f).coerceIn(0f, 1f)
    }

    /**
     * Calculates pixel brightness using luminance formula
     */
    private fun calculatePixelBrightness(pixel: Int): Float {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return 0.299f * r + 0.587f * g + 0.114f * b
    }
    
    /**
     * Advanced skin tone correction based on lighting conditions and color analysis.
     */
    fun correctSkinTone(bitmap: Bitmap, regions: List<SkinRegion>): Bitmap {
        if (regions.isEmpty()) return bitmap.copy(bitmap.config, false)
        
        val workingBitmap = prepareWorkingBitmap(bitmap)
        val scaleFactor = workingBitmap.width.toFloat() / bitmap.width.toFloat()
        
        // Scale regions to match working bitmap
        val scaledRegions = regions.map { region ->
            val scaledBounds = Rect(
                (region.bounds.left * scaleFactor).toInt(),
                (region.bounds.top * scaleFactor).toInt(),
                (region.bounds.right * scaleFactor).toInt(),
                (region.bounds.bottom * scaleFactor).toInt()
            )
            region.copy(bounds = scaledBounds)
        }
        
        // Advanced lighting analysis for better correction
        val lightingConditions = analyzeLightingConditions(workingBitmap)
        
        // Analyze current skin tone characteristics with lighting context
        val skinAnalysis = analyzeAdvancedSkinToneCharacteristics(workingBitmap, scaledRegions, lightingConditions)
        
        // Calculate lighting-adaptive correction parameters
        val correctionMatrix = calculateAdvancedSkinToneCorrectionMatrix(skinAnalysis, lightingConditions)
        
        // Create skin mask for selective correction
        val skinMask = createSkinMask(workingBitmap, scaledRegions)
        
        // Apply adaptive color correction
        val correctedBitmap = applyAdaptiveColorCorrection(workingBitmap, correctionMatrix, skinMask, lightingConditions)
        
        // Scale back to original size if needed
        return if (scaleFactor != 1f) {
            Bitmap.createScaledBitmap(correctedBitmap, bitmap.width, bitmap.height, true)
        } else {
            correctedBitmap
        }
    }

    /**
     * Analyzes lighting conditions for adaptive skin tone correction
     */
    private fun analyzeLightingConditions(bitmap: Bitmap): LightingConditions {
        var totalBrightness = 0f
        var totalColorTemp = 0f
        var pixelCount = 0
        
        // Sample lighting across the image
        val stepSize = maxOf(bitmap.width / 20, bitmap.height / 20, 4)
        
        for (y in 0 until bitmap.height step stepSize) {
            for (x in 0 until bitmap.width step stepSize) {
                val pixel = bitmap.getPixel(x, y)
                val brightness = calculatePixelBrightness(pixel)
                val colorTemp = estimatePixelColorTemperature(pixel)
                
                totalBrightness += brightness
                totalColorTemp += colorTemp
                pixelCount++
            }
        }
        
        val avgBrightness = if (pixelCount > 0) totalBrightness / pixelCount / 255f else 0.5f
        val avgColorTemp = if (pixelCount > 0) totalColorTemp / pixelCount else 5500f
        
        // Determine lighting type
        val lightingType = when {
            avgColorTemp < 3000f -> LightingType.ARTIFICIAL
            avgColorTemp < 4000f -> LightingType.GOLDEN_HOUR
            avgColorTemp > 7000f -> LightingType.BLUE_HOUR
            avgBrightness < 0.3f -> LightingType.LOW_LIGHT
            else -> LightingType.DAYLIGHT
        }
        
        return LightingConditions(
            lightingType = lightingType,
            brightness = avgBrightness,
            contrast = 0.5f,
            colorTemperature = avgColorTemp,
            isNaturalLight = lightingType in listOf(LightingType.DAYLIGHT, LightingType.GOLDEN_HOUR, LightingType.BLUE_HOUR),
            shadowIntensity = if (avgBrightness < 0.3f) 0.7f else 0.3f,
            highlightIntensity = if (avgBrightness > 0.7f) 0.8f else 0.5f
        )
    }

    /**
     * Estimates color temperature of a pixel
     */
    private fun estimatePixelColorTemperature(pixel: Int): Float {
        val r = Color.red(pixel).toFloat()
        val g = Color.green(pixel).toFloat()
        val b = Color.blue(pixel).toFloat()
        
        if (b == 0f) return 5500f
        
        val redBlueRatio = r / b
        
        return when {
            redBlueRatio > 1.5f -> 3000f + (2000f / redBlueRatio)
            redBlueRatio > 1.0f -> 4500f + (1000f * (1.5f - redBlueRatio))
            redBlueRatio > 0.7f -> 5500f + (2000f * (1.0f - redBlueRatio))
            else -> 7500f + (2500f * (0.7f - redBlueRatio))
        }.coerceIn(2500f, 10000f)
    }
    
    /**
     * Applies selective sharpening to facial features while preserving skin smoothness.
     */
    fun applySelectiveSharpening(bitmap: Bitmap, intensity: Float): Bitmap {
        if (intensity <= 0f) return bitmap.copy(bitmap.config, false)
        
        val workingBitmap = prepareWorkingBitmap(bitmap)
        val scaleFactor = workingBitmap.width.toFloat() / bitmap.width.toFloat()
        
        // Detect facial features for selective sharpening
        val eyeRegions = detectAdvancedEyeRegions(workingBitmap)
        val skinRegions = detectSkinAreas(workingBitmap)
        
        // Create sharpening mask (sharpen eyes, preserve skin)
        val sharpeningMask = createSelectiveSharpeningMask(workingBitmap, eyeRegions, skinRegions)
        
        // Apply unsharp mask with selective intensity
        val sharpenedBitmap = applyUnsharpMask(workingBitmap, intensity)
        
        // Blend using selective mask
        val result = blendWithMask(workingBitmap, sharpenedBitmap, sharpeningMask, intensity)
        
        // Scale back to original size if needed
        return if (scaleFactor != 1f) {
            Bitmap.createScaledBitmap(result, bitmap.width, bitmap.height, true)
        } else {
            result
        }
    }

    /**
     * Detects potential blemishes and provides healing suggestions.
     */
    fun detectBlemishes(bitmap: Bitmap): List<BlemishCandidate> {
        val workingBitmap = prepareWorkingBitmap(bitmap)
        val skinRegions = detectSkinAreas(workingBitmap)
        
        if (skinRegions.isEmpty()) return emptyList()
        
        val blemishes = mutableListOf<BlemishCandidate>()
        
        for (region in skinRegions) {
            val regionBlemishes = detectBlemishesInRegion(workingBitmap, region)
            blemishes.addAll(regionBlemishes)
        }
        
        // Filter and sort by confidence
        return blemishes
            .filter { it.confidence > BLEMISH_DETECTION_THRESHOLD }
            .sortedByDescending { it.confidence }
            .take(10) // Limit to top 10 blemishes
    }

    /**
     * Comprehensive portrait enhancement with all advanced features.
     */
    fun enhancePortrait(
        bitmap: Bitmap,
        smoothingIntensity: Float = 0.5f,
        eyeEnhancementIntensity: Float = 0.3f,
        skinToneCorrection: Boolean = true,
        selectiveSharpening: Boolean = true,
        sharpeningIntensity: Float = 0.3f
    ): Bitmap {
        // Detect skin areas
        val skinRegions = detectSkinAreas(bitmap)
        
        if (skinRegions.isEmpty()) {
            // No skin detected, return original
            return bitmap.copy(bitmap.config, false)
        }
        
        var result = bitmap
        
        // Apply skin tone correction first if enabled
        if (skinToneCorrection) {
            result = correctSkinTone(result, skinRegions)
        }
        
        // Apply skin smoothing
        if (smoothingIntensity > 0f) {
            result = applySkinSmoothing(result, skinRegions, smoothingIntensity)
        }
        
        // Apply selective sharpening before eye enhancement
        if (selectiveSharpening && sharpeningIntensity > 0f) {
            result = applySelectiveSharpening(result, sharpeningIntensity)
        }
        
        // Apply eye enhancement
        if (eyeEnhancementIntensity > 0f) {
            result = enhanceEyeAreas(result, eyeEnhancementIntensity)
        }
        
        return result
    }
    
    // Private implementation methods
    
    private fun prepareWorkingBitmap(bitmap: Bitmap): Bitmap {
        val maxDimension = maxOf(bitmap.width, bitmap.height)
        
        return if (maxDimension > MAX_PROCESSING_SIZE) {
            val scale = MAX_PROCESSING_SIZE.toFloat() / maxDimension
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap.copy(bitmap.config, true)
        }
    }
    
    private fun createSkinMask(bitmap: Bitmap, regions: List<SkinRegion>): Bitmap {
        val mask = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(mask)
        val paint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
        }
        
        // Fill regions with white (areas to process)
        for (region in regions) {
            // Create soft-edged mask for natural blending
            val centerX = region.bounds.centerX().toFloat()
            val centerY = region.bounds.centerY().toFloat()
            val radius = maxOf(region.bounds.width(), region.bounds.height()) / 2f
            
            // Apply confidence-based alpha
            paint.alpha = (region.confidence * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(centerX, centerY, radius, paint)
        }
        
        return mask
    }
    
    private fun applyBilateralFilter(bitmap: Bitmap, intensity: Float): Bitmap {
        val result = bitmap.copy(bitmap.config, true)
        val width = bitmap.width
        val height = bitmap.height
        
        val kernelRadius = (BILATERAL_KERNEL_SIZE / 2)
        val sigmaColor = BILATERAL_SIGMA_COLOR * intensity
        val sigmaSpace = BILATERAL_SIGMA_SPACE * intensity
        
        // Extract pixel array once for fast access
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // Pre-calculate spatial weights
        val spatialWeights = Array(BILATERAL_KERNEL_SIZE) { Array(BILATERAL_KERNEL_SIZE) { 0f } }
        for (i in 0 until BILATERAL_KERNEL_SIZE) {
            for (j in 0 until BILATERAL_KERNEL_SIZE) {
                val dx = i - kernelRadius
                val dy = j - kernelRadius
                val spatialDistance = sqrt((dx * dx + dy * dy).toFloat())
                spatialWeights[i][j] = exp(-(spatialDistance * spatialDistance) / (2 * sigmaSpace * sigmaSpace))
            }
        }
        
        // Apply bilateral filter using array-based pixel access
        for (y in kernelRadius until height - kernelRadius) {
            for (x in kernelRadius until width - kernelRadius) {
                val centerIdx = y * width + x
                val centerPixel = pixels[centerIdx]
                val centerR = Color.red(centerPixel)
                val centerG = Color.green(centerPixel)
                val centerB = Color.blue(centerPixel)
                
                var weightSum = 0f
                var filteredR = 0f
                var filteredG = 0f
                var filteredB = 0f
                
                for (ky in 0 until BILATERAL_KERNEL_SIZE) {
                    for (kx in 0 until BILATERAL_KERNEL_SIZE) {
                        val px = x + kx - kernelRadius
                        val py = y + ky - kernelRadius
                        val neighborIdx = py * width + px
                        
                        val neighborPixel = pixels[neighborIdx]
                        val neighborR = Color.red(neighborPixel)
                        val neighborG = Color.green(neighborPixel)
                        val neighborB = Color.blue(neighborPixel)
                        
                        // Calculate color distance
                        val colorDistance = sqrt(
                            ((centerR - neighborR) * (centerR - neighborR) +
                             (centerG - neighborG) * (centerG - neighborG) +
                             (centerB - neighborB) * (centerB - neighborB)).toFloat()
                        )
                        
                        // Calculate color weight
                        val colorWeight = exp(-(colorDistance * colorDistance) / (2 * sigmaColor * sigmaColor))
                        
                        // Combine spatial and color weights
                        val totalWeight = spatialWeights[ky][kx] * colorWeight
                        
                        weightSum += totalWeight
                        filteredR += neighborR * totalWeight
                        filteredG += neighborG * totalWeight
                        filteredB += neighborB * totalWeight
                    }
                }
                
                if (weightSum > 0) {
                    val newR = (filteredR / weightSum).toInt().coerceIn(0, 255)
                    val newG = (filteredG / weightSum).toInt().coerceIn(0, 255)
                    val newB = (filteredB / weightSum).toInt().coerceIn(0, 255)
                    
                    result.setPixel(x, y, Color.rgb(newR, newG, newB))
                }
            }
        }
        
        return result
    }
    
    private fun blendWithMask(original: Bitmap, processed: Bitmap, mask: Bitmap, intensity: Float): Bitmap {
        val result = Bitmap.createBitmap(original.width, original.height, original.config)
        val canvas = Canvas(result)
        
        // Draw original image
        canvas.drawBitmap(original, 0f, 0f, null)
        
        // Create paint for blending
        val paint = Paint().apply {
            alpha = (intensity * 255).toInt().coerceIn(0, 255)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
        }
        
        // Apply mask to processed image and blend
        val maskedProcessed = Bitmap.createBitmap(processed.width, processed.height, processed.config)
        val maskCanvas = Canvas(maskedProcessed)
        
        // Draw processed image
        maskCanvas.drawBitmap(processed, 0f, 0f, null)
        
        // Apply mask
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        maskCanvas.drawBitmap(mask, 0f, 0f, paint)
        
        // Blend with original
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
        paint.alpha = (intensity * 255).toInt().coerceIn(0, 255)
        canvas.drawBitmap(maskedProcessed, 0f, 0f, paint)
        
        return result
    }
    
    private fun detectEyeRegions(bitmap: Bitmap): List<Rect> {
        val eyeRegions = mutableListOf<Rect>()
        val width = bitmap.width
        val height = bitmap.height
        
        // Simple eye detection based on dark circular regions in upper half of image
        val searchHeight = height / 2
        val minEyeSize = minOf(width, height) / 20
        val maxEyeSize = minOf(width, height) / 8
        
        for (y in minEyeSize until searchHeight - minEyeSize step minEyeSize / 2) {
            for (x in minEyeSize until width - minEyeSize step minEyeSize / 2) {
                val eyeScore = calculateEyeScore(bitmap, x, y, minEyeSize)
                
                if (eyeScore > EYE_DETECTION_THRESHOLD) {
                    val eyeRect = Rect(
                        x - EYE_ENHANCEMENT_RADIUS,
                        y - EYE_ENHANCEMENT_RADIUS,
                        x + EYE_ENHANCEMENT_RADIUS,
                        y + EYE_ENHANCEMENT_RADIUS
                    )
                    
                    // Ensure bounds are within image
                    eyeRect.intersect(0, 0, width, height)
                    
                    if (!overlapsExistingRegion(eyeRect, eyeRegions)) {
                        eyeRegions.add(eyeRect)
                    }
                }
            }
        }
        
        return eyeRegions
    }
    
    private fun calculateEyeScore(bitmap: Bitmap, centerX: Int, centerY: Int, radius: Int): Float {
        var darkPixels = 0
        var totalPixels = 0
        var averageBrightness = 0f
        
        for (y in (centerY - radius)..(centerY + radius)) {
            for (x in (centerX - radius)..(centerX + radius)) {
                if (x >= 0 && x < bitmap.width && y >= 0 && y < bitmap.height) {
                    val distance = sqrt(((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY)).toFloat())
                    
                    if (distance <= radius) {
                        val pixel = bitmap.getPixel(x, y)
                        val brightness = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3f
                        
                        averageBrightness += brightness
                        totalPixels++
                        
                        if (brightness < 80) { // Dark threshold for potential eye/pupil area
                            darkPixels++
                        }
                    }
                }
            }
        }
        
        if (totalPixels == 0) return 0f
        
        averageBrightness /= totalPixels
        val darkRatio = darkPixels.toFloat() / totalPixels
        
        // Eye score based on presence of dark center and moderate surrounding brightness
        return if (darkRatio > 0.2f && averageBrightness > 60f && averageBrightness < 180f) {
            darkRatio * (1f - abs(averageBrightness - 120f) / 120f)
        } else {
            0f
        }
    }
    
    private fun overlapsExistingRegion(newRect: Rect, existingRegions: List<Rect>): Boolean {
        return existingRegions.any { existing ->
            Rect.intersects(newRect, existing)
        }
    }
    
    private fun applyEyeEnhancement(bitmap: Bitmap, eyeRegions: List<Rect>, intensity: Float): Bitmap {
        val result = bitmap.copy(bitmap.config, true)
        
        for (region in eyeRegions) {
            // Extract eye region
            val eyeBitmap = Bitmap.createBitmap(
                bitmap,
                region.left,
                region.top,
                region.width(),
                region.height()
            )
            
            // Enhance brightness and contrast
            val enhancedEye = enhanceBrightnessAndContrast(eyeBitmap, intensity)
            
            // Blend back into result
            val canvas = Canvas(result)
            val paint = Paint().apply {
                alpha = (intensity * 255).toInt().coerceIn(0, 255)
            }
            
            canvas.drawBitmap(enhancedEye, region.left.toFloat(), region.top.toFloat(), paint)
        }
        
        return result
    }
    
    private fun enhanceBrightnessAndContrast(bitmap: Bitmap, intensity: Float): Bitmap {
        val result = bitmap.copy(bitmap.config, true)
        
        val brightnessAdjustment = 20f * intensity
        val contrastMultiplier = 1f + (0.3f * intensity)
        
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                
                // Apply brightness and contrast
                val newR = ((r - 128) * contrastMultiplier + 128 + brightnessAdjustment).toInt().coerceIn(0, 255)
                val newG = ((g - 128) * contrastMultiplier + 128 + brightnessAdjustment).toInt().coerceIn(0, 255)
                val newB = ((b - 128) * contrastMultiplier + 128 + brightnessAdjustment).toInt().coerceIn(0, 255)
                
                result.setPixel(x, y, Color.rgb(newR, newG, newB))
            }
        }
        
        return result
    }
    
    // Data classes for advanced eye detection
    private data class EyeRegion(
        val bounds: Rect,
        val pupilCenter: Pair<Int, Int>,
        val irisRadius: Int,
        val confidence: Float,
        val averageBrightness: Float
    )

    private data class EyeCandidateAnalysis(
        val radius: Int,
        val confidence: Float,
        val pupilCenter: Pair<Int, Int>,
        val irisRadius: Int,
        val averageBrightness: Float
    )

    private data class SkinToneCharacteristics(
        val averageHue: Float,
        val averageSaturation: Float,
        val averageBrightness: Float,
        val colorTemperature: Float,
        val lightingType: LightingType = LightingType.MIXED
    )

    data class BlemishCandidate(
        val center: Pair<Int, Int>,
        val radius: Int,
        val confidence: Float,
        val averageBrightness: Float,
        val contrastScore: Float
    )
    
    private fun analyzeSkinToneCharacteristics(bitmap: Bitmap, regions: List<SkinRegion>): SkinToneCharacteristics {
        var totalHue = 0f
        var totalSaturation = 0f
        var totalBrightness = 0f
        var totalPixels = 0
        
        for (region in regions) {
            for (y in region.bounds.top until region.bounds.bottom) {
                for (x in region.bounds.left until region.bounds.right) {
                    if (x >= 0 && x < bitmap.width && y >= 0 && y < bitmap.height) {
                        val pixel = bitmap.getPixel(x, y)
                        val hsv = FloatArray(3)
                        Color.colorToHSV(pixel, hsv)
                        
                        totalHue += hsv[0]
                        totalSaturation += hsv[1]
                        totalBrightness += hsv[2]
                        totalPixels++
                    }
                }
            }
        }
        
        return if (totalPixels > 0) {
            val avgHue = totalHue / totalPixels
            val avgSaturation = totalSaturation / totalPixels
            val avgBrightness = totalBrightness / totalPixels
            
            // Estimate color temperature based on hue
            val colorTemperature = when {
                avgHue < 30f -> 0.2f // Warm
                avgHue < 60f -> 0f   // Neutral
                else -> -0.2f        // Cool
            }
            
            SkinToneCharacteristics(avgHue, avgSaturation, avgBrightness, colorTemperature)
        } else {
            SkinToneCharacteristics(20f, 0.4f, 0.6f, 0f)
        }
    }
    
    private fun calculateSkinToneCorrectionMatrix(characteristics: SkinToneCharacteristics): ColorMatrix {
        val matrix = ColorMatrix()
        
        // Adjust warmth based on detected color temperature
        val warmthAdjustment = -characteristics.colorTemperature * WARMTH_ADJUSTMENT
        
        // Create color correction matrix
        val correctionMatrix = floatArrayOf(
            1f + warmthAdjustment * 0.1f, 0f, 0f, 0f, warmthAdjustment * 10f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f - warmthAdjustment * 0.1f, 0f, -warmthAdjustment * 5f,
            0f, 0f, 0f, 1f, 0f
        )
        
        matrix.set(correctionMatrix)
        
        // Apply saturation adjustment for natural skin tones
        val saturationMatrix = ColorMatrix()
        val saturationAdjustment = 1f + (0.4f - characteristics.averageSaturation) * SKIN_TONE_CORRECTION_STRENGTH
        saturationMatrix.setSaturation(saturationAdjustment.coerceIn(0.8f, 1.2f))
        
        matrix.postConcat(saturationMatrix)
        
        return matrix
    }
    
    private fun applySelectiveColorCorrection(bitmap: Bitmap, colorMatrix: ColorMatrix, mask: Bitmap): Bitmap {
        val result = bitmap.copy(bitmap.config, true)
        val canvas = Canvas(result)
        
        // Create corrected version
        val correctedBitmap = bitmap.copy(bitmap.config, true)
        val correctedCanvas = Canvas(correctedBitmap)
        
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        
        correctedCanvas.drawBitmap(bitmap, 0f, 0f, paint)
        
        // Blend using mask
        return blendWithMask(bitmap, correctedBitmap, mask, SKIN_TONE_CORRECTION_STRENGTH)
    }

    // Advanced methods for new features

    /**
     * Advanced eye enhancement with pupil and iris processing
     */
    private fun applyAdvancedEyeEnhancement(bitmap: Bitmap, eyeRegions: List<EyeRegion>, intensity: Float): Bitmap {
        val result = bitmap.copy(bitmap.config, true)
        
        for (eyeRegion in eyeRegions) {
            // Extract eye region
            val eyeBitmap = Bitmap.createBitmap(
                bitmap,
                eyeRegion.bounds.left,
                eyeRegion.bounds.top,
                eyeRegion.bounds.width(),
                eyeRegion.bounds.height()
            )
            
            // Apply comprehensive eye enhancement
            val enhancedEye = enhanceEyeRegion(eyeBitmap, eyeRegion, intensity)
            
            // Blend back into result
            val canvas = Canvas(result)
            val paint = Paint().apply {
                alpha = (intensity * 255).toInt().coerceIn(0, 255)
            }
            
            canvas.drawBitmap(enhancedEye, eyeRegion.bounds.left.toFloat(), eyeRegion.bounds.top.toFloat(), paint)
        }
        
        return result
    }

    /**
     * Enhances individual eye region with pupil and iris processing
     */
    private fun enhanceEyeRegion(eyeBitmap: Bitmap, eyeRegion: EyeRegion, intensity: Float): Bitmap {
        var result = eyeBitmap.copy(eyeBitmap.config, true)
        
        // Enhance iris area (increase saturation and contrast)
        result = enhanceIrisArea(result, eyeRegion, intensity * IRIS_ENHANCEMENT_STRENGTH)
        
        // Brighten and sharpen the overall eye area
        result = enhanceBrightnessAndContrast(result, intensity)
        
        // Apply selective sharpening to eye details
        result = applyEyeSharpening(result, intensity * EYE_SHARPENING_STRENGTH)
        
        return result
    }

    /**
     * Enhances iris area with selective color and contrast adjustments
     */
    private fun enhanceIrisArea(bitmap: Bitmap, eyeRegion: EyeRegion, intensity: Float): Bitmap {
        val result = bitmap.copy(bitmap.config, true)
        val pupilX = eyeRegion.pupilCenter.first - eyeRegion.bounds.left
        val pupilY = eyeRegion.pupilCenter.second - eyeRegion.bounds.top
        val irisRadius = eyeRegion.irisRadius
        
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val distance = sqrt(((x - pupilX) * (x - pupilX) + (y - pupilY) * (y - pupilY)).toFloat())
                
                // Only enhance iris area (not pupil)
                if (distance > irisRadius / 3 && distance <= irisRadius) {
                    val pixel = bitmap.getPixel(x, y)
                    val hsv = FloatArray(3)
                    Color.colorToHSV(pixel, hsv)
                    
                    // Enhance saturation and value for iris
                    hsv[1] = (hsv[1] * (1f + intensity * 0.3f)).coerceIn(0f, 1f)
                    hsv[2] = (hsv[2] * (1f + intensity * 0.2f)).coerceIn(0f, 1f)
                    
                    val enhancedColor = Color.HSVToColor(hsv)
                    result.setPixel(x, y, enhancedColor)
                }
            }
        }
        
        return result
    }

    /**
     * Applies selective sharpening optimized for eye details
     */
    private fun applyEyeSharpening(bitmap: Bitmap, intensity: Float): Bitmap {
        return applyUnsharpMask(bitmap, intensity, UNSHARP_MASK_RADIUS * 0.5f, UNSHARP_MASK_THRESHOLD * 0.5f)
    }

    /**
     * Checks if eye region overlaps with existing regions
     */
    private fun overlapsExistingEyeRegion(newRegion: EyeRegion, existingRegions: List<EyeRegion>): Boolean {
        return existingRegions.any { existing ->
            Rect.intersects(newRegion.bounds, existing.bounds)
        }
    }

    /**
     * Advanced skin tone characteristics analysis with lighting context
     */
    private fun analyzeAdvancedSkinToneCharacteristics(
        bitmap: Bitmap, 
        regions: List<SkinRegion>,
        lightingConditions: LightingConditions
    ): SkinToneCharacteristics {
        val basicCharacteristics = analyzeSkinToneCharacteristics(bitmap, regions)
        
        return SkinToneCharacteristics(
            averageHue = basicCharacteristics.averageHue,
            averageSaturation = basicCharacteristics.averageSaturation,
            averageBrightness = basicCharacteristics.averageBrightness,
            colorTemperature = basicCharacteristics.colorTemperature,
            lightingType = lightingConditions.lightingType
        )
    }

    /**
     * Advanced skin tone correction matrix with lighting adaptation
     */
    private fun calculateAdvancedSkinToneCorrectionMatrix(
        characteristics: SkinToneCharacteristics,
        lightingConditions: LightingConditions
    ): ColorMatrix {
        val matrix = ColorMatrix()
        
        // Base correction from original method
        val baseMatrix = calculateSkinToneCorrectionMatrix(characteristics)
        matrix.set(baseMatrix)
        
        // Apply lighting-specific adjustments
        val lightingAdjustment = when (characteristics.lightingType) {
            LightingType.ARTIFICIAL -> {
                // Reduce yellow cast from artificial lighting
                ColorMatrix().apply {
                    val warmthReduction = floatArrayOf(
                        1.05f, 0f, 0f, 0f, 0f,
                        0f, 1f, 0f, 0f, 0f,
                        0f, 0f, 0.95f, 0f, 5f,
                        0f, 0f, 0f, 1f, 0f
                    )
                    set(warmthReduction)
                }
            }
            LightingType.GOLDEN_HOUR -> {
                // Enhance warm tones slightly
                ColorMatrix().apply {
                    val warmthEnhancement = floatArrayOf(
                        1.02f, 0f, 0f, 0f, 2f,
                        0f, 1f, 0f, 0f, 0f,
                        0f, 0f, 0.98f, 0f, -2f,
                        0f, 0f, 0f, 1f, 0f
                    )
                    set(warmthEnhancement)
                }
            }
            LightingType.BLUE_HOUR -> {
                // Reduce blue cast
                ColorMatrix().apply {
                    val coolReduction = floatArrayOf(
                        0.98f, 0f, 0f, 0f, 3f,
                        0f, 1f, 0f, 0f, 0f,
                        0f, 0f, 1.02f, 0f, -3f,
                        0f, 0f, 0f, 1f, 0f
                    )
                    set(coolReduction)
                }
            }
            else -> ColorMatrix() // Identity matrix for other lighting types
        }
        
        // Apply lighting adjustment with reduced intensity
        lightingAdjustment.setScale(
            1f + LIGHTING_ADAPTATION_STRENGTH,
            1f + LIGHTING_ADAPTATION_STRENGTH,
            1f + LIGHTING_ADAPTATION_STRENGTH,
            1f
        )
        
        matrix.postConcat(lightingAdjustment)
        
        return matrix
    }

    /**
     * Adaptive color correction that considers lighting conditions
     */
    private fun applyAdaptiveColorCorrection(
        bitmap: Bitmap, 
        colorMatrix: ColorMatrix, 
        mask: Bitmap,
        lightingConditions: LightingConditions
    ): Bitmap {
        // Adjust correction strength based on lighting conditions
        val adaptiveStrength = when (lightingConditions.lightingType) {
            LightingType.ARTIFICIAL -> SKIN_TONE_CORRECTION_STRENGTH * 1.2f
            LightingType.LOW_LIGHT -> SKIN_TONE_CORRECTION_STRENGTH * 0.8f
            else -> SKIN_TONE_CORRECTION_STRENGTH
        }
        
        val result = bitmap.copy(bitmap.config, true)
        val canvas = Canvas(result)
        
        // Create corrected version
        val correctedBitmap = bitmap.copy(bitmap.config, true)
        val correctedCanvas = Canvas(correctedBitmap)
        
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        
        correctedCanvas.drawBitmap(bitmap, 0f, 0f, paint)
        
        // Blend using mask with adaptive strength
        return blendWithMask(bitmap, correctedBitmap, mask, adaptiveStrength)
    }

    /**
     * Creates selective sharpening mask (sharpen eyes, preserve skin)
     */
    private fun createSelectiveSharpeningMask(
        bitmap: Bitmap, 
        eyeRegions: List<EyeRegion>, 
        skinRegions: List<SkinRegion>
    ): Bitmap {
        val mask = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(mask)
        
        // Start with black (no sharpening)
        canvas.drawColor(Color.TRANSPARENT)
        
        val paint = Paint().apply {
            isAntiAlias = true
        }
        
        // Add white areas for eye regions (full sharpening)
        paint.color = Color.WHITE
        for (eyeRegion in eyeRegions) {
            canvas.drawRect(eyeRegion.bounds, paint)
        }
        
        // Add gray areas around skin regions (reduced sharpening)
        paint.color = Color.argb(64, 255, 255, 255) // 25% intensity
        for (skinRegion in skinRegions) {
            // Create border around skin region for subtle sharpening
            val borderRect = Rect(skinRegion.bounds)
            borderRect.inset(-10, -10)
            canvas.drawRect(borderRect, paint)
        }
        
        return mask
    }

    /**
     * Applies unsharp mask for sharpening
     */
    private fun applyUnsharpMask(
        bitmap: Bitmap, 
        intensity: Float, 
        radius: Float = UNSHARP_MASK_RADIUS,
        threshold: Float = UNSHARP_MASK_THRESHOLD
    ): Bitmap {
        val result = bitmap.copy(bitmap.config, true)
        
        // Create blurred version
        val blurred = applyGaussianBlur(bitmap, radius)
        
        // Apply unsharp mask formula: original + intensity * (original - blurred)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val original = bitmap.getPixel(x, y)
                val blurredPixel = blurred.getPixel(x, y)
                
                val origR = Color.red(original)
                val origG = Color.green(original)
                val origB = Color.blue(original)
                
                val blurR = Color.red(blurredPixel)
                val blurG = Color.green(blurredPixel)
                val blurB = Color.blue(blurredPixel)
                
                // Calculate difference
                val diffR = origR - blurR
                val diffG = origG - blurG
                val diffB = origB - blurB
                
                // Apply threshold
                val magnitude = sqrt((diffR * diffR + diffG * diffG + diffB * diffB).toFloat())
                
                if (magnitude > threshold) {
                    val newR = (origR + intensity * diffR).toInt().coerceIn(0, 255)
                    val newG = (origG + intensity * diffG).toInt().coerceIn(0, 255)
                    val newB = (origB + intensity * diffB).toInt().coerceIn(0, 255)
                    
                    result.setPixel(x, y, Color.rgb(newR, newG, newB))
                }
            }
        }
        
        return result
    }

    /**
     * Simple Gaussian blur implementation
     */
    private fun applyGaussianBlur(bitmap: Bitmap, radius: Float): Bitmap {
        val result = bitmap.copy(bitmap.config, true)
        
        // Simplified box blur approximation of Gaussian blur
        val kernelSize = (radius * 2).toInt() + 1
        val kernelRadius = kernelSize / 2
        
        for (y in kernelRadius until bitmap.height - kernelRadius) {
            for (x in kernelRadius until bitmap.width - kernelRadius) {
                var totalR = 0
                var totalG = 0
                var totalB = 0
                var count = 0
                
                for (ky in -kernelRadius..kernelRadius) {
                    for (kx in -kernelRadius..kernelRadius) {
                        val pixel = bitmap.getPixel(x + kx, y + ky)
                        totalR += Color.red(pixel)
                        totalG += Color.green(pixel)
                        totalB += Color.blue(pixel)
                        count++
                    }
                }
                
                val avgR = totalR / count
                val avgG = totalG / count
                val avgB = totalB / count
                
                result.setPixel(x, y, Color.rgb(avgR, avgG, avgB))
            }
        }
        
        return result
    }

    /**
     * Detects blemishes within a skin region
     */
    private fun detectBlemishesInRegion(bitmap: Bitmap, skinRegion: SkinRegion): List<BlemishCandidate> {
        val blemishes = mutableListOf<BlemishCandidate>()
        val bounds = skinRegion.bounds
        
        // Sample points within the skin region
        val stepSize = 3
        
        for (y in bounds.top until bounds.bottom step stepSize) {
            for (x in bounds.left until bounds.right step stepSize) {
                if (x >= 0 && x < bitmap.width && y >= 0 && y < bitmap.height) {
                    val blemishScore = analyzeBlemishCandidate(bitmap, x, y)
                    
                    if (blemishScore.confidence > BLEMISH_DETECTION_THRESHOLD) {
                        blemishes.add(blemishScore)
                    }
                }
            }
        }
        
        // Merge nearby blemishes
        return mergeNearbyBlemishes(blemishes)
    }

    /**
     * Analyzes a point for blemish characteristics
     */
    private fun analyzeBlemishCandidate(bitmap: Bitmap, centerX: Int, centerY: Int): BlemishCandidate {
        val searchRadius = 5
        var totalBrightness = 0f
        var pixelCount = 0
        var contrastSum = 0f
        
        // Analyze local area
        for (y in (centerY - searchRadius)..(centerY + searchRadius)) {
            for (x in (centerX - searchRadius)..(centerX + searchRadius)) {
                if (x >= 0 && x < bitmap.width && y >= 0 && y < bitmap.height) {
                    val distance = sqrt(((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY)).toFloat())
                    
                    if (distance <= searchRadius) {
                        val pixel = bitmap.getPixel(x, y)
                        val brightness = calculatePixelBrightness(pixel)
                        
                        totalBrightness += brightness
                        pixelCount++
                        
                        // Calculate local contrast
                        if (distance > 2) {
                            val centerPixel = bitmap.getPixel(centerX, centerY)
                            val centerBrightness = calculatePixelBrightness(centerPixel)
                            contrastSum += abs(brightness - centerBrightness)
                        }
                    }
                }
            }
        }
        
        val avgBrightness = if (pixelCount > 0) totalBrightness / pixelCount else 0f
        val avgContrast = if (pixelCount > 4) contrastSum / (pixelCount - 4) else 0f
        
        // Calculate blemish confidence
        val confidence = calculateBlemishConfidence(avgBrightness, avgContrast, searchRadius)
        
        return BlemishCandidate(
            center = Pair(centerX, centerY),
            radius = searchRadius,
            confidence = confidence,
            averageBrightness = avgBrightness,
            contrastScore = avgContrast
        )
    }

    /**
     * Calculates blemish detection confidence
     */
    private fun calculateBlemishConfidence(brightness: Float, contrast: Float, radius: Int): Float {
        // Blemishes are typically darker or lighter spots with high local contrast
        val brightnessScore = when {
            brightness < 80f -> (80f - brightness) / 80f // Dark spots
            brightness > 180f -> (brightness - 180f) / 75f // Light spots
            else -> 0f
        }
        
        val contrastScore = (contrast / 50f).coerceIn(0f, 1f)
        val sizeScore = if (radius in MIN_BLEMISH_SIZE..MAX_BLEMISH_SIZE) 1f else 0.5f
        
        return (brightnessScore * 0.5f + contrastScore * 0.4f + sizeScore * 0.1f).coerceIn(0f, 1f)
    }

    /**
     * Merges nearby blemish candidates to avoid duplicates
     */
    private fun mergeNearbyBlemishes(blemishes: List<BlemishCandidate>): List<BlemishCandidate> {
        val merged = mutableListOf<BlemishCandidate>()
        val processed = mutableSetOf<Int>()
        
        for (i in blemishes.indices) {
            if (i in processed) continue
            
            var currentBlemish = blemishes[i]
            val nearbyBlemishes = mutableListOf<BlemishCandidate>()
            
            for (j in i + 1 until blemishes.size) {
                if (j in processed) continue
                
                val distance = sqrt(
                    ((currentBlemish.center.first - blemishes[j].center.first) * 
                     (currentBlemish.center.first - blemishes[j].center.first) +
                     (currentBlemish.center.second - blemishes[j].center.second) * 
                     (currentBlemish.center.second - blemishes[j].center.second)).toFloat()
                )
                
                if (distance < 10f) { // Merge blemishes within 10 pixels
                    nearbyBlemishes.add(blemishes[j])
                    processed.add(j)
                }
            }
            
            if (nearbyBlemishes.isNotEmpty()) {
                // Merge by taking the highest confidence blemish
                val allBlemishes = nearbyBlemishes + currentBlemish
                currentBlemish = allBlemishes.maxByOrNull { it.confidence } ?: currentBlemish
            }
            
            merged.add(currentBlemish)
            processed.add(i)
        }
        
        return merged
    }
    
    /**
     * Calculates combined bounding box with margin for ROI processing
     */
    private fun calculateCombinedBounds(regions: List<SkinRegion>): Rect {
        if (regions.isEmpty()) {
            return Rect(0, 0, 0, 0)
        }
        
        val margin = 20 // Add 20 pixel margin around skin regions
        val minX = (regions.minOf { it.bounds.left } - margin).coerceAtLeast(0)
        val maxX = (regions.maxOf { it.bounds.right } + margin)
        val minY = (regions.minOf { it.bounds.top } - margin).coerceAtLeast(0)
        val maxY = (regions.maxOf { it.bounds.bottom } + margin)
        
        return Rect(minX, minY, maxX, maxY)
    }
    
    /**
     * Applies bilateral filter only to the specified region for performance optimization with variable kernel size
     */
    private fun applyBilateralFilterToRegion(bitmap: Bitmap, region: Rect, intensity: Float, kernelSize: Int = 15): Bitmap {
        val result = bitmap.copy(bitmap.config, true)
        val width = bitmap.width
        val height = bitmap.height
        
        val kernelRadius = (kernelSize / 2)
        val sigmaColor = BILATERAL_SIGMA_COLOR * intensity
        val sigmaSpace = BILATERAL_SIGMA_SPACE * intensity
        
        // Extract pixel array once for fast access
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // Pre-calculate spatial weights
        val spatialWeights = Array(kernelSize) { Array(kernelSize) { 0f } }
        for (i in 0 until kernelSize) {
            for (j in 0 until kernelSize) {
                val dx = i - kernelRadius
                val dy = j - kernelRadius
                val spatialDistance = sqrt((dx * dx + dy * dy).toFloat())
                spatialWeights[i][j] = exp(-(spatialDistance * spatialDistance) / (2 * sigmaSpace * sigmaSpace))
            }
        }
        
        // Apply bilateral filter only within the specified region
        val startY = maxOf(kernelRadius, region.top)
        val endY = minOf(height - kernelRadius, region.bottom)
        val startX = maxOf(kernelRadius, region.left)
        val endX = minOf(width - kernelRadius, region.right)
        
        for (y in startY until endY) {
            for (x in startX until endX) {
                val centerIdx = y * width + x
                val centerPixel = pixels[centerIdx]
                val centerR = Color.red(centerPixel)
                val centerG = Color.green(centerPixel)
                val centerB = Color.blue(centerPixel)
                
                var weightSum = 0f
                var filteredR = 0f
                var filteredG = 0f
                var filteredB = 0f
                
                for (ky in 0 until kernelSize) {
                    for (kx in 0 until kernelSize) {
                        val px = x + kx - kernelRadius
                        val py = y + ky - kernelRadius
                        val neighborIdx = py * width + px
                        
                        val neighborPixel = pixels[neighborIdx]
                        val neighborR = Color.red(neighborPixel)
                        val neighborG = Color.green(neighborPixel)
                        val neighborB = Color.blue(neighborPixel)
                        
                        // Calculate color distance
                        val colorDistance = sqrt(
                            ((centerR - neighborR) * (centerR - neighborR) +
                             (centerG - neighborG) * (centerG - neighborG) +
                             (centerB - neighborB) * (centerB - neighborB)).toFloat()
                        )
                        
                        // Calculate color weight
                        val colorWeight = exp(-(colorDistance * colorDistance) / (2 * sigmaColor * sigmaColor))
                        
                        // Combine spatial and color weights
                        val totalWeight = spatialWeights[ky][kx] * colorWeight
                        
                        weightSum += totalWeight
                        filteredR += neighborR * totalWeight
                        filteredG += neighborG * totalWeight
                        filteredB += neighborB * totalWeight
                    }
                }
                
                if (weightSum > 0) {
                    val newR = (filteredR / weightSum).toInt().coerceIn(0, 255)
                    val newG = (filteredG / weightSum).toInt().coerceIn(0, 255)
                    val newB = (filteredB / weightSum).toInt().coerceIn(0, 255)
                    
                    result.setPixel(x, y, Color.rgb(newR, newG, newB))
                }
            }
        }
        
        return result
    }
    
    /**
     * Applies simple Gaussian blur to the specified region for Lite mode processing
     */
    private fun applyGaussianBlurToRegion(bitmap: Bitmap, region: Rect, intensity: Float): Bitmap {
        val result = bitmap.copy(bitmap.config, true)
        val kernelSize = 5 // Fixed 5x5 kernel for Gaussian blur
        val kernelRadius = kernelSize / 2
        val sigma = 2.0f * intensity
        
        // Create Gaussian kernel
        val kernel = FloatArray(kernelSize * kernelSize)
        var kernelSum = 0f
        
        for (i in 0 until kernelSize) {
            for (j in 0 until kernelSize) {
                val x = i - kernelRadius
                val y = j - kernelRadius
                val weight = exp(-(x * x + y * y) / (2 * sigma * sigma))
                kernel[i * kernelSize + j] = weight
                kernelSum += weight
            }
        }
        
        // Normalize kernel
        for (i in kernel.indices) {
            kernel[i] /= kernelSum
        }
        
        // Apply Gaussian blur only within the specified region
        val startY = maxOf(kernelRadius, region.top)
        val endY = minOf(bitmap.height - kernelRadius, region.bottom)
        val startX = maxOf(kernelRadius, region.left)
        val endX = minOf(bitmap.width - kernelRadius, region.right)
        
        for (y in startY until endY) {
            for (x in startX until endX) {
                var r = 0f
                var g = 0f
                var b = 0f
                
                for (ky in 0 until kernelSize) {
                    for (kx in 0 until kernelSize) {
                        val px = x + kx - kernelRadius
                        val py = y + ky - kernelRadius
                        val pixel = bitmap.getPixel(px, py)
                        
                        val weight = kernel[ky * kernelSize + kx]
                        r += Color.red(pixel) * weight
                        g += Color.green(pixel) * weight
                        b += Color.blue(pixel) * weight
                    }
                }
                
                result.setPixel(x, y, Color.rgb(r.toInt(), g.toInt(), b.toInt()))
            }
        }
        
        return result
    }
}