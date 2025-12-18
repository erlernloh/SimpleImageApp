# Ultra Detail+ Architecture Refactor - Detailed Task Breakdown

## Overview
This document breaks down the architecture refactor into granular, traceable tasks with exact file locations and dependencies.

---

## PHASE 0: PREPARATION - Audit & Documentation
**Status:** ✅ COMPLETED
**Purpose:** Understand all existing dependencies before making changes

### Task 0.1: Document CapturedFrame Usage ✅
**Files Affected:**
- `BurstCaptureController.kt:67-127` - Definition
- `UltraDetailPipeline.kt` - 8 functions use it
- `NativeMFSRPipeline.kt:109` - processYUV input
- `MFSRPipeline.kt:171, 296` - process functions
- `GyroAlignmentHelper.kt:274-275, 321` - homography computation

### Task 0.2: Document BurstCaptureController Interface ✅
**Public Methods:**
- `setup()` - Line 182-224
- `startCapture()` - Line 232-304
- `captureState` - Line 149-150
- `cancelCapture()` - Line 351-357
- `release()` - Line 410-416

**Consumers:**
- `UltraDetailScreen.kt:65, 131, 147`
- `UltraDetailViewModel.kt:91, 212, 231, 237`

### Task 0.3: Document UltraDetailPipeline Interface ✅
**Public Methods:**
- `initialize()` - Line ~180
- `process()` - Line 230-442
- `generateQuickPreview()` - Line 571-590
- `state` - StateFlow

**Consumers:**
- `UltraDetailViewModel.kt:90, 252, 286, 290, 295`

---

## PHASE 1: DATA LAYER - New Frame Data Structures
**Status:** 🔲 PENDING
**Purpose:** Create new data structures that support both YUV (legacy) and Bitmap (high-quality) frames

### Task 1.1: Create HighQualityCapturedFrame Data Class
**File:** `BurstCaptureController.kt`
**Location:** After line 127 (after CapturedFrame definition)
**New Code:**
```kotlin
/**
 * High-quality captured frame using Bitmap instead of YUV planes.
 * Used when ImageCapture is available for full-resolution capture.
 */
data class HighQualityCapturedFrame(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val timestamp: Long,
    val gyroSamples: List<GyroSample> = emptyList(),
    val exposureTimeNs: Long = 0,
    val iso: Int = 0
) {
    fun recycle() {
        if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
    }
}
```
**Dependencies:** None
**Dependents:** Tasks 2.2, 2.3, 3.1, 3.2, 3.3

### Task 1.2: Create CaptureQuality Enum
**File:** `BurstCaptureController.kt`
**Location:** After line 49 (after BurstCaptureConfig)
**New Code:**
```kotlin
/**
 * Capture quality mode
 */
enum class CaptureQuality {
    PREVIEW,      // Use ImageAnalysis (current behavior, faster but lower quality)
    HIGH_QUALITY  // Use ImageCapture (slower but full sensor quality)
}
```
**Dependencies:** None
**Dependents:** Task 1.3, 2.1

### Task 1.3: Update BurstCaptureConfig
**File:** `BurstCaptureController.kt`
**Location:** Line 40-49
**Current:**
```kotlin
data class BurstCaptureConfig(
    val frameCount: Int = 8,
    val targetResolution: Size = Size(4000, 3000),
    val lockAeAf: Boolean = true,
    val frameIntervalMs: Long = 150
)
```
**New:**
```kotlin
data class BurstCaptureConfig(
    val frameCount: Int = 8,
    val targetResolution: Size = Size(4000, 3000),
    val lockAeAf: Boolean = true,
    val frameIntervalMs: Long = 150,
    val captureQuality: CaptureQuality = CaptureQuality.PREVIEW  // NEW
)
```
**Dependencies:** Task 1.2
**Dependents:** Task 2.1, 6.1

---

## PHASE 2: CAPTURE LAYER - New Capture Mechanism
**Status:** 🔲 PENDING
**Purpose:** Add ImageCapture-based burst capture alongside existing ImageAnalysis

### Task 2.1: Add ImageCapture Use Case
**File:** `BurstCaptureController.kt`
**Location:** Line 153 (add new member variable)
**Changes:**
1. Add `private var imageCapture: ImageCapture? = null` after line 153
2. Modify `setup()` function (line 182-224) to create ImageCapture use case
3. Bind ImageCapture to lifecycle alongside ImageAnalysis

