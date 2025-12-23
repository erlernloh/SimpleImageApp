/**
 * SceneClassifier.kt - Content-Aware Scene Classification
 * 
 * Classifies image content to enable adaptive processing:
 * - Face detection for portrait-specific enhancement
 * - Text detection for sharpness-focused processing
 * - Nature/landscape detection for color/texture enhancement
 * - Architecture detection for edge preservation
 */

package com.imagedit.app.ultradetail

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "SceneClassifier"

/**
 * Scene content types
 */
enum class SceneType {
    FACE,           // Portrait with faces
    TEXT,           // Document, signage, text-heavy
    NATURE,         // Landscape, foliage, natural scenes
    ARCHITECTURE,   // Buildings, structures, geometric
    GENERAL         // Mixed or unclassified content
}

/**
 * Scene classification result
 */
data class SceneClassification(
    val primaryType: SceneType,
    val confidence: Float,
    val scores: Map<SceneType, Float>
) {
    override fun toString(): String {
        return "SceneClassification(type=$primaryType, confidence=${"%.2f".format(confidence)}, " +
               "scores=${scores.entries.joinToString { "${it.key}=${"%.2f".format(it.value)}" }})"
    }
}

/**
 * Lightweight scene classifier using heuristics
 * 
 * Uses image statistics and simple feature detection:
 * - Skin tone detection for faces
 * - Edge density for text/architecture
 * - Color distribution for nature
 * - Texture patterns for classification
 */
class SceneClassifier(private val context: Context) {
    
    /**
     * Classify image content
     * 
     * @param bitmap Input image to classify
     * @return Classification result with primary type and confidence scores
     */
    fun classify(bitmap: Bitmap): SceneClassification {
        val startTime = System.currentTimeMillis()
        
        // Downsample for faster analysis
        val sampleSize = 256
        val sample = if (bitmap.width > sampleSize || bitmap.height > sampleSize) {
            Bitmap.createScaledBitmap(
                bitmap,
                minOf(sampleSize, bitmap.width),
                minOf(sampleSize, bitmap.height),
                true
            )
        } else {
            bitmap
        }
        
        // Compute features
        val skinToneRatio = detectSkinTone(sample)
        val edgeDensity = computeEdgeDensity(sample)
        val colorfulness = computeColorfulness(sample)
        val saturation = computeSaturation(sample)
        val textureComplexity = computeTextureComplexity(sample)
        
        // Compute scores for each scene type
        val scores = mutableMapOf<SceneType, Float>()
        
        // Face score: high skin tone ratio, moderate edges
        scores[SceneType.FACE] = skinToneRatio * 2.0f + 
                                 (1.0f - edgeDensity) * 0.5f
        
        // Text score: very high edge density, low colorfulness
        scores[SceneType.TEXT] = edgeDensity * 2.0f + 
                                (1.0f - colorfulness) * 1.0f +
                                (1.0f - saturation) * 0.5f
        
        // Nature score: high colorfulness, high saturation, complex texture
        scores[SceneType.NATURE] = colorfulness * 1.5f + 
                                   saturation * 1.5f +
                                   textureComplexity * 0.5f
        
        // Architecture score: high edges, low saturation, geometric patterns
        scores[SceneType.ARCHITECTURE] = edgeDensity * 1.5f + 
                                         (1.0f - saturation) * 1.0f +
                                         (1.0f - textureComplexity) * 0.5f
        
        // General score: baseline
        scores[SceneType.GENERAL] = 1.0f
        
        // Find primary type
        val primaryEntry = scores.maxByOrNull { it.value }!!
        val primaryType = primaryEntry.key
        val maxScore = primaryEntry.value
        
        // Normalize scores
        val totalScore = scores.values.sum()
        val normalizedScores = scores.mapValues { it.value / totalScore }
        
        // Confidence is the ratio of primary score to second-best score
        val sortedScores = scores.values.sortedDescending()
        val confidence = if (sortedScores.size > 1) {
            (sortedScores[0] - sortedScores[1]) / sortedScores[0]
        } else {
            1.0f
        }.coerceIn(0f, 1f)
        
        if (sample !== bitmap) {
            sample.recycle()
        }
        
        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Scene classification: $primaryType (confidence=${"%.2f".format(confidence)}) in ${elapsed}ms")
        Log.d(TAG, "  Features: skin=${"%.2f".format(skinToneRatio)}, edges=${"%.2f".format(edgeDensity)}, " +
                   "color=${"%.2f".format(colorfulness)}, sat=${"%.2f".format(saturation)}, " +
                   "texture=${"%.2f".format(textureComplexity)}")
        
        return SceneClassification(primaryType, confidence, normalizedScores)
    }
    
