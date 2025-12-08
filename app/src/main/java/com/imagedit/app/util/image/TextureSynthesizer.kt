package com.imagedit.app.util.image

import android.graphics.*
import com.imagedit.app.domain.model.TexturePatch
import com.imagedit.app.domain.model.TextureFeatures
import kotlin.math.*

/**
 * Implements advanced patch-based texture synthesis algorithms for healing operations
 * Features multi-scale synthesis, improved similarity metrics, and gradient-domain blending
 */
class TextureSynthesizer {

    companion object {
        private const val PATCH_SIZE = 32
        private const val OVERLAP_SIZE = 8
        private const val MAX_PATCHES_TO_EVALUATE = 100
        private const val SIMILARITY_THRESHOLD = 0.6f
        private const val MULTI_SCALE_LEVELS = 3
        private const val GRADIENT_WEIGHT = 0.3f
        private const val TEXTURE_WEIGHT = 0.4f
        private const val COLOR_WEIGHT = 0.3f
    }

    /**
     * Performs advanced multi-scale patch-based healing with improved similarity metrics
     */
    fun synthesizeTexture(
        sourceBitmap: Bitmap,
        targetArea: Rect,
        maskBitmap: Bitmap
    ): Bitmap {
        // Use multi-scale approach for better quality
        return synthesizeMultiScale(sourceBitmap, targetArea, maskBitmap)
    }

    /**
     * Multi-scale texture synthesis for better quality results
     */
    private fun synthesizeMultiScale(
        sourceBitmap: Bitmap,
        targetArea: Rect,
        maskBitmap: Bitmap
    ): Bitmap {
        var result = sourceBitmap.copy(sourceBitmap.config, true)
        
        // Process from coarse to fine scale
        for (level in MULTI_SCALE_LEVELS - 1 downTo 0) {
            val scale = 1f / (1 shl level) // 1/8, 1/4, 1/2, 1
            val scaledPatchSize = (PATCH_SIZE * scale).toInt().coerceAtLeast(8)
            
            result = synthesizeAtScale(
                result,
                targetArea,
                maskBitmap,
                scaledPatchSize,
                scale
            )
        }
        
        return result
    }

    /**
     * Synthesizes texture at a specific scale level
     */
    private fun synthesizeAtScale(
        sourceBitmap: Bitmap,
        targetArea: Rect,
        maskBitmap: Bitmap,
        patchSize: Int,
        scale: Float
    ): Bitmap {
        val result = sourceBitmap.copy(sourceBitmap.config, true)
        val canvas = Canvas(result)
        
        // Find best matching patches with improved metrics
        val patches = findBestPatchesAdvanced(sourceBitmap, targetArea, maskBitmap, patchSize)
        
        // Apply patches with gradient-domain blending
        patches.forEach { patch ->
            val blendedPatch = applyGradientDomainBlending(patch, result, targetArea)
            canvas.drawBitmap(blendedPatch, patch.targetRect.left.toFloat(), patch.targetRect.top.toFloat(), null)
        }
        
        return result
    }

    /**
     * Finds the best matching patches using advanced similarity metrics
     */
    private fun findBestPatchesAdvanced(
        sourceBitmap: Bitmap,
        targetArea: Rect,
        maskBitmap: Bitmap,
        patchSize: Int = PATCH_SIZE
    ): List<TexturePatch> {
        val patches = mutableListOf<TexturePatch>()
        val searchRadius = min(sourceBitmap.width, sourceBitmap.height) / 3
        
        // Divide target area into overlapping patches
        val patchRects = generatePatchRects(targetArea, patchSize)
        
        patchRects.forEach { patchRect ->
            val bestPatch = findBestMatchingPatchAdvanced(
                sourceBitmap, 
                patchRect, 
                maskBitmap, 
                searchRadius,
                patchSize
            )
            bestPatch?.let { patches.add(it) }
        }
        
        return patches.sortedByDescending { it.similarity }
    }

