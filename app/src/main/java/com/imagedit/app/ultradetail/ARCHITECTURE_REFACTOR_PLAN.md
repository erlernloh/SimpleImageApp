# Ultra Detail+ Architecture Refactor Plan

## Executive Summary

The current Ultra Detail+ implementation has fundamental architectural issues that limit image quality. This document outlines a comprehensive refactor plan to address these issues.

---

## Dependency Map (Verified from Codebase)

### CapturedFrame Data Class
**Definition:** `BurstCaptureController.kt:67-127`
```kotlin
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
    val gyroSamples: List<GyroSample> = emptyList()
)
```

**Used By:**
| File | Functions | Usage |
|------|-----------|-------|
| `UltraDetailPipeline.kt` | `process()`, `processUltraPreset()`, `generateQuickPreview()`, `selectBestFrame()`, `computeFrameSharpness()`, `analyzeFrameDiversity()`, `convertYUVToBitmap()`, `convertYUVToBitmapCrop()` | Main processing input |
| `NativeMFSRPipeline.kt` | `processYUV()` | MFSR processing input |
| `MFSRPipeline.kt` | `process()`, `processNativeMFSR()` | MFSR wrapper input |
| `GyroAlignmentHelper.kt` | `computeFrameHomography()`, `computeAllHomographies()` | Gyro data extraction |
| `BurstCaptureController.kt` | `startCapture()`, `handleFrame()`, `logGyroStatistics()` | Frame creation/collection |

### BurstCaptureController Class
**Definition:** `BurstCaptureController.kt:145-558`

**Public Interface:**
- `setup(cameraProvider, lifecycleOwner, cameraSelector, preview)` - Camera initialization
- `startCapture(scope): List<CapturedFrame>` - Burst capture
- `captureState: StateFlow<BurstCaptureState>` - State observation
- `cancelCapture()` - Cancel operation
- `release()` - Cleanup

**Used By:**
| File | Location | Usage |
|------|----------|-------|
| `UltraDetailScreen.kt:65` | `var burstController by remember { mutableStateOf<BurstCaptureController?>(null) }` | Controller instance |
| `UltraDetailScreen.kt:131` | `burstController = BurstCaptureController(context, config).apply { setup(...) }` | Creation & setup |
| `UltraDetailScreen.kt:147` | `burstController?.release()` | Cleanup |
| `UltraDetailViewModel.kt:91` | `private var burstController: BurstCaptureController? = null` | Reference storage |
| `UltraDetailViewModel.kt:212` | `fun startCapture(burstController: BurstCaptureController)` | Capture initiation |
| `UltraDetailViewModel.kt:231` | `burstController.captureState.collect { state -> ... }` | State observation |
| `UltraDetailViewModel.kt:237` | `val frames = burstController.startCapture(viewModelScope)` | Frame capture |

### UltraDetailPipeline Class
**Definition:** `UltraDetailPipeline.kt`

**Public Interface:**
- `initialize(preset: UltraDetailPreset): Boolean` - Initialize for preset
- `process(frames: List<CapturedFrame>, preset, scope): UltraDetailResult?` - Main processing
- `generateQuickPreview(frames: List<CapturedFrame>): Bitmap?` - Quick preview
- `setRefinementStrength(strength: Float)` - Set MFSR refinement
- `state: StateFlow<PipelineState>` - State observation
- `cancel()` / `reset()` / `close()` - Lifecycle

**Used By:**
| File | Location | Usage |
|------|----------|-------|
| `UltraDetailViewModel.kt:90` | `private var pipeline: UltraDetailPipeline? = null` | Pipeline instance |
| `UltraDetailViewModel.kt:252` | `pipeline?.generateQuickPreview(frames)` | Preview generation |
| `UltraDetailViewModel.kt:286` | `pipeline?.initialize(preset)` | Initialization |
| `UltraDetailViewModel.kt:290` | `pipeline?.setRefinementStrength(...)` | Config |
| `UltraDetailViewModel.kt:295` | `pipeline?.process(frames, preset, viewModelScope)` | Processing |

