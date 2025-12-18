# Ultra Detail+ Enhancement - Comprehensive Task Breakdown

## Overview
This document outlines all enhancements to improve detail recovery and image quality in the Ultra Detail+ pipeline. Tasks are organized by module with clear dependencies and file mappings.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         CAPTURE LAYER (Kotlin)                               │
├─────────────────────────────────────────────────────────────────────────────┤
│  BurstCaptureController.kt  │  RawCaptureHelper.kt  │  GyroAlignmentHelper.kt│
│  - 12+ frame capture        │  - RAW/DNG support    │  - Gyro data collection│
│  - Diversity validation     │  - Device capability  │  - Rotation integration│
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         ALIGNMENT LAYER (C++)                                │
├─────────────────────────────────────────────────────────────────────────────┤
│  orb_alignment.cpp/h        │  rolling_shutter.cpp/h │  kalman_fusion.cpp/h │
│  - ORB feature detection    │  - CMOS RS correction  │  - Gyro+Flow fusion  │
│  - RANSAC homography        │  - Per-row timing      │  - Kalman filtering  │
├─────────────────────────────────────────────────────────────────────────────┤
│  alignment.cpp/h (existing) │  optical_flow.cpp/h    │  phase_correlation.cpp│
│  - Tile-based SAD           │  - Lucas-Kanade flow   │  - FFT alignment     │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         FUSION LAYER (C++)                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│  drizzle.cpp/h              │  anisotropic_merge.cpp/h │  mfsr.cpp (existing)│
│  - Sub-pixel interlacing    │  - Edge-aware kernels    │  - Shift-and-add SR │
│  - Variable pixel fraction  │  - Directional blending  │  - Lanczos weights  │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         REFINEMENT LAYER (C++ & Kotlin)                      │
├─────────────────────────────────────────────────────────────────────────────┤
│  freq_separation.cpp/h      │  texture_synthesis.cpp/h │  MFSRRefiner.kt     │
│  - Low/high freq split      │  - Perlin noise inject   │  - ESRGAN 4x upscale│
│  - Adaptive sharpening      │  - Flat area detection   │  - CPU refinement   │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         INTEGRATION LAYER                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│  ultradetail_jni.cpp        │  NativeMFSRPipeline.kt   │  UltraDetailPipeline.kt│
│  - JNI bindings for all     │  - Kotlin wrapper        │  - Orchestration     │
│  - Native method exports    │  - Config management     │  - Progress reporting│
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Task Groups

### GROUP A: Capture Enhancements
**Goal**: Capture more information from the sensor

#### A1. Enhanced Burst Capture (12+ frames with diversity)
**File**: `BurstCaptureController.kt`
**Priority**: HIGH
**Dependencies**: None

| Subtask | Description | Status |
|---------|-------------|--------|
| A1.1 | Increase default `frameCount` from 8 to 12 | Pending |
| A1.2 | Add `frameDiversityThreshold` config parameter | Pending |
| A1.3 | Implement real-time diversity check during capture | Pending |
| A1.4 | Add user prompt for "slight movement" when diversity low | Pending |
| A1.5 | Implement adaptive capture interval (30-150ms based on motion) | Pending |
| A1.6 | Add capture quality metrics logging | Pending |

**Config Changes**:
```kotlin
data class BurstCaptureConfig(
    val frameCount: Int = 12,                    // Changed from 8
    val minFrameDiversity: Float = 0.3f,         // NEW: minimum sub-pixel shift
    val adaptiveInterval: Boolean = true,        // NEW: adjust timing based on motion
    val captureIntervalRange: IntRange = 30..150 // NEW: ms range for adaptive
)
```

#### A2. RAW Capture Mode
**File**: `RawCaptureHelper.kt` (enhance existing)
**Priority**: MEDIUM
**Dependencies**: A1

| Subtask | Description | Status |
|---------|-------------|--------|
| A2.1 | Add device RAW capability detection | Pending |
| A2.2 | Implement DNG capture alongside JPEG | Pending |
| A2.3 | Add RAW-to-linear conversion (apply black level, white balance) | Pending |
| A2.4 | Implement Bayer demosaicing bypass for MFSR | Pending |
| A2.5 | Add RAW burst capture mode toggle in UI | Pending |
| A2.6 | Handle devices without RAW support gracefully | Pending |

**New Functions**:
```kotlin
// RawCaptureHelper.kt
fun isRawSupported(): Boolean
fun captureRawBurst(config: RawBurstConfig): List<RawFrame>
fun convertRawToLinear(raw: RawFrame): LinearImage
fun getRawCharacteristics(): RawSensorInfo
```

