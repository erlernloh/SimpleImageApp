# Real-ESRGAN Integration Guide

## Overview
This guide covers downloading, converting, and integrating Real-ESRGAN x4plus model for improved super-resolution in the Ultra Detail+ feature.

---

## Part 1: Model Download and Conversion

### Prerequisites
```bash
pip install torch torchvision onnx onnxruntime
pip install basicsr realesrgan
```

### Step 1: Download Real-ESRGAN Model

**Option A: Pre-trained PyTorch Model**
```bash
# Download Real-ESRGAN x4plus (general photo model)
wget https://github.com/xinntao/Real-ESRGAN/releases/download/v0.1.0/RealESRGAN_x4plus.pth

# Or use the anime model for sharper results
wget https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.2.4/RealESRGAN_x4plus_anime_6B.pth
```

**Recommended:** Use `RealESRGAN_x4plus.pth` for general photos (64MB)

### Step 2: Convert PyTorch to ONNX

Create `convert_realesrgan_to_onnx.py`:

```python
import torch
import torch.onnx
from basicsr.archs.rrdbnet_arch import RRDBNet

def convert_to_onnx():
    # Model configuration for Real-ESRGAN x4plus
    model = RRDBNet(
        num_in_ch=3,
        num_out_ch=3,
        num_feat=64,
        num_block=23,
        num_grow_ch=32,
        scale=4
    )
    
    # Load pre-trained weights
    checkpoint = torch.load('RealESRGAN_x4plus.pth')
    
    # Handle different checkpoint formats
    if 'params_ema' in checkpoint:
        model.load_state_dict(checkpoint['params_ema'], strict=True)
    elif 'params' in checkpoint:
        model.load_state_dict(checkpoint['params'], strict=True)
    else:
        model.load_state_dict(checkpoint, strict=True)
    
    model.eval()
    
    # Create dummy input (tile size for mobile: 256x256)
    dummy_input = torch.randn(1, 3, 256, 256)
    
    # Export to ONNX with dynamic axes for flexible tile sizes
    torch.onnx.export(
        model,
        dummy_input,
        "realesrgan_x4plus.onnx",
        export_params=True,
        opset_version=14,  # Use opset 14 for better mobile compatibility
        do_constant_folding=True,
        input_names=['input'],
        output_names=['output'],
        dynamic_axes={
            'input': {0: 'batch', 2: 'height', 3: 'width'},
            'output': {0: 'batch', 2: 'height', 3: 'width'}
        }
    )
    
    print("✓ ONNX model exported: realesrgan_x4plus.onnx")
    print(f"  Model size: {os.path.getsize('realesrgan_x4plus.onnx') / 1024 / 1024:.1f} MB")

if __name__ == "__main__":
    convert_to_onnx()
```

Run conversion:
```bash
python convert_realesrgan_to_onnx.py
```

### Step 3: Optimize for Mobile (FP16 Quantization)

Create `quantize_onnx_fp16.py`:

```python
import onnx
from onnxruntime.quantization import quantize_dynamic, QuantType
from onnxmltools.utils.float16_converter import convert_float_to_float16

def quantize_to_fp16():
    # Load ONNX model
    model = onnx.load("realesrgan_x4plus.onnx")
    
    # Convert to FP16
    model_fp16 = convert_float_to_float16(model)
    
    # Save FP16 model
    onnx.save(model_fp16, "realesrgan_x4plus_fp16.onnx")
    
    print("✓ FP16 model created: realesrgan_x4plus_fp16.onnx")
    print(f"  Original size: {os.path.getsize('realesrgan_x4plus.onnx') / 1024 / 1024:.1f} MB")
    print(f"  FP16 size: {os.path.getsize('realesrgan_x4plus_fp16.onnx') / 1024 / 1024:.1f} MB")

if __name__ == "__main__":
    quantize_to_fp16()
```

Run quantization:
```bash
pip install onnxmltools
python quantize_onnx_fp16.py
```

### Step 4: Verify ONNX Model

Create `verify_onnx_model.py`:

