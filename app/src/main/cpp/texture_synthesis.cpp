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
#include <chrono>

namespace ultradetail {

TextureSynthProcessor::TextureSynthProcessor(const TextureSynthParams& params)
    : params_(params) {
}

float TextureSynthProcessor::computeLocalVariance(
    const RGBImage& image,
    int x, int y,
    int radius
) {
#ifdef USE_NEON
    // NEON SIMD vectorized variance computation
    float32x4_t sumRGB = vdupq_n_f32(0.0f);  // [sumR, sumG, sumB, 0]
    float32x4_t sumRGB2 = vdupq_n_f32(0.0f); // [sumR2, sumG2, sumB2, 0]
    int count = 0;
    
    for (int dy = -radius; dy <= radius; ++dy) {
        int py = y + dy;
        if (py < 0 || py >= image.height) continue;
        
        int dx = -radius;
        // Process 4 pixels at a time with NEON
        for (; dx <= radius - 3; dx += 4) {
            int px = x + dx;
            if (px < 0 || px + 3 >= image.width) {
                // Scalar fallback for boundary
                for (int i = 0; i < 4 && dx + i <= radius; ++i) {
                    int px_i = x + dx + i;
                    if (px_i >= 0 && px_i < image.width) {
                        const RGBPixel& p = image.at(px_i, py);
                        sumRGB = vsetq_lane_f32(vgetq_lane_f32(sumRGB, 0) + p.r, sumRGB, 0);
                        sumRGB = vsetq_lane_f32(vgetq_lane_f32(sumRGB, 1) + p.g, sumRGB, 1);
                        sumRGB = vsetq_lane_f32(vgetq_lane_f32(sumRGB, 2) + p.b, sumRGB, 2);
                        sumRGB2 = vsetq_lane_f32(vgetq_lane_f32(sumRGB2, 0) + p.r * p.r, sumRGB2, 0);
                        sumRGB2 = vsetq_lane_f32(vgetq_lane_f32(sumRGB2, 1) + p.g * p.g, sumRGB2, 1);
                        sumRGB2 = vsetq_lane_f32(vgetq_lane_f32(sumRGB2, 2) + p.b * p.b, sumRGB2, 2);
                        count++;
                    }
                }
                continue;
            }
            
            // Load 4 RGB pixels
            float32x4_t r = {image.at(px, py).r, image.at(px+1, py).r,
                             image.at(px+2, py).r, image.at(px+3, py).r};
            float32x4_t g = {image.at(px, py).g, image.at(px+1, py).g,
                             image.at(px+2, py).g, image.at(px+3, py).g};
            float32x4_t b = {image.at(px, py).b, image.at(px+1, py).b,
                             image.at(px+2, py).b, image.at(px+3, py).b};
            
            // Accumulate sums (manual horizontal add for ARMv7 compatibility)
            float32x2_t r_low = vget_low_f32(r);
            float32x2_t r_high = vget_high_f32(r);
            float sumR = vget_lane_f32(vpadd_f32(r_low, r_high), 0) + vget_lane_f32(vpadd_f32(r_low, r_high), 1);
            
            float32x2_t g_low = vget_low_f32(g);
            float32x2_t g_high = vget_high_f32(g);
            float sumG = vget_lane_f32(vpadd_f32(g_low, g_high), 0) + vget_lane_f32(vpadd_f32(g_low, g_high), 1);
            
            float32x2_t b_low = vget_low_f32(b);
            float32x2_t b_high = vget_high_f32(b);
            float sumB = vget_lane_f32(vpadd_f32(b_low, b_high), 0) + vget_lane_f32(vpadd_f32(b_low, b_high), 1);
            
            sumRGB = vsetq_lane_f32(vgetq_lane_f32(sumRGB, 0) + sumR, sumRGB, 0);
            sumRGB = vsetq_lane_f32(vgetq_lane_f32(sumRGB, 1) + sumG, sumRGB, 1);
            sumRGB = vsetq_lane_f32(vgetq_lane_f32(sumRGB, 2) + sumB, sumRGB, 2);
            
            // Accumulate squared sums
            float32x4_t r2 = vmulq_f32(r, r);
            float32x4_t g2 = vmulq_f32(g, g);
            float32x4_t b2 = vmulq_f32(b, b);
            
            float32x2_t r2_low = vget_low_f32(r2);
            float32x2_t r2_high = vget_high_f32(r2);
            float sumR2 = vget_lane_f32(vpadd_f32(r2_low, r2_high), 0) + vget_lane_f32(vpadd_f32(r2_low, r2_high), 1);
            
            float32x2_t g2_low = vget_low_f32(g2);
            float32x2_t g2_high = vget_high_f32(g2);
            float sumG2 = vget_lane_f32(vpadd_f32(g2_low, g2_high), 0) + vget_lane_f32(vpadd_f32(g2_low, g2_high), 1);
            
            float32x2_t b2_low = vget_low_f32(b2);
            float32x2_t b2_high = vget_high_f32(b2);
            float sumB2 = vget_lane_f32(vpadd_f32(b2_low, b2_high), 0) + vget_lane_f32(vpadd_f32(b2_low, b2_high), 1);
            
            sumRGB2 = vsetq_lane_f32(vgetq_lane_f32(sumRGB2, 0) + sumR2, sumRGB2, 0);
            sumRGB2 = vsetq_lane_f32(vgetq_lane_f32(sumRGB2, 1) + sumG2, sumRGB2, 1);
            sumRGB2 = vsetq_lane_f32(vgetq_lane_f32(sumRGB2, 2) + sumB2, sumRGB2, 2);
            
            count += 4;
        }
        
        // Handle remaining pixels
        for (; dx <= radius; ++dx) {
            int px = x + dx;
            if (px < 0 || px >= image.width) continue;
            
            const RGBPixel& p = image.at(px, py);
            sumRGB = vsetq_lane_f32(vgetq_lane_f32(sumRGB, 0) + p.r, sumRGB, 0);
            sumRGB = vsetq_lane_f32(vgetq_lane_f32(sumRGB, 1) + p.g, sumRGB, 1);
            sumRGB = vsetq_lane_f32(vgetq_lane_f32(sumRGB, 2) + p.b, sumRGB, 2);
            sumRGB2 = vsetq_lane_f32(vgetq_lane_f32(sumRGB2, 0) + p.r * p.r, sumRGB2, 0);
            sumRGB2 = vsetq_lane_f32(vgetq_lane_f32(sumRGB2, 1) + p.g * p.g, sumRGB2, 1);
            sumRGB2 = vsetq_lane_f32(vgetq_lane_f32(sumRGB2, 2) + p.b * p.b, sumRGB2, 2);
            count++;
        }
    }
    
    if (count < 2) return 0;
    
    float invN = 1.0f / count;
    float sumR = vgetq_lane_f32(sumRGB, 0);
    float sumG = vgetq_lane_f32(sumRGB, 1);
    float sumB = vgetq_lane_f32(sumRGB, 2);
    float sumR2 = vgetq_lane_f32(sumRGB2, 0);
    float sumG2 = vgetq_lane_f32(sumRGB2, 1);
    float sumB2 = vgetq_lane_f32(sumRGB2, 2);
    
    float varR = sumR2 * invN - (sumR * invN) * (sumR * invN);
    float varG = sumG2 * invN - (sumG * invN) * (sumG * invN);
    float varB = sumB2 * invN - (sumB * invN) * (sumB * invN);
    
    return (varR + varG + varB) / 3.0f;
#else
    // Scalar fallback
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
#endif
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
    
#ifdef USE_NEON
    // NEON SIMD vectorized version - process 4 pixels at once
    for (int dy = -half; dy <= half; ++dy) {
        int py1 = y1 + dy, py2 = y2 + dy;
        if (py1 < 0 || py1 >= image.height || py2 < 0 || py2 >= image.height) continue;
        
        int dx = -half;
        // Process 4 pixels at a time with NEON
        for (; dx <= half - 3; dx += 4) {
            int px1 = x1 + dx, px2 = x2 + dx;
            if (px1 < 0 || px1 + 3 >= image.width || px2 < 0 || px2 + 3 >= image.width) {
                // Fallback to scalar for boundary pixels
                for (int i = 0; i < 4 && dx + i <= half; ++i) {
                    int px1_i = x1 + dx + i, px2_i = x2 + dx + i;
                    if (px1_i >= 0 && px1_i < image.width && px2_i >= 0 && px2_i < image.width) {
                        const RGBPixel& p1 = image.at(px1_i, py1);
                        const RGBPixel& p2 = image.at(px2_i, py2);
                        float dr = p1.r - p2.r, dg = p1.g - p2.g, db = p1.b - p2.b;
                        ssd += dr * dr + dg * dg + db * db;
                        count++;
                    }
                }
                continue;
            }
            
            // Load 4 RGB pixels from each patch
            float32x4_t r1 = {image.at(px1, py1).r, image.at(px1+1, py1).r, 
                              image.at(px1+2, py1).r, image.at(px1+3, py1).r};
            float32x4_t g1 = {image.at(px1, py1).g, image.at(px1+1, py1).g,
                              image.at(px1+2, py1).g, image.at(px1+3, py1).g};
            float32x4_t b1 = {image.at(px1, py1).b, image.at(px1+1, py1).b,
                              image.at(px1+2, py1).b, image.at(px1+3, py1).b};
            
            float32x4_t r2 = {image.at(px2, py2).r, image.at(px2+1, py2).r,
                              image.at(px2+2, py2).r, image.at(px2+3, py2).r};
            float32x4_t g2 = {image.at(px2, py2).g, image.at(px2+1, py2).g,
                              image.at(px2+2, py2).g, image.at(px2+3, py2).g};
            float32x4_t b2 = {image.at(px2, py2).b, image.at(px2+1, py2).b,
                              image.at(px2+2, py2).b, image.at(px2+3, py2).b};
            
            // Compute squared differences using NEON
            ssd += neon::ssd_f32x4(r1, r2);
            ssd += neon::ssd_f32x4(g1, g2);
            ssd += neon::ssd_f32x4(b1, b2);
            count += 4;
        }
        
        // Handle remaining pixels with scalar code
        for (; dx <= half; ++dx) {
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
#else
    // Scalar fallback for non-NEON platforms
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
#endif
    
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
    
    // Early termination threshold - if we find a patch this good, stop searching
    float earlyTermThreshold = 10.0f; // Very low score = excellent match
    
    // Coarse-to-fine search: first pass at 2x stride to find candidates
    const int coarseStride = 2;
    std::vector<std::pair<int, int>> candidates;
    candidates.reserve(25); // Top candidates
    
    // Phase 1: Coarse search with stride
    for (int sy = std::max(half, targetY - searchR); 
         sy < std::min(image.height - half, targetY + searchR); sy += coarseStride) {
        for (int sx = std::max(half, targetX - searchR);
             sx < std::min(image.width - half, targetX + searchR); sx += coarseStride) {
            
            // Skip if too close to target
            if (std::abs(sx - targetX) < half && std::abs(sy - targetY) < half) {
                continue;
            }
            
            // Quick color-based filtering
            const RGBPixel& srcColor = image.at(sx, sy);
            float colorDist = std::sqrt(
                (srcColor.r - targetColor.r) * (srcColor.r - targetColor.r) +
                (srcColor.g - targetColor.g) * (srcColor.g - targetColor.g) +
                (srcColor.b - targetColor.b) * (srcColor.b - targetColor.b)
            );
            
            // Only consider if color is reasonably similar
            if (colorDist < 50.0f) {
                candidates.push_back({sx, sy});
            }
        }
    }
    
    // Phase 2: Refine top candidates with full evaluation
    int candidatesEvaluated = 0;
    for (const auto& candidate : candidates) {
        int sx = candidate.first;
        int sy = candidate.second;
        
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
            
            // Early termination: found excellent match
            if (bestScore < earlyTermThreshold) {
                break;
            }
        }
        
        candidatesEvaluated++;
        // Limit candidates to avoid excessive computation
        if (candidatesEvaluated >= 50) break;
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
    int pixelsNeedingSynthesis = 0;
    int totalPixels = input.width * input.height;
    
    // Adaptive variance threshold - for upscaled images (smoother than raw)
    // Use higher threshold to detect more areas needing synthesis
    float adaptiveVarThreshold = params_.varianceThreshold * 20.0f; // 0.06 for upscaled images
    
    for (int y = 0; y < input.height; ++y) {
        for (int x = 0; x < input.width; ++x) {
            // Compute local variance
            float var = computeLocalVariance(input, x, y, radius);
            map.variance.at(x, y) = var;
            
            // Compute edge magnitude
            float edge = computeEdgeMagnitude(input, x, y);
            map.edges.at(x, y) = edge;
            
            // Adaptive confidence calculation:
            // 1. Skip pixels with high variance (already textured)
            // 2. Protect edges (preserve structure)
            // 3. Gradual falloff instead of binary threshold
            
            float synthNeed = 0.0f;
            if (var < adaptiveVarThreshold) {
                // Smooth falloff: 1.0 at var=0, 0.0 at var=threshold
                synthNeed = 1.0f - (var / adaptiveVarThreshold);
                synthNeed = std::max(0.0f, std::min(1.0f, synthNeed));
            }
            
            // Edge protection: reduce synthesis near edges (preserve structure)
            // Normalized edge magnitude (typical range 0-100)
            float edgeProtect = std::min(1.0f, edge / 50.0f);
            
            // Final confidence: high for low-variance non-edge regions
            float confidence = synthNeed * (1.0f - edgeProtect * 0.7f);
            map.confidence.at(x, y) = confidence;
            
            if (confidence > 0.05f) {
                pixelsNeedingSynthesis++;
            }
        }
    }
    
    float percentNeedingSynth = (100.0f * pixelsNeedingSynthesis) / totalPixels;
    LOGD("Adaptive detail map: %.1f%% pixels require synthesis (%d/%d)",
         percentNeedingSynth, pixelsNeedingSynthesis, totalPixels);
    
    // Early exit optimization: if < 5% needs synthesis, skip processing
    if (percentNeedingSynth < 5.0f) {
        LOGD("TextureSynth: Image already has sufficient detail (%.1f%%), skipping synthesis", percentNeedingSynth);
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
    
    auto startTime = std::chrono::high_resolution_clock::now();
    
    // Compute detail map
    LOGD("TextureSynth: Computing detail map...");
    DetailMap detailMap = computeDetailMap(input);
    
    auto detailMapTime = std::chrono::high_resolution_clock::now();
    auto detailMapMs = std::chrono::duration_cast<std::chrono::milliseconds>(detailMapTime - startTime).count();
    LOGD("TextureSynth: Detail map computed in %lldms", detailMapMs);
    
    // Use guided synthesis
    LOGD("TextureSynth: Starting guided synthesis...");
    result = synthesizeGuided(input, detailMap);
    
    auto endTime = std::chrono::high_resolution_clock::now();
    auto totalMs = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime).count();
    LOGD("TextureSynth: Total synthesis time: %lldms", totalMs);
    
    return result;
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
    
    // Adaptive step size - larger for upscaled images to reduce overhead
    // For upscaled images, use full patch size as step (not half)
    int baseStep = std::max(2, params_.patchSize);
    int pixelsEvaluated = 0;
    int pixelsSkipped = 0;
    
    // Progress tracking
    int totalPixelsToEvaluate = ((input.height - 2 * half) / baseStep) * ((input.width - 2 * half) / baseStep);
    int progressUpdateInterval = std::max(1, totalPixelsToEvaluate / 100); // Update every 1%
    int lastProgressUpdate = 0;
    
    for (int y = half; y < input.height - half; y += baseStep) {
        for (int x = half; x < input.width - half; x += baseStep) {
            pixelsEvaluated++;
            
            float confidence = detailMap.confidence.at(x, y);
            float variance = detailMap.variance.at(x, y);
            
            // Skip if no synthesis needed (lower threshold for upscaled images)
            if (confidence < 0.05f) {
                pixelsSkipped++;
                continue;
            }
            
            // Adaptive step: skip more in high-variance regions
            // In low-variance regions (var < 0.02), process every pixel
            // In medium-variance regions, add randomization
            if (variance > 0.02f && randDist(rng) > confidence) {
                pixelsSkipped++;
                continue;
            }
            
            // Find best matching patch with more texture
            float targetVar = detailMap.variance.at(x, y);
            TexturePatch bestPatch = findBestPatch(
                input, x, y,
                input.at(x, y),
                targetVar
            );
            
            // For upscaled images: apply patch if it has ANY variance or if confidence is high
            // Don't require source to have more variance than target (uniform smooth images)
            bool shouldApply = (bestPatch.variance > 0.001f) || (confidence > 0.3f);
            
            if (shouldApply) {
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
            
            // Progress callback
            if (params_.progressCallback && (pixelsEvaluated - lastProgressUpdate) >= progressUpdateInterval) {
                float avgDetail = patchesProcessed > 0 ? totalDetail / patchesProcessed : 0.0f;
                params_.progressCallback(pixelsEvaluated, totalPixelsToEvaluate, avgDetail);
                lastProgressUpdate = pixelsEvaluated;
            }
        }
    }
    
    // Final progress update
    if (params_.progressCallback) {
        float avgDetail = patchesProcessed > 0 ? totalDetail / patchesProcessed : 0.0f;
        params_.progressCallback(totalPixelsToEvaluate, totalPixelsToEvaluate, avgDetail);
    }
    
    result.patchesProcessed = patchesProcessed;
    result.avgDetailAdded = patchesProcessed > 0 ? totalDetail / patchesProcessed : 0;
    result.success = true;
    
    float skipRate = (100.0f * pixelsSkipped) / pixelsEvaluated;
    LOGD("TextureSynth: Processed %d patches, avg detail=%.3f",
         patchesProcessed, result.avgDetailAdded);
    LOGD("TextureSynth: Adaptive processing - evaluated %d pixels, skipped %d (%.1f%%)",
         pixelsEvaluated, pixelsSkipped, skipRate);
    
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

float TextureSynthProcessor::analyzeImageQuality(const RGBImage& input) {
    if (input.width == 0 || input.height == 0) return 0.0f;
    
    // Sample image at regular intervals to assess detail level
    const int sampleStep = 32;  // Sample every 32 pixels
    const int varianceRadius = 3;
    
    float totalVariance = 0.0f;
    float highVariancePixels = 0.0f;
    int sampleCount = 0;
    
    for (int y = varianceRadius; y < input.height - varianceRadius; y += sampleStep) {
        for (int x = varianceRadius; x < input.width - varianceRadius; x += sampleStep) {
            // Compute local variance
            float sumR = 0, sumG = 0, sumB = 0;
            float sumR2 = 0, sumG2 = 0, sumB2 = 0;
            int count = 0;
            
            for (int dy = -varianceRadius; dy <= varianceRadius; ++dy) {
                for (int dx = -varianceRadius; dx <= varianceRadius; ++dx) {
                    const RGBPixel& p = input.at(x + dx, y + dy);
                    sumR += p.r; sumG += p.g; sumB += p.b;
                    sumR2 += p.r * p.r; sumG2 += p.g * p.g; sumB2 += p.b * p.b;
                    count++;
                }
            }
            
            float meanR = sumR / count, meanG = sumG / count, meanB = sumB / count;
            float varR = (sumR2 / count) - (meanR * meanR);
            float varG = (sumG2 / count) - (meanG * meanG);
            float varB = (sumB2 / count) - (meanB * meanB);
            float variance = (varR + varG + varB) / 3.0f;
            
            totalVariance += variance;
            if (variance < 0.005f) {  // Low detail threshold
                highVariancePixels += 1.0f;
            }
            sampleCount++;
        }
    }
    
    if (sampleCount == 0) return 0.0f;
    
    float avgVariance = totalVariance / sampleCount;
    float lowDetailRatio = highVariancePixels / sampleCount;
    
    // Score: 0.0 = image already has good detail, 1.0 = needs synthesis
    // If >40% of samples have low variance, synthesis is beneficial
    float qualityScore = std::min(1.0f, lowDetailRatio * 2.5f);
    
    LOGD("TextureSynth Quality Analysis: avgVar=%.5f, lowDetail=%.1f%%, score=%.2f",
         avgVariance, lowDetailRatio * 100.0f, qualityScore);
    
    return qualityScore;
}

} // namespace ultradetail