---

### GROUP B: Alignment Enhancements
**Goal**: More accurate sub-pixel alignment

#### B1. ORB Feature Matching
**Files**: `orb_alignment.cpp`, `orb_alignment.h` (NEW)
**Priority**: HIGH
**Dependencies**: None

| Subtask | Description | Status |
|---------|-------------|--------|
| B1.1 | Implement FAST corner detection with orientation | Pending |
| B1.2 | Implement rotated BRIEF descriptor computation | Pending |
| B1.3 | Implement brute-force matching with ratio test | Pending |
| B1.4 | Implement RANSAC homography estimation | Pending |
| B1.5 | Add fallback to existing SAD alignment if ORB fails | Pending |
| B1.6 | Add JNI bindings for ORB functions | Pending |

**Key Structures**:
```cpp
struct ORBKeypoint { float x, y, angle, response; int octave; };
struct ORBDescriptor { std::bitset<256> bits; };
struct ORBAlignmentResult { HomographyMatrix H; int inliers; bool success; };
```

#### B2. Rolling Shutter Correction
**Files**: `rolling_shutter.cpp`, `rolling_shutter.h` (NEW)
**Priority**: MEDIUM
**Dependencies**: B1, B4

| Subtask | Description | Status |
|---------|-------------|--------|
| B2.1 | Implement per-row motion model | Pending |
| B2.2 | Estimate readout time from sensor metadata | Pending |
| B2.3 | Interpolate gyro data to per-row timestamps | Pending |
| B2.4 | Apply row-wise homography correction | Pending |
| B2.5 | Add RS correction toggle (can be expensive) | Pending |

**Key Structures**:
```cpp
struct RollingShutterParams {
    float readoutTimeMs;      // Total sensor readout time
    bool topToBottom;         // Readout direction
    int imageHeight;          // For per-row interpolation
};
struct RSCorrectedFrame { RGBImage corrected; float correctionQuality; };
```

#### B3. Gyro-Flow Kalman Fusion
**Files**: `kalman_fusion.cpp`, `kalman_fusion.h` (NEW)
**Priority**: MEDIUM
**Dependencies**: B1, existing `optical_flow.cpp`

| Subtask | Description | Status |
|---------|-------------|--------|
| B3.1 | Implement Kalman filter state (position, velocity) | Pending |
| B3.2 | Fuse gyro prediction with optical flow measurement | Pending |
| B3.3 | Handle gyro drift compensation | Pending |
| B3.4 | Output confidence-weighted motion field | Pending |
| B3.5 | Add adaptive noise covariance based on motion magnitude | Pending |

**Key Structures**:
```cpp
struct KalmanState { float x, y, vx, vy; float P[4][4]; };
struct FusedMotion { SubPixelMotion motion; float gyroWeight; float flowWeight; };
```

---

### GROUP C: Fusion Enhancements
**Goal**: Better sub-pixel reconstruction

#### C1. Drizzle Algorithm
**Files**: `drizzle.cpp`, `drizzle.h` (NEW)
**Priority**: HIGH
**Dependencies**: B1 or existing alignment

| Subtask | Description | Status |
|---------|-------------|--------|
| C1.1 | Implement variable pixel fraction (pixfrac) | Pending |
| C1.2 | Implement drop size calculation based on sub-pixel shift | Pending |
| C1.3 | Implement weighted accumulation with drop overlap | Pending |
| C1.4 | Add gap filling for uncovered output pixels | Pending |
| C1.5 | Implement Lanczos kernel for final reconstruction | Pending |
| C1.6 | Add quality map output (coverage per pixel) | Pending |

**Key Structures**:
```cpp
struct DrizzleParams {
    float pixfrac = 0.8f;     // Drop size as fraction of input pixel
    int scaleFactor = 2;       // Output scale
    float dropoffKernel = 1.0f; // Kernel sharpness
};
struct DrizzleResult { RGBImage output; FloatImage coverageMap; float avgCoverage; };
```

#### C2. Edge-Aware Anisotropic Merging
**Files**: `anisotropic_merge.cpp`, `anisotropic_merge.h` (NEW)
**Priority**: HIGH
**Dependencies**: C1 or existing MFSR

