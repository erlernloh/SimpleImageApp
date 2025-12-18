# Photara MVP Task List

## Overview
This document contains a comprehensive task list to fix all identified issues and make the app MVP-ready. Tasks are organized by priority (P0 = MVP blockers, P1 = Quality/Correctness, P2 = Maintainability, P3 = Polish).

---

## P0: MVP Blockers (Must Fix Before Release)

### P0-1: Fix Healing Tool Coordinate Mapping
**Status:** Not Started  
**Effort:** Medium  
**Files:**
- `app/src/main/java/com/imagedit/app/ui/editor/PhotoEditorScreen.kt`
- `app/src/main/java/com/imagedit/app/ui/editor/PhotoEditorViewModel.kt`

**Problem:** The healing overlay sends raw screen coordinates to the ViewModel without mapping to bitmap coordinates. Under `ContentScale.Fit`, the displayed image may be letterboxed/pillarboxed, causing healed regions to not match where the user painted.

**Tasks:**
- [ ] Calculate `contentRect` for the healing overlay (same approach used for crop)
- [ ] Map touch coordinates from overlay space → normalized image space → bitmap pixel coordinates
- [ ] Account for any rotation/transforms applied to the preview
- [ ] Add unit tests for coordinate mapping edge cases
- [ ] Test on various aspect ratios (16:9, 4:3, 1:1 images on different screen sizes)

---

### P0-2: Implement Landscape Analysis Properly
**Status:** Not Started  
**Effort:** Medium  
**Files:**
- `app/src/main/java/com/imagedit/app/ui/editor/PhotoEditorViewModel.kt`
- `app/src/main/java/com/imagedit/app/data/repository/EnhancedImageProcessor.kt`

**Problem:** `analyzeLandscapeElements()` is explicitly a placeholder that sets `landscapeAnalysis = null`. The UI shows an analysis panel but it will never have data.

**Tasks:**
- [ ] Option A: Inject `LandscapeDetector` into `PhotoEditorViewModel` and implement proper analysis
- [ ] Option B: Route analysis through `SmartProcessor.analyzeLandscape()` interface method
- [ ] Option C: Remove the analysis UI panel and keep only manual sliders (simplest for MVP)
- [ ] Update `LandscapeEnhancementCard` to handle null analysis gracefully
- [ ] Test landscape enhancement with real landscape photos

---

### P0-3: Wire Accessibility Features or Remove Stubs
**Status:** Not Started  
**Effort:** Low-Medium  
**Files:**
- `app/src/main/java/com/imagedit/app/ui/accessibility/AccessibilityEnhancedPhotoEditor.kt`
- `app/src/main/java/com/imagedit/app/ui/accessibility/VoiceControlSupport.kt`
- `app/src/main/java/com/imagedit/app/navigation/AppNavigation.kt`

**Problem:** `AccessibilityEnhancedPhotoEditor` wrapper exists but is not used in the actual navigation. Voice command `ApplyHealing` doesn't call `viewModel.applyHealing()`. Landscape voice command is commented out.

**Tasks:**
- [ ] Option A: Wrap `PhotoEditorScreen` with `AccessibilityEnhancedPhotoEditor` in navigation
- [ ] Option B: Remove accessibility files from MVP scope (simpler)
- [ ] If keeping: Fix `ApplyHealing` voice command to call `viewModel.applyHealing()`
- [ ] If keeping: Uncomment and implement landscape voice command
- [ ] If keeping: Add RECORD_AUDIO permission handling for voice control

---

## P1: Quality & Correctness

### P1-1: Fix Repository Casting (Leaky Abstraction)
**Status:** Not Started  
**Effort:** Low  
**Files:**
- `app/src/main/java/com/imagedit/app/ui/gallery/GalleryViewModel.kt`
- `app/src/main/java/com/imagedit/app/domain/repository/PhotoRepository.kt`

**Problem:** `GalleryViewModel` casts `photoRepository as PhotoRepositoryImpl` to access pagination methods, breaking abstraction.

