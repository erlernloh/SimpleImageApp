@echo off
REM prepare_release.bat - Prepare AI models for GitHub Release (Windows)

set VERSION=v1.0.0
if not "%1"=="" set VERSION=%1

echo ==========================================
echo AI Model Release Preparation
echo Version: %VERSION%
echo ==========================================
echo.

cd /d "%~dp0"
mkdir release_models 2>nul

echo Checking Python dependencies...
python -c "import torch" 2>nul
if errorlevel 1 (
    echo Installing Python dependencies...
    pip install -r requirements.txt
) else (
    echo [OK] Python dependencies installed
)

echo.
echo ==========================================
echo Step 1: Download Real-ESRGAN PyTorch Models
echo ==========================================

if not exist "RealESRGAN_x4plus.pth" (
    echo Downloading Real-ESRGAN x4plus...
    curl -L -o RealESRGAN_x4plus.pth https://github.com/xinntao/Real-ESRGAN/releases/download/v0.1.0/RealESRGAN_x4plus.pth
) else (
    echo [OK] RealESRGAN_x4plus.pth already exists
)

if not exist "RealESRGAN_x4plus_anime_6B.pth" (
    echo Downloading Real-ESRGAN x4plus anime...
    curl -L -o RealESRGAN_x4plus_anime_6B.pth https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.2.4/RealESRGAN_x4plus_anime_6B.pth
) else (
    echo [OK] RealESRGAN_x4plus_anime_6B.pth already exists
)

echo.
echo ==========================================
echo Step 2: Convert to ONNX Format
echo ==========================================

if not exist "realesrgan_x4plus.onnx" (
    echo Converting Real-ESRGAN x4plus to ONNX...
    python convert_realesrgan_simple.py -i RealESRGAN_x4plus.pth -o realesrgan_x4plus.onnx --verify
) else (
    echo [OK] realesrgan_x4plus.onnx already exists
)

if not exist "realesrgan_x4plus_anime.onnx" (
    echo Converting Real-ESRGAN anime to ONNX...
    python convert_realesrgan_simple.py -i RealESRGAN_x4plus_anime_6B.pth -o realesrgan_x4plus_anime.onnx --verify
) else (
    echo [OK] realesrgan_x4plus_anime.onnx already exists
)

echo.
echo ==========================================
echo Step 3: Quantize to FP16
echo ==========================================

if not exist "release_models\realesrgan_x4plus_fp16.onnx" (
    echo Quantizing Real-ESRGAN x4plus to FP16...
    python quantize_onnx_fp16.py -i realesrgan_x4plus.onnx -o release_models\realesrgan_x4plus_fp16.onnx --verify
) else (
    echo [OK] realesrgan_x4plus_fp16.onnx already exists
)

if not exist "release_models\realesrgan_x4plus_anime_fp16.onnx" (
    echo Quantizing Real-ESRGAN anime to FP16...
    python quantize_onnx_fp16.py -i realesrgan_x4plus_anime.onnx -o release_models\realesrgan_x4plus_anime_fp16.onnx --verify
) else (
    echo [OK] realesrgan_x4plus_anime_fp16.onnx already exists
)

echo.
echo ==========================================
echo Step 4: Generate Checksums
echo ==========================================

cd release_models
certutil -hashfile realesrgan_x4plus_fp16.onnx SHA256 | findstr /v ":" > SHA256SUMS.txt
certutil -hashfile realesrgan_x4plus_anime_fp16.onnx SHA256 | findstr /v ":" >> SHA256SUMS.txt
echo [OK] Checksums generated
cd ..

echo.
echo ==========================================
echo Release Summary
echo ==========================================
echo.
echo Output directory: %~dp0release_models
echo.
echo Files ready for release:
dir /b release_models
echo.
echo ==========================================
echo Next Steps:
echo ==========================================
echo.
echo 1. Create a git tag:
echo    git tag %VERSION%
echo    git push origin %VERSION%
echo.
echo 2. Go to GitHub - Releases - Draft a new release
echo.
echo 3. Upload these files from release_models:
echo    - realesrgan_x4plus_fp16.onnx
echo    - realesrgan_x4plus_anime_fp16.onnx
echo    - SHA256SUMS.txt
echo.
echo 4. Update ModelDownloader.kt with release URLs
echo.
echo Done!
pause