    /**
     * Detect skin tone ratio in image
     */
    private fun detectSkinTone(bitmap: Bitmap): Float {
        var skinPixels = 0
        var totalPixels = 0
        
        val step = 4  // Sample every 4th pixel
        
        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                
                // Skin tone detection (simple heuristic)
                // R > 95, G > 40, B > 20, R > G, R > B, |R - G| > 15
                if (r > 95 && g > 40 && b > 20 && 
                    r > g && r > b && 
                    kotlin.math.abs(r - g) > 15) {
                    skinPixels++
                }
                totalPixels++
            }
        }
        
        return if (totalPixels > 0) skinPixels.toFloat() / totalPixels else 0f
    }
    
    /**
     * Compute edge density using Sobel operator
     */
    private fun computeEdgeDensity(bitmap: Bitmap): Float {
        var edgeSum = 0f
        var count = 0
        
        for (y in 1 until bitmap.height - 1) {
            for (x in 1 until bitmap.width - 1) {
                // Get grayscale values
                val tl = getGray(bitmap, x - 1, y - 1)
                val tc = getGray(bitmap, x, y - 1)
                val tr = getGray(bitmap, x + 1, y - 1)
                val ml = getGray(bitmap, x - 1, y)
                val mr = getGray(bitmap, x + 1, y)
                val bl = getGray(bitmap, x - 1, y + 1)
                val bc = getGray(bitmap, x, y + 1)
                val br = getGray(bitmap, x + 1, y + 1)
                
                // Sobel operator
                val gx = -tl - 2 * ml - bl + tr + 2 * mr + br
                val gy = -tl - 2 * tc - tr + bl + 2 * bc + br
                
                val magnitude = kotlin.math.sqrt((gx * gx + gy * gy).toDouble()).toFloat()
                edgeSum += magnitude
                count++
            }
        }
        
        val avgEdge = if (count > 0) edgeSum / count else 0f
        return (avgEdge / 255f).coerceIn(0f, 1f)
    }
    
    /**
     * Compute colorfulness metric
     */
    private fun computeColorfulness(bitmap: Bitmap): Float {
        var colorSum = 0f
        var count = 0
        
        val step = 4
        
        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                val pixel = bitmap.getPixel(x, y)
                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f
                
                // Colorfulness = std dev of RGB channels
                val mean = (r + g + b) / 3f
                val variance = ((r - mean) * (r - mean) + 
                               (g - mean) * (g - mean) + 
                               (b - mean) * (b - mean)) / 3f
                
                colorSum += kotlin.math.sqrt(variance.toDouble()).toFloat()
                count++
            }
        }
        
        return if (count > 0) (colorSum / count).coerceIn(0f, 1f) else 0f
    }
    
    /**
     * Compute average saturation
     */
    private fun computeSaturation(bitmap: Bitmap): Float {
        var satSum = 0f
        var count = 0
        
        val step = 4
        
        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                val pixel = bitmap.getPixel(x, y)
                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f
                
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                
                val saturation = if (max > 0) (max - min) / max else 0f
                satSum += saturation
                count++
            }
        }
        
        return if (count > 0) satSum / count else 0f
    }
    
    /**
     * Compute texture complexity using local variance
     */
    private fun computeTextureComplexity(bitmap: Bitmap): Float {
        var varianceSum = 0f
        var count = 0
        
        val windowSize = 5
        val half = windowSize / 2
        
        for (y in half until bitmap.height - half step 8) {
            for (x in half until bitmap.width - half step 8) {
                var sum = 0f
                var sumSq = 0f
                var n = 0
                
                for (dy in -half..half) {
                    for (dx in -half..half) {
                        val gray = getGray(bitmap, x + dx, y + dy)
                        sum += gray
                        sumSq += gray * gray
                        n++
                    }
                }
                
                val mean = sum / n
                val variance = (sumSq / n) - (mean * mean)
                varianceSum += variance
                count++
            }
        }
        
        val avgVariance = if (count > 0) varianceSum / count else 0f
        return (avgVariance / 10000f).coerceIn(0f, 1f)  // Normalize
    }
    
    private fun getGray(bitmap: Bitmap, x: Int, y: Int): Float {
        val pixel = bitmap.getPixel(x, y)
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b)
    }
}
