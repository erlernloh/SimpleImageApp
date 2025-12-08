package com.imagedit.app.util.image

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.imagedit.app.domain.model.*
import javax.inject.Inject
import kotlin.math.*

/**
 * Detects and analyzes landscape elements in outdoor scene photos.
 * Implements sky region detection, foliage detection, horizon line detection,
 * and dominant color analysis specifically for landscape photography.
 */
class LandscapeDetector @Inject constructor() {
    
    companion object {
        // Sky detection thresholds
        private const val SKY_BLUE_HUE_MIN = 180f
        private const val SKY_BLUE_HUE_MAX = 260f
        private const val SKY_BRIGHTNESS_THRESHOLD = 0.4f
        private const val SKY_SATURATION_MIN = 0.2f
        private const val SKY_GRADIENT_THRESHOLD = 0.3f
        
        // Foliage detection thresholds
        private const val FOLIAGE_GREEN_HUE_MIN = 60f
        private const val FOLIAGE_GREEN_HUE_MAX = 180f
        private const val FOLIAGE_SATURATION_MIN = 0.3f
        private const val FOLIAGE_BRIGHTNESS_MIN = 0.2f
        private const val FOLIAGE_BRIGHTNESS_MAX = 0.8f
        
        // Water detection thresholds
        private const val WATER_BLUE_HUE_MIN = 180f
        private const val WATER_BLUE_HUE_MAX = 240f
        private const val WATER_REFLECTION_THRESHOLD = 0.6f
        private const val WATER_SMOOTHNESS_THRESHOLD = 0.7f
        
        // Rock/mountain detection thresholds
        private const val ROCK_SATURATION_MAX = 0.3f
        private const val ROCK_BRIGHTNESS_MIN = 0.3f
        private const val ROCK_BRIGHTNESS_MAX = 0.7f
        private const val ROCK_TEXTURE_THRESHOLD = 0.5f
        
        // Horizon detection parameters
        private const val HORIZON_EDGE_THRESHOLD = 20
        private const val HORIZON_LINE_MIN_LENGTH = 0.3f
        private const val HORIZON_ANGLE_TOLERANCE = 15f
        
        // Color clustering parameters
        private const val COLOR_QUANTIZATION_FACTOR = 32
        private const val MAX_COLOR_CLUSTERS = 12
        private const val MIN_CLUSTER_PERCENTAGE = 0.02f
        
        // Landscape confidence thresholds
        private const val MIN_LANDSCAPE_CONFIDENCE = 0.4f
        private const val SKY_PRESENCE_WEIGHT = 0.3f
        private const val FOLIAGE_PRESENCE_WEIGHT = 0.25f
        private const val HORIZON_PRESENCE_WEIGHT = 0.2f
        private const val COLOR_HARMONY_WEIGHT = 0.25f
    }
    
    /**
     * Performs comprehensive landscape analysis on the given bitmap.
     */
    fun analyzeLandscape(bitmap: Bitmap): LandscapeAnalysis {
        // Detect individual landscape elements
        val skyRegions = detectSkyRegions(bitmap)
        val foliageRegions = detectFoliageRegions(bitmap)
        val waterRegions = detectWaterRegions(bitmap)
        val rockRegions = detectRockRegions(bitmap)
        
        // Detect horizon line
        val horizonLine = detectHorizonLine(bitmap)
        
        // Analyze dominant colors
        val dominantColors = analyzeDominantColors(bitmap)
        
        // Calculate element percentages
        val totalPixels = bitmap.width * bitmap.height
        val skyPercentage = calculateRegionPercentage(skyRegions, totalPixels)
        val foliagePercentage = calculateRegionPercentage(foliageRegions, totalPixels)
        val waterPercentage = calculateRegionPercentage(waterRegions, totalPixels)
        val rockPercentage = calculateRegionPercentage(rockRegions, totalPixels)
        
        // Calculate overall landscape confidence
        val landscapeConfidence = calculateLandscapeConfidence(
            skyPercentage, foliagePercentage, waterPercentage, rockPercentage,
            horizonLine, dominantColors
        )
        
        // Generate recommended parameters based on analysis
        val recommendedParameters = generateRecommendedParameters(
            skyPercentage, foliagePercentage, waterPercentage, rockPercentage,
            dominantColors, landscapeConfidence
        )
        
        return LandscapeAnalysis(
            skyRegions = skyRegions,
            foliageRegions = foliageRegions,
            waterRegions = waterRegions,
            rockRegions = rockRegions,
            horizonLine = horizonLine,
            dominantColors = dominantColors,
            landscapeConfidence = landscapeConfidence,
            skyPercentage = skyPercentage,
            foliagePercentage = foliagePercentage,
            waterPercentage = waterPercentage,
            rockPercentage = rockPercentage,
            recommendedParameters = recommendedParameters
        )
    }
    
