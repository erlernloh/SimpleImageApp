#!/bin/bash
# prepare_release.sh - Prepare AI models for GitHub Release
# This script downloads, converts, and packages models for release

set -e  # Exit on error

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="$SCRIPT_DIR/release_models"
VERSION="${1:-v1.0.0}"

echo "=========================================="
echo "AI Model Release Preparation"
echo "Version: $VERSION"
echo "=========================================="
echo ""

# Create output directory
mkdir -p "$OUTPUT_DIR"
cd "$SCRIPT_DIR"

# Check Python dependencies
echo "Checking Python dependencies..."
if ! python -c "import torch" 2>/dev/null; then
    echo "Installing Python dependencies..."
    pip install -r requirements.txt
fi

echo ""
echo "=========================================="
echo "Step 1: Download Real-ESRGAN PyTorch Model"
echo "=========================================="

if [ ! -f "RealESRGAN_x4plus.pth" ]; then
    echo "Downloading Real-ESRGAN x4plus..."
    wget https://github.com/xinntao/Real-ESRGAN/releases/download/v0.1.0/RealESRGAN_x4plus.pth
else
    echo "✓ RealESRGAN_x4plus.pth already exists"
fi

if [ ! -f "RealESRGAN_x4plus_anime_6B.pth" ]; then
    echo "Downloading Real-ESRGAN x4plus anime..."
    wget https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.2.4/RealESRGAN_x4plus_anime_6B.pth
else
    echo "✓ RealESRGAN_x4plus_anime_6B.pth already exists"
fi

echo ""
echo "=========================================="
echo "Step 2: Convert to ONNX Format"
echo "=========================================="

# Convert Real-ESRGAN x4plus
if [ ! -f "realesrgan_x4plus.onnx" ]; then
    echo "Converting Real-ESRGAN x4plus to ONNX..."
    python convert_realesrgan_to_onnx.py -i RealESRGAN_x4plus.pth -o realesrgan_x4plus.onnx --verify
else
    echo "✓ realesrgan_x4plus.onnx already exists"
fi

# Convert Real-ESRGAN anime
if [ ! -f "realesrgan_x4plus_anime.onnx" ]; then
    echo "Converting Real-ESRGAN anime to ONNX..."
    python convert_realesrgan_to_onnx.py -i RealESRGAN_x4plus_anime_6B.pth -o realesrgan_x4plus_anime.onnx --verify
else
    echo "✓ realesrgan_x4plus_anime.onnx already exists"
fi

echo ""
echo "=========================================="
echo "Step 3: Quantize to FP16"
echo "=========================================="

# Quantize Real-ESRGAN x4plus
if [ ! -f "$OUTPUT_DIR/realesrgan_x4plus_fp16.onnx" ]; then
    echo "Quantizing Real-ESRGAN x4plus to FP16..."
    python quantize_onnx_fp16.py -i realesrgan_x4plus.onnx -o "$OUTPUT_DIR/realesrgan_x4plus_fp16.onnx" --verify
else
    echo "✓ realesrgan_x4plus_fp16.onnx already exists"
fi

# Quantize Real-ESRGAN anime
if [ ! -f "$OUTPUT_DIR/realesrgan_x4plus_anime_fp16.onnx" ]; then
    echo "Quantizing Real-ESRGAN anime to FP16..."
    python quantize_onnx_fp16.py -i realesrgan_x4plus_anime.onnx -o "$OUTPUT_DIR/realesrgan_x4plus_anime_fp16.onnx" --verify
else
    echo "✓ realesrgan_x4plus_anime_fp16.onnx already exists"
fi

echo ""
echo "=========================================="
echo "Step 4: Generate Checksums"
echo "=========================================="

cd "$OUTPUT_DIR"
sha256sum *.onnx > SHA256SUMS.txt
echo "✓ Checksums generated"

echo ""
echo "=========================================="
echo "Release Summary"
echo "=========================================="
echo ""
echo "Output directory: $OUTPUT_DIR"
echo ""
echo "Files ready for release:"
ls -lh "$OUTPUT_DIR"
echo ""
echo "Total size:"
du -sh "$OUTPUT_DIR"
echo ""
echo "=========================================="
echo "Next Steps:"
echo "=========================================="
echo ""
echo "1. Create a git tag:"
echo "   git tag $VERSION"
echo "   git push origin $VERSION"
echo ""
echo "2. Go to GitHub → Releases → Draft a new release"
echo ""
echo "3. Upload these files from $OUTPUT_DIR:"
echo "   - realesrgan_x4plus_fp16.onnx"
echo "   - realesrgan_x4plus_anime_fp16.onnx"
echo "   - SHA256SUMS.txt"
echo ""
echo "4. Update ModelDownloader.kt with release URLs"
echo ""
echo "Done!"
