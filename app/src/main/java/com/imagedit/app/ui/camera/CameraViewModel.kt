package com.imagedit.app.ui.camera

import android.content.Context
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Camera
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imagedit.app.domain.model.CapturedPhotoItem
import com.imagedit.app.domain.model.PhotoCaptureSession
import com.imagedit.app.domain.repository.PhotoRepository
import com.imagedit.app.util.image.ThumbnailGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import android.util.Size

enum class GridType {
    NONE,
    RULE_OF_THIRDS,
    GOLDEN_RATIO,
    CENTER_CROSS
}

enum class TimerDuration(val seconds: Int, val label: String) {
    OFF(0, "Off"),
    THREE_SEC(3, "3s"),
    FIVE_SEC(5, "5s"),
    TEN_SEC(10, "10s")
}

enum class FlashMode { OFF, ON, AUTO }

data class CameraUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val lastCapturedPhoto: Uri? = null,
    val gridType: GridType = GridType.NONE,
    val timerDuration: TimerDuration = TimerDuration.OFF,
    val isTimerCountingDown: Boolean = false,
    val remainingSeconds: Int = 0,
    val cameraSettings: CameraSettings = CameraSettings(),
    val flashMode: FlashMode = FlashMode.AUTO,
    val zoomRatio: Float = 1f,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f
)

data class Resolution(val width: Int, val height: Int) {
    override fun toString(): String = "${width}x${height}"
    fun toSize(): Size = Size(width, height)
}

data class CameraSettings(
    val availableResolutions: List<Resolution> = emptyList(),
    val selectedResolution: Resolution? = null
)

