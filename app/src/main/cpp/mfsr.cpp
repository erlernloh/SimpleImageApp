/**
 * mfsr.cpp - Multi-Frame Super-Resolution implementation
 * 
 * Shift-and-add style super-resolution using sub-pixel aligned frames.
 */

#include "mfsr.h"
#include "neon_utils.h"
#include <cmath>
#include <algorithm>

namespace ultradetail {

MultiFrameSR::MultiFrameSR(const MFSRParams& params)
    : params_(params) {
}

float MultiFrameSR::lanczosWeight(float distance, float a) {
    if (distance == 0.0f) return 1.0f;
    if (std::abs(distance) >= a) return 0.0f;
    
    float pi_d = M_PI * distance;
    float pi_d_a = pi_d / a;
    return (std::sin(pi_d) / pi_d) * (std::sin(pi_d_a) / pi_d_a);
}

float MultiFrameSR::gaussianWeight(float distance, float sigma) {
    return std::exp(-(distance * distance) / (2.0f * sigma * sigma));
}

float MultiFrameSR::computeSubPixelSAD(
    const GrayImage& ref,
    const GrayImage& frame,
    int refX, int refY,
    float frameX, float frameY,
    int tileSize
) {
    float sad = 0.0f;
    int validPixels = 0;
    
    for (int dy = 0; dy < tileSize; ++dy) {
        int ry = refY + dy;
        float fy = frameY + dy;
        
        if (ry < 0 || ry >= ref.height) continue;
        
        for (int dx = 0; dx < tileSize; ++dx) {
            int rx = refX + dx;
            float fx = frameX + dx;
            
            if (rx < 0 || rx >= ref.width) continue;
            if (fx < 0 || fx >= frame.width - 1) continue;
            if (fy < 0 || fy >= frame.height - 1) continue;
            
            // Bilinear interpolation for sub-pixel sampling
            int x0 = static_cast<int>(std::floor(fx));
            int y0 = static_cast<int>(std::floor(fy));
            int x1 = x0 + 1;
            int y1 = y0 + 1;
            
            float wx = fx - x0;
            float wy = fy - y0;
            
            x0 = clamp(x0, 0, frame.width - 1);
            x1 = clamp(x1, 0, frame.width - 1);
            y0 = clamp(y0, 0, frame.height - 1);
            y1 = clamp(y1, 0, frame.height - 1);
            
            float p00 = frame.at(x0, y0);
            float p10 = frame.at(x1, y0);
            float p01 = frame.at(x0, y1);
            float p11 = frame.at(x1, y1);
            
            float interpolated = p00 * (1 - wx) * (1 - wy) +
                                 p10 * wx * (1 - wy) +
                                 p01 * (1 - wx) * wy +
                                 p11 * wx * wy;
            
            sad += std::abs(ref.at(rx, ry) - interpolated);
            validPixels++;
        }
    }
    
    return validPixels > 0 ? sad / validPixels : std::numeric_limits<float>::max();
}

SubPixelMotion MultiFrameSR::refineToSubPixel(
    const GrayImage& ref,
    const GrayImage& frame,
    int tileX, int tileY,
    int tileSize,
    const MotionVector& integerMotion
) {
    int refStartX = tileX * tileSize;
    int refStartY = tileY * tileSize;
    
    // Sample costs in a 3x3 grid around integer motion
    float costs[3][3];
    for (int dy = -1; dy <= 1; ++dy) {
        for (int dx = -1; dx <= 1; ++dx) {
            float frameX = refStartX + integerMotion.dx + dx;
            float frameY = refStartY + integerMotion.dy + dy;
            costs[dy + 1][dx + 1] = computeSubPixelSAD(
                ref, frame,
                refStartX, refStartY,
                frameX, frameY,
                tileSize
            );
        }
    }
    
    // Parabolic fitting for sub-pixel refinement
    float dxSub = 0.0f, dySub = 0.0f;
    
    // X direction parabola
    float ax = costs[1][0] + costs[1][2] - 2.0f * costs[1][1];
    if (std::abs(ax) > 1e-6f) {
        dxSub = (costs[1][0] - costs[1][2]) / (2.0f * ax);
        dxSub = clamp(dxSub, -0.5f, 0.5f);
    }
    
    // Y direction parabola
    float ay = costs[0][1] + costs[2][1] - 2.0f * costs[1][1];
    if (std::abs(ay) > 1e-6f) {
        dySub = (costs[0][1] - costs[2][1]) / (2.0f * ay);
        dySub = clamp(dySub, -0.5f, 0.5f);
    }
    
    // Compute confidence based on cost curvature
    float curvature = (ax + ay) / 2.0f;
    float confidence = curvature > 0 ? std::min(1.0f, curvature * 10.0f) : 0.5f;
    
    // Verify sub-pixel refinement improves cost
    float refinedCost = computeSubPixelSAD(
        ref, frame,
        refStartX, refStartY,
        refStartX + integerMotion.dx + dxSub,
        refStartY + integerMotion.dy + dySub,
        tileSize
    );
    
    if (refinedCost > costs[1][1] * 1.1f) {
        // Sub-pixel refinement made things worse, use integer motion
        dxSub = 0.0f;
        dySub = 0.0f;
        confidence *= 0.5f;
    }
    
    return SubPixelMotion(
        integerMotion.dx + dxSub,
        integerMotion.dy + dySub,
        confidence
    );
}

SubPixelMotionField MultiFrameSR::computeSubPixelMotion(
    const GrayImage& reference,
    const GrayImage& frame,
    const FrameAlignment& coarseAlignment
) {
    const MotionField& coarseMotion = coarseAlignment.motionField;
    
    // Handle empty motion field
    if (coarseMotion.width <= 0 || coarseMotion.height <= 0 || coarseMotion.empty()) {
        LOGW("MFSR: Empty motion field, creating default");
        int tilesX = (reference.width + params_.tileSize - 1) / params_.tileSize;
        int tilesY = (reference.height + params_.tileSize - 1) / params_.tileSize;
        SubPixelMotionField subPixelMotion(tilesX, tilesY);
        for (int ty = 0; ty < tilesY; ++ty) {
            for (int tx = 0; tx < tilesX; ++tx) {
                subPixelMotion.at(tx, ty) = SubPixelMotion(0, 0, 0.5f);
            }
        }
        return subPixelMotion;
    }
    
    SubPixelMotionField subPixelMotion(coarseMotion.width, coarseMotion.height);
    
    for (int ty = 0; ty < coarseMotion.height; ++ty) {
        for (int tx = 0; tx < coarseMotion.width; ++tx) {
            const MotionVector& mv = coarseMotion.at(tx, ty);
            
            SubPixelMotion refined = refineToSubPixel(
                reference, frame,
                tx, ty,
                params_.tileSize,
                mv
            );
            
            subPixelMotion.at(tx, ty) = refined;
        }
    }
    
    return subPixelMotion;
}

void MultiFrameSR::scatterToAccumulator(
    const RGBImage& frame,
    const SubPixelMotionField& motion,
    AccumulatorImage& accumulator,
    int scaleFactor
) {
    int outWidth = accumulator.width;
    int outHeight = accumulator.height;
    int inWidth = frame.width;
    int inHeight = frame.height;
    
    // Safety check for empty motion field
    if (motion.width <= 0 || motion.height <= 0 || motion.empty()) {
        LOGW("MFSR: Empty motion field in scatterToAccumulator, using identity");
        // Fall back to identity motion (just upscale without motion compensation)
        for (int y = 0; y < inHeight; ++y) {
            for (int x = 0; x < inWidth; ++x) {
                const RGBPixel& pixel = frame.at(x, y);
                float outX = static_cast<float>(x) * scaleFactor;
                float outY = static_cast<float>(y) * scaleFactor;
                int ox = static_cast<int>(outX);
                int oy = static_cast<int>(outY);
                if (ox >= 0 && ox < outWidth && oy >= 0 && oy < outHeight) {
                    accumulator.at(ox, oy).add(pixel, 1.0f);
                }
            }
        }
        return;
    }
    
    // For each input pixel, scatter to output grid
    for (int y = 0; y < inHeight; ++y) {
        for (int x = 0; x < inWidth; ++x) {
            // Get motion for this pixel's tile
            int tx = x / params_.tileSize;
            int ty = y / params_.tileSize;
            tx = clamp(tx, 0, motion.width - 1);
            ty = clamp(ty, 0, motion.height - 1);
            
            const SubPixelMotion& mv = motion.at(tx, ty);
            
            // Compute position in high-res grid
            // The reference frame maps directly; other frames are offset by motion
            float srcX = static_cast<float>(x) - mv.dx;
            float srcY = static_cast<float>(y) - mv.dy;
            
            // Scale to output resolution
            float outX = srcX * scaleFactor;
            float outY = srcY * scaleFactor;
            
            // Get the pixel value
            const RGBPixel& pixel = frame.at(x, y);
            
            // Scatter to nearby output pixels using weighted distribution
            int outX0 = static_cast<int>(std::floor(outX));
            int outY0 = static_cast<int>(std::floor(outY));
            
            // Distribute to 2x2 neighborhood (bilinear splatting)
            for (int dy = 0; dy <= 1; ++dy) {
                for (int dx = 0; dx <= 1; ++dx) {
                    int ox = outX0 + dx;
                    int oy = outY0 + dy;
                    
                    if (ox < 0 || ox >= outWidth || oy < 0 || oy >= outHeight) {
                        continue;
                    }
                    
                    // Compute weight based on distance
                    float distX = std::abs(outX - ox);
                    float distY = std::abs(outY - oy);
                    float dist = std::sqrt(distX * distX + distY * distY);
                    
                    float weight;
                    if (params_.useWeightedAccumulation) {
                        weight = gaussianWeight(dist, 0.7f) * mv.confidence;
                    } else {
                        weight = (1.0f - distX) * (1.0f - distY);
                    }
                    
                    if (weight > 0.01f) {
                        accumulator.at(ox, oy).add(pixel, weight);
                    }
                }
            }
        }
    }
}

void MultiFrameSR::fillGaps(AccumulatorImage& accumulator) {
    int width = accumulator.width;
    int height = accumulator.height;
    
    // Find pixels with no samples and fill from neighbors
    std::vector<std::pair<int, int>> gapPixels;
    
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            if (accumulator.at(x, y).sampleCount == 0) {
                gapPixels.push_back({x, y});
            }
        }
    }
    
    // Iteratively fill gaps from neighbors
    int maxIterations = 3;
    for (int iter = 0; iter < maxIterations && !gapPixels.empty(); ++iter) {
        std::vector<std::pair<int, int>> remainingGaps;
        
        for (const auto& gap : gapPixels) {
            int x = gap.first;
            int y = gap.second;
            
            AccumulatorPixel& pixel = accumulator.at(x, y);
            
            // Sample from 8-connected neighbors
            float totalWeight = 0.0f;
            RGBPixel sum;
            
            for (int dy = -1; dy <= 1; ++dy) {
                for (int dx = -1; dx <= 1; ++dx) {
                    if (dx == 0 && dy == 0) continue;
                    
                    int nx = x + dx;
                    int ny = y + dy;
                    
                    if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
                    
                    const AccumulatorPixel& neighbor = accumulator.at(nx, ny);
                    if (neighbor.sampleCount > 0) {
                        float dist = std::sqrt(static_cast<float>(dx * dx + dy * dy));
                        float w = 1.0f / dist;
                        RGBPixel np = neighbor.normalize();
                        sum.r += np.r * w;
                        sum.g += np.g * w;
                        sum.b += np.b * w;
                        totalWeight += w;
                    }
                }
            }
            
            if (totalWeight > 0) {
                pixel.r = sum.r;
                pixel.g = sum.g;
                pixel.b = sum.b;
                pixel.weight = totalWeight;
                pixel.sampleCount = 1;  // Mark as filled
            } else {
                remainingGaps.push_back(gap);
            }
        }
        
        gapPixels = std::move(remainingGaps);
    }
}

