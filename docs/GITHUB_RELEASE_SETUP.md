# GitHub Release Setup Guide

Complete guide for hosting AI models on GitHub Releases.

---

## Quick Start (Windows)

```powershell
cd scripts
.\prepare_release.ps1 -Version "v1.0.0"
```

This will:
1. Download PyTorch models (~130MB)
2. Convert to ONNX format
3. Quantize to FP16 (~33MB each)
4. Generate checksums
5. Output files to `scripts/release_models/`

---

## Step-by-Step Guide

### Step 1: Prepare Models Locally

**Windows:**
```powershell
cd scripts
.\prepare_release.ps1
```

**Linux/Mac:**
```bash
cd scripts
chmod +x prepare_release.sh
./prepare_release.sh
```

**Expected output:**
```
scripts/release_models/
├── realesrgan_x4plus_fp16.onnx          (~33 MB)
├── realesrgan_x4plus_anime_fp16.onnx    (~33 MB)
└── SHA256SUMS.txt
```

### Step 2: Create Git Tag

```bash
# Create and push tag
git tag v1.0.0 -m "Release v1.0.0 - AI Models"
git push origin v1.0.0
```

### Step 3: Create GitHub Release

1. **Go to your repository on GitHub**
   ```
   https://github.com/YOUR_USERNAME/SimpleImageApp
   ```

2. **Click "Releases" → "Draft a new release"**

3. **Fill in release details:**
   - **Tag:** `v1.0.0` (select existing tag)
   - **Title:** `AI Models v1.0.0`
   - **Description:**
     ```markdown
     # AI Models for SimpleImageApp
     
     This release contains optimized ONNX models for on-demand download.
     
     ## Models Included
     
     - **Real-ESRGAN x4plus (FP16)** - Best quality upscaling (~33 MB)
     - **Real-ESRGAN x4plus Anime (FP16)** - Optimized for anime/illustrations (~33 MB)
     
     ## Installation
     
     Models are automatically downloaded through the app's Model Management screen.
     
     ## Checksums
     
     See `SHA256SUMS.txt` for file verification.
     ```

4. **Upload files:**
   - Drag and drop from `scripts/release_models/`:
     - `realesrgan_x4plus_fp16.onnx`
     - `realesrgan_x4plus_anime_fp16.onnx`
     - `SHA256SUMS.txt`

5. **Publish release**

### Step 4: Update Download URLs

After publishing, GitHub will generate download URLs. Update `ModelDownloader.kt`:

```kotlin
// In AvailableModels object
val REAL_ESRGAN_X4_FP16 = ModelInfo(
    name = "Real-ESRGAN x4plus (FP16)",
    fileName = "realesrgan_x4plus_fp16.onnx",
    downloadUrl = "https://github.com/YOUR_USERNAME/SimpleImageApp/releases/download/v1.0.0/realesrgan_x4plus_fp16.onnx",
    expectedSizeBytes = 33_000_000L,
    description = "Best quality 4x upscaling. Downloads ~33MB.",
    runtime = ModelRuntime.ONNX
)

val REAL_ESRGAN_X4_ANIME_FP16 = ModelInfo(
    name = "Real-ESRGAN x4plus Anime (FP16)",
    fileName = "realesrgan_x4plus_anime_fp16.onnx",
    downloadUrl = "https://github.com/YOUR_USERNAME/SimpleImageApp/releases/download/v1.0.0/realesrgan_x4plus_anime_fp16.onnx",
    expectedSizeBytes = 33_000_000L,
    description = "Sharper 4x upscaling for anime/illustrations. Downloads ~33MB.",
    runtime = ModelRuntime.ONNX
)
```

**Replace `YOUR_USERNAME` with your actual GitHub username!**

### Step 5: Test Download

1. Build and install app
2. Open Model Management screen
3. Click "Download" on Real-ESRGAN
4. Verify download completes
5. Check file exists: `/data/data/com.imagedit.app/files/models/`

---

## URL Format

GitHub Release URLs follow this pattern:
```
https://github.com/{owner}/{repo}/releases/download/{tag}/{filename}
```

**Example:**
```
https://github.com/john-doe/SimpleImageApp/releases/download/v1.0.0/realesrgan_x4plus_fp16.onnx
```

---

## Troubleshooting

### Issue: "Failed to download model"

**Cause:** Release is not public or URL is incorrect

**Solution:**
1. Verify release is published (not draft)
2. Check URL in browser - should download file
3. Ensure no typos in `ModelDownloader.kt`

### Issue: "Model file is too small"

**Cause:** Downloaded HTML error page instead of model

**Solution:**
1. Check release is public
2. Verify URL points to actual file, not release page
3. Test URL with `curl` or browser

### Issue: "Conversion script fails"

**Cause:** Missing Python dependencies

**Solution:**
```bash
pip install -r requirements.txt
```

If still fails:
```bash
pip install torch torchvision onnx onnxruntime basicsr realesrgan --upgrade
```

---

## Alternative: Manual Upload

