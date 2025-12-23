# On-Demand Model Download System

## Overview

The app now supports **on-demand model downloads** instead of bundling large AI models in the APK. This reduces the initial app size and allows users to download only the models they need.

---

## How It Works

### 1. Model Storage

Models are stored in the app's private storage:
```
/data/data/com.imagedit.app/files/models/
├── realesrgan_x4plus_fp16.onnx (~33MB)
├── realesrgan_x4plus_anime_fp16.onnx (~33MB)
├── swinir_x4_fp16.onnx (~12MB)
└── esrgan.tflite (~20MB)
```

### 2. Download Flow

```
User opens Model Management → Clicks "Download" → Progress shown → Model saved → Ready to use
```

### 3. Pipeline Integration

The pipeline automatically checks for downloaded models:

```kotlin
// In UltraDetailPipeline.kt
val modelDownloader = ModelDownloader(context)
val modelPath = modelDownloader.getModelPath(AvailableModels.REAL_ESRGAN_X4_FP16)

if (modelPath != null) {
    // Use downloaded model
    onnxSR = OnnxSuperResolution(context, OnnxSRConfig(
        modelFilePath = modelPath
    ))
} else {
    // Fall back to bundled model or TFLite
}
```

---

## Available Models

| Model | Runtime | Size | Quality | Speed | Use Case |
|-------|---------|------|---------|-------|----------|
| **Real-ESRGAN x4plus** | ONNX | 33MB | Best | Slow | General photos |
| **Real-ESRGAN Anime** | ONNX | 33MB | Best | Slow | Anime/illustrations |
| **SwinIR x4** | ONNX | 12MB | Excellent | Medium | Balanced quality/speed |
| **ESRGAN FP16** | TFLite | 20MB | Good | Fast | Legacy fallback |
| **ESRGAN INT8** | TFLite | 5MB | Fair | Very Fast | Low-end devices |

---

## Model Hosting Setup

### Option 1: GitHub Releases (Recommended)

1. **Convert models** using Python scripts:
   ```bash
   cd scripts
   python convert_realesrgan_to_onnx.py
   python quantize_onnx_fp16.py
   ```

2. **Create a GitHub release:**
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```

3. **Upload models** to the release:
   - Go to GitHub → Releases → Create new release
   - Upload `realesrgan_x4plus_fp16.onnx`
   - Upload `realesrgan_x4plus_anime_fp16.onnx`
   - Upload `swinir_x4_fp16.onnx`

4. **Update URLs** in `ModelDownloader.kt`:
   ```kotlin
   val REAL_ESRGAN_X4_FP16 = ModelInfo(
       downloadUrl = "https://github.com/YOUR_USERNAME/SimpleImageApp/releases/download/v1.0.0/realesrgan_x4plus_fp16.onnx",
       // ...
   )
   ```

### Option 2: CDN/Cloud Storage

Use Firebase Storage, AWS S3, or any CDN:

```kotlin
val REAL_ESRGAN_X4_FP16 = ModelInfo(
    downloadUrl = "https://your-cdn.com/models/realesrgan_x4plus_fp16.onnx",
    // ...
)
```

### Option 3: Self-Hosted Server

Host on your own server:

```kotlin
val REAL_ESRGAN_X4_FP16 = ModelInfo(
    downloadUrl = "https://yourserver.com/api/models/realesrgan_x4plus_fp16.onnx",
    // ...
)
```

---

## User Experience

### Model Management Screen

Access via: **Settings → AI Models**

Features:
- ✅ View all available models
- ✅ See download status and storage usage
- ✅ Download models with progress indicator
- ✅ Delete models to free space
- ✅ Model runtime badges (ONNX/TFLite)

### First-Time User Flow

1. User installs app (~50MB without models)
2. User opens ULTRA mode
3. App shows: "Download Real-ESRGAN for best quality?"
4. User downloads model (~33MB)
5. Model ready for next capture

### Automatic Fallback

If model not downloaded:
```
Real-ESRGAN (ONNX) → TFLite ESRGAN → No SR (2x MFSR only)
```

User sees in logcat:
```
ONNX Real-ESRGAN not available (model not downloaded)
Falling back to TFLite ESRGAN
```

---

## Implementation Details

### ModelDownloader.kt

**Key methods:**
- `isModelAvailable(model)` - Check if downloaded
- `getModelPath(model)` - Get file path
- `downloadModel(model)` - Download with progress
- `deleteModel(model)` - Remove from storage

**Download states:**
```kotlin
sealed class DownloadState {
    object Idle
    object Checking
    data class Downloading(progress, downloadedMB, totalMB)
    object Extracting
    object Complete
    data class Error(message)
}
```

### OnnxSuperResolution.kt

**Supports two loading modes:**
```kotlin
// From downloaded file
OnnxSRConfig(modelFilePath = "/data/.../models/model.onnx")

