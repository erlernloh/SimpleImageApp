/**
 * edge_detection.cpp - Edge detection and detail mask implementation
 * 
 * NEON-optimized Sobel/Scharr edge detection with tile-based
 * detail classification.
 */

#include "edge_detection.h"
#include "neon_utils.h"
#include <cmath>

namespace ultradetail {

// Sobel kernels
static const float SOBEL_X[3][3] = {
    {-1, 0, 1},
    {-2, 0, 2},
    {-1, 0, 1}
};

static const float SOBEL_Y[3][3] = {
    {-1, -2, -1},
    { 0,  0,  0},
    { 1,  2,  1}
};

// Scharr kernels (more accurate than Sobel)
static const float SCHARR_X[3][3] = {
    {-3,  0,  3},
    {-10, 0, 10},
    {-3,  0,  3}
};

static const float SCHARR_Y[3][3] = {
    {-3, -10, -3},
    { 0,   0,  0},
    { 3,  10,  3}
};

// Prewitt kernels
static const float PREWITT_X[3][3] = {
    {-1, 0, 1},
    {-1, 0, 1},
    {-1, 0, 1}
};

static const float PREWITT_Y[3][3] = {
    {-1, -1, -1},
    { 0,  0,  0},
    { 1,  1,  1}
};

EdgeDetector::EdgeDetector(const DetailMaskParams& params)
    : params_(params) {
}

void EdgeDetector::applySobel(const GrayImage& input, GrayImage& gradX, GrayImage& gradY) {
    int width = input.width;
    int height = input.height;
    
    gradX = GrayImage(width, height);
    gradY = GrayImage(width, height);
    
    // Apply 3x3 Sobel kernels
    for (int y = 1; y < height - 1; ++y) {
        const float* row0 = input.row(y - 1);
        const float* row1 = input.row(y);
        const float* row2 = input.row(y + 1);
        float* gxRow = gradX.row(y);
        float* gyRow = gradY.row(y);
        
#ifdef USE_NEON
        int x = 1;
        for (; x + 3 < width - 1; x += 4) {
            // Load 6 values per row for 4 output pixels
            float32x4_t gx = neon::sobel_x_3x3(row0 + x - 1, row1 + x - 1, row2 + x - 1);
            float32x4_t gy = neon::sobel_y_3x3(row0 + x - 1, row1 + x - 1, row2 + x - 1);
            
            vst1q_f32(gxRow + x, gx);
            vst1q_f32(gyRow + x, gy);
        }
        
        // Handle remaining pixels
        for (; x < width - 1; ++x) {
            float gx = 0, gy = 0;
            for (int ky = -1; ky <= 1; ++ky) {
                for (int kx = -1; kx <= 1; ++kx) {
                    float val = input.at(x + kx, y + ky);
                    gx += val * SOBEL_X[ky + 1][kx + 1];
                    gy += val * SOBEL_Y[ky + 1][kx + 1];
                }
            }
            gxRow[x] = gx;
            gyRow[x] = gy;
        }
#else
        for (int x = 1; x < width - 1; ++x) {
            float gx = 0, gy = 0;
            for (int ky = -1; ky <= 1; ++ky) {
                for (int kx = -1; kx <= 1; ++kx) {
                    float val = input.at(x + kx, y + ky);
                    gx += val * SOBEL_X[ky + 1][kx + 1];
                    gy += val * SOBEL_Y[ky + 1][kx + 1];
                }
            }
            gxRow[x] = gx;
            gyRow[x] = gy;
        }
#endif
    }
    
    // Handle borders (set to zero)
    for (int x = 0; x < width; ++x) {
        gradX.at(x, 0) = 0;
        gradX.at(x, height - 1) = 0;
        gradY.at(x, 0) = 0;
        gradY.at(x, height - 1) = 0;
    }
    for (int y = 0; y < height; ++y) {
        gradX.at(0, y) = 0;
        gradX.at(width - 1, y) = 0;
        gradY.at(0, y) = 0;
        gradY.at(width - 1, y) = 0;
    }
}

void EdgeDetector::applyScharr(const GrayImage& input, GrayImage& gradX, GrayImage& gradY) {
    int width = input.width;
    int height = input.height;
    
    gradX = GrayImage(width, height);
    gradY = GrayImage(width, height);
    
    for (int y = 1; y < height - 1; ++y) {
        float* gxRow = gradX.row(y);
        float* gyRow = gradY.row(y);
        
        for (int x = 1; x < width - 1; ++x) {
            float gx = 0, gy = 0;
            for (int ky = -1; ky <= 1; ++ky) {
                for (int kx = -1; kx <= 1; ++kx) {
                    float val = input.at(x + kx, y + ky);
                    gx += val * SCHARR_X[ky + 1][kx + 1];
                    gy += val * SCHARR_Y[ky + 1][kx + 1];
                }
            }
            // Normalize Scharr (sum of absolute weights = 32)
            gxRow[x] = gx / 32.0f;
            gyRow[x] = gy / 32.0f;
        }
    }
    
    // Handle borders
    for (int x = 0; x < width; ++x) {
        gradX.at(x, 0) = 0;
        gradX.at(x, height - 1) = 0;
        gradY.at(x, 0) = 0;
        gradY.at(x, height - 1) = 0;
    }
    for (int y = 0; y < height; ++y) {
        gradX.at(0, y) = 0;
        gradX.at(width - 1, y) = 0;
        gradY.at(0, y) = 0;
        gradY.at(width - 1, y) = 0;
    }
}

void EdgeDetector::applyPrewitt(const GrayImage& input, GrayImage& gradX, GrayImage& gradY) {
    int width = input.width;
    int height = input.height;
    
    gradX = GrayImage(width, height);
    gradY = GrayImage(width, height);
    
    for (int y = 1; y < height - 1; ++y) {
        float* gxRow = gradX.row(y);
        float* gyRow = gradY.row(y);
        
        for (int x = 1; x < width - 1; ++x) {
            float gx = 0, gy = 0;
            for (int ky = -1; ky <= 1; ++ky) {
                for (int kx = -1; kx <= 1; ++kx) {
                    float val = input.at(x + kx, y + ky);
                    gx += val * PREWITT_X[ky + 1][kx + 1];
                    gy += val * PREWITT_Y[ky + 1][kx + 1];
                }
            }
            gxRow[x] = gx;
            gyRow[x] = gy;
        }
    }
    
    // Handle borders
    for (int x = 0; x < width; ++x) {
        gradX.at(x, 0) = 0;
        gradX.at(x, height - 1) = 0;
        gradY.at(x, 0) = 0;
        gradY.at(x, height - 1) = 0;
    }
    for (int y = 0; y < height; ++y) {
        gradX.at(0, y) = 0;
        gradX.at(width - 1, y) = 0;
        gradY.at(0, y) = 0;
        gradY.at(width - 1, y) = 0;
    }
}

void EdgeDetector::computeMagnitude(const GrayImage& gradX, const GrayImage& gradY, GrayImage& magnitude) {
    int width = gradX.width;
    int height = gradX.height;
    
    magnitude = GrayImage(width, height);
    
#ifdef USE_NEON
    for (int y = 0; y < height; ++y) {
        const float* gxRow = gradX.row(y);
        const float* gyRow = gradY.row(y);
        float* magRow = magnitude.row(y);
        
        int x = 0;
        for (; x + 3 < width; x += 4) {
            float32x4_t gx = vld1q_f32(gxRow + x);
            float32x4_t gy = vld1q_f32(gyRow + x);
            float32x4_t mag = neon::gradient_magnitude(gx, gy);
            vst1q_f32(magRow + x, mag);
        }
        
        for (; x < width; ++x) {
            float gx = gxRow[x];
            float gy = gyRow[x];
            magRow[x] = std::sqrt(gx * gx + gy * gy);
        }
    }
#else
    for (int y = 0; y < height; ++y) {
        const float* gxRow = gradX.row(y);
        const float* gyRow = gradY.row(y);
        float* magRow = magnitude.row(y);
        
        for (int x = 0; x < width; ++x) {
            float gx = gxRow[x];
            float gy = gyRow[x];
            magRow[x] = std::sqrt(gx * gx + gy * gy);
        }
    }
#endif
}

void EdgeDetector::computeEdgeMagnitude(const GrayImage& luminance, GrayImage& output) {
    GrayImage gradX, gradY;
    
    switch (params_.edgeOperator) {
        case EdgeOperator::SOBEL:
            applySobel(luminance, gradX, gradY);
            break;
        case EdgeOperator::SCHARR:
            applyScharr(luminance, gradX, gradY);
            break;
        case EdgeOperator::PREWITT:
            applyPrewitt(luminance, gradX, gradY);
            break;
    }
    
    computeMagnitude(gradX, gradY, output);
}

void EdgeDetector::generateDetailMask(const GrayImage& edgeMagnitude, DetailMask& result) {
    int width = edgeMagnitude.width;
    int height = edgeMagnitude.height;
    int tileSize = params_.tileSize;
    
    // Validate input dimensions
    if (width <= 0 || height <= 0) {
        LOGE("Invalid edge magnitude dimensions: %dx%d", width, height);
        result.numDetailTiles = 0;
        result.numSmoothTiles = 0;
        result.averageEdgeMagnitude = 0.0f;
        return;
    }
    
    int numTilesX = (width + tileSize - 1) / tileSize;
    int numTilesY = (height + tileSize - 1) / tileSize;
    
    result.tileMask = ByteImage(numTilesX, numTilesY);
    result.edgeMagnitude = edgeMagnitude;
    result.numDetailTiles = 0;
    result.numSmoothTiles = 0;
    result.averageEdgeMagnitude = 0;
    
    float totalMagnitude = 0;
    int totalValidPixels = 0;
    int nanCount = 0;
    int infCount = 0;
    
    // Compute average edge magnitude per tile
    for (int ty = 0; ty < numTilesY; ++ty) {
        for (int tx = 0; tx < numTilesX; ++tx) {
            float tileSum = 0;
            int tileValidPixels = 0;
            
            int startX = tx * tileSize;
            int startY = ty * tileSize;
            int endX = std::min(startX + tileSize, width);
            int endY = std::min(startY + tileSize, height);
            
            for (int y = startY; y < endY; ++y) {
                const float* magRow = edgeMagnitude.row(y);
                for (int x = startX; x < endX; ++x) {
                    float val = magRow[x];
                    
                    // Skip NaN values
                    if (std::isnan(val)) {
                        nanCount++;
                        continue;
                    }
                    
                    // Skip Inf values
                    if (std::isinf(val)) {
                        infCount++;
                        continue;
                    }
                    
                    // Clamp negative values to 0 (edge magnitude should be non-negative)
                    val = std::max(0.0f, val);
                    
                    tileSum += val;
                    tileValidPixels++;
                }
            }
            
            // Guard against division by zero
            float avgMagnitude = (tileValidPixels > 0) ? tileSum / tileValidPixels : 0.0f;
            
            // Ensure result is finite
            if (!std::isfinite(avgMagnitude)) {
                avgMagnitude = 0.0f;
            }
            
            totalMagnitude += tileSum;
            totalValidPixels += tileValidPixels;
            
            // Classify tile
            bool isDetail = avgMagnitude >= params_.detailThreshold;
            result.tileMask.at(tx, ty) = isDetail ? 255 : 0;
            
            if (isDetail) {
                result.numDetailTiles++;
            } else {
                result.numSmoothTiles++;
            }
        }
    }
    
    // Guard against division by zero for overall average
    result.averageEdgeMagnitude = (totalValidPixels > 0) ? totalMagnitude / totalValidPixels : 0.0f;
    
    // Final sanity check
    if (!std::isfinite(result.averageEdgeMagnitude)) {
        result.averageEdgeMagnitude = 0.0f;
    }
    
    // Log invalid values as bugs - these indicate upstream problems
    if (nanCount > 0 || infCount > 0) {
        float invalidPercent = 100.0f * (nanCount + infCount) / (width * height);
        if (invalidPercent < 0.1f) {
            LOGW("Edge magnitude: %d NaN + %d Inf (%.4f%%) [MINOR BUG - investigate upstream]",
                 nanCount, infCount, invalidPercent);
        } else {
            LOGE("Edge magnitude: %d NaN + %d Inf (%.2f%%) [SERIOUS BUG - fix required!]",
                 nanCount, infCount, invalidPercent);
        }
    }
    
    // Apply morphological operations to smooth mask boundaries
    if (params_.applyMorphology && params_.dilationRadius > 0) {
        dilateMask(result.tileMask, params_.dilationRadius);
        
        // Recount after dilation
        result.numDetailTiles = 0;
        result.numSmoothTiles = 0;
        for (int ty = 0; ty < numTilesY; ++ty) {
            for (int tx = 0; tx < numTilesX; ++tx) {
                if (result.tileMask.at(tx, ty) > 0) {
                    result.numDetailTiles++;
                } else {
                    result.numSmoothTiles++;
                }
            }
        }
    }
    
    LOGD("Detail mask: %d detail tiles, %d smooth tiles, avg edge mag: %.3f (valid pixels: %d)",
         result.numDetailTiles, result.numSmoothTiles, result.averageEdgeMagnitude, totalValidPixels);
}

void EdgeDetector::detectDetails(const GrayImage& luminance, DetailMask& result) {
    GrayImage edgeMagnitude;
    computeEdgeMagnitude(luminance, edgeMagnitude);
    generateDetailMask(edgeMagnitude, result);
}

bool EdgeDetector::isDetailTile(const DetailMask& mask, int tileX, int tileY) {
    if (tileX < 0 || tileX >= mask.tileMask.width ||
        tileY < 0 || tileY >= mask.tileMask.height) {
        return false;
    }
    return mask.tileMask.at(tileX, tileY) > 0;
}

void EdgeDetector::getTileForPixel(int x, int y, int& tileX, int& tileY) const {
    tileX = x / params_.tileSize;
    tileY = y / params_.tileSize;
}

void EdgeDetector::dilateMask(ByteImage& mask, int radius) {
    int width = mask.width;
    int height = mask.height;
    
    ByteImage dilated(width, height);
    
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            uint8_t maxVal = 0;
            
            for (int dy = -radius; dy <= radius; ++dy) {
                int ny = clamp(y + dy, 0, height - 1);
                for (int dx = -radius; dx <= radius; ++dx) {
                    int nx = clamp(x + dx, 0, width - 1);
                    maxVal = std::max(maxVal, mask.at(nx, ny));
                }
            }
            
            dilated.at(x, y) = maxVal;
        }
    }
    
    mask = std::move(dilated);
}

void EdgeDetector::erodeMask(ByteImage& mask, int radius) {
    int width = mask.width;
    int height = mask.height;
    
    ByteImage eroded(width, height);
    
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            uint8_t minVal = 255;
            
            for (int dy = -radius; dy <= radius; ++dy) {
                int ny = clamp(y + dy, 0, height - 1);
                for (int dx = -radius; dx <= radius; ++dx) {
                    int nx = clamp(x + dx, 0, width - 1);
                    minVal = std::min(minVal, mask.at(nx, ny));
                }
            }
            
            eroded.at(x, y) = minVal;
        }
    }
    
    mask = std::move(eroded);
}

} // namespace ultradetail