@HiltViewModel
class CameraViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoRepository: PhotoRepository,
    private val thumbnailGenerator: ThumbnailGenerator
) : ViewModel() {
    
    var uiState by mutableStateOf(CameraUiState())
        private set
    
    // Photo capture session state
    private val _captureSession = MutableStateFlow(PhotoCaptureSession())
    val captureSession: StateFlow<PhotoCaptureSession> = _captureSession.asStateFlow()
    
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var currentSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var configUpdateJob: Job? = null
    private var isPaused: Boolean = false // Track if camera is paused but not released
    
    fun setupCamera(
        lifecycleOwner: LifecycleOwner,
        preview: Preview,
        onCameraReady: () -> Unit
    ) {
        // PERFORMANCE: Defer camera setup to let UI fully render first
        // This helps prevent "Skipped frames" on startup
        viewModelScope.launch {
            delay(300) // Allow UI to settle before camera initialization
            setupCameraInternal(lifecycleOwner, preview, onCameraReady)
        }
    }
    
    private fun setupCameraInternal(
        lifecycleOwner: LifecycleOwner,
        preview: Preview,
        onCameraReady: () -> Unit
    ) {
        try {
            uiState = uiState.copy(isLoading = true, error = null)

            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            
            // Use addListener to avoid blocking the main thread
            cameraProviderFuture.addListener({
                try {
                    cameraProvider = cameraProviderFuture.get()

                    // Query available resolutions before binding
                    queryAvailableResolutions(currentSelector)

                    // Build ImageCapture with selected or fallback resolution
                    val imageCaptureBuilder = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)

                    val selectedRes = uiState.cameraSettings.selectedResolution
                    val resolutionSelector = if (selectedRes != null) {
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    selectedRes.toSize(),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                )
                            )
                            .build()
                    } else {
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                            .build()
                    }
                    imageCaptureBuilder.setResolutionSelector(resolutionSelector)
                    imageCapture = imageCaptureBuilder.build()
                    // Apply current flash mode
                    imageCapture?.flashMode = when (uiState.flashMode) {
                        FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
                        FlashMode.ON -> ImageCapture.FLASH_MODE_ON
                        FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
                    }

                    cameraProvider?.unbindAll()
                    camera = cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        currentSelector,
                        preview,
                        imageCapture
                    )

                    // Observe zoom state to keep UI updated
                    camera?.cameraInfo?.zoomState?.observe(lifecycleOwner) { zoomState ->
                        uiState = uiState.copy(
                            zoomRatio = zoomState.zoomRatio,
                            minZoomRatio = zoomState.minZoomRatio,
                            maxZoomRatio = zoomState.maxZoomRatio
                        )
                    }

                    uiState = uiState.copy(isLoading = false)
                    onCameraReady()

                } catch (e: Exception) {
                    uiState = uiState.copy(
                        isLoading = false,
                        error = "Failed to setup camera: ${e.message}"
                    )
                }
            }, androidx.core.content.ContextCompat.getMainExecutor(context))

        } catch (e: Exception) {
            uiState = uiState.copy(
                isLoading = false,
                error = "Failed to initialize camera: ${e.message}"
            )
        }
    }

    private fun aspectRatioFromSize(width: Int, height: Int): Int {
        val longSide = maxOf(width, height).toDouble()
        val shortSide = minOf(width, height).toDouble()
        val ratio = longSide / shortSide
        val diff43 = kotlin.math.abs(ratio - 4.0 / 3.0)
        val diff169 = kotlin.math.abs(ratio - 16.0 / 9.0)
        return if (diff43 <= diff169) {
            androidx.camera.core.AspectRatio.RATIO_4_3
        } else {
            androidx.camera.core.AspectRatio.RATIO_16_9
        }
    }

    private fun queryAvailableResolutions(cameraSelector: CameraSelector) {
        cameraProvider?.let { provider ->
            val cameraInfo = provider.availableCameraInfos.firstOrNull { 
                cameraSelector.filter(listOf(it)).isNotEmpty() 
            }

            cameraInfo?.let {
                val streamMap = Camera2CameraInfo.from(it)
                    .getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val supportedSizes: Array<android.util.Size>? = streamMap?.getOutputSizes(ImageFormat.JPEG)

                val resolutions: List<Resolution> = supportedSizes
                    ?.map { size: android.util.Size -> Resolution(size.width, size.height) }
                    ?.distinctBy { res -> res.width to res.height }
                    ?.sortedByDescending { res -> res.width.toLong() * res.height }
                    ?: emptyList()

                val currentSettings = uiState.cameraSettings
                val preservedSelection = currentSettings.selectedResolution
                val updatedSelection = preservedSelection?.let { selected ->
                    resolutions.firstOrNull { it == selected }
                }
                val finalSelection = updatedSelection ?: resolutions.firstOrNull()

                uiState = uiState.copy(
                    cameraSettings = currentSettings.copy(
                        availableResolutions = resolutions,
                        selectedResolution = finalSelection
                    )
                )

                if (resolutions.isNotEmpty()) {
                    val selectedLabel = finalSelection?.let { "${it.width}x${it.height}" } ?: "none"
                    Log.d(TAG, "Available JPEG resolutions: ${resolutions.joinToString { res -> "${res.width}x${res.height}" }} | selected=$selectedLabel")
                } else {
                    Log.w(TAG, "No JPEG output sizes reported for camera selector $cameraSelector")
                }
            }
        }
    }

    fun switchCamera(lifecycleOwner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        // Toggle selector
        currentSelector = if (currentSelector == CameraSelector.DEFAULT_BACK_CAMERA)
            CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

        // Rebind with new selector
        val ratio = uiState.cameraSettings.selectedResolution?.let { aspectRatioFromSize(it.width, it.height) }
            ?: androidx.camera.core.AspectRatio.RATIO_4_3
        val preview = Preview.Builder()
            .setTargetAspectRatio(ratio)
            .build()
            .also { it.setSurfaceProvider(surfaceProvider) }
        setupCamera(lifecycleOwner, preview) {}
    }

    fun onPinchZoom(zoomChange: Float) {
        val current = uiState.zoomRatio
        val target = (current * zoomChange).coerceIn(uiState.minZoomRatio, uiState.maxZoomRatio)
        camera?.cameraControl?.setZoomRatio(target)
    }
    
    fun capturePhoto(onPhotoCaptured: (Uri) -> Unit = {}) {
        // If timer is enabled, start countdown
        if (uiState.timerDuration != TimerDuration.OFF && !uiState.isTimerCountingDown) {
            startTimerCountdown(onPhotoCaptured)
            return
        }
        
        val imageCapture = imageCapture ?: return
        
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        }
        
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()
        
        uiState = uiState.copy(isLoading = true)
        
        imageCapture.takePicture(
            outputOptions,
            androidx.camera.core.impl.utils.executor.CameraXExecutors.directExecutor(),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri ?: return
                    
                    viewModelScope.launch {
                        try {
                            // PERFORMANCE: Update UI immediately to prevent frame drops
                            // Don't wait for thumbnail generation
                            uiState = uiState.copy(
                                isLoading = false,
                                lastCapturedPhoto = savedUri
                            )
                            
                            // Add to session immediately with null thumbnail
                            // Thumbnail will be generated in background
                            addPhotoToSession(savedUri, null)
                            onPhotoCaptured(savedUri)
                            
                            // PERFORMANCE: Process heavy operations in background with lower priority
                            // This prevents "Skipped 30-40 frames" after photo capture
                            withContext(Dispatchers.IO) {
                                // Save photo metadata to repository
                                val photo = com.imagedit.app.domain.model.Photo(
                                    id = name,
                                    uri = savedUri,
                                    name = "$name.jpg",
                                    timestamp = System.currentTimeMillis(),
                                    width = 0, // Actual dimensions will be set by repository
                                    height = 0,
                                    size = 0L
                                )
                                
                                photoRepository.savePhoto(photo)
                                
                                // PERFORMANCE: Increased delay to let UI fully settle before thumbnail generation
                                // This helps prevent frame drops after photo capture
                                delay(300)
                                
                                // Generate thumbnail in background
                                val thumbnailUri = thumbnailGenerator.generateThumbnail(savedUri)
                                
                                // Update session with thumbnail
                                if (thumbnailUri != null) {
                                    updatePhotoThumbnailInSession(savedUri, thumbnailUri)
                                }
                            }
                            
                        } catch (e: Exception) {
                            uiState = uiState.copy(
                                isLoading = false,
                                error = "Failed to save photo: ${e.message}"
                            )
                        }
                    }
                }
                
                override fun onError(exception: ImageCaptureException) {
                    uiState = uiState.copy(
                        isLoading = false,
                        error = "Photo capture failed: ${exception.message}"
                    )
                }
            }
        )
    }
    
    /**
     * Add a photo to the current capture session
     */
    private fun addPhotoToSession(photoUri: Uri, thumbnailUri: Uri?) {
        val currentSession = _captureSession.value
        val newPhoto = CapturedPhotoItem(
            uri = photoUri.toString(),
            thumbnailUri = thumbnailUri?.toString(),
            timestamp = System.currentTimeMillis(),
            isSelected = false
        )
        
        _captureSession.value = currentSession.copy(
            capturedPhotos = currentSession.capturedPhotos + newPhoto,
            isActive = true
        )
    }
    
    /**
     * Update thumbnail URI for a photo in the session (called after background thumbnail generation)
     */
    private fun updatePhotoThumbnailInSession(photoUri: Uri, thumbnailUri: Uri) {
        val currentSession = _captureSession.value
        val updatedPhotos = currentSession.capturedPhotos.map { photo ->
            if (photo.uri == photoUri.toString()) {
                photo.copy(thumbnailUri = thumbnailUri.toString())
            } else {
                photo
            }
        }
        _captureSession.value = currentSession.copy(capturedPhotos = updatedPhotos)
    }
    
    /**
     * Select a photo from the session
     */
    fun selectPhotoFromSession(uri: Uri) {
        _captureSession.value = _captureSession.value.copy(
            selectedPhotoUri = uri.toString()
        )
    }
    
    /**
     * Remove a photo from the session
     */
    fun removePhotoFromSession(uri: Uri) {
        val currentSession = _captureSession.value
        val updatedPhotos = currentSession.capturedPhotos.filter { it.uri != uri.toString() }
        
        _captureSession.value = currentSession.copy(
            capturedPhotos = updatedPhotos,
            selectedPhotoUri = if (currentSession.selectedPhotoUri == uri.toString()) null else currentSession.selectedPhotoUri,
            isActive = updatedPhotos.isNotEmpty()
        )
    }
    
    /**
     * End the current capture session and clear all photos
     */
    fun endSession() {
        thumbnailGenerator.clearThumbnailCache()
        _captureSession.value = PhotoCaptureSession()
    }
    
    /**
     * Start a new capture session
     */
    fun startNewSession() {
        _captureSession.value = PhotoCaptureSession(isActive = true)
    }
    
    /**
     * Toggle multi-select mode for batch editing
     */
    fun toggleMultiSelectMode() {
        val currentSession = _captureSession.value
        _captureSession.value = currentSession.copy(
            isMultiSelectMode = !currentSession.isMultiSelectMode,
            selectedPhotoUris = if (currentSession.isMultiSelectMode) emptySet() else currentSession.selectedPhotoUris,
            selectedPhotoUri = if (!currentSession.isMultiSelectMode) null else currentSession.selectedPhotoUri
        )
    }
    
    /**
     * Toggle selection of a photo in multi-select mode
     */
    fun togglePhotoMultiSelection(uri: Uri) {
        val currentSession = _captureSession.value
        val uriString = uri.toString()
        val newSelection = if (uriString in currentSession.selectedPhotoUris) {
            currentSession.selectedPhotoUris - uriString
        } else {
            currentSession.selectedPhotoUris + uriString
        }
        _captureSession.value = currentSession.copy(selectedPhotoUris = newSelection)
    }
    
    /**
     * Select all photos in multi-select mode
     */
    fun selectAllPhotos() {
        val currentSession = _captureSession.value
        _captureSession.value = currentSession.copy(
            selectedPhotoUris = currentSession.capturedPhotos.map { it.uri }.toSet()
        )
    }
    
    /**
     * Clear all selections in multi-select mode
     */
    fun clearMultiSelection() {
        _captureSession.value = _captureSession.value.copy(selectedPhotoUris = emptySet())
    }
    
    /**
     * Get list of selected photo URIs for batch editing
     */
    fun getSelectedPhotoUris(): List<String> {
        return _captureSession.value.selectedPhotoUris.toList()
    }
    
    fun clearError() {
        uiState = uiState.copy(error = null)
    }
    
    /**
     * Toggle grid overlay type
     */
    fun toggleGrid() {
        val nextGrid = when (uiState.gridType) {
            GridType.NONE -> GridType.RULE_OF_THIRDS
            GridType.RULE_OF_THIRDS -> GridType.GOLDEN_RATIO
            GridType.GOLDEN_RATIO -> GridType.CENTER_CROSS
            GridType.CENTER_CROSS -> GridType.NONE
        }
        uiState = uiState.copy(gridType = nextGrid)
    }
    
    /**
     * Toggle timer duration
     */
    fun toggleTimer() {
        val nextTimer = when (uiState.timerDuration) {
            TimerDuration.OFF -> TimerDuration.THREE_SEC
            TimerDuration.THREE_SEC -> TimerDuration.FIVE_SEC
            TimerDuration.FIVE_SEC -> TimerDuration.TEN_SEC
            TimerDuration.TEN_SEC -> TimerDuration.OFF
        }
        uiState = uiState.copy(timerDuration = nextTimer)
    }

    fun toggleFlashMode() {
        val next = when (uiState.flashMode) {
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.OFF
            FlashMode.OFF -> FlashMode.AUTO
        }
        uiState = uiState.copy(flashMode = next)
        imageCapture?.flashMode = when (next) {
            FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
        }
    }
    
    /**
     * Start timer countdown before capturing photo
     */
    private fun startTimerCountdown(onPhotoCaptured: (Uri) -> Unit) {
        viewModelScope.launch {
            uiState = uiState.copy(
                isTimerCountingDown = true,
                remainingSeconds = uiState.timerDuration.seconds
            )
            
            // Countdown
            for (i in uiState.timerDuration.seconds downTo 1) {
                uiState = uiState.copy(remainingSeconds = i)
                kotlinx.coroutines.delay(1000)
            }
            
            // Capture photo
            uiState = uiState.copy(isTimerCountingDown = false, remainingSeconds = 0)
            capturePhoto(onPhotoCaptured)
        }
    }
    
    /**
     * Cancel timer countdown
     */
    fun cancelTimer() {
        uiState = uiState.copy(isTimerCountingDown = false, remainingSeconds = 0)
    }

    fun changeResolution(resolution: Resolution, lifecycleOwner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        if (uiState.cameraSettings.selectedResolution == resolution) return

        uiState = uiState.copy(
            cameraSettings = uiState.cameraSettings.copy(selectedResolution = resolution)
        )
        
        // Debounce camera reconfiguration to avoid rapid rebinds
        configUpdateJob?.cancel()
        configUpdateJob = viewModelScope.launch {
            delay(300) // Wait 300ms before applying changes
            
            // Re-bind use cases to apply the new resolution
            val ratio = aspectRatioFromSize(resolution.width, resolution.height)
            val preview = Preview.Builder()
                .setTargetAspectRatio(ratio)
                .build()
                .also { it.setSurfaceProvider(surfaceProvider) }
            setupCamera(lifecycleOwner, preview) {}
        }
    }
    
    /**
     * Pause camera without releasing resources (for navigation)
     * This prevents expensive camera close/reopen cycles
     */
    fun pauseCamera() {
        isPaused = true
        // Don't unbind - keep camera alive
    }
    
    /**
     * Resume camera from paused state
     */
    fun resumeCamera() {
        isPaused = false
        // Camera is already bound, no action needed
    }
    
    /**
     * Release camera resources completely (for app exit)
     */
    fun releaseCamera() {
        if (!isPaused) {
            try {
                Log.d(TAG, "Releasing camera resources...")
                
                // Unbind all use cases first
                cameraProvider?.unbindAll()
                
                // Small delay to let unbind complete and prevent race condition
                Thread.sleep(50)
                
                // Clear reference
                imageCapture = null
                
                Log.d(TAG, "Camera released successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing camera", e)
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        
        Log.d(TAG, "ViewModel clearing...")
        
        // Cancel all pending coroutines first to stop ongoing operations
        configUpdateJob?.cancel()
        
        // Small delay to let pending camera operations finish
        // This prevents race condition with HWUI rendering thread
        try {
            Thread.sleep(100)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted during cleanup delay")
        }
        
        // Now safe to release camera resources
        releaseCamera()
        endSession()
        
        Log.d(TAG, "ViewModel cleared successfully")
    }

    private companion object {
        const val TAG = "CameraViewModel"
    }
}
