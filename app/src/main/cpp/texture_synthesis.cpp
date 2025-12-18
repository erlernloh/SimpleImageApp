/**
 * texture_synthesis.cpp - Texture Synthesis Implementation
 * 
 * Implements patch-based texture synthesis for detail enhancement.
 */

#include "texture_synthesis.h"
#include "neon_utils.h"
#include <cmath>
#include <algorithm>
#include <random>

namespace ultradetail {

TextureSynthProcessor::TextureSynthProcessor(const TextureSynthParams& params)
    : params_(params) {
}

float TextureSynthProcessor::computeLocalVariance(
    const RGBImage& image,
    int x, int y,
    int radius
) {
    float sumR = 0, sumG = 0, sumB = 0;
    float sumR2 = 0, sumG2 = 0, sumB2 = 0;
    int count = 0;
    
    for (int dy = -radius; dy <= radius; ++dy) {
        int py = y + dy;
        if (py < 0 || py >= image.height) continue;
        
        for (int dx = -radius; dx <= radius; ++dx) {
            int px = x + dx;
            if (px < 0 || px >= image.width) continue;
            
            const RGBPixel& p = image.at(px, py);
            sumR += p.r; sumG += p.g; sumB += p.b;
            sumR2 += p.r * p.r;
            sumG2 += p.g * p.g;
            sumB2 += p.b * p.b;
            count++;
        }
    }
    
    if (count < 2) return 0;
    
    float invN = 1.0f / count;
    float varR = sumR2 * invN - (sumR * invN) * (sumR * invN);
    float varG = sumG2 * invN - (sumG * invN) * (sumG * invN);
    float varB = sumB2 * invN - (sumB * invN) * (sumB * invN);
    
    return (varR + varG + varB) / 3.0f;
}

float TextureSynthProcessor::computeEdgeMagnitude(
    const RGBImage& image,
    int x, int y
) {
    if (x < 1 || x >= image.width - 1 || y < 1 || y >= image.height - 1) {
        return 0;
    }
    
    // Sobel gradients on luminance
    auto lum = [](const RGBPixel& p) {
        return 0.299f * p.r + 0.587f * p.g + 0.114f * p.b;
    };
    
    float gx = -lum(image.at(x-1, y-1)) - 2*lum(image.at(x-1, y)) - lum(image.at(x-1, y+1))
              + lum(image.at(x+1, y-1)) + 2*lum(image.at(x+1, y)) + lum(image.at(x+1, y+1));
    
    float gy = -lum(image.at(x-1, y-1)) - 2*lum(image.at(x, y-1)) - lum(image.at(x+1, y-1))
              + lum(image.at(x-1, y+1)) + 2*lum(image.at(x, y+1)) + lum(image.at(x+1, y+1));
    
    return std::sqrt(gx * gx + gy * gy);
}

float TextureSynthProcessor::computePatchSSD(
    const RGBImage& image,
    int x1, int y1,
    int x2, int y2,
    int patchSize
) {
    int half = patchSize / 2;
    float ssd = 0;
    int count = 0;
    
    for (int dy = -half; dy <= half; ++dy) {
        int py1 = y1 + dy, py2 = y2 + dy;
        if (py1 < 0 || py1 >= image.height || py2 < 0 || py2 >= image.height) continue;
        
        for (int dx = -half; dx <= half; ++dx) {
            int px1 = x1 + dx, px2 = x2 + dx;
            if (px1 < 0 || px1 >= image.width || px2 < 0 || px2 >= image.width) continue;
            
            const RGBPixel& p1 = image.at(px1, py1);
            const RGBPixel& p2 = image.at(px2, py2);
            
            float dr = p1.r - p2.r;
            float dg = p1.g - p2.g;
            float db = p1.b - p2.b;
            
            ssd += dr * dr + dg * dg + db * db;
            count++;
        }
    }
    
    return count > 0 ? ssd / count : 1e10f;
}

TexturePatch TextureSynthProcessor::findBestPatch(
    const RGBImage& image,
    int targetX, int targetY,
    const RGBPixel& targetColor,
    float targetVariance
) {
    TexturePatch best;
    best.x = targetX;
    best.y = targetY;
    float bestScore = 1e10f;
    
    int searchR = params_.searchRadius;
    int half = params_.patchSize / 2;
    
    // Search for similar patches
    for (int sy = std::max(half, targetY - searchR); 
         sy < std::min(image.height - half, targetY + searchR); ++sy) {
        for (int sx = std::max(half, targetX - searchR);
             sx < std::min(image.width - half, targetX + searchR); ++sx) {
            
            // Skip if too close to target
            if (std::abs(sx - targetX) < half && std::abs(sy - targetY) < half) {
                continue;
            }
            
            // Check variance similarity
            float srcVariance = computeLocalVariance(image, sx, sy, half);
            if (srcVariance < params_.varianceThreshold) continue;
            
            // Color similarity
            const RGBPixel& srcColor = image.at(sx, sy);
            float colorDist = std::sqrt(
                (srcColor.r - targetColor.r) * (srcColor.r - targetColor.r) +
                (srcColor.g - targetColor.g) * (srcColor.g - targetColor.g) +
                (srcColor.b - targetColor.b) * (srcColor.b - targetColor.b)
            );
            
            // Variance similarity
            float varDist = std::abs(srcVariance - targetVariance);
            
            // Edge similarity
            float srcEdge = computeEdgeMagnitude(image, sx, sy);
            float targetEdge = computeEdgeMagnitude(image, targetX, targetY);
            float edgeDist = std::abs(srcEdge - targetEdge);
            
            // Combined score
            float score = colorDist + varDist * 10.0f + edgeDist * params_.edgeWeight;
            
            if (score < bestScore) {
                bestScore = score;
                best.x = sx;
                best.y = sy;
                best.variance = srcVariance;
                best.edgeMagnitude = srcEdge;
            }
        }
    }
    
    return best;
}

void TextureSynthProcessor::blendPatch(
    RGBImage& output,
    const RGBImage& source,
    int targetX, int targetY,
    int sourceX, int sourceY,
    int patchSize,
    float weight
) {
    int half = patchSize / 2;
    
    for (int dy = -half; dy <= half; ++dy) {
        int ty = targetY + dy, sy = sourceY + dy;
        if (ty < 0 || ty >= output.height || sy < 0 || sy >= source.height) continue;
        
        for (int dx = -half; dx <= half; ++dx) {
            int tx = targetX + dx, sx = sourceX + dx;
            if (tx < 0 || tx >= output.width || sx < 0 || sx >= source.width) continue;
            
            // Gaussian-like falloff from center
            float dist = std::sqrt(static_cast<float>(dx * dx + dy * dy));
            float falloff = std::exp(-dist * dist / (half * half * 0.5f));
            float w = weight * falloff;
            
            RGBPixel& out = output.at(tx, ty);
            const RGBPixel& src = source.at(sx, sy);
            
            out.r = out.r * (1 - w) + src.r * w;
            out.g = out.g * (1 - w) + src.g * w;
            out.b = out.b * (1 - w) + src.b * w;
        }
    }
}

DetailMap TextureSynthProcessor::computeDetailMap(const RGBImage& input) {
    DetailMap map;
    map.resize(input.width, input.height);
    
    int radius = params_.patchSize / 2;
    
    for (int y = 0; y < input.height; ++y) {
        for (int x = 0; x < input.width; ++x) {
            // Compute local variance
            float var = computeLocalVariance(input, x, y, radius);
            map.variance.at(x, y) = var;
            
            // Compute edge magnitude
            float edge = computeEdgeMagnitude(input, x, y);
            map.edges.at(x, y) = edge;
            
            // Confidence: low variance regions need synthesis
            // High edge regions should preserve structure
            float needsSynth = var < params_.varianceThreshold ? 1.0f : 0.0f;
            float edgeProtect = std::min(1.0f, edge * 5.0f);
            map.confidence.at(x, y) = needsSynth * (1.0f - edgeProtect * 0.5f);
        }
    }
    
    return map;
}

TextureSynthResult TextureSynthProcessor::synthesize(
    const RGBImage& input,
    const RGBImage* reference
) {
    TextureSynthResult result;
    
    if (input.width == 0 || input.height == 0) {
        LOGE("TextureSynth: Invalid input");
        return result;
    }
    
    // Compute detail map
    DetailMap detailMap = computeDetailMap(input);
    
    // Use guided synthesis
    return synthesizeGuided(input, detailMap);
}

TextureSynthResult TextureSynthProcessor::synthesizeGuided(
    const RGBImage& input,
    const DetailMap& detailMap
) {
    TextureSynthResult result;
    result.synthesized = input;  // Start with copy
    result.detailMask.resize(input.width, input.height);
    
    int half = params_.patchSize / 2;
    int patchesProcessed = 0;
    float totalDetail = 0;
    
    // Process in scanline order with some randomization
    std::random_device rd;
    std::mt19937 rng(rd());
    std::uniform_real_distribution<float> randDist(0, 1);
    
    // Step size for processing (not every pixel)
    int step = std::max(1, params_.patchSize / 2);
    
    for (int y = half; y < input.height - half; y += step) {
        for (int x = half; x < input.width - half; x += step) {
            float confidence = detailMap.confidence.at(x, y);
            
            // Skip if no synthesis needed
            if (confidence < 0.1f) continue;
            
            // Add some randomization to avoid artifacts
            if (randDist(rng) > confidence) continue;
            
            // Find best matching patch with more texture
            float targetVar = detailMap.variance.at(x, y);
            TexturePatch bestPatch = findBestPatch(
                input, x, y,
                input.at(x, y),
                targetVar
            );
            
            // Only use if source has more detail
            if (bestPatch.variance > targetVar * 1.5f) {
                // Blend the patch
                float blendW = params_.blendWeight * confidence;
                blendPatch(
                    result.synthesized, input,
                    x, y,
                    bestPatch.x, bestPatch.y,
                    params_.patchSize,
                    blendW
                );
                
                // Update detail mask
                result.detailMask.at(x, y) = blendW;
                totalDetail += blendW;
                patchesProcessed++;
            }
        }
    }
    
    result.patchesProcessed = patchesProcessed;
    result.avgDetailAdded = patchesProcessed > 0 ? totalDetail / patchesProcessed : 0;
    result.success = true;
    
    LOGD("TextureSynth: Processed %d patches, avg detail=%.3f",
         patchesProcessed, result.avgDetailAdded);
    
    return result;
}

RGBImage TextureSynthProcessor::transferTexture(
    const RGBImage& target,
    const RGBImage& source,
    const GrayImage& mask
) {
    RGBImage result = target;
    
    if (source.width != target.width || source.height != target.height) {
        LOGW("TextureSynth: Size mismatch in transferTexture");
        return result;
    }
    
    int half = params_.patchSize / 2;
    
    for (int y = half; y < target.height - half; ++y) {
        for (int x = half; x < target.width - half; ++x) {
            float m = mask.at(x, y);
            if (m < 0.01f) continue;
            
            // Extract high-frequency detail from source
            float srcVar = computeLocalVariance(source, x, y, half);
            float tgtVar = computeLocalVariance(target, x, y, half);
            
            if (srcVar > tgtVar) {
                // Transfer detail
                const RGBPixel& src = source.at(x, y);
                RGBPixel& tgt = result.at(x, y);
                
                float w = m * params_.blendWeight;
                tgt.r = tgt.r * (1 - w) + src.r * w;
                tgt.g = tgt.g * (1 - w) + src.g * w;
                tgt.b = tgt.b * (1 - w) + src.b * w;
            }
        }
    }
    
    return result;
}

} // namespace ultradetail