**New Code in setup():**
```kotlin
// Add ImageCapture for high-quality burst
if (config.captureQuality == CaptureQuality.HIGH_QUALITY) {
    imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setTargetResolution(config.targetResolution)
        .build()
}
```
**Dependencies:** Task 1.2, 1.3
**Dependents:** Task 2.2

### Task 2.2: Implement captureHighQualityFrame()
**File:** `BurstCaptureController.kt`
**Location:** After line 304 (after startCapture)
**New Function:**
```kotlin
/**
 * Capture a single high-quality frame using ImageCapture
 */
private suspend fun captureHighQualityFrame(): HighQualityCapturedFrame? {
    val capture = imageCapture ?: return null
    
    return suspendCancellableCoroutine { continuation ->
        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bitmap = imageToBitmap(image)
                        val frame = HighQualityCapturedFrame(
                            bitmap = bitmap,
                            width = image.width,
                            height = image.height,
                            timestamp = System.nanoTime(),
                            gyroSamples = getGyroSamplesForFrame(System.nanoTime())
                        )
                        continuation.resume(frame)
                    } finally {
                        image.close()
                    }
                }
                
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "High-quality capture failed", exception)
                    continuation.resume(null)
                }
            }
        )
    }
}

private fun imageToBitmap(image: ImageProxy): Bitmap {
    // Convert ImageProxy to Bitmap
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}
```
**Dependencies:** Task 1.1, 2.1
**Dependents:** Task 2.3

### Task 2.3: Implement startHighQualityCapture()
**File:** `BurstCaptureController.kt`
**Location:** After captureHighQualityFrame()
**New Function:**
```kotlin
/**
 * Start high-quality burst capture using ImageCapture
 * 
 * @return List of high-quality captured frames
 */
suspend fun startHighQualityCapture(scope: CoroutineScope): List<HighQualityCapturedFrame> {
    if (isCapturing || imageCapture == null) {
        return emptyList()
    }
    
    isCapturing = true
    val frames = mutableListOf<HighQualityCapturedFrame>()
    
    try {
        startGyroRecording()
        lockExposureAndFocus()
        
        _captureState.value = BurstCaptureState.Capturing(0, config.frameCount)
        
        repeat(config.frameCount) { index ->
            val frame = captureHighQualityFrame()
            if (frame != null) {
                frames.add(frame)
                _captureState.value = BurstCaptureState.Capturing(index + 1, config.frameCount)
                Log.d(TAG, "HQ Captured frame ${index + 1}/${config.frameCount}")
            }
            
            if (index < config.frameCount - 1) {
                delay(config.frameIntervalMs)
            }
        }
        
        stopGyroRecording()
        unlockExposureAndFocus()
        
        _captureState.value = BurstCaptureState.HighQualityComplete(frames)
        
    } catch (e: Exception) {
        Log.e(TAG, "High-quality burst capture failed", e)
        frames.forEach { it.recycle() }
        _captureState.value = BurstCaptureState.Error(e.message ?: "Unknown error")
        return emptyList()
    } finally {
        isCapturing = false
    }
    
    return frames
}
```
**Dependencies:** Task 1.1, 2.2
**Dependents:** Task 5.1, 6.1

### Task 2.4: Add Camera2 Interop for Manual AE Lock
**File:** `BurstCaptureController.kt`
**Location:** Modify lockExposureAndFocus() at line 380-403
**Changes:**
```kotlin
private suspend fun lockExposureAndFocus() {
    camera?.let { cam ->
        try {
            // Use Camera2 interop for proper AE lock
            val camera2Control = Camera2CameraControl.from(cam.cameraControl)
            camera2Control.captureRequestOptions = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                .build()
            
            // Also do standard CameraX focus lock
            // ... existing code ...
        } catch (e: Exception) {
            Log.w(TAG, "Camera2 interop failed, using standard lock", e)
            // Fallback to existing implementation
        }
    }
}
```
**New Imports Required:**
```kotlin
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import android.hardware.camera2.CaptureRequest
```
**Dependencies:** None
**Dependents:** Task 2.3