```python
import onnxruntime as ort
import numpy as np
from PIL import Image

def verify_model():
    # Load ONNX model
    session = ort.InferenceSession("realesrgan_x4plus_fp16.onnx")
    
    # Get input/output info
    input_name = session.get_inputs()[0].name
    output_name = session.get_outputs()[0].name
    
    print(f"Input name: {input_name}")
    print(f"Input shape: {session.get_inputs()[0].shape}")
    print(f"Output name: {output_name}")
    print(f"Output shape: {session.get_outputs()[0].shape}")
    
    # Test with dummy input
    dummy_input = np.random.randn(1, 3, 256, 256).astype(np.float32)
    
    # Run inference
    output = session.run([output_name], {input_name: dummy_input})[0]
    
    print(f"\n✓ Model verification successful!")
    print(f"  Input shape: {dummy_input.shape}")
    print(f"  Output shape: {output.shape}")
    print(f"  Expected output shape: (1, 3, 1024, 1024)")
    
    assert output.shape == (1, 3, 1024, 1024), "Output shape mismatch!"

if __name__ == "__main__":
    verify_model()
```

Run verification:
```bash
python verify_onnx_model.py
```

---

## Part 2: Android Integration

### Step 1: Copy Model to Assets

```bash
# Copy the FP16 model to Android assets
cp realesrgan_x4plus_fp16.onnx app/src/main/assets/realesrgan_x4plus_fp16.onnx
```

### Step 2: Update MFSRRefiner.kt

The model loading code needs to be updated to use the new model file.

**Current model:** `esrgan_4x.onnx` (basic ESRGAN)
**New model:** `realesrgan_x4plus_fp16.onnx` (Real-ESRGAN)

### Step 3: Adjust Tile Size

Real-ESRGAN works best with:
- **Tile size:** 256x256 (input) → 1024x1024 (output)
- **Overlap:** 32 pixels (to avoid seams)

---

## Part 3: Performance Considerations

### Memory Usage

| Model | Size | RAM Usage (per tile) |
|-------|------|---------------------|
| Basic ESRGAN | ~17MB | ~50MB |
| Real-ESRGAN FP32 | ~64MB | ~200MB |
| Real-ESRGAN FP16 | ~32MB | ~100MB |

**Recommendation:** Use FP16 for mobile devices.

### Processing Time (Snapdragon 730G)

| Tile Size | FP32 | FP16 | GPU (NNAPI) |
|-----------|------|------|-------------|
| 128x128 | ~80ms | ~50ms | ~30ms |
| 256x256 | ~300ms | ~180ms | ~100ms |
| 512x512 | ~1200ms | ~700ms | ~400ms |

**Recommendation:** Use 256x256 tiles with GPU acceleration.

---

## Part 4: Quality Comparison

### Expected Improvements

| Aspect | Basic ESRGAN | Real-ESRGAN |
|--------|--------------|-------------|
| Texture synthesis | Basic | Excellent |
| Edge sharpness | Moderate | Sharp |
| Face details | Poor | Good |
| Artifacts | Some halos | Minimal |
| Natural look | Moderate | High |

---

## Part 5: Testing Checklist

- [ ] Model downloads successfully
- [ ] ONNX conversion completes without errors
- [ ] FP16 quantization reduces size by ~50%
- [ ] Model verification passes
- [ ] Model loads in Android app
- [ ] Inference produces 4x upscaled output
- [ ] Visual quality improved vs basic ESRGAN
- [ ] Processing time acceptable (<500ms per tile on mid-range device)
- [ ] No memory leaks during repeated inference
- [ ] GPU acceleration works (if available)

---

## Troubleshooting

### Issue: ONNX export fails
**Solution:** Ensure PyTorch and ONNX versions are compatible:
```bash
pip install torch==1.13.1 onnx==1.14.0
```

### Issue: Model too large for mobile
**Solution:** Use INT8 quantization (may reduce quality):
```python
from onnxruntime.quantization import quantize_dynamic
quantize_dynamic("realesrgan_x4plus.onnx", "realesrgan_x4plus_int8.onnx", weight_type=QuantType.QUInt8)
```

### Issue: Inference too slow
**Solutions:**
1. Reduce tile size to 128x128
2. Enable NNAPI GPU delegate
3. Use FP16 instead of FP32
4. Consider using a lighter model (e.g., SwinIR-S)

---

## Next Steps

After successful integration:
1. Add model selection in settings (Basic ESRGAN vs Real-ESRGAN)
2. Implement adaptive tile sizing based on device capability
3. Add progress indicators for long processing
4. Benchmark on various devices
5. Consider adding SwinIR as alternative (Phase 1.2)

---

## References

- Real-ESRGAN GitHub: https://github.com/xinntao/Real-ESRGAN
- ONNX Runtime: https://onnxruntime.ai/
- Model Zoo: https://github.com/xinntao/Real-ESRGAN/blob/master/docs/model_zoo.md
