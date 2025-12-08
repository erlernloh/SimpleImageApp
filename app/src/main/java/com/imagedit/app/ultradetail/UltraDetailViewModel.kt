/**
 * UltraDetailViewModel.kt - ViewModel for Ultra Detail+ feature
 * 
 * Manages UI state and coordinates the Ultra Detail+ pipeline.
 */

package com.imagedit.app.ultradetail

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imagedit.app.util.image.ImageUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

private const val TAG = "UltraDetailViewModel"

/**
 * UI processing stage for progress display
 */
enum class UiProcessingStage {
    IDLE,
    CAPTURING,
    ALIGNING,
    SUPER_RESOLUTION,
    REFINING,
    FINALIZING
}

/**
 * UI state for Ultra Detail+ screen
 */
data class UltraDetailUiState(
    val isInitialized: Boolean = false,
    val selectedPreset: UltraDetailPreset = UltraDetailPreset.BALANCED,
    val isCapturing: Boolean = false,
    val isProcessing: Boolean = false,
    val isSaving: Boolean = false,
    val captureProgress: Float = 0f,
    val processingProgress: Float = 0f,
    val statusMessage: String = "",
    val processingStage: UiProcessingStage = UiProcessingStage.IDLE,
    val processingStartTimeMs: Long = 0,
    val estimatedTotalTimeMs: Long = 60000,  // Default 60 seconds for ULTRA
    val currentTile: Int = 0,
    val totalTiles: Int = 0,
    val resultBitmap: Bitmap? = null,
    val previewBitmap: Bitmap? = null,  // Quick preview shown immediately after capture
    val savedUri: Uri? = null,
    val processingTimeMs: Long = 0,
    val framesUsed: Int = 0,
    val detailTilesCount: Int = 0,
    val srTilesProcessed: Int = 0,
    val mfsrApplied: Boolean = false,
    val mfsrScaleFactor: Int = 1,
    val mfsrCoveragePercent: Float = 0f,
    val refinementStrength: Float = 0.7f,  // Tunable: 0=original, 1=fully refined
    val error: String? = null
)

/**
 * ViewModel for Ultra Detail+ feature
 */