### Native JNI Methods (NativeBurstProcessor)
**Definition:** `NativeBurstProcessor.kt:211-243`

```kotlin
// Current YUV processing
private external fun nativeProcessYUV(
    handle: Long,
    yPlanes: Array<ByteBuffer>,
    uPlanes: Array<ByteBuffer>,
    vPlanes: Array<ByteBuffer>,
    yRowStrides: IntArray,
    uvRowStrides: IntArray,
    uvPixelStrides: IntArray,
    width: Int,
    height: Int,
    outputBitmap: Bitmap,
    callback: NativeProgressCallback?
): Int

// Need to add: nativeProcessBitmaps() for high-quality path
```

### Native JNI Methods (NativeMFSRPipeline)
**Definition:** `NativeMFSRPipeline.kt`

```kotlin
// Already supports Bitmap input
fun processBitmaps(
    inputBitmaps: Array<Bitmap>,
    referenceIndex: Int,
    homographies: FloatArray?,
    outputBitmap: Bitmap,
    progressCallback: MFSRProgressCallback?
): Int

// Also supports YUV input
fun processYUV(
    frames: List<CapturedFrame>,
    referenceIndex: Int,
    homographies: List<Homography>?,
    outputBitmap: Bitmap,
    progressCallback: MFSRProgressCallback?
): Int
```

---

## Problem Statement

### Core Issue: ImageAnalysis vs ImageCapture

The current implementation uses `ImageAnalysis` for burst capture, which provides **preview-quality frames** from the camera's continuous stream. This is fundamentally wrong for a feature designed to create "ultra" quality images.

**Current Flow:**
```
Camera Preview Stream → ImageAnalysis → YUV Frames → Processing
                        (preview quality)
```

**Required Flow:**
```
Camera → ImageCapture (triggered) → Full-resolution JPEG/YUV → Processing
         (full sensor quality)
```

### Impact of Current Architecture

| Aspect | Current | Required |
|--------|---------|----------|
| Frame Quality | Preview (~2MP effective) | Full sensor (~12MP) |
| Exposure Control | Limited | Full manual control |
| Noise Level | Higher (preview processing) | Lower (RAW/full processing) |
| Dynamic Range | Reduced | Full sensor capability |

---

## Files Requiring Changes

### 1. **BurstCaptureController.kt** - MAJOR REWRITE
**Current:** Uses `ImageAnalysis` with continuous frame capture
**Required:** Switch to `ImageCapture` with burst mode or repeated captures

#### Functions to Modify:
- `setup()` - Replace ImageAnalysis with ImageCapture use case
- `startCapture()` - Change from channel-based to sequential ImageCapture.takePicture()
- `handleFrame()` - Remove (no longer needed)
- `lockExposureAndFocus()` - Enhance with manual exposure settings
- Add new: `captureFrame()` - Single frame capture with full quality

#### New Data Structures:
```kotlin
data class HighQualityCapturedFrame(
    val bitmap: Bitmap,           // Full resolution ARGB
    val exifData: ExifInterface?, // Exposure metadata
    val timestamp: Long,
    val gyroSamples: List<GyroSample>
)
```

### 2. **UltraDetailPipeline.kt** - MODERATE CHANGES
**Current:** Expects YUV ByteBuffer frames
**Required:** Handle both YUV and Bitmap inputs

#### Functions to Modify:
- `process()` - Add overload for Bitmap input
- `processUltraPreset()` - Update to use high-quality frames
- `convertYUVToBitmap()` - Keep for backward compatibility
- `convertYUVToBitmapCrop()` - Keep for preview generation
- `selectBestFrame()` - Update for new frame type
- `computeFrameSharpness()` - Update for Bitmap input

#### New Functions:
- `processHighQualityFrames()` - New entry point for Bitmap-based processing

