/**
 * RGBQualityMask.kt - RGB overlay for pixel quality assessment
 * 
 * Uses the user's innovative concept: assign each frame's luminance to a different
 * color channel (R, G, B), then overlay them. Where all three channels align
 * (approaching white), pixels are well-aligned and should be kept. Where colors
 * diverge, there's misalignment or motion.
 * 
 * Key insight: This is used as a QUALITY MASK only - the original frame colors
 * are preserved separately and used for the final output. The RGB overlay is
 * purely for determining which pixels to trust.
 * 
 * Similar techniques:
 * - Anaglyph stereo visualization
 * - Medical image registration QA
 * - Temporal median filtering
 * - Lucky imaging frame selection
 */

package com.imagedit.app.ultradetail

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val TAG = "RGBQualityMask"

/**
 * Configuration for RGB quality mask
 */
data class RGBQualityConfig(
    /** Threshold for considering pixels "aligned" (0-1, lower = stricter) */
    val alignmentThreshold: Float = 0.15f,
    
    /** Weight for aligned pixels in final merge */
    val alignedWeight: Float = 1.0f,
    
    /** Weight for misaligned pixels (0 = reject, 1 = keep anyway) */
    val misalignedWeight: Float = 0.3f,
    
    /** Whether to generate debug visualization */
    val generateVisualization: Boolean = false
)

/**
 * Result of RGB quality mask computation
 */
data class RGBQualityResult(
    /** Quality mask (0-1 per pixel, 1 = high quality/aligned) */
    val qualityMask: FloatArray,
    
    /** Percentage of pixels that are well-aligned */
    val alignmentPercentage: Float,
    
    /** Optional RGB visualization bitmap for debugging */
    val visualization: Bitmap?,
    
    /** Width of the mask */
    val width: Int,
    
    /** Height of the mask */
    val height: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RGBQualityResult
        return qualityMask.contentEquals(other.qualityMask) &&
               alignmentPercentage == other.alignmentPercentage &&
               width == other.width && height == other.height
    }

    override fun hashCode(): Int {
        var result = qualityMask.contentHashCode()
        result = 31 * result + alignmentPercentage.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}

/**
 * RGB Quality Mask system for pixel selection in multi-frame processing
 */