@HiltViewModel
class UltraDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(UltraDetailUiState())
    val uiState: StateFlow<UltraDetailUiState> = _uiState.asStateFlow()
    
    private var pipeline: UltraDetailPipeline? = null
    private var burstController: BurstCaptureController? = null
    
    /**
     * Initialize the Ultra Detail+ pipeline
     */
    fun initialize() {
        viewModelScope.launch {
            try {
                pipeline = UltraDetailPipeline(context)
                
                // Observe pipeline state
                launch {
                    pipeline?.state?.collect { state ->
                        handlePipelineState(state)
                    }
                }
                
                _uiState.value = _uiState.value.copy(
                    isInitialized = true,
                    statusMessage = "Ready"
                )
                
                Log.d(TAG, "ViewModel initialized")
                
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to initialize: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Set the processing preset
     */
    fun setPreset(preset: UltraDetailPreset) {
        _uiState.value = _uiState.value.copy(selectedPreset = preset)
        Log.d(TAG, "Preset changed to: $preset")
    }
    
    /**
     * Set the refinement blend strength (ULTRA preset only)
     * 
     * @param strength 0.0 = original MFSR output, 1.0 = fully refined
     */
    fun setRefinementStrength(strength: Float) {
        _uiState.value = _uiState.value.copy(
            refinementStrength = strength.coerceIn(0f, 1f)
        )
        Log.d(TAG, "Refinement strength set to: $strength")
    }
    
    /**
     * Start burst capture and processing
     */
    fun startCapture(burstController: BurstCaptureController) {
        if (_uiState.value.isCapturing || _uiState.value.isProcessing) {
            return
        }
        
        this.burstController = burstController
        
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    isCapturing = true,
                    captureProgress = 0f,
                    statusMessage = "Capturing burst...",
                    error = null,
                    resultBitmap = null
                )
                
                // Observe burst capture state
                launch {
                    burstController.captureState.collect { state ->
                        handleBurstState(state)
                    }
                }
                
                // Start capture
                val frames = burstController.startCapture(viewModelScope)
                
                if (frames.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isCapturing = false,
                        error = "Burst capture failed"
                    )
                    return@launch
                }
                
                val preset = _uiState.value.selectedPreset
                
                // For ULTRA preset, generate quick preview immediately
                var previewBitmap: Bitmap? = null
                if (preset == UltraDetailPreset.ULTRA) {
                    previewBitmap = pipeline?.generateQuickPreview(frames)
                    // Start foreground service to prevent app from being killed
                    ProcessingService.start(context)
                }
                
                val estimatedTime = when (preset) {
                    UltraDetailPreset.FAST -> 2000L
                    UltraDetailPreset.BALANCED -> 3000L
                    UltraDetailPreset.MAX -> 5000L
                    UltraDetailPreset.ULTRA -> 90000L  // ~90 seconds for ULTRA
                }
                
                _uiState.value = _uiState.value.copy(
                    isCapturing = false,
                    isProcessing = true,
                    processingProgress = 0f,
                    processingStage = UiProcessingStage.ALIGNING,
                    processingStartTimeMs = System.currentTimeMillis(),
                    estimatedTotalTimeMs = estimatedTime,
                    currentTile = 0,
                    totalTiles = 0,
                    statusMessage = "Aligning frames...",
                    previewBitmap = previewBitmap  // Show preview while processing
                )
                
                // Initialize pipeline with selected preset
                pipeline?.initialize(preset)
                
                // Set refinement strength for ULTRA preset
                if (preset == UltraDetailPreset.ULTRA) {
                    pipeline?.setRefinementStrength(_uiState.value.refinementStrength)
                }
                
                // Process frames
                val result = try {
                    pipeline?.process(frames, preset, viewModelScope)
                } finally {
                    // Stop foreground service when done
                    if (preset == UltraDetailPreset.ULTRA) {
                        ProcessingService.stop(context)
                    }
                }
                
                if (result != null) {
                    val mfsrStatus = if (result.mfsrApplied) " (MFSR ${result.mfsrScaleFactor}x)" else ""
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        processingStage = UiProcessingStage.IDLE,
                        resultBitmap = result.bitmap,
                        processingTimeMs = result.processingTimeMs,
                        framesUsed = result.framesUsed,
                        detailTilesCount = result.detailTilesCount,
                        srTilesProcessed = result.srTilesProcessed,
                        mfsrApplied = result.mfsrApplied,
                        mfsrScaleFactor = result.mfsrScaleFactor,
                        mfsrCoveragePercent = result.mfsrCoveragePercent,
                        statusMessage = "Complete! ${result.processingTimeMs}ms$mfsrStatus"
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        processingStage = UiProcessingStage.IDLE,
                        error = "Processing failed"
                    )
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Capture/processing failed", e)
                _uiState.value = _uiState.value.copy(
                    isCapturing = false,
                    isProcessing = false,
                    error = e.message ?: "Unknown error"
                )
            }
        }
    }
    
    /**
     * Cancel ongoing capture or processing
     */
    fun cancel() {
        burstController?.cancelCapture()
        pipeline?.cancel()
        
        _uiState.value = _uiState.value.copy(
            isCapturing = false,
            isProcessing = false,
            statusMessage = "Cancelled"
        )
    }
    
    /**
     * Clear the result and reset state
     */
    fun clearResult() {
        _uiState.value.resultBitmap?.recycle()
        _uiState.value = _uiState.value.copy(
            resultBitmap = null,
            savedUri = null,
            processingTimeMs = 0,
            framesUsed = 0,
            detailTilesCount = 0,
            srTilesProcessed = 0,
            statusMessage = "Ready"
        )
    }
    
    /**
     * Save the result bitmap to device gallery
     * 
     * @param onSaved Callback with saved URI on success
     */
    fun saveResult(onSaved: (Uri) -> Unit = {}) {
        val bitmap = _uiState.value.resultBitmap ?: return
        
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    isSaving = true,
                    statusMessage = "Saving image..."
                )
                
                val result = withContext(Dispatchers.IO) {
                    // Generate filename with timestamp
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val presetSuffix = when (_uiState.value.selectedPreset) {
                        UltraDetailPreset.FAST -> "Fast"
                        UltraDetailPreset.BALANCED -> "Balanced"
                        UltraDetailPreset.MAX -> "Max"
                        UltraDetailPreset.ULTRA -> "Ultra"
                    }
                    val mfsrSuffix = if (_uiState.value.mfsrApplied) "_MFSR${_uiState.value.mfsrScaleFactor}x" else ""
                    val filename = "UltraDetail_${timestamp}_${presetSuffix}${mfsrSuffix}.jpg"
                    val mfsrDesc = if (_uiState.value.mfsrApplied) {
                        " with ${_uiState.value.mfsrScaleFactor}x MFSR upscaling (${_uiState.value.mfsrCoveragePercent.toInt()}% coverage)"
                    } else ""
                    val description = "Ultra Detail+ ($presetSuffix) - Enhanced with burst processing$mfsrDesc"
                    
                    ImageUtils.saveBitmap(
                        context = context,
                        bitmap = bitmap,
                        filename = filename,
                        originalDateTaken = System.currentTimeMillis(),
                        quality = 95,
                        description = description
                    )
                }
                
                result.fold(
                    onSuccess = { uri ->
                        _uiState.value = _uiState.value.copy(
                            isSaving = false,
                            savedUri = uri,
                            statusMessage = "Saved successfully!"
                        )
                        Log.i(TAG, "Image saved to: $uri")
                        onSaved(uri)
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            isSaving = false,
                            error = "Failed to save: ${error.message}"
                        )
                        Log.e(TAG, "Failed to save image", error)
                    }
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Save failed", e)
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = "Save failed: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Clear any error message
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    /**
     * Handle burst capture state changes
     */
    private fun handleBurstState(state: BurstCaptureState) {
        when (state) {
            is BurstCaptureState.Capturing -> {
                val progress = state.framesCollected.toFloat() / state.totalFrames
                _uiState.value = _uiState.value.copy(
                    captureProgress = progress,
                    statusMessage = "Capturing ${state.framesCollected}/${state.totalFrames}..."
                )
            }
            is BurstCaptureState.Complete -> {
                _uiState.value = _uiState.value.copy(
                    isCapturing = false,
                    statusMessage = "Burst captured: ${state.frames.size} frames"
                )
            }
            is BurstCaptureState.Error -> {
                _uiState.value = _uiState.value.copy(
                    isCapturing = false,
                    error = state.message
                )
            }
            BurstCaptureState.Idle -> {
                // No action needed
            }
        }
    }
    
    /**
     * Handle pipeline state changes
     */
    private fun handlePipelineState(state: PipelineState) {
        when (state) {
            is PipelineState.ProcessingBurst -> {
                _uiState.value = _uiState.value.copy(
                    processingProgress = state.progress,
                    processingStage = UiProcessingStage.ALIGNING,
                    statusMessage = state.message
                )
            }
            is PipelineState.ApplyingSuperResolution -> {
                val progress = if (state.totalTiles > 0) {
                    state.tilesProcessed.toFloat() / state.totalTiles
                } else 0f
                
                _uiState.value = _uiState.value.copy(
                    processingProgress = progress,
                    processingStage = UiProcessingStage.SUPER_RESOLUTION,
                    currentTile = state.tilesProcessed,
                    totalTiles = state.totalTiles,
                    statusMessage = "Super-resolution: ${state.tilesProcessed}/${state.totalTiles} tiles"
                )
            }
            is PipelineState.Complete -> {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    processingStage = UiProcessingStage.IDLE,
                    resultBitmap = state.result,
                    processingTimeMs = state.processingTimeMs,
                    statusMessage = "Complete! ${state.processingTimeMs}ms"
                )
            }
            is PipelineState.Error -> {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    processingStage = UiProcessingStage.IDLE,
                    error = state.message,
                    resultBitmap = state.fallbackResult
                )
            }
            else -> {
                // No action needed
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        pipeline?.close()
        burstController?.release()
    }
}
