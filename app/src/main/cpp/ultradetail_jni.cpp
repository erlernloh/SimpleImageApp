/**
 * ultradetail_jni.cpp - JNI bridge for Ultra Detail+ pipeline
 * 
 * Exposes native burst processing functions to Kotlin/Java.
 */

#include <jni.h>
#include <android/bitmap.h>
#include "burst_processor.h"
#include "yuv_converter.h"

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
            // ARGB format
            int idx = x * 4;
            float r = row[idx + 1] / 255.0f;
            float g = row[idx + 2] / 255.0f;
            float b = row[idx + 3] / 255.0f;
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

} // extern "C"
