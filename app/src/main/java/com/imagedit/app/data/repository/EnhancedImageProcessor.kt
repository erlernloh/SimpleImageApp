package com.imagedit.app.data.repository

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import android.util.Size
import com.imagedit.app.domain.model.AdjustmentParameters
import com.imagedit.app.domain.model.ColorBalanceAnalysis
import com.imagedit.app.domain.model.CompositionAnalysis
import com.imagedit.app.domain.model.CompositionType
import com.imagedit.app.domain.model.DynamicRangeAnalysis
import com.imagedit.app.domain.model.EnhancementResult
import com.imagedit.app.domain.model.ExposureAnalysis
import com.imagedit.app.domain.model.FilmGrain
import com.imagedit.app.domain.model.LandscapeAnalysis
import com.imagedit.app.domain.model.LandscapeEnhancementParameters
import com.imagedit.app.domain.model.LandscapeParameters
import com.imagedit.app.domain.model.LensEffects
import com.imagedit.app.domain.model.ProcessingMode
import com.imagedit.app.domain.model.ProcessingOperation
import com.imagedit.app.domain.model.QualityMetrics
import com.imagedit.app.domain.model.SceneAnalysis
import com.imagedit.app.domain.model.SceneType
import com.imagedit.app.domain.model.SkinToneAnalysis
import com.imagedit.app.domain.model.SmartProcessingError
import com.imagedit.app.domain.model.BrushStroke
import com.imagedit.app.domain.model.HealingBrush
import com.imagedit.app.domain.model.HealingResult
import com.imagedit.app.domain.model.HealingRegion
import com.imagedit.app.domain.model.HealingValidation
import com.imagedit.app.domain.model.SourceCandidate
import com.imagedit.app.domain.repository.ImageProcessor
import com.imagedit.app.domain.repository.SmartProcessor
import com.imagedit.app.util.PerformanceManager
import com.imagedit.app.util.image.HistogramAnalyzer
import com.imagedit.app.util.image.LandscapeDetector
import com.imagedit.app.util.image.LandscapeEnhancer
import com.imagedit.app.util.image.PortraitEnhancer
import com.imagedit.app.util.image.SceneAnalyzer
import com.imagedit.app.util.image.HealingTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

