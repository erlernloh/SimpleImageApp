/**
 * ultradetail_jni.cpp - JNI bridge for Ultra Detail+ pipeline
 * 
 * Exposes native burst processing functions to Kotlin/Java.
 */

#include <jni.h>
#include <android/bitmap.h>
#include "burst_processor.h"
#include "yuv_converter.h"
#include "tiled_pipeline.h"
#include "freq_separation.h"
#include "anisotropic_merge.h"
#include "orb_alignment.h"
#include "drizzle.h"
#include "rolling_shutter.h"
#include "kalman_fusion.h"
#include "texture_synthesis.h"
#include "texture_synthesis_tiled.h"
#include "exposure_fusion.h"

using namespace ultradetail;

// Global processor instance (thread-safe usage required from Kotlin side)
static std::unique_ptr<BurstProcessor> g_processor;
static JavaVM* g_jvm = nullptr;

// JNI callback helper
struct JNIProgressCallback {
    JNIEnv* env;
    jobject callback;
    jmethodID methodId;
    
    void operator()(ProcessingStage stage, float progress, const char* message) {
        if (callback && methodId) {
            jstring jMessage = env->NewStringUTF(message);
            env->CallVoidMethod(callback, methodId,
                               static_cast<jint>(stage),
                               static_cast<jfloat>(progress),
                               jMessage);
            env->DeleteLocalRef(jMessage);
        }
    }
};

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    LOGI("UltraDetail JNI loaded");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    g_processor.reset();
    LOGI("UltraDetail JNI unloaded");
}

/**
 * Initialize the burst processor with parameters
 */
JNIEXPORT jlong JNICALL
Java_com_imagedit_app_ultradetail_NativeBurstProcessor_nativeCreate(
    JNIEnv* env,
    jclass clazz,
    jint alignmentTileSize,
    jint searchRadius,
    jint pyramidLevels,
    jint mergeMethod,
    jfloat trimRatio,
    jboolean applyWiener,
    jint detailTileSize,
    jfloat detailThreshold,
    jboolean enableMFSR,
    jint mfsrScaleFactor
) {
    BurstProcessorParams params;
    
    // Alignment params
    params.alignment.tileSize = alignmentTileSize;
    params.alignment.searchRadius = searchRadius;
    params.alignment.pyramidLevels = pyramidLevels;
    
    // Merge params
    params.merge.method = static_cast<MergeMethod>(mergeMethod);
    params.merge.trimRatio = trimRatio;
    params.merge.applyWienerFilter = applyWiener;
    
    // Detail mask params
    params.detailMask.tileSize = detailTileSize;
    params.detailMask.detailThreshold = detailThreshold;
    
    // MFSR params
    params.enableMFSR = enableMFSR;
    params.mfsr.scaleFactor = mfsrScaleFactor;
    params.mfsr.tileSize = alignmentTileSize;  // Use same tile size as alignment
    
    auto* processor = new BurstProcessor(params);
    
    LOGD("Created BurstProcessor: tile=%d, search=%d, levels=%d, MFSR=%s (scale=%d)",
         alignmentTileSize, searchRadius, pyramidLevels,
         enableMFSR ? "enabled" : "disabled", mfsrScaleFactor);
    
    return reinterpret_cast<jlong>(processor);
}

/**
 * Destroy the burst processor
 */
JNIEXPORT void JNICALL
Java_com_imagedit_app_ultradetail_NativeBurstProcessor_nativeDestroy(
    JNIEnv* env,
    jclass clazz,
    jlong handle
) {
    auto* processor = reinterpret_cast<BurstProcessor*>(handle);
    delete processor;
    LOGD("Destroyed BurstProcessor");
}

/**
 * Process YUV frames from CameraX
 * 
 * @param handle Processor handle
 * @param yPlanes Array of Y plane ByteBuffers
 * @param uPlanes Array of U plane ByteBuffers
 * @param vPlanes Array of V plane ByteBuffers
 * @param yRowStrides Y plane row strides
 * @param uvRowStrides UV plane row strides
 * @param uvPixelStrides UV pixel strides
 * @param width Image width
 * @param height Image height
 * @param outputBitmap Output bitmap (ARGB_8888)
 * @param callback Progress callback
 * @return Processing result code (0 = success)
 */