### 3. **NativeBurstProcessor.kt** - MODERATE CHANGES
**Current:** Only accepts YUV planes
**Required:** Add Bitmap processing path

#### Functions to Modify:
- Add `processBitmaps()` - Direct Bitmap array processing
- Keep `processYUV()` - For backward compatibility with preview

#### JNI Changes Required:
- Add native method for Bitmap array processing

### 4. **NativeMFSRPipeline.kt** - MINOR CHANGES
**Current:** Already supports both YUV and Bitmap
**Required:** Ensure Bitmap path is optimized

#### Functions to Verify:
- `processBitmaps()` - Already exists, verify quality
- `processYUV()` - Keep for compatibility

### 5. **UltraDetailScreen.kt** - MODERATE CHANGES
**Current:** Triggers burst via BurstCaptureController
**Required:** Update capture flow for new mechanism

#### Functions to Modify:
- `CaptureButton` onClick handler - Update capture initiation
- Preview handling - May need adjustment for capture feedback
- Progress indicators - Update for new capture stages

### 6. **UltraDetailViewModel.kt** - MINOR CHANGES
**Current:** Manages capture state
**Required:** Update state handling for new capture flow

#### Functions to Modify:
- `startCapture()` - Update for new capture mechanism
- State management - Add new states for sequential capture

### 7. **Native C++ Files** - MODERATE CHANGES

#### burst_processor.cpp / burst_processor.h
- Add `processBitmaps()` function
- Optimize for ARGB input (skip YUV conversion)

#### ultradetail_jni.cpp
- Add JNI bridge for Bitmap array processing
- Update progress callbacks

---

## Implementation Phases

### Phase 1: New Capture Mechanism (HIGH PRIORITY)
**Estimated Effort:** 3-4 hours

1. Create new `HighQualityBurstCapture` class
2. Implement sequential `ImageCapture.takePicture()` with delays
3. Add proper exposure locking via Camera2 interop
4. Integrate gyroscope data collection

### Phase 2: Pipeline Updates (MEDIUM PRIORITY)
**Estimated Effort:** 2-3 hours

1. Add Bitmap processing path to `UltraDetailPipeline`
2. Update native processors for Bitmap input
3. Ensure backward compatibility with existing YUV path

### Phase 3: UI/UX Updates (LOW PRIORITY)
**Estimated Effort:** 1-2 hours

1. Update capture feedback (shutter sound, flash)
2. Add capture progress for sequential shots
3. Update estimated time calculations

### Phase 4: Testing & Optimization (REQUIRED)
**Estimated Effort:** 2-3 hours

1. Test all presets (FAST, BALANCED, MAX, ULTRA)
2. Verify quality improvement
3. Optimize memory usage
4. Performance profiling

---

## Detailed Implementation: Phase 1

### New BurstCaptureController Architecture

```kotlin
class HighQualityBurstCapture(
    private val context: Context,
    private val config: BurstCaptureConfig
) : SensorEventListener {
    
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    
    // Setup with ImageCapture instead of ImageAnalysis
    fun setup(
        cameraProvider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        cameraSelector: CameraSelector,
        preview: Preview?
    ) {
        // Configure for maximum quality
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetResolution(config.targetResolution)
            .build()
        
        // Bind to lifecycle
        camera = cameraProvider.bindToLifecycle(
            lifecycleOwner,
            cameraSelector,
            preview,
            imageCapture
        )
    }
    
    // Capture burst using sequential takePicture calls
    suspend fun captureHighQualityBurst(): List<HighQualityCapturedFrame> {
        val frames = mutableListOf<HighQualityCapturedFrame>()
        
        // Lock exposure before burst
        lockExposureManual()
        startGyroRecording()
        
        repeat(config.frameCount) { index ->
            val frame = captureOneFrame()
            frames.add(frame)
            
            // Delay between frames for hand movement
            if (index < config.frameCount - 1) {
                delay(config.frameIntervalMs)
            }
        }
        
        stopGyroRecording()
        unlockExposure()
        
        return frames
    }
    
    private suspend fun captureOneFrame(): HighQualityCapturedFrame {
        return suspendCancellableCoroutine { continuation ->
            val outputOptions = ImageCapture.OutputFileOptions.Builder(/* temp file */).build()
            
            imageCapture?.takePicture(
                outputOptions,
                executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        // Load bitmap from saved file
                        val bitmap = BitmapFactory.decodeFile(/* path */)
                        val frame = HighQualityCapturedFrame(
                            bitmap = bitmap,
                            timestamp = System.nanoTime(),
                            gyroSamples = getGyroSamplesForFrame(timestamp)
                        )
                        continuation.resume(frame)
                    }
                    
                    override fun onError(exception: ImageCaptureException) {
                        continuation.resumeWithException(exception)
                    }
                }
            )
        }
    }
    
    // Use Camera2 interop for manual exposure control
    private suspend fun lockExposureManual() {
        Camera2CameraControl.from(camera!!.cameraControl).apply {
            // Lock AE
            captureRequestOptions = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_LOCK, 
                    true
                )
                .build()
        }
    }
}
```

