/**
 * pyramid.cpp - Gaussian and Laplacian pyramid implementation
 * 
 * NEON-optimized multi-scale pyramid construction for alignment.
 */

#include "pyramid.h"
#include "neon_utils.h"

namespace ultradetail {

// 5-tap Gaussian kernel: [1, 4, 6, 4, 1] / 16
static const float GAUSS_KERNEL[5] = {
    1.0f / 16.0f,
    4.0f / 16.0f,
    6.0f / 16.0f,
    4.0f / 16.0f,
    1.0f / 16.0f
};

/**
 * Apply 1D Gaussian blur horizontally
 */
static void gaussianBlurH(const GrayImage& src, GrayImage& dst) {
    const int width = src.width;
    const int height = src.height;
    
    if (dst.width != width || dst.height != height) {
        dst = GrayImage(width, height);
    }
    
    for (int y = 0; y < height; ++y) {
        const float* srcRow = src.row(y);
        float* dstRow = dst.row(y);
        
        for (int x = 0; x < width; ++x) {
            float sum = 0.0f;
            
            for (int k = -2; k <= 2; ++k) {
                int sx = clamp(x + k, 0, width - 1);
                sum += srcRow[sx] * GAUSS_KERNEL[k + 2];
            }
            
            dstRow[x] = sum;
        }
    }
}

/**
 * Apply 1D Gaussian blur vertically
 */
static void gaussianBlurV(const GrayImage& src, GrayImage& dst) {
    const int width = src.width;
    const int height = src.height;
    
    if (dst.width != width || dst.height != height) {
        dst = GrayImage(width, height);
    }
    
    for (int y = 0; y < height; ++y) {
        float* dstRow = dst.row(y);
        
        for (int x = 0; x < width; ++x) {
            float sum = 0.0f;
            
            for (int k = -2; k <= 2; ++k) {
                int sy = clamp(y + k, 0, height - 1);
                sum += src.at(x, sy) * GAUSS_KERNEL[k + 2];
            }
            
            dstRow[x] = sum;
        }
    }
}

void GaussianPyramid::downsample2x(const GrayImage& src, GrayImage& dst) {
    const int dstW = src.width / 2;
    const int dstH = src.height / 2;
    
    if (dstW < 1 || dstH < 1) {
        dst = GrayImage();
        return;
    }
    
    // Apply separable Gaussian blur
    GrayImage blurredH, blurred;
    gaussianBlurH(src, blurredH);
    gaussianBlurV(blurredH, blurred);
    
    // Subsample
    dst = GrayImage(dstW, dstH);
    
#ifdef USE_NEON
    for (int y = 0; y < dstH; ++y) {
        const float* srcRow = blurred.row(y * 2);
        float* dstRow = dst.row(y);
        
        int x = 0;
        for (; x + 3 < dstW; x += 4) {
            // Load 8 source pixels, take every other one
            float vals[4] = {
                srcRow[x * 2],
                srcRow[x * 2 + 2],
                srcRow[x * 2 + 4],
                srcRow[x * 2 + 6]
            };
            vst1q_f32(dstRow + x, vld1q_f32(vals));
        }
        
        for (; x < dstW; ++x) {
            dstRow[x] = srcRow[x * 2];
        }
    }
#else
    for (int y = 0; y < dstH; ++y) {
        const float* srcRow = blurred.row(y * 2);
        float* dstRow = dst.row(y);
        
        for (int x = 0; x < dstW; ++x) {
            dstRow[x] = srcRow[x * 2];
        }
    }
#endif
}

void GaussianPyramid::build(const GrayImage& image, int numLevels) {
    levels_.clear();
    levels_.reserve(numLevels);
    
    // Level 0 is the original image
    levels_.push_back(image);
    
    // Build subsequent levels
    for (int i = 1; i < numLevels; ++i) {
        GrayImage downsampled;
        downsample2x(levels_[i - 1], downsampled);
        
        if (downsampled.empty() || downsampled.width < 4 || downsampled.height < 4) {
            break;
        }
        
        levels_.push_back(std::move(downsampled));
    }
    
    LOGD("Built Gaussian pyramid with %d levels", static_cast<int>(levels_.size()));
}

const GrayImage& GaussianPyramid::getLevel(int level) const {
    return levels_[clamp(level, 0, static_cast<int>(levels_.size()) - 1)];
}

// RGB Pyramid implementation

static void gaussianBlurH_RGB(const RGBImage& src, RGBImage& dst) {
    const int width = src.width;
    const int height = src.height;
    
    if (dst.width != width || dst.height != height) {
        dst = RGBImage(width, height);
    }
    
    for (int y = 0; y < height; ++y) {
        const RGBPixel* srcRow = src.row(y);
        RGBPixel* dstRow = dst.row(y);
        
        for (int x = 0; x < width; ++x) {
            RGBPixel sum;
            
            for (int k = -2; k <= 2; ++k) {
                int sx = clamp(x + k, 0, width - 1);
                float w = GAUSS_KERNEL[k + 2];
                sum.r += srcRow[sx].r * w;
                sum.g += srcRow[sx].g * w;
                sum.b += srcRow[sx].b * w;
            }
            
            dstRow[x] = sum;
        }
    }
}

static void gaussianBlurV_RGB(const RGBImage& src, RGBImage& dst) {
    const int width = src.width;
    const int height = src.height;
    
    if (dst.width != width || dst.height != height) {
        dst = RGBImage(width, height);
    }
    
    for (int y = 0; y < height; ++y) {
        RGBPixel* dstRow = dst.row(y);
        
        for (int x = 0; x < width; ++x) {
            RGBPixel sum;
            
            for (int k = -2; k <= 2; ++k) {
                int sy = clamp(y + k, 0, height - 1);
                float w = GAUSS_KERNEL[k + 2];
                const RGBPixel& px = src.at(x, sy);
                sum.r += px.r * w;
                sum.g += px.g * w;
                sum.b += px.b * w;
            }
            
            dstRow[x] = sum;
        }
    }
}

void RGBPyramid::downsample2x(const RGBImage& src, RGBImage& dst) {
    const int dstW = src.width / 2;
    const int dstH = src.height / 2;
    
    if (dstW < 1 || dstH < 1) {
        dst = RGBImage();
        return;
    }
    
    RGBImage blurredH, blurred;
    gaussianBlurH_RGB(src, blurredH);
    gaussianBlurV_RGB(blurredH, blurred);
    
    dst = RGBImage(dstW, dstH);
    
    for (int y = 0; y < dstH; ++y) {
        const RGBPixel* srcRow = blurred.row(y * 2);
        RGBPixel* dstRow = dst.row(y);
        
        for (int x = 0; x < dstW; ++x) {
            dstRow[x] = srcRow[x * 2];
        }
    }
}

void RGBPyramid::build(const RGBImage& image, int numLevels) {
    levels_.clear();
    levels_.reserve(numLevels);
    
    levels_.push_back(image);
    
    for (int i = 1; i < numLevels; ++i) {
        RGBImage downsampled;
        downsample2x(levels_[i - 1], downsampled);
        
        if (downsampled.empty() || downsampled.width < 4 || downsampled.height < 4) {
            break;
        }
        
        levels_.push_back(std::move(downsampled));
    }
}

const RGBImage& RGBPyramid::getLevel(int level) const {
    return levels_[clamp(level, 0, static_cast<int>(levels_.size()) - 1)];
}

// Laplacian Pyramid implementation

void LaplacianPyramid::upsample2x(const GrayImage& src, GrayImage& dst, int targetW, int targetH) {
    dst = GrayImage(targetW, targetH);
    
    for (int y = 0; y < targetH; ++y) {
        float* dstRow = dst.row(y);
        float srcY = static_cast<float>(y) / 2.0f;
        int sy0 = static_cast<int>(srcY);
        int sy1 = std::min(sy0 + 1, src.height - 1);
        float fy = srcY - sy0;
        
        for (int x = 0; x < targetW; ++x) {
            float srcX = static_cast<float>(x) / 2.0f;
            int sx0 = static_cast<int>(srcX);
            int sx1 = std::min(sx0 + 1, src.width - 1);
            float fx = srcX - sx0;
            
            // Bilinear interpolation
            float v00 = src.at(sx0, sy0);
            float v10 = src.at(sx1, sy0);
            float v01 = src.at(sx0, sy1);
            float v11 = src.at(sx1, sy1);
            
            float top = v00 * (1.0f - fx) + v10 * fx;
            float bot = v01 * (1.0f - fx) + v11 * fx;
            
            dstRow[x] = top * (1.0f - fy) + bot * fy;
        }
    }
}

void LaplacianPyramid::build(const GrayImage& image, int numLevels) {
    details_.clear();
    
    GaussianPyramid gauss;
    gauss.build(image, numLevels);
    
    int actualLevels = gauss.numLevels();
    details_.reserve(actualLevels - 1);
    
    // Compute Laplacian at each level (difference between level and upsampled next level)
    for (int i = 0; i < actualLevels - 1; ++i) {
        const GrayImage& current = gauss.getLevel(i);
        const GrayImage& next = gauss.getLevel(i + 1);
        
        GrayImage upsampled;
        upsample2x(next, upsampled, current.width, current.height);
        
        GrayImage detail(current.width, current.height);
        for (int y = 0; y < current.height; ++y) {
            const float* curRow = current.row(y);
            const float* upRow = upsampled.row(y);
            float* detRow = detail.row(y);
            
            for (int x = 0; x < current.width; ++x) {
                detRow[x] = curRow[x] - upRow[x];
            }
        }
        
        details_.push_back(std::move(detail));
    }
    
    // Store residual (lowest frequency)
    residual_ = gauss.getLevel(actualLevels - 1);
}

void LaplacianPyramid::reconstruct(GrayImage& output) const {
    if (details_.empty()) {
        output = GrayImage();
        return;
    }
    
    // Start from residual and add details back
    GrayImage current = residual_;
    
    for (int i = static_cast<int>(details_.size()) - 1; i >= 0; --i) {
        const GrayImage& detail = details_[i];
        
        GrayImage upsampled;
        upsample2x(current, upsampled, detail.width, detail.height);
        
        current = GrayImage(detail.width, detail.height);
        for (int y = 0; y < detail.height; ++y) {
            const float* upRow = upsampled.row(y);
            const float* detRow = detail.row(y);
            float* outRow = current.row(y);
            
            for (int x = 0; x < detail.width; ++x) {
                outRow[x] = upRow[x] + detRow[x];
            }
        }
    }
    
    output = std::move(current);
}

} // namespace ultradetail