    /**
     * Detects sky regions using color and gradient analysis.
     */
    fun detectSkyRegions(bitmap: Bitmap): List<Rect> {
        val skyRegions = mutableListOf<Rect>()
        val width = bitmap.width
        val height = bitmap.height
        
        // Create a mask for sky pixels
        val skyMask = Array(height) { BooleanArray(width) }
        
        // First pass: identify potential sky pixels based on color
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                if (isSkyPixel(pixel)) {
                    skyMask[y][x] = true
                }
            }
        }
        
        // Second pass: apply gradient analysis for sky regions
        // Sky typically has smooth gradients, especially in upper portions
        for (y in 0 until height / 2) { // Focus on upper half
            for (x in 0 until width) {
                if (skyMask[y][x]) {
                    val gradientScore = calculateSkyGradientScore(bitmap, x, y)
                    if (gradientScore < SKY_GRADIENT_THRESHOLD) {
                        skyMask[y][x] = false
                    }
                }
            }
        }
        
        // Third pass: find connected sky regions
        val visited = Array(height) { BooleanArray(width) }
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (skyMask[y][x] && !visited[y][x]) {
                    val region = findConnectedSkyRegion(skyMask, visited, x, y, width, height)
                    if (region != null && isValidSkyRegion(region, width, height)) {
                        skyRegions.add(region)
                    }
                }
            }
        }
        
        return skyRegions.sortedByDescending { it.width() * it.height() }
    }
    
    /**
     * Detects foliage/vegetation regions using green color analysis.
     */
    fun detectFoliageRegions(bitmap: Bitmap): List<Rect> {
        val foliageRegions = mutableListOf<Rect>()
        val width = bitmap.width
        val height = bitmap.height
        
        // Create a mask for foliage pixels
        val foliageMask = Array(height) { BooleanArray(width) }
        
        // Identify foliage pixels based on green color characteristics
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                if (isFoliagePixel(pixel)) {
                    foliageMask[y][x] = true
                }
            }
        }
        
        // Apply texture analysis to refine foliage detection
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                if (foliageMask[y][x]) {
                    val textureScore = calculateFoliageTextureScore(bitmap, x, y)
                    if (textureScore < 0.3f) {
                        foliageMask[y][x] = false
                    }
                }
            }
        }
        
        // Find connected foliage regions
        val visited = Array(height) { BooleanArray(width) }
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (foliageMask[y][x] && !visited[y][x]) {
                    val region = findConnectedRegion(foliageMask, visited, x, y, width, height)
                    if (region != null && isValidFoliageRegion(region)) {
                        foliageRegions.add(region)
                    }
                }
            }
        }
        
        return foliageRegions.sortedByDescending { it.width() * it.height() }
    }
    
    /**
     * Detects water body regions using blue color and reflection analysis.
     */
    fun detectWaterRegions(bitmap: Bitmap): List<Rect> {
        val waterRegions = mutableListOf<Rect>()
        val width = bitmap.width
        val height = bitmap.height
        
        // Create a mask for water pixels
        val waterMask = Array(height) { BooleanArray(width) }
        
        // Identify potential water pixels
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                if (isWaterPixel(pixel)) {
                    waterMask[y][x] = true
                }
            }
        }
        
        // Apply smoothness analysis (water surfaces are typically smooth)
        for (y in 2 until height - 2) {
            for (x in 2 until width - 2) {
                if (waterMask[y][x]) {
                    val smoothnessScore = calculateSmoothness(bitmap, x, y, 2)
                    if (smoothnessScore < WATER_SMOOTHNESS_THRESHOLD) {
                        waterMask[y][x] = false
                    }
                }
            }
        }
        
        // Find connected water regions
        val visited = Array(height) { BooleanArray(width) }
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (waterMask[y][x] && !visited[y][x]) {
                    val region = findConnectedRegion(waterMask, visited, x, y, width, height)
                    if (region != null && isValidWaterRegion(region)) {
                        waterRegions.add(region)
                    }
                }
            }
        }
        
        return waterRegions.sortedByDescending { it.width() * it.height() }
    }
    
    /**
     * Detects mountain/rock regions using texture and color analysis.
     */
    fun detectRockRegions(bitmap: Bitmap): List<Rect> {
        val rockRegions = mutableListOf<Rect>()
        val width = bitmap.width
        val height = bitmap.height
        
        // Create a mask for rock pixels
        val rockMask = Array(height) { BooleanArray(width) }
        
        // Identify potential rock pixels based on color characteristics
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                if (isRockPixel(pixel)) {
                    rockMask[y][x] = true
                }
            }
        }
        
        // Apply texture analysis (rocks have high texture variation)
        for (y in 2 until height - 2) {
            for (x in 2 until width - 2) {
                if (rockMask[y][x]) {
                    val textureScore = calculateRockTextureScore(bitmap, x, y)
                    if (textureScore < ROCK_TEXTURE_THRESHOLD) {
                        rockMask[y][x] = false
                    }
                }
            }
        }
        
        // Find connected rock regions
        val visited = Array(height) { BooleanArray(width) }
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (rockMask[y][x] && !visited[y][x]) {
                    val region = findConnectedRegion(rockMask, visited, x, y, width, height)
                    if (region != null && isValidRockRegion(region)) {
                        rockRegions.add(region)
                    }
                }
            }
        }
        
        return rockRegions.sortedByDescending { it.width() * it.height() }
    }
    
    /**
     * Detects horizon line using edge detection and line analysis.
     */
    fun detectHorizonLine(bitmap: Bitmap): HorizonLine? {
        val width = bitmap.width
        val height = bitmap.height
        
        // Detect horizontal edges in the middle portion of the image
        val startY = height / 4
        val endY = 3 * height / 4
        
        val horizontalEdges = mutableListOf<Pair<Int, Float>>() // y-position to edge strength
        
        for (y in startY until endY) {
            var edgeStrength = 0f
            var edgeCount = 0
            
            for (x in 1 until width - 1) {
                val topPixel = bitmap.getPixel(x, y - 1)
                val bottomPixel = bitmap.getPixel(x, y + 1)
                
                val topBrightness = calculatePixelBrightness(topPixel)
                val bottomBrightness = calculatePixelBrightness(bottomPixel)
                
                val edgeMagnitude = abs(topBrightness - bottomBrightness)
                if (edgeMagnitude > HORIZON_EDGE_THRESHOLD) {
                    edgeStrength += edgeMagnitude
                    edgeCount++
                }
            }
            
            if (edgeCount > width * HORIZON_LINE_MIN_LENGTH) {
                val avgEdgeStrength = edgeStrength / edgeCount
                horizontalEdges.add(Pair(y, avgEdgeStrength))
            }
        }
        
        // Find the strongest horizontal edge that could be a horizon
        val bestHorizonCandidate = horizontalEdges.maxByOrNull { it.second }
        if (bestHorizonCandidate != null) {
            val yPosition = bestHorizonCandidate.first
            val confidence = (bestHorizonCandidate.second / 255f).coerceIn(0f, 1f)
            val angle = calculateHorizonAngle(bitmap, yPosition)
            if (abs(angle) <= HORIZON_ANGLE_TOLERANCE && confidence > 0.3f) {
                return HorizonLine(
                    y = yPosition,
                    angle = angle,
                    confidence = confidence,
                    startX = 0,
                    endX = bitmap.width
                )
            }
        }
        return null
    }
    
    /**
     * Analyzes dominant colors in the landscape scene.
     */
    fun analyzeDominantColors(bitmap: Bitmap): List<LandscapeColorCluster> {
        val colorMap = mutableMapOf<Int, Int>()
        val totalPixels = bitmap.width * bitmap.height
        
        // Sample pixels for performance (every 3rd pixel)
        for (y in 0 until bitmap.height step 3) {
            for (x in 0 until bitmap.width step 3) {
                val pixel = bitmap.getPixel(x, y)
                val quantizedColor = quantizeColor(pixel)
                colorMap[quantizedColor] = colorMap.getOrDefault(quantizedColor, 0) + 1
            }
        }
        
        // Convert to color clusters and sort by frequency
        return colorMap.entries
            .sortedByDescending { it.value }
            .take(MAX_COLOR_CLUSTERS)
            .mapNotNull { (color, count) ->
                val percentage = (count * 9f) / totalPixels // Adjust for sampling
                if (percentage >= MIN_CLUSTER_PERCENTAGE) {
                    val elementType = classifyColorElement(color)
                    LandscapeColorCluster(color, percentage, elementType)
                } else null
            }
    }
    
    // Private helper methods
    
    private fun isSkyPixel(pixel: Int): Boolean {
        val hsv = FloatArray(3)
        Color.colorToHSV(pixel, hsv)
        
        val hue = hsv[0]
        val saturation = hsv[1]
        val brightness = hsv[2]
        
        // Check for blue sky colors or bright/white sky
        return ((hue in SKY_BLUE_HUE_MIN..SKY_BLUE_HUE_MAX && saturation >= SKY_SATURATION_MIN) ||
                (brightness >= SKY_BRIGHTNESS_THRESHOLD && saturation <= 0.3f)) &&
                brightness >= 0.3f
    }
    
    private fun isFoliagePixel(pixel: Int): Boolean {
        val hsv = FloatArray(3)
        Color.colorToHSV(pixel, hsv)
        
        val hue = hsv[0]
        val saturation = hsv[1]
        val brightness = hsv[2]
        
        return hue in FOLIAGE_GREEN_HUE_MIN..FOLIAGE_GREEN_HUE_MAX &&
                saturation >= FOLIAGE_SATURATION_MIN &&
                brightness in FOLIAGE_BRIGHTNESS_MIN..FOLIAGE_BRIGHTNESS_MAX
    }
    
    private fun isWaterPixel(pixel: Int): Boolean {
        val hsv = FloatArray(3)
        Color.colorToHSV(pixel, hsv)
        
        val hue = hsv[0]
        val saturation = hsv[1]
        val brightness = hsv[2]
        
        // Water can be blue or reflect sky colors
        return (hue in WATER_BLUE_HUE_MIN..WATER_BLUE_HUE_MAX ||
                (brightness > 0.4f && saturation < 0.4f)) &&
                brightness > 0.2f
    }
    
    private fun isRockPixel(pixel: Int): Boolean {
        val hsv = FloatArray(3)
        Color.colorToHSV(pixel, hsv)
        
        val saturation = hsv[1]
        val brightness = hsv[2]
        
        // Rocks typically have low saturation and moderate brightness
        return saturation <= ROCK_SATURATION_MAX &&
                brightness in ROCK_BRIGHTNESS_MIN..ROCK_BRIGHTNESS_MAX
    }
    
    private fun calculateSkyGradientScore(bitmap: Bitmap, x: Int, y: Int): Float {
        val radius = 3
        val pixels = mutableListOf<Int>()
        
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val nx = (x + dx).coerceIn(0, bitmap.width - 1)
                val ny = (y + dy).coerceIn(0, bitmap.height - 1)
                pixels.add(bitmap.getPixel(nx, ny))
            }
        }
        
        // Calculate color variation (sky should have low variation)
        val brightnessValues = pixels.map { calculatePixelBrightness(it) }
        val avgBrightness = brightnessValues.average().toFloat()
        val variance = brightnessValues.map { (it - avgBrightness).pow(2) }.average().toFloat()
        
        return sqrt(variance) / 255f
    }
    
    private fun calculateFoliageTextureScore(bitmap: Bitmap, x: Int, y: Int): Float {
        val radius = 2
        val pixels = mutableListOf<Int>()
        
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val nx = (x + dx).coerceIn(0, bitmap.width - 1)
                val ny = (y + dy).coerceIn(0, bitmap.height - 1)
                pixels.add(bitmap.getPixel(nx, ny))
            }
        }
        
        // Calculate texture variation (foliage should have moderate to high variation)
        val greenValues = pixels.map { Color.green(it).toFloat() }
        val avgGreen = greenValues.average().toFloat()
        val variance = greenValues.map { (it - avgGreen).pow(2) }.average().toFloat()
        
        return (sqrt(variance) / 255f).coerceIn(0f, 1f)
    }
    
    private fun calculateSmoothness(bitmap: Bitmap, x: Int, y: Int, radius: Int): Float {
        val pixels = mutableListOf<Int>()
        
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val nx = (x + dx).coerceIn(0, bitmap.width - 1)
                val ny = (y + dy).coerceIn(0, bitmap.height - 1)
                pixels.add(bitmap.getPixel(nx, ny))
            }
        }
        
        // Calculate smoothness (low variation indicates smooth surface)
        val brightnessValues = pixels.map { calculatePixelBrightness(it) }
        val avgBrightness = brightnessValues.average().toFloat()
        val variance = brightnessValues.map { (it - avgBrightness).pow(2) }.average().toFloat()
        
        val smoothness = 1f - (sqrt(variance) / 255f)
        return smoothness.coerceIn(0f, 1f)
    }
    
    private fun calculateRockTextureScore(bitmap: Bitmap, x: Int, y: Int): Float {
        val radius = 2
        val pixels = mutableListOf<Int>()
        
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val nx = (x + dx).coerceIn(0, bitmap.width - 1)
                val ny = (y + dy).coerceIn(0, bitmap.height - 1)
                pixels.add(bitmap.getPixel(nx, ny))
            }
        }
        
        // Calculate texture variation (rocks should have high variation)
        val brightnessValues = pixels.map { calculatePixelBrightness(it) }
        val avgBrightness = brightnessValues.average().toFloat()
        val variance = brightnessValues.map { (it - avgBrightness).pow(2) }.average().toFloat()
        
        return (sqrt(variance) / 255f).coerceIn(0f, 1f)
    }
    
    private fun findConnectedSkyRegion(
        mask: Array<BooleanArray>,
        visited: Array<BooleanArray>,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int
    ): Rect? {
        val points = mutableListOf<Pair<Int, Int>>()
        val stack = mutableListOf<Pair<Int, Int>>()
        stack.add(Pair(startX, startY))
        
        while (stack.isNotEmpty()) {
            val (x, y) = stack.removeAt(stack.size - 1)
            
            if (x < 0 || x >= width || y < 0 || y >= height || visited[y][x] || !mask[y][x]) {
                continue
            }
            
            visited[y][x] = true
            points.add(Pair(x, y))
            
            // Add neighbors
            stack.add(Pair(x + 1, y))
            stack.add(Pair(x - 1, y))
            stack.add(Pair(x, y + 1))
            stack.add(Pair(x, y - 1))
        }
        
        if (points.size < 100) return null // Minimum region size
        
        val minX = points.minOf { it.first }
        val maxX = points.maxOf { it.first }
        val minY = points.minOf { it.second }
        val maxY = points.maxOf { it.second }
        
        return Rect(minX, minY, maxX, maxY)
    }
    
    private fun findConnectedRegion(
        mask: Array<BooleanArray>,
        visited: Array<BooleanArray>,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int
    ): Rect? {
        val points = mutableListOf<Pair<Int, Int>>()
        val stack = mutableListOf<Pair<Int, Int>>()
        stack.add(Pair(startX, startY))
        
        while (stack.isNotEmpty()) {
            val (x, y) = stack.removeAt(stack.size - 1)
            
            if (x < 0 || x >= width || y < 0 || y >= height || visited[y][x] || !mask[y][x]) {
                continue
            }
            
            visited[y][x] = true
            points.add(Pair(x, y))
            
            // Add neighbors
            stack.add(Pair(x + 1, y))
            stack.add(Pair(x - 1, y))
            stack.add(Pair(x, y + 1))
            stack.add(Pair(x, y - 1))
        }
        
        if (points.size < 50) return null // Minimum region size
        
        val minX = points.minOf { it.first }
        val maxX = points.maxOf { it.first }
        val minY = points.minOf { it.second }
        val maxY = points.maxOf { it.second }
        
        return Rect(minX, minY, maxX, maxY)
    }
    
    private fun isValidSkyRegion(region: Rect, imageWidth: Int, imageHeight: Int): Boolean {
        val regionArea = region.width() * region.height()
        val imageArea = imageWidth * imageHeight
        val areaPercentage = regionArea.toFloat() / imageArea
        
        // Sky regions should be reasonably large and preferably in upper portion
        return areaPercentage > 0.05f && region.top < imageHeight / 2
    }
    
    private fun isValidFoliageRegion(region: Rect): Boolean {
        val regionArea = region.width() * region.height()
        return regionArea > 200 // Minimum area for foliage regions
    }
    
    private fun isValidWaterRegion(region: Rect): Boolean {
        val regionArea = region.width() * region.height()
        return regionArea > 300 // Minimum area for water regions
    }
    
    private fun isValidRockRegion(region: Rect): Boolean {
        val regionArea = region.width() * region.height()
        return regionArea > 150 // Minimum area for rock regions
    }
    
    private fun calculateHorizonAngle(bitmap: Bitmap, yPosition: Int): Float {
        val width = bitmap.width
        val edgePoints = mutableListOf<Pair<Int, Int>>()
        
        // Find edge points along the horizon line
        for (x in 0 until width - 1) {
            val topPixel = bitmap.getPixel(x, (yPosition - 1).coerceAtLeast(0))
            val bottomPixel = bitmap.getPixel(x, (yPosition + 1).coerceAtMost(bitmap.height - 1))
            
            val topBrightness = calculatePixelBrightness(topPixel)
            val bottomBrightness = calculatePixelBrightness(bottomPixel)
            
            if (abs(topBrightness - bottomBrightness) > HORIZON_EDGE_THRESHOLD) {
                edgePoints.add(Pair(x, yPosition))
            }
        }
        
        if (edgePoints.size < 3) return 0f
        
        // Calculate line slope using least squares
        val n = edgePoints.size
        val sumX = edgePoints.sumOf { it.first }
        val sumY = edgePoints.sumOf { it.second }
        val sumXY = edgePoints.sumOf { it.first * it.second }
        val sumX2 = edgePoints.sumOf { it.first * it.first }
        
        val slope = (n * sumXY - sumX * sumY).toFloat() / (n * sumX2 - sumX * sumX).toFloat()
        
        // Convert slope to angle in degrees
        return atan(slope) * 180f / PI.toFloat()
    }
    
    private fun calculatePixelBrightness(pixel: Int): Float {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return (0.299f * r + 0.587f * g + 0.114f * b)
    }
    
    private fun quantizeColor(color: Int): Int {
        val r = (Color.red(color) / COLOR_QUANTIZATION_FACTOR) * COLOR_QUANTIZATION_FACTOR
        val g = (Color.green(color) / COLOR_QUANTIZATION_FACTOR) * COLOR_QUANTIZATION_FACTOR
        val b = (Color.blue(color) / COLOR_QUANTIZATION_FACTOR) * COLOR_QUANTIZATION_FACTOR
        return Color.rgb(r, g, b)
    }
    
    private fun classifyColorElement(color: Int): LandscapeElement {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        
        val hue = hsv[0]
        val saturation = hsv[1]
        val brightness = hsv[2]
        
        return when {
            hue in SKY_BLUE_HUE_MIN..SKY_BLUE_HUE_MAX && brightness > 0.4f -> LandscapeElement.SKY
            hue in FOLIAGE_GREEN_HUE_MIN..FOLIAGE_GREEN_HUE_MAX && saturation > 0.3f -> LandscapeElement.FOLIAGE
            hue in WATER_BLUE_HUE_MIN..WATER_BLUE_HUE_MAX && brightness > 0.2f -> LandscapeElement.WATER
            saturation < 0.3f && brightness in 0.3f..0.7f -> LandscapeElement.ROCK
            else -> LandscapeElement.UNKNOWN
        }
    }
    
    private fun calculateRegionPercentage(regions: List<Rect>, totalPixels: Int): Float {
        val totalRegionPixels = regions.sumOf { it.width() * it.height() }
        return totalRegionPixels.toFloat() / totalPixels
    }
    
    private fun calculateLandscapeConfidence(
        skyPercentage: Float,
        foliagePercentage: Float,
        waterPercentage: Float,
        rockPercentage: Float,
        horizonLine: HorizonLine?,
        dominantColors: List<LandscapeColorCluster>
    ): Float {
        var confidence = 0f
        
        // Sky presence contributes to landscape confidence
        confidence += (skyPercentage * SKY_PRESENCE_WEIGHT).coerceAtMost(SKY_PRESENCE_WEIGHT)
        
        // Foliage presence contributes to landscape confidence
        confidence += (foliagePercentage * FOLIAGE_PRESENCE_WEIGHT * 2f).coerceAtMost(FOLIAGE_PRESENCE_WEIGHT)
        
        // Horizon line presence is a strong indicator
        if (horizonLine != null) {
            confidence += HORIZON_PRESENCE_WEIGHT * horizonLine.confidence
        }
        
        // Natural color harmony (blues, greens, earth tones)
        val naturalColorPercentage = dominantColors
            .filter { it.elementType in listOf(LandscapeElement.SKY, LandscapeElement.FOLIAGE, LandscapeElement.WATER) }
            .sumOf { it.percentage.toDouble() }.toFloat()
        
        confidence += (naturalColorPercentage * COLOR_HARMONY_WEIGHT).coerceAtMost(COLOR_HARMONY_WEIGHT)
        
        return confidence.coerceIn(0f, 1f)
    }
    
    private fun generateRecommendedParameters(
        skyPercentage: Float,
        foliagePercentage: Float,
        waterPercentage: Float,
        rockPercentage: Float,
        dominantColors: List<LandscapeColorCluster>,
        landscapeConfidence: Float
    ): LandscapeParameters {
        // Base parameters
        var skyEnhancement = 0.3f
        var foliageEnhancement = 0.4f
        var clarityBoost = 0.2f
        var waterEnhancement = 0.25f
        var rockEnhancement = 0.15f
        
        // Adjust based on element presence
        if (skyPercentage > 0.3f) {
            skyEnhancement = (0.2f + skyPercentage * 0.5f).coerceAtMost(0.8f)
        }
        
        if (foliagePercentage > 0.2f) {
            foliageEnhancement = (0.3f + foliagePercentage * 0.6f).coerceAtMost(0.9f)
        }
        
        if (waterPercentage > 0.15f) {
            waterEnhancement = (0.2f + waterPercentage * 0.7f).coerceAtMost(0.8f)
        }
        
        if (rockPercentage > 0.2f) {
            rockEnhancement = (0.1f + rockPercentage * 0.5f).coerceAtMost(0.6f)
            clarityBoost = (clarityBoost + rockPercentage * 0.3f).coerceAtMost(0.7f)
        }
        
        // Scale all parameters by landscape confidence
        val confidenceScale = (0.5f + landscapeConfidence * 0.5f).coerceIn(0.3f, 1f)
        
        return LandscapeParameters(
            skyEnhancement = skyEnhancement * confidenceScale,
            foliageEnhancement = foliageEnhancement * confidenceScale,
            clarityBoost = clarityBoost * confidenceScale,
            naturalColorGrading = landscapeConfidence > 0.5f,
            waterEnhancement = waterEnhancement * confidenceScale,
            rockEnhancement = rockEnhancement * confidenceScale
        )
    }
}