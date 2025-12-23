# Ultra Detail+ Technical Specification
## MAX and ULTRA Mode Processing Pipeline

**Version:** 2.0  
**Last Updated:** December 2024  
**Target Audience:** Computer Vision / Image Processing Experts

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [System Architecture Overview](#2-system-architecture-overview)
3. [Burst Capture System](#3-burst-capture-system)
4. [Frame Alignment Pipeline](#4-frame-alignment-pipeline)
5. [Multi-Frame Super-Resolution (MFSR)](#5-multi-frame-super-resolution-mfsr)
6. [Neural Refinement Stage](#6-neural-refinement-stage)
7. [Data Flow Diagrams](#7-data-flow-diagrams)
8. [Memory Management](#8-memory-management)
9. [Parallel Processing Architecture](#9-parallel-processing-architecture)
10. [Limitations and Trade-offs](#10-limitations-and-trade-offs)
11. [Performance Characteristics](#11-performance-characteristics)
12. [References](#12-references)

---

## 1. Executive Summary

Ultra Detail+ is a computational photography pipeline that combines **Multi-Frame Super-Resolution (MFSR)** with **Neural Texture Synthesis** to achieve up to **8x resolution enhancement** from handheld burst captures on mobile devices.

### Key Differentiators

| Aspect | Traditional SR | Ultra Detail+ |
|--------|---------------|---------------|
| Input | Single frame | 8-12 burst frames |
| Detail Source | Hallucinated (AI) | Real sub-pixel data + AI enhancement |
| Resolution Gain | 2-4x (AI-generated) | 8x (2x real + 4x neural) |
| Authenticity | Low (invented textures) | High (real captured detail) |

### Preset Comparison

| Preset | Upscale Factor | Processing | Output Quality |
|--------|---------------|------------|----------------|
| **MAX** | 2x | MFSR only | Real sub-pixel detail, no AI |
| **ULTRA** | 8x (2x MFSR + 4x ESRGAN) | MFSR + Neural | Real detail + AI texture synthesis |

---

## 2. System Architecture Overview

### 2.1 Technology Stack

```
┌─────────────────────────────────────────────────────────────────┐
│                        APPLICATION LAYER                         │
│  Kotlin/Compose UI  │  ViewModel  │  Coroutines (Dispatchers.IO) │
├─────────────────────────────────────────────────────────────────┤
│                       PROCESSING LAYER                           │
│  UltraDetailPipeline.kt  │  MFSRRefiner.kt  │  NativeMFSRPipeline│
├─────────────────────────────────────────────────────────────────┤
│                         NATIVE LAYER                             │
│  tiled_pipeline.cpp  │  phase_correlation.cpp  │  mfsr.cpp      │
├─────────────────────────────────────────────────────────────────┤
│                       ACCELERATION LAYER                         │
│  ARM NEON SIMD  │  C++ std::thread  │  TensorFlow Lite GPU      │
├─────────────────────────────────────────────────────────────────┤
│                        HARDWARE LAYER                            │
│  Camera Sensor  │  Gyroscope  │  CPU (Multi-core)  │  GPU       │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 Processing Pipeline Stages

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│   CAPTURE    │───▶│  ALIGNMENT   │───▶│    MFSR      │───▶│   REFINE     │
│  (8-12 YUV)  │    │ (Gyro+Phase) │    │ (2x Upscale) │    │ (4x ESRGAN)  │
└──────────────┘    └──────────────┘    └──────────────┘    └──────────────┘
     150ms               50ms              3-5 min            1-2 min
```

---

## 3. Burst Capture System

### 3.1 Capture Configuration

```kotlin
// MAX Preset Configuration
BurstCaptureConfig(
    frameCount = 8,
    frameIntervalMs = 150,
    captureQuality = CaptureQuality.HIGH_QUALITY,
    targetResolution = Size(4000, 3000)  // ~12MP
)

// ULTRA Preset Configuration  
BurstCaptureConfig(
    frameCount = 12,
    frameIntervalMs = 100,
    captureQuality = CaptureQuality.HIGH_QUALITY,
    minFrameDiversity = 0.3f,  // Minimum sub-pixel shift
    adaptiveInterval = true,
    diversityCheckEnabled = true
)
```

### 3.2 Frame Data Structure

Each captured frame contains:

```kotlin
data class CapturedFrame(
    val yPlane: ByteBuffer,      // Y luminance plane (full resolution)
    val uPlane: ByteBuffer,      // U chrominance plane (half resolution)
    val vPlane: ByteBuffer,      // V chrominance plane (half resolution)
    val width: Int,              // Frame width in pixels
    val height: Int,             // Frame height in pixels
    val yRowStride: Int,         // Y plane row stride
    val uvRowStride: Int,        // UV plane row stride
    val uvPixelStride: Int,      // UV plane pixel stride
    val timestampNs: Long,       // Capture timestamp (nanoseconds)
    val gyroSamples: List<GyroSample>  // Gyroscope data during exposure
)

data class GyroSample(
    val timestampNs: Long,
    val rotationX: Float,  // rad/s around X axis
    val rotationY: Float,  // rad/s around Y axis
    val rotationZ: Float   // rad/s around Z axis
)
```

### 3.3 Why Burst Capture?

MFSR requires **sub-pixel diversity** between frames. Natural hand tremor during handheld capture provides this diversity:

- **Typical hand tremor:** 0.3-2.0 pixels of motion between frames
- **Capture interval:** 100-150ms allows sufficient motion accumulation
- **Frame count:** 8-12 frames provides statistical robustness

**Critical Insight:** If frames are too similar (< 0.3px shift), MFSR degrades to simple averaging with no resolution gain.

---

## 4. Frame Alignment Pipeline

### 4.1 Hybrid Alignment Strategy

The alignment system uses a **two-stage hybrid approach**:

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  GYRO INITIAL   │────▶│ PHASE CORREL.   │────▶│  FLOW FIELD     │
│  (Coarse Est.)  │     │ (Refinement)    │     │  (Per-Pixel)    │
└─────────────────┘     └─────────────────┘     └─────────────────┘
    ±10-50 px              ±0.1 px               Dense flow map
```

### 4.2 Stage 1: Gyroscope-Based Homography

Converts gyroscope rotation data to image-space homography:

```kotlin
// Rotation matrix from gyro integration
R = Rx(θx) × Ry(θy) × Rz(θz)

// Homography from rotation (assuming pure rotation, no translation)
H = K × R × K⁻¹

where K = [fx  0  cx]   // Camera intrinsic matrix
          [0  fy  cy]
          [0   0   1]
```

**Gyro Accuracy:** ±5-10 pixels (sufficient for search window reduction)

### 4.3 Stage 2: Phase Correlation Refinement

FFT-based sub-pixel alignment refinement:

```cpp
// Phase correlation algorithm
1. Apply Hanning window to reduce edge effects
2. Compute 2D FFT of reference and target tiles
3. Compute cross-power spectrum: CPS = (F1 × F2*) / |F1 × F2*|
4. Inverse FFT to get correlation surface
5. Find peak location with sub-pixel interpolation

// Sub-pixel peak refinement (parabolic fit)
Δx = (C[x-1] - C[x+1]) / (2 × (C[x-1] - 2×C[x] + C[x+1]))
```

**Phase Correlation Accuracy:** ±0.1 pixels

### 4.4 Flow Field Generation

The final output is a dense flow field mapping each pixel:

```cpp
struct FlowVector {
    float dx;         // Horizontal displacement
    float dy;         // Vertical displacement  
    float confidence; // Alignment confidence (0-1)
};

// Flow field dimensions match input tile
FlowField flowField(tileWidth, tileHeight);
```

---

## 5. Multi-Frame Super-Resolution (MFSR)

### 5.1 Core Algorithm: Scatter Accumulation

MFSR uses **scatter-based accumulation** to reconstruct a high-resolution image from multiple low-resolution observations:

```cpp
// For each input frame and pixel:
for (frame : frames) {
    for (pixel : frame) {
        // 1. Get alignment offset
        FlowVector flow = alignmentField[frame][pixel];
        
        // 2. Compute sub-pixel destination in HR grid
        float dstX = (pixel.x - flow.dx) * scaleFactor;
        float dstY = (pixel.y - flow.dy) * scaleFactor;
        
        // 3. Splat to HR grid using Mitchell-Netravali kernel
        for (ky = -2; ky < 2; ky++) {
            for (kx = -2; kx < 2; kx++) {
                float weight = mitchell(dstX - px) * mitchell(dstY - py);
                accumulator[px][py] += pixel.value * weight;
                weightMap[px][py] += weight;
            }
        }
    }
}

// Normalize
output = accumulator / weightMap;
```

### 5.2 Mitchell-Netravali Interpolation Kernel

We use Mitchell-Netravali (B=1/3, C=1/3) instead of Lanczos-3 for better stability:

```cpp
float mitchellWeight(float t) {
    t = abs(t);
    const float B = 1.0f / 3.0f;
    const float C = 1.0f / 3.0f;
    
    if (t < 1.0f) {
        return ((12 - 9*B - 6*C) * t³ + (-18 + 12*B + 6*C) * t² + (6 - 2*B)) / 6;
    } else if (t < 2.0f) {
        return ((-B - 6*C) * t³ + (6*B + 30*C) * t² + (-12*B - 48*C) * t + (8*B + 24*C)) / 6;
    }
    return 0.0f;
}
```

**Why Mitchell-Netravali over Lanczos?**
- Less ringing (halos) around high-contrast edges
- More stable under alignment errors
- Better for optical flow-based alignment where shifts are imprecise

### 5.3 Robustness Weighting (Outlier Rejection)

To handle moving objects and alignment errors:

```cpp
enum RobustnessMethod {
    NONE,   // Simple averaging
    HUBER,  // Mild outlier rejection (default)
    TUKEY   // Aggressive outlier rejection
};

float computeRobustnessWeight(RGBPixel current, RGBPixel reference, float confidence) {
    float diff = colorDistance(current, reference);
    
    // Huber loss derivative
    if (diff < threshold) {
        return confidence;
    } else {
        return confidence * threshold / diff;
    }
}
```

### 5.4 De-Ringing Clamp

Prevents interpolation artifacts at high-contrast edges:

```cpp
// Track min/max of all input samples contributing to each output pixel
struct AccumPixel {
    float r, g, b, weight;
    float minR, minG, minB;  // Local minimum
    float maxR, maxG, maxB;  // Local maximum
};

// After normalization, clamp to local range
output.r = clamp(normalized.r, minR, maxR);
output.g = clamp(normalized.g, minG, maxG);
output.b = clamp(normalized.b, minB, maxB);
```

### 5.5 Gap Filling

Pixels without sufficient sample coverage are filled using bicubic interpolation from the reference frame:

```cpp
if (weightMap[x][y] < minWeight) {
    // Map HR position back to LR reference frame
    float srcX = x / scaleFactor;
    float srcY = y / scaleFactor;
    
    // Bicubic interpolation from reference
    output[x][y] = bicubicInterpolate(referenceFrame, srcX, srcY);
}
```

### 5.6 Tile-Based Processing

To manage memory on mobile devices, processing is done in tiles:

```cpp
TilePipelineConfig config {
    .tileWidth = 512,
    .tileHeight = 512,
    .overlap = 64,        // Overlap for seamless blending
    .scaleFactor = 2,
    .robustness = HUBER,
    .robustnessThreshold = 0.8f
};
```

**Tile Grid Calculation:**
```
For 2048×1536 input at 2x scale:
- Output: 4096×3072
- Tiles: ceil(2048/448) × ceil(1536/448) = 5×4 = 20 tiles
- Each tile: 512×512 with 64px overlap
```

---

## 6. Neural Refinement Stage

### 6.1 ESRGAN 4x Upscaling

The ULTRA preset applies ESRGAN after MFSR for additional 4x upscaling:

```
Input (MFSR 2x output) ──▶ ESRGAN 4x ──▶ Output (8x total)
   2048×1536                              8192×6144
```

### 6.2 Model Architecture

```
ESRGAN (Enhanced Super-Resolution GAN)
├── Input: 128×128×3 RGB tile
├── Feature Extraction: Conv2D + LeakyReLU
├── RRDB Blocks (×23): Residual-in-Residual Dense Blocks
├── Upsampling: PixelShuffle 4x
└── Output: 512×512×3 RGB tile

Model Size: ~16MB (FP16 quantized)
Inference: TensorFlow Lite with GPU Delegate
```

### 6.3 Tile-Based Neural Processing

```kotlin
// ESRGAN tile configuration
RefinerConfig(
    tileSize = 128,      // Input tile size
    overlap = 16,        // Overlap for seamless blending
    useGpu = true,       // GPU acceleration
    blendStrength = 0.7f // Blend with original (0=original, 1=refined)
)

// Tile processing loop
for (tile in tiles) {
    // Skip pure-padding tiles (uniform areas)
    if (isPurePaddingTile(tile)) {
        output[tile] = bilinearUpscale(input[tile])
        continue
    }
    
    // Neural processing
    output[tile] = esrgan.process(input[tile])
}
```

### 6.4 CPU Refinement Polish

Final post-processing step:

```kotlin
// Unsharp mask for sharpening
sharpened = original + alpha * (original - gaussian_blur(original))

// Edge-preserving denoise (bilateral filter)
denoised = bilateral_filter(sharpened, spatialSigma=2.5, rangeSigma=0.15)
```

---

## 7. Data Flow Diagrams

### 7.1 MAX Preset Data Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           MAX PRESET PIPELINE                            │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐             │
│  │ Camera   │   │ 8 YUV    │   │ Gyro     │   │ Gyro     │             │
│  │ Sensor   │──▶│ Frames   │──▶│ Samples  │──▶│ Homog.   │             │
│  │ 12MP     │   │ 4000×3000│   │ per frame│   │ 3×3 each │             │
│  └──────────┘   └──────────┘   └──────────┘   └──────────┘             │
│       │              │              │              │                    │
│       │              ▼              │              ▼                    │
│       │        ┌──────────┐        │        ┌──────────┐               │
│       │        │ YUV→RGB  │        │        │ Phase    │               │
│       │        │ Convert  │        │        │ Correl.  │               │
│       │        │ (on-fly) │        │        │ Refine   │               │
│       │        └──────────┘        │        └──────────┘               │
│       │              │              │              │                    │
│       │              ▼              │              ▼                    │
│       │        ┌─────────────────────────────────────┐                 │
│       │        │         TILE-BASED MFSR             │                 │
│       │        │  ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐   │                 │
│       │        │  │Tile1│ │Tile2│ │Tile3│ │...  │   │                 │
│       │        │  │512² │ │512² │ │512² │ │     │   │                 │
│       │        │  └─────┘ └─────┘ └─────┘ └─────┘   │                 │
│       │        │     ↓       ↓       ↓       ↓      │                 │
│       │        │  ┌─────────────────────────────┐   │                 │
│       │        │  │   Parallel Thread Pool      │   │                 │
│       │        │  │   (4-8 CPU threads)         │   │                 │
│       │        │  └─────────────────────────────┘   │                 │
│       │        └─────────────────────────────────────┘                 │
│       │                          │                                      │
│       │                          ▼                                      │
│       │                    ┌──────────┐                                │
│       │                    │ 2x Output│                                │
│       │                    │ 8000×6000│                                │
│       │                    │ ~48MP    │                                │
│       │                    └──────────┘                                │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### 7.2 ULTRA Preset Data Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          ULTRA PRESET PIPELINE                           │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐             │
│  │ Camera   │   │ 12 YUV   │   │ Gyro     │   │ Gyro     │             │
│  │ Sensor   │──▶│ Frames   │──▶│ Samples  │──▶│ Homog.   │             │
│  │ 12MP     │   │ 4000×3000│   │ per frame│   │ 3×3 each │             │
│  └──────────┘   └──────────┘   └──────────┘   └──────────┘             │
│                      │                              │                    │
│                      ▼                              ▼                    │
│               ┌─────────────────────────────────────────┐               │
│               │            MFSR 2x UPSCALE              │               │
│               │  (Same as MAX, but with 12 frames)      │               │
│               │  Output: 8000×6000 (~48MP)              │               │
│               └─────────────────────────────────────────┘               │
│                                   │                                      │
│                                   ▼                                      │
│               ┌─────────────────────────────────────────┐               │
│               │          ESRGAN 4x UPSCALE              │               │
│               │  ┌─────────────────────────────────┐    │               │
│               │  │   TensorFlow Lite Interpreter   │    │               │
│               │  │   + GPU Delegate (OpenGL ES)    │    │               │
│               │  └─────────────────────────────────┘    │               │
│               │                                         │               │
│               │  Tile Processing: 128×128 → 512×512     │               │
│               │  Total Tiles: ~2000 for 48MP input      │               │
│               │  Output: 32000×24000 (~768MP)           │               │
│               │                                         │               │
│               │  ⚠️ MEMORY CAP: Limited to 100MP max    │               │
│               └─────────────────────────────────────────┘               │
│                                   │                                      │
│                                   ▼                                      │
│               ┌─────────────────────────────────────────┐               │
│               │         CPU REFINEMENT POLISH           │               │
│               │  • Unsharp Mask (sharpening)            │               │
│               │  • Bilateral Filter (edge-preserving)   │               │
│               └─────────────────────────────────────────┘               │
│                                   │                                      │
│                                   ▼                                      │
│                            ┌──────────┐                                 │
│                            │ 8x Output│                                 │
│                            │ Capped at│                                 │
│                            │ ~100MP   │                                 │
│                            └──────────┘                                 │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 8. Memory Management

### 8.1 Memory Budget Analysis

```
For 12MP input (4000×3000) with ULTRA preset:

CAPTURE PHASE:
├── 12 YUV frames: 12 × (4000×3000×1.5) = 216 MB
└── Gyro samples: ~1 KB (negligible)

MFSR PHASE (per tile, 512×512):
├── Tile crops (12 frames): 12 × (512×512×3×4) = 37.7 MB
├── Gray tiles (12 frames): 12 × (512×512×4) = 12.6 MB
├── Flow fields (12 frames): 12 × (512×512×3×4) = 37.7 MB
├── Accumulator (1024×1024): 1024×1024×10×4 = 40 MB
└── Output tile: 1024×1024×3×4 = 12 MB
    SUBTOTAL: ~140 MB per tile (released after each tile)

MFSR OUTPUT:
└── 2x upscaled bitmap: 8000×6000×4 = 183 MB

ESRGAN PHASE:
├── Input buffer: 128×128×3×4 = 192 KB
├── Output buffer: 512×512×3×4 = 3 MB
├── TFLite model: ~16 MB
└── GPU memory: ~50-100 MB

FINAL OUTPUT:
└── 8x upscaled bitmap: Capped at 100MP × 4 = 400 MB

PEAK MEMORY: ~600-800 MB
```

### 8.2 Memory Optimization Strategies

1. **Direct YUV Processing:** Avoids RGB conversion memory spike (~360MB saved)
2. **Tile-Based Processing:** Only one tile in memory at a time
3. **On-the-Fly Conversion:** YUV→RGB conversion during scatter, not pre-converted
4. **Output Capping:** Maximum 100MP output to prevent OOM
5. **Aggressive Recycling:** Bitmaps recycled immediately after use

---

## 9. Parallel Processing Architecture

### 9.1 Multi-Threaded Tile Processing

```cpp
// Detect available CPU cores
const int numThreads = std::max(2, std::thread::hardware_concurrency());
// Typical: 4-8 threads on modern smartphones

// Parallel tile processing
std::vector<std::thread> threads;
int tilesPerThread = (totalTiles + numThreads - 1) / numThreads;

for (int t = 0; t < numThreads; ++t) {
    int startIdx = t * tilesPerThread;
    int endIdx = std::min(startIdx + tilesPerThread, totalTiles);
    
    threads.emplace_back([&, startIdx, endIdx]() {
        for (int i = startIdx; i < endIdx; ++i) {
            processTile(tiles[i], tileResults[i]);
            
            // Thread-safe progress reporting
            std::lock_guard<std::mutex> lock(progressMutex);
            progressCallback(++tilesCompleted, totalTiles);
        }
    });
}

// Wait for completion
for (auto& thread : threads) {
    thread.join();
}

// Sequential blending (avoids race conditions)
for (int i = 0; i < totalTiles; ++i) {
    blendTileToOutput(tileResults[i], outputImage);
}
```

### 9.2 GPU Acceleration

| Component | Acceleration Method |
|-----------|---------------------|
| MFSR Scatter | CPU Multi-threaded (NEON SIMD potential) |
| Phase Correlation FFT | CPU (could use GPU FFT) |
| ESRGAN Inference | TensorFlow Lite GPU Delegate (OpenGL ES) |
| Bilateral Filter | CPU (could use RenderScript) |

### 9.3 Speedup Expectations

| Configuration | Processing Time (12MP → 48MP) |
|---------------|-------------------------------|
| Single-threaded | 15-20 minutes |
| 4 threads | 4-6 minutes |
| 8 threads | 2-4 minutes |
| + GPU ESRGAN | 3-5 minutes total |

---

## 10. Limitations and Trade-offs

### 10.1 Fundamental Limitations

| Limitation | Cause | Mitigation |
|------------|-------|------------|
| **Motion blur** | Hand shake during exposure | Short exposure, burst averaging |
| **Moving objects** | Scene motion between frames | Robustness weighting, reference fallback |
| **Low diversity** | Insufficient hand tremor | Diversity detection, user guidance |
| **Memory constraints** | Mobile device limits | Tile processing, output capping |
| **Processing time** | Computational complexity | Multi-threading, GPU acceleration |

### 10.2 Quality Degradation Scenarios

1. **Tripod Capture:** Frames are identical → MFSR degrades to simple upscaling
2. **Fast Motion:** Alignment fails → Blurry output, reference frame fallback
3. **Low Light:** High noise → Noise amplified in upscaling
4. **Repetitive Textures:** Phase correlation may lock to wrong peak

### 10.3 Theoretical vs. Practical Resolution Gain

```
THEORETICAL (Nyquist limit):
- 10 frames with 0.5px diversity each → √10 ≈ 3.16x resolution gain
- Practical limit: ~2-3x real detail improvement

PRACTICAL (Ultra Detail+):
- MFSR 2x: ~1.5-2x real detail (rest is interpolation)
- ESRGAN 4x: Adds plausible texture, not real detail
- Total: 8x pixels, ~2x real detail, 4x AI-enhanced texture
```

### 10.4 When NOT to Use Ultra Detail+

- **Action shots:** Moving subjects will ghost/blur
- **Tripod photography:** No sub-pixel diversity
- **Time-critical:** Processing takes 3-5 minutes
- **Memory-constrained:** Requires 600-800MB RAM

---

## 11. Performance Characteristics

### 11.1 Benchmark Results (Typical Mid-Range Android)

| Stage | Time | Memory |
|-------|------|--------|
| Burst Capture (12 frames) | 1.5s | 216 MB |
| Gyro Homography | 50ms | 1 KB |
| MFSR 2x (20 tiles, 4 threads) | 3-4 min | 140 MB peak |
| ESRGAN 4x (2000 tiles, GPU) | 1-2 min | 100 MB GPU |
| CPU Refinement | 30s | 50 MB |
| **Total** | **4-6 min** | **800 MB peak** |

### 11.2 Computational Complexity

```
MFSR Scatter Accumulation (per tile):
- Frames: F = 10
- Tile pixels: N = 512×512 = 262,144
- Kernel size: K = 4×4 = 16
- Operations per pixel: F × K = 160
- Total per tile: N × F × K = 42 million operations
- Total for image: 20 tiles × 42M = 840 million operations

Phase Correlation (per tile pair):
- FFT: O(N log N) where N = 512×512
- Total: 2 × FFT + multiply + IFFT = ~4 × 512² × log(512²) ≈ 20M operations
- Per frame pair: 20M × 11 pairs = 220M operations
```

---

## 12. References

### 12.1 Academic Papers

1. **Handheld Multi-Frame Super-Resolution** (Wronski et al., Google, 2019)
   - Core MFSR algorithm inspiration
   - Robustness weighting approach

2. **ESRGAN: Enhanced Super-Resolution Generative Adversarial Networks** (Wang et al., 2018)
   - Neural upscaling architecture
   - RRDB block design

3. **Reconstruction Filters in Computer Graphics** (Mitchell & Netravali, 1988)
   - Mitchell-Netravali interpolation kernel

4. **Phase Correlation** (Kuglin & Hines, 1975)
   - FFT-based image registration

### 12.2 Implementation References

- TensorFlow Lite: https://www.tensorflow.org/lite
- Android CameraX: https://developer.android.com/training/camerax
- ARM NEON Intrinsics: https://developer.arm.com/architectures/instruction-sets/simd-isas/neon

---

## Appendix A: Configuration Reference

### A.1 MFSR Pipeline Configuration

```kotlin
NativeMFSRConfig(
    tileWidth = 512,           // Tile width in pixels
    tileHeight = 512,          // Tile height in pixels
    overlap = 64,              // Overlap between tiles
    scaleFactor = 2,           // Upscale factor (2x)
    robustness = HUBER,        // Outlier rejection method
    robustnessThreshold = 0.8f,// Threshold for outlier rejection
    useGyroInit = true         // Use gyro for initial alignment
)
```

### A.2 ESRGAN Refiner Configuration

```kotlin
RefinerConfig(
    model = ESRGAN_REFINE,     // Model to use
    tileSize = 128,            // Input tile size
    overlap = 16,              // Overlap for blending
    useGpu = true,             // GPU acceleration
    numThreads = 4,            // CPU threads (fallback)
    blendStrength = 0.7f       // Blend with original
)
```

### A.3 Burst Capture Configuration

```kotlin
BurstCaptureConfig(
    frameCount = 12,           // Number of frames
    frameIntervalMs = 100,     // Interval between frames
    targetResolution = Size(4000, 3000),
    captureQuality = HIGH_QUALITY,
    minFrameDiversity = 0.3f,  // Minimum sub-pixel shift
    adaptiveInterval = true,   // Adjust timing dynamically
    diversityCheckEnabled = true
)
```

---

*Document generated from Ultra Detail+ codebase analysis*
*For questions, contact the development team*