void MultiFrameSR::finalizeImage(const AccumulatorImage& accumulator, RGBImage& output) {
    output = RGBImage(accumulator.width, accumulator.height);
    
    for (int y = 0; y < output.height; ++y) {
        for (int x = 0; x < output.width; ++x) {
            output.at(x, y) = accumulator.at(x, y).normalize();
        }
    }
}

void MultiFrameSR::process(
    const std::vector<RGBImage>& frames,
    const std::vector<FrameAlignment>& alignments,
    int referenceIndex,
    MFSRResult& result,
    MFSRProgressCallback progressCallback
) {
    if (frames.empty()) {
        LOGE("MFSR: No frames provided");
        result.success = false;
        return;
    }
    
    if (frames.size() != alignments.size()) {
        LOGE("MFSR: Frame count (%zu) doesn't match alignment count (%zu)",
             frames.size(), alignments.size());
        result.success = false;
        return;
    }
    
    // Validate reference index
    if (referenceIndex < 0 || referenceIndex >= static_cast<int>(frames.size())) {
        LOGE("MFSR: Invalid reference index %d for %zu frames", referenceIndex, frames.size());
        result.success = false;
        return;
    }
    
    int inWidth = frames[0].width;
    int inHeight = frames[0].height;
    
    // Validate frame dimensions
    if (inWidth <= 0 || inHeight <= 0) {
        LOGE("MFSR: Invalid frame dimensions %dx%d", inWidth, inHeight);
        result.success = false;
        return;
    }
    
    int outWidth = inWidth * params_.scaleFactor;
    int outHeight = inHeight * params_.scaleFactor;
    
    LOGI("MFSR: Processing %zu frames, %dx%d -> %dx%d (scale=%d)",
         frames.size(), inWidth, inHeight, outWidth, outHeight, params_.scaleFactor);
    
    if (progressCallback) {
        progressCallback("Initializing MFSR", 0.0f);
    }
    
    // Create accumulator with error handling for large allocations
    AccumulatorImage accumulator;
    try {
        size_t requiredBytes = static_cast<size_t>(outWidth) * outHeight * sizeof(AccumulatorPixel);
        LOGI("MFSR: Allocating accumulator: %dx%d (%zu bytes)", outWidth, outHeight, requiredBytes);
        accumulator = AccumulatorImage(outWidth, outHeight);
    } catch (const std::bad_alloc& e) {
        LOGE("MFSR: Failed to allocate accumulator: %s", e.what());
        result.success = false;
        return;
    } catch (const std::exception& e) {
        LOGE("MFSR: Exception during accumulator allocation: %s", e.what());
        result.success = false;
        return;
    }
    
    // Convert reference frame to grayscale for sub-pixel alignment
    GrayImage refGray;
    try {
        refGray = GrayImage(inWidth, inHeight);
    } catch (const std::exception& e) {
        LOGE("MFSR: Failed to allocate grayscale image: %s", e.what());
        result.success = false;
        return;
    }
    
    const RGBImage& refFrame = frames[referenceIndex];
    for (int y = 0; y < inHeight; ++y) {
        for (int x = 0; x < inWidth; ++x) {
            const RGBPixel& p = refFrame.at(x, y);
            refGray.at(x, y) = 0.299f * p.r + 0.587f * p.g + 0.114f * p.b;
        }
    }
    
    float totalSubPixelShift = 0.0f;
    int shiftCount = 0;
    int framesContributed = 0;
    
    // Process each frame
    for (size_t i = 0; i < frames.size(); ++i) {
        if (progressCallback) {
            float progress = static_cast<float>(i) / frames.size();
            progressCallback("Computing sub-pixel alignment", progress * 0.5f);
        }
        
        const RGBImage& frame = frames[i];
        const FrameAlignment& alignment = alignments[i];
        
        // Skip invalid alignments (except reference)
        if (static_cast<int>(i) != referenceIndex && !alignment.isValid) {
            LOGW("MFSR: Skipping frame %zu due to invalid alignment", i);
            continue;
        }
        
        // Convert frame to grayscale
        GrayImage frameGray(inWidth, inHeight);
        for (int y = 0; y < inHeight; ++y) {
            for (int x = 0; x < inWidth; ++x) {
                const RGBPixel& p = frame.at(x, y);
                frameGray.at(x, y) = 0.299f * p.r + 0.587f * p.g + 0.114f * p.b;
            }
        }
        
        // Compute sub-pixel motion
        SubPixelMotionField subPixelMotion;
        
        if (static_cast<int>(i) == referenceIndex) {
            // Reference frame has zero motion
            int tilesX = (inWidth + params_.tileSize - 1) / params_.tileSize;
            int tilesY = (inHeight + params_.tileSize - 1) / params_.tileSize;
            subPixelMotion = SubPixelMotionField(tilesX, tilesY);
            for (int ty = 0; ty < tilesY; ++ty) {
                for (int tx = 0; tx < tilesX; ++tx) {
                    subPixelMotion.at(tx, ty) = SubPixelMotion(0, 0, 1.0f);
                }
            }
        } else {
            subPixelMotion = computeSubPixelMotion(refGray, frameGray, alignment);
            
            // Compute average sub-pixel shift
            for (int ty = 0; ty < subPixelMotion.height; ++ty) {
                for (int tx = 0; tx < subPixelMotion.width; ++tx) {
                    const SubPixelMotion& m = subPixelMotion.at(tx, ty);
                    float fracX = m.dx - std::floor(m.dx);
                    float fracY = m.dy - std::floor(m.dy);
                    totalSubPixelShift += std::sqrt(fracX * fracX + fracY * fracY);
                    shiftCount++;
                }
            }
        }
        
        if (progressCallback) {
            float progress = static_cast<float>(i) / frames.size();
            progressCallback("Scattering to high-res grid", 0.5f + progress * 0.3f);
        }
        
        // Scatter frame to accumulator
        scatterToAccumulator(frame, subPixelMotion, accumulator, params_.scaleFactor);
        framesContributed++;
    }
    
    if (progressCallback) {
        progressCallback("Filling gaps", 0.85f);
    }
    
    // Fill gaps in accumulator
    fillGaps(accumulator);
    
    if (progressCallback) {
        progressCallback("Finalizing image", 0.95f);
    }
    
    // Compute coverage
    int filledPixels = 0;
    for (int y = 0; y < outHeight; ++y) {
        for (int x = 0; x < outWidth; ++x) {
            if (accumulator.at(x, y).sampleCount > 0) {
                filledPixels++;
            }
        }
    }
    
    // Finalize output image
    finalizeImage(accumulator, result.upscaledImage);
    
    result.averageSubPixelShift = shiftCount > 0 ? totalSubPixelShift / shiftCount : 0;
    result.framesContributed = framesContributed;
    result.coverage = static_cast<float>(filledPixels) / (outWidth * outHeight);
    result.success = true;
    
    LOGI("MFSR complete: %d frames, avgSubPixelShift=%.3f, coverage=%.1f%%",
         framesContributed, result.averageSubPixelShift, result.coverage * 100.0f);
    
    if (progressCallback) {
        progressCallback("MFSR complete", 1.0f);
    }
}

} // namespace ultradetail
