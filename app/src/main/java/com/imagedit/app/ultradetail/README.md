# Ultra Detail+ Imaging Pipeline

Advanced multi-frame capture and processing pipeline for Android that combines
HDR+ style burst processing with selective neural network super-resolution.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    Ultra Detail+ Pipeline                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐      │
│  │   Stage 1    │───▶│   Stage 2    │───▶│   Stage 3    │      │
│  │ Burst Merge  │    │ Detail Mask  │    │ Super-Res    │      │
│  └──────────────┘    └──────────────┘    └──────────────┘      │
│         │                   │                   │                │
│         ▼                   ▼                   ▼                │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐      │
│  │   C++/NDK    │    │   C++/NDK    │    │   TFLite     │      │
│  │  - Pyramid   │    │  - Sobel     │    │  - GPU/NNAPI │      │
│  │  - Alignment │    │  - Tile mask │    │  - Tiling    │      │
│  │  - Merge     │    │  - Morphology│    │  - Blending  │      │
│  └──────────────┘    └──────────────┘    └──────────────┘      │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

## Components

### Kotlin Classes

- **`BurstCaptureController`** - CameraX burst capture with AE/AF lock
- **`NativeBurstProcessor`** - JNI bridge to native processing
- **`SuperResolutionProcessor`** - TFLite super-resolution with tiling
- **`UltraDetailPipeline`** - Main orchestrator
- **`UltraDetailViewModel`** - UI state management
- **`UltraDetailScreen`** - Compose UI

### C++ Native Code

- **`burst_processor.cpp/h`** - Main processing pipeline
- **`pyramid.cpp/h`** - Gaussian/Laplacian pyramids
- **`alignment.cpp/h`** - HDR+ style tile alignment
- **`merge.cpp/h`** - Robust frame merging
- **`edge_detection.cpp/h`** - Sobel/Scharr edge detection
- **`yuv_converter.cpp/h`** - YUV to RGB conversion
- **`neon_utils.h`** - NEON SIMD optimizations

## Presets

| Preset | Frames | Processing | Output |
|--------|--------|------------|--------|
| Fast | 6 | Merge only | 1x |
| Balanced | 8 | Merge + edges | 1x |
| Max | 12 | Full pipeline | 2x SR |

## Performance Targets

- **Total processing time**: ≤6 seconds for 12MP input
- **Target devices**: Mid-range Android (Snapdragon 7xx+)
- **Memory usage**: <500MB peak

## TFLite Model Requirements

Place the super-resolution model in `app/src/main/assets/`:

- **Input**: `[1, 256, 256, 3]` float32 RGB normalized [0,1]
- **Output**: `[1, 512, 512, 3]` float32 RGB (for 2x) or `[1, 1024, 1024, 3]` (for 4x)
- **Recommended**: ESPCN, FSRCNN, or similar lightweight SR models

## Usage

```kotlin
// In your Activity/Fragment
val viewModel: UltraDetailViewModel by viewModels()

// Initialize
viewModel.initialize()

// Set preset
viewModel.setPreset(UltraDetailPreset.MAX)

// Start capture (requires BurstCaptureController setup)
viewModel.startCapture(burstController)

// Observe state
viewModel.uiState.collect { state ->
    when {
        state.isCapturing -> showCaptureProgress(state.captureProgress)
        state.isProcessing -> showProcessingProgress(state.processingProgress)
        state.resultBitmap != null -> showResult(state.resultBitmap)
        state.error != null -> showError(state.error)
    }
}
```

## Building

The native code requires NDK 25.2.9519653 or later. Build with:

```bash
./gradlew assembleDebug
```

## Algorithm Details

### Stage 1: Burst Alignment & Merge

1. Convert YUV_420_888 to float32 RGB
2. Build Gaussian pyramids (4 levels)
3. Coarse-to-fine tile alignment (32x32 tiles)
4. Warp frames to reference
5. Trimmed mean merge with Wiener filtering

### Stage 2: Detail Mask

1. Convert to luminance
2. Apply Scharr edge operator
3. Compute per-tile (64x64) average gradient
4. Threshold to classify detail vs smooth
5. Dilate mask to expand detail regions

### Stage 3: Super-Resolution

1. Split image into overlapping 256x256 tiles
2. For each tile:
   - If detail-rich: run TFLite SR model
   - If smooth: bicubic interpolation
3. Stitch tiles with feathered blending
4. Output 2x or 4x upscaled image

## License

Part of the ImageEdit app project.
