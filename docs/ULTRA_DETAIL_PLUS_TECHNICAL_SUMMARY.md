# Ultra Detail+ Technical Implementation Summary

## For Expert Review

**Document Version:** 1.0  
**Date:** December 2024  
**Platform:** Android (Kotlin + C++ NDK)

---

## 1. Executive Overview

Ultra Detail+ is a multi-frame super-resolution (MFSR) system for Android that captures burst images and combines them to produce higher-resolution output with reduced noise. The system implements a tile-based processing pipeline inspired by Google's Handheld Multi-Frame Super-Resolution (SIGGRAPH 2019).

### Preset Hierarchy

| Preset | Processing Pipeline | Output Scale | Key Features |
|--------|---------------------|--------------|--------------|
| **FAST** | Burst merge only | 1x | Simple averaging, denoising |
| **BALANCED** | Burst merge + detail mask | 1x | Trimmed mean, Wiener filter |
| **MAX** | MFSR + optional AI SR | 2x (MFSR) + 4x (AI) | Full MFSR pipeline, optional ESRGAN |
| **ULTRA** | MFSR + neural refinement | 2x | MFSR + TFLite refinement network |

---

## 2. System Architecture

### 2.1 High-Level Components

```
┌─────────────────────────────────────────────────────────────────┐
│                     UltraDetailPipeline.kt                       │
│                    (Kotlin Orchestrator)                         │
├─────────────────────────────────────────────────────────────────┤
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐   │
│  │ BurstCapture │  │GyroAlignment │  │ NativeMFSRPipeline   │   │
│  │  Controller  │  │   Helper     │  │   (JNI Wrapper)      │   │
│  └──────────────┘  └──────────────┘  └──────────────────────┘   │
├─────────────────────────────────────────────────────────────────┤
│                         JNI Boundary                             │
├─────────────────────────────────────────────────────────────────┤
│                     tiled_pipeline.cpp                           │
│                    (C++ Native Core)                             │
├─────────────────────────────────────────────────────────────────┤
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐   │
│  │DenseOptical  │  │  MultiFrame  │  │  Tile Blending &     │   │
│  │    Flow      │  │     SR       │  │  Post-Processing     │   │
│  └──────────────┘  └──────────────┘  └──────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 File Structure

| File | Purpose |
|------|---------|
| `UltraDetailPipeline.kt` | Main orchestrator, coordinates all processing stages |
| `NativeMFSRPipeline.kt` | Kotlin wrapper for native C++ pipeline via JNI |
| `MFSRRefiner.kt` | TensorFlow Lite neural refinement for ULTRA preset |
| `GyroAlignmentHelper.kt` | Gyroscope integration for motion estimation |
| `tiled_pipeline.cpp/h` | Core C++ tile-based MFSR implementation |
| `optical_flow.cpp/h` | Hierarchical Lucas-Kanade optical flow |
| `mfsr.cpp/h` | Multi-frame super-resolution accumulator |

---

## 3. MAX Preset Implementation

### 3.1 Pipeline Stages

```
Input: N burst frames (YUV420) + gyroscope data
                    │
                    ▼
┌─────────────────────────────────────────┐
│ Stage 1: Frame Diversity Analysis       │
│ - Compute gyro magnitude per frame      │
│ - Estimate sub-pixel shift potential    │
│ - Select best reference frame           │
└─────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────┐
│ Stage 2: Gyro Homography Computation    │
│ - Integrate gyroscope rotation          │
│ - Convert to 3x3 homography matrices    │
│ - Initialize optical flow search        │
└─────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────┐
│ Stage 3: Tile-Based MFSR Processing     │
│ (See Section 3.2 for details)           │
└─────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────┐
│ Stage 4: Post-Processing                │
│ - Bilateral filtering for artifacts     │
│ - Optional ESRGAN 4x upscaling          │
└─────────────────────────────────────────┘
                    │
                    ▼
Output: 2x upscaled image (or 8x with ESRGAN)
```

### 3.2 Tile-Based MFSR Core Algorithm

The core MFSR processing is implemented in `tiled_pipeline.cpp`. Processing is done tile-by-tile to maintain constant memory usage (~200MB) regardless of image size.

#### Configuration Parameters

```cpp
struct TilePipelineConfig {
    int tileWidth = 512;          // Tile size in input pixels
    int tileHeight = 512;
    int overlap = 64;             // Overlap for seamless blending
    int scaleFactor = 2;          // 2x upscaling
    
