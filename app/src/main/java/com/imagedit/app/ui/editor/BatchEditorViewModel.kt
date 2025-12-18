package com.imagedit.app.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imagedit.app.domain.model.*
import com.imagedit.app.domain.repository.FilterPreset
import com.imagedit.app.domain.repository.ImageProcessor
import com.imagedit.app.domain.repository.PhotoRepository
import com.imagedit.app.domain.repository.PresetRepository
import com.imagedit.app.util.image.ImageUtils
import android.graphics.Bitmap.CompressFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Represents a photo being edited in batch mode
 */
data class BatchPhoto(
    val uri: Uri,
    val originalBitmap: Bitmap? = null,
    val processedBitmap: Bitmap? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

/**
 * UI state for batch photo editing
 */
data class BatchEditorUiState(
    val photos: List<BatchPhoto> = emptyList(),
    val currentIndex: Int = 0,
    val isLoading: Boolean = false,
    val isProcessing: Boolean = false,
    val isSaving: Boolean = false,
    val saveProgress: Float = 0f,
    val savedCount: Int = 0,
    val error: String? = null,
    val adjustments: AdjustmentParameters = AdjustmentParameters(),
    val filmGrain: FilmGrain = FilmGrain.default(),
    val lensEffects: LensEffects = LensEffects.default(),
    val availablePresets: List<FilterPreset> = emptyList(),
    val selectedPreset: FilterPreset? = null,
    val hasUnsavedChanges: Boolean = false,
    val rotation: Int = 0,
    val isFlippedHorizontally: Boolean = false,
    val isFlippedVertically: Boolean = false
)

@HiltViewModel
class BatchEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoRepository: PhotoRepository,
    private val imageProcessor: ImageProcessor,
    private val presetRepository: PresetRepository
) : ViewModel() {
    
    companion object {
        private const val TAG = "BatchEditorViewModel"
        private const val PREVIEW_SIZE = 800 // Smaller preview for batch editing
        private const val MAX_FULL_RES_SIZE = 4096 // Cap full resolution to prevent OOM
        private const val MAX_BATCH_SIZE = 20 // Maximum photos in a batch to prevent memory issues
    }
    
    private val _uiState = MutableStateFlow(BatchEditorUiState())
    val uiState: StateFlow<BatchEditorUiState> = _uiState.asStateFlow()
    
    private var processingJob: Job? = null
    
    init {
        loadPresets()
    }
    
    /**
     * Load multiple photos for batch editing
     */
    fun loadPhotos(photoUris: List<String>) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                
                // Limit batch size to prevent memory issues
                val limitedUris = if (photoUris.size > MAX_BATCH_SIZE) {
                    Log.w(TAG, "Batch size ${photoUris.size} exceeds limit $MAX_BATCH_SIZE, truncating")
                    photoUris.take(MAX_BATCH_SIZE)
                } else {
                    photoUris
                }
                
                val batchPhotos = limitedUris.map { uriString ->
                    BatchPhoto(uri = Uri.parse(uriString), isLoading = true)
                }
                
                _uiState.value = _uiState.value.copy(photos = batchPhotos)
                
                // Load each photo in parallel with limited concurrency
                val loadedPhotos = withContext(Dispatchers.IO) {
                    batchPhotos.mapIndexed { index, batchPhoto ->
                        try {
                            val bitmap = ImageUtils.loadBitmap(
                                context, 
                                batchPhoto.uri, 
                                maxWidth = PREVIEW_SIZE, 
                                maxHeight = PREVIEW_SIZE
                            )
                            if (bitmap != null) {
                                batchPhoto.copy(
                                    originalBitmap = bitmap,
                                    processedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false),
                                    isLoading = false
                                )
                            } else {
                                batchPhoto.copy(
                                    isLoading = false,
                                    error = "Failed to load image"
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error loading photo: ${batchPhoto.uri}", e)
                            batchPhoto.copy(
                                isLoading = false,
                                error = e.message ?: "Unknown error"
                            )
                        }
                    }
                }
                
                _uiState.value = _uiState.value.copy(
                    photos = loadedPhotos,
                    isLoading = false
                )
                
                Log.d(TAG, "Loaded ${loadedPhotos.size} photos for batch editing")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error loading photos", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load photos: ${e.message}"
                )
            }
        }
    }
    
    private fun loadPresets() {
        viewModelScope.launch {
            presetRepository.getAllPresets().collect { presets ->
                _uiState.value = _uiState.value.copy(availablePresets = presets)
            }
        }
    }
    
    /**
     * Navigate to a specific photo in the batch
     */
    fun setCurrentIndex(index: Int) {
        val photos = _uiState.value.photos
        if (index in photos.indices) {
            _uiState.value = _uiState.value.copy(currentIndex = index)
        }
    }
    
    /**
     * Navigate to next photo
     */
    fun nextPhoto() {
        val currentIndex = _uiState.value.currentIndex
        val maxIndex = _uiState.value.photos.size - 1
        if (currentIndex < maxIndex) {
            setCurrentIndex(currentIndex + 1)
        }
    }
    
    /**
     * Navigate to previous photo
     */
    fun previousPhoto() {
        val currentIndex = _uiState.value.currentIndex
        if (currentIndex > 0) {
            setCurrentIndex(currentIndex - 1)
        }
    }
    
    /**
     * Update adjustments and apply to all photos
     */
    fun updateAdjustments(adjustments: AdjustmentParameters) {
        _uiState.value = _uiState.value.copy(
            adjustments = adjustments,
            hasUnsavedChanges = true,
            selectedPreset = null
        )
        processAllPhotosWithDebounce()
    }
    
    fun updateFilmGrain(filmGrain: FilmGrain) {
        _uiState.value = _uiState.value.copy(
            filmGrain = filmGrain,
            hasUnsavedChanges = true,
            selectedPreset = null
        )
        processAllPhotosWithDebounce()
    }
    
    fun updateLensEffects(lensEffects: LensEffects) {
        _uiState.value = _uiState.value.copy(
            lensEffects = lensEffects,
            hasUnsavedChanges = true,
            selectedPreset = null
        )
        processAllPhotosWithDebounce()
    }
    
    fun applyPreset(preset: FilterPreset) {
        _uiState.value = _uiState.value.copy(
            adjustments = preset.adjustments,
            filmGrain = preset.filmGrain,
            lensEffects = preset.lensEffects,
            selectedPreset = preset,
            hasUnsavedChanges = true
        )
        processAllPhotosWithDebounce()
    }
    
    fun rotateLeft() {
        val newRotation = (_uiState.value.rotation - 90 + 360) % 360
        _uiState.value = _uiState.value.copy(
            rotation = newRotation,
            hasUnsavedChanges = true
        )
        processAllPhotosWithDebounce()
    }
    
    fun rotateRight() {
        val newRotation = (_uiState.value.rotation + 90) % 360
        _uiState.value = _uiState.value.copy(
            rotation = newRotation,
            hasUnsavedChanges = true
        )
        processAllPhotosWithDebounce()
    }
    
    fun flipHorizontally() {
        _uiState.value = _uiState.value.copy(
            isFlippedHorizontally = !_uiState.value.isFlippedHorizontally,
            hasUnsavedChanges = true
        )
        processAllPhotosWithDebounce()
    }
    
    fun flipVertically() {
        _uiState.value = _uiState.value.copy(
            isFlippedVertically = !_uiState.value.isFlippedVertically,
            hasUnsavedChanges = true
        )
        processAllPhotosWithDebounce()
    }
    
    fun resetToOriginal() {
        _uiState.value = _uiState.value.copy(
            adjustments = AdjustmentParameters(),
            filmGrain = FilmGrain.default(),
            lensEffects = LensEffects.default(),
            rotation = 0,
            isFlippedHorizontally = false,
            isFlippedVertically = false,
            hasUnsavedChanges = false,
            selectedPreset = null
        )
        
        // Reset all photos to original
        val resetPhotos = _uiState.value.photos.map { photo ->
            photo.copy(processedBitmap = photo.originalBitmap?.copy(Bitmap.Config.ARGB_8888, false))
        }
        _uiState.value = _uiState.value.copy(photos = resetPhotos)
    }
    
    private fun processAllPhotosWithDebounce() {
        processingJob?.cancel()
        processingJob = viewModelScope.launch {
            delay(200) // Slightly longer debounce for batch processing
            processAllPhotos()
        }
    }
    
    private fun processAllPhotos() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isProcessing = true)
                
                val state = _uiState.value
                val processedPhotos = withContext(Dispatchers.Default) {
                    state.photos.map { photo ->
                        if (photo.originalBitmap == null) {
                            photo
                        } else {
                            try {
                                val processed = processPhoto(
                                    photo.originalBitmap,
                                    state.adjustments,
                                    state.filmGrain,
                                    state.lensEffects,
                                    state.rotation,
                                    state.isFlippedHorizontally,
                                    state.isFlippedVertically
                                )
                                photo.copy(processedBitmap = processed)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing photo: ${photo.uri}", e)
                                photo.copy(error = e.message)
                            }
                        }
                    }
                }
                
                _uiState.value = _uiState.value.copy(
                    photos = processedPhotos,
                    isProcessing = false
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing photos", e)
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    error = "Processing failed: ${e.message}"
                )
            }
        }
    }
    
    private suspend fun processPhoto(
        originalBitmap: Bitmap,
        adjustments: AdjustmentParameters,
        filmGrain: FilmGrain,
        lensEffects: LensEffects,
        rotation: Int,
        isFlippedHorizontally: Boolean,
        isFlippedVertically: Boolean
    ): Bitmap? {
        // Apply transformations
        val matrix = android.graphics.Matrix()
        
        if (rotation != 0) {
            matrix.postRotate(
                rotation.toFloat(),
                originalBitmap.width / 2f,
                originalBitmap.height / 2f
            )
        }
        
        val scaleX = if (isFlippedHorizontally) -1f else 1f
        val scaleY = if (isFlippedVertically) -1f else 1f
        if (scaleX != 1f || scaleY != 1f) {
            matrix.postScale(scaleX, scaleY, originalBitmap.width / 2f, originalBitmap.height / 2f)
        }
        
        val transformedBitmap = if (!matrix.isIdentity) {
            Bitmap.createBitmap(
                originalBitmap,
                0, 0,
                originalBitmap.width,
                originalBitmap.height,
                matrix,
                true
            )
        } else {
            originalBitmap
        }
        
        // Apply filters
        val result = imageProcessor.processImage(
            bitmap = transformedBitmap,
            adjustments = adjustments,
            filmGrain = filmGrain,
            lensEffects = lensEffects
        )
        
        return result.getOrNull()
    }
    
    /**
     * Save all edited photos
     */
    fun saveAllPhotos(onComplete: (Int, Int) -> Unit) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    isSaving = true,
                    saveProgress = 0f,
                    savedCount = 0
                )
                
                val state = _uiState.value
                val photos = state.photos.filter { it.originalBitmap != null }
                var successCount = 0
                var failCount = 0
                
                photos.forEachIndexed { index, photo ->
                    try {
                        // Load full resolution with safety cap to prevent OOM
                        val fullRes = withContext(Dispatchers.IO) {
                            // Request GC before loading large image
                            System.gc()
                            
                            ImageUtils.loadBitmap(
                                context,
                                photo.uri,
                                maxWidth = MAX_FULL_RES_SIZE,
                                maxHeight = MAX_FULL_RES_SIZE
                            )
                        }
                        
                        if (fullRes != null) {
                            // Process at full resolution
                            val processed = withContext(Dispatchers.Default) {
                                processPhoto(
                                    fullRes,
                                    state.adjustments,
                                    state.filmGrain,
                                    state.lensEffects,
                                    state.rotation,
                                    state.isFlippedHorizontally,
                                    state.isFlippedVertically
                                )
                            }
                            
                            if (processed != null) {
                                // Save to gallery using ImageUtils
                                val filename = "batch_edited_${System.currentTimeMillis()}_${index}.jpg"
                                val saveResult = withContext(Dispatchers.IO) {
                                    ImageUtils.saveBitmap(
                                        context = context,
                                        bitmap = processed,
                                        filename = filename,
                                        originalDateTaken = null,
                                        sourceUri = photo.uri,
                                        format = CompressFormat.JPEG,
                                        quality = 95,
                                        mimeType = "image/jpeg"
                                    )
                                }
                                
                                saveResult.fold(
                                    onSuccess = { savedUri ->
                                        // Register with photo repository
                                        val savedPhoto = Photo(
                                            id = filename,
                                            uri = savedUri,
                                            name = filename,
                                            timestamp = System.currentTimeMillis(),
                                            width = processed.width,
                                            height = processed.height,
                                            size = 0L,
                                            hasEdits = true
                                        )
                                        photoRepository.savePhoto(savedPhoto)
                                        successCount++
                                    },
                                    onFailure = {
                                        failCount++
                                    }
                                )
                            } else {
                                failCount++
                            }
                        } else {
                            failCount++
                        }
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Error saving photo: ${photo.uri}", e)
                        failCount++
                    }
                    
                    // Update progress
                    val progress = (index + 1).toFloat() / photos.size
                    _uiState.value = _uiState.value.copy(
                        saveProgress = progress,
                        savedCount = successCount
                    )
                }
                
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    hasUnsavedChanges = false
                )
                
                onComplete(successCount, failCount)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error saving photos", e)
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = "Failed to save photos: ${e.message}"
                )
                onComplete(0, _uiState.value.photos.size)
            }
        }
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    /**
     * Get the current photo being displayed
     */
    fun getCurrentPhoto(): BatchPhoto? {
        val photos = _uiState.value.photos
        val index = _uiState.value.currentIndex
        return if (index in photos.indices) photos[index] else null
    }
}
