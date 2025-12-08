/**
 * BurstCaptureController.kt - CameraX burst capture controller
 * 
 * Manages burst capture of 8-12 YUV frames with locked AE/AF
 * for Ultra Detail+ processing.
 */

package com.imagedit.app.ultradetail

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.Image
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val TAG = "BurstCaptureController"

/**
 * Burst capture configuration
 */
data class BurstCaptureConfig(
    val frameCount: Int = 8,
    val targetResolution: Size = Size(4000, 3000), // ~12MP
    val lockAeAf: Boolean = true,
    val frameIntervalMs: Long = 50 // Time between frames
) {
    init {
        require(frameCount in 2..16) { "Frame count must be between 2 and 16" }
    }
}

/**
 * Captured frame data
 */
/**
 * Gyroscope sample with timestamp
 */
data class GyroSample(
    val timestamp: Long,      // Nanoseconds (same timebase as camera frames)
    val rotationX: Float,     // Rotation rate around X axis (rad/s)
    val rotationY: Float,     // Rotation rate around Y axis (rad/s)
    val rotationZ: Float      // Rotation rate around Z axis (rad/s)
)

/**
 * Captured frame data with associated gyro samples
 */
data class CapturedFrame(
    val yPlane: ByteBuffer,
    val uPlane: ByteBuffer,
    val vPlane: ByteBuffer,
    val yRowStride: Int,
    val uvRowStride: Int,
    val uvPixelStride: Int,
    val width: Int,
    val height: Int,
    val timestamp: Long,
    val gyroSamples: List<GyroSample> = emptyList()  // Gyro samples during this frame's exposure
) {
    companion object {
        /**
         * Create from CameraX Image
         */
        fun fromImage(image: Image): CapturedFrame {
            require(image.format == ImageFormat.YUV_420_888) {
                "Expected YUV_420_888, got ${image.format}"
            }
            
            val planes = image.planes
            
            // Copy plane data to direct ByteBuffers (image will be closed)
            val yBuffer = copyToDirectBuffer(planes[0].buffer)
            val uBuffer = copyToDirectBuffer(planes[1].buffer)
            val vBuffer = copyToDirectBuffer(planes[2].buffer)
            
            return CapturedFrame(
                yPlane = yBuffer,
                uPlane = uBuffer,
                vPlane = vBuffer,
                yRowStride = planes[0].rowStride,
                uvRowStride = planes[1].rowStride,
                uvPixelStride = planes[1].pixelStride,
                width = image.width,
                height = image.height,
                timestamp = image.timestamp
            )
        }
        
        private fun copyToDirectBuffer(source: ByteBuffer): ByteBuffer {
            val copy = ByteBuffer.allocateDirect(source.remaining())
            copy.put(source)
            copy.rewind()
            return copy
        }
    }
}

/**
 * Burst capture state
 */
sealed class BurstCaptureState {
    object Idle : BurstCaptureState()
    data class Capturing(val framesCollected: Int, val totalFrames: Int) : BurstCaptureState()
    data class Complete(val frames: List<CapturedFrame>) : BurstCaptureState()
    data class Error(val message: String) : BurstCaptureState()
}

/**
 * Burst capture controller for CameraX
 * 
 * Captures multiple YUV frames in rapid succession with locked
 * exposure and focus for HDR+ style processing.
 */