| Subtask | Description | Status |
|---------|-------------|--------|
| C2.1 | Compute local gradient orientation per pixel | Pending |
| C2.2 | Build anisotropic kernel aligned to edge direction | Pending |
| C2.3 | Apply directional blending (merge along edges, not across) | Pending |
| C2.4 | Implement adaptive kernel size based on local variance | Pending |
| C2.5 | Add structure tensor computation for robust orientation | Pending |

**Key Structures**:
```cpp
struct AnisotropicKernel {
    float weights[7][7];      // Directional weights
    float angle;              // Dominant edge angle
    float anisotropy;         // 0=isotropic, 1=fully directional
};
struct StructureTensor { float Ixx, Ixy, Iyy; float lambda1, lambda2, angle; };
```

---

### GROUP D: Refinement Enhancements
**Goal**: Add plausible high-frequency details

#### D1. Frequency Separation Refinement
**Files**: `freq_separation.cpp`, `freq_separation.h` (NEW)
**Priority**: HIGH
**Dependencies**: None (can work on any image)

| Subtask | Description | Status |
|---------|-------------|--------|
| D1.1 | Implement Gaussian blur for low-frequency extraction | Pending |
| D1.2 | Compute high-frequency residual | Pending |
| D1.3 | Implement adaptive sharpening on high-frequency | Pending |
| D1.4 | Apply edge mask to prevent halo artifacts | Pending |
| D1.5 | Recombine with adjustable strength | Pending |
| D1.6 | Add JNI bindings | Pending |

**Key Structures**:
```cpp
struct FreqSeparationParams {
    float lowPassSigma = 2.0f;    // Gaussian sigma for low-freq
    float highBoost = 1.5f;        // High-freq amplification
    float edgeProtection = 0.8f;   // Reduce boost near strong edges
};
struct FreqComponents { FloatImage lowFreq; FloatImage highFreq; FloatImage edgeMask; };
```

#### D2. Texture Noise Injection
**Files**: `texture_synthesis.cpp`, `texture_synthesis.h` (NEW)
**Priority**: MEDIUM
**Dependencies**: D1

| Subtask | Description | Status |
|---------|-------------|--------|
| D2.1 | Implement Perlin noise generator | Pending |
| D2.2 | Detect flat/smooth regions (low local variance) | Pending |
| D2.3 | Generate film-grain-like noise texture | Pending |
| D2.4 | Blend noise only in flat regions | Pending |
| D2.5 | Match noise characteristics to image ISO/exposure | Pending |

**Key Structures**:
```cpp
struct TextureNoiseParams {
    float noiseScale = 0.02f;      // Noise amplitude (0-1)
    float flatThreshold = 10.0f;   // Variance threshold for "flat"
    int noiseOctaves = 3;          // Perlin noise octaves
    float persistence = 0.5f;      // Octave amplitude falloff
};
```

---

### GROUP E: Integration

#### E1. JNI Bindings Update
**File**: `ultradetail_jni.cpp` (modify existing)
**Priority**: HIGH
**Dependencies**: All C++ modules

| Subtask | Description | Status |
|---------|-------------|--------|
| E1.1 | Add JNI exports for ORB alignment | Pending |
| E1.2 | Add JNI exports for Drizzle | Pending |
| E1.3 | Add JNI exports for rolling shutter correction | Pending |
| E1.4 | Add JNI exports for anisotropic merge | Pending |
| E1.5 | Add JNI exports for frequency separation | Pending |
| E1.6 | Add JNI exports for texture synthesis | Pending |
| E1.7 | Update CMakeLists.txt with new source files | Pending |

**JNI Function Naming Convention**:
```
Java_com_imagedit_app_ultradetail_NativeMFSRPipeline_<functionName>
```

#### E2. Kotlin Wrapper Updates
**File**: `NativeMFSRPipeline.kt` (modify existing)
**Priority**: HIGH
**Dependencies**: E1

| Subtask | Description | Status |
|---------|-------------|--------|
| E2.1 | Add external fun declarations for new native methods | Pending |
| E2.2 | Add config classes for new algorithms | Pending |
| E2.3 | Add result classes for new algorithms | Pending |
| E2.4 | Update processing pipeline to use new modules | Pending |

#### E3. Pipeline Orchestration
**File**: `UltraDetailPipeline.kt` (modify existing)
**Priority**: HIGH
**Dependencies**: E2