JNIEXPORT jint JNICALL
Java_com_imagedit_app_ultradetail_NativeBurstProcessor_nativeProcessYUV(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jobjectArray yPlanes,
    jobjectArray uPlanes,
    jobjectArray vPlanes,
    jintArray yRowStrides,
    jintArray uvRowStrides,
    jintArray uvPixelStrides,
    jint width,
    jint height,
    jobject outputBitmap,
    jobject callback
) {
    auto* processor = reinterpret_cast<BurstProcessor*>(handle);
    if (!processor) {
        LOGE("Invalid processor handle");
        return -1;
    }
    
    int numFrames = env->GetArrayLength(yPlanes);
    if (numFrames < 2) {
        LOGE("Need at least 2 frames, got %d", numFrames);
        return -2;
    }
    
    LOGD("Processing %d YUV frames (%dx%d)", numFrames, width, height);
    
    // Get stride arrays
    jint* yStrides = env->GetIntArrayElements(yRowStrides, nullptr);
    jint* uvStrides = env->GetIntArrayElements(uvRowStrides, nullptr);
    jint* uvPixStrides = env->GetIntArrayElements(uvPixelStrides, nullptr);
    
    // Build YUV frame array
    std::vector<YUVFrame> frames(numFrames);
    std::vector<jobject> yBuffers(numFrames);
    std::vector<jobject> uBuffers(numFrames);
    std::vector<jobject> vBuffers(numFrames);
    
    for (int i = 0; i < numFrames; ++i) {
        yBuffers[i] = env->GetObjectArrayElement(yPlanes, i);
        uBuffers[i] = env->GetObjectArrayElement(uPlanes, i);
        vBuffers[i] = env->GetObjectArrayElement(vPlanes, i);
        
        frames[i].yPlane = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuffers[i]));
        frames[i].uPlane = static_cast<const uint8_t*>(env->GetDirectBufferAddress(uBuffers[i]));
        frames[i].vPlane = static_cast<const uint8_t*>(env->GetDirectBufferAddress(vBuffers[i]));
        frames[i].yRowStride = yStrides[i];
        frames[i].uvRowStride = uvStrides[i];
        frames[i].uvPixelStride = uvPixStrides[i];
        frames[i].width = width;
        frames[i].height = height;
    }
    
    // Setup progress callback
    JNIProgressCallback progressCallback{nullptr, nullptr, nullptr};
    if (callback) {
        progressCallback.env = env;
        progressCallback.callback = callback;
        jclass callbackClass = env->GetObjectClass(callback);
        progressCallback.methodId = env->GetMethodID(callbackClass, "onProgress",
                                                      "(IFLjava/lang/String;)V");
    }
    
    // Process
    BurstProcessingResult result;
    processor->process(frames, result,
        [&progressCallback](ProcessingStage stage, float progress, const char* msg) {
            if (progressCallback.callback) {
                progressCallback(stage, progress, msg);
            }
        }
    );
    
    // Release stride arrays
    env->ReleaseIntArrayElements(yRowStrides, yStrides, JNI_ABORT);
    env->ReleaseIntArrayElements(uvRowStrides, uvStrides, JNI_ABORT);
    env->ReleaseIntArrayElements(uvPixelStrides, uvPixStrides, JNI_ABORT);
    
    if (!result.success) {
        LOGE("Processing failed: %s", result.errorMessage.c_str());
        return -3;
    }
    
    // Copy result to output bitmap
    AndroidBitmapInfo bitmapInfo;
    if (AndroidBitmap_getInfo(env, outputBitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to get bitmap info");
        return -4;
    }
    
    if (bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Output bitmap must be ARGB_8888");
        return -5;
    }
    
    void* bitmapPixels;
    if (AndroidBitmap_lockPixels(env, outputBitmap, &bitmapPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to lock bitmap pixels");
        return -6;
    }
    
    // Verify merged image has valid data
    LOGD("Merged image: %dx%d, bitmap: %dx%d stride=%d, MFSR=%s",
         result.mergedImage.width, result.mergedImage.height,
         bitmapInfo.width, bitmapInfo.height, bitmapInfo.stride,
         result.mfsrApplied ? "applied" : "not applied");
    
    // Check if bitmap size matches result size
    if (result.mergedImage.width != static_cast<int>(bitmapInfo.width) ||
        result.mergedImage.height != static_cast<int>(bitmapInfo.height)) {
        LOGW("Bitmap size mismatch: result=%dx%d, bitmap=%dx%d",
             result.mergedImage.width, result.mergedImage.height,
             bitmapInfo.width, bitmapInfo.height);
        // If MFSR was applied but bitmap is wrong size, this is an error
        // The caller should have allocated the correct size
        if (result.mfsrApplied) {
            AndroidBitmap_unlockPixels(env, outputBitmap);
            LOGE("Output bitmap size doesn't match MFSR output");
            return -7;
        }
    }
    
    // Compute and log image statistics for diagnostics
    // This uses severity-based logging: HEALTHY=INFO, minor issues=WARN, serious=ERROR
    ImageStats stats = computeImageStats(result.mergedImage);
    stats.log("Merged image");
    
    // Handle invalid values - this is a SAFETY NET, not a fix
    // Non-zero counts here indicate upstream bugs that should be investigated
    if (!stats.isHealthy()) {
        // Log as ERROR because this indicates a bug in the pipeline
        LOGE("PIPELINE BUG: Merged image contains %d NaN + %d Inf values (%.4f%% invalid)",
             stats.nanCount, stats.infCount, stats.invalidPercentage());
        LOGE("This is a bug that should be fixed upstream. Sanitizing as safety measure...");
        
        int sanitized = sanitizeRGBImage(result.mergedImage);
        LOGW("Sanitized %d pixels (replaced NaN/Inf with black, clamped to [0,1])", sanitized);
        
        // Verify sanitization worked
        ImageStats postStats = computeImageStats(result.mergedImage);
        if (postStats.isHealthy()) {
            LOGI("Post-sanitization: image is now numerically healthy");
        } else {
            LOGE("Post-sanitization: STILL UNHEALTHY - this should not happen!");
        }
    }
    
#if ULTRADETAIL_DEBUG
    // Sample a few pixels for debugging (only in debug builds)
    if (result.mergedImage.width > 0 && result.mergedImage.height > 0) {
        int cx = result.mergedImage.width / 2;
        int cy = result.mergedImage.height / 2;
        const RGBPixel& centerPx = result.mergedImage.at(cx, cy);
        LOGD_DEBUG("Center pixel (%d,%d): R=%.3f G=%.3f B=%.3f",
             cx, cy, centerPx.r, centerPx.g, centerPx.b);
    }
#endif
    
    // Convert float RGB to RGBA bitmap
    rgbFloatToArgb(result.mergedImage, static_cast<uint8_t*>(bitmapPixels), bitmapInfo.stride);
    
    AndroidBitmap_unlockPixels(env, outputBitmap);
    
    // Final status log with acceptance criteria
    bool passesAcceptance = stats.isHealthy();
    LOGI("Processing complete: %.1f ms, MFSR=%s, Acceptance=%s", 
         result.processingTimeMs,
         result.mfsrApplied ? "applied" : "not applied",
         passesAcceptance ? "PASS" : "FAIL (NaN/Inf detected)");
    
    return 0;
}

/**
 * Get MFSR result information after processing
 * 
 * @param handle Processor handle
 * @param outputInfo Output array [mfsrApplied, scaleFactor, outputWidth, outputHeight, coveragePercent]
 * @return 0 on success, -1 on error
 */
JNIEXPORT jint JNICALL
Java_com_imagedit_app_ultradetail_NativeBurstProcessor_nativeGetMFSRInfo(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jfloatArray outputInfo
) {
    auto* processor = reinterpret_cast<BurstProcessor*>(handle);
    if (!processor || !processor->hasResult()) {
        LOGW("No valid result available for MFSR info");
        return -1;
    }
    
    const auto& result = processor->getLastResult();
    
    if (outputInfo != nullptr && env->GetArrayLength(outputInfo) >= 5) {
        jfloat* info = env->GetFloatArrayElements(outputInfo, nullptr);
        info[0] = result.mfsrApplied ? 1.0f : 0.0f;
        info[1] = static_cast<float>(result.mfsrScaleFactor);
        info[2] = static_cast<float>(result.mergedImage.width);
        info[3] = static_cast<float>(result.mergedImage.height);
        info[4] = result.mfsrCoverage * 100.0f;  // As percentage
        env->ReleaseFloatArrayElements(outputInfo, info, 0);
    }
    
    LOGD("MFSR info: applied=%d, scale=%d, size=%dx%d, coverage=%.1f%%",
         result.mfsrApplied, result.mfsrScaleFactor,
         result.mergedImage.width, result.mergedImage.height,
         result.mfsrCoverage * 100.0f);
    
    return 0;
}

/**
 * Get the detail mask after processing
 * 
 * @param handle Processor handle
 * @param outputMask Output byte array for tile mask
 * @param dimensions Output array [tilesX, tilesY]
 * @return Number of detail tiles, or -1 on error
 */
JNIEXPORT jint JNICALL
Java_com_imagedit_app_ultradetail_NativeBurstProcessor_nativeGetDetailMask(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jbyteArray outputMask,
    jintArray dimensions
) {
    auto* processor = reinterpret_cast<BurstProcessor*>(handle);
    if (!processor || !processor->hasResult()) {
        LOGW("No valid result available for detail mask");
        return -1;
    }
    
    const auto& result = processor->getLastResult();
    const auto& mask = result.detailMask;
    
    // Check if detail mask was computed
    if (mask.tileMask.width == 0 || mask.tileMask.height == 0) {
        LOGW("Detail mask not computed");
        return -1;
    }
    
    int tilesX = mask.tileMask.width;
    int tilesY = mask.tileMask.height;
    int maskSize = tilesX * tilesY;
    
    // Set dimensions output
    if (dimensions != nullptr && env->GetArrayLength(dimensions) >= 2) {
        jint* dims = env->GetIntArrayElements(dimensions, nullptr);
        dims[0] = tilesX;
        dims[1] = tilesY;
        env->ReleaseIntArrayElements(dimensions, dims, 0);
    }
    
    // Copy mask data to output array
    if (outputMask != nullptr) {
        int outputLen = env->GetArrayLength(outputMask);
        int copyLen = std::min(maskSize, outputLen);
        
        jbyte* maskData = env->GetByteArrayElements(outputMask, nullptr);
        for (int i = 0; i < copyLen; ++i) {
            maskData[i] = static_cast<jbyte>(mask.tileMask.data[i]);
        }
        env->ReleaseByteArrayElements(outputMask, maskData, 0);
    }
    
    LOGD("Detail mask retrieved: %dx%d tiles, %d detail tiles",
         tilesX, tilesY, mask.numDetailTiles);
    
    return mask.numDetailTiles;
}

/**
 * Cancel ongoing processing
 */
JNIEXPORT void JNICALL
Java_com_imagedit_app_ultradetail_NativeBurstProcessor_nativeCancel(
    JNIEnv* env,
    jobject thiz,
    jlong handle
) {
    auto* processor = reinterpret_cast<BurstProcessor*>(handle);
    if (processor) {
        processor->cancel();
        LOGD("Processing cancelled");
    }
}

/**
 * Process a single bitmap for edge detection (for testing/preview)
 */
JNIEXPORT jint JNICALL
Java_com_imagedit_app_ultradetail_NativeBurstProcessor_nativeComputeEdgeMask(
    JNIEnv* env,
    jobject thiz,
    jobject inputBitmap,
    jbyteArray outputMask,
    jint tileSize,
    jfloat threshold
) {
    AndroidBitmapInfo bitmapInfo;
    if (AndroidBitmap_getInfo(env, inputBitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return -1;
    }
    
    void* bitmapPixels;
    if (AndroidBitmap_lockPixels(env, inputBitmap, &bitmapPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return -2;
    }
    
    int width = bitmapInfo.width;
    int height = bitmapInfo.height;
    
    // Convert bitmap to grayscale
    GrayImage luminance(width, height);
    uint8_t* pixels = static_cast<uint8_t*>(bitmapPixels);
    
    for (int y = 0; y < height; ++y) {
        uint8_t* row = pixels + y * bitmapInfo.stride;
        float* lumRow = luminance.row(y);
        
        for (int x = 0; x < width; ++x) {
            // Android ARGB_8888 format: R, G, B, A (in memory order)
            int idx = x * 4;
            float r = row[idx + 0] / 255.0f;
            float g = row[idx + 1] / 255.0f;
            float b = row[idx + 2] / 255.0f;
            lumRow[x] = 0.299f * r + 0.587f * g + 0.114f * b;
        }
    }
    
    AndroidBitmap_unlockPixels(env, inputBitmap);
    
    // Compute edge mask
    DetailMaskParams params;
    params.tileSize = tileSize;
    params.detailThreshold = threshold;
    
    EdgeDetector detector(params);
    DetailMask mask;
    detector.detectDetails(luminance, mask);
    
    // Copy mask to output array
    int maskSize = mask.tileMask.width * mask.tileMask.height;
    jbyte* maskData = env->GetByteArrayElements(outputMask, nullptr);
    
    for (int i = 0; i < maskSize && i < env->GetArrayLength(outputMask); ++i) {
        maskData[i] = static_cast<jbyte>(mask.tileMask.data[i]);
    }
    
    env->ReleaseByteArrayElements(outputMask, maskData, 0);
    
    LOGD("Edge mask computed: %d detail tiles, %d smooth tiles",
         mask.numDetailTiles, mask.numSmoothTiles);
    
    return mask.numDetailTiles;
}

// ============================================================================
// TiledMFSRPipeline JNI bindings
// ============================================================================

/**
 * Create a TiledMFSRPipeline instance
 */
JNIEXPORT jlong JNICALL
Java_com_imagedit_app_ultradetail_NativeMFSRPipeline_nativeCreate(
    JNIEnv* env,
    jclass clazz,
    jint tileWidth,
    jint tileHeight,
    jint overlap,
    jint scaleFactor,
    jint robustnessMethod,
    jfloat robustnessThreshold,
    jboolean useGyroInit
) {
    TilePipelineConfig config;
    config.tileWidth = tileWidth;
    config.tileHeight = tileHeight;
    config.overlap = overlap;
    config.scaleFactor = scaleFactor;
    config.robustness = static_cast<TilePipelineConfig::RobustnessMethod>(robustnessMethod);
    config.robustnessThreshold = robustnessThreshold;
    config.useGyroInit = useGyroInit;
    
    // Update MFSR params to match
    config.mfsrParams.scaleFactor = scaleFactor;
    
    auto* pipeline = new TiledMFSRPipeline(config);
    
    LOGI("Created TiledMFSRPipeline: tile=%dx%d, overlap=%d, scale=%d",
         tileWidth, tileHeight, overlap, scaleFactor);
    
    return reinterpret_cast<jlong>(pipeline);
}

/**
 * Destroy a TiledMFSRPipeline instance
 */
JNIEXPORT void JNICALL
Java_com_imagedit_app_ultradetail_NativeMFSRPipeline_nativeDestroy(
    JNIEnv* env,
    jclass clazz,
    jlong handle
) {
    auto* pipeline = reinterpret_cast<TiledMFSRPipeline*>(handle);
    delete pipeline;
    LOGD("TiledMFSRPipeline destroyed");
}

/**
 * Process RGB bitmaps through the MFSR pipeline
 * 
 * @param handle Pipeline handle
 * @param inputBitmaps Array of input Bitmap objects
 * @param referenceIndex Index of reference frame
 * @param homographies Flattened 3x3 homography matrices (9 floats per frame), or null
 * @param outputBitmap Pre-allocated output bitmap (scaled size)
 * @param callback Progress callback object
 * @return Result code (0 = success)
 */
JNIEXPORT jint JNICALL
Java_com_imagedit_app_ultradetail_NativeMFSRPipeline_nativeProcessBitmaps(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jobjectArray inputBitmaps,
    jint referenceIndex,
    jfloatArray homographies,
    jobject outputBitmap,
    jobject callback
) {
    auto* pipeline = reinterpret_cast<TiledMFSRPipeline*>(handle);
    if (!pipeline) {
        LOGE("Invalid pipeline handle");
        return -1;
    }
    
    int numFrames = env->GetArrayLength(inputBitmaps);
    if (numFrames < 2) {
        LOGE("Need at least 2 frames for MFSR");
        return -2;
    }
    
    // Get first bitmap info
    jobject firstBitmap = env->GetObjectArrayElement(inputBitmaps, 0);
    AndroidBitmapInfo bitmapInfo;
    if (AndroidBitmap_getInfo(env, firstBitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to get bitmap info");
        return -3;
    }
    
    // Verify bitmap format
    if (bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Input bitmap format is %d, expected RGBA_8888 (%d)", 
             bitmapInfo.format, ANDROID_BITMAP_FORMAT_RGBA_8888);
        return -3;
    }
    
    int width = bitmapInfo.width;
    int height = bitmapInfo.height;
    
    LOGI("Processing %d frames (%dx%d, format=%d, stride=%d) through MFSR pipeline", 
         numFrames, width, height, bitmapInfo.format, bitmapInfo.stride);
    
    // Convert bitmaps to RGBImage format
    std::vector<RGBImage> frames(numFrames);
    std::vector<GrayImage> grayFrames(numFrames);
    
    for (int i = 0; i < numFrames; ++i) {
        jobject bitmap = env->GetObjectArrayElement(inputBitmaps, i);
        
        void* pixels;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
            LOGE("Failed to lock bitmap %d", i);
            return -4;
        }
        
        frames[i].resize(width, height);
        grayFrames[i].resize(width, height);
        
        uint8_t* src = static_cast<uint8_t*>(pixels);
        
        // Debug: log first pixel of first frame
        if (i == 0) {
            uint8_t* firstRow = src;
            LOGI("Frame 0 first pixel bytes: [%d, %d, %d, %d]", 
                 firstRow[0], firstRow[1], firstRow[2], firstRow[3]);
        }
        
        for (int y = 0; y < height; ++y) {
            uint8_t* row = src + y * bitmapInfo.stride;
            for (int x = 0; x < width; ++x) {
                int idx = x * 4;
                // Android ARGB_8888 format: R, G, B, A (in memory order)
                float r = row[idx + 0] / 255.0f;
                float g = row[idx + 1] / 255.0f;
                float b = row[idx + 2] / 255.0f;
                
                frames[i].at(x, y) = RGBPixel(r, g, b);
                grayFrames[i].at(x, y) = 0.299f * r + 0.587f * g + 0.114f * b;
            }
        }
        
        // Debug: log average pixel value of first frame
        if (i == 0) {
            float avgR = 0, avgG = 0, avgB = 0;
            for (int y = 0; y < height; y += 10) {
                for (int x = 0; x < width; x += 10) {
                    const RGBPixel& p = frames[i].at(x, y);
                    avgR += p.r;
                    avgG += p.g;
                    avgB += p.b;
                }
            }
            int samples = (height / 10) * (width / 10);
            LOGI("Frame 0 avg RGB (sampled): %.3f, %.3f, %.3f", 
                 avgR / samples, avgG / samples, avgB / samples);
        }
        
        AndroidBitmap_unlockPixels(env, bitmap);
    }
    
    // Parse homographies if provided
    std::vector<GyroHomography> gyroHomographies;
    if (homographies != nullptr) {
        int homLen = env->GetArrayLength(homographies);
        if (homLen >= numFrames * 9) {
            jfloat* homData = env->GetFloatArrayElements(homographies, nullptr);
            
            for (int i = 0; i < numFrames; ++i) {
                GyroHomography gh;
                for (int j = 0; j < 9; ++j) {
                    gh.h[j] = homData[i * 9 + j];
                }
                gh.isValid = true;
                gyroHomographies.push_back(gh);
            }
            
            env->ReleaseFloatArrayElements(homographies, homData, JNI_ABORT);
        }
    }
    
    // Setup progress callback
    jmethodID progressMethod = nullptr;
    if (callback != nullptr) {
        jclass callbackClass = env->GetObjectClass(callback);
        progressMethod = env->GetMethodID(callbackClass, "onProgress", 
                                          "(IILjava/lang/String;F)V");
    }
    
    // Process
    PipelineResult result;
    pipeline->process(
        frames, grayFrames, referenceIndex,
        gyroHomographies.empty() ? nullptr : &gyroHomographies,
        result,
        [&](int tile, int total, const char* msg, float progress) {
            if (callback && progressMethod) {
                jstring jMsg = env->NewStringUTF(msg);
                env->CallVoidMethod(callback, progressMethod, tile, total, jMsg, progress);
                env->DeleteLocalRef(jMsg);
            }
        }
    );
    
    if (!result.success) {
        LOGE("MFSR processing failed");
        return -5;
    }
    
    // Copy result to output bitmap
    AndroidBitmapInfo outInfo;
    if (AndroidBitmap_getInfo(env, outputBitmap, &outInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to get output bitmap info");
        return -6;
    }
    
    if (outInfo.width != static_cast<uint32_t>(result.outputWidth) ||
        outInfo.height != static_cast<uint32_t>(result.outputHeight)) {
        LOGE("Output bitmap size mismatch: expected %dx%d, got %dx%d",
             result.outputWidth, result.outputHeight, outInfo.width, outInfo.height);
        return -7;
    }
    
    void* outPixels;
    if (AndroidBitmap_lockPixels(env, outputBitmap, &outPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to lock output bitmap");
        return -8;
    }
    
    uint8_t* dst = static_cast<uint8_t*>(outPixels);
    
    // Debug: compute output average before writing
    float outAvgR = 0, outAvgG = 0, outAvgB = 0;
    int outSamples = 0;
    for (int y = 0; y < result.outputHeight; y += 20) {
        for (int x = 0; x < result.outputWidth; x += 20) {
            const RGBPixel& p = result.outputImage.at(x, y);
            outAvgR += p.r;
            outAvgG += p.g;
            outAvgB += p.b;
            outSamples++;
        }
    }
    LOGI("Output avg RGB before write: %.3f, %.3f, %.3f", 
         outAvgR / outSamples, outAvgG / outSamples, outAvgB / outSamples);
    
    for (int y = 0; y < result.outputHeight; ++y) {
        uint8_t* row = dst + y * outInfo.stride;
        for (int x = 0; x < result.outputWidth; ++x) {
            const RGBPixel& p = result.outputImage.at(x, y);
            int idx = x * 4;
            // Android ARGB_8888 format: R, G, B, A (in memory order)
            row[idx + 0] = static_cast<uint8_t>(clamp(p.r * 255.0f, 0.0f, 255.0f));
            row[idx + 1] = static_cast<uint8_t>(clamp(p.g * 255.0f, 0.0f, 255.0f));
            row[idx + 2] = static_cast<uint8_t>(clamp(p.b * 255.0f, 0.0f, 255.0f));
            row[idx + 3] = 255;  // Alpha
        }
    }
    
    AndroidBitmap_unlockPixels(env, outputBitmap);
    
    LOGI("MFSR complete: %dx%d -> %dx%d, tiles=%d, time=%.1fs, fallback=%s",
         result.inputWidth, result.inputHeight,
         result.outputWidth, result.outputHeight,
         result.tilesProcessed, result.processingTimeMs / 1000.0f,
         result.usedFallback ? "yes" : "no");
    
    return 0;
}

/**
 * Process bitmaps with quality mask for pixel weighting
 * 
 * The quality mask provides per-pixel weights (0-1) computed from RGB overlay alignment.
 * Pixels with higher quality values are given more weight during MFSR accumulation.
 * 
 * @param handle Pipeline handle
 * @param inputBitmaps Array of input Bitmap objects
 * @param referenceIndex Index of reference frame
 * @param homographies Flattened 3x3 homography matrices (9 floats per frame), or null
 * @param qualityMask Per-pixel quality weights (0-1), same size as input frames
 * @param maskWidth Width of quality mask
 * @param maskHeight Height of quality mask
 * @param outputBitmap Pre-allocated output bitmap (scaled size)
 * @param callback Progress callback object
 * @return Result code (0 = success)
 */
JNIEXPORT jint JNICALL
Java_com_imagedit_app_ultradetail_NativeMFSRPipeline_nativeProcessBitmapsWithMask(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jobjectArray inputBitmaps,
    jint referenceIndex,
    jfloatArray homographies,
    jfloatArray qualityMask,
    jint maskWidth,
    jint maskHeight,
    jobject outputBitmap,
    jobject callback
) {
    auto* pipeline = reinterpret_cast<TiledMFSRPipeline*>(handle);
    if (!pipeline) {
        LOGE("Invalid pipeline handle");
        return -1;
    }
    
    int numFrames = env->GetArrayLength(inputBitmaps);
    if (numFrames < 2) {
        LOGE("Need at least 2 frames for MFSR");
        return -2;
    }
    
    // Get quality mask data
    jfloat* maskData = nullptr;
    int maskLen = 0;
    if (qualityMask != nullptr) {
        maskLen = env->GetArrayLength(qualityMask);
        maskData = env->GetFloatArrayElements(qualityMask, nullptr);
        LOGI("Quality mask received: %dx%d (%d pixels), expected %d", 
             maskWidth, maskHeight, maskLen, maskWidth * maskHeight);
    }
    
    // Get first bitmap info
    jobject firstBitmap = env->GetObjectArrayElement(inputBitmaps, 0);
    AndroidBitmapInfo bitmapInfo;
    if (AndroidBitmap_getInfo(env, firstBitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to get bitmap info");
        if (maskData) env->ReleaseFloatArrayElements(qualityMask, maskData, JNI_ABORT);
        return -3;
    }
    
    // Verify bitmap format
    if (bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Input bitmap format is %d, expected RGBA_8888 (%d)", 
             bitmapInfo.format, ANDROID_BITMAP_FORMAT_RGBA_8888);
        if (maskData) env->ReleaseFloatArrayElements(qualityMask, maskData, JNI_ABORT);
        return -3;
    }
    
    int width = bitmapInfo.width;
    int height = bitmapInfo.height;
    
    LOGI("Processing %d frames (%dx%d) with quality mask through MFSR pipeline", 
         numFrames, width, height);
    
    // Log quality mask statistics
    if (maskData && maskLen > 0) {
        float maskMin = maskData[0], maskMax = maskData[0], maskSum = 0;
        for (int i = 0; i < maskLen; ++i) {
            if (maskData[i] < maskMin) maskMin = maskData[i];
            if (maskData[i] > maskMax) maskMax = maskData[i];
            maskSum += maskData[i];
        }
        LOGI("Quality mask stats: min=%.3f, max=%.3f, avg=%.3f", 
             maskMin, maskMax, maskSum / maskLen);
    }
    
    // Convert bitmaps to RGBImage format
    std::vector<RGBImage> frames(numFrames);
    std::vector<GrayImage> grayFrames(numFrames);
    
    for (int i = 0; i < numFrames; ++i) {
        jobject bitmap = env->GetObjectArrayElement(inputBitmaps, i);
        
        void* pixels;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
            LOGE("Failed to lock bitmap %d", i);
            if (maskData) env->ReleaseFloatArrayElements(qualityMask, maskData, JNI_ABORT);
            return -4;
        }
        
        frames[i].resize(width, height);
        grayFrames[i].resize(width, height);
        
        uint8_t* src = static_cast<uint8_t*>(pixels);
        
        for (int y = 0; y < height; ++y) {
            uint8_t* row = src + y * bitmapInfo.stride;
            for (int x = 0; x < width; ++x) {
                int idx = x * 4;
                // Android ARGB_8888 format: R, G, B, A (in memory order)
                float r = row[idx + 0] / 255.0f;
                float g = row[idx + 1] / 255.0f;
                float b = row[idx + 2] / 255.0f;
                
                // Apply quality mask weighting if available
                // This pre-weights pixels based on alignment quality
                float weight = 1.0f;
                if (maskData && maskLen == width * height) {
                    weight = maskData[y * width + x];
                    // Blend between original and weighted: high quality = full color, low = attenuated
                    // This helps the MFSR accumulator give less weight to misaligned regions
                    float blendFactor = 0.3f + 0.7f * weight;  // Range: 0.3 to 1.0
                    r *= blendFactor;
                    g *= blendFactor;
                    b *= blendFactor;
                }
                
                frames[i].at(x, y) = RGBPixel(r, g, b);
                grayFrames[i].at(x, y) = 0.299f * r + 0.587f * g + 0.114f * b;
            }
        }
        
        AndroidBitmap_unlockPixels(env, bitmap);
    }
    
    // Release quality mask
    if (maskData) {
        env->ReleaseFloatArrayElements(qualityMask, maskData, JNI_ABORT);
    }
    
    // Parse homographies if provided
    std::vector<GyroHomography> gyroHomographies;
    if (homographies != nullptr) {
        int homLen = env->GetArrayLength(homographies);
        if (homLen >= numFrames * 9) {
            jfloat* homData = env->GetFloatArrayElements(homographies, nullptr);
            
            for (int i = 0; i < numFrames; ++i) {
                GyroHomography gh;
                for (int j = 0; j < 9; ++j) {
                    gh.h[j] = homData[i * 9 + j];
                }
                gh.isValid = true;
                gyroHomographies.push_back(gh);
            }
            
            env->ReleaseFloatArrayElements(homographies, homData, JNI_ABORT);
        }
    }
    
    // Setup progress callback
    jmethodID progressMethod = nullptr;
    if (callback != nullptr) {
        jclass callbackClass = env->GetObjectClass(callback);
        progressMethod = env->GetMethodID(callbackClass, "onProgress", 
                                          "(IILjava/lang/String;F)V");
    }
    
    // Process
    PipelineResult result;
    pipeline->process(
        frames, grayFrames, referenceIndex,
        gyroHomographies.empty() ? nullptr : &gyroHomographies,
        result,
        [&](int tile, int total, const char* msg, float progress) {
            if (callback && progressMethod) {
                jstring jMsg = env->NewStringUTF(msg);
                env->CallVoidMethod(callback, progressMethod, tile, total, jMsg, progress);
                env->DeleteLocalRef(jMsg);
            }
        }
    );
    
    if (!result.success) {
        LOGE("MFSR processing with quality mask failed");
        return -5;
    }
    
    // Copy result to output bitmap
    AndroidBitmapInfo outInfo;
    if (AndroidBitmap_getInfo(env, outputBitmap, &outInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to get output bitmap info");
        return -6;
    }
    
    if (outInfo.width != static_cast<uint32_t>(result.outputWidth) ||
        outInfo.height != static_cast<uint32_t>(result.outputHeight)) {
        LOGE("Output bitmap size mismatch: expected %dx%d, got %dx%d",
             result.outputWidth, result.outputHeight, outInfo.width, outInfo.height);
        return -7;
    }
    
    void* outPixels;
    if (AndroidBitmap_lockPixels(env, outputBitmap, &outPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to lock output bitmap");
        return -8;
    }
    
    uint8_t* dst = static_cast<uint8_t*>(outPixels);
    
    for (int y = 0; y < result.outputHeight; ++y) {
        uint8_t* row = dst + y * outInfo.stride;
        for (int x = 0; x < result.outputWidth; ++x) {
            const RGBPixel& p = result.outputImage.at(x, y);
            int idx = x * 4;
            // Android ARGB_8888 format: R, G, B, A (in memory order)
            row[idx + 0] = static_cast<uint8_t>(clamp(p.r * 255.0f, 0.0f, 255.0f));
            row[idx + 1] = static_cast<uint8_t>(clamp(p.g * 255.0f, 0.0f, 255.0f));
            row[idx + 2] = static_cast<uint8_t>(clamp(p.b * 255.0f, 0.0f, 255.0f));
            row[idx + 3] = 255;  // Alpha
        }
    }
    
    AndroidBitmap_unlockPixels(env, outputBitmap);
    
    LOGI("MFSR with quality mask complete: %dx%d -> %dx%d, tiles=%d, time=%.1fs",
         result.inputWidth, result.inputHeight,
         result.outputWidth, result.outputHeight,
         result.tilesProcessed, result.processingTimeMs / 1000.0f);
    
    return 0;
}

/**
 * Get result info from last processing
 */
JNIEXPORT jint JNICALL
Java_com_imagedit_app_ultradetail_NativeMFSRPipeline_nativeGetResultInfo(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jfloatArray outputInfo
) {
    // outputInfo: [inputWidth, inputHeight, outputWidth, outputHeight, 
    //              tilesProcessed, tilesFailed, averageFlow, processingTimeMs, usedFallback]
    
    // This would need to store the last result in the pipeline
    // For now, return success
    return 0;
}

/**
 * Process YUV frames directly through the MFSR pipeline
 * This avoids the ~360MB memory spike from converting all frames to RGB upfront.
 * YUV->RGB conversion happens on-the-fly per tile in native code.
 * 
 * @param handle Pipeline handle
 * @param yPlanes Array of Y plane ByteBuffers
 * @param uPlanes Array of U plane ByteBuffers
 * @param vPlanes Array of V plane ByteBuffers
 * @param yRowStrides Y plane row strides
 * @param uvRowStrides UV plane row strides
 * @param uvPixelStrides UV pixel strides
 * @param width Frame width
 * @param height Frame height
 * @param referenceIndex Index of reference frame (-1 for auto-select)
 * @param homographies Flattened 3x3 homography matrices (9 floats per frame), or null
 * @param gyroMagnitudes Total gyro rotation magnitude per frame (for auto-select)
 * @param outputBitmap Pre-allocated output bitmap (scaled size)
 * @param callback Progress callback object
 * @return Result code (0 = success), or selected reference index if referenceIndex was -1
 */
JNIEXPORT jint JNICALL
Java_com_imagedit_app_ultradetail_NativeMFSRPipeline_nativeProcessYUV(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jobjectArray yPlanes,
    jobjectArray uPlanes,
    jobjectArray vPlanes,
    jintArray yRowStrides,
    jintArray uvRowStrides,
    jintArray uvPixelStrides,
    jint width,
    jint height,
    jint referenceIndex,
    jfloatArray homographies,
    jfloatArray gyroMagnitudes,
    jobject outputBitmap,
    jobject callback
) {
    auto* pipeline = reinterpret_cast<TiledMFSRPipeline*>(handle);
    if (!pipeline) {
        LOGE("Invalid pipeline handle");
        return -1;
    }
    
    int numFrames = env->GetArrayLength(yPlanes);
    if (numFrames < 2) {
        LOGE("Need at least 2 frames for MFSR");
        return -2;
    }
    
    LOGI("Processing %d YUV frames (%dx%d) through MFSR pipeline", numFrames, width, height);
    
    // Get stride arrays
    jint* yStrides = env->GetIntArrayElements(yRowStrides, nullptr);
    jint* uvStrides = env->GetIntArrayElements(uvRowStrides, nullptr);
    jint* uvPixStrides = env->GetIntArrayElements(uvPixelStrides, nullptr);
    
    // Smart reference frame selection: choose frame with lowest gyro rotation
    int selectedRef = referenceIndex;
    if (referenceIndex < 0 && gyroMagnitudes != nullptr) {
        int gyroLen = env->GetArrayLength(gyroMagnitudes);
        if (gyroLen >= numFrames) {
            jfloat* gyroMags = env->GetFloatArrayElements(gyroMagnitudes, nullptr);
            float minRotation = gyroMags[0];
            selectedRef = 0;
            for (int i = 1; i < numFrames; ++i) {
                if (gyroMags[i] < minRotation) {
                    minRotation = gyroMags[i];
                    selectedRef = i;
                }
            }
            env->ReleaseFloatArrayElements(gyroMagnitudes, gyroMags, JNI_ABORT);
            LOGI("Auto-selected reference frame %d (lowest gyro rotation: %.4f rad)", 
                 selectedRef, minRotation);
        } else {
            selectedRef = numFrames / 2;  // Fallback to middle
        }
    } else if (referenceIndex < 0) {
        selectedRef = numFrames / 2;  // Fallback to middle
    }
    
    // Convert YUV frames to RGB on-the-fly (one at a time to save memory)
    std::vector<RGBImage> frames(numFrames);
    std::vector<GrayImage> grayFrames(numFrames);
    
    for (int i = 0; i < numFrames; ++i) {
        jobject yBuf = env->GetObjectArrayElement(yPlanes, i);
        jobject uBuf = env->GetObjectArrayElement(uPlanes, i);
        jobject vBuf = env->GetObjectArrayElement(vPlanes, i);
        
        uint8_t* yData = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuf));
        uint8_t* uData = static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuf));
        uint8_t* vData = static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuf));
        
        if (!yData || !uData || !vData) {
            LOGE("Failed to get buffer address for frame %d", i);
            env->ReleaseIntArrayElements(yRowStrides, yStrides, JNI_ABORT);
            env->ReleaseIntArrayElements(uvRowStrides, uvStrides, JNI_ABORT);
            env->ReleaseIntArrayElements(uvPixelStrides, uvPixStrides, JNI_ABORT);
            return -3;
        }
        
        frames[i].resize(width, height);
        grayFrames[i].resize(width, height);
        
        int yRowStride = yStrides[i];
        int uvRowStride = uvStrides[i];
        int uvPixelStride = uvPixStrides[i];
        
        // YUV420 to RGB conversion
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                int yIdx = y * yRowStride + x;
                int uvIdx = (y / 2) * uvRowStride + (x / 2) * uvPixelStride;
                
                int yVal = (yData[yIdx] & 0xFF) - 16;
                int uVal = (uData[uvIdx] & 0xFF) - 128;
                int vVal = (vData[uvIdx] & 0xFF) - 128;
                
                // BT.601 YUV to RGB
                float r = (1.164f * yVal + 1.596f * vVal) / 255.0f;
                float g = (1.164f * yVal - 0.813f * vVal - 0.391f * uVal) / 255.0f;
                float b = (1.164f * yVal + 2.018f * uVal) / 255.0f;
                
                r = clamp(r, 0.0f, 1.0f);
                g = clamp(g, 0.0f, 1.0f);
                b = clamp(b, 0.0f, 1.0f);
                
                frames[i].at(x, y) = RGBPixel(r, g, b);
                grayFrames[i].at(x, y) = 0.299f * r + 0.587f * g + 0.114f * b;
            }
        }
        
        LOGD("Converted frame %d YUV->RGB", i);
    }
    
    env->ReleaseIntArrayElements(yRowStrides, yStrides, JNI_ABORT);
    env->ReleaseIntArrayElements(uvRowStrides, uvStrides, JNI_ABORT);
    env->ReleaseIntArrayElements(uvPixelStrides, uvPixStrides, JNI_ABORT);
    
    // Parse homographies if provided
    std::vector<GyroHomography> gyroHomographies;
    if (homographies != nullptr) {
        int homLen = env->GetArrayLength(homographies);
        if (homLen >= numFrames * 9) {
            jfloat* homData = env->GetFloatArrayElements(homographies, nullptr);
            
            for (int i = 0; i < numFrames; ++i) {
                GyroHomography gh;
                for (int j = 0; j < 9; ++j) {
                    gh.h[j] = homData[i * 9 + j];
                }
                gh.isValid = true;
                gyroHomographies.push_back(gh);
            }
            
            env->ReleaseFloatArrayElements(homographies, homData, JNI_ABORT);
        }
    }
    
    // Setup progress callback
    jmethodID progressMethod = nullptr;
    if (callback != nullptr) {
        jclass callbackClass = env->GetObjectClass(callback);
        progressMethod = env->GetMethodID(callbackClass, "onProgress", 
                                          "(IILjava/lang/String;F)V");
    }
    
    // Process
    PipelineResult result;
    pipeline->process(
        frames, grayFrames, selectedRef,
        gyroHomographies.empty() ? nullptr : &gyroHomographies,
        result,
        [&](int tile, int total, const char* msg, float progress) {
            if (callback && progressMethod) {
                jstring jMsg = env->NewStringUTF(msg);
                env->CallVoidMethod(callback, progressMethod, tile, total, jMsg, progress);
                env->DeleteLocalRef(jMsg);
            }
        }
    );
    
    // Free frame memory as soon as possible
    frames.clear();
    grayFrames.clear();
    
    if (!result.success) {
        LOGE("MFSR processing failed");
        return -5;
    }
    
    // Copy result to output bitmap
    AndroidBitmapInfo outInfo;
    if (AndroidBitmap_getInfo(env, outputBitmap, &outInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to get output bitmap info");
        return -6;
    }
    
    if (outInfo.width != static_cast<uint32_t>(result.outputWidth) ||
        outInfo.height != static_cast<uint32_t>(result.outputHeight)) {
        LOGE("Output bitmap size mismatch: expected %dx%d, got %dx%d",
             result.outputWidth, result.outputHeight, outInfo.width, outInfo.height);
        return -7;
    }
    
    void* outPixels;
    if (AndroidBitmap_lockPixels(env, outputBitmap, &outPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to lock output bitmap");
        return -8;
    }
    
    uint8_t* dst = static_cast<uint8_t*>(outPixels);
    for (int y = 0; y < result.outputHeight; ++y) {
        uint8_t* row = dst + y * outInfo.stride;
        for (int x = 0; x < result.outputWidth; ++x) {
            const RGBPixel& p = result.outputImage.at(x, y);
            int idx = x * 4;
            // Android ARGB_8888 format: R, G, B, A (in memory order)
            row[idx + 0] = static_cast<uint8_t>(clamp(p.r * 255.0f, 0.0f, 255.0f));
            row[idx + 1] = static_cast<uint8_t>(clamp(p.g * 255.0f, 0.0f, 255.0f));
            row[idx + 2] = static_cast<uint8_t>(clamp(p.b * 255.0f, 0.0f, 255.0f));
            row[idx + 3] = 255;  // Alpha
        }
    }
    
    AndroidBitmap_unlockPixels(env, outputBitmap);
    
    LOGI("MFSR (YUV) complete: %dx%d -> %dx%d, ref=%d, tiles=%d, time=%.1fs, fallback=%s",
         result.inputWidth, result.inputHeight,
         result.outputWidth, result.outputHeight,
         selectedRef, result.tilesProcessed, result.processingTimeMs / 1000.0f,
         result.usedFallback ? "yes" : "no");
    
    // Return selected reference index (useful for preview)
    return selectedRef;
}

/**
 * Select the best reference frame based on gyro stability
 * Returns the index of the frame with lowest total gyro rotation
 */
JNIEXPORT jint JNICALL
Java_com_imagedit_app_ultradetail_NativeMFSRPipeline_nativeSelectReferenceFrame(
    JNIEnv* env,
    jclass clazz,
    jfloatArray gyroMagnitudes
) {
    int numFrames = env->GetArrayLength(gyroMagnitudes);
    if (numFrames < 1) return 0;
    
    jfloat* mags = env->GetFloatArrayElements(gyroMagnitudes, nullptr);
    
    int bestIdx = 0;
    float minMag = mags[0];
    for (int i = 1; i < numFrames; ++i) {
        if (mags[i] < minMag) {
            minMag = mags[i];
            bestIdx = i;
        }
    }
    
    env->ReleaseFloatArrayElements(gyroMagnitudes, mags, JNI_ABORT);
    
    LOGD("Selected reference frame %d (gyro magnitude: %.4f)", bestIdx, minMag);
    return bestIdx;
}

// ==================== Phase 1: Frequency Separation ====================

/**
 * Apply frequency separation enhancement to a bitmap
 * 
 * @param inputBitmap Input ARGB_8888 bitmap
 * @param outputBitmap Output ARGB_8888 bitmap (same size as input)
 * @param lowPassSigma Gaussian sigma for low-frequency extraction
 * @param highBoost High-frequency amplification factor
 * @param edgeProtection Edge protection strength (0-1)
 * @param blendStrength Final blend with original (0-1)
 * @return 0 on success, negative on error
 */
JNIEXPORT jint JNICALL
Java_com_imagedit_app_ultradetail_NativeMFSRPipeline_nativeApplyFreqSeparation(
    JNIEnv* env,
    jclass clazz,
    jobject inputBitmap,
    jobject outputBitmap,
    jfloat lowPassSigma,
    jfloat highBoost,
    jfloat edgeProtection,
    jfloat blendStrength
) {
    AndroidBitmapInfo inInfo, outInfo;
    void* inPixels;
    void* outPixels;
    
    if (AndroidBitmap_getInfo(env, inputBitmap, &inInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, outputBitmap, &outInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("FreqSep: Failed to get bitmap info");
        return -1;
    }
    
    if (inInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        outInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("FreqSep: Bitmap format must be ARGB_8888");
        return -2;
    }
    
    if (inInfo.width != outInfo.width || inInfo.height != outInfo.height) {
        LOGE("FreqSep: Input/output size mismatch");
        return -3;
    }
    
    if (AndroidBitmap_lockPixels(env, inputBitmap, &inPixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, outputBitmap, &outPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("FreqSep: Failed to lock pixels");
        return -4;
    }
    
    int width = inInfo.width;
    int height = inInfo.height;
    
    // Convert to RGBImage
    RGBImage input, output;
    input.resize(width, height);
    
    uint8_t* src = static_cast<uint8_t*>(inPixels);
    for (int y = 0; y < height; ++y) {
        uint8_t* row = src + y * inInfo.stride;
        for (int x = 0; x < width; ++x) {
            int idx = x * 4;
            input.at(x, y) = RGBPixel(
                row[idx + 0] / 255.0f,
                row[idx + 1] / 255.0f,
                row[idx + 2] / 255.0f
            );
        }
    }
    
    // Apply frequency separation
    FreqSeparationParams params;
    params.lowPassSigma = lowPassSigma;
    params.highBoost = highBoost;
    params.edgeProtection = edgeProtection;
    params.blendStrength = blendStrength;
    
    FreqSeparationProcessor processor(params);
    processor.processRGB(input, output);
    
    // Copy to output bitmap
    uint8_t* dst = static_cast<uint8_t*>(outPixels);
    for (int y = 0; y < height; ++y) {
        uint8_t* row = dst + y * outInfo.stride;
        for (int x = 0; x < width; ++x) {
            const RGBPixel& p = output.at(x, y);
            int idx = x * 4;
            row[idx + 0] = static_cast<uint8_t>(clamp(p.r * 255.0f, 0.0f, 255.0f));
            row[idx + 1] = static_cast<uint8_t>(clamp(p.g * 255.0f, 0.0f, 255.0f));
            row[idx + 2] = static_cast<uint8_t>(clamp(p.b * 255.0f, 0.0f, 255.0f));
            row[idx + 3] = 255;
        }
    }
    
    AndroidBitmap_unlockPixels(env, inputBitmap);
    AndroidBitmap_unlockPixels(env, outputBitmap);
    
    LOGI("FreqSep: Processed %dx%d (sigma=%.1f, boost=%.1f, edge=%.1f, blend=%.1f)",
         width, height, lowPassSigma, highBoost, edgeProtection, blendStrength);
    
    return 0;
}

// ==================== Phase 1: Anisotropic Merge ====================

/**
 * Apply anisotropic filtering to a bitmap (edge-aware smoothing)
 * 
 * @param inputBitmap Input ARGB_8888 bitmap
 * @param outputBitmap Output ARGB_8888 bitmap (same size as input)
 * @param kernelSigma Base sigma for anisotropic kernel
 * @param elongation Kernel elongation along edges
 * @param noiseThreshold Below this, use isotropic kernel
 * @return 0 on success, negative on error
 */
JNIEXPORT jint JNICALL
Java_com_imagedit_app_ultradetail_NativeMFSRPipeline_nativeApplyAnisotropicFilter(
    JNIEnv* env,
    jclass clazz,
    jobject inputBitmap,
    jobject outputBitmap,
    jfloat kernelSigma,
    jfloat elongation,
    jfloat noiseThreshold
) {
    AndroidBitmapInfo inInfo, outInfo;
    void* inPixels;
    void* outPixels;
    
    if (AndroidBitmap_getInfo(env, inputBitmap, &inInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, outputBitmap, &outInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AnisotropicFilter: Failed to get bitmap info");
        return -1;
    }
    
    if (inInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        outInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("AnisotropicFilter: Bitmap format must be ARGB_8888");
        return -2;
    }
    
    if (AndroidBitmap_lockPixels(env, inputBitmap, &inPixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, outputBitmap, &outPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AnisotropicFilter: Failed to lock pixels");
        return -3;
    }
    
    int width = inInfo.width;
    int height = inInfo.height;
    
    // Convert to RGBImage
    RGBImage input, output;
    input.resize(width, height);
    
    uint8_t* src = static_cast<uint8_t*>(inPixels);
    for (int y = 0; y < height; ++y) {
        uint8_t* row = src + y * inInfo.stride;
        for (int x = 0; x < width; ++x) {
            int idx = x * 4;
            input.at(x, y) = RGBPixel(
                row[idx + 0] / 255.0f,
                row[idx + 1] / 255.0f,
                row[idx + 2] / 255.0f
            );
        }
    }
    
    // Apply anisotropic filtering
    AnisotropicMergeParams params;
    params.kernelSigma = kernelSigma;
    params.elongation = elongation;
    params.noiseThreshold = noiseThreshold;
    
    AnisotropicMergeProcessor processor(params);
    processor.filterRGB(input, output);
    
    // Copy to output bitmap
    uint8_t* dst = static_cast<uint8_t*>(outPixels);
    for (int y = 0; y < height; ++y) {
        uint8_t* row = dst + y * outInfo.stride;
        for (int x = 0; x < width; ++x) {
            const RGBPixel& p = output.at(x, y);
            int idx = x * 4;
            row[idx + 0] = static_cast<uint8_t>(clamp(p.r * 255.0f, 0.0f, 255.0f));
            row[idx + 1] = static_cast<uint8_t>(clamp(p.g * 255.0f, 0.0f, 255.0f));
            row[idx + 2] = static_cast<uint8_t>(clamp(p.b * 255.0f, 0.0f, 255.0f));
            row[idx + 3] = 255;
        }
    }
    
    AndroidBitmap_unlockPixels(env, inputBitmap);
    AndroidBitmap_unlockPixels(env, outputBitmap);
    
    LOGI("AnisotropicFilter: Processed %dx%d (sigma=%.1f, elong=%.1f, noise=%.3f)",
         width, height, kernelSigma, elongation, noiseThreshold);
    
    return 0;
}

// ==================== Phase 2: ORB Alignment ====================

/**
 * Align two bitmaps using ORB feature matching
 * Returns homography as 9 floats (row-major 3x3 matrix)
 * 
 * @param referenceBitmap Reference frame
 * @param frameBitmap Frame to align
 * @param homographyOut Output array for 9 homography values
 * @param maxKeypoints Maximum keypoints to detect
 * @param ransacThreshold RANSAC inlier threshold in pixels
 * @return Number of inliers, or negative on error
 */
JNIEXPORT jint JNICALL
Java_com_imagedit_app_ultradetail_NativeMFSRPipeline_nativeAlignORB(
    JNIEnv* env,
    jclass clazz,
    jobject referenceBitmap,
    jobject frameBitmap,
    jfloatArray homographyOut,
    jint maxKeypoints,
    jfloat ransacThreshold
) {
    AndroidBitmapInfo refInfo, frameInfo;
    void* refPixels;
    void* framePixels;
    
    if (AndroidBitmap_getInfo(env, referenceBitmap, &refInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, frameBitmap, &frameInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("ORB: Failed to get bitmap info");
        return -1;
    }
    
    if (AndroidBitmap_lockPixels(env, referenceBitmap, &refPixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, frameBitmap, &framePixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("ORB: Failed to lock pixels");
        return -2;
    }
    
    int width = refInfo.width;
    int height = refInfo.height;
    
    // Convert to grayscale
    GrayImage refGray, frameGray;
    refGray.resize(width, height);
    frameGray.resize(width, height);
    
    uint8_t* refSrc = static_cast<uint8_t*>(refPixels);
    uint8_t* frameSrc = static_cast<uint8_t*>(framePixels);
    
    for (int y = 0; y < height; ++y) {
        uint8_t* refRow = refSrc + y * refInfo.stride;
        uint8_t* frameRow = frameSrc + y * frameInfo.stride;
        for (int x = 0; x < width; ++x) {
            int idx = x * 4;
            // Luminance from RGB
            refGray.at(x, y) = (0.299f * refRow[idx] + 0.587f * refRow[idx+1] + 0.114f * refRow[idx+2]) / 255.0f;
            frameGray.at(x, y) = (0.299f * frameRow[idx] + 0.587f * frameRow[idx+1] + 0.114f * frameRow[idx+2]) / 255.0f;
        }
    }
    
    AndroidBitmap_unlockPixels(env, referenceBitmap);
    AndroidBitmap_unlockPixels(env, frameBitmap);
    
    // Run ORB alignment
    ORBAlignmentParams params;
    params.maxKeypoints = maxKeypoints;
    params.ransacThreshold = ransacThreshold;
    
    ORBAligner aligner(params);
    ORBAlignmentResult result = aligner.align(refGray, frameGray);
    
    // Copy homography to output
    jfloat* homOut = env->GetFloatArrayElements(homographyOut, nullptr);
    for (int i = 0; i < 9; ++i) {
        homOut[i] = result.homography.data[i];
    }
    env->ReleaseFloatArrayElements(homographyOut, homOut, 0);
    
    LOGI("ORB: Aligned %dx%d, inliers=%d/%d (%.1f%%), success=%s",
         width, height, result.inlierCount, result.totalMatches,
         result.inlierRatio * 100, result.success ? "yes" : "no");
    
    return result.success ? result.inlierCount : -3;
}

// ==================== Phase 2: Drizzle ====================

/**
 * Apply drizzle algorithm to combine multiple bitmaps
 * 
 * @param inputBitmaps Array of input bitmaps
 * @param shifts Sub-pixel shifts as flat array [dx0, dy0, w0, dx1, dy1, w1, ...]
 * @param outputBitmap Pre-allocated output bitmap (scaled size)
 * @param scaleFactor Output scale factor
 * @param pixfrac Drop size fraction (0.1-1.0)
 * @return 0 on success, negative on error
 */
JNIEXPORT jint JNICALL
Java_com_imagedit_app_ultradetail_NativeMFSRPipeline_nativeDrizzle(
    JNIEnv* env,
    jclass clazz,
    jobjectArray inputBitmaps,
    jfloatArray shifts,
    jobject outputBitmap,
    jint scaleFactor,
    jfloat pixfrac
) {
    int numFrames = env->GetArrayLength(inputBitmaps);
    if (numFrames < 2) {
        LOGE("Drizzle: Need at least 2 frames");
        return -1;
    }
    
    // Get shifts
    jfloat* shiftData = env->GetFloatArrayElements(shifts, nullptr);
    std::vector<SubPixelShift> subPixelShifts(numFrames);
    for (int i = 0; i < numFrames; ++i) {
        subPixelShifts[i].dx = shiftData[i * 3];
        subPixelShifts[i].dy = shiftData[i * 3 + 1];
        subPixelShifts[i].weight = shiftData[i * 3 + 2];
    }
    env->ReleaseFloatArrayElements(shifts, shiftData, JNI_ABORT);
    
    // Get first bitmap to determine size
    jobject firstBitmap = env->GetObjectArrayElement(inputBitmaps, 0);
    AndroidBitmapInfo firstInfo;
    AndroidBitmap_getInfo(env, firstBitmap, &firstInfo);
    
    int inWidth = firstInfo.width;
    int inHeight = firstInfo.height;
    
    // Load all frames
    std::vector<RGBImage> frames(numFrames);
    for (int f = 0; f < numFrames; ++f) {
        jobject bitmap = env->GetObjectArrayElement(inputBitmaps, f);
        void* pixels;
        AndroidBitmapInfo info;
        
        AndroidBitmap_getInfo(env, bitmap, &info);
        AndroidBitmap_lockPixels(env, bitmap, &pixels);
        
        frames[f].resize(info.width, info.height);
        uint8_t* src = static_cast<uint8_t*>(pixels);
        
        for (int y = 0; y < static_cast<int>(info.height); ++y) {
            uint8_t* row = src + y * info.stride;
            for (int x = 0; x < static_cast<int>(info.width); ++x) {
                int idx = x * 4;
                frames[f].at(x, y) = RGBPixel(
                    row[idx] / 255.0f,
                    row[idx + 1] / 255.0f,
                    row[idx + 2] / 255.0f
                );
            }
        }
        
        AndroidBitmap_unlockPixels(env, bitmap);
    }
    
    // Run drizzle
    DrizzleParams params;
    params.scaleFactor = scaleFactor;
    params.pixfrac = pixfrac;
    
    DrizzleProcessor processor(params);
    DrizzleResult result = processor.process(frames, subPixelShifts, 0);
    
    if (!result.success) {
        LOGE("Drizzle: Processing failed");
        return -2;
    }
    
    // Copy to output bitmap
    AndroidBitmapInfo outInfo;
    void* outPixels;
    AndroidBitmap_getInfo(env, outputBitmap, &outInfo);
    AndroidBitmap_lockPixels(env, outputBitmap, &outPixels);
    
    uint8_t* dst = static_cast<uint8_t*>(outPixels);
    for (int y = 0; y < result.outputHeight; ++y) {
        uint8_t* row = dst + y * outInfo.stride;
        for (int x = 0; x < result.outputWidth; ++x) {
            const RGBPixel& p = result.output.at(x, y);
            int idx = x * 4;
            row[idx] = static_cast<uint8_t>(clamp(p.r * 255.0f, 0.0f, 255.0f));
            row[idx + 1] = static_cast<uint8_t>(clamp(p.g * 255.0f, 0.0f, 255.0f));
            row[idx + 2] = static_cast<uint8_t>(clamp(p.b * 255.0f, 0.0f, 255.0f));
            row[idx + 3] = 255;
        }
    }
    
    AndroidBitmap_unlockPixels(env, outputBitmap);
    
    LOGI("Drizzle: %d frames %dx%d -> %dx%d (scale=%d, pixfrac=%.2f, coverage=%.2f)",
         numFrames, inWidth, inHeight, result.outputWidth, result.outputHeight,
         scaleFactor, pixfrac, result.avgCoverage);
    
    return 0;
}

// ==================== Phase 2: Rolling Shutter Correction ====================

/**
 * Correct rolling shutter distortion using gyro data
 * 
 * @param inputBitmap Input bitmap with RS distortion
 * @param outputBitmap Output corrected bitmap (same size)
 * @param gyroData Gyro samples as flat array [t0, rx0, ry0, rz0, t1, rx1, ...]
 * @param readoutTimeMs Frame readout time in milliseconds
 * @param focalLengthPx Focal length in pixels
 * @return 0 on success, negative on error
 */
JNIEXPORT jint JNICALL
Java_com_imagedit_app_ultradetail_NativeMFSRPipeline_nativeCorrectRollingShutter(
    JNIEnv* env,
    jclass clazz,
    jobject inputBitmap,
    jobject outputBitmap,
    jfloatArray gyroData,
    jfloat readoutTimeMs,
    jfloat focalLengthPx
) {
    AndroidBitmapInfo inInfo, outInfo;
    void* inPixels;
    void* outPixels;
    
    if (AndroidBitmap_getInfo(env, inputBitmap, &inInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, outputBitmap, &outInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("RS: Failed to get bitmap info");
        return -1;
    }
    
    if (AndroidBitmap_lockPixels(env, inputBitmap, &inPixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, outputBitmap, &outPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("RS: Failed to lock pixels");
        return -2;
    }
    
    int width = inInfo.width;
    int height = inInfo.height;
    
    // Convert to RGBImage
    RGBImage input;
    input.resize(width, height);
    
    uint8_t* src = static_cast<uint8_t*>(inPixels);
    for (int y = 0; y < height; ++y) {
        uint8_t* row = src + y * inInfo.stride;
        for (int x = 0; x < width; ++x) {
            int idx = x * 4;
            input.at(x, y) = RGBPixel(
                row[idx] / 255.0f,
                row[idx + 1] / 255.0f,
                row[idx + 2] / 255.0f
            );
        }
    }
    
    // Parse gyro data
    int gyroLen = env->GetArrayLength(gyroData);
    int numSamples = gyroLen / 4;
    jfloat* gyro = env->GetFloatArrayElements(gyroData, nullptr);
    
    std::vector<GyroSampleRS> samples(numSamples);
    for (int i = 0; i < numSamples; ++i) {
        samples[i].timestamp = gyro[i * 4];
        samples[i].rotX = gyro[i * 4 + 1];
        samples[i].rotY = gyro[i * 4 + 2];
        samples[i].rotZ = gyro[i * 4 + 3];
    }
    env->ReleaseFloatArrayElements(gyroData, gyro, JNI_ABORT);
    
    // Run correction
    RollingShutterParams params;
    params.readoutTimeMs = readoutTimeMs;
    params.focalLengthPx = focalLengthPx;
    
    RollingShutterCorrector corrector(params);
    RSCorrectionResult result = corrector.correct(input, samples, 0.0f);
    
    AndroidBitmap_unlockPixels(env, inputBitmap);
    
    if (!result.success) {
        AndroidBitmap_unlockPixels(env, outputBitmap);
        LOGE("RS: Correction failed");
        return -3;
    }
    
    // Copy to output
    uint8_t* dst = static_cast<uint8_t*>(outPixels);
    for (int y = 0; y < height; ++y) {
        uint8_t* row = dst + y * outInfo.stride;
        for (int x = 0; x < width; ++x) {
            const RGBPixel& p = result.corrected.at(x, y);
            int idx = x * 4;
            row[idx] = static_cast<uint8_t>(clamp(p.r * 255.0f, 0.0f, 255.0f));
            row[idx + 1] = static_cast<uint8_t>(clamp(p.g * 255.0f, 0.0f, 255.0f));
            row[idx + 2] = static_cast<uint8_t>(clamp(p.b * 255.0f, 0.0f, 255.0f));
            row[idx + 3] = 255;
        }
    }
    
    AndroidBitmap_unlockPixels(env, outputBitmap);
    
    LOGI("RS: Corrected %dx%d, max_disp=%.2f, avg_disp=%.2f",
         width, height, result.maxDisplacement, result.avgDisplacement);
    
    return 0;
}

// ==================== Phase 3: Kalman Fusion ====================

/**
 * Fuse gyro and optical flow measurements using Kalman filter
 * 
 * @param gyroData Gyro samples [t, rx, ry, rz, dt, ...] per frame
 * @param flowData Optical flow [dx, dy, confidence, ...] per frame pair
 * @param numFrames Number of frames
 * @param outputMotion Output fused motion [x, y, vx, vy, uncertainty, ...]
 * @param gyroWeight Weight for gyro measurements
 * @param flowWeight Weight for flow measurements
 * @return Number of fused results, or negative on error
 */
JNIEXPORT jint JNICALL
Java_com_imagedit_app_ultradetail_NativeMFSRPipeline_nativeKalmanFusion(
    JNIEnv* env,
    jclass clazz,
    jfloatArray gyroData,
    jfloatArray flowData,
    jint numFrames,
    jfloatArray outputMotion,
    jfloat gyroWeight,
    jfloat flowWeight
) {
    if (numFrames < 2) {
        LOGE("KalmanFusion: Need at least 2 frames");
        return -1;
    }
    
    // Parse gyro data (5 floats per sample: t, rx, ry, rz, dt)
    int gyroLen = env->GetArrayLength(gyroData);
    jfloat* gyro = env->GetFloatArrayElements(gyroData, nullptr);
    
    // Parse flow data (3 floats per frame pair: dx, dy, confidence)
    int flowLen = env->GetArrayLength(flowData);
    jfloat* flow = env->GetFloatArrayElements(flowData, nullptr);
    
    int numFlows = flowLen / 3;
    
    // Build measurements
    std::vector<std::vector<GyroMeasurement>> allGyro(numFlows);
    std::vector<FlowMeasurement> flows(numFlows);
    
    // Distribute gyro samples across frame intervals (simplified)
    int samplesPerInterval = (gyroLen / 5) / numFlows;
    int gyroIdx = 0;
    
    for (int i = 0; i < numFlows; ++i) {
        // Gyro samples for this interval
        for (int j = 0; j < samplesPerInterval && gyroIdx * 5 + 4 < gyroLen; ++j, ++gyroIdx) {
            GyroMeasurement m;
            m.timestamp = gyro[gyroIdx * 5];
            m.rotX = gyro[gyroIdx * 5 + 1];
            m.rotY = gyro[gyroIdx * 5 + 2];
            m.rotZ = gyro[gyroIdx * 5 + 3];
            m.dt = gyro[gyroIdx * 5 + 4];
            allGyro[i].push_back(m);
        }
        
        // Flow measurement
        flows[i].dx = flow[i * 3];
        flows[i].dy = flow[i * 3 + 1];
        flows[i].confidence = flow[i * 3 + 2];
    }
    
    env->ReleaseFloatArrayElements(gyroData, gyro, JNI_ABORT);
    env->ReleaseFloatArrayElements(flowData, flow, JNI_ABORT);
    
    // Run Kalman fusion
    KalmanFusionParams params;
    params.gyroWeight = gyroWeight;
    params.flowWeight = flowWeight;
    
    KalmanFusionProcessor processor(params);
    std::vector<FusionResult> results = processor.fuseBatch(allGyro, flows);
    
    // Copy results to output (5 floats per result: x, y, vx, vy, uncertainty)
    jfloat* out = env->GetFloatArrayElements(outputMotion, nullptr);
    for (size_t i = 0; i < results.size(); ++i) {
        out[i * 5] = results[i].motion.x;
        out[i * 5 + 1] = results[i].motion.y;
        out[i * 5 + 2] = results[i].motion.vx;
        out[i * 5 + 3] = results[i].motion.vy;
        out[i * 5 + 4] = results[i].uncertainty;
    }
    env->ReleaseFloatArrayElements(outputMotion, out, 0);
    
    LOGI("KalmanFusion: Fused %zu frame pairs (gyroW=%.2f, flowW=%.2f)",
         results.size(), gyroWeight, flowWeight);
    
    return static_cast<int>(results.size());
}

// ==================== Phase 3: Texture Synthesis ====================

/**
 * Synthesize texture details for an image
 * 
 * @param inputBitmap Input bitmap (potentially lacking detail)
 * @param outputBitmap Output bitmap with synthesized details
 * @param patchSize Synthesis patch size
 * @param searchRadius Search radius for similar patches
 * @param blendWeight Blend weight for synthesized detail
 * @return 0 on success, negative on error
 */
JNIEXPORT jint JNICALL
Java_com_imagedit_app_ultradetail_NativeMFSRPipeline_nativeTextureSynthesis(
    JNIEnv* env,
    jclass clazz,
    jobject inputBitmap,
    jobject outputBitmap,
    jint patchSize,
    jint searchRadius,
    jfloat blendWeight
) {
    AndroidBitmapInfo inInfo, outInfo;
    void* inPixels;
    void* outPixels;
    
    if (AndroidBitmap_getInfo(env, inputBitmap, &inInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, outputBitmap, &outInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("TextureSynth: Failed to get bitmap info");
        return -1;
    }
    
    if (AndroidBitmap_lockPixels(env, inputBitmap, &inPixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, outputBitmap, &outPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("TextureSynth: Failed to lock pixels");
        return -2;
    }
    
    int width = inInfo.width;
    int height = inInfo.height;
    
    // Convert to RGBImage
    RGBImage input;
    input.resize(width, height);
    
    uint8_t* src = static_cast<uint8_t*>(inPixels);
    for (int y = 0; y < height; ++y) {
        uint8_t* row = src + y * inInfo.stride;
        for (int x = 0; x < width; ++x) {
            int idx = x * 4;
            input.at(x, y) = RGBPixel(
                row[idx] / 255.0f,
                row[idx + 1] / 255.0f,
                row[idx + 2] / 255.0f
            );
        }
    }
    
    AndroidBitmap_unlockPixels(env, inputBitmap);
    
    // Run texture synthesis
    TextureSynthParams params;
    params.patchSize = patchSize;
    params.searchRadius = searchRadius;
    params.blendWeight = blendWeight;
    
    TextureSynthProcessor processor(params);
    TextureSynthResult result = processor.synthesize(input);
    
    if (!result.success) {
        AndroidBitmap_unlockPixels(env, outputBitmap);
        LOGE("TextureSynth: Synthesis failed");
        return -3;
    }
    
    // Copy to output
    uint8_t* dst = static_cast<uint8_t*>(outPixels);
    for (int y = 0; y < height; ++y) {
        uint8_t* row = dst + y * outInfo.stride;
        for (int x = 0; x < width; ++x) {
            const RGBPixel& p = result.synthesized.at(x, y);
            int idx = x * 4;
            row[idx] = static_cast<uint8_t>(clamp(p.r * 255.0f, 0.0f, 255.0f));
            row[idx + 1] = static_cast<uint8_t>(clamp(p.g * 255.0f, 0.0f, 255.0f));
            row[idx + 2] = static_cast<uint8_t>(clamp(p.b * 255.0f, 0.0f, 255.0f));
            row[idx + 3] = 255;
        }
    }
    
    AndroidBitmap_unlockPixels(env, outputBitmap);
    
    LOGI("TextureSynth: %dx%d, patches=%d, avgDetail=%.3f",
         width, height, result.patchesProcessed, result.avgDetailAdded);
    
    return 0;
}

/**
 * Transfer texture from source to target regions
 * 
 * @param targetBitmap Target bitmap to enhance
 * @param sourceBitmap Source bitmap with texture
 * @param maskData Mask array (1 float per pixel, 0-1)
 * @param outputBitmap Output enhanced bitmap
 * @param blendWeight Blend weight
 * @return 0 on success, negative on error
 */
JNIEXPORT jint JNICALL
Java_com_imagedit_app_ultradetail_NativeMFSRPipeline_nativeTextureTransfer(
    JNIEnv* env,
    jclass clazz,
    jobject targetBitmap,
    jobject sourceBitmap,
    jfloatArray maskData,
    jobject outputBitmap,
    jfloat blendWeight
) {
    AndroidBitmapInfo tgtInfo, srcInfo, outInfo;
    void* tgtPixels;
    void* srcPixels;
    void* outPixels;
    
    AndroidBitmap_getInfo(env, targetBitmap, &tgtInfo);
    AndroidBitmap_getInfo(env, sourceBitmap, &srcInfo);
    AndroidBitmap_getInfo(env, outputBitmap, &outInfo);
    
    AndroidBitmap_lockPixels(env, targetBitmap, &tgtPixels);
    AndroidBitmap_lockPixels(env, sourceBitmap, &srcPixels);
    AndroidBitmap_lockPixels(env, outputBitmap, &outPixels);
    
    int width = tgtInfo.width;
    int height = tgtInfo.height;
    
    // Convert to RGBImage
    RGBImage target, source;
    target.resize(width, height);
    source.resize(width, height);
    
    uint8_t* tgtSrc = static_cast<uint8_t*>(tgtPixels);
    uint8_t* srcSrc = static_cast<uint8_t*>(srcPixels);
    
    for (int y = 0; y < height; ++y) {
        uint8_t* tgtRow = tgtSrc + y * tgtInfo.stride;
        uint8_t* srcRow = srcSrc + y * srcInfo.stride;
        for (int x = 0; x < width; ++x) {
            int idx = x * 4;
            target.at(x, y) = RGBPixel(tgtRow[idx]/255.0f, tgtRow[idx+1]/255.0f, tgtRow[idx+2]/255.0f);
            source.at(x, y) = RGBPixel(srcRow[idx]/255.0f, srcRow[idx+1]/255.0f, srcRow[idx+2]/255.0f);
        }
    }
    
    AndroidBitmap_unlockPixels(env, targetBitmap);
    AndroidBitmap_unlockPixels(env, sourceBitmap);
    
    // Parse mask
    GrayImage mask;
    mask.resize(width, height);
    jfloat* maskPtr = env->GetFloatArrayElements(maskData, nullptr);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            mask.at(x, y) = maskPtr[y * width + x];
        }
    }
    env->ReleaseFloatArrayElements(maskData, maskPtr, JNI_ABORT);
    
    // Transfer texture
    TextureSynthParams params;
    params.blendWeight = blendWeight;
    
    TextureSynthProcessor processor(params);
    RGBImage result = processor.transferTexture(target, source, mask);
    
    // Copy to output
    uint8_t* dst = static_cast<uint8_t*>(outPixels);
    for (int y = 0; y < height; ++y) {
        uint8_t* row = dst + y * outInfo.stride;
        for (int x = 0; x < width; ++x) {
            const RGBPixel& p = result.at(x, y);
            int idx = x * 4;
            row[idx] = static_cast<uint8_t>(clamp(p.r * 255.0f, 0.0f, 255.0f));
            row[idx + 1] = static_cast<uint8_t>(clamp(p.g * 255.0f, 0.0f, 255.0f));
            row[idx + 2] = static_cast<uint8_t>(clamp(p.b * 255.0f, 0.0f, 255.0f));
            row[idx + 3] = 255;
        }
    }
    
    AndroidBitmap_unlockPixels(env, outputBitmap);
    
    LOGI("TextureTransfer: %dx%d complete", width, height);
    
    return 0;
}

/**
 * Transfer high-frequency detail from reference frame to upscaled output
 * 
 * This implements cross-frame texture transfer:
 * 1. Upscale reference to match output size
 * 2. Extract high-frequency (Laplacian) from upscaled reference
 * 3. Blend high-frequency into output where it improves detail
 * 
 * @param upscaledBitmap The upscaled output (target)
 * @param referenceBitmap The sharpest original frame (source of detail)
 * @param outputBitmap Output bitmap (same size as upscaled)
 * @param blendStrength How much high-frequency to transfer (0-1)
 * @return 0 on success, negative on error
 */
JNIEXPORT jint JNICALL
Java_com_imagedit_app_ultradetail_NativeMFSRPipeline_nativeReferenceDetailTransfer(
    JNIEnv* env,
    jclass clazz,
    jobject upscaledBitmap,
    jobject referenceBitmap,
    jobject outputBitmap,
    jfloat blendStrength
) {
    AndroidBitmapInfo upInfo, refInfo, outInfo;
    void* upPixels;
    void* refPixels;
    void* outPixels;
    
    AndroidBitmap_getInfo(env, upscaledBitmap, &upInfo);
    AndroidBitmap_getInfo(env, referenceBitmap, &refInfo);
    AndroidBitmap_getInfo(env, outputBitmap, &outInfo);
    
    AndroidBitmap_lockPixels(env, upscaledBitmap, &upPixels);
    AndroidBitmap_lockPixels(env, referenceBitmap, &refPixels);
    AndroidBitmap_lockPixels(env, outputBitmap, &outPixels);
    
    int outWidth = upInfo.width;
    int outHeight = upInfo.height;
    int refWidth = refInfo.width;
    int refHeight = refInfo.height;
    
    LOGI("ReferenceDetailTransfer: upscaled=%dx%d, ref=%dx%d, blend=%.2f",
         outWidth, outHeight, refWidth, refHeight, blendStrength);
    
    // Calculate scale factor
    float scaleX = (float)outWidth / refWidth;
    float scaleY = (float)outHeight / refHeight;
    
    uint8_t* upSrc = static_cast<uint8_t*>(upPixels);
    uint8_t* refSrc = static_cast<uint8_t*>(refPixels);
    uint8_t* dst = static_cast<uint8_t*>(outPixels);
    
    // First pass: copy upscaled to output
    memcpy(dst, upSrc, outHeight * outInfo.stride);
    
    // Second pass: extract and transfer high-frequency from reference
    // Use Laplacian (center - neighbors) as high-frequency
    const int kernelRadius = 1;
    
    int patchesTransferred = 0;
    float totalDetailAdded = 0.0f;
    
    for (int y = kernelRadius; y < outHeight - kernelRadius; ++y) {
        uint8_t* outRow = dst + y * outInfo.stride;
        
        for (int x = kernelRadius; x < outWidth - kernelRadius; ++x) {
            // Map output position to reference position
            float refX = x / scaleX;
            float refY = y / scaleY;
            int rx = (int)refX;
            int ry = (int)refY;
            
            if (rx < kernelRadius || rx >= refWidth - kernelRadius ||
                ry < kernelRadius || ry >= refHeight - kernelRadius) {
                continue;
            }
            
            // Compute Laplacian (high-frequency) at reference position
            // Laplacian = 4*center - top - bottom - left - right
            uint8_t* refCenter = refSrc + ry * refInfo.stride + rx * 4;
            uint8_t* refTop = refSrc + (ry-1) * refInfo.stride + rx * 4;
            uint8_t* refBot = refSrc + (ry+1) * refInfo.stride + rx * 4;
            uint8_t* refLeft = refSrc + ry * refInfo.stride + (rx-1) * 4;
            uint8_t* refRight = refSrc + ry * refInfo.stride + (rx+1) * 4;
            
            float lapR = 4.0f * refCenter[0] - refTop[0] - refBot[0] - refLeft[0] - refRight[0];
            float lapG = 4.0f * refCenter[1] - refTop[1] - refBot[1] - refLeft[1] - refRight[1];
            float lapB = 4.0f * refCenter[2] - refTop[2] - refBot[2] - refLeft[2] - refRight[2];
            
            // Compute magnitude of high-frequency
            float lapMag = sqrtf(lapR*lapR + lapG*lapG + lapB*lapB) / 255.0f;
            
            // Only transfer if reference has significant high-frequency
            if (lapMag > 0.05f) {
                // Compute Laplacian at output position
                uint8_t* outCenter = outRow + x * 4;
                uint8_t* outTop = dst + (y-1) * outInfo.stride + x * 4;
                uint8_t* outBot = dst + (y+1) * outInfo.stride + x * 4;
                uint8_t* outLeft = outRow + (x-1) * 4;
                uint8_t* outRight = outRow + (x+1) * 4;
                
                float outLapR = 4.0f * outCenter[0] - outTop[0] - outBot[0] - outLeft[0] - outRight[0];
                float outLapG = 4.0f * outCenter[1] - outTop[1] - outBot[1] - outLeft[1] - outRight[1];
                float outLapB = 4.0f * outCenter[2] - outTop[2] - outBot[2] - outLeft[2] - outRight[2];
                
                float outLapMag = sqrtf(outLapR*outLapR + outLapG*outLapG + outLapB*outLapB) / 255.0f;
                
                // Transfer high-frequency if reference has more detail
                if (lapMag > outLapMag * 1.2f) {
                    // Scale Laplacian by scale factor (detail gets spread over more pixels)
                    float scaledLapR = lapR / scaleX;
                    float scaledLapG = lapG / scaleX;
                    float scaledLapB = lapB / scaleX;
                    
                    // Adaptive blend based on detail difference
                    float detailRatio = (lapMag - outLapMag) / (lapMag + 0.01f);
                    float adaptiveBlend = blendStrength * detailRatio;
                    
                    // Add high-frequency to output
                    int idx = x * 4;
                    float newR = outRow[idx] + adaptiveBlend * scaledLapR;
                    float newG = outRow[idx+1] + adaptiveBlend * scaledLapG;
                    float newB = outRow[idx+2] + adaptiveBlend * scaledLapB;
                    
                    outRow[idx] = (uint8_t)fmaxf(0, fminf(255, newR));
                    outRow[idx+1] = (uint8_t)fmaxf(0, fminf(255, newG));
                    outRow[idx+2] = (uint8_t)fmaxf(0, fminf(255, newB));
                    
                    patchesTransferred++;
                    totalDetailAdded += adaptiveBlend * lapMag;
                }
            }
        }
    }
    
    AndroidBitmap_unlockPixels(env, upscaledBitmap);
    AndroidBitmap_unlockPixels(env, referenceBitmap);
    AndroidBitmap_unlockPixels(env, outputBitmap);
    
    float avgDetail = patchesTransferred > 0 ? totalDetailAdded / patchesTransferred : 0;
    LOGI("ReferenceDetailTransfer: %d patches transferred, avg detail=%.4f", 
         patchesTransferred, avgDetail);
    
    return 0;
}

/**
 * Tiled texture synthesis with hybrid CPU-GPU processing (Phase 2)
 * 
 * @param inputBitmap Input bitmap
 * @param outputBitmap Output bitmap (must be same size)
 * @param tileSize Base tile size (core region)
 * @param overlap Overlap between tiles
 * @param useGPU Enable GPU processing for even tiles
 * @param numCPUThreads Number of CPU worker threads
 * @return 0 on success, negative on error
 */
JNIEXPORT jint JNICALL
Java_com_imagedit_app_ultradetail_NativeMFSRPipeline_nativeTextureSynthesisTiled(
    JNIEnv* env,
    jclass clazz,
    jobject inputBitmap,
    jobject outputBitmap,
    jint tileSize,
    jint overlap,
    jboolean useGPU,
    jint numCPUThreads
) {
    AndroidBitmapInfo inInfo, outInfo;
    void* inPixels;
    void* outPixels;
    
    if (AndroidBitmap_getInfo(env, inputBitmap, &inInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, outputBitmap, &outInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("TiledTextureSynth: Failed to get bitmap info");
        return -1;
    }
    
    if (AndroidBitmap_lockPixels(env, inputBitmap, &inPixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, outputBitmap, &outPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("TiledTextureSynth: Failed to lock pixels");
        return -2;
    }
    
    int width = inInfo.width;
    int height = inInfo.height;
    
    // Convert to RGBImage
    RGBImage input;
    input.resize(width, height);
    
    uint8_t* src = static_cast<uint8_t*>(inPixels);
    for (int y = 0; y < height; ++y) {
        uint8_t* row = src + y * inInfo.stride;
        for (int x = 0; x < width; ++x) {
            int idx = x * 4;
            input.at(x, y) = RGBPixel(
                row[idx] / 255.0f,
                row[idx + 1] / 255.0f,
                row[idx + 2] / 255.0f
            );
        }
    }
    
    AndroidBitmap_unlockPixels(env, inputBitmap);
    
    // Configure tiled synthesis
    TileSynthConfig config;
    config.tileSize = tileSize;
    config.overlap = overlap;
    config.useGPU = useGPU;
    config.numCPUThreads = numCPUThreads;
    config.mode = TileScheduleMode::ALTERNATING;
    
    // Phase 1 optimizations in base params
    // Optimized parameters based on analysis:
    // - Lower variance threshold to process more regions (was 0.01f)
    // - Increase search radius for better patch matching (was 20)
    config.synthParams.patchSize = 7;
    config.synthParams.searchRadius = 32;
    config.synthParams.blendWeight = 0.4f;
    config.synthParams.varianceThreshold = 0.003f;
    
    // Run tiled synthesis
    TiledTextureSynthProcessor processor(config);
    TextureSynthResult result = processor.synthesize(input);
    
    if (!result.success) {
        AndroidBitmap_unlockPixels(env, outputBitmap);
        LOGE("TiledTextureSynth: Synthesis failed");
        return -3;
    }
    
    // Copy to output
    uint8_t* dst = static_cast<uint8_t*>(outPixels);
    for (int y = 0; y < height; ++y) {
        uint8_t* row = dst + y * outInfo.stride;
        for (int x = 0; x < width; ++x) {
            const RGBPixel& p = result.synthesized.at(x, y);
            int idx = x * 4;
            row[idx] = static_cast<uint8_t>(clamp(p.r * 255.0f, 0.0f, 255.0f));
            row[idx + 1] = static_cast<uint8_t>(clamp(p.g * 255.0f, 0.0f, 255.0f));
            row[idx + 2] = static_cast<uint8_t>(clamp(p.b * 255.0f, 0.0f, 255.0f));
            row[idx + 3] = 255;
        }
    }
    
    AndroidBitmap_unlockPixels(env, outputBitmap);
    
    LOGI("TiledTextureSynth: %dx%d, patches=%d, avgDetail=%.3f, GPU=%s",
         width, height, result.patchesProcessed, result.avgDetailAdded,
         processor.isGPUAvailable() ? "yes" : "no");
    
    return 0;
}

/**
 * Tiled texture synthesis with progress callback
 * 
 * @param inputBitmap Input bitmap
 * @param outputBitmap Output bitmap (same size as input)
 * @param tileSize Base tile size
 * @param overlap Overlap between tiles
 * @param useGPU Enable GPU processing for even tiles
 * @param numCPUThreads Number of CPU worker threads
 * @param callback Progress callback object
 * @return 0 on success, negative on error
 */
JNIEXPORT jint JNICALL
Java_com_imagedit_app_ultradetail_NativeMFSRPipeline_nativeTextureSynthesisTiledWithProgress(
    JNIEnv* env,
    jclass clazz,
    jobject inputBitmap,
    jobject outputBitmap,
    jint tileSize,
    jint overlap,
    jboolean useGPU,
    jint numCPUThreads,
    jobject callback
) {
    AndroidBitmapInfo inInfo, outInfo;
    void* inPixels;
    void* outPixels;
    
    if (AndroidBitmap_getInfo(env, inputBitmap, &inInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, outputBitmap, &outInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("TiledTextureSynth: Failed to get bitmap info");
        return -1;
    }
    
    if (AndroidBitmap_lockPixels(env, inputBitmap, &inPixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, outputBitmap, &outPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("TiledTextureSynth: Failed to lock pixels");
        return -2;
    }
    
    int width = inInfo.width;
    int height = inInfo.height;
    
    // Convert to RGBImage
    RGBImage input;
    input.resize(width, height);
    
    uint8_t* src = static_cast<uint8_t*>(inPixels);
    for (int y = 0; y < height; ++y) {
        uint8_t* row = src + y * inInfo.stride;
        for (int x = 0; x < width; ++x) {
            int idx = x * 4;
            input.at(x, y) = RGBPixel(
                row[idx] / 255.0f,
                row[idx + 1] / 255.0f,
                row[idx + 2] / 255.0f
            );
        }
    }
    
    AndroidBitmap_unlockPixels(env, inputBitmap);
    
    // Setup callback if provided
    jclass callbackClass = nullptr;
    jmethodID onProgressMethod = nullptr;
    jobject globalCallback = nullptr;
    
    if (callback != nullptr) {
        callbackClass = env->GetObjectClass(callback);
        onProgressMethod = env->GetMethodID(callbackClass, "onProgress", "(IIII)V");
        globalCallback = env->NewGlobalRef(callback);
    }
    
    // Configure tiled synthesis
    TileSynthConfig config;
    config.tileSize = tileSize;
    config.overlap = overlap;
    config.useGPU = useGPU;
    config.numCPUThreads = numCPUThreads;
    config.mode = TileScheduleMode::ALTERNATING;
    
    // Phase 1 optimizations in base params
    // Optimized parameters based on analysis:
    // - Lower variance threshold to process more regions (was 0.01f)
    // - Increase search radius for better patch matching (was 20)
    config.synthParams.patchSize = 7;
    config.synthParams.searchRadius = 32;
    config.synthParams.blendWeight = 0.4f;
    config.synthParams.varianceThreshold = 0.003f;
    
    // Store JNI env and callback for progress reporting
    // Note: We need to use a thread-safe approach since callbacks may come from worker threads
    JavaVM* jvm = nullptr;
    env->GetJavaVM(&jvm);
    
    // Set up progress callback that calls back to Java
    if (globalCallback != nullptr && onProgressMethod != nullptr) {
        config.progressCallback = [jvm, globalCallback, onProgressMethod](int completed, int total, float avgDetail) {
            JNIEnv* callbackEnv = nullptr;
            bool needsDetach = false;
            
            jint getEnvResult = jvm->GetEnv((void**)&callbackEnv, JNI_VERSION_1_6);
            if (getEnvResult == JNI_EDETACHED) {
                if (jvm->AttachCurrentThread(&callbackEnv, nullptr) == JNI_OK) {
                    needsDetach = true;
                } else {
                    LOGE("TiledTextureSynth: Failed to attach thread for callback");
                    return;
                }
            } else if (getEnvResult != JNI_OK) {
                LOGE("TiledTextureSynth: Failed to get JNI env for callback");
                return;
            }
            
            // Log every 10 tiles for debugging
            if (completed % 10 == 0 || completed == total) {
                LOGD("TiledTextureSynth: Progress callback: %d/%d tiles", completed, total);
            }
            
            // Call the Java callback - pass completed, total, and split CPU/GPU (for now all CPU)
            callbackEnv->CallVoidMethod(globalCallback, onProgressMethod, 
                                        completed, total, completed, 0);
            
            // Check for Java exceptions
            if (callbackEnv->ExceptionCheck()) {
                LOGE("TiledTextureSynth: Exception in Java callback");
                callbackEnv->ExceptionDescribe();
                callbackEnv->ExceptionClear();
            }
            
            if (needsDetach) {
                jvm->DetachCurrentThread();
            }
        };
    } else {
        LOGW("TiledTextureSynth: No progress callback provided");
    }
    
    // Run tiled synthesis
    TiledTextureSynthProcessor processor(config);
    TextureSynthResult result = processor.synthesize(input);
    
    // Clean up global reference
    if (globalCallback != nullptr) {
        env->DeleteGlobalRef(globalCallback);
    }
    
    if (!result.success) {
        AndroidBitmap_unlockPixels(env, outputBitmap);
        LOGE("TiledTextureSynth: Synthesis failed");
        return -3;
    }
    
    // Copy to output
    uint8_t* dst = static_cast<uint8_t*>(outPixels);
    for (int y = 0; y < height; ++y) {
        uint8_t* row = dst + y * outInfo.stride;
        for (int x = 0; x < width; ++x) {
            const RGBPixel& p = result.synthesized.at(x, y);
            int idx = x * 4;
            row[idx] = static_cast<uint8_t>(clamp(p.r * 255.0f, 0.0f, 255.0f));
            row[idx + 1] = static_cast<uint8_t>(clamp(p.g * 255.0f, 0.0f, 255.0f));
            row[idx + 2] = static_cast<uint8_t>(clamp(p.b * 255.0f, 0.0f, 255.0f));
            row[idx + 3] = 255;
        }
    }
    
    AndroidBitmap_unlockPixels(env, outputBitmap);
    
    LOGI("TiledTextureSynth: %dx%d, patches=%d, avgDetail=%.3f, GPU=%s",
         width, height, result.patchesProcessed, result.avgDetailAdded,
         processor.isGPUAvailable() ? "yes" : "no");
    
    return 0;
}

/**
 * Mertens Exposure Fusion - Combine multiple exposures into HDR-like output
 * 
 * @param bitmapArray Array of input bitmaps (different exposures)
 * @param outputBitmap Output fused bitmap
 * @param contrastWeight Weight for contrast metric (default 1.0)
 * @param saturationWeight Weight for saturation metric (default 1.0)
 * @param exposureWeight Weight for well-exposedness metric (default 1.0)
 * @param pyramidLevels Number of pyramid levels (default 5)
 * @return 0 on success, negative on error
 */
JNIEXPORT jint JNICALL
Java_com_imagedit_app_ultradetail_NativeMFSRPipeline_nativeExposureFusion(
    JNIEnv* env,
    jclass clazz,
    jobjectArray bitmapArray,
    jobject outputBitmap,
    jfloat contrastWeight,
    jfloat saturationWeight,
    jfloat exposureWeight,
    jint pyramidLevels
) {
    jsize numImages = env->GetArrayLength(bitmapArray);
    
    if (numImages < 2) {
        LOGE("ExposureFusion: Need at least 2 images, got %d", numImages);
        return -1;
    }
    
    LOGI("ExposureFusion: Fusing %d images", numImages);
    
    // Load all input images
    std::vector<RGBImage> images;
    images.reserve(numImages);
    
    AndroidBitmapInfo info;
    int width = 0, height = 0;
    
    for (jsize i = 0; i < numImages; ++i) {
        jobject bitmap = env->GetObjectArrayElement(bitmapArray, i);
        
        AndroidBitmapInfo bmpInfo;
        void* pixels;
        
        if (AndroidBitmap_getInfo(env, bitmap, &bmpInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
            AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
            LOGE("ExposureFusion: Failed to access bitmap %d", i);
            env->DeleteLocalRef(bitmap);
            return -2;
        }
        
        if (i == 0) {
            width = bmpInfo.width;
            height = bmpInfo.height;
        } else if (bmpInfo.width != width || bmpInfo.height != height) {
            LOGE("ExposureFusion: Size mismatch at image %d", i);
            AndroidBitmap_unlockPixels(env, bitmap);
            env->DeleteLocalRef(bitmap);
            return -3;
        }
        
        // Convert to RGBImage
        RGBImage img;
        img.resize(width, height);
        
        uint8_t* src = static_cast<uint8_t*>(pixels);
        for (int y = 0; y < height; ++y) {
            uint8_t* row = src + y * bmpInfo.stride;
            for (int x = 0; x < width; ++x) {
                int idx = x * 4;
                img.at(x, y) = RGBPixel(
                    row[idx] / 255.0f,
                    row[idx + 1] / 255.0f,
                    row[idx + 2] / 255.0f
                );
            }
        }
        
        images.push_back(img);
        
        AndroidBitmap_unlockPixels(env, bitmap);
        env->DeleteLocalRef(bitmap);
    }
    
    // Configure fusion
    ExposureFusionConfig config;
    config.contrastWeight = contrastWeight;
    config.saturationWeight = saturationWeight;
    config.exposureWeight = exposureWeight;
    config.pyramidLevels = pyramidLevels;
    
    // Perform fusion
    ExposureFusionProcessor processor(config);
    ExposureFusionResult result = processor.fuse(images);
    
    if (!result.success) {
        LOGE("ExposureFusion: Fusion failed");
        return -4;
    }
    
    // Copy to output bitmap
    AndroidBitmapInfo outInfo;
    void* outPixels;
    
    if (AndroidBitmap_getInfo(env, outputBitmap, &outInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, outputBitmap, &outPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("ExposureFusion: Failed to access output bitmap");
        return -5;
    }
    
    uint8_t* dst = static_cast<uint8_t*>(outPixels);
    for (int y = 0; y < height; ++y) {
        uint8_t* row = dst + y * outInfo.stride;
        for (int x = 0; x < width; ++x) {
            const RGBPixel& p = result.fused.at(x, y);
            int idx = x * 4;
            row[idx] = static_cast<uint8_t>(clamp(p.r * 255.0f, 0.0f, 255.0f));
            row[idx + 1] = static_cast<uint8_t>(clamp(p.g * 255.0f, 0.0f, 255.0f));
            row[idx + 2] = static_cast<uint8_t>(clamp(p.b * 255.0f, 0.0f, 255.0f));
            row[idx + 3] = 255;
        }
    }
    
    AndroidBitmap_unlockPixels(env, outputBitmap);
    
    LOGI("ExposureFusion: Complete - %dx%d from %d images", width, height, numImages);
    
    return 0;
}

} // extern "C"
