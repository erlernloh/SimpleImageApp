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

// Standalone Lanczos weight function
static inline float lanczosWeight(float distance, float a = 2.0f) {
    if (distance == 0.0f) return 1.0f;
    if (std::abs(distance) >= a) return 0.0f;
    
    float pi_d = M_PI * distance;
    float pi_d_a = pi_d / a;
    return (std::sin(pi_d) / pi_d) * (std::sin(pi_d_a) / pi_d_a);
}

TiledMFSRPipeline::TiledMFSRPipeline(const TilePipelineConfig& config)
    : config_(config) {
    
    // Initialize processors
    flowProcessor_ = std::make_unique<DenseOpticalFlow>(config_.flowParams);
    mfsrProcessor_ = std::make_unique<MultiFrameSR>(config_.mfsrParams);
    
    LOGI("TiledMFSRPipeline initialized: tile=%dx%d, overlap=%d, scale=%d",
         config_.tileWidth, config_.tileHeight, config_.overlap, config_.scaleFactor);
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
    // Compute color difference
    float dr = pixel.r - reference.r;
    float dg = pixel.g - reference.g;
    float db = pixel.b - reference.b;
    float colorDiff = std::sqrt(dr*dr + dg*dg + db*db);
    
    float weight = flowConfidence;
    
    switch (config_.robustness) {
        case TilePipelineConfig::RobustnessMethod::TUKEY:
            weight *= tukeyBiweight(colorDiff, config_.robustnessThreshold);
            break;
            
        case TilePipelineConfig::RobustnessMethod::HUBER:
            weight *= huberWeight(colorDiff, config_.robustnessThreshold);
            break;
            
        case TilePipelineConfig::RobustnessMethod::NONE:
        default:
            // Just use flow confidence
            break;
    }
    
    return weight;
}