// From bundled assets
OnnxSRConfig(model = OnnxSRModel.REAL_ESRGAN_X4)
```

### ModelManagementScreen.kt

**Compose UI features:**
- LazyColumn with model cards
- Download progress indicators
- Storage usage summary
- Delete confirmation dialogs

---

## Testing

### Test Download Flow

1. Open Model Management screen
2. Click "Download" on Real-ESRGAN
3. Verify progress indicator shows
4. Wait for "Downloaded" status
5. Check file exists: `/data/data/com.imagedit.app/files/models/`

### Test Pipeline Integration

1. Download Real-ESRGAN model
2. Open ULTRA mode
3. Capture image
4. Check logcat for: `"ONNX Real-ESRGAN initialized from downloaded model"`
5. Verify output quality improved

### Test Fallback

1. Delete Real-ESRGAN model
2. Open ULTRA mode
3. Capture image
4. Check logcat for: `"ONNX Real-ESRGAN not available"`
5. Verify TFLite ESRGAN used instead

---

## Network Requirements

### Permissions

Already included in manifest:
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

### Download Conditions

Consider implementing:
- WiFi-only downloads (optional)
- Pause/resume support
- Retry on failure
- Download queue

---

## Storage Management

### Disk Space Checks

Before download:
```kotlin
val availableSpace = context.filesDir.usableSpace
val requiredSpace = model.expectedSizeBytes * 1.2 // 20% buffer

if (availableSpace < requiredSpace) {
    emit(DownloadState.Error("Insufficient storage"))
}
```

### Cleanup Strategy

- User can manually delete models
- Consider auto-cleanup of unused models
- Warn if storage < 100MB

---

## Future Enhancements

### Phase 1 (Current)
- ✅ Basic download/delete
- ✅ Progress indicators
- ✅ Automatic fallback

### Phase 2 (Planned)
- [ ] Download queue
- [ ] Pause/resume
- [ ] WiFi-only option
- [ ] Model version updates

### Phase 3 (Advanced)
- [ ] Differential updates
- [ ] Model compression
- [ ] Background downloads
- [ ] Smart model recommendations

---

## Troubleshooting

### Download Fails

**Error:** "Download failed"
**Solution:** 
1. Check internet connection
2. Verify download URL is correct
3. Check GitHub release is public
4. Try again (network timeout)

### Model Not Loading

**Error:** "Failed to load model"
**Solution:**
1. Verify file exists in models directory
2. Check file size > 100KB
3. Re-download if corrupted
4. Check logcat for detailed error

### Out of Memory

**Error:** "Failed to initialize ONNX session"
**Solution:**
1. Close other apps
2. Use smaller model (SwinIR instead of Real-ESRGAN)
3. Reduce tile size in config

---

## Migration from Bundled Models

### Old Approach (Bundled)
```
APK size: 100MB (app + models)
Install time: Slow
First use: Instant
Updates: Full APK update
```

### New Approach (On-Demand)
```
APK size: 50MB (app only)
Install time: Fast
First use: Download required (~30s)
Updates: App or models separately
```

### Benefits

| Aspect | Improvement |
|--------|-------------|
| APK size | -50% (50MB vs 100MB) |
| Install time | -40% faster |
| Update flexibility | Models updated independently |
| User choice | Download only needed models |
| Storage efficiency | Delete unused models |

---

## API Reference

### ModelDownloader

```kotlin
class ModelDownloader(context: Context) {
    fun isModelAvailable(model: ModelInfo): Boolean
    fun getModelPath(model: ModelInfo): String?
    fun downloadModel(model: ModelInfo): Flow<DownloadState>
    fun deleteModel(model: ModelInfo): Boolean
}
```

### AvailableModels

```kotlin
object AvailableModels {
    val REAL_ESRGAN_X4_FP16: ModelInfo
    val REAL_ESRGAN_X4_ANIME_FP16: ModelInfo
    val SWINIR_X4_FP16: ModelInfo
    val ESRGAN_FP16: ModelInfo
    val ESRGAN_INT8: ModelInfo
}
```

### OnnxSRConfig

```kotlin
data class OnnxSRConfig(
    val model: OnnxSRModel,
    val tileSize: Int = 256,
    val overlap: Int = 16,
    val useGpu: Boolean = true,
    val numThreads: Int = 4,
    val modelFilePath: String? = null  // For downloaded models
)
```

---

## Summary

The on-demand model download system provides:

✅ **Smaller APK** - 50% size reduction  
✅ **User choice** - Download only needed models  
✅ **Flexibility** - Update models without app update  
✅ **Automatic fallback** - Works without downloads  
✅ **Easy management** - Simple UI for downloads  

**Next step:** Host models on GitHub Releases and update download URLs.
