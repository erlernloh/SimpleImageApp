/**
 * common.h - Common definitions for Ultra Detail+ pipeline
 * 
 * Contains shared types, constants, and utility macros used across
 * the native image processing pipeline.
 */

#ifndef ULTRADETAIL_COMMON_H
#define ULTRADETAIL_COMMON_H

#include <cstdint>
#include <cmath>
#include <algorithm>
#include <vector>
#include <memory>
#include <android/log.h>

// Logging macros
#define LOG_TAG "UltraDetail"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Debug/Release configuration
// Set to 0 for release builds to disable expensive diagnostics
#ifndef ULTRADETAIL_DEBUG
#define ULTRADETAIL_DEBUG 1
#endif

// Conditional debug logging - only in debug builds
#if ULTRADETAIL_DEBUG
#define LOGD_DEBUG(...) LOGD(__VA_ARGS__)
#define ENABLE_IMAGE_STATS 1
#else
#define LOGD_DEBUG(...) ((void)0)
#define ENABLE_IMAGE_STATS 0
#endif

// Processing constants
namespace ultradetail {

// Tile sizes for alignment and processing
constexpr int ALIGNMENT_TILE_SIZE = 32;      // HDR+ style tile size for alignment
constexpr int DETAIL_TILE_SIZE = 64;         // Tile size for detail mask
constexpr int SR_TILE_SIZE = 256;            // Super-resolution input tile size
constexpr int SR_OVERLAP = 32;               // Overlap for SR tile stitching

// Search window for tile alignment
constexpr int SEARCH_RADIUS = 8;             // ±8 pixels search window

// Pyramid levels
constexpr int MAX_PYRAMID_LEVELS = 4;

// Burst capture settings
constexpr int MIN_BURST_FRAMES = 8;
constexpr int MAX_BURST_FRAMES = 12;

// Edge detection thresholds (normalized [0,1] range)
// Note: RGB values are in [0,1] range, so gradients are small
constexpr float EDGE_THRESHOLD_LOW = 0.02f;    // ~5/255
constexpr float EDGE_THRESHOLD_HIGH = 0.10f;   // ~25/255
constexpr float DETAIL_TILE_THRESHOLD = 0.01f; // Low threshold to catch subtle detail

// Merge parameters
constexpr float TRIMMED_MEAN_RATIO = 0.2f;   // Trim 20% from each end
constexpr float WIENER_NOISE_VAR = 0.01f;    // Assumed noise variance

/**
 * RGB pixel structure (float32)
 */
struct RGBPixel {
    float r, g, b;
    
    RGBPixel() : r(0), g(0), b(0) {}
    RGBPixel(float r_, float g_, float b_) : r(r_), g(g_), b(b_) {}
    
    RGBPixel operator+(const RGBPixel& other) const {
        return RGBPixel(r + other.r, g + other.g, b + other.b);
    }
    
    RGBPixel operator*(float scale) const {
        return RGBPixel(r * scale, g * scale, b * scale);
    }
    
    RGBPixel& operator+=(const RGBPixel& other) {
        r += other.r; g += other.g; b += other.b;
        return *this;
    }
};

/**
 * Motion vector for tile alignment
 */
struct MotionVector {
    int dx, dy;
    float cost;
    
    MotionVector() : dx(0), dy(0), cost(std::numeric_limits<float>::max()) {}
    MotionVector(int dx_, int dy_, float cost_) : dx(dx_), dy(dy_), cost(cost_) {}
};

/**
 * Image buffer wrapper
 */
template<typename T>
class ImageBuffer {
public:
    std::vector<T> data;
    int width;
    int height;
    int stride;
    
    ImageBuffer() : width(0), height(0), stride(0) {}
    
    ImageBuffer(int w, int h) : width(w), height(h), stride(w) {
        data.resize(static_cast<size_t>(w) * h);
    }
    
    ImageBuffer(int w, int h, int s) : width(w), height(h), stride(s) {
        data.resize(static_cast<size_t>(s) * h);
    }
    
    T& at(int x, int y) {
        return data[y * stride + x];
    }
    
    const T& at(int x, int y) const {
        return data[y * stride + x];
    }
    
    T* row(int y) {
        return data.data() + y * stride;
    }
    
    const T* row(int y) const {
        return data.data() + y * stride;
    }
    
    void fill(const T& value) {
        std::fill(data.begin(), data.end(), value);
    }
    
    bool empty() const {
        return data.empty();
    }
    
    size_t size() const {
        return data.size();
    }
    
    void resize(int w, int h) {
        width = w;
        height = h;
        stride = w;
        data.resize(static_cast<size_t>(w) * h);
    }
    