**Tasks:**
- [ ] Add `loadNextPage()`, `refreshPhotos()`, `hasMorePhotos()` to `PhotoRepository` interface
- [ ] Remove cast in `GalleryViewModel`
- [ ] Ensure all pagination logic is accessible through the interface

---

### P1-2: Add Batch Editing Memory Safety
**Status:** Not Started  
**Effort:** Low  
**Files:**
- `app/src/main/java/com/imagedit/app/ui/batch/BatchEditorViewModel.kt`

**Problem:** Batch editor loads images with `maxWidth = Int.MAX_VALUE`, which can cause OOM when processing many large photos.

**Tasks:**
- [ ] Add a reasonable max dimension cap (e.g., 2048px) for batch preview
- [ ] Process full resolution only during save, one image at a time
- [ ] Add memory pressure monitoring before loading each image
- [ ] Show warning if user selects too many large images

---

### P1-3: Remove Deprecated MediaStore.DATA Usage
**Status:** Not Started  
**Effort:** Low  
**Files:**
- `app/src/main/java/com/imagedit/app/ui/viewer/PhotoViewerScreen.kt`

**Problem:** Uses `MediaStore.Images.Media.DATA` which is deprecated and often inaccessible on Android 10+.

**Tasks:**
- [ ] Remove "Path" display or show URI instead
- [ ] Use `DISPLAY_NAME` and `RELATIVE_PATH` for user-friendly location info
- [ ] Test on Android 10+ devices with scoped storage

---

### P1-4: Complete Gallery Share Implementation
**Status:** Not Started  
**Effort:** Low  
**Files:**
- `app/src/main/java/com/imagedit/app/ui/gallery/GalleryScreen.kt`
- `app/src/main/java/com/imagedit/app/ui/gallery/GalleryViewModel.kt`

**Problem:** Bulk share action has placeholder comment "Share intent will be handled here".

**Tasks:**
- [ ] Implement `shareSelectedPhotos()` to create proper share intent
- [ ] Use `FileProvider` for sharing URIs on Android 7+
- [ ] Test sharing to common apps (Messages, Email, WhatsApp)

---

## P2: Maintainability & Performance

### P2-1: Unify Image Processor Implementations
**Status:** Not Started  
**Effort:** Medium  
**Files:**
- `app/src/main/java/com/imagedit/app/data/repository/EnhancedImageProcessor.kt`
- `app/src/main/java/com/imagedit/app/data/repository/ImageProcessorImpl.kt`
- `app/src/main/java/com/imagedit/app/di/RepositoryModule.kt`

**Problem:** Two `ImageProcessor` implementations exist. `ImageProcessorImpl` has caching/pooling but isn't bound. `EnhancedImageProcessor` is bound but lacks those optimizations.

**Tasks:**
- [ ] Option A: Delete `ImageProcessorImpl` if not needed
- [ ] Option B: Merge caching/pooling from `ImageProcessorImpl` into `EnhancedImageProcessor`
- [ ] Ensure only one implementation is maintained
- [ ] Update DI bindings accordingly

---

### P2-2: Remove Unused Glide Dependency
**Status:** Not Started  
**Effort:** Low  
**Files:**
- `app/build.gradle.kts`

**Problem:** Glide is in dependencies but Coil is used throughout the app.

**Tasks:**
- [ ] Verify no Glide imports exist in codebase
- [ ] Remove Glide dependency from `build.gradle.kts`
- [ ] Rebuild and test image loading still works

---

### P2-3: Migrate Settings to DataStore
**Status:** Not Started  
**Effort:** Low-Medium  
**Files:**
- `app/src/main/java/com/imagedit/app/data/repository/SettingsRepository.kt`

**Problem:** Settings use SharedPreferences while other persistence uses DataStore, creating inconsistency.

**Tasks:**
- [ ] Create DataStore preferences for settings
- [ ] Migrate existing SharedPreferences data on first launch
- [ ] Update `SettingsRepository` to use DataStore
- [ ] Remove SharedPreferences code

---

### P2-4: Consolidate Smart Enhancement Methods
**Status:** Not Started  
**Effort:** Low  
**Files:**
- `app/src/main/java/com/imagedit/app/ui/editor/PhotoEditorViewModel.kt`

