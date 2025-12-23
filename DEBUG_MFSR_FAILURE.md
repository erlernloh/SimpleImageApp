# MFSR Processing Failure Debug Guide

## Problem
User reports "MFSR processing failed" message with no logcat output except:
```
---------------------------- PROCESS STARTED (20265) for package com.imagedit.app ----------------------------
```

## Diagnostic Logging Added

### ViewModel Layer (`UltraDetailViewModel.kt`)
Added comprehensive logging to track execution flow:

1. **`startCapture()`**
   - Logs preset, quality mode, and RAW decision
   - Tracks which capture path is taken

2. **`processHighQualityCapture()`**
   - Entry point logging with preset
   - HQ capture start/complete
   - Pipeline initialization check
   - Processing start with timeout value
   - Result handling

3. **`processRawCapture()`**
   - RAW capability checks
   - Fallback triggers

### Pipeline Layer (`UltraDetailPipeline.kt`)
Added detailed logging:

1. **`processHighQuality()`**
   - Entry point with frame count and preset
   - Pipeline initialization status
   - Frame dimension extraction

2. **`processHQWithMFSR()`**
   - Entry point with all parameters
   - Pipeline null check
   - Frame dimensions

## Expected Log Sequence for Normal Operation

```
I/UltraDetailViewModel: startCapture: preset=BALANCED, useHighQuality=true, shouldUseRaw=false
I/UltraDetailViewModel: startCapture: Using standard capture path (highQuality=true)
I/UltraDetailViewModel: processHighQualityCapture: Starting HQ capture for preset=BALANCED
D/UltraDetailViewModel: processHighQualityCapture: Calling startHighQualityCapture
I/UltraDetailViewModel: processHighQualityCapture: HQ Capture complete: X frames
I/UltraDetailViewModel: processHighQualityCapture: Initializing pipeline for preset=BALANCED
I/UltraDetailViewModel: processHighQualityCapture: Starting pipeline processing (timeout=XXXms)
I/UltraDetailPipeline: processHighQuality: ENTRY - frames.size=X, preset=BALANCED
D/UltraDetailPipeline: processHighQuality: Checking mfsrPipeline initialization
I/UltraDetailPipeline: processHighQuality: Processing X HQ frames (WxH) with preset BALANCED
I/UltraDetailPipeline: processHQWithMFSR: ENTRY - frames=X, refIndex=Y, preset=BALANCED
I/UltraDetailPipeline: processHQWithMFSR: Frame dimensions: WxH
```

## Possible Failure Points

### 1. **App Crash Before Logging**
- Native library load failure
- JNI initialization crash
- Out of memory during startup

**Check**: Look for crash logs in Android Studio Logcat with filter "AndroidRuntime"

### 2. **Silent Exception in Coroutine**
- Uncaught exception in viewModelScope
- Exception handler swallowing errors

**Check**: Look for any exception logs with TAG "UltraDetailViewModel" or "UltraDetailPipeline"

### 3. **Pipeline Initialization Failure**
- Native library not loaded
- MFSR pipeline creation failed

**Check**: Look for "Failed to initialize pipeline" or "mfsrPipeline is NULL"

### 4. **Capture Never Starts**
- BurstController not properly initialized
- Camera permission issues
- CameraX initialization failure

**Check**: Look for "startCapture: Already capturing" or camera-related errors

### 5. **Memory Pressure**
- OOM before processing starts
- System killing app due to memory

**Check**: Look for "OutOfMemoryError" or memory warnings

## Debug Steps

1. **Clear logcat and start fresh**
   ```bash
   adb logcat -c
   adb logcat | grep -E "UltraDetail|MFSR|AndroidRuntime"
   ```

2. **Check for native crashes**
   ```bash
   adb logcat | grep -E "FATAL|DEBUG|tombstone"
   ```

3. **Monitor memory**
   ```bash
   adb shell dumpsys meminfo com.imagedit.app
   ```

4. **Check if app is actually starting**
   ```bash
   adb logcat | grep "UltraDetailViewModel: initialize"
   ```

5. **Verify native library loads**
   ```bash
   adb logcat | grep "System.loadLibrary"
   ```

## Quick Test

Run the app and immediately check if you see:
- `UltraDetailViewModel: initialize` - App started
- `UltraDetailViewModel: ViewModel initialized` - ViewModel ready
- `UltraDetailViewModel: startCapture:` - Capture triggered

If none of these appear, the app is crashing before ViewModel initialization.

## Next Steps Based on Findings

### If no logs at all:
- App is crashing during startup
- Check native library compatibility
- Check for missing dependencies

### If logs stop at "startCapture":
- BurstController issue
- Camera initialization problem

### If logs stop at "processHighQualityCapture":
- Frame capture failing
- Memory issue during capture

### If logs stop at "processHighQuality":
- Pipeline initialization failing
- Native code crash

### If "MFSR processing failed" appears:
- Check native return code in logs
- Look for "Native MFSR completed in Xms (result=Y)"
- Negative result code indicates specific native error
