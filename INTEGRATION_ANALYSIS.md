# RAW Capture Integration Analysis

## CRITICAL FINDINGS

### 1. RAW Capture Path is DISCONNECTED from Processing Pipeline

**Location**: `UltraDetailViewModel.kt:416-420`

```kotlin
// TODO: Process raw-cache files through native pipeline
// For now, just show DNGs saved message
_uiState.value = _uiState.value.copy(
    statusMessage = "RAW burst saved (${result.dngUris.size} DNGs). Processing coming soon...",
    error = null
)
```

**Issue**: RAW capture saves DNGs and raw-cache files but NEVER processes them through the MFSR pipeline.

### 2. RAW is Disabled by Default

**Location**: `UltraDetailViewModel.kt:81`

```kotlin
val rawCaptureEnabled: Boolean = false,  // User preference to use RAW when available
```

**Result**: Standard YUV/Bitmap capture path is being used, NOT the RAW path.

### 3. Standard Capture Path is What's Failing

The "MFSR processing failed" error is coming from the **standard capture path**, not RAW.

## Connection Flow Analysis

### UI → ViewModel Connection ✅
```
UltraDetailScreen.kt:332
  → viewModel.startCapture(controller)
    → UltraDetailViewModel.startCapture(burstController)
```
**Status**: CONNECTED

### ViewModel → BurstController Connection ✅
```
UltraDetailViewModel.kt:323
  this.burstController = burstController
```
**Status**: CONNECTED

### BurstController → Pipeline Connection ✅
```
UltraDetailViewModel.kt:357
  processHighQualityCapture(burstController, preset)
    → burstController.startHighQualityCapture(viewModelScope)
      → activePipeline.processHighQuality(hqFrames, preset, viewModelScope)
```
**Status**: CONNECTED

### RAW Capture → Pipeline Connection ❌
```
UltraDetailViewModel.kt:397
  rawBurstController!!.captureRawBurst(preset, deviceCap, frameCount)
    → Saves DNGs and raw-cache files
      → STOPS HERE - NO PROCESSING
```
**Status**: DISCONNECTED

## What's Actually Happening

1. User clicks capture button
2. `rawCaptureEnabled = false` (default)
3. Standard capture path is used: `processHighQualityCapture()`
4. BurstController captures frames via CameraX
5. Pipeline processes frames
6. **Something in the pipeline is failing** → "MFSR processing failed"

## Why No Logcat Output

Possible reasons:
1. **App crashes before logging** - Native library issue
2. **Exception in coroutine** - Swallowed by exception handler
3. **Pipeline initialization fails** - Returns null early
4. **Memory pressure** - OOM kills app
5. **Native code crash** - No Java stack trace

## Required Fixes

### Immediate (To diagnose current failure):
1. ✅ Added comprehensive logging to track execution flow
2. Need to run app and capture full logcat output
3. Identify where standard capture path is failing

### Future (To complete RAW integration):
1. Implement RAW processing in `processRawCapture()`
2. Create native pipeline support for raw-cache files
3. Add disk-backed tile demosaicing for RAW data
4. Connect RAW frames to existing MFSR pipeline

## Next Steps

1. **Run the app with new logging**
2. **Capture full logcat output** from app start to failure
3. **Identify exact failure point** from logs
4. **Fix the actual issue** (likely in standard capture path, not RAW)
5. **Then** complete RAW integration

## Log Markers to Look For

```
I/UltraDetailViewModel: initialize: STARTING ViewModel initialization
I/UltraDetailViewModel: initialize: Pipeline created successfully
I/UltraDetailViewModel: initialize: ViewModel initialized successfully
I/UltraDetailViewModel: startCapture: preset=X, useHighQuality=X, shouldUseRaw=false
I/UltraDetailViewModel: startCapture: Using standard capture path
I/UltraDetailViewModel: processHighQualityCapture: Starting HQ capture
I/UltraDetailPipeline: processHighQuality: ENTRY - frames.size=X
```

If logs stop before "ViewModel initialized successfully" → Initialization failure
If logs stop before "HQ Capture complete" → Capture failure  
If logs stop before "processHighQuality: ENTRY" → Pipeline null or not called
If logs reach pipeline but fail → Native processing issue
