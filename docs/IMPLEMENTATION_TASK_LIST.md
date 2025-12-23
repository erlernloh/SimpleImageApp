# Implementation Task List - Advanced Super-Resolution

## Status Legend
- ✅ **IMPLEMENTED** - Code complete and integrated
- 🔄 **PARTIAL** - Some aspects implemented
- ⏳ **PENDING** - Not yet started
- 🔮 **FUTURE** - Requires significant R&D or resources

---

## Phase 1: Intelligent Burst Capture

| Feature | Status | Notes |
|---------|--------|-------|
| Exposure Bracketing (±1.5 EV) | ✅ IMPLEMENTED | `BurstCaptureController.kt` - frames captured at varying EV |
| Gyro-Guided Sub-Pixel Diversity | 🔄 PARTIAL | Gyro recorded but not used to trigger capture timing |
| Sharpness-Aware Frame Selection | ✅ IMPLEMENTED | `selectBestFrameHQ()` in pipeline |
| RAW Capture Mode | ✅ IMPLEMENTED | `RawBurstCaptureController.kt` exists |
| Smart capture timing (0.3-0.7px sweet spot) | ⏳ PENDING | Would improve sub-pixel diversity |
| Reject excessive motion frames (>2px) | ⏳ PENDING | Currently uses all captured frames |

---

## Phase 2: Multi-Frame Fusion (Leica-Inspired)

| Feature | Status | Notes |
|---------|--------|-------|
| Sub-Pixel Alignment (Phase correlation) | ✅ IMPLEMENTED | `phase_correlation.cpp` |
| ORB Feature Matching | ✅ IMPLEMENTED | `orb_alignment.cpp` |
| Gyro-based Homography | ✅ IMPLEMENTED | `GyroAlignmentHelper.kt` |
| Kalman Fusion | ✅ IMPLEMENTED | `kalman_fusion.cpp` |
| Mertens Exposure Fusion | ⏳ PENDING | Would improve HDR detail recovery |
| Laplacian Pyramid Blending | ⏳ PENDING | Better seam blending |
| Wavelet Detail Extraction | ⏳ PENDING | Per-exposure detail extraction |
| Per-Pixel Quality Mask | ✅ IMPLEMENTED | `RGBQualityMask.kt` |
| Weighted Median Outlier Rejection | ⏳ PENDING | Would reduce ghosting |

---

## Phase 3: Neural Super-Resolution (Topaz-Inspired)

| Feature | Status | Notes |
|---------|--------|-------|
| Scene Classification | ⏳ PENDING | Detect face/text/nature/architecture |
| Multi-Model Ensemble | ⏳ PENDING | Different models for different content |
| Face-Specific Model (GFPGAN-lite) | ⏳ PENDING | Better face detail |
| Text-Specific Model | ⏳ PENDING | Sharper text/signage |
| Degradation-Aware Processing | ⏳ PENDING | Estimate blur kernel per region |
| Tiled Processing | ✅ IMPLEMENTED | `MFSRRefiner.kt` with ESRGAN |
| Adaptive Tile Size | 🔄 PARTIAL | Fixed tile sizes per preset |

---

## Phase 4: Reference-Based Texture Enhancement

| Feature | Status | Notes |
|---------|--------|-------|
| Cross-Frame Reference Transfer | ✅ IMPLEMENTED | `nativeReferenceDetailTransfer()` - NEW |
| Self-Exemplar Mining | ⏳ PENDING | Find similar patches within image |
| Deformable Attention Matching | ⏳ PENDING | Better correspondence |
| Semantic Texture Synthesis | ⏳ PENDING | Recognize texture type |
| Wavelet-Based Detail Injection | ⏳ PENDING | Frequency-domain enhancement |

---

## Phase 5: Final Refinement

| Feature | Status | Notes |
|---------|--------|-------|
| Frequency Separation | ✅ IMPLEMENTED | `freq_separation.cpp` |
| Anisotropic Filtering | ✅ IMPLEMENTED | `anisotropic_merge.cpp` |
| Drizzle Sub-Pixel Enhancement | ✅ IMPLEMENTED | `drizzle.cpp` |
| Texture Synthesis | ✅ IMPLEMENTED | `texture_synthesis.cpp` |
| Halo Artifact Removal | ⏳ PENDING | Post-processing cleanup |
| Ringing Suppression | ⏳ PENDING | Near edges |
| Quality Assessment (BRISQUE/NIQE) | ⏳ PENDING | No-reference quality score |