float TiledMFSRPipeline::computeBlendWeight(int x, int y, int width, int height, int overlap) {
    // Compute distance from edges for smooth blending
    float wx = 1.0f;
    float wy = 1.0f;
    
    if (x < overlap) {
        wx = static_cast<float>(x) / overlap;
    } else if (x >= width - overlap) {
        wx = static_cast<float>(width - 1 - x) / overlap;
    }
    
    if (y < overlap) {
        wy = static_cast<float>(y) / overlap;
    } else if (y >= height - overlap) {
        wy = static_cast<float>(height - 1 - y) / overlap;
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
    const float maxAllowedMotion = 50.0f;  // pixels
    float maxMotion = 0.0f;
    
    for (size_t i = 0; i < grayFrames.size(); ++i) {
        if (static_cast<int>(i) == referenceIndex) continue;
        
        float motion = estimateGlobalMotion(grayFrames[referenceIndex], grayFrames[i]);
        maxMotion = std::max(maxMotion, motion);
        
        if (motion > maxAllowedMotion) {
            LOGW("Excessive motion detected: %.1f pixels (max allowed: %.1f)", 
                 motion, maxAllowedMotion);
            return FallbackReason::EXCESSIVE_MOTION;
        }
    }
    
    LOGI("Global motion check passed: max=%.1f pixels", maxMotion);
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
    
    // Step 2: Compute dense optical flow for this tile
    flowProcessor_->setReference(grayTileCrops[referenceIndex]);
    
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
        
        // Compute flow with optional gyro initialization
        GyroHomography gyroInit;
        if (gyroHomographies && config_.useGyroInit && i < static_cast<int>(gyroHomographies->size())) {
            gyroInit = (*gyroHomographies)[i];
        }
        
        DenseFlowResult flowResult;
        flowResult = flowProcessor_->computeFlow(grayTileCrops[i], gyroInit);
        
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
    }
    
    result.averageFlow = validFlows > 0 ? totalFlow / validFlows : 0.0f;
    
    // Step 3: Classical MFSR accumulation
    int outWidth = tile.width * config_.scaleFactor;
    int outHeight = tile.height * config_.scaleFactor;
    
    // Accumulator for high-res grid
    struct AccumPixel {
        float r, g, b, weight;
        AccumPixel() : r(0), g(0), b(0), weight(0) {}
    };
    ImageBuffer<AccumPixel> accumulator(outWidth, outHeight);
    
    // Reference frame for robustness comparison
    const RGBImage& refCrop = tileCrops[referenceIndex];
    
    // Scatter pixels from all frames to high-res grid
    for (int frameIdx = 0; frameIdx < numFrames; ++frameIdx) {
        const RGBImage& crop = tileCrops[frameIdx];
        const FlowField& flow = tileFlows[frameIdx];
        
        for (int y = 0; y < crop.height; ++y) {
            for (int x = 0; x < crop.width; ++x) {
                const RGBPixel& pixel = crop.at(x, y);
                const FlowVector& fv = flow.at(x, y);
                
                // Skip invalid pixels
                if (!std::isfinite(pixel.r) || !std::isfinite(pixel.g) || !std::isfinite(pixel.b)) {
                    continue;
                }
                
                // Compute destination in HR grid
                float dstX = (x - fv.dx) * config_.scaleFactor;
                float dstY = (y - fv.dy) * config_.scaleFactor;
                
                // Skip out-of-bounds
                if (dstX < 0 || dstX >= outWidth - 1 || dstY < 0 || dstY >= outHeight - 1) {
                    continue;
                }
                
                // Compute robustness weight
                float robustWeight = 1.0f;
                if (frameIdx != referenceIndex && x < refCrop.width && y < refCrop.height) {
                    robustWeight = computeRobustnessWeight(pixel, refCrop.at(x, y), fv.confidence);
                }
                
                // Lanczos-2 splatting (4x4 kernel)
                const float lanczosA = 2.0f;
                int x0 = static_cast<int>(std::floor(dstX)) - 1;
                int y0 = static_cast<int>(std::floor(dstY)) - 1;
                
                for (int ky = 0; ky < 4; ++ky) {
                    int py = y0 + ky;
                    if (py < 0 || py >= outHeight) continue;
                    
                    float dy = std::abs(dstY - py);
                    float wy = lanczosWeight(dy, lanczosA);
                    
                    for (int kx = 0; kx < 4; ++kx) {
                        int px = x0 + kx;
                        if (px < 0 || px >= outWidth) continue;
                        
                        float dx = std::abs(dstX - px);
                        float wx = lanczosWeight(dx, lanczosA);
                        
                        float w = wx * wy * fv.confidence * robustWeight;
                        if (w <= 0.0f) continue;
                        
                        AccumPixel& acc = accumulator.at(px, py);
                        acc.r += pixel.r * w;
                        acc.g += pixel.g * w;
                        acc.b += pixel.b * w;
                        acc.weight += w;
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
                out.r = clamp(acc.r * invW, 0.0f, 1.0f);
                out.g = clamp(acc.g * invW, 0.0f, 1.0f);
                out.b = clamp(acc.b * invW, 0.0f, 1.0f);
                validPixels++;
            } else {
                // Gap - will be filled by interpolation
                out.r = out.g = out.b = 0.0f;
            }
        }
    }
    
    // Fill gaps using neighbor averaging
    for (int pass = 0; pass < 3; ++pass) {
        for (int y = 1; y < outHeight - 1; ++y) {
            for (int x = 1; x < outWidth - 1; ++x) {
                if (accumulator.at(x, y).weight > 0.0f) continue;
                
                float sumR = 0, sumG = 0, sumB = 0;
                int count = 0;
                
                for (int dy = -1; dy <= 1; ++dy) {
                    for (int dx = -1; dx <= 1; ++dx) {
                        if (dx == 0 && dy == 0) continue;
                        const AccumPixel& neighbor = accumulator.at(x + dx, y + dy);
                        if (neighbor.weight > 0.0f) {
                            float invW = 1.0f / neighbor.weight;
                            sumR += neighbor.r * invW;
                            sumG += neighbor.g * invW;
                            sumB += neighbor.b * invW;
                            count++;
                        }
                    }
                }
                
                if (count > 0) {
                    RGBPixel& out = result.outputTile.at(x, y);
                    out.r = sumR / count;
                    out.g = sumG / count;
                    out.b = sumB / count;
                    accumulator.at(x, y).weight = 0.001f;  // Mark as filled
                    validPixels++;
                }
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
