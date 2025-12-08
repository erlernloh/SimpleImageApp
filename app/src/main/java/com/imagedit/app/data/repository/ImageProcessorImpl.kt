package com.imagedit.app.data.repository

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import com.imagedit.app.domain.model.AdjustmentParameters
import com.imagedit.app.domain.model.FilmGrain
import com.imagedit.app.domain.model.LensEffects
import com.imagedit.app.domain.repository.ImageProcessor
import com.imagedit.app.util.BitmapCache
import com.imagedit.app.util.image.BitmapPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class ImageProcessorImpl @Inject constructor(
    private val bitmapCache: BitmapCache,
    private val bitmapPool: BitmapPool
) : ImageProcessor {
    
    companion object {
        private const val TAG = "ImageProcessorImpl"
    }
    
    override suspend fun processImage(
        bitmap: Bitmap,
        adjustments: AdjustmentParameters,
        filmGrain: FilmGrain,
        lensEffects: LensEffects
    ): Result<Bitmap> = withContext(Dispatchers.Default) {
        try {
            // Generate cache key based on parameters
            val cacheKey = generateCacheKey(bitmap, adjustments, filmGrain, lensEffects)
            Log.d(TAG, "processImage() - cacheKey: $cacheKey, bitmap hash: ${bitmap.hashCode()}")
            
            // Check cache first
            bitmapCache.get(cacheKey)?.let { cachedBitmap ->
                if (!cachedBitmap.isRecycled) {
                    Log.d(TAG, "Using cached bitmap for key: $cacheKey")
                    return@withContext Result.success(cachedBitmap)
                }
            }
            
            Log.d(TAG, "Processing new bitmap for key: $cacheKey")
            
            // Try to get bitmap from pool, or create new one
            var processedBitmap = bitmapPool.get(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                ?: Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            
            // Copy original bitmap data
            val canvas = Canvas(processedBitmap)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            
            var previousBitmap: Bitmap? = null
            
            // Apply adjustments
            previousBitmap = processedBitmap
            processedBitmap = applyAdjustments(processedBitmap, adjustments)
            if (previousBitmap != processedBitmap && previousBitmap != bitmap) {
                // Return to pool instead of recycling
                bitmapPool.put(previousBitmap)
            }
            
            // Apply film grain with crash fix
            if (filmGrain.amount > 0f) {
                previousBitmap = processedBitmap
                processedBitmap = applyFilmGrain(processedBitmap, filmGrain)
                if (previousBitmap != processedBitmap && previousBitmap != bitmap) {
                    // Return to pool instead of recycling
                    bitmapPool.put(previousBitmap)
                }
            }
            
            // Apply lens effects
            if (lensEffects.vignetteAmount > 0f) {
                previousBitmap = processedBitmap
                processedBitmap = applyVignette(processedBitmap, lensEffects)
                if (previousBitmap != processedBitmap && previousBitmap != bitmap) {
                    // Return to pool instead of recycling
                    bitmapPool.put(previousBitmap)
                }
            }
            
            // Cache the result
            bitmapCache.put(cacheKey, processedBitmap)
            
            Result.success(processedBitmap)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun generateCacheKey(
        bitmap: Bitmap,
        adjustments: AdjustmentParameters,
        filmGrain: FilmGrain,
        lensEffects: LensEffects
    ): String {
        // Include bitmap hashCode to differentiate between original and transformed bitmaps
        // This ensures that flipped/rotated bitmaps get their own cache entries
        return "${bitmap.hashCode()}_${bitmap.width}x${bitmap.height}_" +
                "b${adjustments.brightness}_" +
                "c${adjustments.contrast}_" +
                "s${adjustments.saturation}_" +
                "e${adjustments.exposure}_" +
                "h${adjustments.highlights}_" +
                "sh${adjustments.shadows}_" +
                "w${adjustments.whites}_" +
                "bl${adjustments.blacks}_" +
                "cl${adjustments.clarity}_" +
                "v${adjustments.vibrance}_" +
                "wa${adjustments.warmth}_" +
                "t${adjustments.tint}_" +
                "fg${filmGrain.amount}_" +
                "vg${lensEffects.vignetteAmount}"
    }
    
    private fun applyAdjustments(bitmap: Bitmap, adjustments: AdjustmentParameters): Bitmap {
        // Skip if no adjustments needed
        if (
            adjustments.brightness == 0f &&
            adjustments.contrast == 0f &&
            adjustments.saturation == 0f &&
            adjustments.exposure == 0f
        ) {
            return bitmap
        }
        
        val colorMatrix = ColorMatrix().apply {
            // Exposure (acts as global gain)
            if (adjustments.exposure != 0f) {
                // UI provides exposure in [-1..1]; map to [0..2] gain
                val exposureFactor = 1f + adjustments.exposure
                postConcat(ColorMatrix(floatArrayOf(
                    exposureFactor, 0f, 0f, 0f, 0f,
                    0f, exposureFactor, 0f, 0f, 0f,
                    0f, 0f, exposureFactor, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            
            // Brightness
            // UI provides brightness in [-1..1]; directly scale to 255 offset
            val brightness = adjustments.brightness
            postConcat(ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, brightness * 255f,
                0f, 1f, 0f, 0f, brightness * 255f,
                0f, 0f, 1f, 0f, brightness * 255f,
                0f, 0f, 0f, 1f, 0f
            )))
            
            // Contrast
            // UI provides contrast in [-1..1]; map to [0..2] factor
            val contrast = 1f + adjustments.contrast
            val offset = (1f - contrast) * 0.5f * 255f
            postConcat(ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, offset,
                0f, contrast, 0f, 0f, offset,
                0f, 0f, contrast, 0f, offset,
                0f, 0f, 0f, 1f, 0f
            )))
            
            // Saturation
            // UI provides saturation in [-1..1]; map to [0..2] and clamp >= 0
            val saturation = (1f + adjustments.saturation).coerceAtLeast(0f)
            val satMatrix = ColorMatrix()
            satMatrix.setSaturation(saturation)
            postConcat(satMatrix)
        }
        
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        
        return result
    }
    
    private fun applyFilmGrain(bitmap: Bitmap, filmGrain: FilmGrain): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(bitmap.width * bitmap.height)
        result.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        val random = Random.Default
        // Fix for crash: ensure grainIntensity is at least 1
        val grainIntensity = (filmGrain.amount * 120f).toInt().coerceAtLeast(1)
        
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val alpha = (pixel shr 24) and 0xFF
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            
            // Apply grain with safety check
            val noise = if (grainIntensity > 0) {
                random.nextInt(grainIntensity * 2) - grainIntensity
            } else {
                0
            }
            
            val newRed = (red + noise).coerceIn(0, 255)
            val newGreen = (green + noise).coerceIn(0, 255)
            val newBlue = (blue + noise).coerceIn(0, 255)
            
            pixels[i] = (alpha shl 24) or (newRed shl 16) or (newGreen shl 8) or newBlue
        }
        
        result.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return result
    }
    
    private fun applyVignette(bitmap: Bitmap, lensEffects: LensEffects): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        
        val centerX = bitmap.width / 2f
        val centerY = bitmap.height / 2f
        val maxRadius = kotlin.math.sqrt((centerX * centerX + centerY * centerY).toDouble()).toFloat()
        
        val pixels = IntArray(bitmap.width * bitmap.height)
        result.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val dx = x - centerX
                val dy = y - centerY
                val distance = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                val normalizedDistance = distance / maxRadius
                
                val vignetteStrength = (normalizedDistance * lensEffects.vignetteAmount).coerceIn(0f, 1f)
                val darkenFactor = 1f - vignetteStrength
                
                val index = y * bitmap.width + x
                val pixel = pixels[index]
                val alpha = (pixel shr 24) and 0xFF
                val red = (((pixel shr 16) and 0xFF) * darkenFactor).toInt().coerceIn(0, 255)
                val green = (((pixel shr 8) and 0xFF) * darkenFactor).toInt().coerceIn(0, 255)
                val blue = ((pixel and 0xFF) * darkenFactor).toInt().coerceIn(0, 255)
                
                pixels[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        
        result.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return result
    }
    
    override suspend fun applyFilter(bitmap: Bitmap, filterName: String): Result<Bitmap> {
        return try {
            val filtered = when (filterName) {
                "Grayscale" -> applyGrayscaleFilter(bitmap)
                "Sepia" -> applySepiaFilter(bitmap)
                "Vintage" -> applyVintageFilter(bitmap)
                else -> bitmap
            }
            Result.success(filtered)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun applyGrayscaleFilter(bitmap: Bitmap): Bitmap {
        val colorMatrix = ColorMatrix().apply {
            setSaturation(0f)
        }
        return applyColorMatrix(bitmap, colorMatrix)
    }
    
    private fun applySepiaFilter(bitmap: Bitmap): Bitmap {
        val colorMatrix = ColorMatrix(floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        return applyColorMatrix(bitmap, colorMatrix)
    }
    
    private fun applyVintageFilter(bitmap: Bitmap): Bitmap {
        val colorMatrix = ColorMatrix(floatArrayOf(
            1.2f, 0.1f, 0.1f, 0f, 20f,
            0.1f, 1.1f, 0.1f, 0f, 10f,
            0.1f, 0.1f, 0.9f, 0f, 5f,
            0f, 0f, 0f, 1f, 0f
        ))
        return applyColorMatrix(bitmap, colorMatrix)
    }
    
    private fun applyColorMatrix(bitmap: Bitmap, colorMatrix: ColorMatrix): Bitmap {
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        
        return result
    }
    
    override suspend fun autoEnhance(bitmap: Bitmap): Result<Bitmap> {
        return try {
            val enhanced = applyAutoEnhancement(bitmap)
            Result.success(enhanced)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun applyAutoEnhancement(bitmap: Bitmap): Bitmap {
        // Enhance colors and overall photo quality (basic algorithm, not AI)
        val colorMatrix = ColorMatrix(floatArrayOf(
            1.1f, 0.05f, 0.05f, 0f, 10f,
            0.05f, 1.05f, 0.05f, 0f, 5f,
            0.05f, 0.05f, 0.95f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        return applyColorMatrix(bitmap, colorMatrix)
    }
    
    override suspend fun applySmoothing(bitmap: Bitmap): Result<Bitmap> {
        return try {
            val processed = applySmoothingFilter(bitmap)
            Result.success(processed)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun applySmoothingFilter(bitmap: Bitmap): Bitmap {
        // Basic smoothing using subtle blur (not AI)
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(bitmap.width * bitmap.height)
        result.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        // Apply subtle smoothing to reduce blemishes
        for (y in 1 until bitmap.height - 1) {
            for (x in 1 until bitmap.width - 1) {
                val index = y * bitmap.width + x
                val pixel = pixels[index]
                
                // Get surrounding pixels for averaging
                val neighbors = arrayOf(
                    pixels[(y-1) * bitmap.width + x],
                    pixels[(y+1) * bitmap.width + x],
                    pixels[y * bitmap.width + (x-1)],
                    pixels[y * bitmap.width + (x+1)]
                )
                
                val alpha = (pixel shr 24) and 0xFF
                val red = neighbors.map { (it shr 16) and 0xFF }.average().toInt()
                val green = neighbors.map { (it shr 8) and 0xFF }.average().toInt()
                val blue = neighbors.map { it and 0xFF }.average().toInt()
                
                // Blend with original (20% smoothing)
                val originalRed = (pixel shr 16) and 0xFF
                val originalGreen = (pixel shr 8) and 0xFF
                val originalBlue = pixel and 0xFF
                
                val blendedRed = (originalRed * 0.8f + red * 0.2f).toInt().coerceIn(0, 255)
                val blendedGreen = (originalGreen * 0.8f + green * 0.2f).toInt().coerceIn(0, 255)
                val blendedBlue = (originalBlue * 0.8f + blue * 0.2f).toInt().coerceIn(0, 255)
                
                pixels[index] = (alpha shl 24) or (blendedRed shl 16) or (blendedGreen shl 8) or blendedBlue
            }
        }
        
        result.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return result
    }
    
    override suspend fun applySoftening(bitmap: Bitmap, intensity: Float): Result<Bitmap> {
        return try {
            val smoothed = applySofteningFilter(bitmap, intensity)
            Result.success(smoothed)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun applySofteningFilter(bitmap: Bitmap, intensity: Float): Bitmap {
        val smoothingFactor = intensity.coerceIn(0f, 1f)
        
        // Apply softening using color matrix (basic algorithm, not AI)
        val colorMatrix = ColorMatrix(floatArrayOf(
            1f - smoothingFactor * 0.1f, smoothingFactor * 0.05f, smoothingFactor * 0.05f, 0f, 0f,
            smoothingFactor * 0.05f, 1f - smoothingFactor * 0.1f, smoothingFactor * 0.05f, 0f, 0f,
            smoothingFactor * 0.05f, smoothingFactor * 0.05f, 1f - smoothingFactor * 0.1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        
        return applyColorMatrix(bitmap, colorMatrix)
    }
}