    // Robustness settings
    RobustnessMethod robustness = HUBER;  // Outlier rejection method
    float robustnessThreshold = 0.8f;     // Higher = more permissive
    
    // Optical flow parameters
    int pyramidLevels = 2;
    int windowSize = 9;
    int maxIterations = 5;
};
```

#### Per-Tile Processing Steps

**Step 1: Tile Extraction**
```cpp
// Extract tile crops from all frames with padding for overlap
for (int i = 0; i < numFrames; ++i) {
    extractTileCrop(frames[i], tile, tileCrops[i]);
    extractTileCrop(grayFrames[i], tile, grayTileCrops[i]);
}
```

**Step 2: Dense Optical Flow Computation**

Uses hierarchical Lucas-Kanade with gyro-based initialization:

```cpp
// Set reference frame
flowProcessor_->setReference(grayTileCrops[referenceIndex]);

for (int i = 0; i < numFrames; ++i) {
    if (i == referenceIndex) continue;
    
    // Get gyro initialization (reduces search radius from 20px to 5px)
    GyroHomography gyroInit = (*gyroHomographies)[i];
    
    // Compute dense per-pixel flow
    DenseFlowResult flowResult = flowProcessor_->computeFlow(
        grayTileCrops[i], 
        gyroInit
    );
    
    tileFlows[i] = flowResult.flowField;
}
```

**Step 3: Sub-Pixel Scatter to High-Resolution Grid**

Uses Mitchell-Netravali kernel (4x4) for stability under alignment errors (Fix #3):

> **Fix #3**: Replaced Lanczos-3 with Mitchell-Netravali (B=1/3, C=1/3).
> Lanczos-3 has negative lobes causing ringing artifacts when optical flow
> alignment is imprecise. Mitchell-Netravali is more stable and produces
> fewer artifacts with software-estimated shifts.

```cpp
// Mitchell-Netravali bicubic filter (B=1/3, C=1/3)
static inline float mitchellWeight(float t) {
    t = std::abs(t);
    const float B = 1.0f / 3.0f;
    const float C = 1.0f / 3.0f;
    
    if (t < 1.0f) {
        return ((12 - 9*B - 6*C)*t³ + (-18 + 12*B + 6*C)*t² + (6 - 2*B)) / 6;
    } else if (t < 2.0f) {
        return ((-B - 6*C)*t³ + (6*B + 30*C)*t² + (-12*B - 48*C)*t + (8*B + 24*C)) / 6;
    }
    return 0.0f;
}

// For each pixel in each frame
for (int frameIdx = 0; frameIdx < numFrames; ++frameIdx) {
    for (int y = 0; y < crop.height; ++y) {
        for (int x = 0; x < crop.width; ++x) {
            const RGBPixel& pixel = crop.at(x, y);
            const FlowVector& fv = flow.at(x, y);
            
            // Compute destination in HR grid (2x resolution)
            float dstX = (x - fv.dx) * scaleFactor;
            float dstY = (y - fv.dy) * scaleFactor;
            
            // Compute robustness weight (outlier rejection)
            float robustWeight = computeRobustnessWeight(
                pixel, 
                refCrop.at(x, y), 
                fv.confidence
            );
            
            // Mitchell-Netravali splatting (4x4 kernel)
            for (int ky = 0; ky < 4; ++ky) {
                for (int kx = 0; kx < 4; ++kx) {
                    float w = mitchellWeight(dx) * mitchellWeight(dy) 
                            * fv.confidence * robustWeight;
                    
                    accumulator.at(px, py).r += pixel.r * w;
                    accumulator.at(px, py).g += pixel.g * w;
                    accumulator.at(px, py).b += pixel.b * w;
                    accumulator.at(px, py).weight += w;
                    
                    // Track min/max for de-ringing
                    accumulator.at(px, py).updateMinMax(pixel);
                }
            }
        }
    }
}
```

**Step 4: Normalization with De-Ringing**

```cpp
for (int y = 0; y < outHeight; ++y) {
    for (int x = 0; x < outWidth; ++x) {
        const AccumPixel& acc = accumulator.at(x, y);
        
        if (acc.weight > 0.0f) {
            float invW = 1.0f / acc.weight;
            float rawR = acc.r * invW;
            float rawG = acc.g * invW;
            float rawB = acc.b * invW;
            
            // De-ringing clamp: limit to local min/max of input samples
            // Prevents Lanczos-3 ringing (halos) around edges
            out.r = clamp(rawR, acc.minR, acc.maxR);
            out.g = clamp(rawG, acc.minG, acc.maxG);
            out.b = clamp(rawB, acc.minB, acc.maxB);
        }
    }
}
```

**Step 5: Gap Filling with Bicubic Interpolation**

Gaps (pixels with no accumulated data) are filled using Mitchell-Netravali bicubic interpolation from the reference frame:

```cpp
// Mitchell-Netravali filter (B=1/3, C=1/3)
auto bicubicWeight = [](float t) -> float {
    t = std::abs(t);
    if (t < 1.0f) {
        return (12 - 9*B - 6*C) * t³ + (-18 + 12*B + 6*C) * t² + (6 - 2*B);
    } else if (t < 2.0f) {
        return (-B - 6*C) * t³ + (6*B + 30*C) * t² + (-12*B - 48*C) * t + (8*B + 24*C);
    }
    return 0.0f;
};