**Problem:** Multiple smart enhancement methods exist: `applySmartEnhancement()`, `applySmartEnhancementWithCancellation()`, `applySmartEnhancementWithMode()`.

**Tasks:**
- [ ] Consolidate into a single method with optional parameters
- [ ] Ensure UI button uses the consolidated method
- [ ] Remove duplicate code paths

---

## P3: Polish & UX

### P3-1: Convert Healing Strokes to Visual Paths
**Status:** Not Started  
**Effort:** Low  
**Files:**
- `app/src/main/java/com/imagedit/app/ui/editor/PhotoEditorScreen.kt`

**Problem:** TODOs exist to convert brush strokes to paths for visual feedback.

**Tasks:**
- [ ] Implement `strokePath` and `currentStrokePath` conversion
- [ ] Show real-time stroke preview as user paints
- [ ] Add stroke color/opacity customization

---

### P3-2: Add Processing Completion Notification
**Status:** Not Started  
**Effort:** Low  
**Files:**
- `app/src/main/java/com/imagedit/app/ultradetail/ProcessingService.kt`
- `app/src/main/java/com/imagedit/app/ultradetail/UltraDetailViewModel.kt`

**Problem:** When UltraDetail+ processing completes in background, user may not notice.

**Tasks:**
- [ ] Add completion notification with "View Result" action
- [ ] Play completion sound (optional, respect system settings)
- [ ] Update notification to show "Complete!" with thumbnail

---

---

# UltraDetail+ Enhancement Tasks

## Overview
UltraDetail+ is the flagship differentiator feature. These tasks aim to make it a truly unique offering that produces genuinely higher resolution images from burst captures.

---

## UD-1: Implement True Background Processing with WorkManager
**Status:** Not Started  
**Effort:** High  
**Files:**
- `app/src/main/java/com/imagedit/app/ultradetail/UltraDetailViewModel.kt`
- `app/src/main/java/com/imagedit/app/ultradetail/ProcessingService.kt`
- NEW: `app/src/main/java/com/imagedit/app/ultradetail/UltraDetailWorker.kt`

**Problem:** Current implementation uses foreground service but processing still blocks the ViewModel scope. If user navigates away, processing may be interrupted.

**Tasks:**
- [ ] Create `UltraDetailWorker` extending `CoroutineWorker`
- [ ] Move heavy processing to WorkManager with `LONG_RUNNING` expedited work
- [ ] Implement chunked processing with checkpoints (save intermediate state)
- [ ] Allow user to queue multiple captures for background processing
- [ ] Show persistent notification with progress
- [ ] Handle app restart - resume from checkpoint
- [ ] Implement proper cancellation with cleanup

**Implementation Notes:**
```kotlin
// UltraDetailWorker.kt structure
class UltraDetailWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        // 1. Load frames from temp storage
        // 2. Process in chunks with setProgress() updates
        // 3. Save checkpoints every N tiles
        // 4. On completion, save to gallery and show notification
    }
    
    override suspend fun getForegroundInfo(): ForegroundInfo {
        // Return notification for foreground service
    }
}
```

---

## UD-2: Implement CPU/GPU Throttling for Background Processing
**Status:** Not Started  
**Effort:** Medium  
**Files:**
- `app/src/main/java/com/imagedit/app/ultradetail/UltraDetailPipeline.kt`
- `app/src/main/java/com/imagedit/app/ultradetail/MFSRPipeline.kt`
- `app/src/main/java/com/imagedit/app/ultradetail/MFSRRefiner.kt`

**Problem:** Processing uses full CPU/GPU which drains battery and heats device.

**Tasks:**
- [ ] Add `ProcessingPriority` enum: `FOREGROUND` (full speed), `BACKGROUND` (throttled)
- [ ] In background mode:
  - [ ] Add `yield()` calls between tiles to allow other work
  - [ ] Add configurable delay between tiles (e.g., 50-100ms)
  - [ ] Reduce GPU thread count for TFLite inference
  - [ ] Monitor battery temperature and pause if too hot
