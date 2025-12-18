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
import kotlinx.coroutines.withTimeoutOrNull
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
    val alignmentQuality: Float = 0f,  // RGB quality mask alignment percentage (0-100)
    val refinementStrength: Float = 0.7f,  // Tunable: 0=original, 1=fully refined (ULTRA mode)
    val denoiseStrength: Float = 0.5f,     // Tunable: 0=minimal, 1=aggressive (FAST/BALANCED/MAX)
    val error: String? = null,
    // Model download state
    val showModelDownloadDialog: Boolean = false,
    val modelDownloadState: DownloadState = DownloadState.Idle,
    val isModelAvailable: Boolean = false,
    // Fix #2: RAW capture support
    val isRawCaptureSupported: Boolean = false,
    val rawCaptureEnabled: Boolean = false  // User preference to use RAW when available
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
    private val modelDownloader = ModelDownloader(context)
    
    // Fix #2: RAW capture helper
    private val rawCaptureHelper = RawCaptureHelper(context)
    
    /**
     * Initialize the Ultra Detail+ pipeline
     */
    fun initialize() {
        viewModelScope.launch {
            try {
                pipeline = UltraDetailPipeline(context)
                
                // Check if SR model is available
                val modelAvailable = modelDownloader.isModelAvailable(AvailableModels.ESRGAN_FP16)
                
                // Fix #2: Check RAW capture support
                val rawCapability = rawCaptureHelper.checkRawSupport()
                if (rawCapability.isRawSupported) {
                    Log.i(TAG, "RAW capture supported: ${rawCaptureHelper.getCapabilityDescription(rawCapability)}")
                } else {
                    Log.d(TAG, "RAW capture not supported on this device")
                }
                
                // Observe pipeline state
                launch {
                    pipeline?.state?.collect { state ->
                        handlePipelineState(state)
                    }
                }
                
                _uiState.value = _uiState.value.copy(
                    isInitialized = true,
                    statusMessage = "Ready",
                    isModelAvailable = modelAvailable,
                    isRawCaptureSupported = rawCapability.isRawSupported
                )
                
                Log.d(TAG, "ViewModel initialized, SR model available: $modelAvailable, RAW supported: ${rawCapability.isRawSupported}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to initialize: ${e.message}"
                )
            }
        }
    }
    
    // Pending preset to apply after model download
    private var pendingPreset: UltraDetailPreset? = null
    
    /**
     * Check if SR model needs to be downloaded for MAX preset
     */
    fun checkModelForPreset(preset: UltraDetailPreset): Boolean {
        if (preset == UltraDetailPreset.MAX) {
            val available = modelDownloader.isModelAvailable(AvailableModels.ESRGAN_FP16)
            if (!available) {
                // Store the pending preset to apply after download
                pendingPreset = preset
                _uiState.value = _uiState.value.copy(showModelDownloadDialog = true)
                return false
            }
        }
        return true
    }
    
    /**
     * Start downloading the SR model
     */
    fun downloadModel() {
        viewModelScope.launch {
            modelDownloader.downloadModel(AvailableModels.ESRGAN_FP16).collect { state ->
                _uiState.value = _uiState.value.copy(modelDownloadState = state)
                
                when (state) {
                    is DownloadState.Complete -> {
                        // Apply the pending preset now that model is available
                        val presetToApply = pendingPreset
                        pendingPreset = null
                        
                        _uiState.value = _uiState.value.copy(
                            isModelAvailable = true,
                            showModelDownloadDialog = false,
                            selectedPreset = presetToApply ?: _uiState.value.selectedPreset
                        )
                        
                        if (presetToApply != null) {
                            Log.d(TAG, "Model downloaded, applying pending preset: $presetToApply")
                        }
                    }
                    is DownloadState.Error -> {
                        Log.e(TAG, "Model download failed: ${state.message}")
                        pendingPreset = null  // Clear pending preset on error
                    }
                    else -> {}
                }
            }
        }
    }
    
    /**
     * Dismiss the model download dialog
     * 
     * Fix #4: Improved UX - allow user to choose whether to download
     * 
     * @param startDownload If true, start the download; if false, just dismiss
     */
    fun dismissModelDownloadDialog(startDownload: Boolean = false) {
        if (startDownload) {
            // User chose to download - start the download
            downloadModel()
        } else {
            // User dismissed without downloading - clear pending preset
            pendingPreset = null
            _uiState.value = _uiState.value.copy(
                showModelDownloadDialog = false,
                modelDownloadState = DownloadState.Idle
            )
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
     * Set the denoising strength (FAST/BALANCED/MAX presets)
     * 
     * @param strength 0.0 = minimal denoising, 1.0 = aggressive denoising
     */
    fun setDenoiseStrength(strength: Float) {
        _uiState.value = _uiState.value.copy(
            denoiseStrength = strength.coerceIn(0f, 1f)
        )
        Log.d(TAG, "Denoise strength set to: $strength")
    }
    
    /**
     * Start burst capture and processing
     * Automatically selects high-quality or preview capture based on config
     */
    fun startCapture(burstController: BurstCaptureController) {
        if (_uiState.value.isCapturing || _uiState.value.isProcessing) {
            return
        }
        
        this.burstController = burstController
        val useHighQuality = burstController.config.captureQuality == CaptureQuality.HIGH_QUALITY
        
        viewModelScope.launch {
            try {
                val qualityLabel = if (useHighQuality) "high-quality" else "preview"
                _uiState.value = _uiState.value.copy(
                    isCapturing = true,
                    captureProgress = 0f,
                    statusMessage = "Capturing $qualityLabel burst...",
                    error = null,
                    resultBitmap = null
                )
                
                // Observe burst capture state
                launch {
                    burstController.captureState.collect { state ->
                        handleBurstState(state)
                    }
                }
                
                val preset = _uiState.value.selectedPreset
                
                // Choose capture path based on quality setting
                if (useHighQuality) {
                    // High-quality capture path (ImageCapture)
                    processHighQualityCapture(burstController, preset)
                } else {
                    // Preview capture path (ImageAnalysis) - existing behavior
                    processPreviewCapture(burstController, preset)
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
     * Process using high-quality capture (ImageCapture - full resolution)
     * 
     * Fix #1: Proper exception handling for capture failures
     * Fix #2: Safe frame recycling with try-catch per frame
     * Fix #3: Null pipeline check before processing
     */
    private suspend fun processHighQualityCapture(
        burstController: BurstCaptureController,
        preset: UltraDetailPreset
    ) {
        // Fix #1: Wrap capture in try-catch to handle partial failures
        val hqFrames = try {
            burstController.startHighQualityCapture(viewModelScope)
        } catch (e: Exception) {
            Log.e(TAG, "HQ capture failed with exception", e)
            _uiState.value = _uiState.value.copy(
                isCapturing = false,
                error = "High-quality capture failed: ${e.message}"
            )
            return
        }
        
        if (hqFrames.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                isCapturing = false,
                error = "No frames captured"
            )
            return
        }
        
        Log.i(TAG, "HQ Capture complete: ${hqFrames.size} frames")
        
        // Fix #3: Check pipeline is initialized before processing
        val activePipeline = pipeline ?: run {
            Log.e(TAG, "Pipeline not initialized")
            // Recycle frames before returning
            safeRecycleFrames(hqFrames)
            _uiState.value = _uiState.value.copy(
                isCapturing = false,
                isProcessing = false,
                error = "Pipeline not initialized. Please restart the app."
            )
            return
        }
        
        // Start foreground service for long processing
        if (preset == UltraDetailPreset.MAX || preset == UltraDetailPreset.ULTRA) {
            try {
                ProcessingService.start(context)
            } catch (e: Exception) {
                Log.w(TAG, "Could not start foreground service: ${e.message}")
            }
        }
        
        // Fix #5: Device-aware processing time estimates
        val estimatedTime = estimateProcessingTime(preset, isHighQuality = true)
        
        _uiState.value = _uiState.value.copy(
            isCapturing = false,
            isProcessing = true,
            processingProgress = 0f,
            processingStage = UiProcessingStage.ALIGNING,
            processingStartTimeMs = System.currentTimeMillis(),
            estimatedTotalTimeMs = estimatedTime,
            currentTile = 0,
            totalTiles = 0,
            statusMessage = "Processing high-quality frames..."
        )
        
        // Initialize pipeline
        activePipeline.initialize(preset)
        
        if (preset == UltraDetailPreset.ULTRA) {
            activePipeline.setRefinementStrength(_uiState.value.refinementStrength)
        }
        
        // Fix #2: Safe frame recycling - handle exceptions during processing
        var result: UltraDetailResult? = null
        var processingException: Exception? = null
        
        try {
            // Fix #8: Add timeout for processing
            result = withTimeoutOrNull(estimatedTime * 2) {
                activePipeline.processHighQuality(hqFrames, preset, viewModelScope)
            }
            
            if (result == null) {
                Log.e(TAG, "Processing timeout after ${estimatedTime * 2}ms")
                processingException = Exception("Processing timeout")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Processing failed with exception", e)
            processingException = e
        } finally {
            // Fix #2: Safe frame recycling - each frame in its own try-catch
            safeRecycleFrames(hqFrames)
            
            // Stop foreground service
            if (preset == UltraDetailPreset.MAX || preset == UltraDetailPreset.ULTRA) {
                try {
                    ProcessingService.stop(context)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not stop foreground service: ${e.message}")
                }
            }
        }
        
        // Handle result or exception
        if (processingException != null) {
            _uiState.value = _uiState.value.copy(
                isProcessing = false,
                processingStage = UiProcessingStage.IDLE,
                error = "Processing failed: ${processingException.message}"
            )
        } else {
            handleProcessingResult(result)
        }
    }
    
    /**
     * Fix #2: Safely recycle frames with individual try-catch to prevent cascade failures
     */
    private fun safeRecycleFrames(frames: List<HighQualityCapturedFrame>) {
        frames.forEach { frame ->
            try {
                frame.recycle()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to recycle frame: ${e.message}")
            }
        }
    }
    
    /**
     * Process using preview capture (ImageAnalysis - existing behavior)
     * 
     * Fix #3: Null pipeline check before processing
     * Fix #7: Async preview generation
     * Fix #8: Timeout handling
     */
    private suspend fun processPreviewCapture(
        burstController: BurstCaptureController,
        preset: UltraDetailPreset
    ) {
        // Start preview capture with exception handling
        val frames = try {
            burstController.startCapture(viewModelScope)
        } catch (e: Exception) {
            Log.e(TAG, "Preview capture failed", e)
            _uiState.value = _uiState.value.copy(
                isCapturing = false,
                error = "Burst capture failed: ${e.message}"
            )
            return
        }
        
        if (frames.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                isCapturing = false,
                error = "No frames captured"
            )
            return
        }
        
        // Fix #3: Check pipeline is initialized before processing
        val activePipeline = pipeline ?: run {
            Log.e(TAG, "Pipeline not initialized")
            _uiState.value = _uiState.value.copy(
                isCapturing = false,
                isProcessing = false,
                error = "Pipeline not initialized. Please restart the app."
            )
            return
        }
        
        // Fix #7: Generate preview asynchronously for ULTRA preset
        if (preset == UltraDetailPreset.ULTRA) {
            viewModelScope.launch {
                val preview = withContext(Dispatchers.Default) {
                    activePipeline.generateQuickPreview(frames)
                }
                _uiState.value = _uiState.value.copy(previewBitmap = preview)
            }
            try {
                ProcessingService.start(context)
            } catch (e: Exception) {
                Log.w(TAG, "Could not start foreground service: ${e.message}")
            }
        }
        
        // Fix #5: Device-aware processing time estimates
        val estimatedTime = estimateProcessingTime(preset, isHighQuality = false)
        
        _uiState.value = _uiState.value.copy(
            isCapturing = false,
            isProcessing = true,
            processingProgress = 0f,
            processingStage = UiProcessingStage.ALIGNING,
            processingStartTimeMs = System.currentTimeMillis(),
            estimatedTotalTimeMs = estimatedTime,
            currentTile = 0,
            totalTiles = 0,
            statusMessage = "Aligning frames..."
        )
        
        // Initialize pipeline
        activePipeline.initialize(preset)
        
        if (preset == UltraDetailPreset.ULTRA) {
            activePipeline.setRefinementStrength(_uiState.value.refinementStrength)
        }
        
        // Process frames with timeout
        var result: UltraDetailResult? = null
        var processingException: Exception? = null
        
        try {
            // Fix #8: Add timeout for processing
            result = withTimeoutOrNull(estimatedTime * 2) {
                activePipeline.process(frames, preset, viewModelScope)
            }
            
            if (result == null) {
                Log.e(TAG, "Processing timeout after ${estimatedTime * 2}ms")
                processingException = Exception("Processing timeout")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Processing failed", e)
            processingException = e
        } finally {
            if (preset == UltraDetailPreset.ULTRA) {
                try {
                    ProcessingService.stop(context)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not stop foreground service: ${e.message}")
                }
            }
        }
        
        // Handle result or exception
        if (processingException != null) {
            _uiState.value = _uiState.value.copy(
                isProcessing = false,
                processingStage = UiProcessingStage.IDLE,
                error = "Processing failed: ${processingException.message}"
            )
        } else {
            handleProcessingResult(result)
        }
    }
    
    /**
     * Fix #5: Device-aware processing time estimation
     * 
     * Estimates processing time based on device performance and preset.
     * Prevents progress bar from getting "stuck" at 100%.
     */
    private fun estimateProcessingTime(preset: UltraDetailPreset, isHighQuality: Boolean): Long {
        val deviceScore = getDevicePerformanceScore()
        
        // Base times increased significantly to avoid false timeouts on real devices
        // Real-world processing times can vary widely based on image complexity and device load
        val baseTime = when (preset) {
            UltraDetailPreset.FAST -> if (isHighQuality) 10000L else 5000L
            UltraDetailPreset.BALANCED -> if (isHighQuality) 20000L else 10000L
            UltraDetailPreset.MAX -> if (isHighQuality) 120000L else 60000L      // 2 min base for MAX
            UltraDetailPreset.ULTRA -> if (isHighQuality) 300000L else 180000L   // 5 min base for ULTRA
        }
        
        // Adjust based on device performance
        return when {
            deviceScore >= 8 -> (baseTime * 0.7).toLong()   // Flagship: 30% faster
            deviceScore >= 5 -> baseTime                     // Mid-range: baseline
            deviceScore >= 3 -> (baseTime * 1.5).toLong()   // Budget: 50% slower
            else -> (baseTime * 2.0).toLong()               // Low-end: 2x slower
        }
    }
    
    /**
     * Get a rough device performance score (0-10)
     * 
     * Based on available memory and CPU cores.
     */
    private fun getDevicePerformanceScore(): Int {
        val runtime = Runtime.getRuntime()
        val maxMemoryMB = runtime.maxMemory() / (1024 * 1024)
        val availableProcessors = runtime.availableProcessors()
        
        // Score based on memory (0-5 points)
        val memoryScore = when {
            maxMemoryMB >= 512 -> 5
            maxMemoryMB >= 384 -> 4
            maxMemoryMB >= 256 -> 3
            maxMemoryMB >= 192 -> 2
            maxMemoryMB >= 128 -> 1
            else -> 0
        }
        
        // Score based on CPU cores (0-5 points)
        val cpuScore = when {
            availableProcessors >= 8 -> 5
            availableProcessors >= 6 -> 4
            availableProcessors >= 4 -> 3
            availableProcessors >= 2 -> 2
            else -> 1
        }
        
        return memoryScore + cpuScore
    }
    
    /**
     * Handle processing result and update UI state
     */
    private fun handleProcessingResult(result: UltraDetailResult?) {
        if (result != null) {
            val mfsrStatus = if (result.mfsrApplied) " (MFSR ${result.mfsrScaleFactor}x)" else ""
            val alignmentStatus = if (result.alignmentQuality > 0) " (${result.alignmentQuality.toInt()}% aligned)" else ""
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
                alignmentQuality = result.alignmentQuality,
                statusMessage = "Complete! ${result.processingTimeMs}ms$mfsrStatus$alignmentStatus"
            )
        } else {
            _uiState.value = _uiState.value.copy(
                isProcessing = false,
                processingStage = UiProcessingStage.IDLE,
                error = "Processing failed"
            )
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
                    // Fix #9: Sanitize filename to remove invalid characters
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val presetSuffix = when (_uiState.value.selectedPreset) {
                        UltraDetailPreset.FAST -> "Fast"
                        UltraDetailPreset.BALANCED -> "Balanced"
                        UltraDetailPreset.MAX -> "Max"
                        UltraDetailPreset.ULTRA -> "Ultra"
                    }
                    val mfsrSuffix = if (_uiState.value.mfsrApplied) "_MFSR${_uiState.value.mfsrScaleFactor}x" else ""
                    // Sanitize: remove any non-alphanumeric characters except underscore and hyphen
                    val sanitizedPreset = presetSuffix.replace(Regex("[^a-zA-Z0-9_-]"), "")
                    val sanitizedMfsr = mfsrSuffix.replace(Regex("[^a-zA-Z0-9_-]"), "")
                    val filename = "UltraDetail_${timestamp}_${sanitizedPreset}${sanitizedMfsr}.jpg"
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
                        
                        // Show completion notification with preview
                        val resolution = formatResolution(bitmap.width, bitmap.height)
                        ProcessingService.showCompletionNotification(
                            context = context,
                            savedUri = uri,
                            previewBitmap = bitmap,
                            processingTimeMs = _uiState.value.processingTimeMs,
                            resolution = resolution
                        )
                        
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
            is BurstCaptureState.HighQualityComplete -> {
                _uiState.value = _uiState.value.copy(
                    isCapturing = false,
                    statusMessage = "HQ burst captured: ${state.frames.size} frames"
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
            is PipelineState.ProcessingMFSR -> {
                // MFSR tile processing - use the progress value from native code
                val progress = if (state.totalTiles > 0) {
                    state.tilesProcessed.toFloat() / state.totalTiles
                } else state.progress
                
                _uiState.value = _uiState.value.copy(
                    processingProgress = progress,
                    processingStage = UiProcessingStage.SUPER_RESOLUTION,
                    currentTile = state.tilesProcessed,
                    totalTiles = state.totalTiles,
                    statusMessage = if (state.totalTiles > 0) {
                        "MFSR: ${state.tilesProcessed}/${state.totalTiles} tiles (${(progress * 100).toInt()}%)"
                    } else {
                        "MFSR processing... ${(state.progress * 100).toInt()}%"
                    }
                )
            }
            is PipelineState.RefiningMFSR -> {
                val progress = if (state.totalTiles > 0) {
                    state.tilesProcessed.toFloat() / state.totalTiles
                } else 0f
                
                _uiState.value = _uiState.value.copy(
                    processingProgress = progress,
                    processingStage = UiProcessingStage.SUPER_RESOLUTION,
                    currentTile = state.tilesProcessed,
                    totalTiles = state.totalTiles,
                    statusMessage = "Neural refinement: ${state.tilesProcessed}/${state.totalTiles} tiles"
                )
            }
            is PipelineState.Complete -> {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    processingStage = UiProcessingStage.IDLE,
                    resultBitmap = state.result,
                    processingTimeMs = state.processingTimeMs,
                    framesUsed = state.framesUsed,
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
    
    /**
     * Format resolution in a user-friendly way (e.g., "12MP" or "4032×3024")
     */
    private fun formatResolution(width: Int, height: Int): String {
        val megapixels = (width.toLong() * height) / 1_000_000.0
        return when {
            megapixels >= 1.0 -> String.format("%.1fMP", megapixels)
            else -> "${width}×${height}"
        }
    }
}