    /**
     * Generates overlapping patch rectangles for the target area with adaptive sizing
     */
    private fun generatePatchRects(targetArea: Rect, patchSize: Int = PATCH_SIZE): List<Rect> {
        val patches = mutableListOf<Rect>()
        val overlapSize = (patchSize * 0.25f).toInt().coerceAtLeast(4)
        val stepSize = patchSize - overlapSize
        
        var y = targetArea.top
        while (y < targetArea.bottom) {
            var x = targetArea.left
            while (x < targetArea.right) {
                val patchRect = Rect(
                    x,
                    y,
                    min(x + patchSize, targetArea.right),
                    min(y + patchSize, targetArea.bottom)
                )
                patches.add(patchRect)
                x += stepSize
            }
            y += stepSize
        }
        
        return patches
    }

    /**
     * Finds the best matching patch using advanced similarity metrics
     */
    private fun findBestMatchingPatchAdvanced(
        sourceBitmap: Bitmap,
        targetRect: Rect,
        maskBitmap: Bitmap,
        searchRadius: Int,
        patchSize: Int
    ): TexturePatch? {
        var bestPatch: TexturePatch? = null
        var bestSimilarity = 0f
        
        // Sample candidate patches around the target area
        val candidates = generateCandidatePatches(sourceBitmap, targetRect, searchRadius)
        
        // Analyze target texture for better matching
        val targetFeatures = analyzeTargetTextureFeatures(sourceBitmap, targetRect)
        
        candidates.take(MAX_PATCHES_TO_EVALUATE).forEach { candidateRect ->
            val similarity = calculateAdvancedPatchSimilarity(
                sourceBitmap,
                candidateRect,
                targetRect,
                maskBitmap,
                targetFeatures
            )
            
            if (similarity > bestSimilarity && similarity > SIMILARITY_THRESHOLD) {
                bestSimilarity = similarity
                bestPatch = createAdvancedTexturePatch(
                    sourceBitmap,
                    candidateRect,
                    targetRect,
                    similarity
                )
            }
        }
        
        return bestPatch
    }

    /**
     * Analyzes texture features of the target area for better matching
     */
    private fun analyzeTargetTextureFeatures(
        sourceBitmap: Bitmap,
        targetRect: Rect
    ): TextureFeatures {
        // Create a small bitmap from the target area border for analysis
        val borderWidth = 4
        val expandedRect = Rect(
            (targetRect.left - borderWidth).coerceAtLeast(0),
            (targetRect.top - borderWidth).coerceAtLeast(0),
            (targetRect.right + borderWidth).coerceAtMost(sourceBitmap.width),
            (targetRect.bottom + borderWidth).coerceAtMost(sourceBitmap.height)
        )
        
        val borderBitmap = Bitmap.createBitmap(
            sourceBitmap,
            expandedRect.left,
            expandedRect.top,
            expandedRect.width(),
            expandedRect.height()
        )
        
        return analyzeAdvancedTextureFeatures(borderBitmap)
    }

    /**
     * Generates candidate patch locations for matching
     */
    private fun generateCandidatePatches(
        sourceBitmap: Bitmap,
        targetRect: Rect,
        searchRadius: Int
    ): List<Rect> {
        val candidates = mutableListOf<Rect>()
        val centerX = targetRect.centerX()
        val centerY = targetRect.centerY()
        val patchWidth = targetRect.width()
        val patchHeight = targetRect.height()
        
        // Generate spiral pattern around target area
        for (radius in 10..searchRadius step 20) {
            for (angle in 0 until 360 step 30) {
                val x = centerX + (radius * cos(Math.toRadians(angle.toDouble()))).toInt()
                val y = centerY + (radius * sin(Math.toRadians(angle.toDouble()))).toInt()
                
                val candidateRect = Rect(
                    x - patchWidth / 2,
                    y - patchHeight / 2,
                    x + patchWidth / 2,
                    y + patchHeight / 2
                )
                
                // Ensure candidate is within source bitmap bounds
                if (candidateRect.left >= 0 && candidateRect.top >= 0 &&
                    candidateRect.right < sourceBitmap.width &&
                    candidateRect.bottom < sourceBitmap.height) {
                    candidates.add(candidateRect)
                }
            }
        }
        
        return candidates
    }

