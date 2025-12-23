# AI Model Integration Status

## Current Status: Phase 1.1 - Real-ESRGAN Integration (In Progress)

Last Updated: 2025-12-23

---

## Completed Steps

### ✅ 1. Code Infrastructure
- [x] Created `OnnxSuperResolution.kt` - ONNX Runtime wrapper for Real-ESRGAN
- [x] Updated `RefinerModel` enum to support both TFLite and ONNX models
- [x] Added ONNX Runtime dependency to `build.gradle.kts`
- [x] Created model conversion guide (`REAL_ESRGAN_INTEGRATION_GUIDE.md`)

### ✅ 2. Documentation
- [x] Created comprehensive Real-ESRGAN integration guide
- [x] Documented model conversion process (PyTorch → ONNX → FP16)
- [x] Added performance benchmarks and memory requirements
- [x] Created testing checklist

---

## Pending Steps

### 🔄 3. Model Preparation (USER ACTION REQUIRED)

**You need to download and convert the Real-ESRGAN model:**

1. **Install Python dependencies:**
   ```bash
   pip install torch torchvision onnx onnxruntime basicsr realesrgan onnxmltools
   ```

2. **Download Real-ESRGAN model:**
   ```bash
   wget https://github.com/xinntao/Real-ESRGAN/releases/download/v0.1.0/RealESRGAN_x4plus.pth
   ```

3. **Convert to ONNX (use scripts in `REAL_ESRGAN_INTEGRATION_GUIDE.md`):**
   - Run `convert_realesrgan_to_onnx.py` → creates `realesrgan_x4plus.onnx`
   - Run `quantize_onnx_fp16.py` → creates `realesrgan_x4plus_fp16.onnx`
   - Run `verify_onnx_model.py` → verify model works

4. **Copy to Android assets:**
   ```bash
   cp realesrgan_x4plus_fp16.onnx app/src/main/assets/realesrgan_x4plus_fp16.onnx
   ```

**Expected model size:** ~32MB (FP16 quantized)

---

### 🔄 4. Integration into Pipeline

**Files to modify:**

#### A. Update `UltraDetailPipeline.kt`
Add option to use ONNX-based refiner:

```kotlin
// In processUltraPreset() or processMaxPreset()
val refiner = if (useRealESRGAN) {
    OnnxSuperResolution(context, OnnxSRConfig(
        model = OnnxSRModel.REAL_ESRGAN_X4,
        tileSize = 256,
        overlap = 16,
        useGpu = true
    ))
} else {
    MFSRRefiner(context, RefinerConfig(
        model = RefinerModel.ESRGAN_REFINE
    ))
}
```

#### B. Update `UltraDetailViewModel.kt`
Add model selection preference:

```kotlin
data class UltraDetailSettings(
    // ... existing settings
    val srModel: SRModelType = SRModelType.REAL_ESRGAN,
    val useGpuAcceleration: Boolean = true
)

enum class SRModelType {
    BASIC_ESRGAN,      // TFLite, faster but lower quality
    REAL_ESRGAN,       // ONNX, best quality
    REAL_ESRGAN_ANIME, // ONNX, sharper for anime/illustrations
    SWINIR             // ONNX, transformer-based (future)
}
```

#### C. Update `UltraDetailScreen.kt`
Add model selection in settings UI:

```kotlin
// In settings dialog
Row {
    Text("SR Model:")
    DropdownMenu {
        SRModelType.values().forEach { model ->
            DropdownMenuItem(
                text = { Text(model.description) },
                onClick = { viewModel.setSRModel(model) }
            )
        }
    }
}
```

---

### 🔄 5. Testing

**Test checklist:**

- [ ] Model loads successfully on device
- [ ] ONNX Runtime initializes without errors
- [ ] GPU acceleration works (NNAPI delegate)
- [ ] Tile-based processing completes without OOM
- [ ] Output quality better than basic ESRGAN
- [ ] Processing time acceptable (<500ms per 256x256 tile)
- [ ] No memory leaks during repeated use
- [ ] Blending between tiles seamless (no visible seams)