- [ ] Respect battery saver mode - pause or reduce quality
- [ ] Add user setting for background processing priority

**Implementation Notes:**
```kotlin
// Throttled tile processing
suspend fun processTileThrottled(tile: Tile, priority: ProcessingPriority) {
    val result = processTile(tile)
    
    if (priority == ProcessingPriority.BACKGROUND) {
        delay(50) // Allow system breathing room
        yield()   // Let other coroutines run
    }
    
    return result
}
```

---

## UD-3: Add Visual Progress UI with Stage Breakdown
**Status:** Not Started  
**Effort:** Medium  
**Files:**
- `app/src/main/java/com/imagedit/app/ui/ultradetail/UltraDetailScreen.kt`
- `app/src/main/java/com/imagedit/app/ultradetail/UltraDetailViewModel.kt`

**Problem:** Current progress is a single bar. Users don't understand what's happening during the 60-90 second processing.

**Tasks:**
- [ ] Create multi-stage progress UI:
  - [ ] Stage 1: "Capturing burst" (8 frames) - circular progress
  - [ ] Stage 2: "Analyzing motion" - gyro alignment visualization
  - [ ] Stage 3: "Aligning frames" - show frame thumbnails aligning
  - [ ] Stage 4: "Super-resolution" - tile grid showing completion
  - [ ] Stage 5: "Neural refinement" - before/after preview
- [ ] Show estimated time remaining per stage
- [ ] Add "Processing in background" button to minimize
- [ ] Show live preview that updates as tiles complete

**UI Mockup:**
```
┌─────────────────────────────────────┐
│  Ultra Detail+ Processing           │
├─────────────────────────────────────┤
│  ✓ Captured 8 frames               │
│  ✓ Motion analysis complete        │
│  ● Super-resolution: 45/120 tiles  │
│    [████████░░░░░░░░] 37%          │
│  ○ Neural refinement               │
├─────────────────────────────────────┤
│  Est. remaining: 2m 15s            │
│                                     │
│  [Live Preview Area]               │
│  (tiles fill in as completed)      │
│                                     │
├─────────────────────────────────────┤
│  [Continue in Background] [Cancel] │
└─────────────────────────────────────┘
```

---

## UD-4: Implement User-Adjustable Denoising
**Status:** Not Started  
**Effort:** Medium  
**Files:**
- `app/src/main/java/com/imagedit/app/ultradetail/UltraDetailViewModel.kt`
- `app/src/main/java/com/imagedit/app/ui/ultradetail/UltraDetailScreen.kt`
- NEW: `app/src/main/java/com/imagedit/app/ultradetail/DenoiseProcessor.kt`

**Problem:** MFSR output may have higher noise (acceptable per requirements), but users need control over noise/detail tradeoff.

**Tasks:**
- [ ] Create `DenoiseProcessor` with adjustable strength (0-100%)
- [ ] Implement multiple denoising algorithms:
  - [ ] Bilateral filter (fast, preserves edges)
  - [ ] Non-local means (slower, better quality)
  - [ ] Optional: TFLite denoising model
- [ ] Add post-processing UI after MFSR completes:
  - [ ] Denoise strength slider (0 = raw MFSR, 100 = max denoise)
  - [ ] Detail preservation slider
  - [ ] Real-time preview (on downscaled image)
- [ ] Apply final denoise settings when saving
- [ ] Save user's preferred denoise level as default

**UI Addition:**
```
┌─────────────────────────────────────┐
│  Post-Processing                    │
├─────────────────────────────────────┤
│  Noise Reduction                    │
│  [░░░░░░░░░░░░░░░░░░░░] 0%         │
│  (More detail ← → Less noise)      │
│                                     │
│  Detail Preservation                │
│  [████████████░░░░░░░░] 60%        │
│                                     │
│  [Preview] [Reset] [Apply & Save]  │
└─────────────────────────────────────┘
```

---

## UD-5: Ensure True Super-Resolution Output Quality
**Status:** Not Started  
**Effort:** High  
**Files:**
- `app/src/main/java/com/imagedit/app/ultradetail/MFSRPipeline.kt`
- `app/src/main/java/com/imagedit/app/ultradetail/NativeMFSRPipeline.kt`
- Native C++ code in `app/src/main/cpp/`