| Subtask | Description | Status |
|---------|-------------|--------|
| E3.1 | Add pipeline stage for ORB alignment (optional) | Pending |
| E3.2 | Add pipeline stage for rolling shutter correction | Pending |
| E3.3 | Replace simple merge with anisotropic merge | Pending |
| E3.4 | Add Drizzle as alternative to shift-and-add | Pending |
| E3.5 | Add frequency separation as post-processing | Pending |
| E3.6 | Add texture injection as final step | Pending |
| E3.7 | Add quality preset configurations (FAST/BALANCED/MAX/ULTRA) | Pending |

#### E4. CMakeLists.txt Update
**File**: `CMakeLists.txt` (modify existing)
**Priority**: HIGH
**Dependencies**: All C++ files created

| Subtask | Description | Status |
|---------|-------------|--------|
| E4.1 | Add orb_alignment.cpp to sources | Pending |
| E4.2 | Add drizzle.cpp to sources | Pending |
| E4.3 | Add rolling_shutter.cpp to sources | Pending |
| E4.4 | Add anisotropic_merge.cpp to sources | Pending |
| E4.5 | Add freq_separation.cpp to sources | Pending |
| E4.6 | Add texture_synthesis.cpp to sources | Pending |
| E4.7 | Add kalman_fusion.cpp to sources | Pending |

---

## Implementation Order (Recommended)

### Phase 1: Foundation (High Impact, Low Risk)
1. **A1** - Enhanced Burst Capture (12+ frames)
2. **D1** - Frequency Separation Refinement
3. **C2** - Edge-Aware Anisotropic Merging

### Phase 2: Alignment Improvements
4. **B1** - ORB Feature Matching
5. **C1** - Drizzle Algorithm
6. **B3** - Gyro-Flow Kalman Fusion

### Phase 3: Advanced Features
7. **B2** - Rolling Shutter Correction
8. **D2** - Texture Noise Injection
9. **A2** - RAW Capture Mode

### Phase 4: Integration
10. **E4** - CMakeLists.txt Update
11. **E1** - JNI Bindings
12. **E2** - Kotlin Wrappers
13. **E3** - Pipeline Orchestration

---

## File Summary

### New C++ Files
| File | Purpose | Lines (est.) |
|------|---------|--------------|
| `orb_alignment.h` | ORB header | ~180 |
| `orb_alignment.cpp` | ORB implementation | ~500 |
| `drizzle.h` | Drizzle header | ~80 |
| `drizzle.cpp` | Drizzle implementation | ~300 |
| `rolling_shutter.h` | RS correction header | ~60 |
| `rolling_shutter.cpp` | RS correction impl | ~250 |
| `anisotropic_merge.h` | Anisotropic header | ~70 |
| `anisotropic_merge.cpp` | Anisotropic impl | ~350 |
| `freq_separation.h` | Freq sep header | ~50 |
| `freq_separation.cpp` | Freq sep impl | ~200 |
| `texture_synthesis.h` | Texture header | ~60 |
| `texture_synthesis.cpp` | Texture impl | ~250 |
| `kalman_fusion.h` | Kalman header | ~80 |
| `kalman_fusion.cpp` | Kalman impl | ~300 |

### Modified Files
| File | Changes |
|------|---------|
| `CMakeLists.txt` | Add new source files |
| `ultradetail_jni.cpp` | Add JNI exports |
| `NativeMFSRPipeline.kt` | Add native declarations |
| `UltraDetailPipeline.kt` | Orchestration updates |
| `BurstCaptureController.kt` | 12+ frames, diversity |
| `RawCaptureHelper.kt` | RAW capture mode |

---

## Testing Checklist

- [ ] Build succeeds with all new files
- [ ] ORB alignment produces valid homographies
- [ ] Drizzle output has better detail than simple upscale
- [ ] Rolling shutter correction reduces wobble artifacts
- [ ] Anisotropic merge preserves edges better
- [ ] Frequency separation adds sharpness without halos
- [ ] Texture injection looks natural in flat areas
- [ ] 12-frame capture completes in reasonable time
- [ ] RAW mode works on supported devices
- [ ] Pipeline presets (FAST/BALANCED/MAX/ULTRA) work correctly
- [ ] Memory usage stays within bounds
- [ ] No crashes or ANRs during processing

---

## Notes

1. **NEON Optimization**: All C++ pixel loops should have NEON variants for ARM performance
2. **Memory Management**: Use tile-based processing for large images to avoid OOM
3. **Fallbacks**: Each enhancement should gracefully fall back if it fails
4. **Logging**: Use consistent TAG prefixes for debugging (e.g., `ORB:`, `DRIZZLE:`, `RS:`)
5. **Thread Safety**: Native code must be thread-safe for parallel tile processing