class RGBQualityMask(
    private val config: RGBQualityConfig = RGBQualityConfig()
) {
    
    /**
     * Compute quality mask from 3 frames using RGB overlay method
     * 
     * @param frame1 First frame (assigned to RED channel)
     * @param frame2 Second frame (assigned to GREEN channel)
     * @param frame3 Third frame (assigned to BLUE channel)
     * @return Quality result with mask and statistics
     */
    fun computeFromThreeFrames(
        frame1: Bitmap,
        frame2: Bitmap,
        frame3: Bitmap
    ): RGBQualityResult {
        require(frame1.width == frame2.width && frame2.width == frame3.width) {
            "All frames must have same width"
        }
        require(frame1.height == frame2.height && frame2.height == frame3.height) {
            "All frames must have same height"
        }
        
        val width = frame1.width
        val height = frame1.height
        val qualityMask = FloatArray(width * height)
        
        var alignedCount = 0
        val totalPixels = width * height
        
        // Create visualization if requested
        val visualization = if (config.generateVisualization) {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } else null
        
        // Process row by row for memory efficiency
        val row1 = IntArray(width)
        val row2 = IntArray(width)
        val row3 = IntArray(width)
        val visRow = if (visualization != null) IntArray(width) else null
        
        for (y in 0 until height) {
            frame1.getPixels(row1, 0, width, 0, y, width, 1)
            frame2.getPixels(row2, 0, width, 0, y, width, 1)
            frame3.getPixels(row3, 0, width, 0, y, width, 1)
            
            for (x in 0 until width) {
                // Extract luminance from each frame
                val lum1 = getLuminance(row1[x])
                val lum2 = getLuminance(row2[x])
                val lum3 = getLuminance(row3[x])
                
                // Compute alignment quality
                // When all three luminances are similar, the "RGB" would be grayish/white
                // When they differ, we get color fringes
                val quality = computeAlignmentQuality(lum1, lum2, lum3)
                qualityMask[y * width + x] = quality
                
                if (quality > (1f - config.alignmentThreshold)) {
                    alignedCount++
                }
                
                // Generate visualization
                if (visRow != null) {
                    // Map luminances to RGB channels for visualization
                    val r = (lum1 * 255).toInt().coerceIn(0, 255)
                    val g = (lum2 * 255).toInt().coerceIn(0, 255)
                    val b = (lum3 * 255).toInt().coerceIn(0, 255)
                    visRow[x] = Color.rgb(r, g, b)
                }
            }
            
            if (visualization != null && visRow != null) {
                visualization.setPixels(visRow, 0, width, 0, y, width, 1)
            }
        }
        
        val alignmentPercentage = alignedCount.toFloat() / totalPixels * 100f
        
        Log.i(TAG, "RGB Quality Mask: ${"%.1f".format(alignmentPercentage)}% pixels aligned " +
                   "(threshold=${"%.2f".format(config.alignmentThreshold)})")
        
        return RGBQualityResult(
            qualityMask = qualityMask,
            alignmentPercentage = alignmentPercentage,
            visualization = visualization,
            width = width,
            height = height
        )
    }
    
    /**
     * Compute quality mask from multiple frames (groups into RGB triplets)
     * 
     * For N frames, creates floor(N/3) RGB overlays and combines them.
     * Remaining frames (N mod 3) are handled separately.
     * 
     * @param frames List of frames to process
     * @return Combined quality result
     */
    fun computeFromMultipleFrames(frames: List<Bitmap>): RGBQualityResult {
        if (frames.isEmpty()) {
            return RGBQualityResult(FloatArray(0), 0f, null, 0, 0)
        }
        
        if (frames.size < 3) {
            // Not enough frames for RGB overlay, use simple variance method
            return computeFromTwoFrames(frames)
        }
        
        val width = frames[0].width
        val height = frames[0].height
        val numTriplets = frames.size / 3
        
        // Accumulate quality from all triplets
        val accumulatedQuality = FloatArray(width * height) { 0f }
        var totalAlignmentPercent = 0f
        
        for (i in 0 until numTriplets) {
            val result = computeFromThreeFrames(
                frames[i * 3],
                frames[i * 3 + 1],
                frames[i * 3 + 2]
            )
            
            // Accumulate quality (will average later)
            for (j in accumulatedQuality.indices) {
                accumulatedQuality[j] += result.qualityMask[j]
            }
            totalAlignmentPercent += result.alignmentPercentage
        }
        
        // Handle remaining frames (if any)
        val remainder = frames.size % 3
        if (remainder > 0) {
            val remainingFrames = frames.takeLast(remainder)
            val remainderResult = computeFromTwoFrames(remainingFrames)
            for (j in accumulatedQuality.indices) {
                accumulatedQuality[j] += remainderResult.qualityMask[j]
            }
            totalAlignmentPercent += remainderResult.alignmentPercentage
        }
        
        // Average the accumulated quality
        val divisor = numTriplets + if (remainder > 0) 1 else 0
        for (j in accumulatedQuality.indices) {
            accumulatedQuality[j] /= divisor
        }
        
        val avgAlignmentPercent = totalAlignmentPercent / divisor
        
        Log.i(TAG, "Combined RGB Quality from ${frames.size} frames ($numTriplets triplets): " +
                   "${"%.1f".format(avgAlignmentPercent)}% aligned")
        
        return RGBQualityResult(
            qualityMask = accumulatedQuality,
            alignmentPercentage = avgAlignmentPercent,
            visualization = null,  // Don't generate for combined
            width = width,
            height = height
        )
    }
    
    /**
     * Fallback for 1-2 frames: use simple variance-based quality
     */
    private fun computeFromTwoFrames(frames: List<Bitmap>): RGBQualityResult {
        if (frames.isEmpty()) {
            return RGBQualityResult(FloatArray(0), 0f, null, 0, 0)
        }
        
        val width = frames[0].width
        val height = frames[0].height
        val qualityMask = FloatArray(width * height) { 1f }  // Default to full quality
        
        if (frames.size == 1) {
            return RGBQualityResult(qualityMask, 100f, null, width, height)
        }
        
        // For 2 frames, compute pixel-wise difference
        val row1 = IntArray(width)
        val row2 = IntArray(width)
        var alignedCount = 0
        
        for (y in 0 until height) {
            frames[0].getPixels(row1, 0, width, 0, y, width, 1)
            frames[1].getPixels(row2, 0, width, 0, y, width, 1)
            
            for (x in 0 until width) {
                val lum1 = getLuminance(row1[x])
                val lum2 = getLuminance(row2[x])
                val diff = abs(lum1 - lum2)
                val quality = 1f - diff.coerceIn(0f, 1f)
                qualityMask[y * width + x] = quality
                
                if (quality > (1f - config.alignmentThreshold)) {
                    alignedCount++
                }
            }
        }
        
        val alignmentPercentage = alignedCount.toFloat() / (width * height) * 100f
        return RGBQualityResult(qualityMask, alignmentPercentage, null, width, height)
    }
    
    /**
     * Apply quality mask to weight pixels during merge
     * 
     * @param qualityMask Quality mask from computeFromThreeFrames
     * @param pixelIndex Index of pixel to get weight for
     * @return Weight to apply (0-1)
     */
    fun getPixelWeight(qualityMask: FloatArray, pixelIndex: Int): Float {
        if (pixelIndex < 0 || pixelIndex >= qualityMask.size) return config.misalignedWeight
        
        val quality = qualityMask[pixelIndex]
        
        // Smooth transition between aligned and misaligned weights
        return if (quality > (1f - config.alignmentThreshold)) {
            config.alignedWeight
        } else {
            // Linear interpolation based on quality
            val t = quality / (1f - config.alignmentThreshold)
            config.misalignedWeight + t * (config.alignedWeight - config.misalignedWeight)
        }
    }
    
    /**
     * Compute alignment quality from three luminance values
     * 
     * Returns 1.0 when all three are identical (white in RGB overlay)
     * Returns 0.0 when they are maximally different
     */
    private fun computeAlignmentQuality(lum1: Float, lum2: Float, lum3: Float): Float {
        // Method 1: Coefficient of variation (CV)
        val mean = (lum1 + lum2 + lum3) / 3f
        if (mean < 0.01f) return 1f  // Dark pixels are considered aligned
        
        val variance = ((lum1 - mean) * (lum1 - mean) +
                       (lum2 - mean) * (lum2 - mean) +
                       (lum3 - mean) * (lum3 - mean)) / 3f
        val stdDev = sqrt(variance)
        val cv = stdDev / mean
        
        // Convert CV to quality (lower CV = higher quality)
        // CV of 0 = perfect alignment = quality 1.0
        // CV of 0.5+ = poor alignment = quality 0.0
        return (1f - cv * 2f).coerceIn(0f, 1f)
    }
    
    /**
     * Get luminance from ARGB pixel
     */
    private fun getLuminance(pixel: Int): Float {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
    }
    
    companion object {
        /**
         * Create a debug visualization showing alignment quality
         * 
         * Green = well aligned
         * Yellow = moderate alignment
         * Red = poor alignment
         */
        fun createQualityVisualization(result: RGBQualityResult): Bitmap {
            val bitmap = Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(result.width)
            
            for (y in 0 until result.height) {
                for (x in 0 until result.width) {
                    val quality = result.qualityMask[y * result.width + x]
                    
                    // Map quality to color: red (0) -> yellow (0.5) -> green (1)
                    val r: Int
                    val g: Int
                    val b = 0
                    
                    if (quality < 0.5f) {
                        // Red to yellow
                        r = 255
                        g = (quality * 2 * 255).toInt()
                    } else {
                        // Yellow to green
                        r = ((1f - quality) * 2 * 255).toInt()
                        g = 255
                    }
                    
                    pixels[x] = Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b)
                }
                bitmap.setPixels(pixels, 0, result.width, 0, y, result.width, 1)
            }
            
            return bitmap
        }
    }
}