**Problem:** Need to verify that output is genuinely higher resolution with real detail recovery, not just bicubic upscale.

**Tasks:**
- [ ] Add quality metrics to validate MFSR effectiveness:
  - [ ] Compare PSNR/SSIM of MFSR output vs bicubic upscale
  - [ ] Measure edge sharpness improvement
  - [ ] Log metrics for debugging
- [ ] Improve sub-pixel alignment accuracy:
  - [ ] Use gyro data for initial homography estimation
  - [ ] Refine with optical flow at multiple pyramid levels
  - [ ] Reject frames with excessive motion blur
- [ ] Implement robust pixel accumulation:
  - [ ] Use Tukey M-estimator for outlier rejection
  - [ ] Weight contributions by alignment confidence
  - [ ] Handle occlusions and moving objects
- [ ] Add "quality indicator" to result:
  - [ ] Show percentage of pixels with multi-frame contribution
  - [ ] Warn if too much motion detected
  - [ ] Suggest retaking if quality is low

**Validation Approach:**
```kotlin
// Add to result metadata
data class MFSRQualityMetrics(
    val avgSubpixelContributions: Float,  // How many frames contributed per output pixel
    val alignmentConfidence: Float,        // 0-1, how well frames aligned
    val detailRecoveryScore: Float,        // Comparison vs bicubic baseline
    val motionBlurRejectedFrames: Int      // Frames excluded due to blur
)
```

---

## UD-6: Add Completion Notification with Preview
**Status:** Not Started  
**Effort:** Low  
**Files:**
- `app/src/main/java/com/imagedit/app/ultradetail/ProcessingService.kt`
- `app/src/main/java/com/imagedit/app/ultradetail/UltraDetailWorker.kt` (new)

**Problem:** When processing completes in background, user needs clear notification.

**Tasks:**
- [ ] Create rich notification with:
  - [ ] Large icon showing result thumbnail
  - [ ] "View Result" action button
  - [ ] "Share" action button
  - [ ] Processing stats (time, resolution)
- [ ] Play completion sound (respect Do Not Disturb)
- [ ] Add vibration pattern for completion
- [ ] Deep link to result viewer when tapped

**Notification Example:**
```
┌─────────────────────────────────────┐
│ 📷 Ultra Detail+ Complete           │
│ 4000x3000 → 8000x6000 (4x pixels)  │
│ Processing time: 1m 23s            │
│                                     │
│ [View Result]  [Share]  [Dismiss]  │
└─────────────────────────────────────┘
```

---

## UD-7: Implement Frame Quality Selection
**Status:** Not Started  
**Effort:** Medium  
**Files:**
- `app/src/main/java/com/imagedit/app/ultradetail/BurstCaptureController.kt`
- `app/src/main/java/com/imagedit/app/ultradetail/UltraDetailPipeline.kt`

**Problem:** Currently uses all captured frames. Some frames may have motion blur or poor alignment.

**Tasks:**
- [ ] Analyze each frame for:
  - [ ] Motion blur (Laplacian variance)
  - [ ] Exposure consistency
  - [ ] Gyro rotation magnitude during exposure
- [ ] Rank frames by quality score
- [ ] Auto-select best N frames (configurable, default 6-8)
- [ ] Show frame quality in UI (optional advanced view)
- [ ] Allow manual frame exclusion (advanced users)

---

## UD-8: Add Capture Guidance UI
**Status:** Not Started  
**Effort:** Medium  
**Files:**
- `app/src/main/java/com/imagedit/app/ui/ultradetail/UltraDetailScreen.kt`

**Problem:** Users may not understand how to hold phone steady for best results.

**Tasks:**
- [ ] Add real-time stability indicator during capture:
  - [ ] Gyro-based "steadiness meter"
  - [ ] Green/yellow/red indicator
  - [ ] "Hold steady..." prompt