class BurstCaptureController(
    private val context: Context,
    private val config: BurstCaptureConfig = BurstCaptureConfig()
) : SensorEventListener {
    private val _captureState = MutableStateFlow<BurstCaptureState>(BurstCaptureState.Idle)
    val captureState: StateFlow<BurstCaptureState> = _captureState.asStateFlow()
    
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    
    private val capturedFrames = mutableListOf<CapturedFrame>()
    private var isCapturing = false
    private var captureJob: Job? = null
    
    // Channel for receiving frames during burst
    private var frameChannel: Channel<CapturedFrame>? = null
    
    // Gyroscope sensor for motion tracking
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    
    // Gyro sample buffer - thread-safe collection
    private val gyroBuffer = java.util.concurrent.ConcurrentLinkedQueue<GyroSample>()
    private var isGyroRecording = false
    
    // Track last frame timestamp for associating gyro samples
    private var lastFrameTimestamp: Long = 0
    
    /**
     * Setup burst capture with CameraX
     * 
     * @param cameraProvider Camera provider
     * @param lifecycleOwner Lifecycle owner for camera binding
     * @param cameraSelector Camera to use
     * @param preview Optional preview use case
     */
    fun setup(
        cameraProvider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
        preview: Preview? = null
    ) {
        // Configure image analysis for YUV capture with high resolution
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    config.targetResolution,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        
        imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply {
                setAnalyzer(analysisExecutor) { imageProxy ->
                    handleFrame(imageProxy)
                }
            }
        
        // Bind to lifecycle
        val useCases = mutableListOf<UseCase>(imageAnalysis!!)
        preview?.let { useCases.add(it) }
        
        cameraProvider.unbindAll()
        camera = cameraProvider.bindToLifecycle(
            lifecycleOwner,
            cameraSelector,
            *useCases.toTypedArray()
        )
        
        // Log actual resolution obtained
        val actualResolution = imageAnalysis?.resolutionInfo?.resolution ?: config.targetResolution
        Log.d(TAG, "Burst capture setup complete: ${config.frameCount} frames at $actualResolution (requested: ${config.targetResolution})")
    }
    
    /**
     * Start burst capture
     * 
     * @param scope Coroutine scope for capture operation
     * @return Flow of capture state updates
     */
    suspend fun startCapture(scope: CoroutineScope): List<CapturedFrame> = suspendCoroutine { continuation ->
        if (isCapturing) {
            continuation.resume(emptyList())
            return@suspendCoroutine
        }
        
        isCapturing = true
        capturedFrames.clear()
        frameChannel = Channel(Channel.UNLIMITED)
        
        _captureState.value = BurstCaptureState.Capturing(0, config.frameCount)
        
        // Start gyroscope recording for motion tracking
        startGyroRecording()
        
        captureJob = scope.launch {
            try {
                // Lock AE/AF if configured
                if (config.lockAeAf) {
                    lockExposureAndFocus()
                }
                
                // Collect frames with gyro data
                val frames = mutableListOf<CapturedFrame>()
                
                repeat(config.frameCount) { index ->
                    val frame = frameChannel?.receive()
                    if (frame != null) {
                        // Associate gyro samples with this frame
                        val gyroSamples = getGyroSamplesForFrame(frame.timestamp)
                        val frameWithGyro = frame.copy(gyroSamples = gyroSamples)
                        
                        frames.add(frameWithGyro)
                        _captureState.value = BurstCaptureState.Capturing(index + 1, config.frameCount)
                        
                        Log.d(TAG, "Captured frame ${index + 1}/${config.frameCount} with ${gyroSamples.size} gyro samples")
                    }
                    
                    // Small delay between frames
                    if (index < config.frameCount - 1) {
                        delay(config.frameIntervalMs)
                    }
                }
                
                // Stop gyroscope recording
                stopGyroRecording()
                
                // Log gyro statistics for debugging
                logGyroStatistics(frames)
                
                // Unlock AE/AF
                if (config.lockAeAf) {
                    unlockExposureAndFocus()
                }
                
                isCapturing = false
                _captureState.value = BurstCaptureState.Complete(frames)
                continuation.resume(frames)
                
            } catch (e: Exception) {
                Log.e(TAG, "Burst capture failed", e)
                stopGyroRecording()
                isCapturing = false
                _captureState.value = BurstCaptureState.Error(e.message ?: "Unknown error")
                continuation.resume(emptyList())
            }
        }
    }
    
    /**
     * Log gyroscope statistics for debugging and validation
     */
    private fun logGyroStatistics(frames: List<CapturedFrame>) {
        val totalSamples = frames.sumOf { it.gyroSamples.size }
        val avgSamplesPerFrame = if (frames.isNotEmpty()) totalSamples / frames.size else 0
        
        if (totalSamples == 0) {
            Log.w(TAG, "Gyro stats: No gyro samples collected (gyroscope may be unavailable)")
            return
        }
        
        // Calculate average rotation rates across all samples
        var sumX = 0f; var sumY = 0f; var sumZ = 0f
        var maxRotation = 0f
        
        frames.flatMap { it.gyroSamples }.forEach { sample ->
            sumX += kotlin.math.abs(sample.rotationX)
            sumY += kotlin.math.abs(sample.rotationY)
            sumZ += kotlin.math.abs(sample.rotationZ)
            
            val magnitude = kotlin.math.sqrt(
                sample.rotationX * sample.rotationX +
                sample.rotationY * sample.rotationY +
                sample.rotationZ * sample.rotationZ
            )
            maxRotation = kotlin.math.max(maxRotation, magnitude)
        }
        
        val avgX = sumX / totalSamples
        val avgY = sumY / totalSamples
        val avgZ = sumZ / totalSamples
        
        Log.i(TAG, "Gyro stats: $totalSamples samples (~$avgSamplesPerFrame/frame)")
        Log.i(TAG, "Gyro avg rotation (rad/s): X=%.4f, Y=%.4f, Z=%.4f, max=%.4f".format(avgX, avgY, avgZ, maxRotation))
        
        // Estimate total rotation during burst (rough approximation)
        val burstDurationMs = frames.size * config.frameIntervalMs
        val estimatedRotationDeg = maxRotation * (burstDurationMs / 1000f) * (180f / Math.PI.toFloat())
        Log.i(TAG, "Gyro estimated max rotation during burst: %.2f degrees".format(estimatedRotationDeg))
    }
    
    /**
     * Cancel ongoing capture
     */
    fun cancelCapture() {
        captureJob?.cancel()
        stopGyroRecording()
        isCapturing = false
        frameChannel?.close()
        _captureState.value = BurstCaptureState.Idle
    }
    
    /**
     * Handle incoming frame from image analysis
     */
    private fun handleFrame(imageProxy: ImageProxy) {
        if (isCapturing && frameChannel?.isClosedForSend == false) {
            try {
                val frame = CapturedFrame.fromImage(imageProxy.image!!)
                frameChannel?.trySend(frame)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to capture frame", e)
            }
        }
        imageProxy.close()
    }
    
    /**
     * Lock auto-exposure and auto-focus
     */
    private suspend fun lockExposureAndFocus() {
        camera?.cameraControl?.let { control ->
            try {
                // Start focus and metering
                val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
                val centerPoint = factory.createPoint(0.5f, 0.5f)
                val action = FocusMeteringAction.Builder(centerPoint)
                    .setAutoCancelDuration(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                
                control.startFocusAndMetering(action).await()
                
                // Lock exposure
                control.setExposureCompensationIndex(0).await()
                
                Log.d(TAG, "AE/AF locked")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to lock AE/AF", e)
            }
        }
    }
    
    /**
     * Unlock auto-exposure and auto-focus
     */
    private fun unlockExposureAndFocus() {
        camera?.cameraControl?.cancelFocusAndMetering()
        Log.d(TAG, "AE/AF unlocked")
    }
    
    /**
     * Release resources
     */
    fun release() {
        cancelCapture()
        stopGyroRecording()
        analysisExecutor.shutdown()
        imageAnalysis = null
        camera = null
    }
    
    // ==================== Gyroscope Recording ====================
    
    /**
     * Start recording gyroscope data
     * Uses SENSOR_DELAY_FASTEST for ~200Hz sampling rate
     */
    private fun startGyroRecording() {
        if (gyroscope == null) {
            Log.w(TAG, "Gyroscope not available on this device")
            return
        }
        
        gyroBuffer.clear()
        isGyroRecording = true
        lastFrameTimestamp = 0
        
        // Register at fastest rate for accurate motion tracking
        val registered = sensorManager.registerListener(
            this,
            gyroscope,
            SensorManager.SENSOR_DELAY_FASTEST
        )
        
        if (registered) {
            Log.d(TAG, "Gyroscope recording started (SENSOR_DELAY_FASTEST)")
        } else {
            Log.e(TAG, "Failed to register gyroscope listener")
            isGyroRecording = false
        }
    }
    
    /**
     * Stop recording gyroscope data
     */
    private fun stopGyroRecording() {
        if (isGyroRecording) {
            sensorManager.unregisterListener(this)
            isGyroRecording = false
            Log.d(TAG, "Gyroscope recording stopped. Collected ${gyroBuffer.size} samples")
        }
    }
    
    /**
     * Get gyro samples between two timestamps
     * Used to associate gyro data with each captured frame
     */
    private fun getGyroSamplesForFrame(frameTimestamp: Long): List<GyroSample> {
        val samples = mutableListOf<GyroSample>()
        
        // Get samples between last frame and this frame
        val startTime = if (lastFrameTimestamp > 0) lastFrameTimestamp else frameTimestamp - 50_000_000 // 50ms before
        val endTime = frameTimestamp
        
        // Collect samples in the time window
        gyroBuffer.forEach { sample ->
            if (sample.timestamp in startTime..endTime) {
                samples.add(sample)
            }
        }
        
        // Update last frame timestamp
        lastFrameTimestamp = frameTimestamp
        
        return samples
    }
    
    // ==================== SensorEventListener Implementation ====================
    
    override fun onSensorChanged(event: SensorEvent) {
        if (!isGyroRecording || event.sensor.type != Sensor.TYPE_GYROSCOPE) {
            return
        }
        
        // Store gyro sample with timestamp
        val sample = GyroSample(
            timestamp = event.timestamp,  // Nanoseconds since boot
            rotationX = event.values[0],  // rad/s around X axis
            rotationY = event.values[1],  // rad/s around Y axis
            rotationZ = event.values[2]   // rad/s around Z axis
        )
        
        gyroBuffer.add(sample)
        
        // Limit buffer size to prevent memory issues (keep last ~2 seconds at 200Hz)
        while (gyroBuffer.size > 400) {
            gyroBuffer.poll()
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Log accuracy changes for debugging
        if (sensor.type == Sensor.TYPE_GYROSCOPE) {
            val accuracyStr = when (accuracy) {
                SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "HIGH"
                SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "MEDIUM"
                SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "LOW"
                else -> "UNRELIABLE"
            }
            Log.d(TAG, "Gyroscope accuracy changed: $accuracyStr")
        }
    }
}

/**
 * Extension to await CameraX ListenableFuture
 */
private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.await(): T {
    return suspendCoroutine { continuation ->
        addListener({
            try {
                continuation.resume(get())
            } catch (e: Exception) {
                continuation.resumeWith(Result.failure(e))
            }
        }, Executors.newSingleThreadExecutor())
    }
}
