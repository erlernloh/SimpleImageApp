# Download ESRGAN TFLite model for Ultra Detail+ super-resolution
# Run this script from the project root directory

$ErrorActionPreference = "Stop"

$assetsDir = "app\src\main\assets\models"
$modelFile = "$assetsDir\esrgan.tflite"

# Create assets/models directory if it doesn't exist
if (-not (Test-Path $assetsDir)) {
    New-Item -ItemType Directory -Path $assetsDir -Force | Out-Null
    Write-Host "Created directory: $assetsDir"
}

# Check if model already exists
if (Test-Path $modelFile) {
    Write-Host "Model already exists at: $modelFile"
    $response = Read-Host "Do you want to re-download? (y/N)"
    if ($response -ne "y" -and $response -ne "Y") {
        Write-Host "Skipping download."
        exit 0
    }
}

Write-Host ""
Write-Host "Select model variant:"
Write-Host "  1. Compressed ESRGAN (33 KB) - Faster, smaller, recommended for mobile"
Write-Host "  2. Full ESRGAN FP16 (~5 MB) - Higher quality, slower"
Write-Host ""
$choice = Read-Host "Enter choice (1 or 2)"

switch ($choice) {
    "1" {
        Write-Host ""
        Write-Host "Downloading compressed ESRGAN model..."
        $url = "https://github.com/captain-pool/GSOC/releases/download/2.0.0/compressed_esrgan.tflite"
        Invoke-WebRequest -Uri $url -OutFile $modelFile
    }
    "2" {
        Write-Host ""
        Write-Host "Downloading full ESRGAN FP16 model..."
        $tempFile = "$assetsDir\esrgan_fp16.tar.gz"
        $url = "https://github.com/margaretmz/esrgan-e2e-tflite-tutorial/releases/download/v0.1.0/esrgan_fp16.tar.gz"
        
        Invoke-WebRequest -Uri $url -OutFile $tempFile
        
        Write-Host "Extracting..."
        Push-Location $assetsDir
        tar -xzf "esrgan_fp16.tar.gz"
        
        # Rename to expected filename
        if (Test-Path "esrgan_fp16.tflite") {
            Move-Item -Force "esrgan_fp16.tflite" "esrgan.tflite"
        }
        
        # Cleanup
        Remove-Item "esrgan_fp16.tar.gz" -ErrorAction SilentlyContinue
        Pop-Location
    }
    default {
        Write-Host "Invalid choice. Exiting."
        exit 1
    }
}

if (Test-Path $modelFile) {
    $fileSize = (Get-Item $modelFile).Length
    Write-Host ""
    Write-Host "Success! Model downloaded to: $modelFile"
    Write-Host "File size: $([math]::Round($fileSize / 1KB, 2)) KB"
    Write-Host ""
    Write-Host "The Ultra Detail+ feature is now ready to use."
} else {
    Write-Host "Error: Model file was not created."
    exit 1
}
