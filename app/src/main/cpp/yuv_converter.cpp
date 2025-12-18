/**
 * yuv_converter.cpp - YUV to RGB conversion implementation
 * 
 * Optimized YUV_420_888 to RGB conversion with NEON acceleration.
 */

#include "yuv_converter.h"
#include "neon_utils.h"

namespace ultradetail {

// BT.601 LIMITED-RANGE YUV to RGB conversion coefficients
// Android cameras output limited-range YUV (Y: 16-235, UV: 16-240)
// R = 1.164 * (Y - 16) + 1.596 * (V - 128)
// G = 1.164 * (Y - 16) - 0.813 * (V - 128) - 0.391 * (U - 128)
// B = 1.164 * (Y - 16) + 2.018 * (U - 128)

constexpr float YUV_Y_SCALE = 1.164f;  // 255/219 for limited range
constexpr float YUV_Y_OFFSET = 16.0f;  // Y offset for limited range
constexpr float YUV_R_V = 1.596f;
constexpr float YUV_G_U = -0.391f;
constexpr float YUV_G_V = -0.813f;
constexpr float YUV_B_U = 2.018f;

void yuvToRgbFloat(const YUVFrame& yuv, RGBImage& output) {
    if (output.width != yuv.width || output.height != yuv.height) {
        output = RGBImage(yuv.width, yuv.height);
    }
    
    const int width = yuv.width;
    const int height = yuv.height;
    
#ifdef USE_NEON
    // NEON-optimized conversion with limited-range BT.601
    const float32x4_t v_16 = vdupq_n_f32(16.0f);
    const float32x4_t v_128 = vdupq_n_f32(128.0f);
    const float32x4_t v_y_scale = vdupq_n_f32(YUV_Y_SCALE);
    const float32x4_t v_r_v = vdupq_n_f32(YUV_R_V);
    const float32x4_t v_g_u = vdupq_n_f32(YUV_G_U);
    const float32x4_t v_g_v = vdupq_n_f32(YUV_G_V);
    const float32x4_t v_b_u = vdupq_n_f32(YUV_B_U);
    const float32x4_t v_255_inv = vdupq_n_f32(1.0f / 255.0f);
    const float32x4_t v_zero = vdupq_n_f32(0.0f);
    const float32x4_t v_one = vdupq_n_f32(1.0f);
    
    for (int y = 0; y < height; ++y) {
        const uint8_t* yRow = yuv.yPlane + y * yuv.yRowStride;
        const uint8_t* uRow = yuv.uPlane + (y / 2) * yuv.uvRowStride;
        const uint8_t* vRow = yuv.vPlane + (y / 2) * yuv.uvRowStride;
        RGBPixel* outRow = output.row(y);
        
        int x = 0;
        
        // Process 4 pixels at a time
        for (; x + 3 < width; x += 4) {
            // Load Y values
            uint8_t y0 = yRow[x];
            uint8_t y1 = yRow[x + 1];
            uint8_t y2 = yRow[x + 2];
            uint8_t y3 = yRow[x + 3];
            
            float yVals[4] = {
                static_cast<float>(y0),
                static_cast<float>(y1),
                static_cast<float>(y2),
                static_cast<float>(y3)
            };
            float32x4_t vY = vld1q_f32(yVals);
            
            // Load UV values (subsampled 2x2)
            int uvIdx0 = (x / 2) * yuv.uvPixelStride;
            int uvIdx1 = ((x + 2) / 2) * yuv.uvPixelStride;
            
            float uVals[4] = {
                static_cast<float>(uRow[uvIdx0]),
                static_cast<float>(uRow[uvIdx0]),
                static_cast<float>(uRow[uvIdx1]),
                static_cast<float>(uRow[uvIdx1])
            };
            float vVals[4] = {
                static_cast<float>(vRow[uvIdx0]),
                static_cast<float>(vRow[uvIdx0]),
                static_cast<float>(vRow[uvIdx1]),
                static_cast<float>(vRow[uvIdx1])
            };
            
            float32x4_t vU = vld1q_f32(uVals);
            float32x4_t vV = vld1q_f32(vVals);
            
            // Apply limited-range offset: Y - 16, UV - 128
            vY = vsubq_f32(vY, v_16);
            vY = vmulq_f32(vY, v_y_scale);  // Scale Y by 1.164
            vU = vsubq_f32(vU, v_128);
            vV = vsubq_f32(vV, v_128);
            
            // Compute RGB using BT.601 limited-range coefficients
            float32x4_t vR = vmlaq_f32(vY, vV, v_r_v);
            float32x4_t vG = vmlaq_f32(vmlaq_f32(vY, vU, v_g_u), vV, v_g_v);
            float32x4_t vB = vmlaq_f32(vY, vU, v_b_u);
            
            // Normalize to [0, 1] and clamp
            vR = vmulq_f32(vR, v_255_inv);
            vG = vmulq_f32(vG, v_255_inv);
            vB = vmulq_f32(vB, v_255_inv);
            
            vR = vmaxq_f32(vminq_f32(vR, v_one), v_zero);
            vG = vmaxq_f32(vminq_f32(vG, v_one), v_zero);
            vB = vmaxq_f32(vminq_f32(vB, v_one), v_zero);
            
            // Store results
            float rVals[4], gVals[4], bVals[4];
            vst1q_f32(rVals, vR);
            vst1q_f32(gVals, vG);
            vst1q_f32(bVals, vB);
            
            for (int i = 0; i < 4; ++i) {
                outRow[x + i] = RGBPixel(rVals[i], gVals[i], bVals[i]);
            }
        }
        
        // Handle remaining pixels with limited-range conversion
        for (; x < width; ++x) {
            float yVal = (static_cast<float>(yRow[x]) - YUV_Y_OFFSET) * YUV_Y_SCALE;
            int uvIdx = (x / 2) * yuv.uvPixelStride;
            float uVal = static_cast<float>(uRow[uvIdx]) - 128.0f;
            float vVal = static_cast<float>(vRow[uvIdx]) - 128.0f;
            
            float r = (yVal + YUV_R_V * vVal) / 255.0f;
            float g = (yVal + YUV_G_U * uVal + YUV_G_V * vVal) / 255.0f;
            float b = (yVal + YUV_B_U * uVal) / 255.0f;
            
            outRow[x] = RGBPixel(
                clamp(r, 0.0f, 1.0f),
                clamp(g, 0.0f, 1.0f),
                clamp(b, 0.0f, 1.0f)
            );
        }
    }
#else
    // Scalar fallback with limited-range BT.601 conversion
    for (int y = 0; y < height; ++y) {
        const uint8_t* yRow = yuv.yPlane + y * yuv.yRowStride;
        const uint8_t* uRow = yuv.uPlane + (y / 2) * yuv.uvRowStride;
        const uint8_t* vRow = yuv.vPlane + (y / 2) * yuv.uvRowStride;
        RGBPixel* outRow = output.row(y);
        
        for (int x = 0; x < width; ++x) {
            // Apply limited-range offset and scale
            float yVal = (static_cast<float>(yRow[x]) - YUV_Y_OFFSET) * YUV_Y_SCALE;
            int uvIdx = (x / 2) * yuv.uvPixelStride;
            float uVal = static_cast<float>(uRow[uvIdx]) - 128.0f;
            float vVal = static_cast<float>(vRow[uvIdx]) - 128.0f;
            
            float r = (yVal + YUV_R_V * vVal) / 255.0f;
            float g = (yVal + YUV_G_U * uVal + YUV_G_V * vVal) / 255.0f;
            float b = (yVal + YUV_B_U * uVal) / 255.0f;
            
            outRow[x] = RGBPixel(
                clamp(r, 0.0f, 1.0f),
                clamp(g, 0.0f, 1.0f),
                clamp(b, 0.0f, 1.0f)
            );
        }
    }
#endif
}

void yuvToGray(const YUVFrame& yuv, GrayImage& output) {
    if (output.width != yuv.width || output.height != yuv.height) {
        output = GrayImage(yuv.width, yuv.height);
    }
    
    const int width = yuv.width;
    const int height = yuv.height;
    
#ifdef USE_NEON
    // Limited-range conversion: (Y - 16) * 1.164 / 255
    const float32x4_t v_16 = vdupq_n_f32(16.0f);
    const float32x4_t v_scale = vdupq_n_f32(YUV_Y_SCALE / 255.0f);  // 1.164 / 255
    const float32x4_t v_zero = vdupq_n_f32(0.0f);
    const float32x4_t v_one = vdupq_n_f32(1.0f);
    
    for (int y = 0; y < height; ++y) {
        const uint8_t* yRow = yuv.yPlane + y * yuv.yRowStride;
        float* outRow = output.row(y);
        
        int x = 0;
        for (; x + 7 < width; x += 8) {
            float32x4_t v0 = neon::load_u8_to_f32_low(yRow + x);
            float32x4_t v1 = neon::load_u8_to_f32_high(yRow + x);
            
            // Apply limited-range offset and scale
            v0 = vsubq_f32(v0, v_16);
            v1 = vsubq_f32(v1, v_16);
            v0 = vmulq_f32(v0, v_scale);
            v1 = vmulq_f32(v1, v_scale);
            
            // Clamp to [0, 1]
            v0 = vmaxq_f32(vminq_f32(v0, v_one), v_zero);
            v1 = vmaxq_f32(vminq_f32(v1, v_one), v_zero);
            
            vst1q_f32(outRow + x, v0);
            vst1q_f32(outRow + x + 4, v1);
        }
        
        for (; x < width; ++x) {
            float val = (static_cast<float>(yRow[x]) - YUV_Y_OFFSET) * YUV_Y_SCALE / 255.0f;
            outRow[x] = clamp(val, 0.0f, 1.0f);
        }
    }
#else
    // Scalar fallback with limited-range conversion
    for (int y = 0; y < height; ++y) {
        const uint8_t* yRow = yuv.yPlane + y * yuv.yRowStride;
        float* outRow = output.row(y);
        
        for (int x = 0; x < width; ++x) {
            float val = (static_cast<float>(yRow[x]) - YUV_Y_OFFSET) * YUV_Y_SCALE / 255.0f;
            outRow[x] = clamp(val, 0.0f, 1.0f);
        }
    }
#endif
}

ImageStats computeImageStats(const RGBImage& image) {
    ImageStats stats;
    
    if (image.empty()) {
        return stats;
    }
    
#if ENABLE_IMAGE_STATS
    double sumR = 0, sumG = 0, sumB = 0;
    
    for (int y = 0; y < image.height; ++y) {
        const RGBPixel* row = image.row(y);
        for (int x = 0; x < image.width; ++x) {
            const RGBPixel& px = row[x];
            stats.totalPixels++;
            
            // Check for NaN
            if (std::isnan(px.r) || std::isnan(px.g) || std::isnan(px.b)) {
                stats.nanCount++;
                continue;
            }
            
            // Check for Inf
            if (std::isinf(px.r) || std::isinf(px.g) || std::isinf(px.b)) {
                stats.infCount++;
                continue;
            }
            
            // Check for out-of-range values
            if (px.r < 0.0f || px.r > 1.0f || 
                px.g < 0.0f || px.g > 1.0f || 
                px.b < 0.0f || px.b > 1.0f) {
                stats.outOfRangeCount++;
            }
            
            // Update min/max
            stats.minR = std::min(stats.minR, px.r);
            stats.maxR = std::max(stats.maxR, px.r);
            stats.minG = std::min(stats.minG, px.g);
            stats.maxG = std::max(stats.maxG, px.g);
            stats.minB = std::min(stats.minB, px.b);
            stats.maxB = std::max(stats.maxB, px.b);
            
            sumR += px.r;
            sumG += px.g;
            sumB += px.b;
        }
    }
    
    int validPixels = stats.totalPixels - stats.nanCount - stats.infCount;
    if (validPixels > 0) {
        stats.meanR = static_cast<float>(sumR / validPixels);
        stats.meanG = static_cast<float>(sumG / validPixels);
        stats.meanB = static_cast<float>(sumB / validPixels);
    }
#else
    // In release builds, just do a quick NaN/Inf scan without full stats
    stats.totalPixels = image.width * image.height;
    // Sample a subset of pixels for quick health check
    int step = std::max(1, (image.width * image.height) / 10000);  // Sample ~10k pixels max
    for (int i = 0; i < image.width * image.height; i += step) {
        int y = i / image.width;
        int x = i % image.width;
        const RGBPixel& px = image.at(x, y);
        if (std::isnan(px.r) || std::isnan(px.g) || std::isnan(px.b)) {
            stats.nanCount++;
        } else if (std::isinf(px.r) || std::isinf(px.g) || std::isinf(px.b)) {
            stats.infCount++;
        }
    }
    // Scale up counts to estimate total
    stats.nanCount *= step;
    stats.infCount *= step;
#endif
    
    return stats;
}

int sanitizeRGBImage(RGBImage& image) {
    int sanitizedCount = 0;
    
    for (int y = 0; y < image.height; ++y) {
        RGBPixel* row = image.row(y);
        for (int x = 0; x < image.width; ++x) {
            RGBPixel& px = row[x];
            bool needsSanitize = false;
            
            if (!std::isfinite(px.r)) { px.r = 0.0f; needsSanitize = true; }
            if (!std::isfinite(px.g)) { px.g = 0.0f; needsSanitize = true; }
            if (!std::isfinite(px.b)) { px.b = 0.0f; needsSanitize = true; }
            
            // Also clamp to valid range [0, 1]
            px.r = clamp(px.r, 0.0f, 1.0f);
            px.g = clamp(px.g, 0.0f, 1.0f);
            px.b = clamp(px.b, 0.0f, 1.0f);
            
            if (needsSanitize) sanitizedCount++;
        }
    }
    
    return sanitizedCount;
}

void rgbFloatToArgb(const RGBImage& input, uint8_t* output, int outputStride) {
    const int width = input.width;
    const int height = input.height;
    
    for (int y = 0; y < height; ++y) {
        const RGBPixel* inRow = input.row(y);
        uint8_t* outRow = output + y * outputStride;
        
        for (int x = 0; x < width; ++x) {
            const RGBPixel& px = inRow[x];
            int idx = x * 4;
            
            // RGBA format (Android Bitmap RGBA_8888)
            // floatToUint8 now handles NaN/Inf internally
            outRow[idx + 0] = floatToUint8(px.r);
            outRow[idx + 1] = floatToUint8(px.g);
            outRow[idx + 2] = floatToUint8(px.b);
            outRow[idx + 3] = 255;  // Alpha
        }
    }
}

void rgbToLuminance(const RGBImage& rgb, GrayImage& output) {
    if (output.width != rgb.width || output.height != rgb.height) {
        output = GrayImage(rgb.width, rgb.height);
    }
    
    const int width = rgb.width;
    const int height = rgb.height;
    
    // ITU-R BT.601 luminance coefficients
    constexpr float LUM_R = 0.299f;
    constexpr float LUM_G = 0.587f;
    constexpr float LUM_B = 0.114f;
    
#ifdef USE_NEON
    const float32x4_t v_lum_r = vdupq_n_f32(LUM_R);
    const float32x4_t v_lum_g = vdupq_n_f32(LUM_G);
    const float32x4_t v_lum_b = vdupq_n_f32(LUM_B);
#endif
    
    for (int y = 0; y < height; ++y) {
        const RGBPixel* inRow = rgb.row(y);
        float* outRow = output.row(y);
        
        for (int x = 0; x < width; ++x) {
            const RGBPixel& px = inRow[x];
            float lum = LUM_R * px.r + LUM_G * px.g + LUM_B * px.b;
            
            // Handle NaN/Inf - output 0 for invalid pixels
            if (!std::isfinite(lum)) {
                lum = 0.0f;
            }
            
            // Clamp to valid range
            outRow[x] = clamp(lum, 0.0f, 1.0f);
        }
    }
}

} // namespace ultradetail