    /**
     * Calculates advanced similarity between patches using multiple metrics
     */
    private fun calculateAdvancedPatchSimilarity(
        sourceBitmap: Bitmap,
        sourceRect: Rect,
        targetRect: Rect,
        maskBitmap: Bitmap,
        targetFeatures: TextureFeatures
    ): Float {
        // Get candidate patch features
        val candidateBitmap = Bitmap.createBitmap(
            sourceBitmap,
            sourceRect.left,
            sourceRect.top,
            sourceRect.width(),
            sourceRect.height()
        )
        val candidateFeatures = analyzeAdvancedTextureFeatures(candidateBitmap)
        
        // Calculate multiple similarity metrics
        val colorSimilarity = calculateColorSimilarity(candidateFeatures, targetFeatures)
        val textureSimilarity = calculateTextureSimilarity(candidateFeatures, targetFeatures)
        val gradientSimilarity = calculateGradientSimilarity(candidateBitmap, sourceBitmap, targetRect)
        
        // Weighted combination of similarities
        return COLOR_WEIGHT * colorSimilarity + 
               TEXTURE_WEIGHT * textureSimilarity + 
               GRADIENT_WEIGHT * gradientSimilarity
    }

    /**
     * Calculates color similarity between texture features
     */
    private fun calculateColorSimilarity(
        candidate: TextureFeatures,
        target: TextureFeatures
    ): Float {
        val candidateR = Color.red(candidate.averageColor)
        val candidateG = Color.green(candidate.averageColor)
        val candidateB = Color.blue(candidate.averageColor)
        
        val targetR = Color.red(target.averageColor)
        val targetG = Color.green(target.averageColor)
        val targetB = Color.blue(target.averageColor)
        
        val colorDistance = sqrt(
            ((candidateR - targetR) * (candidateR - targetR) +
             (candidateG - targetG) * (candidateG - targetG) +
             (candidateB - targetB) * (candidateB - targetB)).toFloat()
        )
        
        // Normalize to 0-1 range (max distance is sqrt(3*255^2))
        return 1f - (colorDistance / (255f * sqrt(3f))).coerceIn(0f, 1f)
    }

    /**
     * Calculates texture similarity using advanced features
     */
    private fun calculateTextureSimilarity(
        candidate: TextureFeatures,
        target: TextureFeatures
    ): Float {
        val contrastSim = 1f - abs(candidate.contrast - target.contrast) / max(candidate.contrast, target.contrast).coerceAtLeast(1f)
        val entropySim = 1f - abs(candidate.entropy - target.entropy) / max(candidate.entropy, target.entropy).coerceAtLeast(1f)
        val edgeSim = 1f - abs(candidate.edgeDensity - target.edgeDensity) / max(candidate.edgeDensity, target.edgeDensity).coerceAtLeast(0.1f)
        val energySim = 1f - abs(candidate.textureEnergy - target.textureEnergy) / max(candidate.textureEnergy, target.textureEnergy).coerceAtLeast(1f)
        
        return (contrastSim + entropySim + edgeSim + energySim) / 4f
    }

    /**
     * Calculates gradient similarity for better edge matching
     */
    private fun calculateGradientSimilarity(
        candidateBitmap: Bitmap,
        sourceBitmap: Bitmap,
        targetRect: Rect
    ): Float {
        val candidateGradient = calculateGradientMagnitude(candidateBitmap)
        val targetGradient = calculateGradientMagnitudeForRect(sourceBitmap, targetRect)
        
        return 1f - abs(candidateGradient - targetGradient) / max(candidateGradient, targetGradient).coerceAtLeast(1f)
    }