### Task 2.5: Update BurstCaptureState
**File:** `BurstCaptureController.kt`
**Location:** Line 132-137
**Current:**
```kotlin
sealed class BurstCaptureState {
    object Idle : BurstCaptureState()
    data class Capturing(val framesCollected: Int, val totalFrames: Int) : BurstCaptureState()
    data class Complete(val frames: List<CapturedFrame>) : BurstCaptureState()
    data class Error(val message: String) : BurstCaptureState()
}
```
**New:**
```kotlin
sealed class BurstCaptureState {
    object Idle : BurstCaptureState()
    data class Capturing(val framesCollected: Int, val totalFrames: Int) : BurstCaptureState()
    data class Complete(val frames: List<CapturedFrame>) : BurstCaptureState()
    data class HighQualityComplete(val frames: List<HighQualityCapturedFrame>) : BurstCaptureState()  // NEW
    data class Error(val message: String) : BurstCaptureState()
}
```
**Dependencies:** Task 1.1
**Dependents:** Task 5.2

### Task 2.6: Maintain Backward Compatibility
**File:** `BurstCaptureController.kt`
**Purpose:** Ensure existing YUV capture path still works
**Changes:**
- Keep `startCapture()` function unchanged
- Add quality check in `setup()` to configure appropriate use case
- Document which path is used based on config

---

## PHASE 3: PIPELINE LAYER - Processing Updates
**Status:** 🔲 PENDING
**Purpose:** Add Bitmap-based processing path to UltraDetailPipeline

### Task 3.1: Add processHighQuality() Function
**File:** `UltraDetailPipeline.kt`
**Location:** After process() function (after line 442)
**New Function:**
```kotlin
/**
 * Process high-quality Bitmap frames
 * 
 * @param frames List of high-quality captured frames
 * @param preset Processing preset
 * @param scope Coroutine scope
 * @return Processing result
 */
suspend fun processHighQuality(
    frames: List<HighQualityCapturedFrame>,
    preset: UltraDetailPreset,
    scope: CoroutineScope
): UltraDetailResult? = withContext(Dispatchers.Default) {
    // Similar to process() but uses Bitmap input directly
    // Skip YUV conversion step
    // ...
}
```
**Dependencies:** Task 1.1
**Dependents:** Task 5.1

### Task 3.2: Update selectBestFrame() for HighQualityCapturedFrame
**File:** `UltraDetailPipeline.kt`
**Location:** Line 601-640
**Changes:** Add overloaded version for HighQualityCapturedFrame
```kotlin
/**
 * Select best frame from high-quality captures
 */
private fun selectBestFrameHQ(frames: List<HighQualityCapturedFrame>): Int {
    if (frames.size <= 1) return 0
    
    val metrics = frames.mapIndexed { index, frame ->
        val gyroMag = computeGyroMagnitude(frame.gyroSamples)
        val sharpness = computeBitmapSharpness(frame.bitmap)
        Triple(index, gyroMag, sharpness)
    }
    // ... same scoring logic as selectBestFrame()
}
```
**Dependencies:** Task 1.1, Task 3.3
**Dependents:** Task 3.1

### Task 3.3: Add computeBitmapSharpness() Function
**File:** `UltraDetailPipeline.kt`
**Location:** After computeFrameSharpness() (after line 698)
**New Function:**
```kotlin
/**
 * Compute image sharpness from Bitmap using Laplacian variance
 */
private fun computeBitmapSharpness(bitmap: Bitmap): Float {
    val width = bitmap.width
    val height = bitmap.height
    
    // Sample center region
    val sampleSize = 128
    val startX = (width - sampleSize) / 2
    val startY = (height - sampleSize) / 2
    
    var sum = 0.0
    var sumSq = 0.0
    var count = 0
    
    for (y in startY until (startY + sampleSize - 2) step 2) {
        for (x in startX until (startX + sampleSize - 2) step 2) {
            // Get grayscale values
            val center = getGrayscale(bitmap, x, y)
            val up = getGrayscale(bitmap, x, y - 1)
            val down = getGrayscale(bitmap, x, y + 1)
            val left = getGrayscale(bitmap, x - 1, y)
            val right = getGrayscale(bitmap, x + 1, y)
            
            val laplacian = 4f * center - up - down - left - right
            sum += laplacian
            sumSq += laplacian * laplacian
            count++
        }
    }
    
    if (count == 0) return 0f
    val mean = sum / count
    val variance = (sumSq / count) - (mean * mean)
    return variance.toFloat().coerceAtLeast(0f)
}

private fun getGrayscale(bitmap: Bitmap, x: Int, y: Int): Float {
    val pixel = bitmap.getPixel(x, y)
    val r = (pixel shr 16) and 0xFF
    val g = (pixel shr 8) and 0xFF
    val b = pixel and 0xFF
    return 0.299f * r + 0.587f * g + 0.114f * b
}
```
**Dependencies:** None
**Dependents:** Task 3.2