// For each gap pixel, interpolate from reference frame
for (int y = 0; y < outHeight; ++y) {
    for (int x = 0; x < outWidth; ++x) {
        if (accumulator.at(x, y).weight > 0.0f) continue;
        
        // Map HR position back to LR reference frame
        float srcX = x / scaleFactor;
        float srcY = y / scaleFactor;
        
        // 4x4 bicubic interpolation
        // ... (see implementation)
    }
}
```

### 3.3 Tile Blending

Tiles are blended using Hermite smoothstep function for seamless transitions:

```cpp
float computeBlendWeight(int x, int y, int width, int height, int overlap) {
    // Hermite smoothstep: 3t² - 2t³
    auto smoothstep = [](float t) -> float {
        t = clamp(t, 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    };
    
    float wx = 1.0f, wy = 1.0f;
    
    if (x < overlap) {
        wx = smoothstep(x / overlap);
    } else if (x >= width - overlap) {
        wx = smoothstep((width - 1 - x) / overlap);
    }
    
    // Similar for y...
    
    return wx * wy;
}
```

### 3.4 Post-Processing: Bilateral Filter

Edge-preserving smoothing to remove blotchy artifacts:

```cpp
const int filterRadius = 2;
// Fix #6: Tuned parameters for less aggressive smoothing
const float spatialSigma = 2.5f;   // Wider spatial kernel (was 1.5)
const float rangeSigma = 0.15f;    // More tolerant of color variation (was 0.08)

for (int y = filterRadius; y < outHeight - filterRadius; ++y) {
    for (int x = filterRadius; x < outWidth - filterRadius; ++x) {
        const RGBPixel& center = result.outputImage.at(x, y);
        
        float sumR = 0, sumG = 0, sumB = 0, sumW = 0;
        
        for (int dy = -filterRadius; dy <= filterRadius; ++dy) {
            for (int dx = -filterRadius; dx <= filterRadius; ++dx) {
                const RGBPixel& neighbor = result.outputImage.at(x+dx, y+dy);
                
                // Spatial weight (Gaussian)
                float spatialDist = sqrt(dx*dx + dy*dy);
                float spatialW = exp(-spatialDist² / (2 * spatialSigma²));
                
                // Range weight (color similarity)
                float colorDiff = sqrt(
                    (neighbor.r - center.r)² +
                    (neighbor.g - center.g)² +
                    (neighbor.b - center.b)²
                );
                float rangeW = exp(-colorDiff² / (2 * rangeSigma²));
                
                float w = spatialW * rangeW;
                sumR += neighbor.r * w;
                sumG += neighbor.g * w;
                sumB += neighbor.b * w;
                sumW += w;
            }
        }
        
        smoothed.at(x, y) = RGBPixel(sumR/sumW, sumG/sumW, sumB/sumW);
    }
}
```

### 3.5 Robustness Weighting

Two methods for outlier rejection with **adaptive thresholding** (Fix #4):

```cpp
// Fix #4: Adaptive threshold based on flow confidence
// High confidence (low motion) → gentler rejection
// Low confidence (high motion) → more aggressive rejection
float adaptiveThreshold = baseThreshold * (0.5f + 0.5f * flowConfidence);
```

**Huber Weight (Default - Gentler)**
```cpp
float huberWeight(float residual, float delta) {
    float absR = std::abs(residual);
    if (absR <= delta) return 1.0f;
    return delta / absR;
}
```

**Tukey Biweight (Aggressive)**
```cpp
float tukeyBiweight(float residual, float c) {
    float u = residual / c;
    if (std::abs(u) > 1.0f) return 0.0f;
    float t = 1.0f - u * u;
    return t * t;
}
```

---

## 4. ULTRA Preset Implementation

ULTRA extends MAX with neural refinement using TensorFlow Lite.

### 4.1 Additional Stage: Neural Refinement

```kotlin
class MFSRRefiner(context: Context, config: RefinerConfig) {
    
    // Configuration
    data class RefinerConfig(
        val model: RefinerModel = RefinerModel.ESRGAN_REFINE,
        val tileSize: Int = 128,
        val overlap: Int = 16,
        val useGpu: Boolean = true,
        val blendStrength: Float = 0.7f  // 0=original, 1=fully refined
    )
    
    suspend fun refine(mfsrOutput: Bitmap): Bitmap {
        // Process tile-by-tile through TFLite model
        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val refinedTile = processTile(inputBitmap, srcX, srcY)
                canvas.drawBitmap(refinedTile, dstRect, paint)
            }
        }
        
        // Blend with original based on strength
        if (config.blendStrength < 1.0f) {
            blendWithOriginal(output, mfsrOutput, config.blendStrength)
        }
        
        return output
    }
}
```

### 4.2 Refinement Blending

```kotlin
private fun blendWithOriginal(refined: Bitmap, original: Bitmap, strength: Float) {
    val origWeight = 1.0f - strength
    
    for (i in pixels.indices) {
        val r = (refinedR * strength + originalR * origWeight).toInt()
        val g = (refinedG * strength + originalG * origWeight).toInt()
        val b = (refinedB * strength + originalB * origWeight).toInt()
        
        refinedPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
```

---

## 5. Optical Flow Implementation

### 5.1 Hierarchical Lucas-Kanade

```cpp
class DenseOpticalFlow {
public:
    struct OpticalFlowParams {
        int pyramidLevels = 4;
        int windowSize = 15;
        int maxIterations = 10;
        float convergenceThreshold = 0.01f;
        float minEigenThreshold = 0.001f;
        bool useGyroInit = true;
        float gyroSearchRadius = 5.0f;    // With gyro
        float noGyroSearchRadius = 20.0f; // Without gyro
    };
    
    DenseFlowResult computeFlow(const GrayImage& target, 
                                const GyroHomography& gyroInit);
};
```

### 5.2 Gyro-Based Flow Initialization

```cpp
struct GyroHomography {
    float h[9];  // 3x3 homography matrix (row-major)
    
    FlowVector getInitialFlow(float x, float y) const {
        float w = h[6] * x + h[7] * y + h[8];
        float newX = (h[0] * x + h[1] * y + h[2]) / w;
        float newY = (h[3] * x + h[4] * y + h[5]) / w;
        return FlowVector(newX - x, newY - y, 1.0f);
    }
};
```

---

## 6. Reference Frame Selection

### 6.1 Combined Metric Approach

```kotlin
private fun selectBestFrame(frames: List<CapturedFrame>): Int {
    val metrics = frames.mapIndexed { index, frame ->
        val gyroMag = computeGyroMagnitude(frame.gyroSamples)
        val sharpness = computeFrameSharpness(frame)
        Triple(index, gyroMag, sharpness)
    }
    
    // Normalize to 0-1 range
    // ...
    
    // Score: 40% gyro stability + 60% sharpness
    metrics.forEach { (index, gyro, sharpness) ->
        val gyroScore = 1f - normalizedGyro    // Lower gyro = better
        val sharpScore = normalizedSharpness   // Higher = better
        val combinedScore = 0.4f * gyroScore + 0.6f * sharpScore
        
        if (combinedScore > bestScore) {
            bestScore = combinedScore
            bestIndex = index
        }
    }
    
    return bestIndex
}
```

### 6.2 Sharpness Computation (Laplacian Variance)

```kotlin
private fun computeFrameSharpness(frame: CapturedFrame): Float {
    // Sample center 128x128 region of Y channel
    for (y in startY until startY + sampleSize step 2) {
        for (x in startX until startX + sampleSize step 2) {
            val center = yBuffer.get(idx)
            val up = yBuffer.get(idxUp)
            val down = yBuffer.get(idxDown)
            val left = yBuffer.get(idxLeft)
            val right = yBuffer.get(idxRight)
            
            // Laplacian = 4*center - up - down - left - right
            val laplacian = 4f * center - up - down - left - right
            
            sum += laplacian
            sumSq += laplacian * laplacian
            count++
        }
    }
    
    // Variance of Laplacian (higher = sharper)
    val mean = sum / count
    val variance = (sumSq / count) - (mean * mean)
    return variance
}
```

---

## 7. Memory Management

### 7.1 Tile-Based Processing Strategy

The pipeline processes images tile-by-tile to maintain constant memory usage:

```
Memory Budget: ~200MB per tile

Per-tile allocation:
- N frame crops (RGB): N × tileW × tileH × 3 × 4 bytes
- N grayscale crops: N × tileW × tileH × 4 bytes  
- N flow fields: N × tileW × tileH × 12 bytes
- HR accumulator: (tileW × scale) × (tileH × scale) × 20 bytes
- Output tile: (tileW × scale) × (tileH × scale) × 12 bytes

For 512×512 tiles with 8 frames at 2x scale:
≈ 8 × 512 × 512 × 12 + 8 × 512 × 512 × 4 + 1024 × 1024 × 32
≈ 25MB + 8MB + 33MB ≈ 66MB per tile (well under 200MB limit)
```

### 7.2 Direct YUV Processing

ULTRA preset processes YUV frames directly to avoid RGB conversion memory spike:

```kotlin
// Avoids ~360MB memory spike from converting all frames to RGB upfront
val mfsrResult = pipeline.processYUV(
    frames = frames,           // YUV data
    referenceIndex = -1,       // Auto-select
    homographies = homographies,
    outputBitmap = outputBitmap,
    progressCallback = callback
)
```

---

## 8. Fallback Handling

### 8.1 Fallback Conditions

```cpp
enum class FallbackReason {
    NONE,                   // No fallback needed
    EXCESSIVE_MOTION,       // >50 pixels global motion
    LOW_COVERAGE,           // <50% valid pixels
    FLOW_FAILED,            // Optical flow computation failed
    MEMORY_EXCEEDED,        // Memory limit exceeded
    ALIGNMENT_FAILED        // Frame alignment failed
};

FallbackReason checkFallbackConditions(...) {
    if (frames.size() < 2) {
        return FallbackReason::ALIGNMENT_FAILED;
    }
    
    const float maxAllowedMotion = 50.0f;  // pixels
    for (size_t i = 0; i < grayFrames.size(); ++i) {
        float motion = estimateGlobalMotion(grayFrames[referenceIndex], grayFrames[i]);
        if (motion > maxAllowedMotion) {
            return FallbackReason::EXCESSIVE_MOTION;
        }
    }
    
    return FallbackReason::NONE;
}
```

### 8.2 Fallback Upscale

When MFSR fails, falls back to bilinear upscaling of reference frame:

```cpp
void fallbackUpscale(const RGBImage& referenceFrame, PipelineResult& result) {
    // Bilinear interpolation
    for (int y = 0; y < outHeight; ++y) {
        for (int x = 0; x < outWidth; ++x) {
            float srcX = x / scaleFactor;
            float srcY = y / scaleFactor;
            
            // Bilinear interpolation from 4 neighbors
            out = p00 * (1-fx) * (1-fy) + p10 * fx * (1-fy) +
                  p01 * (1-fx) * fy + p11 * fx * fy;
        }
    }
    
    result.usedFallback = true;
}
```

---

## 9. Key Algorithms Summary

| Algorithm | Purpose | Implementation |
|-----------|---------|----------------|
| **Mitchell-Netravali Splatting** | Sub-pixel scatter to HR grid | 4×4 kernel, B=1/3, C=1/3 (Fix #3) |
| **Hermite Smoothstep** | Tile blending | 3t² - 2t³ for smooth transitions |
| **Mitchell-Netravali Bicubic** | Gap filling | B=1/3, C=1/3 filter |
| **Bilateral Filter** | Artifact removal | σ_spatial=2.5, σ_range=0.15 (Fix #6) |
| **Adaptive Huber Weight** | Outlier rejection | Threshold adapts to flow confidence (Fix #4) |
| **Hierarchical Lucas-Kanade** | Optical flow | Coarse-to-fine with gyro init |
| **Laplacian Variance** | Sharpness detection | Reference frame selection |

---

## 10. Performance Characteristics

| Metric | MAX Preset | ULTRA Preset |
|--------|------------|--------------|
| Typical processing time | 5-15 seconds | 10-25 seconds |
| Memory usage (peak) | ~200MB | ~250MB |
| Output resolution | 2× input | 2× input |
| Tile size | 512×512 | 512×512 |
| Frames used | 8-16 | 8-16 |

---

## 11. References

1. **Google Handheld Multi-Frame Super-Resolution** (SIGGRAPH 2019)
   - Tile-based processing approach
   - Gyro-based flow initialization
   - Robustness weighting

2. **Google HDR+** (SIGGRAPH 2016)
   - Burst alignment and merging
   - Bilateral filtering

3. **Lanczos Resampling**
   - High-quality interpolation kernel
   - De-ringing via local min/max clamping

4. **Mitchell-Netravali Filter**
   - Bicubic interpolation for gap filling
   - B=1/3, C=1/3 parameters

---

## 12. Known Limitations

1. **Low Frame Diversity**: MFSR requires sub-pixel motion between frames. Static tripod shots will not benefit from super-resolution.

2. **Excessive Motion**: Global motion >50 pixels triggers fallback to single-frame upscaling.

3. **Emulator Limitations**: Gyroscope data is often zeros on emulators, reducing alignment accuracy.

4. **Neural Model Dependency**: ULTRA preset requires downloaded TFLite model (~20MB).

---

## 13. Expert Analysis Fixes (December 2024)

Based on comparative analysis with Nikon Z8 pixel shift technology, the following fixes were implemented to address "blotchy/blurry" output issues:

### Fix #3: Mitchell-Netravali Kernel (Implemented)
**Problem**: Lanczos-3 has negative lobes causing ringing artifacts when optical flow alignment is imprecise.

**Solution**: Replaced Lanczos-3 (6×6) with Mitchell-Netravali (4×4, B=1/3, C=1/3).

**Files**: `tiled_pipeline.cpp`

### Fix #4: Adaptive Robustness Weighting (Implemented)
**Problem**: Fixed threshold rejects too many frames in low-motion regions.

**Solution**: Threshold adapts based on flow confidence:
```cpp
float adaptiveThreshold = baseThreshold * (0.5f + 0.5f * flowConfidence);
```

**Files**: `tiled_pipeline.cpp`, `tiled_pipeline.h`

### Fix #5: Phase Correlation Alignment (Implemented)
**Problem**: Dense Lucas-Kanade is slow and error-prone in textureless regions.

**Solution**: FFT-based phase correlation for global shift detection, much faster and more robust.

**Files**: `phase_correlation.h`, `phase_correlation.cpp`

### Fix #6: Bilateral Filter Tuning (Implemented)
**Problem**: Over-aggressive smoothing (σ_spatial=1.5, σ_range=0.08) caused loss of detail.

**Solution**: Gentler parameters (σ_spatial=2.5, σ_range=0.15) preserve more texture.

**Files**: `tiled_pipeline.cpp`

### Fix #1: Hybrid Alignment (Implemented)
**Problem**: Dense optical flow is computationally expensive and error-prone.

**Solution**: Hybrid approach using gyro homography + phase correlation + optional sparse flow.

**Files**: `tiled_pipeline.h`, `tiled_pipeline.cpp`, `phase_correlation.cpp`

### Fix #2: RAW Capture Infrastructure (Implemented)
**Problem**: Working with demosaiced RGB loses color channel alignment that Nikon preserves.

**Solution**: Added `RawCaptureHelper` to detect RAW capability. Full RAW processing requires Camera2 API integration (future work).

**Files**: `RawCaptureHelper.kt`, `UltraDetailViewModel.kt`

---

## 14. Alignment Method Comparison

| Method | Speed | Accuracy | Best For |
|--------|-------|----------|----------|
| Dense Lucas-Kanade | Slow | High (local) | Complex deformations |
| Phase Correlation | Fast | Medium (global) | Translations |
| **Hybrid (Default)** | Medium | High | General use |

The hybrid method is now the default, providing the best balance of speed and quality.

---

*End of Technical Summary*