**Test images:**
- Portrait photo (face details)
- Landscape photo (texture synthesis)
- Low-light photo (noise handling)
- Text/document (edge sharpness)

---

## Performance Targets

### Real-ESRGAN vs Basic ESRGAN

| Metric | Basic ESRGAN | Real-ESRGAN | Target |
|--------|--------------|-------------|--------|
| Quality (subjective) | 6/10 | 9/10 | 8+/10 |
| Texture synthesis | Basic | Excellent | Good+ |
| Face details | Poor | Good | Good |
| Processing time (256x256 tile) | ~120ms | ~180ms | <300ms |
| Model size | 17MB | 32MB | <50MB |
| Memory usage | 50MB | 100MB | <150MB |

### Device Compatibility

| Device Tier | Model | Tile Size | Expected Time (10 tiles) |
|-------------|-------|-----------|--------------------------|
| High-end (SD 8 Gen 2+) | Real-ESRGAN | 512x512 | ~2s |
| Mid-range (SD 730G) | Real-ESRGAN | 256x256 | ~5s |
| Low-end (SD 660) | Basic ESRGAN | 128x128 | ~8s |

---

## Known Issues & Limitations

### Current Limitations

1. **Model not included in repo**
   - Real-ESRGAN model is 32MB - too large for git
   - User must download and convert separately
   - Consider hosting on release page or CDN

2. **ONNX Runtime size**
   - Adds ~10MB to APK size
   - Worth it for quality improvement

3. **GPU acceleration**
   - NNAPI support varies by device
   - Need fallback to CPU if GPU fails
   - Some devices have buggy NNAPI implementations

### Potential Issues

- **OOM on low-end devices:** Use smaller tile size (128x128)
- **Slow processing:** Reduce tile count or use basic ESRGAN
- **Model loading failure:** Check asset path and file exists
- **NNAPI crashes:** Disable GPU and use CPU only

---

## Next Steps (Priority Order)

1. **Download and convert Real-ESRGAN model** (USER ACTION)
2. **Test OnnxSuperResolution class** with converted model
3. **Integrate into UltraDetailPipeline** with feature flag
4. **Add model selection UI** in settings
5. **Benchmark on target device** (Snapdragon 730G)
6. **Compare quality** with basic ESRGAN side-by-side
7. **Optimize tile size** based on device capability
8. **Add progress indicators** for long processing

---

## Alternative Approaches (If Real-ESRGAN Too Slow)

### Option A: Hybrid Approach
- Use basic ESRGAN for preview
- Use Real-ESRGAN for final export only
- Let user choose speed vs quality

### Option B: Lighter Model
- Use Real-ESRGAN x2 instead of x4
- Combine with MFSR 4x for same total upscale
- Faster processing, still better than basic ESRGAN

### Option C: Progressive Enhancement
- Process at lower resolution first
- Show preview immediately
- Continue processing at full resolution in background

---

## Future Enhancements (Phase 1.2+)

### SwinIR Integration
- Transformer-based SR model
- Better detail recovery than CNN-based models
- Requires more memory but produces excellent results

### NAFNet Denoising
- Pre-process before SR for cleaner input
- Especially useful for low-light photos
- Can be combined with any SR model

### Model Caching
- Keep model in memory between captures
- Faster subsequent processing
- Trade memory for speed

---

## References

- Real-ESRGAN: https://github.com/xinntao/Real-ESRGAN
- ONNX Runtime Android: https://onnxruntime.ai/docs/tutorials/mobile/
- Model Zoo: https://github.com/xinntao/Real-ESRGAN/blob/master/docs/model_zoo.md
- Integration Guide: `docs/REAL_ESRGAN_INTEGRATION_GUIDE.md`