### Task 3.4: Update processUltraPreset() for Bitmap Path
**File:** `UltraDetailPipeline.kt`
**Location:** Line 450-551
**Changes:** Add overloaded version or conditional logic for Bitmap input
**Dependencies:** Task 3.1
**Dependents:** None

### Task 3.5: Update analyzeFrameDiversity() for New Frame Type
**File:** `UltraDetailPipeline.kt`
**Location:** Line 748-769
**Changes:** Add overloaded version for HighQualityCapturedFrame
**Dependencies:** Task 1.1
**Dependents:** Task 3.1

---

## PHASE 4: NATIVE LAYER - JNI and C++ Updates
**Status:** 🔲 PENDING
**Purpose:** Add native Bitmap array processing (optional optimization)

### Task 4.1: Add nativeProcessBitmapArray() to NativeBurstProcessor
**File:** `NativeBurstProcessor.kt`
**Location:** After line 243
**New JNI Method:**
```kotlin
private external fun nativeProcessBitmaps(
    handle: Long,
    inputBitmaps: Array<Bitmap>,
    outputBitmap: Bitmap,
    callback: NativeProgressCallback?
): Int
```
**Dependencies:** None
**Dependents:** Task 4.2, 4.3

### Task 4.2: Implement processBitmapArray() in C++
**File:** `burst_processor.cpp`
**New Function:** Process array of Bitmaps directly
**Dependencies:** Task 4.1
**Dependents:** None

### Task 4.3: Update ultradetail_jni.cpp
**File:** `ultradetail_jni.cpp`
**Changes:** Add JNI bridge for nativeProcessBitmaps
**Dependencies:** Task 4.1, 4.2
**Dependents:** None

### Task 4.4: Verify NativeMFSRPipeline.processBitmaps()
**File:** `NativeMFSRPipeline.kt`
**Purpose:** Verify existing Bitmap processing works correctly
**Status:** Already implemented, needs testing
**Dependencies:** None
**Dependents:** Task 3.4

---

## PHASE 5: VIEWMODEL LAYER - State Management
**Status:** 🔲 PENDING
**Purpose:** Update ViewModel to handle new capture flow

### Task 5.1: Update startCapture() in ViewModel
**File:** `UltraDetailViewModel.kt`
**Location:** Line 212-350
**Changes:**
```kotlin
fun startCapture(burstController: BurstCaptureController) {
    // Check capture quality from config
    val useHighQuality = burstController.config.captureQuality == CaptureQuality.HIGH_QUALITY
    
    if (useHighQuality) {
        // Use new high-quality capture path
        val hqFrames = burstController.startHighQualityCapture(viewModelScope)
        val result = pipeline?.processHighQuality(hqFrames, preset, viewModelScope)
        // ... handle result
    } else {
        // Use existing YUV capture path
        val frames = burstController.startCapture(viewModelScope)
        val result = pipeline?.process(frames, preset, viewModelScope)
        // ... handle result
    }
}
```
**Dependencies:** Task 2.3, 3.1
**Dependents:** Task 6.1

### Task 5.2: Update UltraDetailUiState
**File:** `UltraDetailViewModel.kt`
**Location:** Line 30-85 (UltraDetailUiState data class)
**Changes:** Add capture quality indicator
```kotlin
data class UltraDetailUiState(
    // ... existing fields ...
    val captureQuality: CaptureQuality = CaptureQuality.PREVIEW,  // NEW
    val isHighQualityCapture: Boolean = false  // NEW
)
```
**Dependencies:** Task 1.2
**Dependents:** Task 6.2