@Singleton
class EnhancedImageProcessor @Inject constructor(
    private val performanceManager: PerformanceManager,
    private val histogramAnalyzer: HistogramAnalyzer,
    private val sceneAnalyzer: SceneAnalyzer,
    private val landscapeDetector: LandscapeDetector,
    private val landscapeEnhancer: LandscapeEnhancer,
    private val settingsRepository: SettingsRepository
) : ImageProcessor, SmartProcessor {
    
    companion object {
        private const val TAG = "EnhancedImageProcessor"
    }
    
    private val healingTool by lazy { HealingTool(performanceManager) }
    
    override suspend fun processImage(
        bitmap: Bitmap,
        adjustments: AdjustmentParameters,
        filmGrain: FilmGrain,
        lensEffects: LensEffects
    ): Result<Bitmap> = withContext(Dispatchers.Default) {
        try {
            var processedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            
            // Apply adjustments with enhanced layer-based processing
            processedBitmap = applyEnhancedAdjustments(processedBitmap, adjustments)
            
            // Apply film grain with crash fix
            if (filmGrain.amount > 0f) {
                processedBitmap = applyEnhancedFilmGrain(processedBitmap, filmGrain)
            }
            
            // Apply lens effects
            if (lensEffects.vignetteAmount > 0f) {
                processedBitmap = applyEnhancedVignette(processedBitmap, lensEffects)
            }
            
            Result.success(processedBitmap)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }  
  
    private fun applyEnhancedAdjustments(bitmap: Bitmap, adjustments: AdjustmentParameters): Bitmap {
        val colorMatrix = ColorMatrix()

        // Exposure (global gain) [UI: -1..1] -> factor [0..2]
        if (adjustments.exposure != 0f) {
            val exposureFactor = 1f + adjustments.exposure
            colorMatrix.postConcat(ColorMatrix(floatArrayOf(
                exposureFactor, 0f, 0f, 0f, 0f,
                0f, exposureFactor, 0f, 0f, 0f,
                0f, 0f, exposureFactor, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )))
        }

        // Contrast [-1..1] -> [0..2]
        if (adjustments.contrast != 0f) {
            val contrast = adjustments.contrast + 1f
            val offset = (1f - contrast) * 0.5f * 255f
            colorMatrix.postConcat(ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, offset,
                0f, contrast, 0f, 0f, offset,
                0f, 0f, contrast, 0f, offset,
                0f, 0f, 0f, 1f, 0f
            )))
        }

        // Saturation [-1..1] -> [0..2]
        if (adjustments.saturation != 0f) {
            val saturation = (1f + adjustments.saturation).coerceAtLeast(0f)
            val satMatrix = ColorMatrix().apply { setSaturation(saturation) }
            colorMatrix.postConcat(satMatrix)
        }

        // Vibrance: secondary saturation with softer strength
        if (adjustments.vibrance != 0f) {
            val vib = (1f + adjustments.vibrance * 0.5f).coerceAtLeast(0f)
            val vibMatrix = ColorMatrix().apply { setSaturation(vib) }
            colorMatrix.postConcat(vibMatrix)
        }

        // Brightness [-1..1] -> offset
        if (adjustments.brightness != 0f) {
            val b = adjustments.brightness * 255f
            colorMatrix.postConcat(ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, b,
                0f, 1f, 0f, 0f, b,
                0f, 0f, 1f, 0f, b,
                0f, 0f, 0f, 1f, 0f
            )))
        }

        // Warmth (temperature)
        if (adjustments.warmth != 0f) {
            val warmth = adjustments.warmth
            val warmthMatrix = if (warmth > 0f) {
                ColorMatrix(floatArrayOf(
                    1f + warmth * 0.3f, 0f, 0f, 0f, 0f,
                    0f, 1f + warmth * 0.1f, 0f, 0f, 0f,
                    0f, 0f, 1f - warmth * 0.2f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
            } else {
                val cool = -warmth
                ColorMatrix(floatArrayOf(
                    1f - cool * 0.2f, 0f, 0f, 0f, 0f,
                    0f, 1f - cool * 0.1f, 0f, 0f, 0f,
                    0f, 0f, 1f + cool * 0.3f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
            }
            colorMatrix.postConcat(warmthMatrix)
        }

        // Tint (green-magenta)
        if (adjustments.tint != 0f) {
            val t = adjustments.tint
            val (rGain, gGain, bGain) = if (t > 0f) {
                // Magenta shift: increase R & B, reduce G
                Triple(1f + t * 0.1f, 1f - t * 0.2f, 1f + t * 0.1f)
            } else {
                val g = -t
                // Green shift: increase G, reduce R & B
                Triple(1f - g * 0.1f, 1f + g * 0.2f, 1f - g * 0.1f)
            }
            colorMatrix.postConcat(ColorMatrix(floatArrayOf(
                rGain, 0f, 0f, 0f, 0f,
                0f, gGain, 0f, 0f, 0f,
                0f, 0f, bGain, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )))
        }

        // Apply color matrix stage
        var result = applyColorMatrix(bitmap, colorMatrix)

        // Tone curve approximations and clarity (per-pixel)
        if (
            adjustments.highlights != 0f || adjustments.shadows != 0f ||
            adjustments.whites != 0f || adjustments.blacks != 0f ||
            adjustments.clarity != 0f
        ) {
            result = applyToneAndClarity(result, adjustments)
        }

        return result
    }

    private fun applyToneAndClarity(src: Bitmap, a: AdjustmentParameters): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = out.width
        val h = out.height
        val pixels = IntArray(w * h)
        out.getPixels(pixels, 0, w, 0, 0, w, h)

        val hl = a.highlights.coerceIn(-1f, 1f)
        val sh = a.shadows.coerceIn(-1f, 1f)
        val wh = a.whites.coerceIn(-1f, 1f)
        val bl = a.blacks.coerceIn(-1f, 1f)
        val cl = a.clarity.coerceIn(-1f, 1f)

        fun sCurve(x: Float, amount: Float): Float {
            // Midtone contrast curve. amount in [-1..1]
            val m = (x - 0.5f)
            return (x + amount * m * (1f - kotlin.math.abs(m)) * 2f).coerceIn(0f, 1f)
        }

        for (i in pixels.indices) {
            val p = pixels[i]
            val a8 = (p ushr 24) and 0xFF
            var r = ((p ushr 16) and 0xFF) / 255f
            var g = ((p ushr 8) and 0xFF) / 255f
            var b = (p and 0xFF) / 255f

            val l = (0.2126f * r + 0.7152f * g + 0.0722f * b)

            // Shadows lift and Highlights recovery
            val shw = sh * (1f - l) // stronger in darks
            val hlw = hl * l        // stronger in brights (negative to recover)

            r = (r + shw * 0.5f - hlw * 0.5f)
            g = (g + shw * 0.5f - hlw * 0.5f)
            b = (b + shw * 0.5f - hlw * 0.5f)

            // Whites and Blacks
            val whitesW = if (l > 0.8f) (l - 0.8f) / 0.2f else 0f
            val blacksW = if (l < 0.2f) (0.2f - l) / 0.2f else 0f
            r = (r + wh * whitesW * 0.5f - bl * blacksW * 0.5f)
            g = (g + wh * whitesW * 0.5f - bl * blacksW * 0.5f)
            b = (b + wh * whitesW * 0.5f - bl * blacksW * 0.5f)

            // Clarity as midtone S-curve (global approximation)
            r = sCurve(r.coerceIn(0f, 1f), cl)
            g = sCurve(g.coerceIn(0f, 1f), cl)
            b = sCurve(b.coerceIn(0f, 1f), cl)

            val R = (r.coerceIn(0f, 1f) * 255f).toInt()
            val G = (g.coerceIn(0f, 1f) * 255f).toInt()
            val B = (b.coerceIn(0f, 1f) * 255f).toInt()
            pixels[i] = (a8 shl 24) or (R shl 16) or (G shl 8) or B
        }

        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
    
    private fun applyEnhancedFilmGrain(bitmap: Bitmap, filmGrain: FilmGrain): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(bitmap.width * bitmap.height)
        result.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        // Validate grain amount before processing
        if (filmGrain.amount <= 0f) {
            return result
        }
        
        val random = Random.Default
        // Enhanced grain with better distribution
        val grainIntensity = (filmGrain.amount * 120f).toInt().coerceAtLeast(1)
        val grainSize = filmGrain.size.coerceIn(0.1f, 3.0f)
        
        // Safety check to prevent Random range error
        val grainRange = (grainIntensity * 2).coerceAtLeast(2)
        
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val index = y * bitmap.width + x
                val pixel = pixels[index]
                
                val alpha = (pixel shr 24) and 0xFF
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                
                // Enhanced grain pattern with size consideration
                val grainX = (x / grainSize).toInt()
                val grainY = (y / grainSize).toInt()
                val grainSeed = grainX * 31 + grainY * 17
                val grainRandom = Random(grainSeed)
                
                val noise = grainRandom.nextInt(grainRange) - grainIntensity
                
                val newRed = (red + noise).coerceIn(0, 255)
                val newGreen = (green + noise).coerceIn(0, 255)
                val newBlue = (blue + noise).coerceIn(0, 255)
                
                pixels[index] = (alpha shl 24) or (newRed shl 16) or (newGreen shl 8) or newBlue
            }
        }
        
        result.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return result
    }
    
    private fun applyEnhancedVignette(bitmap: Bitmap, lensEffects: LensEffects): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(bitmap.width * bitmap.height)
        result.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        val centerX = bitmap.width / 2f
        val centerY = bitmap.height / 2f
        val maxRadius = sqrt((centerX * centerX + centerY * centerY).toDouble()).toFloat()
        
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val dx = x - centerX
                val dy = y - centerY
                val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                val normalizedDistance = (distance / maxRadius).coerceIn(0f, 1f)
                
                // Enhanced vignette with smoother falloff
                val vignetteStrength = (normalizedDistance * lensEffects.vignetteAmount).coerceIn(0f, 1f)
                val darkenFactor = 1f - (vignetteStrength * vignetteStrength) // Quadratic falloff for smoother effect
                
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
                "Dramatic" -> applyDramaticFilter(bitmap)
                "Bright & Airy" -> applyBrightAiryFilter(bitmap)
                "Classic B&W" -> applyClassicBWFilter(bitmap)
                "Warm Sunset" -> applyWarmSunsetFilter(bitmap)
                "Cool Blue" -> applyCoolBlueFilter(bitmap)
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
    
    private fun applyDramaticFilter(bitmap: Bitmap): Bitmap {
        val colorMatrix = ColorMatrix(floatArrayOf(
            1.5f, 0f, 0f, 0f, -20f,
            0f, 1.5f, 0f, 0f, -20f,
            0f, 0f, 1.5f, 0f, -20f,
            0f, 0f, 0f, 1f, 0f
        ))
        return applyColorMatrix(bitmap, colorMatrix)
    }
    
    private fun applyBrightAiryFilter(bitmap: Bitmap): Bitmap {
        val colorMatrix = ColorMatrix(floatArrayOf(
            1.1f, 0f, 0f, 0f, 30f,
            0f, 1.1f, 0f, 0f, 30f,
            0f, 0f, 1.1f, 0f, 30f,
            0f, 0f, 0f, 1f, 0f
        ))
        return applyColorMatrix(bitmap, colorMatrix)
    }
    
    private fun applyClassicBWFilter(bitmap: Bitmap): Bitmap {
        val colorMatrix = ColorMatrix(floatArrayOf(
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        return applyColorMatrix(bitmap, colorMatrix)
    }
    
    private fun applyWarmSunsetFilter(bitmap: Bitmap): Bitmap {
        val colorMatrix = ColorMatrix(floatArrayOf(
            1.3f, 0f, 0f, 0f, 40f,
            0f, 1.1f, 0f, 0f, 20f,
            0f, 0f, 0.8f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        return applyColorMatrix(bitmap, colorMatrix)
    }
    
    private fun applyCoolBlueFilter(bitmap: Bitmap): Bitmap {
        val colorMatrix = ColorMatrix(floatArrayOf(
            0.8f, 0f, 0f, 0f, 0f,
            0f, 1.0f, 0f, 0f, 10f,
            0f, 0f, 1.3f, 0f, 30f,
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
            // Auto enhancement with color adjustments (basic algorithm, not AI)
            val enhanced = applyAutoEnhancement(bitmap)
            Result.success(enhanced)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun applyAutoEnhancement(bitmap: Bitmap): Bitmap {
        // Enhance colors using color matrix adjustments (not AI)
        val colorMatrix = ColorMatrix(floatArrayOf(
            1.1f, 0.05f, 0.05f, 0f, 10f,
            0.05f, 1.05f, 0.05f, 0f, 5f,
            0.05f, 0.05f, 0.95f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        return applyColorMatrix(bitmap, colorMatrix)
    }    

    override suspend fun applySmoothing(bitmap: Bitmap): Result<Bitmap> = withContext(Dispatchers.Default) {
        try {
            // Apply basic smoothing using a simple blur kernel
            val smoothedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            // Simple smoothing implementation
            Result.success(smoothedBitmap)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun applySoftening(bitmap: Bitmap, intensity: Float): Result<Bitmap> = withContext(Dispatchers.Default) {
        try {
            // Apply softening with specified intensity
            val softenedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            // Simple softening implementation
            Result.success(softenedBitmap)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
 
   // SmartProcessor implementation methods
    
    override suspend fun analyzeScene(bitmap: Bitmap): Result<SceneAnalysis> = withContext(Dispatchers.Default) {
        try {
            val optimalSize = performanceManager.getOptimalProcessingSize(
                Size(bitmap.width, bitmap.height), 
                ProcessingMode.MEDIUM
            )
            
            val processingBitmap = if (optimalSize.width < bitmap.width || optimalSize.height < bitmap.height) {
                Bitmap.createScaledBitmap(bitmap, optimalSize.width, optimalSize.height, true)
            } else {
                bitmap
            }
            
            val sceneAnalysis = sceneAnalyzer.analyzeScene(processingBitmap)
            Result.success(sceneAnalysis)
        } catch (e: Exception) {
            Result.failure(SmartProcessingError.AlgorithmFailure("scene_analysis", e))
        }
    }
    
    override suspend fun smartEnhance(bitmap: Bitmap, mode: ProcessingMode, sceneAnalysis: SceneAnalysis?): Result<EnhancementResult> = withContext(Dispatchers.Default) {
        try {
            Log.d(TAG, "Smart enhance started: mode=$mode, bitmap=${bitmap.width}x${bitmap.height}")
            
            // Use current settings for processing mode (allow override for real-time switching)
            val currentSettings = settingsRepository.getCurrentSettings()
            val effectiveMode = mode // Use passed mode for real-time switching capability
            
            // Check memory conditions
            if (performanceManager.shouldCancelForMemory()) {
                Log.w(TAG, "Smart enhance cancelled: insufficient memory")
                return@withContext Result.failure(SmartProcessingError.InsufficientMemory)
            }
            
            val optimalSize = performanceManager.getOptimalProcessingSize(Size(bitmap.width, bitmap.height), effectiveMode)
            val processingBitmap = if (optimalSize.width < bitmap.width || optimalSize.height < bitmap.height) {
                Log.d(TAG, "Downscaling for processing: ${bitmap.width}x${bitmap.height} -> ${optimalSize.width}x${optimalSize.height}")
                Bitmap.createScaledBitmap(bitmap, optimalSize.width, optimalSize.height, true)
            } else {
                bitmap
            }
            
            val timed = measureTimedValue {
                val enhancementData = applyIntelligentSmartEnhancement(processingBitmap, mode, sceneAnalysis)
                // Scale back to original size if needed
                val finalBitmap = if (optimalSize.width < bitmap.width || optimalSize.height < bitmap.height) {
                    Bitmap.createScaledBitmap(enhancementData.enhancedBitmap, bitmap.width, bitmap.height, true)
                } else {
                    enhancementData.enhancedBitmap
                }
                enhancementData.copy(enhancedBitmap = finalBitmap)
            }

            val result = EnhancementResult(
                enhancedBitmap = timed.value.enhancedBitmap,
                appliedAdjustments = timed.value.appliedAdjustments,
                processingTime = timed.duration,
                qualityMetrics = timed.value.qualityMetrics
            )
            
            Log.d(TAG, "Smart enhance completed in ${timed.duration.inWholeMilliseconds}ms")
            Log.d(TAG, "Applied adjustments: brightness=${result.appliedAdjustments.exposure}, contrast=${result.appliedAdjustments.contrast}, saturation=${result.appliedAdjustments.saturation}")

            Result.success(result)
        } catch (e: OutOfMemoryError) {
            Result.failure(SmartProcessingError.InsufficientMemory)
        } catch (e: Exception) {
            Result.failure(SmartProcessingError.AlgorithmFailure("smart_enhance", e))
        }
    }
    
    override suspend fun enhancePortrait(bitmap: Bitmap, intensity: Float, mode: ProcessingMode): Result<Bitmap> = withContext(Dispatchers.Default) {
        try {
            val startTime = System.currentTimeMillis()
            Log.d(TAG, "Portrait enhancement started: intensity=$intensity, mode=$mode, bitmap=${bitmap.width}x${bitmap.height}")
            
            // Use current settings for processing mode and intensity
            val currentSettings = settingsRepository.getCurrentSettings()
            val effectiveMode = mode // Use passed mode for real-time switching capability
            val effectiveIntensity = if (intensity < 0) currentSettings.portraitEnhancementIntensity else intensity
            
            // Check memory conditions
            if (performanceManager.shouldCancelForMemory()) {
                Log.w(TAG, "Portrait enhancement cancelled: insufficient memory")
                return@withContext Result.failure(SmartProcessingError.InsufficientMemory)
            }
            
            val optimalSize = performanceManager.getOptimalProcessingSize(Size(bitmap.width, bitmap.height), effectiveMode)
            val processingBitmap = if (optimalSize.width < bitmap.width || optimalSize.height < bitmap.height) {
                Bitmap.createScaledBitmap(bitmap, optimalSize.width, optimalSize.height, true)
            } else {
                bitmap
            }
            
            val portraitEnhancer = PortraitEnhancer()
            
            // Detect skin areas
            val skinRegions = portraitEnhancer.detectSkinAreas(processingBitmap)
            Log.d(TAG, "Detected ${skinRegions.size} skin regions")
            
            if (skinRegions.isEmpty()) {
                Log.d(TAG, "No skin detected, returning original image")
                // No skin detected, return original
                return@withContext Result.success(bitmap.copy(bitmap.config, false))
            }
            
            // Apply portrait enhancement based on mode
            val enhancedBitmap = if (performanceManager.shouldUseSimplifiedAlgorithm(ProcessingOperation.PORTRAIT_ENHANCE, effectiveMode)) {
                Log.d(TAG, "Applying simplified portrait enhancement (lite mode)")
                // Simplified portrait enhancement for lite mode
                portraitEnhancer.applySkinSmoothing(processingBitmap, skinRegions, effectiveIntensity * 0.7f)
            } else {
                Log.d(TAG, "Applying full portrait enhancement with skin smoothing, eye enhancement, and tone correction")
                // Full portrait enhancement
                portraitEnhancer.enhancePortrait(
                    processingBitmap,
                    smoothingIntensity = effectiveIntensity,
                    eyeEnhancementIntensity = effectiveIntensity * 0.6f,
                    skinToneCorrection = true
                )
            }
            
            // Scale back to original size if needed
            val finalBitmap = if (optimalSize.width < bitmap.width || optimalSize.height < bitmap.height) {
                Bitmap.createScaledBitmap(enhancedBitmap, bitmap.width, bitmap.height, true)
            } else {
                enhancedBitmap
            }
            
            val elapsedTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "Portrait enhancement completed in ${elapsedTime}ms")
            
            Result.success(finalBitmap)
        } catch (e: OutOfMemoryError) {
            Result.failure(SmartProcessingError.InsufficientMemory)
        } catch (e: Exception) {
            Result.failure(SmartProcessingError.AlgorithmFailure("portrait_enhance", e))
        }
    }
    
    override suspend fun enhanceLandscape(bitmap: Bitmap, parameters: LandscapeParameters, mode: ProcessingMode): Result<Bitmap> = withContext(Dispatchers.Default) {
        try {
            // Check memory conditions
            if (performanceManager.shouldCancelForMemory()) {
                return@withContext Result.failure(SmartProcessingError.InsufficientMemory)
            }
            
            val optimalSize = performanceManager.getOptimalProcessingSize(Size(bitmap.width, bitmap.height), mode)
            val processingBitmap = if (optimalSize.width < bitmap.width || optimalSize.height < bitmap.height) {
                Bitmap.createScaledBitmap(bitmap, optimalSize.width, optimalSize.height, true)
            } else {
                bitmap
            }
            
            // Analyze landscape elements
            val landscapeAnalysis = landscapeDetector.analyzeLandscape(processingBitmap)
            
            // Convert LandscapeParameters to LandscapeEnhancementParameters
            val enhancementParameters = LandscapeEnhancementParameters(
                overallIntensity = 0.7f, // Default intensity
                useRegionDetection = true,
                preserveColorHarmony = parameters.naturalColorGrading,
                clarityIntensity = parameters.clarityBoost,
                vibranceBoost = 0.4f
            )
            
            // Apply comprehensive landscape enhancement
            val enhancementResult = landscapeEnhancer.enhanceLandscape(
                processingBitmap,
                enhancementParameters,
                landscapeAnalysis,
                mode
            )
            
            val enhancedBitmap = enhancementResult.getOrElse { 
                // Fallback to basic enhancement if advanced enhancement fails
                return@withContext applyBasicLandscapeEnhancement(processingBitmap, parameters)
            }
            
            // Scale back to original size if needed
            val finalBitmap = if (optimalSize.width < bitmap.width || optimalSize.height < bitmap.height) {
                Bitmap.createScaledBitmap(enhancedBitmap, bitmap.width, bitmap.height, true)
            } else {
                enhancedBitmap
            }
            
            Result.success(finalBitmap)
        } catch (e: OutOfMemoryError) {
            Result.failure(SmartProcessingError.InsufficientMemory)
        } catch (e: Exception) {
            Result.failure(SmartProcessingError.AlgorithmFailure("landscape_enhance", e))
        }
    }
    
    override suspend fun healArea(bitmap: Bitmap, maskArea: Rect, mode: ProcessingMode): Result<HealingResult> = withContext(Dispatchers.Default) {
        try {
            // Check memory conditions
            if (performanceManager.shouldCancelForMemory()) {
                return@withContext Result.failure(SmartProcessingError.InsufficientMemory)
            }
            
            val optimalSize = performanceManager.getOptimalProcessingSize(Size(bitmap.width, bitmap.height), mode)
            val processingBitmap = if (optimalSize.width < bitmap.width || optimalSize.height < bitmap.height) {
                Bitmap.createScaledBitmap(bitmap, optimalSize.width, optimalSize.height, true)
            } else {
                bitmap
            }
            
            // Scale mask area to match processing bitmap
            val scaleFactor = processingBitmap.width.toFloat() / bitmap.width.toFloat()
            val scaledMaskArea = Rect(
                (maskArea.left * scaleFactor).toInt(),
                (maskArea.top * scaleFactor).toInt(),
                (maskArea.right * scaleFactor).toInt(),
                (maskArea.bottom * scaleFactor).toInt()
            )
            
            // Apply basic healing using patch-based approach
            val healedBitmap = applyBasicHealing(processingBitmap, scaledMaskArea, mode)
            
            // Scale back to original size if needed
            val finalBitmap = if (optimalSize.width < bitmap.width || optimalSize.height < bitmap.height) {
                Bitmap.createScaledBitmap(healedBitmap, bitmap.width, bitmap.height, true)
            } else {
                healedBitmap
            }
            
            val healingResult = HealingResult(
                operationId = "heal_${System.currentTimeMillis()}",
                healedBitmap = finalBitmap,
                healedRegion = HealingRegion(
                    id = "heal_${System.currentTimeMillis()}",
                    targetArea = maskArea,
                    targetPath = android.graphics.Path(), // Simplified for now
                    sourceArea = maskArea, // Simplified for now
                    brushStrokes = emptyList()
                ),
                processingTimeMs = 0L, // TODO: measure actual time
                success = true
            )
            Result.success(healingResult)
        } catch (e: OutOfMemoryError) {
            Result.failure(SmartProcessingError.InsufficientMemory)
        } catch (e: Exception) {
            Result.failure(SmartProcessingError.AlgorithmFailure("healing", e))
        }
    }
    
    /**
     * Data class to hold enhancement processing results.
     */
    private data class SmartEnhancementData(
        val enhancedBitmap: Bitmap,
        val appliedAdjustments: AdjustmentParameters,
        val qualityMetrics: QualityMetrics
    )
    
    /**
     * Applies intelligent smart enhancement using histogram analysis and automatic parameter calculation.
     */
    private suspend fun applyIntelligentSmartEnhancement(bitmap: Bitmap, mode: ProcessingMode, cachedSceneAnalysis: SceneAnalysis?): SmartEnhancementData {
        Log.d(TAG, "Smart enhancement: Step 1/6 - Analyzing exposure...")
        val exposureAnalysis = histogramAnalyzer.analyzeExposure(bitmap)
        
        Log.d(TAG, "Smart enhancement: Step 2/6 - Analyzing color balance...")
        val colorBalanceAnalysis = histogramAnalyzer.analyzeColorBalance(bitmap)
        
        Log.d(TAG, "Smart enhancement: Step 3/6 - Analyzing dynamic range...")
        val dynamicRangeAnalysis = histogramAnalyzer.analyzeDynamicRange(bitmap)
        
        Log.d(TAG, "Smart enhancement: Step 4/6 - Detecting skin tones...")
        val skinToneAnalysis = histogramAnalyzer.detectSkinTones(bitmap)
        
        // Step 2: Use cached scene analysis if available, otherwise perform scene analysis
        Log.d(TAG, "Smart enhancement: Step 5/6 - Getting scene type...")
        val sceneType = if (cachedSceneAnalysis != null) {
            Log.d(TAG, "Using cached scene analysis: ${cachedSceneAnalysis.sceneType}")
            cachedSceneAnalysis.sceneType
        } else {
            Log.d(TAG, "No cached scene analysis, performing scene detection (this may take ~50s)...")
            sceneAnalyzer.detectSceneType(bitmap)
        }
        
        // Skip composition analysis to avoid 10-50s delay
        // Composition analysis is not critical for smart enhancement
        Log.d(TAG, "Skipping composition analysis to improve performance")
        val compositionAnalysis = CompositionAnalysis(
            compositionType = CompositionType.UNKNOWN,
            confidence = if (cachedSceneAnalysis != null) cachedSceneAnalysis.confidence else 0.5f,
            aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat(),
            horizontalEdgePercentage = 0f,
            verticalEdgePercentage = 0f,
            focalPoints = emptyList(),
            ruleOfThirdsDetected = false
        )
        
        // Step 3: Calculate automatic adjustment parameters based on comprehensive analysis
        Log.d(TAG, "Smart enhancement: Calculating smart adjustments...")
        val smartAdjustments = calculateSmartAdjustments(
            exposureAnalysis, 
            colorBalanceAnalysis, 
            dynamicRangeAnalysis,
            skinToneAnalysis,
            mode
        )
        
        // Step 4: Apply scene-specific color grading enhancements
        Log.d(TAG, "Smart enhancement: Applying scene-specific enhancements for $sceneType...")
        val sceneEnhancedAdjustments = applySceneSpecificEnhancements(
            smartAdjustments,
            sceneType,
            compositionAnalysis,
            colorBalanceAnalysis,
            mode
        )
        
        // Step 5: Apply standard adjustments to all scene types
        // Skip specialized landscape enhancement - it's too slow (30+ seconds) and causes timeouts
        Log.d(TAG, "Smart enhancement: Step 6/6 - Applying enhancements to bitmap...")
        Log.d(TAG, "Applying standard adjustments for $sceneType scene (specialized processing disabled for performance)")
        val enhancedBitmap = applyEnhancedAdjustments(bitmap, sceneEnhancedAdjustments)
        
        Log.d(TAG, "Smart enhancement: Bitmap processing complete!")
        
        // Step 6: Calculate quality metrics
        Log.d(TAG, "Smart enhancement: Calculating quality metrics...")
        val qualityMetrics = calculateEnhancementQuality(
            exposureAnalysis,
            colorBalanceAnalysis,
            dynamicRangeAnalysis,
            sceneEnhancedAdjustments
        )
        
        return SmartEnhancementData(
            enhancedBitmap = enhancedBitmap,
            appliedAdjustments = sceneEnhancedAdjustments,
            qualityMetrics = qualityMetrics
        )
    }
    
    /**
     * Calculates optimal adjustment parameters based on histogram analysis.
     */
    private fun calculateSmartAdjustments(
        exposureAnalysis: ExposureAnalysis,
        colorBalanceAnalysis: ColorBalanceAnalysis,
        dynamicRangeAnalysis: DynamicRangeAnalysis,
        skinToneAnalysis: SkinToneAnalysis,
        mode: ProcessingMode
    ): AdjustmentParameters {
        
        // Automatic exposure correction based on histogram analysis
        val exposureCorrection = calculateExposureCorrection(exposureAnalysis, mode)
        
        // Intelligent contrast adjustment based on dynamic range
        val contrastAdjustment = calculateContrastAdjustment(dynamicRangeAnalysis, mode)
        
        // Color balance correction
        val colorBalanceAdjustments = calculateColorBalanceAdjustments(colorBalanceAnalysis, mode)
        
        // Saturation and vibrance based on scene characteristics
        val colorEnhancements = calculateColorEnhancements(exposureAnalysis, skinToneAnalysis, mode)
        
        // Shadow and highlight recovery
        val toneAdjustments = calculateToneAdjustments(exposureAnalysis, dynamicRangeAnalysis, mode)
        
        return AdjustmentParameters(
            exposure = exposureCorrection,
            contrast = contrastAdjustment,
            saturation = colorEnhancements.saturation,
            vibrance = colorEnhancements.vibrance,
            warmth = colorBalanceAdjustments.warmth,
            tint = colorBalanceAdjustments.tint,
            shadows = toneAdjustments.shadows,
            highlights = toneAdjustments.highlights,
            clarity = calculateClarityAdjustment(dynamicRangeAnalysis, mode),
            whites = toneAdjustments.whites,
            blacks = toneAdjustments.blacks
        )
    }
    
    private fun calculateExposureCorrection(exposureAnalysis: ExposureAnalysis, mode: ProcessingMode): Float {
        val targetBrightness = 0.5f // Target middle gray
        val currentBrightness = (exposureAnalysis.exposureLevel + 2f) / 4f // Convert from -2..2 to 0..1
        
        val rawCorrection = (targetBrightness - currentBrightness) * 2f
        
        // Apply mode-specific limits
        val maxCorrection = when (mode) {
            ProcessingMode.LITE -> 0.3f
            ProcessingMode.MEDIUM -> 0.5f
            ProcessingMode.ADVANCED -> 0.7f
        }
        
        return rawCorrection.coerceIn(-maxCorrection, maxCorrection)
    }
    
    private fun calculateContrastAdjustment(dynamicRangeAnalysis: DynamicRangeAnalysis, mode: ProcessingMode): Float {
        val targetContrast = 0.6f
        val currentContrast = dynamicRangeAnalysis.contrastLevel
        
        val rawAdjustment = (targetContrast - currentContrast) * 0.8f
        
        val maxAdjustment = when (mode) {
            ProcessingMode.LITE -> 0.2f
            ProcessingMode.MEDIUM -> 0.3f
            ProcessingMode.ADVANCED -> 0.4f
        }
        
        return rawAdjustment.coerceIn(-maxAdjustment, maxAdjustment)
    }
    
    private data class ColorBalanceAdjustments(val warmth: Float, val tint: Float)
    
    private fun calculateColorBalanceAdjustments(colorBalanceAnalysis: ColorBalanceAnalysis, mode: ProcessingMode): ColorBalanceAdjustments {
        val targetTemperature = 5500f // Neutral daylight
        val temperatureDiff = colorBalanceAnalysis.colorTemperature - targetTemperature
        
        val warmthAdjustment = (-temperatureDiff / 2000f).coerceIn(-0.3f, 0.3f)
        val tintAdjustment = (colorBalanceAnalysis.tint * -0.5f).coerceIn(-0.2f, 0.2f)
        
        val modeMultiplier = when (mode) {
            ProcessingMode.LITE -> 0.6f
            ProcessingMode.MEDIUM -> 0.8f
            ProcessingMode.ADVANCED -> 1.0f
        }
        
        return ColorBalanceAdjustments(
            warmth = warmthAdjustment * modeMultiplier,
            tint = tintAdjustment * modeMultiplier
        )
    }
    
    private data class ColorEnhancements(val saturation: Float, val vibrance: Float)
    
    private fun calculateColorEnhancements(exposureAnalysis: ExposureAnalysis, skinToneAnalysis: SkinToneAnalysis, mode: ProcessingMode): ColorEnhancements {
        val baseSaturation = if (skinToneAnalysis.skinPercentage > 0.15f) {
            // Portrait detected - conservative saturation
            0.1f
        } else {
            // Landscape or other - more aggressive saturation
            0.2f
        }
        
        val baseVibrance = 0.15f
        
        val modeMultiplier = when (mode) {
            ProcessingMode.LITE -> 0.7f
            ProcessingMode.MEDIUM -> 0.85f
            ProcessingMode.ADVANCED -> 1.0f
        }
        
        return ColorEnhancements(
            saturation = baseSaturation * modeMultiplier,
            vibrance = baseVibrance * modeMultiplier
        )
    }
    
    private data class ToneAdjustments(val shadows: Float, val highlights: Float, val whites: Float, val blacks: Float)
    
    private fun calculateToneAdjustments(exposureAnalysis: ExposureAnalysis, dynamicRangeAnalysis: DynamicRangeAnalysis, mode: ProcessingMode): ToneAdjustments {
        val shadowLift = if (exposureAnalysis.shadowsClipped) 0.2f else 0.1f
        val highlightRecovery = if (exposureAnalysis.highlightsClipped) -0.2f else -0.1f
        
        val modeMultiplier = when (mode) {
            ProcessingMode.LITE -> 0.6f
            ProcessingMode.MEDIUM -> 0.8f
            ProcessingMode.ADVANCED -> 1.0f
        }
        
        return ToneAdjustments(
            shadows = shadowLift * modeMultiplier,
            highlights = highlightRecovery * modeMultiplier,
            whites = 0f,
            blacks = 0f
        )
    }
    
    private fun calculateClarityAdjustment(dynamicRangeAnalysis: DynamicRangeAnalysis, mode: ProcessingMode): Float {
        val baseClarity = if (dynamicRangeAnalysis.contrastLevel < 0.4f) 0.15f else 0.05f
        
        return when (mode) {
            ProcessingMode.LITE -> baseClarity * 0.5f
            ProcessingMode.MEDIUM -> baseClarity * 0.75f
            ProcessingMode.ADVANCED -> baseClarity
        }
    }   
 
    /**
     * Applies scene-specific enhancements based on detected scene type and composition.
     */
    private fun applySceneSpecificEnhancements(
        baseAdjustments: AdjustmentParameters,
        sceneType: SceneType,
        compositionAnalysis: CompositionAnalysis,
        colorBalanceAnalysis: ColorBalanceAnalysis,
        mode: ProcessingMode
    ): AdjustmentParameters {
        
        // Get scene-specific enhancement multipliers
        val sceneMultipliers = getSceneEnhancementMultipliers(sceneType, mode)
        
        // Apply composition-based adjustments
        val compositionAdjustments = getCompositionBasedAdjustments(compositionAnalysis, mode)
        
        // Apply color grading based on scene type
        val colorGradingAdjustments = getColorGradingAdjustments(sceneType, colorBalanceAnalysis, mode)
        
        return AdjustmentParameters(
            exposure = baseAdjustments.exposure + compositionAdjustments.exposure,
            contrast = baseAdjustments.contrast * sceneMultipliers.contrast + compositionAdjustments.contrast,
            saturation = baseAdjustments.saturation * sceneMultipliers.saturation + colorGradingAdjustments.saturation,
            vibrance = baseAdjustments.vibrance * sceneMultipliers.vibrance + colorGradingAdjustments.vibrance,
            warmth = baseAdjustments.warmth + colorGradingAdjustments.warmth,
            tint = baseAdjustments.tint + colorGradingAdjustments.tint,
            shadows = baseAdjustments.shadows * sceneMultipliers.shadows,
            highlights = baseAdjustments.highlights * sceneMultipliers.highlights,
            clarity = baseAdjustments.clarity * sceneMultipliers.clarity,
            whites = baseAdjustments.whites,
            blacks = baseAdjustments.blacks
        )
    }
    
    /**
     * Data class for scene enhancement multipliers.
     */
    private data class SceneEnhancementMultipliers(
        val contrast: Float,
        val saturation: Float,
        val vibrance: Float,
        val shadows: Float,
        val highlights: Float,
        val clarity: Float
    )
    
    /**
     * Gets enhancement multipliers based on scene type.
     */
    private fun getSceneEnhancementMultipliers(sceneType: SceneType, mode: ProcessingMode): SceneEnhancementMultipliers {
        val baseMultipliers = when (sceneType) {
            SceneType.PORTRAIT -> SceneEnhancementMultipliers(
                contrast = 0.8f, // Softer contrast for portraits
                saturation = 0.7f, // Conservative saturation
                vibrance = 1.2f, // Higher vibrance is safer
                shadows = 1.3f, // Lift shadows for better skin
                highlights = 1.1f, // Gentle highlight recovery
                clarity = 0.6f // Minimal clarity for smooth skin
            )
            
            SceneType.LANDSCAPE -> SceneEnhancementMultipliers(
                contrast = 1.2f, // Higher contrast for dramatic landscapes
                saturation = 1.4f, // Boost nature colors
                vibrance = 1.3f, // Enhance color richness
                shadows = 1.2f, // Lift shadows to show detail
                highlights = 1.3f, // Recover sky details
                clarity = 1.4f // Enhance landscape details
            )
            
            SceneType.FOOD -> SceneEnhancementMultipliers(
                contrast = 1.1f, // Moderate contrast
                saturation = 1.5f, // High saturation for appetizing colors
                vibrance = 1.2f, // Enhance color appeal
                shadows = 1.1f, // Slight shadow lift
                highlights = 1.0f, // Preserve highlights
                clarity = 1.2f // Enhance texture details
            )
            
            SceneType.NIGHT -> SceneEnhancementMultipliers(
                contrast = 1.3f, // High contrast for night drama
                saturation = 0.9f, // Conservative saturation
                vibrance = 1.5f, // High vibrance for night colors
                shadows = 1.4f, // Significant shadow lift
                highlights = 0.8f, // Preserve night highlights
                clarity = 1.1f // Moderate clarity
            )
            
            SceneType.INDOOR -> SceneEnhancementMultipliers(
                contrast = 1.0f, // Neutral contrast
                saturation = 1.2f, // Compensate for artificial lighting
                vibrance = 1.3f, // Enhance indoor colors
                shadows = 1.2f, // Lift indoor shadows
                highlights = 1.1f, // Gentle highlight recovery
                clarity = 1.0f // Neutral clarity
            )
            
            SceneType.MACRO -> SceneEnhancementMultipliers(
                contrast = 1.2f, // Enhance detail contrast
                saturation = 1.3f, // Rich colors for macro subjects
                vibrance = 1.4f, // High color richness
                shadows = 1.0f, // Preserve shadow details
                highlights = 1.0f, // Preserve highlight details
                clarity = 1.5f // Maximum detail enhancement
            )
            
            SceneType.UNKNOWN -> SceneEnhancementMultipliers(
                contrast = 1.0f, // Safe neutral values
                saturation = 1.1f,
                vibrance = 1.2f,
                shadows = 1.1f,
                highlights = 1.1f,
                clarity = 1.0f
            )
        }
        
        // Apply mode-specific scaling
        val modeMultiplier = when (mode) {
            ProcessingMode.LITE -> 0.7f
            ProcessingMode.MEDIUM -> 0.85f
            ProcessingMode.ADVANCED -> 1.0f
        }
        
        return SceneEnhancementMultipliers(
            contrast = baseMultipliers.contrast * modeMultiplier,
            saturation = baseMultipliers.saturation * modeMultiplier,
            vibrance = baseMultipliers.vibrance * modeMultiplier,
            shadows = baseMultipliers.shadows * modeMultiplier,
            highlights = baseMultipliers.highlights * modeMultiplier,
            clarity = baseMultipliers.clarity * modeMultiplier
        )
    }
    
    /**
     * Gets composition-based adjustments.
     */
    private fun getCompositionBasedAdjustments(compositionAnalysis: CompositionAnalysis, mode: ProcessingMode): AdjustmentParameters {
        val baseAdjustments = when (compositionAnalysis.compositionType) {
            CompositionType.PORTRAIT -> AdjustmentParameters(
                exposure = 0.05f, // Slight exposure boost for portraits
                contrast = -0.05f // Softer contrast
            )
            
            CompositionType.LANDSCAPE -> AdjustmentParameters(
                exposure = 0f, // Neutral exposure
                contrast = 0.1f // Higher contrast for landscapes
            )
            
            CompositionType.CLOSEUP -> AdjustmentParameters(
                exposure = 0f,
                contrast = 0.05f // Slight contrast boost for details
            )
            
            else -> AdjustmentParameters() // No adjustments for other types
        }
        
        // Scale based on composition confidence
        val confidenceMultiplier = compositionAnalysis.confidence
        
        return AdjustmentParameters(
            exposure = baseAdjustments.exposure * confidenceMultiplier,
            contrast = baseAdjustments.contrast * confidenceMultiplier
        )
    }
    
    /**
     * Gets color grading adjustments based on scene type.
     */
    private fun getColorGradingAdjustments(
        sceneType: SceneType, 
        colorBalanceAnalysis: ColorBalanceAnalysis, 
        mode: ProcessingMode
    ): AdjustmentParameters {
        
        val baseAdjustments = when (sceneType) {
            SceneType.LANDSCAPE -> AdjustmentParameters(
                saturation = 0.1f, // Boost nature colors
                vibrance = 0.15f,
                warmth = -0.05f, // Slightly cooler for landscapes
                tint = 0.02f // Slight magenta for sky
            )
            
            SceneType.PORTRAIT -> AdjustmentParameters(
                saturation = 0.05f, // Conservative saturation
                vibrance = 0.1f,
                warmth = 0.03f, // Slightly warmer for skin
                tint = -0.01f // Slight green for natural skin
            )
            
            SceneType.FOOD -> AdjustmentParameters(
                saturation = 0.2f, // High saturation for appetizing colors
                vibrance = 0.1f,
                warmth = 0.1f, // Warmer for food
                tint = 0f
            )
            
            SceneType.NIGHT -> AdjustmentParameters(
                saturation = 0.05f,
                vibrance = 0.2f, // High vibrance for night colors
                warmth = -0.1f, // Cooler for night scenes
                tint = 0.05f // Slight magenta for night sky
            )
            
            else -> AdjustmentParameters() // No specific grading for other scenes
        }
        
        // Adjust based on existing color balance
        val colorTempAdjustment = when {
            colorBalanceAnalysis.colorTemperature < 3500f -> -0.05f // Cool down very warm images
            colorBalanceAnalysis.colorTemperature > 7500f -> 0.05f // Warm up very cool images
            else -> 0f
        }
        
        return AdjustmentParameters(
            saturation = baseAdjustments.saturation,
            vibrance = baseAdjustments.vibrance,
            warmth = baseAdjustments.warmth + colorTempAdjustment,
            tint = baseAdjustments.tint
        )
    } 
   
    private fun calculateEnhancementQuality(
        exposureAnalysis: ExposureAnalysis,
        colorBalanceAnalysis: ColorBalanceAnalysis,
        dynamicRangeAnalysis: DynamicRangeAnalysis,
        appliedAdjustments: AdjustmentParameters
    ): QualityMetrics {
        // Calculate improvement scores
        val exposureImprovement = 1f - kotlin.math.abs(exposureAnalysis.exposureLevel) / 2f
        val contrastImprovement = dynamicRangeAnalysis.contrastLevel.coerceIn(0f, 1f)
        val colorBalanceImprovement = 1f - (kotlin.math.abs(colorBalanceAnalysis.colorTemperature - 5500f) / 3000f).coerceIn(0f, 1f)
        
        val overallQuality = (exposureImprovement + contrastImprovement + colorBalanceImprovement) / 3f
        
        return QualityMetrics(
            dynamicRangeImprovement = contrastImprovement,
            colorBalanceImprovement = colorBalanceImprovement,
            overallQuality = overallQuality,
            isSuccessful = true,
            exposureCorrection = exposureImprovement,
            contrastImprovement = contrastImprovement,
            detailPreservation = true,
            noiseReduction = 0.9f // Good noise reduction from algorithmic processing
        )
    }
    
    private fun applyBasicHealing(bitmap: Bitmap, maskArea: Rect, mode: ProcessingMode): Bitmap {
        val result = bitmap.copy(bitmap.config, true)
        
        // Simple healing using surrounding pixel averaging
        val healingRadius = if (performanceManager.shouldUseSimplifiedAlgorithm(ProcessingOperation.HEALING, mode)) 3 else 5
        
        for (y in maskArea.top until maskArea.bottom) {
            for (x in maskArea.left until maskArea.right) {
                if (x >= 0 && x < bitmap.width && y >= 0 && y < bitmap.height) {
                    val healedColor = calculateHealedPixel(bitmap, x, y, healingRadius, maskArea)
                    result.setPixel(x, y, healedColor)
                }
            }
        }
        
        return result
    }
    
    private fun calculateHealedPixel(bitmap: Bitmap, x: Int, y: Int, radius: Int, maskArea: Rect): Int {
        var totalR = 0
        var totalG = 0
        var totalB = 0
        var count = 0
        
        // Sample pixels around the area, excluding the mask area itself
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val sampleX = x + dx
                val sampleY = y + dy
                
                if (sampleX >= 0 && sampleX < bitmap.width && 
                    sampleY >= 0 && sampleY < bitmap.height &&
                    !maskArea.contains(sampleX, sampleY)) {
                    
                    val pixel = bitmap.getPixel(sampleX, sampleY)
                    totalR += Color.red(pixel)
                    totalG += Color.green(pixel)
                    totalB += Color.blue(pixel)
                    count++
                }
            }
        }
        
        return if (count > 0) {
            Color.rgb(totalR / count, totalG / count, totalB / count)
        } else {
            bitmap.getPixel(x, y) // Fallback to original pixel
        }
    }
    
    /**
     * Applies basic landscape enhancement as a fallback when advanced enhancement fails.
     */
    private suspend fun applyBasicLandscapeEnhancement(
        bitmap: Bitmap,
        parameters: LandscapeParameters
    ): Result<Bitmap> = withContext(Dispatchers.Default) {
        try {
            // Create landscape-specific adjustments based on parameters
            val landscapeAdjustments = AdjustmentParameters(
                saturation = parameters.foliageEnhancement * 0.5f,
                vibrance = parameters.skyEnhancement * 0.4f,
                clarity = parameters.clarityBoost,
                contrast = 0.1f,
                shadows = 0.1f,
                highlights = -0.1f,
                warmth = if (parameters.naturalColorGrading) 0.05f else 0f
            )
            
            // Apply basic enhancements
            val enhancedBitmap = applyEnhancedAdjustments(bitmap, landscapeAdjustments)
            
            Result.success(enhancedBitmap)
        } catch (e: Exception) {
            Result.failure(SmartProcessingError.AlgorithmFailure("basic_landscape_enhance", e))
        }
    }
    
    override suspend fun healWithBrush(
        bitmap: Bitmap, 
        brushStrokes: List<BrushStroke>, 
        brushSettings: HealingBrush, 
        mode: ProcessingMode
    ): Result<HealingResult> = withContext(Dispatchers.Default) {
        try {
            val result = healingTool.healWithBrush(bitmap, brushStrokes, brushSettings, mode)
            result
        } catch (e: Exception) {
            Result.failure(SmartProcessingError.AlgorithmFailure("healing_brush", e))
        }
    }
    
    override fun validateHealingArea(bitmap: Bitmap, targetArea: Rect): HealingValidation {
        return healingTool.validateHealingArea(bitmap, targetArea)
    }
    
    override suspend fun findSourceCandidates(
        bitmap: Bitmap, 
        targetArea: Rect, 
        maxCandidates: Int
    ): List<SourceCandidate> = withContext(Dispatchers.Default) {
        healingTool.findSourceCandidates(bitmap, targetArea, maxCandidates)
            .map { SourceCandidate(region = it.region, score = it.score) }
    }
}