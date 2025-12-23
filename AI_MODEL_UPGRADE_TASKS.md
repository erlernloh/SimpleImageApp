# AI Model Upgrade Task List

## Overview
This document outlines the tasks required to upgrade and integrate advanced AI models for improved image enhancement in the Ultra Detail+ feature.

---

## Phase 1: Upgrade Super-Resolution Model

### Task 1.1: Replace ESRGAN with Real-ESRGAN
**Priority:** High  
**Estimated Effort:** 2-3 days

**Current State:**
- Using basic ESRGAN model for 4x upscaling
- Model location: `app/src/main/assets/` (ONNX format)
- Processing in: `MFSRRefiner.cpp`

**Tasks:**
- [ ] Download Real-ESRGAN x4plus model (optimized for photos)
- [ ] Convert PyTorch model to ONNX format with dynamic input shapes
- [ ] Quantize model to FP16 for mobile performance
- [ ] Update `MFSRRefiner.cpp` to load new model
- [ ] Adjust tile size and overlap for Real-ESRGAN requirements
- [ ] Test on various image types (portraits, landscapes, text)

**Files to Modify:**
- `app/src/main/cpp/MFSRRefiner.cpp`
- `app/src/main/cpp/MFSRRefiner.h`
- `app/src/main/assets/` (add new model)

**Expected Improvement:**
- Better texture synthesis
- Reduced artifacts on faces
- Sharper edges without halos

---

### Task 1.2: Add SwinIR as Alternative SR Model
**Priority:** Medium  
**Estimated Effort:** 3-4 days

**Description:**
SwinIR uses Swin Transformer architecture for superior detail recovery.

**Tasks:**
- [ ] Download SwinIR lightweight model (SwinIR-S)
- [ ] Convert to ONNX with optimizations
- [ ] Implement model switching in pipeline
- [ ] Add user preference for SR model selection
- [ ] Benchmark performance vs Real-ESRGAN

**Files to Modify:**
- `app/src/main/cpp/MFSRRefiner.cpp`
- `app/src/main/cpp/MFSRRefiner.h`
- `app/src/main/java/com/imagedit/app/ultradetail/UltraDetailViewModel.kt`
- `app/src/main/java/com/imagedit/app/ultradetail/UltraDetailScreen.kt` (settings UI)

---

### Task 1.3: Implement HAT (Hybrid Attention Transformer)
**Priority:** Low (Future)  
**Estimated Effort:** 4-5 days

**Description:**
HAT combines channel attention and window-based self-attention for state-of-the-art SR.

**Tasks:**
- [ ] Evaluate HAT-S (small) model for mobile feasibility
- [ ] Convert to ONNX with INT8 quantization
- [ ] Implement as premium/high-quality option
- [ ] Add processing time estimates to UI

---

## Phase 2: Add Denoising AI

### Task 2.1: Integrate NAFNet for Denoising
**Priority:** High  
**Estimated Effort:** 3-4 days

**Description:**
NAFNet (Nonlinear Activation Free Network) provides excellent denoising with low computational cost.

**Current State:**
- No dedicated AI denoising
- Using basic bilateral filtering in `deghost_enhance.cpp`

**Tasks:**
- [ ] Download NAFNet-width32 model (balanced quality/speed)
- [ ] Convert to ONNX format
- [ ] Create new `AIDenoiser.cpp` class
- [ ] Integrate into pipeline before SR upscaling
- [ ] Add noise level estimation for adaptive denoising
- [ ] Test with high ISO images

**Files to Create:**
- `app/src/main/cpp/AIDenoiser.cpp`
- `app/src/main/cpp/AIDenoiser.h`

**Files to Modify:**
- `app/src/main/cpp/tiled_pipeline.cpp`
- `app/src/main/cpp/CMakeLists.txt`

**Expected Improvement:**
- Cleaner images from low-light captures
- Better detail preservation vs traditional denoising
- Reduced color noise

---

### Task 2.2: Add Restormer as Premium Denoiser
**Priority:** Medium  
**Estimated Effort:** 4-5 days

**Description:**
Restormer uses efficient Transformer blocks for high-quality restoration.

**Tasks:**
- [ ] Evaluate Restormer-lite for mobile
- [ ] Implement as optional high-quality denoising mode
- [ ] Add processing time indicator
- [ ] Compare quality vs NAFNet

---

## Phase 3: Pipeline Integration

### Task 3.1: Create Unified AI Pipeline Manager
**Priority:** High  
**Estimated Effort:** 2-3 days

**Tasks:**
- [ ] Create `AIPipelineManager` class to coordinate models
- [ ] Implement model loading/unloading for memory management
- [ ] Add pipeline configuration options
- [ ] Create preset profiles (Fast, Balanced, Quality)

**Files to Create:**
- `app/src/main/cpp/AIPipelineManager.cpp`
- `app/src/main/cpp/AIPipelineManager.h`

---

### Task 3.2: Add User Settings for AI Models
**Priority:** Medium  
**Estimated Effort:** 1-2 days

**Tasks:**
- [ ] Add AI model selection in Ultra Detail settings
- [ ] Show estimated processing time per model
- [ ] Add quality preview comparison
- [ ] Save user preferences

**Files to Modify:**
- `app/src/main/java/com/imagedit/app/ultradetail/UltraDetailScreen.kt`
- `app/src/main/java/com/imagedit/app/ultradetail/UltraDetailViewModel.kt`

---

## Phase 4: Optimization

### Task 4.1: GPU Acceleration with NNAPI
**Priority:** High  
**Estimated Effort:** 3-4 days

**Tasks:**
- [ ] Enable NNAPI delegate for ONNX Runtime
- [ ] Test on various Android devices
- [ ] Implement fallback to CPU for unsupported ops
- [ ] Benchmark GPU vs CPU performance

---

### Task 4.2: Model Quantization
**Priority:** Medium  
**Estimated Effort:** 2-3 days

**Tasks:**
- [ ] Quantize models to INT8 where quality permits
- [ ] Create FP16 versions for GPU execution
- [ ] Test quality degradation from quantization
- [ ] Implement dynamic precision selection

---

## Model Download Links (Reference)

| Model | Size | Link |
|-------|------|------|
| Real-ESRGAN x4plus | ~64MB | https://github.com/xinntao/Real-ESRGAN |
| SwinIR-S | ~12MB | https://github.com/JingyunLiang/SwinIR |
| NAFNet-width32 | ~8MB | https://github.com/megvii-research/NAFNet |
| Restormer-lite | ~26MB | https://github.com/swz30/Restormer |

---

## Timeline Estimate

| Phase | Duration | Dependencies |
|-------|----------|--------------|
| Phase 1.1 (Real-ESRGAN) | 2-3 days | None |
| Phase 2.1 (NAFNet) | 3-4 days | None |
| Phase 3.1 (Pipeline Manager) | 2-3 days | Phase 1.1, 2.1 |
| Phase 1.2 (SwinIR) | 3-4 days | Phase 3.1 |
| Phase 4.1 (GPU Acceleration) | 3-4 days | Phase 3.1 |
| Phase 3.2 (Settings UI) | 1-2 days | Phase 3.1 |

**Total Estimated Time:** 2-3 weeks for core features

---

## Notes

1. **Memory Management:** Each model requires ~100-200MB RAM when loaded. Implement lazy loading and unloading.

2. **Battery Impact:** AI models are computationally intensive. Consider adding battery-aware processing modes.

3. **Storage:** Total model storage ~120MB. Consider on-demand download for optional models.

4. **Testing:** Create benchmark suite with standard test images for quality comparison.
