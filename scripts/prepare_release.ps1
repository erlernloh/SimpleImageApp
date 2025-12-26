# prepare_release.ps1 - Prepare AI models for GitHub Release (Windows)
# This script downloads, converts, and packages models for release

param(
    [string]$Version = "v1.0.0"
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$OutputDir = Join-Path $ScriptDir "release_models"

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "AI Model Release Preparation" -ForegroundColor Cyan
Write-Host "Version: $Version" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""

# Create output directory
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
Set-Location $ScriptDir

# Check Python dependencies
Write-Host "Checking Python dependencies..." -ForegroundColor Yellow
$pythonCheck = python -c "import torch" 2>&1
if ($LASTEXITCODE -eq 0) {
    Write-Host "✓ Python dependencies OK" -ForegroundColor Green
} else {
    Write-Host "Installing Python dependencies..." -ForegroundColor Yellow
    pip install -r requirements.txt
}

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Step 1: Download Real-ESRGAN PyTorch Models" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

# Download Real-ESRGAN x4plus
if (-not (Test-Path "RealESRGAN_x4plus.pth")) {
    Write-Host "Downloading Real-ESRGAN x4plus..." -ForegroundColor Yellow
    Invoke-WebRequest -Uri "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.1.0/RealESRGAN_x4plus.pth" -OutFile "RealESRGAN_x4plus.pth"
} else {
    Write-Host "✓ RealESRGAN_x4plus.pth already exists" -ForegroundColor Green
}

# Download Real-ESRGAN anime
if (-not (Test-Path "RealESRGAN_x4plus_anime_6B.pth")) {
    Write-Host "Downloading Real-ESRGAN x4plus anime..." -ForegroundColor Yellow
    Invoke-WebRequest -Uri "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.2.4/RealESRGAN_x4plus_anime_6B.pth" -OutFile "RealESRGAN_x4plus_anime_6B.pth"
} else {
    Write-Host "✓ RealESRGAN_x4plus_anime_6B.pth already exists" -ForegroundColor Green
}

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Step 2: Convert to ONNX Format" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

# Convert Real-ESRGAN x4plus
if (-not (Test-Path "realesrgan_x4plus.onnx")) {
    Write-Host "Converting Real-ESRGAN x4plus to ONNX..." -ForegroundColor Yellow
    python convert_realesrgan_to_onnx.py -i RealESRGAN_x4plus.pth -o realesrgan_x4plus.onnx --verify
} else {
    Write-Host "✓ realesrgan_x4plus.onnx already exists" -ForegroundColor Green
}

# Convert Real-ESRGAN anime
if (-not (Test-Path "realesrgan_x4plus_anime.onnx")) {
    Write-Host "Converting Real-ESRGAN anime to ONNX..." -ForegroundColor Yellow
    python convert_realesrgan_to_onnx.py -i RealESRGAN_x4plus_anime_6B.pth -o realesrgan_x4plus_anime.onnx --verify
} else {
    Write-Host "✓ realesrgan_x4plus_anime.onnx already exists" -ForegroundColor Green
}

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Step 3: Quantize to FP16" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

# Quantize Real-ESRGAN x4plus
$outputFile1 = Join-Path $OutputDir "realesrgan_x4plus_fp16.onnx"
if (-not (Test-Path $outputFile1)) {
    Write-Host "Quantizing Real-ESRGAN x4plus to FP16..." -ForegroundColor Yellow
    python quantize_onnx_fp16.py -i realesrgan_x4plus.onnx -o $outputFile1 --verify
} else {
    Write-Host "✓ realesrgan_x4plus_fp16.onnx already exists" -ForegroundColor Green
}

# Quantize Real-ESRGAN anime
$outputFile2 = Join-Path $OutputDir "realesrgan_x4plus_anime_fp16.onnx"
if (-not (Test-Path $outputFile2)) {
    Write-Host "Quantizing Real-ESRGAN anime to FP16..." -ForegroundColor Yellow
    python quantize_onnx_fp16.py -i realesrgan_x4plus_anime.onnx -o $outputFile2 --verify
} else {
    Write-Host "✓ realesrgan_x4plus_anime_fp16.onnx already exists" -ForegroundColor Green
}

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Step 4: Generate Checksums" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

Set-Location $OutputDir
Get-ChildItem -Filter "*.onnx" | ForEach-Object {
    $hash = Get-FileHash -Algorithm SHA256 -Path $_.FullName
    "$($hash.Hash.ToLower())  $($_.Name)" | Out-File -Append -Encoding utf8 SHA256SUMS.txt
}
Write-Host "✓ Checksums generated" -ForegroundColor Green

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Release Summary" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Output directory: $OutputDir" -ForegroundColor White
Write-Host ""
Write-Host "Files ready for release:" -ForegroundColor White
Get-ChildItem $OutputDir | Format-Table Name, @{Label="Size (MB)"; Expression={[math]::Round($_.Length / 1MB, 2)}}
Write-Host ""

$totalSize = (Get-ChildItem $OutputDir -Recurse | Measure-Object -Property Length -Sum).Sum / 1MB
Write-Host "Total size: $([math]::Round($totalSize, 2)) MB" -ForegroundColor White
Write-Host ""

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Next Steps:" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "1. Create a git tag:" -ForegroundColor Yellow
Write-Host "   git tag $Version" -ForegroundColor White
Write-Host "   git push origin $Version" -ForegroundColor White
Write-Host ""
Write-Host "2. Go to GitHub → Releases → Draft a new release" -ForegroundColor Yellow
Write-Host ""
Write-Host "3. Upload these files from $OutputDir" -ForegroundColor Yellow
Write-Host "   - realesrgan_x4plus_fp16.onnx" -ForegroundColor White
Write-Host "   - realesrgan_x4plus_anime_fp16.onnx" -ForegroundColor White
Write-Host "   - SHA256SUMS.txt" -ForegroundColor White
Write-Host ""
Write-Host "4. Update ModelDownloader.kt with release URLs" -ForegroundColor Yellow
Write-Host ""
Write-Host "Done!" -ForegroundColor Green
