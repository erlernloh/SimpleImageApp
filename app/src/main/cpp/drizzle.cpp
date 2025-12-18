/**
 * drizzle.cpp - Drizzle Algorithm Implementation
 * 
 * Implements sub-pixel interlacing for super-resolution.
 */

#include "drizzle.h"
#include "neon_utils.h"
#include <cmath>
#include <algorithm>

namespace ultradetail {

DrizzleProcessor::DrizzleProcessor(const DrizzleParams& params)
    : params_(params) {
}

float DrizzleProcessor::computeDropWeight(float dx, float dy, float dropRadius) const {
    float dist = std::sqrt(dx * dx + dy * dy);
    
    if (dist >= dropRadius) {
        return 0.0f;
    }
    
    // Weight falls off from center
    float normalized = dist / dropRadius;
    return std::pow(1.0f - normalized, params_.weightPower);
}

void DrizzleProcessor::drizzlePixel(
    std::vector<DrizzleAccumulator>& accum,
    int outWidth, int outHeight,
    float inX, float inY,
    const RGBPixel& color,
    float frameWeight
) {
    const int scale = params_.scaleFactor;
    const float dropRadius = params_.pixfrac * scale * 0.5f;
    
    // Convert input coordinates to output coordinates
    float outX = inX * scale;
    float outY = inY * scale;
    
    // Determine affected output pixels
    int minX = std::max(0, static_cast<int>(std::floor(outX - dropRadius)));
    int maxX = std::min(outWidth - 1, static_cast<int>(std::ceil(outX + dropRadius)));
    int minY = std::max(0, static_cast<int>(std::floor(outY - dropRadius)));
    int maxY = std::min(outHeight - 1, static_cast<int>(std::ceil(outY + dropRadius)));
    
    // Drizzle onto affected pixels
    for (int oy = minY; oy <= maxY; ++oy) {
        for (int ox = minX; ox <= maxX; ++ox) {
            // Distance from output pixel center to drop center
            float dx = (ox + 0.5f) - outX;
            float dy = (oy + 0.5f) - outY;
            
            float dropWeight = computeDropWeight(dx, dy, dropRadius);
            
            if (dropWeight > params_.minWeight) {
                float totalWeight = dropWeight * frameWeight;
                accum[oy * outWidth + ox].add(color.r, color.g, color.b, totalWeight);
            }
        }
    }
}

void DrizzleProcessor::drizzlePixelGray(
    std::vector<float>& accumSum,
    std::vector<float>& accumWeight,
    int outWidth, int outHeight,
    float inX, float inY,
    float value,
    float frameWeight
) {
    const int scale = params_.scaleFactor;
    const float dropRadius = params_.pixfrac * scale * 0.5f;
    
    float outX = inX * scale;
    float outY = inY * scale;
    
    int minX = std::max(0, static_cast<int>(std::floor(outX - dropRadius)));
    int maxX = std::min(outWidth - 1, static_cast<int>(std::ceil(outX + dropRadius)));
    int minY = std::max(0, static_cast<int>(std::floor(outY - dropRadius)));
    int maxY = std::min(outHeight - 1, static_cast<int>(std::ceil(outY + dropRadius)));
    
    for (int oy = minY; oy <= maxY; ++oy) {
        for (int ox = minX; ox <= maxX; ++ox) {
            float dx = (ox + 0.5f) - outX;
            float dy = (oy + 0.5f) - outY;
            
            float dropWeight = computeDropWeight(dx, dy, dropRadius);
            
            if (dropWeight > params_.minWeight) {
                float totalWeight = dropWeight * frameWeight;
                int idx = oy * outWidth + ox;
                accumSum[idx] += value * totalWeight;
                accumWeight[idx] += totalWeight;
            }
        }
    }
}

DrizzleResult DrizzleProcessor::process(
    const std::vector<RGBImage>& frames,
    const std::vector<SubPixelShift>& shifts,
    int referenceIdx
) {
    DrizzleResult result;
    
    if (frames.empty() || frames.size() != shifts.size()) {
        LOGE("Drizzle: Invalid input (frames=%zu, shifts=%zu)", frames.size(), shifts.size());
        return result;
    }
    
    const int inWidth = frames[0].width;
    const int inHeight = frames[0].height;
    const int scale = params_.scaleFactor;
    
    result.outputWidth = inWidth * scale;
    result.outputHeight = inHeight * scale;
    
    // Initialize accumulators
    std::vector<DrizzleAccumulator> accum(result.outputWidth * result.outputHeight);
    
    LOGD("Drizzle: Processing %zu frames, %dx%d -> %dx%d (scale=%d, pixfrac=%.2f)",
         frames.size(), inWidth, inHeight, result.outputWidth, result.outputHeight,
         scale, params_.pixfrac);
    
    // Process each frame
    for (size_t f = 0; f < frames.size(); ++f) {
        const RGBImage& frame = frames[f];
        const SubPixelShift& shift = shifts[f];
        
        if (frame.width != inWidth || frame.height != inHeight) {
            LOGW("Drizzle: Frame %zu size mismatch, skipping", f);
            continue;
        }
        
        // Drizzle each input pixel
        for (int iy = 0; iy < inHeight; ++iy) {
            for (int ix = 0; ix < inWidth; ++ix) {
                // Apply sub-pixel shift
                float shiftedX = ix + shift.dx;
                float shiftedY = iy + shift.dy;
                
                // Skip if shifted outside bounds
                if (shiftedX < -0.5f || shiftedX >= inWidth - 0.5f ||
                    shiftedY < -0.5f || shiftedY >= inHeight - 0.5f) {
                    continue;
                }
                
                drizzlePixel(
                    accum,
                    result.outputWidth, result.outputHeight,
                    shiftedX, shiftedY,
                    frame.at(ix, iy),
                    shift.weight
                );
            }
        }
    }
    
    // Normalize accumulators to output
    result.output.resize(result.outputWidth, result.outputHeight);
    result.weightMap.resize(result.outputWidth, result.outputHeight);
    
    float totalCoverage = 0;
    int coveredPixels = 0;
    
    for (int y = 0; y < result.outputHeight; ++y) {
        for (int x = 0; x < result.outputWidth; ++x) {
            int idx = y * result.outputWidth + x;
            result.output.at(x, y) = accum[idx].normalize();
            result.weightMap.at(x, y) = accum[idx].sumWeight;
            
            if (accum[idx].sumWeight > 0) {
                totalCoverage += accum[idx].sumWeight;
                coveredPixels++;
            }
        }
    }
    
    result.avgCoverage = coveredPixels > 0 ? totalCoverage / coveredPixels : 0;
    result.success = coveredPixels > 0;
    
    LOGI("Drizzle: Complete, coverage=%.2f, covered=%d/%d pixels",
         result.avgCoverage, coveredPixels, result.outputWidth * result.outputHeight);
    
    return result;
}

DrizzleResult DrizzleProcessor::processGray(
    const std::vector<GrayImage>& frames,
    const std::vector<SubPixelShift>& shifts,
    int referenceIdx
) {
    DrizzleResult result;
    
    if (frames.empty() || frames.size() != shifts.size()) {
        LOGE("Drizzle: Invalid input");
        return result;
    }
    
    const int inWidth = frames[0].width;
    const int inHeight = frames[0].height;
    const int scale = params_.scaleFactor;
    
    result.outputWidth = inWidth * scale;
    result.outputHeight = inHeight * scale;
    
    const int outSize = result.outputWidth * result.outputHeight;
    std::vector<float> accumSum(outSize, 0);
    std::vector<float> accumWeight(outSize, 0);
    
    // Process each frame
    for (size_t f = 0; f < frames.size(); ++f) {
        const GrayImage& frame = frames[f];
        const SubPixelShift& shift = shifts[f];
        
        for (int iy = 0; iy < inHeight; ++iy) {
            for (int ix = 0; ix < inWidth; ++ix) {
                float shiftedX = ix + shift.dx;
                float shiftedY = iy + shift.dy;
                
                if (shiftedX < -0.5f || shiftedX >= inWidth - 0.5f ||
                    shiftedY < -0.5f || shiftedY >= inHeight - 0.5f) {
                    continue;
                }
                
                drizzlePixelGray(
                    accumSum, accumWeight,
                    result.outputWidth, result.outputHeight,
                    shiftedX, shiftedY,
                    frame.at(ix, iy),
                    shift.weight
                );
            }
        }
    }
    
    // Convert to RGB output (grayscale)
    result.output.resize(result.outputWidth, result.outputHeight);
    result.weightMap.resize(result.outputWidth, result.outputHeight);
    
    float totalCoverage = 0;
    int coveredPixels = 0;
    
    for (int y = 0; y < result.outputHeight; ++y) {
        for (int x = 0; x < result.outputWidth; ++x) {
            int idx = y * result.outputWidth + x;
            float w = accumWeight[idx];
            result.weightMap.at(x, y) = w;
            
            if (w > 0) {
                float val = clamp(accumSum[idx] / w, 0.0f, 1.0f);
                result.output.at(x, y) = RGBPixel(val, val, val);
                totalCoverage += w;
                coveredPixels++;
            } else {
                result.output.at(x, y) = RGBPixel(0, 0, 0);
            }
        }
    }
    
    result.avgCoverage = coveredPixels > 0 ? totalCoverage / coveredPixels : 0;
    result.success = coveredPixels > 0;
    
    return result;
}

std::vector<SubPixelShift> DrizzleProcessor::shiftsFromHomographies(
    const std::vector<HomographyMatrix>& homographies,
    int referenceIdx
) {
    std::vector<SubPixelShift> shifts(homographies.size());
    
    for (size_t i = 0; i < homographies.size(); ++i) {
        if (static_cast<int>(i) == referenceIdx) {
            shifts[i] = SubPixelShift(0, 0, 1.0f);
        } else {
            // Extract translation from homography
            // For small motions, H[2] and H[5] approximate dx and dy
            const float* H = homographies[i].data;
            
            // More accurate: transform origin and measure displacement
            float ox, oy;
            homographies[i].transform(0, 0, ox, oy);
            
            shifts[i] = SubPixelShift(-ox, -oy, 1.0f);
        }
    }
    
    return shifts;
}

} // namespace ultradetail