---

## Experimental Techniques (Other Fields)

| Technique | Source Field | Status | Notes |
|-----------|--------------|--------|-------|
| Lucky Imaging | Astronomy | ⏳ PENDING | Select top 30% sharpest frames |
| Pansharpening | Satellite | ⏳ PENDING | High-res luma + low-res chroma |
| Multi-Slice Fusion | Medical | 🔄 PARTIAL | Similar to current MFSR |
| Spectral Repair | Audio | ⏳ PENDING | Fill missing frequencies |
| Structured Illumination | Microscopy | 🔄 PARTIAL | Exposure bracketing is similar |

---

## Future/Advanced Features

| Feature | Status | Notes |
|---------|--------|-------|
| Diffusion-Based Enhancement | 🔮 FUTURE | SD-Turbo/LCM too heavy for mobile |
| Cloud Offload for Max Quality | 🔮 FUTURE | Requires backend infrastructure |
| Content-Aware Model Training | 🔮 FUTURE | Requires training pipeline |
| Real-Time Preview | 🔮 FUTURE | GPU optimization needed |

---

## Summary Statistics

| Category | Implemented | Partial | Pending | Future |
|----------|-------------|---------|---------|--------|
| Burst Capture | 3 | 1 | 2 | 0 |
| Multi-Frame Fusion | 5 | 0 | 4 | 0 |
| Neural SR | 1 | 1 | 5 | 0 |
| Texture Enhancement | 1 | 0 | 4 | 0 |
| Final Refinement | 4 | 0 | 3 | 0 |
| Experimental | 0 | 2 | 3 | 0 |
| Advanced | 0 | 0 | 0 | 4 |
| **TOTAL** | **14** | **4** | **21** | **4** |

**Implementation Progress: ~33% of proposed features**

---

## Recommended Priority Order

### Immediate (High Impact, Low Effort)

1. **Lucky Imaging Frame Selection** - Use only top 30-50% sharpest frames
   - Effort: 2 hours
   - Impact: Reduces blur from motion-affected frames

2. **Mertens Exposure Fusion** - Better HDR detail from bracketed frames
   - Effort: 4 hours
   - Impact: Significantly better shadow/highlight detail

3. **Motion Rejection** - Skip frames with >2px motion
   - Effort: 2 hours
   - Impact: Cleaner fusion, less ghosting

### Short-Term (1-2 weeks)

4. **Self-Exemplar Texture Mining** - Use sharp patches to fix blurry patches
   - Effort: 1 week
   - Impact: Better texture in smooth areas

5. **Wavelet Detail Extraction** - Per-exposure frequency separation
   - Effort: 3 days
   - Impact: Better detail preservation

6. **Scene Classification** - Detect content type for model selection
   - Effort: 3 days
   - Impact: Enables content-aware processing

### Medium-Term (1 month)

7. **Face-Specific Model** - GFPGAN-lite for face regions
   - Effort: 1 week
   - Impact: Much better face detail

8. **Quality Assessment** - BRISQUE/NIQE scoring
   - Effort: 3 days
   - Impact: User feedback, auto-retry on poor quality

9. **Artifact Removal** - Halo and ringing suppression
   - Effort: 1 week
   - Impact: Cleaner output

### Long-Term (Future)

10. **Diffusion Enhancement** - SD-Turbo for detail hallucination
11. **Cloud Processing** - Offload heavy processing
12. **Custom Model Training** - Domain-specific models

---

## Next Actions

To continue implementation, prioritize:

```
1. [ ] Implement Lucky Imaging frame selection
2. [ ] Add Mertens exposure fusion algorithm
3. [ ] Add motion rejection threshold
4. [ ] Implement self-exemplar texture mining
5. [ ] Add scene classification for content-aware processing
```

These 5 items would bring implementation to ~50% and significantly improve output quality.