    void resize(int w, int h, int s) {
        width = w;
        height = h;
        stride = s;
        data.resize(static_cast<size_t>(s) * h);
    }
};

// Type aliases
using GrayImage = ImageBuffer<float>;
using RGBImage = ImageBuffer<RGBPixel>;
using ByteImage = ImageBuffer<uint8_t>;
using MotionField = ImageBuffer<MotionVector>;

/**
 * Clamp value to range [min, max]
 */
template<typename T>
inline T clamp(T value, T minVal, T maxVal) {
    return std::max(minVal, std::min(maxVal, value));
}

/**
 * Convert float [0,1] to uint8 [0,255]
 * Handles NaN and Inf by treating them as 0
 */
inline uint8_t floatToUint8(float value) {
    // Handle NaN and Inf - treat as black (0)
    if (!std::isfinite(value)) {
        return 0;
    }
    return static_cast<uint8_t>(clamp(value * 255.0f + 0.5f, 0.0f, 255.0f));
}

/**
 * Convert uint8 [0,255] to float [0,1]
 */
inline float uint8ToFloat(uint8_t value) {
    return static_cast<float>(value) / 255.0f;
}

/**
 * Sanitize a float value - replace NaN/Inf with fallback
 */
inline float sanitizeFloat(float value, float fallback = 0.0f) {
    return std::isfinite(value) ? value : fallback;
}

/**
 * Check if a float value is valid (finite and in expected range)
 */
inline bool isValidPixelValue(float value) {
    return std::isfinite(value) && value >= -1.0f && value <= 2.0f;
}

/**
 * Image statistics for diagnostics
 * 
 * IMPORTANT: Non-zero NaN/Inf counts indicate a bug in the pipeline that should
 * be investigated. The sanitization code is a safety net, not a fix.
 */
struct ImageStats {
    float minR, maxR, meanR;
    float minG, maxG, meanG;
    float minB, maxB, meanB;
    int nanCount;
    int infCount;
    int totalPixels;
    int outOfRangeCount;  // Pixels outside [0,1] range
    
    ImageStats() : minR(1e9f), maxR(-1e9f), meanR(0),
                   minG(1e9f), maxG(-1e9f), meanG(0),
                   minB(1e9f), maxB(-1e9f), meanB(0),
                   nanCount(0), infCount(0), totalPixels(0), outOfRangeCount(0) {}
    
    // Check if the image is numerically healthy (acceptance criteria)
    bool isHealthy() const {
        return nanCount == 0 && infCount == 0;
    }
    
    // Get percentage of invalid pixels
    float invalidPercentage() const {
        if (totalPixels == 0) return 0.0f;
        return 100.0f * (nanCount + infCount) / totalPixels;
    }
    
    // Log with appropriate severity based on health
    void log(const char* prefix) const {
        int invalidCount = nanCount + infCount;
        
        if (invalidCount == 0) {
            // Healthy - use INFO level
            LOGI("%s: R[%.3f,%.3f] G[%.3f,%.3f] B[%.3f,%.3f] mean=[%.3f,%.3f,%.3f] pixels=%d [HEALTHY]",
                 prefix, minR, maxR, minG, maxG, minB, maxB, 
                 meanR, meanG, meanB, totalPixels);
        } else if (invalidPercentage() < 0.1f) {
            // Minor issue - use WARNING
            LOGW("%s: R[%.3f,%.3f] G[%.3f,%.3f] B[%.3f,%.3f] NaN=%d Inf=%d (%.4f%%) [MINOR BUG - investigate]",
                 prefix, minR, maxR, minG, maxG, minB, maxB,
                 nanCount, infCount, invalidPercentage());
        } else {
            // Serious issue - use ERROR (this is a bug that needs fixing)
            LOGE("%s: R[%.3f,%.3f] G[%.3f,%.3f] B[%.3f,%.3f] NaN=%d Inf=%d (%.2f%%) [SERIOUS BUG - fix required!]",
                 prefix, minR, maxR, minG, maxG, minB, maxB,
                 nanCount, infCount, invalidPercentage());
        }
        
        // Log out-of-range warning separately if significant
        if (outOfRangeCount > 0 && totalPixels > 0) {
            float oorPercent = 100.0f * outOfRangeCount / totalPixels;
            if (oorPercent > 1.0f) {
                LOGW("%s: %d pixels (%.2f%%) outside [0,1] range - check upstream math",
                     prefix, outOfRangeCount, oorPercent);
            }
        }
    }
};

} // namespace ultradetail

#endif // ULTRADETAIL_COMMON_H