    /**
     * Calculates gradient magnitude for a bitmap
     */
    private fun calculateGradientMagnitude(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        var totalGradient = 0f
        var pixelCount = 0
        
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val center = getGrayscaleValue(bitmap.getPixel(x, y))
                val right = getGrayscaleValue(bitmap.getPixel(x + 1, y))
                val bottom = getGrayscaleValue(bitmap.getPixel(x, y + 1))
                
                val gradX = right - center
                val gradY = bottom - center
                val magnitude = sqrt(gradX * gradX + gradY * gradY).toFloat()
                
                totalGradient += magnitude
                pixelCount++
            }
        }
        
        return if (pixelCount > 0) totalGradient / pixelCount else 0f
    }

    /**
     * Calculates gradient magnitude for a specific rectangle
     */
    private fun calculateGradientMagnitudeForRect(bitmap: Bitmap, rect: Rect): Float {
        val borderWidth = 2
        val expandedRect = Rect(
            (rect.left - borderWidth).coerceAtLeast(0),
            (rect.top - borderWidth).coerceAtLeast(0),
            (rect.right + borderWidth).coerceAtMost(bitmap.width),
            (rect.bottom + borderWidth).coerceAtMost(bitmap.height)
        )
        
        val rectBitmap = Bitmap.createBitmap(
            bitmap,
            expandedRect.left,
            expandedRect.top,
            expandedRect.width(),
            expandedRect.height()
        )
        
        return calculateGradientMagnitude(rectBitmap)
    }

    /**
     * Extracts pixels from a rectangular region of a bitmap
     */
    private fun getPixelsFromRect(bitmap: Bitmap, rect: Rect): IntArray {
        val width = rect.width()
        val height = rect.height()
        val pixels = IntArray(width * height)
        
        bitmap.getPixels(
            pixels, 0, width,
            rect.left, rect.top,
            width, height
        )
        
        return pixels
    }

    /**
     * Converts a color pixel to grayscale value
     */
    private fun getGrayscaleValue(pixel: Int): Double {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    /**
     * Creates an advanced TexturePatch with comprehensive features
     */
    private fun createAdvancedTexturePatch(
        sourceBitmap: Bitmap,
        sourceRect: Rect,
        targetRect: Rect,
        similarity: Float
    ): TexturePatch {
        val patchBitmap = Bitmap.createBitmap(
            sourceBitmap,
            sourceRect.left,
            sourceRect.top,
            sourceRect.width(),
            sourceRect.height()
        )
        
        val textureFeatures = analyzeAdvancedTextureFeatures(patchBitmap)
        
        return TexturePatch(
            sourceRect = sourceRect,
            targetRect = targetRect,
            patchBitmap = patchBitmap,
            similarity = similarity,
            textureFeatures = textureFeatures
        )
    }

    /**
     * Analyzes advanced texture features for improved patch matching
     */
    private fun analyzeAdvancedTextureFeatures(bitmap: Bitmap): TextureFeatures {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        var colorVariance = 0f
        
        // Calculate average color
        pixels.forEach { pixel ->
            totalR += Color.red(pixel)
            totalG += Color.green(pixel)
            totalB += Color.blue(pixel)
        }
        
        val avgR = (totalR / pixels.size).toInt()
        val avgG = (totalG / pixels.size).toInt()
        val avgB = (totalB / pixels.size).toInt()
        val averageColor = Color.rgb(avgR, avgG, avgB)
        
        // Calculate color variance
        pixels.forEach { pixel ->
            val rDiff = Color.red(pixel) - avgR
            val gDiff = Color.green(pixel) - avgG
            val bDiff = Color.blue(pixel) - avgB
            colorVariance += (rDiff * rDiff + gDiff * gDiff + bDiff * bDiff).toFloat()
        }
        colorVariance /= pixels.size
        
        // Calculate advanced texture features
        val edgeDensity = calculateAdvancedEdgeDensity(bitmap)
        val dominantDirection = calculateDominantDirection(bitmap)
        val contrast = sqrt(colorVariance)
        val entropy = calculateEntropy(pixels)
        val localBinaryPattern = calculateLocalBinaryPattern(bitmap)
        val gradientMagnitude = calculateGradientMagnitude(bitmap)
        val textureEnergy = calculateTextureEnergy(bitmap)
        val homogeneity = calculateHomogeneity(bitmap)
        
        return TextureFeatures(
            averageColor = averageColor,
            colorVariance = colorVariance,
            edgeDensity = edgeDensity,
            dominantDirection = dominantDirection,
            contrast = contrast,
            entropy = entropy,
            localBinaryPattern = localBinaryPattern,
            gradientMagnitude = gradientMagnitude,
            textureEnergy = textureEnergy,
            homogeneity = homogeneity
        )
    }

    /**
     * Calculates dominant texture direction using gradient analysis
     */
    private fun calculateDominantDirection(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        var sumX = 0.0
        var sumY = 0.0
        
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val center = getGrayscaleValue(bitmap.getPixel(x, y))
                val right = getGrayscaleValue(bitmap.getPixel(x + 1, y))
                val bottom = getGrayscaleValue(bitmap.getPixel(x, y + 1))
                
                val gradX = right - center
                val gradY = bottom - center
                
                sumX += gradX * gradX
                sumY += gradY * gradY
            }
        }
        
        return atan2(sumY, sumX).toFloat()
    }

    /**
     * Calculates Local Binary Pattern for texture analysis
     */
    private fun calculateLocalBinaryPattern(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val histogram = FloatArray(256) // LBP histogram
        
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val center = getGrayscaleValue(bitmap.getPixel(x, y)).toInt()
                var lbpValue = 0
                
                // 8-neighbor LBP
                val neighbors = arrayOf(
                    Pair(-1, -1), Pair(-1, 0), Pair(-1, 1),
                    Pair(0, 1), Pair(1, 1), Pair(1, 0),
                    Pair(1, -1), Pair(0, -1)
                )
                
                neighbors.forEachIndexed { index, (dx, dy) ->
                    val neighborValue = getGrayscaleValue(bitmap.getPixel(x + dx, y + dy)).toInt()
                    if (neighborValue >= center) {
                        lbpValue = lbpValue or (1 shl index)
                    }
                }
                
                histogram[lbpValue.coerceIn(0, 255)]++
            }
        }
        
        // Normalize histogram
        val total = histogram.sum()
        if (total > 0) {
            for (i in histogram.indices) {
                histogram[i] /= total
            }
        }
        
        return histogram
    }

    /**
     * Calculates texture energy (uniformity measure)
     */
    private fun calculateTextureEnergy(bitmap: Bitmap): Float {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        val histogram = IntArray(256)
        pixels.forEach { pixel ->
            val gray = getGrayscaleValue(pixel).toInt().coerceIn(0, 255)
            histogram[gray]++
        }
        
        var energy = 0.0
        val total = pixels.size.toDouble()
        
        histogram.forEach { count ->
            if (count > 0) {
                val probability = count / total
                energy += probability * probability
            }
        }
        
        return energy.toFloat()
    }

    /**
     * Calculates texture homogeneity
     */
    private fun calculateHomogeneity(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        var homogeneity = 0.0
        var pairCount = 0
        
        for (y in 0 until height - 1) {
            for (x in 0 until width - 1) {
                val current = getGrayscaleValue(bitmap.getPixel(x, y)).toInt()
                val right = getGrayscaleValue(bitmap.getPixel(x + 1, y)).toInt()
                val bottom = getGrayscaleValue(bitmap.getPixel(x, y + 1)).toInt()
                
                homogeneity += 1.0 / (1.0 + abs(current - right))
                homogeneity += 1.0 / (1.0 + abs(current - bottom))
                pairCount += 2
            }
        }
        
        return if (pairCount > 0) (homogeneity / pairCount).toFloat() else 0f
    }

    /**
     * Calculates advanced edge density using Sobel operator
     */
    private fun calculateAdvancedEdgeDensity(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        var totalEdgeStrength = 0.0
        var pixelCount = 0
        
        // Sobel kernels
        val sobelX = arrayOf(
            intArrayOf(-1, 0, 1),
            intArrayOf(-2, 0, 2),
            intArrayOf(-1, 0, 1)
        )
        
        val sobelY = arrayOf(
            intArrayOf(-1, -2, -1),
            intArrayOf(0, 0, 0),
            intArrayOf(1, 2, 1)
        )
        
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var gradX = 0.0
                var gradY = 0.0
                
                // Apply Sobel kernels
                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val pixelValue = getGrayscaleValue(bitmap.getPixel(x + kx, y + ky))
                        gradX += pixelValue * sobelX[ky + 1][kx + 1]
                        gradY += pixelValue * sobelY[ky + 1][kx + 1]
                    }
                }
                
                val magnitude = sqrt(gradX * gradX + gradY * gradY)
                totalEdgeStrength += magnitude
                pixelCount++
            }
        }
        
        return if (pixelCount > 0) (totalEdgeStrength / pixelCount).toFloat() else 0f
    }

    /**
     * Calculates texture entropy (complexity measure)
     */
    private fun calculateEntropy(pixels: IntArray): Float {
        val histogram = IntArray(256)
        
        // Build grayscale histogram
        pixels.forEach { pixel ->
            val gray = getGrayscaleValue(pixel).toInt().coerceIn(0, 255)
            histogram[gray]++
        }
        
        // Calculate entropy
        var entropy = 0.0
        val total = pixels.size.toDouble()
        
        histogram.forEach { count ->
            if (count > 0) {
                val probability = count / total
                entropy -= probability * log2(probability)
            }
        }
        
        return entropy.toFloat()
    }

    /**
     * Applies advanced gradient-domain blending for seamless results
     */
    private fun applyGradientDomainBlending(
        patch: TexturePatch,
        targetBitmap: Bitmap,
        targetArea: Rect
    ): Bitmap {
        val blendedPatch = patch.patchBitmap.copy(patch.patchBitmap.config, true)
        
        // Apply gradient-domain blending with improved edge handling
        return applyAdvancedPoissonBlending(blendedPatch, targetBitmap, patch.targetRect)
    }

    /**
     * Advanced Poisson blending with gradient preservation
     */
    private fun applyAdvancedPoissonBlending(
        sourcePatch: Bitmap,
        targetBitmap: Bitmap,
        targetRect: Rect
    ): Bitmap {
        val result = sourcePatch.copy(sourcePatch.config, true)
        val canvas = Canvas(result)
        
        // Calculate gradient fields for both source and target
        val sourceGradients = calculateGradientField(sourcePatch)
        val targetGradients = calculateGradientFieldForRect(targetBitmap, targetRect)
        
        // Blend gradients for seamless integration
        val blendedGradients = blendGradientFields(sourceGradients, targetGradients)
        
        // Reconstruct image from blended gradients
        val reconstructed = reconstructFromGradients(blendedGradients, sourcePatch.width, sourcePatch.height)
        
        // Apply feathered blending at edges
        val featheredResult = applyFeatheredBlending(reconstructed, sourcePatch)
        
        canvas.drawBitmap(featheredResult, 0f, 0f, null)
        
        return result
    }

    /**
     * Calculates gradient field for a bitmap
     */
    private fun calculateGradientField(bitmap: Bitmap): Array<Array<Pair<Float, Float>>> {
        val width = bitmap.width
        val height = bitmap.height
        val gradients = Array(height) { Array(width) { Pair(0f, 0f) } }
        
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val center = getGrayscaleValue(bitmap.getPixel(x, y))
                val right = getGrayscaleValue(bitmap.getPixel(x + 1, y))
                val bottom = getGrayscaleValue(bitmap.getPixel(x, y + 1))
                
                val gradX = (right - center).toFloat()
                val gradY = (bottom - center).toFloat()
                
                gradients[y][x] = Pair(gradX, gradY)
            }
        }
        
        return gradients
    }

    /**
     * Calculates gradient field for a specific rectangle in target bitmap
     */
    private fun calculateGradientFieldForRect(
        bitmap: Bitmap,
        rect: Rect
    ): Array<Array<Pair<Float, Float>>> {
        val width = rect.width()
        val height = rect.height()
        val gradients = Array(height) { Array(width) { Pair(0f, 0f) } }
        
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val actualX = rect.left + x
                val actualY = rect.top + y
                
                if (actualX + 1 < bitmap.width && actualY + 1 < bitmap.height) {
                    val center = getGrayscaleValue(bitmap.getPixel(actualX, actualY))
                    val right = getGrayscaleValue(bitmap.getPixel(actualX + 1, actualY))
                    val bottom = getGrayscaleValue(bitmap.getPixel(actualX, actualY + 1))
                    
                    val gradX = (right - center).toFloat()
                    val gradY = (bottom - center).toFloat()
                    
                    gradients[y][x] = Pair(gradX, gradY)
                }
            }
        }
        
        return gradients
    }

    /**
     * Blends two gradient fields for seamless integration
     */
    private fun blendGradientFields(
        source: Array<Array<Pair<Float, Float>>>,
        target: Array<Array<Pair<Float, Float>>>
    ): Array<Array<Pair<Float, Float>>> {
        val height = minOf(source.size, target.size)
        val width = if (height > 0) minOf(source[0].size, target[0].size) else 0
        val blended = Array(height) { Array(width) { Pair(0f, 0f) } }
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val sourceGrad = source[y][x]
                val targetGrad = target[y][x]
                
                // Blend gradients based on magnitude
                val sourceMag = sqrt(sourceGrad.first * sourceGrad.first + sourceGrad.second * sourceGrad.second)
                val targetMag = sqrt(targetGrad.first * targetGrad.first + targetGrad.second * targetGrad.second)
                
                val weight = if (sourceMag + targetMag > 0) sourceMag / (sourceMag + targetMag) else 0.5f
                
                val blendedX = weight * sourceGrad.first + (1f - weight) * targetGrad.first
                val blendedY = weight * sourceGrad.second + (1f - weight) * targetGrad.second
                
                blended[y][x] = Pair(blendedX, blendedY)
            }
        }
        
        return blended
    }

    /**
     * Reconstructs image from gradient field (simplified Poisson solver)
     */
    private fun reconstructFromGradients(
        gradients: Array<Array<Pair<Float, Float>>>,
        width: Int,
        height: Int
    ): Bitmap {
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        
        // Simple integration of gradients (could be improved with proper Poisson solver)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var value = 128f // Start with middle gray
                
                if (y < gradients.size && x < gradients[0].size) {
                    val grad = gradients[y][x]
                    value += grad.first + grad.second
                }
                
                val grayValue = value.toInt().coerceIn(0, 255)
                pixels[y * width + x] = Color.rgb(grayValue, grayValue, grayValue)
            }
        }
        
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Applies feathered blending at patch edges
     */
    private fun applyFeatheredBlending(reconstructed: Bitmap, original: Bitmap): Bitmap {
        val result = original.copy(original.config, true)
        val canvas = Canvas(result)
        
        // Create feather mask
        val featherMask = createFeatherMask(original.width, original.height)
        
        val paint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        }
        
        // Apply feathered blending
        canvas.drawBitmap(reconstructed, 0f, 0f, paint)
        canvas.drawBitmap(featherMask, 0f, 0f, paint)
        
        return result
    }

    /**
     * Creates a feather mask for smooth edge blending
     */
    private fun createFeatherMask(width: Int, height: Int): Bitmap {
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(mask)
        
        val featherSize = minOf(width, height) * 0.1f
        
        val gradient = RadialGradient(
            width / 2f, height / 2f,
            minOf(width, height) / 2f - featherSize,
            Color.WHITE, Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        
        val paint = Paint().apply {
            shader = gradient
        }
        
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        
        return mask
    }


}