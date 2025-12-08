package com.imagedit.app.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imagedit.app.domain.model.*
import com.imagedit.app.domain.repository.FilterPreset
import com.imagedit.app.domain.repository.ImageProcessor
import com.imagedit.app.domain.repository.PhotoRepository
import com.imagedit.app.domain.repository.PresetRepository
import com.imagedit.app.domain.repository.SmartProcessor
import com.imagedit.app.data.repository.SettingsRepository
import com.imagedit.app.ui.common.ErrorMessageHelper
import com.imagedit.app.util.image.ImageUtils
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import javax.inject.Inject
import kotlin.math.pow

enum class CropAspectRatio(val label: String, val ratio: Float?) {
    FREE("Free", null),
    SQUARE("1:1", 1f),
    PORTRAIT_3_4("3:4", 3f / 4f),
    PORTRAIT_9_16("9:16", 9f / 16f),
    LANDSCAPE_4_3("4:3", 4f / 3f),
    LANDSCAPE_16_9("16:9", 16f / 9f)
}

data class CropState(
    val isActive: Boolean = false,
    val aspectRatio: CropAspectRatio = CropAspectRatio.FREE,
    val cropRect: androidx.compose.ui.geometry.Rect? = null, // Screen coordinates
    val normalizedCropRect: androidx.compose.ui.geometry.Rect? = null,
    val imageBounds: androidx.compose.ui.geometry.Rect? = null // Screen coordinates
)

data class EditorUiState(
    val originalBitmap: Bitmap? = null,
    val processedBitmap: Bitmap? = null,
    val isLoading: Boolean = false,
    val isProcessing: Boolean = false,
    val error: String? = null,
    val hasUnsavedChanges: Boolean = false,
    val adjustments: AdjustmentParameters = AdjustmentParameters(),
    val filmGrain: FilmGrain = FilmGrain.default(),
    val lensEffects: LensEffects = LensEffects.default(),
    val availablePresets: List<FilterPreset> = emptyList(),
    val selectedPreset: FilterPreset? = null,
    val undoStack: List<AdjustmentState> = emptyList(),
    val redoStack: List<AdjustmentState> = emptyList(),
    val rotation: Int = 0, // 0, 90, 180, 270 degrees
    val isFlippedHorizontally: Boolean = false,
    val isFlippedVertically: Boolean = false,
    val cropState: CropState = CropState(),
    val appliedNormalizedCropRect: androidx.compose.ui.geometry.Rect? = null,
    // Before/After comparison state
    val isShowingBeforeAfter: Boolean = false,
    val beforeAfterTransitionProgress: Float = 0f, // 0f = before, 1f = after
    val smartEnhancementApplied: Boolean = false,
    val smartEnhancementAdjustments: AdjustmentParameters? = null,
    // Portrait enhancement state
    val portraitEnhancementIntensity: Float = 0.5f,
    val isPortraitEnhancementActive: Boolean = false,
    val portraitEnhancementApplied: Boolean = false,
    // Scene analysis state
    val sceneAnalysis: SceneAnalysis? = null,
    val isAnalyzingScene: Boolean = false,
    val sceneAnalysisError: String? = null,
    val manualSceneOverride: SceneType? = null,
    // Landscape enhancement state
    val landscapeAnalysis: com.imagedit.app.domain.model.LandscapeAnalysis? = null,
    val landscapeEnhancementParameters: com.imagedit.app.domain.model.LandscapeEnhancementParameters = com.imagedit.app.domain.model.LandscapeEnhancementParameters(),
    val isLandscapeEnhancementActive: Boolean = false,
    val landscapeEnhancementApplied: Boolean = false,
    // Healing tool state
    val isHealingToolActive: Boolean = false,
    val healingBrushSettings: HealingBrush = HealingBrush(),
    val healingStrokes: List<BrushStroke> = emptyList(),
    val isHealingProcessing: Boolean = false,
    val healingProgress: Float = -1f, // -1 for indeterminate, 0-1 for progress
    val healingValidation: HealingValidation? = null,
    val healingSourceCandidates: List<SourceCandidate> = emptyList(),
    val selectedHealingSource: SourceCandidate? = null
)

data class AdjustmentState(
    val adjustments: AdjustmentParameters,
    val filmGrain: FilmGrain,
    val lensEffects: LensEffects,
    val rotation: Int,
    val isFlippedHorizontally: Boolean,
    val isFlippedVertically: Boolean,
    val bitmap: Bitmap? = null,  // Store bitmap for crop/destructive operations
    val actionType: String = "adjustment"  // For history visualization
)