- [ ] Show capture countdown with visual feedback
- [ ] Add haptic feedback when capture starts/ends
- [ ] Show "Tips for best results" on first use:
  - [ ] Use tripod or brace against surface
  - [ ] Avoid moving subjects
  - [ ] Good lighting helps
- [ ] Abort capture if excessive motion detected

---

## UD-9: Implement Incremental Result Preview
**Status:** Not Started  
**Effort:** High  
**Files:**
- `app/src/main/java/com/imagedit/app/ultradetail/UltraDetailPipeline.kt`
- `app/src/main/java/com/imagedit/app/ui/ultradetail/UltraDetailScreen.kt`

**Problem:** User waits 60-90 seconds with no visual feedback of the actual result.

**Tasks:**
- [ ] Generate low-res preview after alignment (first 5 seconds)
- [ ] Update preview as tiles complete:
  - [ ] Start with bicubic upscale as placeholder
  - [ ] Replace tiles with MFSR output as they complete
  - [ ] Smooth transition animation
- [ ] Allow pinch-to-zoom on preview to see detail
- [ ] Show side-by-side comparison: "Original" vs "Enhanced"

---

## UD-10: Optimize Memory Usage for Large Images
**Status:** Not Started  
**Effort:** Medium  
**Files:**
- `app/src/main/java/com/imagedit/app/ultradetail/BurstCaptureController.kt`
- `app/src/main/java/com/imagedit/app/ultradetail/UltraDetailPipeline.kt`

**Problem:** 8 frames at 12MP = ~360MB RAM just for input. Output at 2x is another ~180MB.

**Tasks:**
- [ ] Implement streaming tile processing:
  - [ ] Don't load all frames into memory at once
  - [ ] Load frame regions on-demand per tile
  - [ ] Release frame data after tile is processed
- [ ] Use memory-mapped files for large buffers
- [ ] Add aggressive GC hints between processing stages
- [ ] Monitor memory and reduce quality if low:
  - [ ] Reduce tile count
  - [ ] Skip refinement stage
  - [ ] Warn user of reduced quality

---

# Testing Tasks

## T-1: Add Integration Tests for Core Flows
**Status:** Not Started  
**Effort:** High  

**Tasks:**
- [ ] Camera capture → save flow
- [ ] Gallery load → edit → save flow
- [ ] Export with EXIF preservation
- [ ] Crop coordinate mapping
- [ ] Healing coordinate mapping
- [ ] Undo/redo stack integrity

---

## T-2: Add UltraDetail+ Quality Validation Tests
**Status:** Not Started  
**Effort:** Medium  

**Tasks:**
- [ ] Test with synthetic burst (known ground truth)
- [ ] Measure PSNR improvement over bicubic
- [ ] Test with real device captures
- [ ] Benchmark processing time on various devices
- [ ] Memory usage profiling

---

## T-3: Add UI Tests for Editor
**Status:** Not Started  
**Effort:** Medium  

**Tasks:**
- [ ] Test all adjustment sliders
- [ ] Test preset application
- [ ] Test crop workflow
- [ ] Test healing workflow
- [ ] Test save/export

---

# Release Checklist

## Pre-Release
- [ ] All P0 tasks complete
- [ ] All P1 tasks complete (or documented as known issues)
- [ ] App tested on min SDK device (API 24)
- [ ] App tested on target SDK device (API 34)
- [ ] ProGuard/R8 rules verified
- [ ] Crash reporting integrated (Firebase Crashlytics or similar)
- [ ] Privacy policy updated for camera/storage permissions
- [ ] Play Store listing prepared

## Performance Targets
- [ ] Cold start < 2 seconds
- [ ] Gallery load < 500ms for first 15 photos
- [ ] Editor preview update < 100ms
- [ ] Smart enhancement < 5 seconds (MEDIUM mode)
- [ ] UltraDetail+ ULTRA < 120 seconds

## Quality Gates
- [ ] No ANRs in testing
- [ ] No crashes in 100 test sessions
- [ ] Memory usage < 512MB peak
- [ ] Battery drain < 5% for typical edit session

---

*Last Updated: December 2024*
*Version: 1.0-MVP*
