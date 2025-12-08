# TFLite Super-Resolution Models

This directory contains TensorFlow Lite models for the Ultra Detail+ super-resolution feature.

## Required Model

Place one of the following models here:

### Option 1: ESRGAN (Recommended for quality)
- **File**: `esrgan.tflite`
- **Input**: `[1, 50, 50, 3]` float32 RGB normalized [0,1]
- **Output**: `[1, 200, 200, 3]` float32 RGB (4x upscale)
- **Download**: https://github.com/margaretmz/esrgan-e2e-tflite-tutorial/releases/download/v0.1.0/esrgan_fp16.tar.gz

### Option 2: Compressed ESRGAN (Recommended for speed)
- **File**: `esrgan.tflite`
- **Size**: ~33 KB
- **Download**: https://github.com/captain-pool/GSOC/releases/download/2.0.0/compressed_esrgan.tflite

## Download Instructions

### Windows (PowerShell)
```powershell
# Option 1: Full ESRGAN (fp16)
Invoke-WebRequest -Uri "https://github.com/margaretmz/esrgan-e2e-tflite-tutorial/releases/download/v0.1.0/esrgan_fp16.tar.gz" -OutFile "esrgan_fp16.tar.gz"
tar -xzf esrgan_fp16.tar.gz
Move-Item esrgan_fp16.tflite esrgan.tflite

# Option 2: Compressed ESRGAN (smaller, faster)
Invoke-WebRequest -Uri "https://github.com/captain-pool/GSOC/releases/download/2.0.0/compressed_esrgan.tflite" -OutFile "esrgan.tflite"
```

### macOS/Linux
```bash
# Option 1: Full ESRGAN (fp16)
curl -L -o esrgan_fp16.tar.gz https://github.com/margaretmz/esrgan-e2e-tflite-tutorial/releases/download/v0.1.0/esrgan_fp16.tar.gz
tar -xzf esrgan_fp16.tar.gz
mv esrgan_fp16.tflite esrgan.tflite

# Option 2: Compressed ESRGAN (smaller, faster)
curl -L -o esrgan.tflite https://github.com/captain-pool/GSOC/releases/download/2.0.0/compressed_esrgan.tflite
```

## Model Compatibility

The Ultra Detail+ pipeline expects:
- Input shape: `[1, H, W, 3]` where H and W are tile dimensions
- Output shape: `[1, H*scale, W*scale, 3]`
- Data type: float32
- Value range: [0, 1] normalized RGB

The ESRGAN model uses 50x50 input tiles with 4x upscale (200x200 output).