@HiltViewModel
class PhotoEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoRepository: PhotoRepository,
    private val imageProcessor: ImageProcessor,
    private val presetRepository: PresetRepository,
    private val smartProcessor: SmartProcessor,
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    
    companion object {
        private const val TAG = "PhotoEditorViewModel"
    }
    
    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()
    
    private var processingJob: Job? = null
    private var healingJob: Job? = null
    private var smartEnhancementJob: Job? = null
    private var portraitEnhancementJob: Job? = null
    private var currentProcessingJob: Job? = null
    private var currentPhotoUri: Uri? = null
    private var originalDateTaken: Long? = null
    
    init {
        loadPresets()
    }
    
    fun loadPhoto(photoUri: String) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                
                val uri = Uri.parse(photoUri)
                currentPhotoUri = uri
                
                // Offload heavy I/O and bitmap operations to background thread
                val result = withContext(Dispatchers.IO) {
                    try {
                        // Get original date taken metadata
                        val dateTaken = ImageUtils.getOriginalDateTaken(context, uri)
                        
                        // Load bitmap at preview resolution for better performance
                        // Full resolution will be used when saving
                        val bitmap = ImageUtils.loadBitmap(context, uri, maxWidth = 1024, maxHeight = 1024)
                        
                        if (bitmap != null) {
                            // Create copy in background thread
                            val processedCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                            Triple(bitmap, processedCopy, dateTaken)
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading photo in background", e)
                        null
                    }
                }
                
                if (result != null) {
                    val (bitmap, processedCopy, dateTaken) = result
                    originalDateTaken = dateTaken
                    _uiState.value = _uiState.value.copy(
                        originalBitmap = bitmap,
                        processedBitmap = processedCopy,
                        isLoading = false,
                        hasUnsavedChanges = false
                    )
                    
                    // Perform scene analysis on the loaded photo
                    performSceneAnalysis(bitmap)
                } else {
                    handleError(Exception("Image file not found or corrupted"), "load image")
                }
            } catch (e: Exception) {
                handleError(e, "load image")
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
    
    private var sceneAnalysisJob: Job? = null
    
    private fun performSceneAnalysis(bitmap: Bitmap) {
        // Cancel any previous scene analysis
        sceneAnalysisJob?.cancel()
        
        sceneAnalysisJob = viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    isAnalyzingScene = true,
                    sceneAnalysisError = null
                )
                
                // PERFORMANCE: Downsample image for faster scene analysis
                // Scene analysis doesn't need full resolution - 480x640 is sufficient
                val analysisResult = withContext(Dispatchers.Default) {
                    val maxAnalysisSize = 480
                    val scaledBitmap = if (bitmap.width > maxAnalysisSize || bitmap.height > maxAnalysisSize) {
                        val scale = maxAnalysisSize.toFloat() / maxOf(bitmap.width, bitmap.height)
                        val newWidth = (bitmap.width * scale).toInt()
                        val newHeight = (bitmap.height * scale).toInt()
                        Log.d(TAG, "Downsampling for scene analysis: ${bitmap.width}x${bitmap.height} -> ${newWidth}x${newHeight}")
                        Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                    } else {
                        bitmap
                    }
                    
                    // Add timeout to prevent scene analysis from blocking too long
                    try {
                        withTimeout(10_000L) { // 10 second timeout
                            smartProcessor.analyzeScene(scaledBitmap)
                        }
                    } finally {
                        // Recycle scaled bitmap if we created one
                        if (scaledBitmap !== bitmap && !scaledBitmap.isRecycled) {
                            scaledBitmap.recycle()
                        }
                    }
                }
                
                analysisResult.fold(
                    onSuccess = { sceneAnalysis ->
                        // Use scene analysis result as-is (trust the detector)
                        // Scene priority logic disabled - it was overriding detection incorrectly
                        
                        _uiState.value = _uiState.value.copy(
                            sceneAnalysis = sceneAnalysis,
                            isAnalyzingScene = false,
                            sceneAnalysisError = null
                        )
                        
                        // Enhanced logging for scene analysis results
                        Log.d(TAG, "Scene analysis completed: ${sceneAnalysis.sceneType} (confidence: ${sceneAnalysis.confidence})")
                        Log.d(TAG, "Suggested enhancements: ${sceneAnalysis.suggestedEnhancements.map { it.type }}")
                        Log.d(TAG, "Color profile: warmth=${sceneAnalysis.colorProfile.warmth}, saturation=${sceneAnalysis.colorProfile.saturationLevel}")
                        Log.d(TAG, "Lighting: type=${sceneAnalysis.lightingConditions.lightingType}, brightness=${sceneAnalysis.lightingConditions.brightness}")
                        Log.d(TAG, "Dominant colors: ${sceneAnalysis.colorProfile.dominantColors.size} colors detected")
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            isAnalyzingScene = false,
                            sceneAnalysisError = "Scene analysis failed: ${error.message}"
                        )
                        Log.e(TAG, "Scene analysis failed", error)
                    }
                )
            } catch (e: TimeoutCancellationException) {
                _uiState.value = _uiState.value.copy(
                    isAnalyzingScene = false,
                    sceneAnalysisError = null // Don't show error for timeout, just skip analysis
                )
                Log.w(TAG, "Scene analysis timed out after 10 seconds")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Job was cancelled (e.g., navigating away), don't update state
                Log.d(TAG, "Scene analysis cancelled")
                throw e // Re-throw to properly cancel the coroutine
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isAnalyzingScene = false,
                    sceneAnalysisError = "Scene analysis failed: ${e.message}"
                )
                Log.e(TAG, "Scene analysis exception", e)
            }
        }
    }
    
    fun updateAdjustments(adjustments: AdjustmentParameters) {
        saveCurrentStateToUndo(actionType = "adjustment")
        clearSmartEnhancementState()
        _uiState.value = _uiState.value.copy(
            adjustments = adjustments,
            hasUnsavedChanges = true,
            selectedPreset = null
        )
        processImageWithDebounce()
    }
    
    fun updateFilmGrain(filmGrain: FilmGrain) {
        saveCurrentStateToUndo(actionType = "film grain")
        _uiState.value = _uiState.value.copy(
            filmGrain = filmGrain,
            hasUnsavedChanges = true,
            selectedPreset = null
        )
        processImageWithDebounce()
    }
    
    fun updateLensEffects(lensEffects: LensEffects) {
        saveCurrentStateToUndo(actionType = "lens effect")
        _uiState.value = _uiState.value.copy(
            lensEffects = lensEffects,
            hasUnsavedChanges = true,
            selectedPreset = null
        )
        processImageWithDebounce()
    }
    
    fun applyPreset(preset: FilterPreset) {
        saveCurrentStateToUndo(actionType = "preset: ${preset.name}")
        _uiState.value = _uiState.value.copy(
            adjustments = preset.adjustments,
            filmGrain = preset.filmGrain,
            lensEffects = preset.lensEffects,
            selectedPreset = preset,
            hasUnsavedChanges = true
        )
        processImageWithDebounce()
    }
    
    fun saveCurrentAsPreset(name: String) {
        viewModelScope.launch {
            val newPreset = FilterPreset(
                id = "custom_${System.currentTimeMillis()}",
                name = name,
                adjustments = _uiState.value.adjustments,
                filmGrain = _uiState.value.filmGrain,
                lensEffects = _uiState.value.lensEffects,
                isBuiltIn = false
            )
            
            val result = presetRepository.savePreset(newPreset)
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        selectedPreset = newPreset,
                        error = null
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        error = "Failed to save preset: ${error.message}"
                    )
                }
            )
        }
    }
    
    fun updatePreset(presetId: String, newName: String) {
        viewModelScope.launch {
            val preset = presetRepository.getPresetById(presetId)
            if (preset != null && !preset.isBuiltIn) {
                val updatedPreset = preset.copy(name = newName)
                presetRepository.savePreset(updatedPreset)
            }
        }
    }
    
    fun duplicatePreset(preset: FilterPreset, newName: String) {
        viewModelScope.launch {
            val duplicatedPreset = preset.copy(
                id = "custom_${System.currentTimeMillis()}",
                name = newName,
                isBuiltIn = false
            )
            
            val result = presetRepository.savePreset(duplicatedPreset)
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(error = null)
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        error = "Failed to duplicate preset: ${error.message}"
                    )
                }
            )
        }
    }
    
    fun deletePreset(presetId: String) {
        viewModelScope.launch {
            val result = presetRepository.deletePreset(presetId)
            result.fold(
                onSuccess = {
                    if (_uiState.value.selectedPreset?.id == presetId) {
                        _uiState.value = _uiState.value.copy(
                            selectedPreset = null,
                            error = null
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        error = "Failed to delete preset: ${error.message}"
                    )
                }
            )
        }
    }
    
    private fun processImageWithDebounce() {
        processingJob?.cancel()
        processingJob = viewModelScope.launch {
            delay(150) // Reduced debounce delay for better responsiveness
            processImage()
        }
    }
    
    private fun processImage() {
        val originalBitmap = _uiState.value.originalBitmap ?: return
        
        Log.d(TAG, "processImage() - flip H: ${_uiState.value.isFlippedHorizontally}, flip V: ${_uiState.value.isFlippedVertically}, rotation: ${_uiState.value.rotation}")
        
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isProcessing = true)
                
                // Move image processing to background thread
                val processedBitmap = withContext(Dispatchers.Default) {
                    Log.d(TAG, "Starting image processing in background thread")
                    // Step 1: Apply transformations (rotation, flip) to original bitmap
                    val matrix = android.graphics.Matrix()
                    
                    // Apply rotation
                    if (_uiState.value.rotation != 0) {
                        matrix.postRotate(
                            _uiState.value.rotation.toFloat(),
                            originalBitmap.width / 2f,
                            originalBitmap.height / 2f
                        )
                    }
                    
                    // Apply flips
                    val scaleX = if (_uiState.value.isFlippedHorizontally) -1f else 1f
                    val scaleY = if (_uiState.value.isFlippedVertically) -1f else 1f
                    if (scaleX != 1f || scaleY != 1f) {
                        matrix.postScale(scaleX, scaleY, originalBitmap.width / 2f, originalBitmap.height / 2f)
                    }
                    
                    val transformedBitmap = if (!matrix.isIdentity) {
                        Log.d(TAG, "Creating transformed bitmap with matrix")
                        android.graphics.Bitmap.createBitmap(
                            originalBitmap,
                            0,
                            0,
                            originalBitmap.width,
                            originalBitmap.height,
                            matrix,
                            true
                        )
                    } else {
                        Log.d(TAG, "No transformation needed, using original bitmap")
                        originalBitmap
                    }
                    
                    Log.d(TAG, "Transformed bitmap hash: ${transformedBitmap.hashCode()}, original hash: ${originalBitmap.hashCode()}")
                    
                    // Step 2: Apply filters and adjustments to transformed bitmap
                    Log.d(TAG, "Calling imageProcessor.processImage() with adjustments: ${_uiState.value.adjustments}")
                    val result = imageProcessor.processImage(
                        bitmap = transformedBitmap,
                        adjustments = _uiState.value.adjustments,
                        filmGrain = _uiState.value.filmGrain,
                        lensEffects = _uiState.value.lensEffects
                    )
                    
                    Log.d(TAG, "imageProcessor.processImage() result: ${if (result.isSuccess) "SUCCESS" else "FAILURE: ${result.exceptionOrNull()?.message}"}")
                    result.getOrNull()
                }
                
                if (processedBitmap != null) {
                    Log.d(TAG, "Processing successful, updating UI with processed bitmap hash: ${processedBitmap.hashCode()}")
                    Log.d(TAG, "Preview updated: ${processedBitmap.width}x${processedBitmap.height}, config=${processedBitmap.config}")
                    _uiState.value = _uiState.value.copy(
                        processedBitmap = processedBitmap,
                        isProcessing = false
                    )
                } else {
                    Log.e(TAG, "Processing failed: processedBitmap is null")
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        error = "Processing failed"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in processImage(): ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    error = "Processing failed: ${e.message}"
                )
            }
        }
    }
    
    private fun saveCurrentStateToUndo(actionType: String = "adjustment", saveBitmap: Boolean = false) {
        val currentState = AdjustmentState(
            adjustments = _uiState.value.adjustments,
            filmGrain = _uiState.value.filmGrain,
            lensEffects = _uiState.value.lensEffects,
            rotation = _uiState.value.rotation,
            isFlippedHorizontally = _uiState.value.isFlippedHorizontally,
            isFlippedVertically = _uiState.value.isFlippedVertically,
            bitmap = if (saveBitmap) _uiState.value.processedBitmap?.copy(Bitmap.Config.ARGB_8888, false) else null,
            actionType = actionType
        )
        
        val newUndoStack = _uiState.value.undoStack + currentState
        // Limit stack size to 10 when storing bitmaps (memory intensive), 20 otherwise
        val maxStackSize = if (saveBitmap) 10 else 20
        val trimmedUndoStack = if (newUndoStack.size > maxStackSize) {
            newUndoStack.drop(1)
        } else {
            newUndoStack
        }
        
        _uiState.value = _uiState.value.copy(
            undoStack = trimmedUndoStack,
            redoStack = emptyList() // Clear redo stack when new action is performed
        )
    }
    
    fun undo() {
        val undoStack = _uiState.value.undoStack
        if (undoStack.isNotEmpty()) {
            val previousState = undoStack.last()
            val newRedoStack = _uiState.value.redoStack + AdjustmentState(
                adjustments = _uiState.value.adjustments,
                filmGrain = _uiState.value.filmGrain,
                lensEffects = _uiState.value.lensEffects,
                rotation = _uiState.value.rotation,
                isFlippedHorizontally = _uiState.value.isFlippedHorizontally,
                isFlippedVertically = _uiState.value.isFlippedVertically,
                bitmap = _uiState.value.processedBitmap?.copy(Bitmap.Config.ARGB_8888, false),
                actionType = "current"
            )
            
            _uiState.value = _uiState.value.copy(
                adjustments = previousState.adjustments,
                filmGrain = previousState.filmGrain,
                lensEffects = previousState.lensEffects,
                rotation = previousState.rotation,
                isFlippedHorizontally = previousState.isFlippedHorizontally,
                isFlippedVertically = previousState.isFlippedVertically,
                processedBitmap = previousState.bitmap ?: _uiState.value.processedBitmap,  // Restore bitmap if present
                undoStack = undoStack.dropLast(1),
                redoStack = newRedoStack,
                hasUnsavedChanges = true,
                selectedPreset = null
            )
            
            // Only apply transformations if no bitmap was restored (non-destructive operations)
            if (previousState.bitmap == null) {
                applyTransformations()
            }
        }
    }
    
    fun redo() {
        val redoStack = _uiState.value.redoStack
        if (redoStack.isNotEmpty()) {
            val nextState = redoStack.last()
            val newUndoStack = _uiState.value.undoStack + AdjustmentState(
                adjustments = _uiState.value.adjustments,
                filmGrain = _uiState.value.filmGrain,
                lensEffects = _uiState.value.lensEffects,
                rotation = _uiState.value.rotation,
                isFlippedHorizontally = _uiState.value.isFlippedHorizontally,
                isFlippedVertically = _uiState.value.isFlippedVertically,
                bitmap = _uiState.value.processedBitmap?.copy(Bitmap.Config.ARGB_8888, false),
                actionType = "current"
            )
            
            _uiState.value = _uiState.value.copy(
                adjustments = nextState.adjustments,
                filmGrain = nextState.filmGrain,
                lensEffects = nextState.lensEffects,
                rotation = nextState.rotation,
                isFlippedHorizontally = nextState.isFlippedHorizontally,
                isFlippedVertically = nextState.isFlippedVertically,
                processedBitmap = nextState.bitmap ?: _uiState.value.processedBitmap,  // Restore bitmap if present
                undoStack = newUndoStack,
                redoStack = redoStack.dropLast(1),
                hasUnsavedChanges = true,
                selectedPreset = null
            )
            
            // Only apply transformations if no bitmap was restored (non-destructive operations)
            if (nextState.bitmap == null) {
                applyTransformations()
            }
        }
    }
    
    /**
     * Get undo history for UI visualization
     */
    fun getUndoHistory(): List<String> {
        return _uiState.value.undoStack.map { it.actionType }
    }
    
    /**
     * Get redo history for UI visualization
     */
    fun getRedoHistory(): List<String> {
        return _uiState.value.redoStack.map { it.actionType }
    }
    
    /**
     * Check if undo is available
     */
    fun canUndo(): Boolean {
        return _uiState.value.undoStack.isNotEmpty()
    }
    
    /**
     * Check if redo is available
     */
    fun canRedo(): Boolean {
        return _uiState.value.redoStack.isNotEmpty()
    }
    
    fun resetToOriginal() {
        saveCurrentStateToUndo(actionType = "reset")
        _uiState.value = _uiState.value.copy(
            adjustments = AdjustmentParameters(),
            filmGrain = FilmGrain.default(),
            lensEffects = LensEffects.default(),
            rotation = 0,
            isFlippedHorizontally = false,
            isFlippedVertically = false,
            processedBitmap = _uiState.value.originalBitmap?.copy(Bitmap.Config.ARGB_8888, false),
            hasUnsavedChanges = false,
            selectedPreset = null,
            appliedNormalizedCropRect = null,
            cropState = CropState(isActive = false)
        )
    }
    
    // Rotation and Flip functions
    fun rotateLeft() {
        saveCurrentStateToUndo(actionType = "rotate left")
        val newRotation = (_uiState.value.rotation - 90 + 360) % 360
        _uiState.value = _uiState.value.copy(
            rotation = newRotation,
            hasUnsavedChanges = true,
            selectedPreset = null
        )
        applyTransformations()
    }
    
    fun rotateRight() {
        saveCurrentStateToUndo(actionType = "rotate right")
        val newRotation = (_uiState.value.rotation + 90) % 360
        _uiState.value = _uiState.value.copy(
            rotation = newRotation,
            hasUnsavedChanges = true,
            selectedPreset = null
        )
        applyTransformations()
    }
    
    fun flipHorizontally() {
        saveCurrentStateToUndo(actionType = "flip horizontal")
        val newFlipState = !_uiState.value.isFlippedHorizontally
        Log.d(TAG, "flipHorizontally() - new state: $newFlipState")
        _uiState.value = _uiState.value.copy(
            isFlippedHorizontally = newFlipState,
            hasUnsavedChanges = true,
            selectedPreset = null
        )
        applyTransformations()
    }
    
    fun flipVertically() {
        saveCurrentStateToUndo(actionType = "flip vertical")
        val newFlipState = !_uiState.value.isFlippedVertically
        Log.d(TAG, "flipVertically() - new state: $newFlipState")
        _uiState.value = _uiState.value.copy(
            isFlippedVertically = newFlipState,
            hasUnsavedChanges = true,
            selectedPreset = null
        )
        applyTransformations()
    }
    
    private fun applyTransformations() {
        // Always start from original bitmap to ensure consistent transformations
        val originalBitmap = _uiState.value.originalBitmap ?: return
        
        Log.d(TAG, "applyTransformations() - flip H: ${_uiState.value.isFlippedHorizontally}, flip V: ${_uiState.value.isFlippedVertically}, rotation: ${_uiState.value.rotation}")
        
        viewModelScope.launch(Dispatchers.Default) {
            try {
                _uiState.value = _uiState.value.copy(isProcessing = true)
                
                val matrix = android.graphics.Matrix()
                
                // Apply rotation
                if (_uiState.value.rotation != 0) {
                    matrix.postRotate(
                        _uiState.value.rotation.toFloat(),
                        originalBitmap.width / 2f,
                        originalBitmap.height / 2f
                    )
                }
                
                // Apply flips
                val scaleX = if (_uiState.value.isFlippedHorizontally) -1f else 1f
                val scaleY = if (_uiState.value.isFlippedVertically) -1f else 1f
                if (scaleX != 1f || scaleY != 1f) {
                    matrix.postScale(scaleX, scaleY, originalBitmap.width / 2f, originalBitmap.height / 2f)
                }
                
                val transformedBitmap = if (!matrix.isIdentity) {
                    android.graphics.Bitmap.createBitmap(
                        originalBitmap,
                        0,
                        0,
                        originalBitmap.width,
                        originalBitmap.height,
                        matrix,
                        true
                    )
                } else {
                    originalBitmap
                }
                
                // Apply filters and adjustments - these stack on top of transformations
                val result = imageProcessor.processImage(
                    bitmap = transformedBitmap,
                    adjustments = _uiState.value.adjustments,
                    filmGrain = _uiState.value.filmGrain,
                    lensEffects = _uiState.value.lensEffects
                )
                
                result.fold(
                    onSuccess = { processedBitmap ->
                        _uiState.value = _uiState.value.copy(
                            processedBitmap = processedBitmap,
                            isProcessing = false
                        )
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            isProcessing = false,
                            error = "Transformation failed: ${error.message}"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    error = "Transformation failed: ${e.message}"
                )
            }
        }
    }
    
    fun savePhoto(onSaved: (Uri) -> Unit) {
        val state = _uiState.value
        val srcUri = currentPhotoUri ?: return
        
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)
                
                // 0) Load FULL-RES bitmap from source (EXIF-corrected orientation)
                val fullRes = withContext(Dispatchers.IO) {
                    ImageUtils.loadBitmap(
                        context,
                        srcUri,
                        maxWidth = Int.MAX_VALUE,
                        maxHeight = Int.MAX_VALUE
                    )
                } ?: throw Exception("Failed to load full-resolution image")
                
                // 1) Apply rotation and flips on full-resolution
                val transformMatrix = android.graphics.Matrix()
                if (state.rotation != 0) {
                    transformMatrix.postRotate(
                        state.rotation.toFloat(),
                        fullRes.width / 2f,
                        fullRes.height / 2f
                    )
                }
                val scaleX = if (state.isFlippedHorizontally) -1f else 1f
                val scaleY = if (state.isFlippedVertically) -1f else 1f
                if (scaleX != 1f || scaleY != 1f) {
                    transformMatrix.postScale(scaleX, scaleY, fullRes.width / 2f, fullRes.height / 2f)
                }
                val transformedFull = if (!transformMatrix.isIdentity) {
                    Bitmap.createBitmap(
                        fullRes,
                        0,
                        0,
                        fullRes.width,
                        fullRes.height,
                        transformMatrix,
                        true
                    )
                } else fullRes
                
                // 2) Determine crop to apply from normalized rect (pending or applied)
                val normalized = state.cropState.normalizedCropRect ?: state.appliedNormalizedCropRect
                val croppedFull = if (normalized != null) {
                    val left = (normalized.left * transformedFull.width).toInt().coerceIn(0, transformedFull.width - 1)
                    val top = (normalized.top * transformedFull.height).toInt().coerceIn(0, transformedFull.height - 1)
                    val right = (normalized.right * transformedFull.width).toInt().coerceIn(left + 1, transformedFull.width)
                    val bottom = (normalized.bottom * transformedFull.height).toInt().coerceIn(top + 1, transformedFull.height)
                    val cropW = (right - left).coerceAtLeast(1)
                    val cropH = (bottom - top).coerceAtLeast(1)
                    Bitmap.createBitmap(transformedFull, left, top, cropW, cropH)
                } else transformedFull
                
                // 3) Apply adjustments on high resolution
                val processedFull = withContext(Dispatchers.Default) {
                    imageProcessor.processImage(
                        bitmap = croppedFull,
                        adjustments = state.adjustments,
                        filmGrain = state.filmGrain,
                        lensEffects = state.lensEffects
                    ).getOrNull()
                } ?: croppedFull
                
                // 4) Save with default settings (JPEG, 95% quality, full resolution)
                val bitmapToSave = processedFull
                val compressFormat = Bitmap.CompressFormat.JPEG
                val extension = "jpg"
                val mimeType = "image/jpeg"
                val quality = 95
                val filename = "edited_${System.currentTimeMillis()}.$extension"
                
                // 5) Save with EXIF metadata preservation
                val result = ImageUtils.saveBitmap(
                    context,
                    bitmapToSave,
                    filename,
                    originalDateTaken = originalDateTaken,
                    sourceUri = srcUri,
                    format = compressFormat,
                    quality = quality,
                    mimeType = mimeType
                )
                
                result.fold(
                    onSuccess = { savedUri ->
                        val photo = Photo(
                            id = filename,
                            uri = savedUri,
                            name = filename,
                            timestamp = originalDateTaken ?: System.currentTimeMillis(),
                            width = bitmapToSave.width,
                            height = bitmapToSave.height,
                            size = 0L,
                            hasEdits = true
                        )
                        photoRepository.savePhoto(photo)
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            hasUnsavedChanges = false
                        )
                        onSaved(savedUri)
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = "Failed to save photo: ${error.message}"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to save photo: ${e.message}"
                )
            }
        }
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    // Crop functions
    fun enterCropMode(imageBounds: androidx.compose.ui.geometry.Rect) {
        _uiState.value = _uiState.value.copy(
            cropState = CropState(
                isActive = true,
                aspectRatio = CropAspectRatio.FREE,
                cropRect = imageBounds, // Start with full image
                normalizedCropRect = androidx.compose.ui.geometry.Rect(0f, 0f, 1f, 1f),
                imageBounds = imageBounds
            )
        )
    }
    
    fun exitCropMode() {
        _uiState.value = _uiState.value.copy(
            cropState = CropState(isActive = false)
        )
    }
    
    fun setCropAspectRatio(aspectRatio: CropAspectRatio) {
        val currentCropRect = _uiState.value.cropState.cropRect
        val imageBounds = _uiState.value.cropState.imageBounds
        
        if (currentCropRect != null && imageBounds != null && aspectRatio.ratio != null) {
            // Calculate new crop rect with the selected aspect ratio
            val newRect = calculateRectWithAspectRatio(
                currentRect = currentCropRect,
                targetRatio = aspectRatio.ratio,
                imageBounds = imageBounds
            )
            
            _uiState.value = _uiState.value.copy(
                cropState = _uiState.value.cropState.copy(
                    aspectRatio = aspectRatio,
                    cropRect = newRect,
                    normalizedCropRect = androidx.compose.ui.geometry.Rect(
                        left = (newRect.left - imageBounds.left) / imageBounds.width,
                        top = (newRect.top - imageBounds.top) / imageBounds.height,
                        right = (newRect.right - imageBounds.left) / imageBounds.width,
                        bottom = (newRect.bottom - imageBounds.top) / imageBounds.height
                    )
                )
            )
        } else {
            _uiState.value = _uiState.value.copy(
                cropState = _uiState.value.cropState.copy(aspectRatio = aspectRatio)
            )
        }
    }
    
    /**
     * Calculate a new crop rectangle with the target aspect ratio,
     * centered and maximized within image bounds
     */
    private fun calculateRectWithAspectRatio(
        currentRect: androidx.compose.ui.geometry.Rect,
        targetRatio: Float,
        imageBounds: androidx.compose.ui.geometry.Rect
    ): androidx.compose.ui.geometry.Rect {
        val centerX = currentRect.center.x
        val centerY = currentRect.center.y
        
        // Try to maintain current width and adjust height
        var width = currentRect.width
        var height = width / targetRatio
        
        // If height exceeds bounds, scale down based on height
        if (height > imageBounds.height) {
            height = imageBounds.height
            width = height * targetRatio
        }
        
        // If width exceeds bounds, scale down based on width
        if (width > imageBounds.width) {
            width = imageBounds.width
            height = width / targetRatio
        }
        
        // Calculate new position (centered)
        var left = centerX - width / 2
        var top = centerY - height / 2
        
        // Ensure within bounds
        if (left < imageBounds.left) left = imageBounds.left
        if (top < imageBounds.top) top = imageBounds.top
        if (left + width > imageBounds.right) left = imageBounds.right - width
        if (top + height > imageBounds.bottom) top = imageBounds.bottom - height
        
        return androidx.compose.ui.geometry.Rect(
            left = left,
            top = top,
            right = left + width,
            bottom = top + height
        )
    }
    
    fun updateCropRect(rect: androidx.compose.ui.geometry.Rect) {
        val bounds = _uiState.value.cropState.imageBounds
        val normalized = if (bounds != null && bounds.width > 0f && bounds.height > 0f) {
            androidx.compose.ui.geometry.Rect(
                left = ((rect.left - bounds.left) / bounds.width).coerceIn(0f, 1f),
                top = ((rect.top - bounds.top) / bounds.height).coerceIn(0f, 1f),
                right = ((rect.right - bounds.left) / bounds.width).coerceIn(0f, 1f),
                bottom = ((rect.bottom - bounds.top) / bounds.height).coerceIn(0f, 1f)
            )
        } else null
        _uiState.value = _uiState.value.copy(
            cropState = _uiState.value.cropState.copy(
                cropRect = rect,
                normalizedCropRect = normalized ?: _uiState.value.cropState.normalizedCropRect
            )
        )
    }

    /**
     * Update the displayed image bounds for crop overlay and rebuild pixel cropRect from normalized.
     */
    fun setCropImageBounds(newBounds: androidx.compose.ui.geometry.Rect) {
        val state = _uiState.value.cropState
        val normalized = state.normalizedCropRect
        val pixelRect = if (normalized != null) {
            rectFromNormalized(normalized, newBounds)
        } else {
            state.cropRect ?: newBounds
        }
        _uiState.value = _uiState.value.copy(
            cropState = state.copy(
                imageBounds = newBounds,
                cropRect = pixelRect
            )
        )
    }

    private fun rectFromNormalized(
        norm: androidx.compose.ui.geometry.Rect,
        bounds: androidx.compose.ui.geometry.Rect
    ): androidx.compose.ui.geometry.Rect {
        val left = bounds.left + norm.left.coerceIn(0f, 1f) * bounds.width
        val top = bounds.top + norm.top.coerceIn(0f, 1f) * bounds.height
        val right = bounds.left + norm.right.coerceIn(0f, 1f) * bounds.width
        val bottom = bounds.top + norm.bottom.coerceIn(0f, 1f) * bounds.height
        return androidx.compose.ui.geometry.Rect(left, top, right, bottom)
    }
    
    fun applyCrop() {
        val bitmap = _uiState.value.processedBitmap ?: return
        val screenCropRect = _uiState.value.cropState.cropRect ?: return
        val imageBounds = _uiState.value.cropState.imageBounds ?: return
        
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isProcessing = true)
                
                // Save current state to undo stack (with bitmap for crop)
                saveCurrentStateToUndo(actionType = "crop", saveBitmap = true)
                
                // Perform crop on background thread
                val croppedBitmap = withContext(Dispatchers.Default) {
                    // Convert screen coordinates to bitmap coordinates
                    val scaleX = bitmap.width / imageBounds.width
                    val scaleY = bitmap.height / imageBounds.height
                    
                    val bitmapCropRect = android.graphics.Rect(
                        ((screenCropRect.left - imageBounds.left) * scaleX).toInt().coerceIn(0, bitmap.width),
                        ((screenCropRect.top - imageBounds.top) * scaleY).toInt().coerceIn(0, bitmap.height),
                        ((screenCropRect.right - imageBounds.left) * scaleX).toInt().coerceIn(0, bitmap.width),
                        ((screenCropRect.bottom - imageBounds.top) * scaleY).toInt().coerceIn(0, bitmap.height)
                    )
                    
                    if (bitmapCropRect.width() > 0 && bitmapCropRect.height() > 0) {
                        Bitmap.createBitmap(
                            bitmap,
                            bitmapCropRect.left,
                            bitmapCropRect.top,
                            bitmapCropRect.width(),
                            bitmapCropRect.height()
                        )
                    } else {
                        bitmap
                    }
                }
                
                // Update original bitmap to the cropped version for future operations
                _uiState.value = _uiState.value.copy(
                    originalBitmap = croppedBitmap,
                    processedBitmap = croppedBitmap,
                    isProcessing = false,
                    hasUnsavedChanges = true,
                    cropState = CropState(isActive = false),
                    rotation = 0, // Reset transformations after crop
                    isFlippedHorizontally = false,
                    isFlippedVertically = false,
                    appliedNormalizedCropRect = _uiState.value.cropState.normalizedCropRect
                )
                
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    error = "Crop failed: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Handle errors with user-friendly messages
     */
    private fun handleError(e: Exception, operation: String) {
        val userMessage = ErrorMessageHelper.getErrorMessage(e, operation)
        Log.e(TAG, "Error during $operation", e)
        _uiState.value = _uiState.value.copy(
            error = userMessage,
            isLoading = false,
            isProcessing = false
        )
    }
    
    /**
     * Retry loading photo
     */
    fun retryLoadPhoto() {
        val uri = currentPhotoUri?.toString() ?: return
        clearError()
        loadPhoto(uri)
    }
    
    // Smart Enhancement and Before/After Comparison Methods
    
    /**
     * Applies smart enhancement using intelligent algorithms and histogram analysis.
     */
    fun applySmartEnhancement() {
        val originalBitmap = _uiState.value.originalBitmap ?: return
        val sceneAnalysis = _uiState.value.sceneAnalysis  // Use cached scene analysis!
        
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isProcessing = true)
                
                // Save current state before applying smart enhancement
                saveCurrentStateToUndo(actionType = "smart enhancement")
                
                Log.d(TAG, "Starting smart enhancement with cached scene: ${sceneAnalysis?.sceneType}")
                
                // Apply smart enhancement with 60-second timeout (increased from 30s)
                val result = withTimeout(60_000) {  // 60 second timeout
                    withContext(Dispatchers.Default) {
                        (imageProcessor as? com.imagedit.app.domain.repository.SmartProcessor)?.smartEnhance(
                            bitmap = originalBitmap,
                            mode = com.imagedit.app.domain.model.ProcessingMode.MEDIUM,
                            sceneAnalysis = sceneAnalysis  // Pass cached analysis to avoid re-analyzing
                        )
                    }
                }
                
                result?.fold(
                    onSuccess = { enhancementResult ->
                        Log.d(TAG, "Smart enhancement applied successfully")
                        Log.d(TAG, "Enhancement result: ${enhancementResult.enhancedBitmap.width}x${enhancementResult.enhancedBitmap.height}")
                        Log.d(TAG, "Applied adjustments: ${enhancementResult.appliedAdjustments}")
                        Log.d(TAG, "Processing time: ${enhancementResult.processingTime}")
                        
                        _uiState.value = _uiState.value.copy(
                            processedBitmap = enhancementResult.enhancedBitmap,
                            adjustments = enhancementResult.appliedAdjustments,
                            smartEnhancementApplied = true,
                            smartEnhancementAdjustments = enhancementResult.appliedAdjustments,
                            isProcessing = false,
                            hasUnsavedChanges = true,
                            selectedPreset = null
                        )
                        
                        Log.d(TAG, "Preview updated with smart enhancement")
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            isProcessing = false,
                            error = "Smart enhancement failed: ${error.message}"
                        )
                    }
                ) ?: run {
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        error = "Smart enhancement not available"
                    )
                }
            } catch (e: TimeoutCancellationException) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    error = "Enhancement timed out after 60 seconds. Try using manual adjustments or a smaller image."
                )
                Log.e(TAG, "Smart enhancement timed out after 60 seconds")
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    error = "Smart enhancement failed: ${e.message}"
                )
                Log.e(TAG, "Smart enhancement failed", e)
            }
        }
    }
    
    /**
     * Clears the smart enhancement state when manual adjustments are made.
     */
    private fun clearSmartEnhancementState() {
        if (_uiState.value.smartEnhancementApplied) {
            _uiState.value = _uiState.value.copy(
                smartEnhancementApplied = false,
                smartEnhancementAdjustments = null
            )
        }
    }
    
    /**
     * Applies scene-based enhancement using the suggested enhancement parameters.
     */
    fun applySceneBasedEnhancement(suggestion: com.imagedit.app.domain.model.EnhancementSuggestion) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isProcessing = true)
                
                // Save current state before applying scene-based enhancement
                saveCurrentStateToUndo(actionType = "scene enhancement: ${suggestion.type.name}")
                
                // Apply the suggested adjustments directly
                _uiState.value = _uiState.value.copy(
                    adjustments = suggestion.parameters,
                    hasUnsavedChanges = true,
                    selectedPreset = null
                )
                
                // Process the image with the new adjustments
                processImage()
                
                Log.d(TAG, "Applied scene-based enhancement: ${suggestion.type.name}")
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    error = "Scene enhancement failed: ${e.message}"
                )
                Log.e(TAG, "Scene enhancement failed", e)
            }
        }
    }
    
    /**
     * Manually overrides the detected scene type and regenerates enhancement suggestions.
     */
    fun overrideSceneType(sceneType: SceneType) {
        val currentAnalysis = _uiState.value.sceneAnalysis
        if (currentAnalysis != null) {
            // Create a new scene analysis with the overridden scene type
            val overriddenAnalysis = currentAnalysis.copy(
                sceneType = sceneType,
                confidence = 1.0f, // Manual override has full confidence
                suggestedEnhancements = generateEnhancementSuggestionsForScene(sceneType)
            )
            
            _uiState.value = _uiState.value.copy(
                sceneAnalysis = overriddenAnalysis,
                manualSceneOverride = sceneType
            )
            
        }
    }
    
    /**
     * Toggles the before/after comparison view.
     */
    fun toggleBeforeAfterComparison() {
        val isCurrentlyShowing = _uiState.value.isShowingBeforeAfter
        _uiState.value = _uiState.value.copy(
            isShowingBeforeAfter = !isCurrentlyShowing,
            beforeAfterTransitionProgress = if (!isCurrentlyShowing) 0f else 1f
        )
    }
    
    /**
     * Updates the before/after transition progress for smooth animations.
     * @param progress 0f = before (original), 1f = after (processed)
     */
    fun updateBeforeAfterProgress(progress: Float) {
        _uiState.value = _uiState.value.copy(
            beforeAfterTransitionProgress = progress.coerceIn(0f, 1f)
        )
    }
    
    /**
     * Sets the before/after comparison state.
     */
    fun setBeforeAfterComparison(isShowing: Boolean) {
        _uiState.value = _uiState.value.copy(
            isShowingBeforeAfter = isShowing,
            beforeAfterTransitionProgress = if (isShowing) 0f else 1f
        )
    }
    
    /**
     * Gets the bitmap to display based on before/after state and transition progress.
     */
    fun getCurrentDisplayBitmap(): Bitmap? {
        val state = _uiState.value
        
        return when {
            !state.isShowingBeforeAfter -> state.processedBitmap
            state.beforeAfterTransitionProgress <= 0.5f -> state.originalBitmap
            else -> state.processedBitmap
        }
    }
    
    /**
     * Reflects smart enhancement adjustments in the UI sliders.
     * This method ensures that after smart enhancement, the adjustment sliders
     * show the values that were automatically applied.
     */
    fun reflectSmartEnhancementInUI() {
        val smartAdjustments = _uiState.value.smartEnhancementAdjustments
        if (smartAdjustments != null && _uiState.value.smartEnhancementApplied) {
            // The adjustments are already set in the state from applySmartEnhancement
            // This method can be used to trigger UI updates if needed
            _uiState.value = _uiState.value.copy(
                adjustments = smartAdjustments
            )
        }
    }
    
    /**
     * Checks if smart enhancement has been applied to the current image.
     */
    fun isSmartEnhancementApplied(): Boolean {
        return _uiState.value.smartEnhancementApplied
    }
    
    /**
     * Generates enhancement suggestions based on the scene type.
     */
    private fun generateEnhancementSuggestionsForScene(sceneType: SceneType): List<EnhancementSuggestion> {
        return when (sceneType) {
            SceneType.PORTRAIT -> listOf(
                EnhancementSuggestion(
                    type = EnhancementType.PORTRAIT_ENHANCE,
                    confidence = 0.9f,
                    description = "Smooth skin tones and enhance facial features",
                    parameters = com.imagedit.app.domain.model.AdjustmentParameters(
                        brightness = 0.1f,
                        contrast = 0.15f,
                        saturation = 0.1f,
                        warmth = 0.05f
                    )
                ),
                EnhancementSuggestion(
                    type = EnhancementType.PORTRAIT_ENHANCE,
                    confidence = 0.8f,
                    description = "Gentle enhancement for natural skin appearance",
                    parameters = com.imagedit.app.domain.model.AdjustmentParameters(
                        brightness = 0.05f,
                        contrast = 0.1f,
                        saturation = 0.05f,
                        warmth = 0.1f
                    )
                )
            )
            SceneType.LANDSCAPE -> listOf(
                EnhancementSuggestion(
                    type = EnhancementType.LANDSCAPE_ENHANCE,
                    confidence = 0.9f,
                    description = "Enhance sky and foliage colors",
                    parameters = com.imagedit.app.domain.model.AdjustmentParameters(
                        contrast = 0.2f,
                        saturation = 0.15f,
                        vibrance = 0.2f,
                        clarity = 0.1f
                    )
                ),
                EnhancementSuggestion(
                    type = EnhancementType.LANDSCAPE_ENHANCE,
                    confidence = 0.85f,
                    description = "Subtle enhancement preserving natural colors",
                    parameters = com.imagedit.app.domain.model.AdjustmentParameters(
                        contrast = 0.1f,
                        saturation = 0.1f,
                        vibrance = 0.15f,
                        clarity = 0.05f
                    )
                )
            )
            SceneType.FOOD -> listOf(
                EnhancementSuggestion(
                    type = EnhancementType.COLOR_CORRECTION,
                    confidence = 0.9f,
                    description = "Enhance colors and appetite appeal",
                    parameters = com.imagedit.app.domain.model.AdjustmentParameters(
                        brightness = 0.1f,
                        contrast = 0.15f,
                        saturation = 0.2f,
                        warmth = 0.1f,
                        vibrance = 0.15f
                    )
                )
            )
            SceneType.NIGHT -> listOf(
                EnhancementSuggestion(
                    type = EnhancementType.LOW_LIGHT_ENHANCE,
                    confidence = 0.85f,
                    description = "Brighten shadows while preserving mood",
                    parameters = com.imagedit.app.domain.model.AdjustmentParameters(
                        brightness = 0.15f,
                        shadows = 0.3f,
                        contrast = 0.1f,
                        clarity = 0.1f
                    )
                )
            )
            SceneType.INDOOR -> listOf(
                EnhancementSuggestion(
                    type = EnhancementType.COLOR_CORRECTION,
                    confidence = 0.8f,
                    description = "Correct artificial lighting and improve clarity",
                    parameters = com.imagedit.app.domain.model.AdjustmentParameters(
                        brightness = 0.1f,
                        contrast = 0.1f,
                        warmth = -0.05f,
                        clarity = 0.1f
                    )
                )
            )
            SceneType.MACRO -> listOf(
                EnhancementSuggestion(
                    type = EnhancementType.SMART_ENHANCE,
                    confidence = 0.85f,
                    description = "Enhance details and color saturation",
                    parameters = com.imagedit.app.domain.model.AdjustmentParameters(
                        contrast = 0.15f,
                        saturation = 0.15f,
                        clarity = 0.2f
                    )
                )
            )
            else -> listOf(
                EnhancementSuggestion(
                    type = EnhancementType.SMART_ENHANCE,
                    confidence = 0.7f,
                    description = "Balanced improvement for various scenes",
                    parameters = com.imagedit.app.domain.model.AdjustmentParameters(
                        contrast = 0.1f,
                        saturation = 0.1f,
                        vibrance = 0.1f
                    )
                )
            )
        }
    }

    /**
     * Applies scene priority logic for photos with multiple scene characteristics.
     */
    private fun applyScenePriorityLogic(sceneAnalysis: SceneAnalysis): SceneAnalysis {
        // If confidence is high, return as-is
        if (sceneAnalysis.confidence >= 0.8f) {
            return sceneAnalysis
        }
        
        // Prioritize portrait if sufficient skin tones detected
        val skinTonePercentage = sceneAnalysis.colorProfile.skinTonePercentage
        if (skinTonePercentage > 0.1f && sceneAnalysis.sceneType != SceneType.PORTRAIT) {
            val adjustedConfidence = (sceneAnalysis.confidence + skinTonePercentage).coerceAtMost(0.9f)
            return sceneAnalysis.copy(
                sceneType = SceneType.PORTRAIT,
                confidence = adjustedConfidence,
                suggestedEnhancements = generateEnhancementSuggestionsForScene(SceneType.PORTRAIT)
            )
        }
        
        // Elevate to night if lighting demands it
        if (sceneAnalysis.lightingConditions.brightness < 0.3f &&
            sceneAnalysis.lightingConditions.lightingType == LightingType.LOW_LIGHT &&
            sceneAnalysis.sceneType != SceneType.NIGHT) {
            val adjustedConfidence = (sceneAnalysis.confidence + 0.2f).coerceAtMost(0.8f)
            return sceneAnalysis.copy(
                sceneType = SceneType.NIGHT,
                confidence = adjustedConfidence,
                suggestedEnhancements = generateEnhancementSuggestionsForScene(SceneType.NIGHT)
            )
        }
        
        // Otherwise, provide mixed-scene suggestions
        return sceneAnalysis.copy(
            suggestedEnhancements = generateMixedSceneEnhancements(sceneAnalysis)
        )
    }

    /**
     * Generates enhancement suggestions for mixed scene characteristics.
     */
    private fun generateMixedSceneEnhancements(sceneAnalysis: SceneAnalysis): List<EnhancementSuggestion> {
        val base = generateEnhancementSuggestionsForScene(sceneAnalysis.sceneType)
        val mixed = EnhancementSuggestion(
            type = EnhancementType.SMART_ENHANCE,
            confidence = 0.75f,
            description = "Conservative enhancement suitable for mixed scenes",
            parameters = com.imagedit.app.domain.model.AdjustmentParameters(
                brightness = 0.05f,
                contrast = 0.08f,
                saturation = 0.05f,
                clarity = 0.03f
            )
        )
        return listOf(mixed) + base.take(1)
    }

    /**
     * Toggles portrait enhancement on/off.
     */
    fun togglePortraitEnhancement() {
        if (_uiState.value.portraitEnhancementApplied) {
            // Turn off portrait enhancement - restore to previous state
            undo()
        } else {
            // Apply portrait enhancement
            applyPortraitEnhancementWithCancellation()
        }
    }
    
    /**
     * Updates the portrait enhancement intensity and reapplies.
     */
    fun updatePortraitEnhancementIntensity(intensity: Float) {
        _uiState.value = _uiState.value.copy(portraitEnhancementIntensity = intensity)
        if (_uiState.value.isPortraitEnhancementActive) {
            applyPortraitEnhancementWithCancellation()
        }
    }
    
    /**
     * Activates portrait enhancement mode for UI controls.
     */
    fun activatePortraitEnhancementMode() {
        _uiState.value = _uiState.value.copy(isPortraitEnhancementActive = true)
    }
    
    /**
     * Deactivates portrait enhancement mode.
     */
    fun deactivatePortraitEnhancementMode() {
        _uiState.value = _uiState.value.copy(isPortraitEnhancementActive = false)
    }
    
    /**
     * Checks if portrait enhancement is currently applied.
     */
    fun isPortraitEnhancementApplied(): Boolean {
        return _uiState.value.portraitEnhancementApplied
    }
    
    override fun onCleared() {
        super.onCleared()
        processingJob?.cancel()
    }
    
    
    /**
     * Updates the smart enhancement methods to support cancellation.
     */
    private fun applySmartEnhancementWithCancellation() {
        val bitmap = _uiState.value.processedBitmap ?: return
        
        // Cancel any existing job
        smartEnhancementJob?.cancel()
        
        smartEnhancementJob = viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isProcessing = true)
                
                // Use current processing mode from settings
                val currentSettings = settingsRepository.getCurrentSettings()
                val result = withContext(Dispatchers.Default) {
                    smartProcessor.smartEnhance(bitmap, currentSettings.processingMode)
                }
                
                result.fold(
                    onSuccess = { enhancementResult ->
                        // Save current state for undo
                        saveCurrentStateToUndo(actionType = "smart enhance")
                        
                        // Update state with enhanced image and applied adjustments
                        _uiState.value = _uiState.value.copy(
                            processedBitmap = enhancementResult.enhancedBitmap,
                            adjustments = enhancementResult.appliedAdjustments,
                            smartEnhancementApplied = true,
                            smartEnhancementAdjustments = enhancementResult.appliedAdjustments,
                            isProcessing = false,
                            hasUnsavedChanges = true,
                            selectedPreset = null
                        )
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            isProcessing = false,
                            error = "Smart enhancement failed: ${error.message}"
                        )
                    }
                )
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        error = "Smart enhancement failed: ${e.message}"
                    )
                }
            }
        }
    }
    
    /**
     * Updates the portrait enhancement method to support cancellation.
     */
    private fun applyPortraitEnhancementWithCancellation() {
        val bitmap = _uiState.value.processedBitmap ?: return
        val intensity = _uiState.value.portraitEnhancementIntensity
        
        // Cancel any existing job
        portraitEnhancementJob?.cancel()
        
        portraitEnhancementJob = viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    isProcessing = true,
                    isPortraitEnhancementActive = true
                )
                
                // Use current processing mode from settings
                val currentSettings = settingsRepository.getCurrentSettings()
                val result = withContext(Dispatchers.Default) {
                    smartProcessor.enhancePortrait(bitmap, intensity, currentSettings.processingMode)
                }
                
                result.fold(
                    onSuccess = { enhancedBitmap ->
                        Log.d(TAG, "Portrait enhancement applied successfully: intensity=$intensity")
                        Log.d(TAG, "Enhanced bitmap: ${enhancedBitmap.width}x${enhancedBitmap.height}")
                        
                        // Save current state for undo
                        saveCurrentStateToUndo(actionType = "portrait enhancement")
                        
                        _uiState.value = _uiState.value.copy(
                            processedBitmap = enhancedBitmap,
                            portraitEnhancementApplied = true,
                            isProcessing = false,
                            hasUnsavedChanges = true,
                            selectedPreset = null
                        )
                        
                        Log.d(TAG, "Preview updated with portrait enhancement")
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            isProcessing = false,
                            isPortraitEnhancementActive = false,
                            error = "Portrait enhancement failed: ${error.message}"
                        )
                    }
                )
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        isPortraitEnhancementActive = false,
                        error = "Portrait enhancement failed: ${e.message}"
                    )
                }
            }
        }
    }
    
    // Performance Settings Integration Methods
    
    /**
     * Gets the current processing mode from settings.
     */
    fun getCurrentProcessingMode(): ProcessingMode {
        return settingsRepository.getCurrentSettings().processingMode
    }
    
    /**
     * Applies smart enhancement with a specific processing mode for real-time switching.
     * This allows users to test different performance modes without changing global settings.
     */
    fun applySmartEnhancementWithMode(mode: ProcessingMode) {
        val bitmap = _uiState.value.processedBitmap ?: return
        
        // Cancel any existing job
        smartEnhancementJob?.cancel()
        
        smartEnhancementJob = viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isProcessing = true)
                
                val result = withContext(Dispatchers.Default) {
                    smartProcessor.smartEnhance(bitmap, mode)
                }
                
                result.fold(
                    onSuccess = { enhancementResult ->
                        // Save current state for undo
                        saveCurrentStateToUndo(actionType = "smart enhance (${mode.name.lowercase()})")
                        
                        // Update state with enhanced image and applied adjustments
                        _uiState.value = _uiState.value.copy(
                            processedBitmap = enhancementResult.enhancedBitmap,
                            adjustments = enhancementResult.appliedAdjustments,
                            smartEnhancementApplied = true,
                            smartEnhancementAdjustments = enhancementResult.appliedAdjustments,
                            isProcessing = false,
                            hasUnsavedChanges = true,
                            error = null
                        )
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            isProcessing = false,
                            error = "Smart enhancement failed: ${error.message}"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    error = "Smart enhancement failed: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Applies portrait enhancement with a specific processing mode for real-time switching.
     */
    fun applyPortraitEnhancementWithMode(intensity: Float, mode: ProcessingMode) {
        val bitmap = _uiState.value.processedBitmap ?: return
        
        // Cancel any existing job
        portraitEnhancementJob?.cancel()
        
        portraitEnhancementJob = viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    isProcessing = true,
                    isPortraitEnhancementActive = true
                )
                
                val result = withContext(Dispatchers.Default) {
                    smartProcessor.enhancePortrait(bitmap, intensity, mode)
                }
                
                result.fold(
                    onSuccess = { enhancedBitmap ->
                        // Save current state for undo
                        saveCurrentStateToUndo(actionType = "portrait enhancement (${mode.name.lowercase()})")
                        
                        _uiState.value = _uiState.value.copy(
                            processedBitmap = enhancedBitmap,
                            portraitEnhancementIntensity = intensity,
                            isProcessing = false,
                            isPortraitEnhancementActive = false,
                            hasUnsavedChanges = true,
                            error = null
                        )
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            isProcessing = false,
                            isPortraitEnhancementActive = false,
                            error = "Portrait enhancement failed: ${error.message}"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    isPortraitEnhancementActive = false,
                    error = "Portrait enhancement failed: ${e.message}"
                )
            }
        }
    }
    
    // Landscape Enhancement Methods
    
    /**
     * Toggles landscape enhancement on/off.
     */
    fun toggleLandscapeEnhancement() {
        if (_uiState.value.landscapeEnhancementApplied) {
            // Turn off landscape enhancement - restore to previous state
            val previousBitmap = _uiState.value.processedBitmap ?: _uiState.value.originalBitmap
            _uiState.value = _uiState.value.copy(
                processedBitmap = previousBitmap,
                landscapeEnhancementApplied = false,
                isLandscapeEnhancementActive = false,
                hasUnsavedChanges = true
            )
        } else {
            // Apply landscape enhancement
            applyLandscapeEnhancement()
        }
    }
    
    /**
     * Applies landscape enhancement with current parameters.
     */
    fun applyLandscapeEnhancement() {
        val bitmap = _uiState.value.processedBitmap ?: _uiState.value.originalBitmap ?: return
        
        _uiState.value = _uiState.value.copy(
            isProcessing = true,
            isLandscapeEnhancementActive = true,
            error = null
        )
        
        // Cancel any existing processing job
        currentProcessingJob?.cancel()
        
        currentProcessingJob = viewModelScope.launch {
            try {
                val currentSettings = settingsRepository.getCurrentSettings()
                val mode = currentSettings.processingMode
                
                // Convert LandscapeEnhancementParameters to LandscapeParameters for the processor
                val landscapeParams = LandscapeParameters(
                    skyEnhancement = _uiState.value.landscapeEnhancementParameters.skySettings.saturationBoost,
                    foliageEnhancement = _uiState.value.landscapeEnhancementParameters.foliageSettings.greenSaturation,
                    clarityBoost = _uiState.value.landscapeEnhancementParameters.clarityIntensity,
                    naturalColorGrading = _uiState.value.landscapeEnhancementParameters.preserveColorHarmony
                )
                
                smartProcessor.enhanceLandscape(bitmap, landscapeParams, mode).fold(
                    onSuccess = { enhancedBitmap ->
                        _uiState.value = _uiState.value.copy(
                            processedBitmap = enhancedBitmap,
                            isProcessing = false,
                            landscapeEnhancementApplied = true,
                            hasUnsavedChanges = true
                        )
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            isProcessing = false,
                            isLandscapeEnhancementActive = false,
                            error = "Landscape enhancement failed: ${error.message}"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    isLandscapeEnhancementActive = false,
                    error = "Landscape enhancement failed: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Updates landscape enhancement parameters and reapplies if active.
     */
    fun updateLandscapeEnhancementParameters(parameters: LandscapeEnhancementParameters) {
        _uiState.value = _uiState.value.copy(
            landscapeEnhancementParameters = parameters
        )
        
        // Reapply enhancement if currently active
        if (_uiState.value.landscapeEnhancementApplied) {
            applyLandscapeEnhancement()
        }
    }
    
    /**
     * Analyzes the current image for landscape elements.
     */
    fun analyzeLandscapeElements() {
        val bitmap = _uiState.value.processedBitmap ?: _uiState.value.originalBitmap ?: return
        
        viewModelScope.launch {
            try {
                // This would require adding LandscapeDetector to the ViewModel dependencies
                // For now, we'll set a placeholder analysis
                _uiState.value = _uiState.value.copy(
                    landscapeAnalysis = null // Will be populated when LandscapeDetector is injected
                )
            } catch (e: Exception) {
                Log.e("PhotoEditorViewModel", "Failed to analyze landscape elements", e)
            }
        }
    }
    
    /**
     * Checks if landscape enhancement is currently applied.
     */
    fun isLandscapeEnhancementApplied(): Boolean {
        return _uiState.value.landscapeEnhancementApplied
    }
    
    /**
     * Activates landscape enhancement mode for UI controls.
     */
    fun activateLandscapeEnhancementMode() {
        _uiState.value = _uiState.value.copy(isLandscapeEnhancementActive = true)
    }
    
    /**
     * Deactivates landscape enhancement mode.
     */
    fun deactivateLandscapeEnhancementMode() {
        _uiState.value = _uiState.value.copy(isLandscapeEnhancementActive = false)
    }
    
    // Healing Tool Methods
    
    /**
     * Activates healing tool mode.
     */
    fun activateHealingTool() {
        _uiState.value = _uiState.value.copy(
            isHealingToolActive = true,
            healingStrokes = emptyList(),
            healingValidation = null,
            healingSourceCandidates = emptyList(),
            selectedHealingSource = null
        )
    }
    
    /**
     * Deactivates healing tool mode.
     */
    fun deactivateHealingTool() {
        _uiState.value = _uiState.value.copy(
            isHealingToolActive = false,
            healingStrokes = emptyList(),
            healingValidation = null,
            healingSourceCandidates = emptyList(),
            selectedHealingSource = null,
            isHealingProcessing = false,
            healingProgress = -1f
        )
    }
    
    /**
     * Updates healing brush settings.
     */
    fun updateHealingBrushSettings(settings: HealingBrush) {
        _uiState.value = _uiState.value.copy(healingBrushSettings = settings)
    }
    
    /**
     * Adds a brush stroke for healing.
     */
    fun addHealingStroke(stroke: BrushStroke) {
        val currentStrokes = _uiState.value.healingStrokes.toMutableList()
        currentStrokes.add(stroke)
        _uiState.value = _uiState.value.copy(healingStrokes = currentStrokes)
        
        // Validate the healing area
        validateHealingArea()
    }
    
    /**
     * Removes the last healing stroke (undo).
     */
    fun undoLastHealingStroke() {
        val currentStrokes = _uiState.value.healingStrokes.toMutableList()
        if (currentStrokes.isNotEmpty()) {
            currentStrokes.removeLastOrNull()
            _uiState.value = _uiState.value.copy(healingStrokes = currentStrokes)
            
            if (currentStrokes.isNotEmpty()) {
                validateHealingArea()
            } else {
                _uiState.value = _uiState.value.copy(
                    healingValidation = null,
                    healingSourceCandidates = emptyList(),
                    selectedHealingSource = null
                )
            }
        }
    }
    
    /**
     * Clears all healing strokes.
     */
    fun clearHealingStrokes() {
        _uiState.value = _uiState.value.copy(
            healingStrokes = emptyList(),
            healingValidation = null,
            healingSourceCandidates = emptyList(),
            selectedHealingSource = null
        )
    }
    
    /**
     * Validates the current healing area and finds source candidates.
     */
    private fun validateHealingArea() {
        val bitmap = _uiState.value.processedBitmap ?: _uiState.value.originalBitmap ?: return
        val strokes = _uiState.value.healingStrokes
        
        if (strokes.isEmpty()) return
        
        viewModelScope.launch {
            try {
                // Calculate bounding rect from strokes
                val boundingRect = calculateBoundingRect(strokes)
                
                // Validate the area
                val validation = smartProcessor.validateHealingArea(bitmap, boundingRect)
                _uiState.value = _uiState.value.copy(healingValidation = validation)
                
                // Find source candidates if validation passes
                if (validation.isValid) {
                    val candidates = smartProcessor.findSourceCandidates(bitmap, boundingRect, 5)
                    _uiState.value = _uiState.value.copy(
                        healingSourceCandidates = candidates,
                        selectedHealingSource = candidates.firstOrNull()
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error validating healing area", e)
                _uiState.value = _uiState.value.copy(
                    healingValidation = HealingValidation(
                        false,
                        "Error validating healing area: ${e.message}"
                    )
                )
            }
        }
    }
    
    /**
     * Applies healing to the current strokes.
     */
    fun applyHealing() {
        val bitmap = _uiState.value.processedBitmap ?: _uiState.value.originalBitmap ?: return
        val strokes = _uiState.value.healingStrokes
        val brushSettings = _uiState.value.healingBrushSettings
        
        if (strokes.isEmpty()) return
        
        // Cancel any existing healing operation
        healingJob?.cancel()
        
        healingJob = viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    isHealingProcessing = true,
                    healingProgress = 0f
                )
                
                // Get current processing mode from settings
                val settings = settingsRepository.getCurrentSettings()
                val mode = settings.processingMode
                
                // Apply healing with progress callback
                val result = smartProcessor.healWithBrush(
                    bitmap, 
                    strokes, 
                    brushSettings, 
                    mode
                )
                
                result.fold(
                    onSuccess = { healingResult ->
                        // Save current state for undo
                        saveCurrentStateToUndo("healing")
                        
                        // Update with healed bitmap
                        _uiState.value = _uiState.value.copy(
                            processedBitmap = healingResult.healedBitmap,
                            hasUnsavedChanges = true,
                            isHealingProcessing = false,
                            healingProgress = -1f,
                            healingStrokes = emptyList(),
                            healingValidation = null,
                            healingSourceCandidates = emptyList(),
                            selectedHealingSource = null
                        )
                    },
                    onFailure = { error ->
                        val exception = error as? Exception ?: Exception(error.message ?: "Healing failed", error)
                        Log.e(TAG, "Healing failed", exception)
                        _uiState.value = _uiState.value.copy(
                            isHealingProcessing = false,
                            healingProgress = -1f,
                            error = ErrorMessageHelper.getErrorMessage(exception)
                        )
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error applying healing", e)
                _uiState.value = _uiState.value.copy(
                    isHealingProcessing = false,
                    healingProgress = -1f,
                    error = "Failed to apply healing: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Selects a source candidate for healing.
     */
    fun selectHealingSource(candidate: SourceCandidate) {
        _uiState.value = _uiState.value.copy(selectedHealingSource = candidate)
    }
    
    /**
     * Calculates bounding rectangle from brush strokes.
     */
    private fun calculateBoundingRect(strokes: List<BrushStroke>): android.graphics.Rect {
        if (strokes.isEmpty()) return android.graphics.Rect()
        
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        
        strokes.forEach { stroke ->
            stroke.points.forEach { point ->
                minX = kotlin.math.min(minX, point.x)
                minY = kotlin.math.min(minY, point.y)
                maxX = kotlin.math.max(maxX, point.x)
                maxY = kotlin.math.max(maxY, point.y)
            }
        }
        
        return android.graphics.Rect(
            minX.toInt(),
            minY.toInt(),
            maxX.toInt(),
            maxY.toInt()
        )
    }
    
    /**
     * Checks if healing tool is currently active.
     */
    fun isHealingToolActive(): Boolean = _uiState.value.isHealingToolActive
    
    /**
     * Cancels the current healing operation.
     */
    fun cancelHealing() {
        healingJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isHealingProcessing = false,
            healingProgress = -1f
        )
    }
    
    /**
     * Checks if there are healing strokes.
     */
    fun hasHealingStrokes(): Boolean = _uiState.value.healingStrokes.isNotEmpty()
    
    /**
     * Starts a new healing stroke.
     */
    fun startHealingStroke(x: Float, y: Float, pressure: Float) {
        // Create a new stroke starting point
        val points = mutableListOf(android.graphics.PointF(x, y))
        currentHealingStroke = points
    }
    
    /**
     * Adds a point to the current healing stroke.
     */
    fun addHealingStrokePoint(x: Float, y: Float, pressure: Float) {
        currentHealingStroke?.add(android.graphics.PointF(x, y))
    }
    
    /**
     * Finishes the current healing stroke.
     */
    fun finishHealingStroke() {
        currentHealingStroke?.let { points ->
            if (points.size >= 2) {
                val stroke = BrushStroke(
                    points = points,
                    brushSize = _uiState.value.healingBrushSettings.size,
                    pressure = 1.0f,
                    timestamp = System.currentTimeMillis()
                )
                addHealingStroke(stroke)
            }
        }
        currentHealingStroke = null
    }
    
    private var currentHealingStroke: MutableList<android.graphics.PointF>? = null
}