### Task 5.3: Update Processing Time Estimates
**File:** `UltraDetailViewModel.kt`
**Location:** Line 265-270
**Changes:** Adjust estimates for high-quality capture (longer capture time)
```kotlin
val estimatedTime = when (preset) {
    UltraDetailPreset.FAST -> if (useHighQuality) 5000L else 3000L
    UltraDetailPreset.BALANCED -> if (useHighQuality) 8000L else 5000L
    UltraDetailPreset.MAX -> if (useHighQuality) 15000L else 8000L
    UltraDetailPreset.ULTRA -> if (useHighQuality) 90000L else 60000L
}
```
**Dependencies:** Task 5.1
**Dependents:** Task 6.3

---

## PHASE 6: UI LAYER - Screen Updates
**Status:** 🔲 PENDING
**Purpose:** Update UI to use new capture flow and show quality indicators

### Task 6.1: Update Capture Button Handler
**File:** `UltraDetailScreen.kt`
**Location:** Line 115-129 (BurstCaptureConfig creation)
**Changes:** Add captureQuality to config based on preset
```kotlin
val config = BurstCaptureConfig(
    frameCount = when (uiState.selectedPreset) { ... },
    targetResolution = Size(4000, 3000),
    frameIntervalMs = when (uiState.selectedPreset) { ... },
    captureQuality = when (uiState.selectedPreset) {
        UltraDetailPreset.FAST -> CaptureQuality.PREVIEW
        UltraDetailPreset.BALANCED -> CaptureQuality.PREVIEW
        UltraDetailPreset.MAX -> CaptureQuality.HIGH_QUALITY
        UltraDetailPreset.ULTRA -> CaptureQuality.HIGH_QUALITY
    }
)
```
**Dependencies:** Task 1.2, 1.3
**Dependents:** None

### Task 6.2: Add Capture Quality Indicator
**File:** `UltraDetailScreen.kt`
**Location:** In preset selector or capture button area
**New UI Element:** Show "HQ" badge when high-quality capture is enabled
**Dependencies:** Task 5.2
**Dependents:** None

### Task 6.3: Update Progress Indicators
**File:** `UltraDetailScreen.kt`
**Changes:** Update progress text to reflect sequential capture
**Dependencies:** Task 5.3
**Dependents:** None

### Task 6.4: Update Preset Descriptions
**File:** `UltraDetailScreen.kt`
**Location:** Line 428-509 (PresetSelector)
**Changes:** Update descriptions to mention capture quality
```kotlin
// For MAX preset
Text("AI-enhanced sharpness • 12 HQ photos (~3s capture)",
    style = MaterialTheme.typography.bodySmall)

// For ULTRA preset  
Text("2× larger image with AI polish • 10 HQ photos (~4s capture)",
    style = MaterialTheme.typography.bodySmall)
```
**Dependencies:** None
**Dependents:** None

---

## PHASE 7: INTEGRATION & TESTING
**Status:** 🔲 PENDING
**Purpose:** Verify all changes work together

### Task 7.1-7.4: Preset Integration Tests
**Test each preset with both capture qualities**

### Task 7.5: Memory Profiling
**Verify memory usage is acceptable**

### Task 7.6: Fallback Testing
**Verify YUV path still works when ImageCapture unavailable**

---

## Execution Order

```
Phase 0 (Completed)
    ↓
Phase 1: Data Layer
    1.2 → 1.3 → 1.1
    ↓
Phase 2: Capture Layer
    2.1 → 2.4 → 2.2 → 2.3 → 2.5 → 2.6
    ↓
Phase 3: Pipeline Layer
    3.3 → 3.2 → 3.5 → 3.1 → 3.4
    ↓
Phase 4: Native Layer (Optional)
    4.1 → 4.2 → 4.3 → 4.4
    ↓
Phase 5: ViewModel Layer
    5.2 → 5.1 → 5.3
    ↓
Phase 6: UI Layer
    6.1 → 6.2 → 6.3 → 6.4
    ↓
Phase 7: Testing
    7.1 → 7.2 → 7.3 → 7.4 → 7.5 → 7.6
```

---

## Risk Mitigation

1. **Feature Flag:** Add `USE_HIGH_QUALITY_CAPTURE` flag to enable/disable new path
2. **Graceful Fallback:** If ImageCapture fails, fall back to ImageAnalysis
3. **Memory Guard:** Check available memory before high-quality capture
4. **Device Compatibility:** Check Camera2 API level before using interop

---

*Document created: December 15, 2024*
*Total Tasks: 35*
*Estimated Total Effort: 8-12 hours*