If scripts don't work, you can manually prepare models:

### 1. Download Pre-converted Models

Real-ESRGAN provides pre-converted ONNX models:
```bash
# Download from official Real-ESRGAN releases
wget https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesrgan-x4plus.onnx
```

### 2. Quantize to FP16

```python
import onnx
from onnxmltools.utils.float16_converter import convert_float_to_float16

model = onnx.load("realesrgan-x4plus.onnx")
model_fp16 = convert_float_to_float16(model)
onnx.save(model_fp16, "realesrgan_x4plus_fp16.onnx")
```

### 3. Upload to GitHub Release

Follow Step 3 above.

---

## Best Practices

### Release Naming

Use semantic versioning:
- `v1.0.0` - Initial release
- `v1.1.0` - New models added
- `v1.0.1` - Model bug fixes

### File Naming

Keep consistent with `ModelDownloader.kt`:
- `realesrgan_x4plus_fp16.onnx` (not `RealESRGAN_x4plus_fp16.onnx`)
- Use lowercase and underscores
- Include precision suffix (`_fp16`, `_int8`)

### Checksums

Always include `SHA256SUMS.txt` for verification:
```bash
sha256sum *.onnx > SHA256SUMS.txt
```

Users can verify:
```bash
sha256sum -c SHA256SUMS.txt
```

### Model Updates

When updating models:
1. Create new release tag (`v1.1.0`)
2. Upload new models
3. Update `ModelDownloader.kt` URLs
4. Keep old releases for backward compatibility

---

## GitHub Actions (Optional)

Automate model preparation with GitHub Actions:

```yaml
# .github/workflows/release-models.yml
name: Prepare Model Release

on:
  push:
    tags:
      - 'v*'

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Set up Python
        uses: actions/setup-python@v4
        with:
          python-version: '3.10'
      
      - name: Install dependencies
        run: |
          cd scripts
          pip install -r requirements.txt
      
      - name: Prepare models
        run: |
          cd scripts
          ./prepare_release.sh ${{ github.ref_name }}
      
      - name: Upload to release
        uses: softprops/action-gh-release@v1
        with:
          files: scripts/release_models/*
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

This automatically:
- Triggers on new tags
- Converts models
- Uploads to release

---

## Storage Limits

GitHub Release limits:
- **File size:** 2 GB per file ✅ (our models are ~33 MB)
- **Release size:** No limit on total size
- **Bandwidth:** Unlimited for public repos

Our models are well within limits.

---

## Private Repositories

If your repo is private:

### Option 1: Make Release Public

1. Keep repo private
2. Make specific releases public
3. Users can download without authentication

### Option 2: Use Personal Access Token

```kotlin
// In ModelDownloader.kt
connection.setRequestProperty("Authorization", "token YOUR_PAT")
```

**Not recommended** - tokens can be extracted from APK.

### Option 3: Use Alternative Hosting

- Firebase Storage
- AWS S3
- Google Cloud Storage
- Your own server

---

## Cost Analysis

### GitHub Releases (Free)
- ✅ Free for public repos
- ✅ Unlimited bandwidth
- ✅ No setup required
- ❌ Requires GitHub account

### Alternative: CDN
- ❌ Costs money ($0.01-0.10 per GB)
- ✅ Faster global delivery
- ✅ No GitHub dependency
- ✅ Custom domain

**Recommendation:** Start with GitHub Releases, migrate to CDN if needed.

---

## Testing Checklist

After setting up release:

- [ ] Download URL works in browser
- [ ] File size matches expected (~33 MB)
- [ ] SHA256 checksum matches
- [ ] App can download model
- [ ] Model loads successfully
- [ ] Image processing works
- [ ] Logcat shows correct model loaded

---

## Example: Complete Setup

```bash
# 1. Prepare models
cd scripts
.\prepare_release.ps1

# 2. Create tag
git tag v1.0.0 -m "AI Models v1.0.0"
git push origin v1.0.0

# 3. Create release on GitHub
# (Upload files from scripts/release_models/)

# 4. Get download URL
# https://github.com/YOUR_USERNAME/SimpleImageApp/releases/download/v1.0.0/realesrgan_x4plus_fp16.onnx

# 5. Update ModelDownloader.kt
# Replace YOUR_USERNAME with actual username

# 6. Test
# Build app → Model Management → Download → Verify
```

---

## Summary

**Setup time:** ~15 minutes  
**Storage used:** ~70 MB (2 models + checksums)  
**Cost:** $0 (GitHub Releases free)  
**Maintenance:** Update URLs when releasing new models  

**Result:** Users can download high-quality AI models on-demand, keeping your APK small and flexible.

---

## Next Steps

1. ✅ Run `prepare_release.ps1` to create models
2. ✅ Create GitHub Release and upload files
3. ✅ Update `ModelDownloader.kt` with actual URLs
4. ✅ Test download in app
5. ✅ Commit URL changes
6. ✅ Build and distribute app

Done! Your on-demand model system is ready.
