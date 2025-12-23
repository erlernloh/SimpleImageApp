# Ultra Detail+ Android Optimization Roadmap
## Mobile-First Implementation for Computational Photography

**Document Version**: 1.0  
**Platform**: Android (API 28+, Snapdragon/Exynos focus)  
**Status**: Mobile-Optimized Technical Specification  
**Date**: December 2025

---

## Executive Summary: Android Constraints & Opportunities

### Why Android Optimization Matters
- **58% of global smartphone market** (Android dominance)
- **Device fragmentation**: 1000+ unique device combinations
- **Thermal constraints**: Real-time processing limited by sustained CPU/GPU load
- **Memory diversity**: 4GB → 12GB+ RAM across device range
- **ISP variety**: Qualcomm, MediaTek, Samsung, Google vary significantly

### Current Android Reality
- **Device Range**: Snapdragon 870 (2021) → 8 Gen 3 (2024)
- **RAM**: 4GB budget → 12GB flagship
- **Storage**: eMMC → UFS 4.0
- **Thermal throttling**: Critical at 2+ minute processing times
- **Battery drain**: Current 5-10 min processing = 15-20% battery

### Phase 2 Android Target
- **Compatible**: Snapdragon 870 and above (~70% active Android devices)
- **Latency**: <500ms (fits thermal + battery budget)
- **Battery Impact**: <5% drain per capture
- **Memory Peak**: <650MB (fits 6GB+ devices, avoids OOM)

---

## Part 1: Android Platform Specifics

### 1.1 Hardware Acceleration Landscape

#### Qualcomm Snapdragon (65% Android market)
| Gen | Model | Compute | NPU | GPU | TFLite Support |
|-----|-------|---------|-----|-----|---|
| 2021 | 870 | 8-core Kryo | Hexagon v68 | Adreno 650 | ✅ Full |
| 2022 | Gen 1 | 8-core Kryo | Hexagon v73 | Adreno 710 | ✅ Full |
| 2023 | Gen 2 | 8-core Kryo | Hexagon v75 | Adreno 740 | ✅ Optimal |
| 2024 | Gen 3 | 8-core Oryon | Hexagon v78 | Adreno 8 | ✅ SOTA |

**Optimization Strategy**:
- ✅ Use Adreno GPU delegates for neural ops (1.5-2x faster)
- ✅ Use Hexagon NPU via QNN (Qualcomm Neural Network) for 2-3x peak throughput
- ⚠️ CPU fallback for older devices (≤2021 models)
- ⚠️ Thermal management for Gen 1 (aggressive throttling above 45°C)

#### MediaTek Dimensity (20% Android market)
| Gen | Model | Compute | NPU | GPU | TFLite Support |
|-----|-------|---------|-----|-----|---|
| 2022 | 9000 | 8-core Cortex-X2 | APU 3.0 | Mali-G77 | ✅ Full |
| 2023 | 9200 | 8-core Cortex-X3 | APU 3.5 | Mali-G78 | ✅ Full |
| 2024 | 9400 | 8-core Cortex-X4 | APU 4.0 | Mali-G715 | ✅ Optimal |

**Optimization Strategy**:
- ✅ Mali GPU delegates (similar to Adreno)
- ✅ APU 3.5+ has dedicated tensor operations
- ⚠️ Power efficiency superior to Snapdragon
- ✅ Better sustained performance (less thermal throttling)

---

## Part 2: Android Architecture Optimization

### 2.1 Processing Pipeline (Android-Optimized)

```kotlin
// Current Pipeline (v2.0) - Heavyweight
Burst Capture (Camera2 API)
    ↓ (Store 12 × YUV frames: 216MB)
Gyro Alignment (Sensor API, 50ms CPU)
    ↓
Tile-Based MFSR (Multi-threaded, 3-4 min CPU)
    ↓
ESRGAN Inference (TFLite GPU, 1-2 min)
    ↓
Post-Processing (Bilateral Filter, 30s CPU)
    ↓
Save to Storage

// Phase 2 Pipeline (v3.0) - Optimized for Android
Burst Capture (CameraX API with threading)
    ↓ (Frames processed immediately, not stored)
Streaming Alignment (SpyNet via TFLite GPU, 30ms/pair)
    ↓
Fourier INR Fusion (TFLite GPU, per-tile accumulation, 50ms/tile)
    ↓
Output Bitmap (GPU-based)
    ↓
Refinement (Learned network, TFLite GPU, 10ms)
    ↓
Save to Storage + MediaStore
```

### 2.2 Memory Model & Constraints

#### Typical Android Memory Budget
```
Low-End (4GB RAM)
├─ System: ~1.5GB
├─ Other Apps: ~1GB
└─ Available: ~1.5GB ← Can't run v2.0 (needs 800MB peak + buffers)

Mid-Range (6-8GB RAM)
├─ System: ~1.5GB
├─ Other Apps: ~2GB
└─ Available: ~3GB ✅ Can run v3.0 (650MB peak)

Flagship (12GB+ RAM)
├─ System: ~1.5GB
├─ Other Apps: ~2GB
└─ Available: ~8.5GB ✅ Can run v4.0 (700MB peak)
```

---

## Phase 1: Android SpyNet Integration (12 weeks)

**Success Metrics**:
- ✅ SpyNet latency <50ms per frame pair (on Snapdragon Gen 2)
- ✅ PSNR improvement >0.5 dB
- ✅ Works on 90%+ of test device fleet
- ✅ Thermal stays <40°C during burst
- ✅ Battery drain <5% per capture session

---

## Phase 2: BurstM-Lite Android Port (20 weeks)

**Success Metrics**:
- ✅ Latency <500ms on Snapdragon Gen 2, MediaTek 9200
- ✅ PSNR 47.5-48.5 dB (stable across devices)
- ✅ Memory peak <650MB
- ✅ Zero OOM crashes on 8-device test fleet
- ✅ Thermal stays <40°C (no throttling)

**Critical Implementation**:
- GPU Delegate mandatory (3-4x faster than CPU)
- Streaming architecture (80MB peak vs 800MB buffered)
- Device-adaptive tile sizes (256px flagship, 128px mid-range)
- Thermal management with fallback pausing

---

## Phase 3: Android HDR + Advanced Features (16 weeks)

**Deliverable**: HDR fusion with 80-100 dB dynamic range

**Android 12+ Features**:
- HDR10 output support
- Tone mapping with MediaStore integration
- Frame quality metrics + selective dropping

---

## Budget Breakdown (Android-Focused)

```
Personnel:
├─ 2 Android Platform Engineers: $120K
├─ 1 Computer Vision Engineer: $100K
├─ 1 ML Operations Engineer: $90K
├─ 1 DevOps/Test Engineer: $75K
└─ Subtotal: $450K

Hardware & Infrastructure:
├─ GPU Training (RTX 3090): $2.5K
├─ Android Test Device Fleet: $10K
├─ Cloud Compute: $5K
└─ Subtotal: $17.5K

Android-Specific Tools: $2.5K
Contingency (10%): $47K
─────────────────────────
TOTAL: $517K over 15 months
```

---

**Document Status**: Technical Specification Ready for Implementation