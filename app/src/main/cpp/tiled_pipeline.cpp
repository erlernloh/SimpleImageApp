/**
 * tiled_pipeline.cpp - Tile-Based Processing Pipeline Implementation
 * 
 * Memory-safe tile-by-tile MFSR processing.
 */

#include "tiled_pipeline.h"
#include "neon_utils.h"
#include <android/log.h>
#include <chrono>
#include <cmath>
#include <algorithm>

// Use logging macros from common.h
#undef LOG_TAG
#define LOG_TAG "TiledPipeline"

namespace ultradetail {

// Mitchell-Netravali filter parameters (B=1/3, C=1/3)
// This kernel is more stable under alignment errors than Lanczos-3
// Less ringing, better for optical flow-based alignment where shifts are imprecise
static constexpr float MITCHELL_B = 1.0f / 3.0f;
static constexpr float MITCHELL_C = 1.0f / 3.0f;

/**
 * Mitchell-Netravali bicubic filter weight function
 * 
 * Replaces Lanczos-3 to reduce ringing artifacts caused by optical flow errors.
 * B=1/3, C=1/3 provides good balance between blur and ringing.
 * 
 * Reference: "Reconstruction Filters in Computer Graphics" (Mitchell & Netravali, 1988)
 */
static inline float mitchellWeight(float t) {
    t = std::abs(t);
    const float B = MITCHELL_B;
    const float C = MITCHELL_C;
    
    if (t < 1.0f) {
        return ((12.0f - 9.0f * B - 6.0f * C) * t * t * t
              + (-18.0f + 12.0f * B + 6.0f * C) * t * t
              + (6.0f - 2.0f * B)) / 6.0f;
    } else if (t < 2.0f) {
        return ((-B - 6.0f * C) * t * t * t
              + (6.0f * B + 30.0f * C) * t * t
              + (-12.0f * B - 48.0f * C) * t
              + (8.0f * B + 24.0f * C)) / 6.0f;
    }
    return 0.0f;
}

// Legacy Lanczos weight function (kept for reference, no longer used in scatter)
static constexpr float LANCZOS_A = 3.0f;
static inline float lanczosWeight(float distance, float a = LANCZOS_A) {
    if (distance == 0.0f) return 1.0f;
    if (std::abs(distance) >= a) return 0.0f;
    
    float pi_d = M_PI * distance;
    float pi_d_a = pi_d / a;
    return (std::sin(pi_d) / pi_d) * (std::sin(pi_d_a) / pi_d_a);
}

/**
 * De-ringing clamp: Limits output to the local min/max of input samples
 * This prevents Lanczos ringing (halos) around high-contrast edges
 * 
 * @param value The interpolated value
 * @param localMin Minimum of nearby input samples
 * @param localMax Maximum of nearby input samples
 * @return Clamped value within [localMin, localMax]
 */
static inline float deringClamp(float value, float localMin, float localMax) {
    return clamp(value, localMin, localMax);
}

static inline RGBPixel deringClampRGB(const RGBPixel& value, 
                                       const RGBPixel& localMin, 
                                       const RGBPixel& localMax) {
    return RGBPixel(
        deringClamp(value.r, localMin.r, localMax.r),
        deringClamp(value.g, localMin.g, localMax.g),
        deringClamp(value.b, localMin.b, localMax.b)
    );
}

TiledMFSRPipeline::TiledMFSRPipeline(const TilePipelineConfig& config)
    : config_(config) {
    
    // Initialize processors based on alignment method
    if (config_.alignmentMethod == TilePipelineConfig::AlignmentMethod::DENSE_OPTICAL_FLOW) {
        flowProcessor_ = std::make_unique<DenseOpticalFlow>(config_.flowParams);
        LOGI("TiledMFSRPipeline: Using dense optical flow alignment");
    } else {
        // Fix #5 & #1: Use hybrid aligner (gyro + phase correlation)
        hybridAligner_ = std::make_unique<HybridAligner>();
        LOGI("TiledMFSRPipeline: Using hybrid alignment (gyro + phase correlation)");
    }
    
    mfsrProcessor_ = std::make_unique<MultiFrameSR>(config_.mfsrParams);
    
    LOGI("TiledMFSRPipeline initialized: tile=%dx%d, overlap=%d, scale=%d, alignment=%s",
         config_.tileWidth, config_.tileHeight, config_.overlap, config_.scaleFactor,
         config_.alignmentMethod == TilePipelineConfig::AlignmentMethod::DENSE_OPTICAL_FLOW ? "dense_flow" :
         config_.alignmentMethod == TilePipelineConfig::AlignmentMethod::PHASE_CORRELATION ? "phase_corr" : "hybrid");
}

std::vector<TileRegion> TiledMFSRPipeline::computeTileGrid(int width, int height) const {
    std::vector<TileRegion> tiles;
    
    int effectiveTileW = config_.tileWidth - config_.overlap;
    int effectiveTileH = config_.tileHeight - config_.overlap;
    
    int numTilesX = (width + effectiveTileW - 1) / effectiveTileW;
    int numTilesY = (height + effectiveTileH - 1) / effectiveTileH;
    
    LOGD("Tile grid: %dx%d tiles for %dx%d image", numTilesX, numTilesY, width, height);
    
    for (int ty = 0; ty < numTilesY; ++ty) {
        for (int tx = 0; tx < numTilesX; ++tx) {
            TileRegion tile;
            
            // Input coordinates
            tile.x = tx * effectiveTileW;
            tile.y = ty * effectiveTileH;
            tile.width = std::min(config_.tileWidth, width - tile.x);
            tile.height = std::min(config_.tileHeight, height - tile.y);
            
            // Padding for overlap blending
            tile.padLeft = (tx > 0) ? config_.overlap / 2 : 0;
            tile.padTop = (ty > 0) ? config_.overlap / 2 : 0;
            tile.padRight = (tx < numTilesX - 1) ? config_.overlap / 2 : 0;
            tile.padBottom = (ty < numTilesY - 1) ? config_.overlap / 2 : 0;
            
            // Output coordinates (scaled)
            tile.outX = tile.x * config_.scaleFactor;
            tile.outY = tile.y * config_.scaleFactor;
            tile.outWidth = tile.width * config_.scaleFactor;
            tile.outHeight = tile.height * config_.scaleFactor;
            
            tiles.push_back(tile);
        }
    }
    
    return tiles;
}

void TiledMFSRPipeline::extractTileCrop(
    const RGBImage& source,
    const TileRegion& tile,
    RGBImage& crop
) {
    // Include padding for overlap
    int startX = std::max(0, tile.x - tile.padLeft);
    int startY = std::max(0, tile.y - tile.padTop);
    int endX = std::min(source.width, tile.x + tile.width + tile.padRight);
    int endY = std::min(source.height, tile.y + tile.height + tile.padBottom);
    
    int cropWidth = endX - startX;
    int cropHeight = endY - startY;
    
    crop.resize(cropWidth, cropHeight);
    
    for (int y = 0; y < cropHeight; ++y) {
        const RGBPixel* srcRow = source.row(startY + y);
        RGBPixel* dstRow = crop.row(y);
        std::copy(srcRow + startX, srcRow + startX + cropWidth, dstRow);
    }
}

void TiledMFSRPipeline::extractTileCrop(
    const GrayImage& source,
    const TileRegion& tile,
    GrayImage& crop
) {
    int startX = std::max(0, tile.x - tile.padLeft);
    int startY = std::max(0, tile.y - tile.padTop);
    int endX = std::min(source.width, tile.x + tile.width + tile.padRight);
    int endY = std::min(source.height, tile.y + tile.height + tile.padBottom);
    
    int cropWidth = endX - startX;
    int cropHeight = endY - startY;
    
    crop.resize(cropWidth, cropHeight);
    
    for (int y = 0; y < cropHeight; ++y) {
        const float* srcRow = source.row(startY + y);
        float* dstRow = crop.row(y);
        std::copy(srcRow + startX, srcRow + startX + cropWidth, dstRow);
    }
}

float TiledMFSRPipeline::computeRobustnessWeight(
    const RGBPixel& pixel,
    const RGBPixel& reference,
    float flowConfidence
) {
    // Fix #4: Adaptive robustness weighting
    // Instead of fixed threshold, adapt based on flow confidence (proxy for motion)
    // Low confidence (high motion) → more aggressive rejection (lower threshold)
    // High confidence (low motion) → gentler rejection (higher threshold)
    
    // Compute color difference
    float dr = pixel.r - reference.r;
    float dg = pixel.g - reference.g;
    float db = pixel.b - reference.b;
    float colorDiff = std::sqrt(dr*dr + dg*dg + db*db);
    
    float weight = flowConfidence;
    
    // Adaptive threshold: base + adjustment based on flow confidence
    // flowConfidence near 1.0 → use higher threshold (gentler, Huber-like)
    // flowConfidence near 0.0 → use lower threshold (more aggressive, Tukey-like)
    float adaptiveThreshold = config_.robustnessThreshold * (0.5f + 0.5f * flowConfidence);
    
    switch (config_.robustness) {
        case TilePipelineConfig::RobustnessMethod::TUKEY:
            weight *= tukeyBiweight(colorDiff, adaptiveThreshold);
            break;
            
        case TilePipelineConfig::RobustnessMethod::HUBER:
            weight *= huberWeight(colorDiff, adaptiveThreshold);
            break;
            
        case TilePipelineConfig::RobustnessMethod::NONE:
        default:
            // Just use flow confidence
            break;
    }
    
    return weight;
}

float TiledMFSRPipeline::computeBlendWeight(int x, int y, int width, int height, int overlap) {
    // Use smooth cosine blending (raised cosine window) for artifact-free tile merging
    // This is the same approach used by Google's HDR+ and Handheld Super-Res
    auto smoothstep = [](float t) -> float {
        // Hermite smoothstep: 3t² - 2t³ for smooth transition
        t = clamp(t, 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    };
    
    float wx = 1.0f;
    float wy = 1.0f;
    
    if (overlap > 0) {
        if (x < overlap) {
            wx = smoothstep(static_cast<float>(x) / overlap);
        } else if (x >= width - overlap) {
            wx = smoothstep(static_cast<float>(width - 1 - x) / overlap);
        }
        
        if (y < overlap) {
            wy = smoothstep(static_cast<float>(y) / overlap);
        } else if (y >= height - overlap) {
            wy = smoothstep(static_cast<float>(height - 1 - y) / overlap);
        }
    }
    
    return wx * wy;
}

void TiledMFSRPipeline::blendTileToOutput(
    const RGBImage& tile,
    const TileRegion& region,
    RGBImage& output,
    ImageBuffer<float>& weightMap
) {
    int scaledOverlap = config_.overlap * config_.scaleFactor;
    
    for (int y = 0; y < tile.height && region.outY + y < output.height; ++y) {
        for (int x = 0; x < tile.width && region.outX + x < output.width; ++x) {
            int outX = region.outX + x;
            int outY = region.outY + y;
            
            float blendWeight = computeBlendWeight(x, y, tile.width, tile.height, scaledOverlap);
            
            const RGBPixel& src = tile.at(x, y);
            RGBPixel& dst = output.at(outX, outY);
            float& dstWeight = weightMap.at(outX, outY);
            
            // Weighted accumulation for blending
            dst.r += src.r * blendWeight;
            dst.g += src.g * blendWeight;
            dst.b += src.b * blendWeight;
            dstWeight += blendWeight;
        }
    }
}

float TiledMFSRPipeline::estimateGlobalMotion(
    const GrayImage& reference,
    const GrayImage& frame
) {
    // Quick global motion estimate using sparse sampling
    const int sampleStep = 32;
    const int blockSize = 16;
    const int searchRadius = 16;
    
    float totalMotion = 0.0f;
    int sampleCount = 0;
    
    for (int y = blockSize; y < reference.height - blockSize - searchRadius; y += sampleStep) {
        for (int x = blockSize; x < reference.width - blockSize - searchRadius; x += sampleStep) {
            // Simple block matching
            float bestSAD = std::numeric_limits<float>::max();
            int bestDx = 0, bestDy = 0;
            
            for (int dy = -searchRadius; dy <= searchRadius; dy += 2) {
                for (int dx = -searchRadius; dx <= searchRadius; dx += 2) {
                    float sad = 0.0f;
                    for (int by = 0; by < blockSize; by += 2) {
                        for (int bx = 0; bx < blockSize; bx += 2) {
                            float diff = reference.at(x + bx, y + by) - 
                                        frame.at(x + bx + dx, y + by + dy);
                            sad += std::abs(diff);
                        }
                    }
                    if (sad < bestSAD) {
                        bestSAD = sad;
                        bestDx = dx;
                        bestDy = dy;
                    }
                }
            }
            
            totalMotion += std::sqrt(static_cast<float>(bestDx * bestDx + bestDy * bestDy));
            sampleCount++;
        }
    }
    
    return sampleCount > 0 ? totalMotion / sampleCount : 0.0f;
}

FallbackReason TiledMFSRPipeline::checkFallbackConditions(
    const std::vector<RGBImage>& frames,
    const std::vector<GrayImage>& grayFrames,
    int referenceIndex
) {
    if (frames.size() < 2) {
        LOGW("Only %zu frames, need at least 2 for MFSR", frames.size());
        return FallbackReason::ALIGNMENT_FAILED;
    }
    
    // Check for excessive motion
    // Increased threshold significantly: hand-held phones typically have 20-100+ pixel shifts
    // between burst frames. The hybrid aligner (phase correlation + gyro) can handle large
    // motions, so we only reject truly extreme cases (e.g., scene change or severe blur).
    // Previous value of 50px was too conservative and caused fallback on normal hand-held shots.
    const float maxAllowedMotion = 200.0f;  // pixels - allow significant hand motion
    float maxMotion = 0.0f;
    
    for (size_t i = 0; i < grayFrames.size(); ++i) {
        if (static_cast<int>(i) == referenceIndex) continue;
        
        float motion = estimateGlobalMotion(grayFrames[referenceIndex], grayFrames[i]);
        maxMotion = std::max(maxMotion, motion);
        LOGI("Frame %zu motion: %.1f pixels", i, motion);
        
        if (motion > maxAllowedMotion) {
            LOGW("Excessive motion detected: %.1f pixels (max allowed: %.1f)", 
                 motion, maxAllowedMotion);
            return FallbackReason::EXCESSIVE_MOTION;
        }
    }
    
    LOGI("Global motion check passed: max=%.1f pixels (threshold=%.1f)", maxMotion, maxAllowedMotion);
    return FallbackReason::NONE;
}

void TiledMFSRPipeline::fallbackUpscale(
    const RGBImage& referenceFrame,
    PipelineResult& result
) {
    LOGI("Performing fallback bicubic upscale");
    
    int outWidth = referenceFrame.width * config_.scaleFactor;
    int outHeight = referenceFrame.height * config_.scaleFactor;
    
    result.outputImage.resize(outWidth, outHeight);
    result.inputWidth = referenceFrame.width;
    result.inputHeight = referenceFrame.height;
    result.outputWidth = outWidth;
    result.outputHeight = outHeight;
    result.usedFallback = true;
    
    // Bicubic interpolation
    for (int y = 0; y < outHeight; ++y) {
        float srcY = static_cast<float>(y) / config_.scaleFactor;
        int y0 = static_cast<int>(srcY);
        float fy = srcY - y0;
        
        for (int x = 0; x < outWidth; ++x) {
            float srcX = static_cast<float>(x) / config_.scaleFactor;
            int x0 = static_cast<int>(srcX);
            float fx = srcX - x0;
            
            // Bilinear for simplicity (bicubic would be better)
            int x1 = std::min(x0 + 1, referenceFrame.width - 1);
            int y1 = std::min(y0 + 1, referenceFrame.height - 1);
            x0 = std::min(x0, referenceFrame.width - 1);
            y0 = std::min(y0, referenceFrame.height - 1);
            
            const RGBPixel& p00 = referenceFrame.at(x0, y0);
            const RGBPixel& p10 = referenceFrame.at(x1, y0);
            const RGBPixel& p01 = referenceFrame.at(x0, y1);
            const RGBPixel& p11 = referenceFrame.at(x1, y1);
            
            RGBPixel& out = result.outputImage.at(x, y);
            out.r = p00.r * (1-fx) * (1-fy) + p10.r * fx * (1-fy) +
                    p01.r * (1-fx) * fy + p11.r * fx * fy;
            out.g = p00.g * (1-fx) * (1-fy) + p10.g * fx * (1-fy) +
                    p01.g * (1-fx) * fy + p11.g * fx * fy;
            out.b = p00.b * (1-fx) * (1-fy) + p10.b * fx * (1-fy) +
                    p01.b * (1-fx) * fy + p11.b * fx * fy;
        }
    }
    
    result.success = true;
}

void TiledMFSRPipeline::processTile(
    const std::vector<RGBImage>& frames,
    const std::vector<GrayImage>& grayFrames,
    const TileRegion& tile,
    int referenceIndex,
    const std::vector<GyroHomography>* gyroHomographies,
    TileResult& result
) {
    const int numFrames = static_cast<int>(frames.size());
    
    // Step 1: Extract tile crops from all frames
    std::vector<RGBImage> tileCrops(numFrames);
    std::vector<GrayImage> grayTileCrops(numFrames);
    
    for (int i = 0; i < numFrames; ++i) {
        extractTileCrop(frames[i], tile, tileCrops[i]);
        extractTileCrop(grayFrames[i], tile, grayTileCrops[i]);
    }
    
    // Step 2: Compute alignment for this tile
    // Fix #5 & #1: Use hybrid alignment (gyro + phase correlation) instead of dense optical flow
    std::vector<FlowField> tileFlows(numFrames);
    float totalFlow = 0.0f;
    int validFlows = 0;
    
    for (int i = 0; i < numFrames; ++i) {
        if (i == referenceIndex) {
            // Reference has zero flow
            tileFlows[i].resize(tileCrops[i].width, tileCrops[i].height);
            for (int y = 0; y < tileFlows[i].height; ++y) {
                for (int x = 0; x < tileFlows[i].width; ++x) {
                    tileFlows[i].at(x, y) = FlowVector(0, 0, 1.0f);
                }
            }
            continue;
        }
        
        // Get gyro homography if available
        const GyroHomography* gyroPtr = nullptr;
        GyroHomography gyroInit;
        if (gyroHomographies && config_.useGyroInit && i < static_cast<int>(gyroHomographies->size())) {
            gyroInit = (*gyroHomographies)[i];
            if (gyroInit.isValid) {
                gyroPtr = &gyroInit;
            }
        }
        
        // Choose alignment method based on configuration
        if (config_.alignmentMethod == TilePipelineConfig::AlignmentMethod::DENSE_OPTICAL_FLOW) {
            // Original dense Lucas-Kanade optical flow
            flowProcessor_->setReference(grayTileCrops[referenceIndex]);
            DenseFlowResult flowResult = flowProcessor_->computeFlow(grayTileCrops[i], gyroInit);
            
            if (flowResult.isValid) {
                tileFlows[i] = std::move(flowResult.flowField);
                totalFlow += flowResult.averageFlow;
                validFlows++;
            } else {
                // Zero flow fallback
                tileFlows[i].resize(tileCrops[i].width, tileCrops[i].height);
                for (int y = 0; y < tileFlows[i].height; ++y) {
                    for (int x = 0; x < tileFlows[i].width; ++x) {
                        tileFlows[i].at(x, y) = FlowVector(0, 0, 0.5f);
                    }
                }
            }
        } else {
            // Fix #5 & #1: Hybrid alignment (gyro + phase correlation)
            // Much faster and more robust for global translations
            tileFlows[i] = hybridAligner_->computeAlignment(
                grayTileCrops[referenceIndex],
                grayTileCrops[i],
                gyroPtr,
                config_.useLocalRefinement
            );
            
            // Compute average flow for statistics
            float sumFlow = 0.0f;
            int count = 0;
            for (int y = 0; y < tileFlows[i].height; y += 4) {
                for (int x = 0; x < tileFlows[i].width; x += 4) {
                    const FlowVector& fv = tileFlows[i].at(x, y);
                    sumFlow += fv.magnitude();
                    count++;
                }
            }
            if (count > 0) {
                totalFlow += sumFlow / count;
                validFlows++;
            }
        }
    }
    
    result.averageFlow = validFlows > 0 ? totalFlow / validFlows : 0.0f;
    
    // Step 3: Classical MFSR accumulation
    int outWidth = tile.width * config_.scaleFactor;
    int outHeight = tile.height * config_.scaleFactor;
    
    // Accumulator for high-res grid with min/max tracking for de-ringing
    struct AccumPixel {
        float r, g, b, weight;
        float minR, minG, minB;  // Local minimum for de-ringing
        float maxR, maxG, maxB;  // Local maximum for de-ringing
        AccumPixel() : r(0), g(0), b(0), weight(0),
                       minR(1.0f), minG(1.0f), minB(1.0f),
                       maxR(0.0f), maxG(0.0f), maxB(0.0f) {}
        
        void updateMinMax(const RGBPixel& p) {
            minR = std::min(minR, p.r);
            minG = std::min(minG, p.g);
            minB = std::min(minB, p.b);
            maxR = std::max(maxR, p.r);
            maxG = std::max(maxG, p.g);
            maxB = std::max(maxB, p.b);
        }
    };
    ImageBuffer<AccumPixel> accumulator(outWidth, outHeight);
    
    // Reference frame for robustness comparison
    const RGBImage& refCrop = tileCrops[referenceIndex];
    
    // Scatter pixels from all frames to high-res grid
    // Sub-pixel diversity is critical for MFSR quality - it comes from:
    // 1. Fractional part of alignment shifts (e.g., 19.59px -> 0.59 sub-pixel offset)
    // 2. Natural hand motion variation between frames
    for (int frameIdx = 0; frameIdx < numFrames; ++frameIdx) {
        const RGBImage& crop = tileCrops[frameIdx];
        const FlowField& flow = tileFlows[frameIdx];
        
        // Add deterministic sub-pixel offset per frame to ensure diversity
        // This helps when alignment is too precise (integer-aligned)
        // Uses golden ratio for optimal sub-pixel sampling distribution
        const float phi = 1.618033988749895f;
        float frameOffsetX = std::fmod(frameIdx * phi, 1.0f) - 0.5f;  // Range [-0.5, 0.5]
        float frameOffsetY = std::fmod(frameIdx * phi * phi, 1.0f) - 0.5f;
        frameOffsetX *= 0.3f;  // Scale down to subtle sub-pixel jitter
        frameOffsetY *= 0.3f;
        
        for (int y = 0; y < crop.height; ++y) {
            for (int x = 0; x < crop.width; ++x) {
                const RGBPixel& pixel = crop.at(x, y);
                const FlowVector& fv = flow.at(x, y);
                
                // Skip invalid pixels
                if (!std::isfinite(pixel.r) || !std::isfinite(pixel.g) || !std::isfinite(pixel.b)) {
                    continue;
                }
                
                // Compute destination in HR grid with sub-pixel offset
                // The fractional part of (fv.dx, fv.dy) provides natural sub-pixel diversity
                // frameOffset adds additional diversity when alignment is too precise
                float dstX = (x - fv.dx + frameOffsetX) * config_.scaleFactor;
                float dstY = (y - fv.dy + frameOffsetY) * config_.scaleFactor;
                
                // Skip out-of-bounds
                if (dstX < 0 || dstX >= outWidth - 1 || dstY < 0 || dstY >= outHeight - 1) {
                    continue;
                }
                
                // Compute robustness weight
                float robustWeight = 1.0f;
                if (frameIdx != referenceIndex && x < refCrop.width && y < refCrop.height) {
                    robustWeight = computeRobustnessWeight(pixel, refCrop.at(x, y), fv.confidence);
                }
                
                // Mitchell-Netravali splatting (4x4 kernel) - more stable than Lanczos-3
                // under optical flow alignment errors. Less ringing, better for imprecise shifts.
                const int kernelRadius = 2;  // Mitchell-Netravali has support [-2, 2]
                const int kernelSize = kernelRadius * 2;  // 4x4 kernel
                int x0 = static_cast<int>(std::floor(dstX)) - kernelRadius + 1;
                int y0 = static_cast<int>(std::floor(dstY)) - kernelRadius + 1;
                
                for (int ky = 0; ky < kernelSize; ++ky) {
                    int py = y0 + ky;
                    if (py < 0 || py >= outHeight) continue;
                    
                    float dy = dstY - py;
                    float wy = mitchellWeight(dy);
                    
                    for (int kx = 0; kx < kernelSize; ++kx) {
                        int px = x0 + kx;
                        if (px < 0 || px >= outWidth) continue;
                        
                        float dx = dstX - px;
                        float wx = mitchellWeight(dx);
                        
                        float w = wx * wy * fv.confidence * robustWeight;
                        if (w <= 0.0f) continue;
                        
                        AccumPixel& acc = accumulator.at(px, py);
                        acc.r += pixel.r * w;
                        acc.g += pixel.g * w;
                        acc.b += pixel.b * w;
                        acc.weight += w;
                        
                        // Track local min/max for de-ringing clamp
                        acc.updateMinMax(pixel);
                    }
                }
            }
        }
    }
    
    // Step 4: Normalize and fill gaps
    result.outputTile.resize(outWidth, outHeight);
    int validPixels = 0;
    
    for (int y = 0; y < outHeight; ++y) {
        for (int x = 0; x < outWidth; ++x) {
            const AccumPixel& acc = accumulator.at(x, y);
            RGBPixel& out = result.outputTile.at(x, y);
            
            if (acc.weight > 0.0f) {
                float invW = 1.0f / acc.weight;
                float rawR = acc.r * invW;
                float rawG = acc.g * invW;
                float rawB = acc.b * invW;
                
                // De-ringing clamp: limit output to local min/max of input samples
                // This prevents Lanczos-3 ringing (halos) around high-contrast edges
                out.r = clamp(rawR, acc.minR, acc.maxR);
                out.g = clamp(rawG, acc.minG, acc.maxG);
                out.b = clamp(rawB, acc.minB, acc.maxB);
                
                // Final clamp to valid range
                out.r = clamp(out.r, 0.0f, 1.0f);
                out.g = clamp(out.g, 0.0f, 1.0f);
                out.b = clamp(out.b, 0.0f, 1.0f);
                
                validPixels++;
            } else {
                // Gap - will be filled by interpolation
                out.r = out.g = out.b = 0.0f;
            }
        }
    }
    
    // Fill gaps using bicubic interpolation from reference frame
    // This is the approach used by Google's Handheld Super-Res - gaps are filled
    // with upscaled reference frame data rather than neighbor averaging
    auto bicubicWeight = [](float t) -> float {
        // Mitchell-Netravali filter (B=1/3, C=1/3) - good balance of blur/ringing
        t = std::abs(t);
        if (t < 1.0f) {
            return (12.0f - 9.0f * (1.0f/3.0f) - 6.0f * (1.0f/3.0f)) * t * t * t
                 + (-18.0f + 12.0f * (1.0f/3.0f) + 6.0f * (1.0f/3.0f)) * t * t
                 + (6.0f - 2.0f * (1.0f/3.0f));
        } else if (t < 2.0f) {
            return (-(1.0f/3.0f) - 6.0f * (1.0f/3.0f)) * t * t * t
                 + (6.0f * (1.0f/3.0f) + 30.0f * (1.0f/3.0f)) * t * t
                 + (-12.0f * (1.0f/3.0f) - 48.0f * (1.0f/3.0f)) * t
                 + (8.0f * (1.0f/3.0f) + 24.0f * (1.0f/3.0f));
        }
        return 0.0f;
    };
    
    // Fill gaps with bicubic-upscaled reference frame
    for (int y = 0; y < outHeight; ++y) {
        for (int x = 0; x < outWidth; ++x) {
            if (accumulator.at(x, y).weight > 0.0f) continue;
            
            // Map HR position back to LR reference frame
            float srcX = static_cast<float>(x) / config_.scaleFactor;
            float srcY = static_cast<float>(y) / config_.scaleFactor;
            
            // Bicubic interpolation from reference frame
            int x0 = static_cast<int>(std::floor(srcX)) - 1;
            int y0 = static_cast<int>(std::floor(srcY)) - 1;
            
            float sumR = 0, sumG = 0, sumB = 0, sumW = 0;
            
            for (int ky = 0; ky < 4; ++ky) {
                int py = y0 + ky;
                if (py < 0 || py >= refCrop.height) continue;
                
                float wy = bicubicWeight(srcY - py) / 6.0f;  // Normalize
                
                for (int kx = 0; kx < 4; ++kx) {
                    int px = x0 + kx;
                    if (px < 0 || px >= refCrop.width) continue;
                    
                    float wx = bicubicWeight(srcX - px) / 6.0f;
                    float w = wx * wy;
                    
                    const RGBPixel& p = refCrop.at(px, py);
                    sumR += p.r * w;
                    sumG += p.g * w;
                    sumB += p.b * w;
                    sumW += w;
                }
            }
            
            if (sumW > 0.0f) {
                RGBPixel& out = result.outputTile.at(x, y);
                out.r = clamp(sumR / sumW, 0.0f, 1.0f);
                out.g = clamp(sumG / sumW, 0.0f, 1.0f);
                out.b = clamp(sumB / sumW, 0.0f, 1.0f);
                validPixels++;
            }
        }
    }
    
    result.coverage = static_cast<float>(validPixels) / (outWidth * outHeight);
    result.framesContributed = numFrames;
    result.success = result.coverage > 0.5f;
}

void TiledMFSRPipeline::process(
    const std::vector<RGBImage>& frames,
    const std::vector<GrayImage>& grayFrames,
    int referenceIndex,
    const std::vector<GyroHomography>* gyroHomographies,
    PipelineResult& result,
    TilePipelineProgress progressCallback
) {
    auto startTime = std::chrono::high_resolution_clock::now();
    
    if (frames.empty()) {
        LOGE("No frames provided");
        result.success = false;
        return;
    }
    
    int width = frames[0].width;
    int height = frames[0].height;
    
    LOGI("Starting tiled MFSR pipeline: %dx%d, %zu frames, scale=%d",
         width, height, frames.size(), config_.scaleFactor);
    
    // Check fallback conditions
    FallbackReason fallback = checkFallbackConditions(frames, grayFrames, referenceIndex);
    if (fallback != FallbackReason::NONE) {
        LOGW("Falling back to single-frame upscale: reason=%d", static_cast<int>(fallback));
        result.fallbackReason = fallback;
        fallbackUpscale(frames[referenceIndex], result);
        return;
    }
    
    // Compute tile grid
    std::vector<TileRegion> tiles = computeTileGrid(width, height);
    int totalTiles = static_cast<int>(tiles.size());
    
    LOGI("Processing %d tiles", totalTiles);
    
    // Allocate output image and weight map
    int outWidth = width * config_.scaleFactor;
    int outHeight = height * config_.scaleFactor;
    
    result.outputImage.resize(outWidth, outHeight);
    ImageBuffer<float> weightMap(outWidth, outHeight);
    
    // Initialize to zero
    for (int y = 0; y < outHeight; ++y) {
        for (int x = 0; x < outWidth; ++x) {
            result.outputImage.at(x, y) = RGBPixel(0, 0, 0);
            weightMap.at(x, y) = 0.0f;
        }
    }
    
    // Process each tile
    float totalFlow = 0.0f;
    int successfulTiles = 0;
    
    for (int i = 0; i < totalTiles; ++i) {
        if (progressCallback) {
            float progress = static_cast<float>(i) / totalTiles;
            progressCallback(i, totalTiles, "Processing MFSR tiles", progress);
        }
        
        TileResult tileResult;
        processTile(frames, grayFrames, tiles[i], referenceIndex, gyroHomographies, tileResult);
        
        if (tileResult.success) {
            blendTileToOutput(tileResult.outputTile, tiles[i], result.outputImage, weightMap);
            totalFlow += tileResult.averageFlow;
            successfulTiles++;
        } else {
            result.tilesFailed++;
            LOGW("Tile %d failed, coverage=%.1f%%", i, tileResult.coverage * 100);
        }
        
        // Tile memory is released here when tileResult goes out of scope
    }
    
    // Normalize output by weight map
    for (int y = 0; y < outHeight; ++y) {
        for (int x = 0; x < outWidth; ++x) {
            float w = weightMap.at(x, y);
            if (w > 0.0f) {
                RGBPixel& p = result.outputImage.at(x, y);
                p.r = clamp(p.r / w, 0.0f, 1.0f);
                p.g = clamp(p.g / w, 0.0f, 1.0f);
                p.b = clamp(p.b / w, 0.0f, 1.0f);
            }
        }
    }
    
    // Post-processing: Edge-preserving smoothing to remove blotchy artifacts
    // Uses a simplified bilateral filter approach similar to Google's HDR+
    if (progressCallback) {
        progressCallback(totalTiles, totalTiles, "Smoothing artifacts", 0.95f);
    }
    
    // Create a copy for filtering
    // Fix #6: Tuned bilateral filter parameters for less aggressive smoothing
    // Previous values (spatialSigma=1.5, rangeSigma=0.08) were too aggressive,
    // causing over-smoothing and loss of detail. New values preserve more texture.
    RGBImage smoothed(outWidth, outHeight);
    const int filterRadius = 2;  // Small radius for subtle smoothing
    const float spatialSigma = 2.5f;   // Wider spatial kernel (was 1.5)
    const float rangeSigma = 0.15f;    // More tolerant of color variation (was 0.08)
    
    for (int y = filterRadius; y < outHeight - filterRadius; ++y) {
        for (int x = filterRadius; x < outWidth - filterRadius; ++x) {
            const RGBPixel& center = result.outputImage.at(x, y);
            
            float sumR = 0, sumG = 0, sumB = 0, sumW = 0;
            
            for (int dy = -filterRadius; dy <= filterRadius; ++dy) {
                for (int dx = -filterRadius; dx <= filterRadius; ++dx) {
                    const RGBPixel& neighbor = result.outputImage.at(x + dx, y + dy);
                    
                    // Spatial weight (Gaussian)
                    float spatialDist = std::sqrt(static_cast<float>(dx*dx + dy*dy));
                    float spatialW = std::exp(-spatialDist * spatialDist / (2.0f * spatialSigma * spatialSigma));
                    
                    // Range weight (color similarity)
                    float colorDiff = std::sqrt(
                        (neighbor.r - center.r) * (neighbor.r - center.r) +
                        (neighbor.g - center.g) * (neighbor.g - center.g) +
                        (neighbor.b - center.b) * (neighbor.b - center.b)
                    );
                    float rangeW = std::exp(-colorDiff * colorDiff / (2.0f * rangeSigma * rangeSigma));
                    
                    float w = spatialW * rangeW;
                    sumR += neighbor.r * w;
                    sumG += neighbor.g * w;
                    sumB += neighbor.b * w;
                    sumW += w;
                }
            }
            
            if (sumW > 0.0f) {
                smoothed.at(x, y) = RGBPixel(
                    clamp(sumR / sumW, 0.0f, 1.0f),
                    clamp(sumG / sumW, 0.0f, 1.0f),
                    clamp(sumB / sumW, 0.0f, 1.0f)
                );
            } else {
                smoothed.at(x, y) = center;
            }
        }
    }
    
    // Copy smoothed result back (excluding border)
    for (int y = filterRadius; y < outHeight - filterRadius; ++y) {
        for (int x = filterRadius; x < outWidth - filterRadius; ++x) {
            result.outputImage.at(x, y) = smoothed.at(x, y);
        }
    }
    
    // Post-processing: Unsharp Mask (USM) sharpening to restore detail
    // This is essential for MFSR output which tends to be soft due to averaging
    if (progressCallback) {
        progressCallback(totalTiles, totalTiles, "Sharpening", 0.98f);
    }
    
    // USM parameters tuned for MFSR output
    const float usmAmount = 0.5f;      // Sharpening strength (0.3-0.7 typical)
    const float usmThreshold = 0.02f;  // Edge threshold to avoid noise amplification
    const int usmRadius = 1;           // Small radius for fine detail
    
    // Create blurred version for USM (simple box blur for speed)
    RGBImage blurred(outWidth, outHeight);
    for (int y = usmRadius; y < outHeight - usmRadius; ++y) {
        for (int x = usmRadius; x < outWidth - usmRadius; ++x) {
            float sumR = 0, sumG = 0, sumB = 0;
            int count = 0;
            for (int dy = -usmRadius; dy <= usmRadius; ++dy) {
                for (int dx = -usmRadius; dx <= usmRadius; ++dx) {
                    const RGBPixel& p = result.outputImage.at(x + dx, y + dy);
                    sumR += p.r;
                    sumG += p.g;
                    sumB += p.b;
                    count++;
                }
            }
            blurred.at(x, y) = RGBPixel(sumR / count, sumG / count, sumB / count);
        }
    }
    
    // Apply USM: sharpened = original + amount * (original - blurred)
    for (int y = usmRadius; y < outHeight - usmRadius; ++y) {
        for (int x = usmRadius; x < outWidth - usmRadius; ++x) {
            RGBPixel& p = result.outputImage.at(x, y);
            const RGBPixel& b = blurred.at(x, y);
            
            // Compute high-frequency detail (difference from blur)
            float diffR = p.r - b.r;
            float diffG = p.g - b.g;
            float diffB = p.b - b.b;
            
            // Only sharpen if detail is above threshold (avoid noise)
            float detailMag = std::sqrt(diffR*diffR + diffG*diffG + diffB*diffB);
            if (detailMag > usmThreshold) {
                p.r = clamp(p.r + usmAmount * diffR, 0.0f, 1.0f);
                p.g = clamp(p.g + usmAmount * diffG, 0.0f, 1.0f);
                p.b = clamp(p.b + usmAmount * diffB, 0.0f, 1.0f);
            }
        }
    }
    
    auto endTime = std::chrono::high_resolution_clock::now();
    auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime);
    
    result.inputWidth = width;
    result.inputHeight = height;
    result.outputWidth = outWidth;
    result.outputHeight = outHeight;
    result.tilesProcessed = successfulTiles;
    result.averageFlow = successfulTiles > 0 ? totalFlow / successfulTiles : 0.0f;
    result.processingTimeMs = static_cast<float>(duration.count());
    result.success = successfulTiles > 0;
    
    LOGI("MFSR complete: %d/%d tiles, avgFlow=%.2f, time=%.1fs",
         successfulTiles, totalTiles, result.averageFlow, result.processingTimeMs / 1000.0f);
    
    if (progressCallback) {
        progressCallback(totalTiles, totalTiles, "MFSR complete", 1.0f);
    }
}

} // namespace ultradetail