---

## Memory Considerations

### Current Memory Usage (10 frames @ 12MP)
- YUV frames: ~18MB each × 10 = ~180MB
- Processing buffers: ~100MB
- **Total: ~280MB**

### New Memory Usage (10 frames @ 12MP ARGB)
- ARGB bitmaps: ~48MB each × 10 = ~480MB
- Processing buffers: ~100MB
- **Total: ~580MB**

### Mitigation Strategies:
1. **Stream processing**: Process frames as they're captured, don't hold all in memory
2. **Disk caching**: Save frames to temp files, load on demand
3. **Downscale option**: For lower-end devices, capture at reduced resolution
4. **Progressive cleanup**: Recycle frames after alignment is computed

---

## Risk Assessment

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Memory OOM on low-end devices | Medium | High | Add device capability check, fallback to current method |
| Capture latency too high | Medium | Medium | Optimize file I/O, use in-memory capture where possible |
| Camera2 interop issues | Low | High | Test on multiple devices, add fallback |
| Breaking existing functionality | Medium | High | Maintain backward compatibility, feature flag |

---

## Testing Checklist

### Functional Tests
- [ ] FAST preset captures 6 frames correctly
- [ ] BALANCED preset captures 8 frames correctly
- [ ] MAX preset captures 12 frames correctly
- [ ] ULTRA preset captures 10 frames with MFSR
- [ ] Gyro data correctly associated with frames
- [ ] Exposure consistent across burst
- [ ] Output quality visibly improved

### Performance Tests
- [ ] Capture time within expected bounds
- [ ] Processing time acceptable
- [ ] Memory usage under 600MB peak
- [ ] No ANR during capture/processing

### Device Compatibility
- [ ] Test on low-end device (2GB RAM)
- [ ] Test on mid-range device (4GB RAM)
- [ ] Test on high-end device (8GB+ RAM)
- [ ] Test on devices with/without Camera2 full support

---

## Rollback Plan

If the new architecture causes issues:
1. Feature flag to switch between old/new capture
2. Keep existing `BurstCaptureController` as fallback
3. Add device capability detection to auto-select method

---

## Timeline Estimate

| Phase | Duration | Dependencies |
|-------|----------|--------------|
| Phase 1: Capture | 3-4 hours | None |
| Phase 2: Pipeline | 2-3 hours | Phase 1 |
| Phase 3: UI/UX | 1-2 hours | Phase 2 |
| Phase 4: Testing | 2-3 hours | Phase 3 |
| **Total** | **8-12 hours** | |

---

## Next Steps

1. **Approve this plan** - Review and confirm approach
2. **Start Phase 1** - Implement new capture mechanism
3. **Incremental testing** - Test each phase before proceeding
4. **Quality validation** - Compare output quality old vs new

---

*Document created: December 15, 2024*
*Status: PENDING APPROVAL*
