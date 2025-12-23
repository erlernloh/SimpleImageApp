/**
 * deghost_enhance.cpp - Ghosting Prevention and Detail Enhancement Implementation
 * 
 * Implements professional-grade techniques based on:
 * - Google HDR+ temporal robustness
 * - Apple Deep Fusion pixel-level analysis
 * - Topaz Photo AI multi-scale sharpening
 */

#include "deghost_enhance.h"
#include "common.h"
#include <algorithm>
#include <cmath>
#include <numeric>

namespace ultradetail {

DeghostEnhancer::DeghostEnhancer(const DeghostEnhanceConfig& config)
    : config_(config) {
}

// ============================================================================
// Motion Mask Detection
// ============================================================================

MotionMask DeghostEnhancer::computeMotionMask(
    const RGBImage& reference,
    const std::vector<RGBImage>& frames,
    int referenceIndex
) {
    int width = reference.width;
    int height = reference.height;
    
    MotionMask mask(width, height);
    
    // For each pixel, check if it's moving across frames
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const RGBPixel& refPixel = reference.at(x, y);
            MotionInfo& info = mask.at(x, y);
            
            float totalDiff = 0.0f;
            int movingCount = 0;
            int validFrames = 0;
            
            for (size_t i = 0; i < frames.size(); ++i) {
                if (static_cast<int>(i) == referenceIndex) continue;
                if (x >= frames[i].width || y >= frames[i].height) continue;
                
                const RGBPixel& framePixel = frames[i].at(x, y);
                float diff = colorDifference(refPixel, framePixel);
                totalDiff += diff;
                validFrames++;
                
                if (diff > config_.motionThreshold) {
                    movingCount++;
                }
            }
            
            if (validFrames > 0) {
                info.motionMagnitude = totalDiff / validFrames;
                // Pixel is moving if majority of frames show motion
                info.isMoving = (movingCount > validFrames / 2) || 
                               (info.motionMagnitude > config_.motionThreshold * 1.5f);
                // Confidence decreases with motion
                info.confidence = 1.0f - std::min(1.0f, info.motionMagnitude / config_.motionThreshold);
            }
        }
    }
    
    return mask;
}

// ============================================================================
// Temporal Median Merging (Robust to Ghosting)
// ============================================================================

RGBPixel DeghostEnhancer::temporalMedianMerge(std::vector<TemporalSample>& samples) {
    if (samples.empty()) {
        return RGBPixel();
    }
    
    if (samples.size() == 1) {
        return samples[0].color;
    }
    
    // Filter out low-confidence samples
    std::vector<TemporalSample> validSamples;
    for (const auto& s : samples) {
        if (s.confidence >= config_.confidenceThreshold && s.weight > 0.01f) {
            validSamples.push_back(s);
        }
    }
    
    if (validSamples.empty()) {
        // Fall back to highest confidence sample
        auto best = std::max_element(samples.begin(), samples.end(),
            [](const TemporalSample& a, const TemporalSample& b) {
                return a.confidence < b.confidence;
            });
        return best->color;
    }
    
    // Sort by luminance for median calculation
    std::sort(validSamples.begin(), validSamples.end(),
        [](const TemporalSample& a, const TemporalSample& b) {
            float lumA = 0.299f * a.color.r + 0.587f * a.color.g + 0.114f * a.color.b;
            float lumB = 0.299f * b.color.r + 0.587f * b.color.g + 0.114f * b.color.b;
            return lumA < lumB;
        });
    
    // Take median sample
    size_t medianIdx = validSamples.size() / 2;
    
    // For better quality, average the middle 3 samples if available
    if (validSamples.size() >= 5) {
        float r = 0, g = 0, b = 0;
        float totalWeight = 0;
        for (size_t i = medianIdx - 1; i <= medianIdx + 1; ++i) {
            float w = validSamples[i].weight * validSamples[i].confidence;
            r += validSamples[i].color.r * w;
            g += validSamples[i].color.g * w;
            b += validSamples[i].color.b * w;
            totalWeight += w;
        }
        if (totalWeight > 0) {
            return RGBPixel(r / totalWeight, g / totalWeight, b / totalWeight);
        }
    }
    
    return validSamples[medianIdx].color;
}

// ============================================================================
// Robust Merge with Motion Rejection
// ============================================================================

RGBPixel DeghostEnhancer::robustMerge(
    std::vector<TemporalSample>& samples,
    const MotionInfo& motionInfo,
    const RGBPixel& referencePixel
) {
    // If pixel is moving and reference fallback is enabled, use reference
    if (config_.useReferenceFallback && motionInfo.isMoving && 
        motionInfo.motionMagnitude > config_.motionThreshold * 2.0f) {
        return referencePixel;
    }
    
    // Use temporal median if enabled
    if (config_.useTemporalMedian) {
        return temporalMedianMerge(samples);
    }
    
    // Otherwise use weighted mean with motion-based rejection
    float totalR = 0, totalG = 0, totalB = 0;
    float totalWeight = 0;
    
    for (auto& sample : samples) {
        // Skip low-confidence samples
        if (sample.confidence < config_.confidenceThreshold) {
            continue;
        }
        
        // Reduce weight for samples that differ significantly from reference
        float diff = colorDifference(sample.color, referencePixel);
        float motionPenalty = 1.0f;
        if (config_.useMotionMask && diff > config_.motionThreshold) {
            motionPenalty = std::max(0.1f, 1.0f - (diff / config_.motionThreshold - 1.0f));
        }
        
        float w = sample.weight * sample.confidence * motionPenalty;
        totalR += sample.color.r * w;
        totalG += sample.color.g * w;
        totalB += sample.color.b * w;
        totalWeight += w;
    }
    
    if (totalWeight > 0) {
        return RGBPixel(
            clamp(totalR / totalWeight, 0.0f, 1.0f),
            clamp(totalG / totalWeight, 0.0f, 1.0f),
            clamp(totalB / totalWeight, 0.0f, 1.0f)
        );
    }
    
    return referencePixel;
}

// ============================================================================
// Multi-Scale Laplacian Pyramid Sharpening
// ============================================================================

std::vector<DeghostEnhancer::PyramidLevel> DeghostEnhancer::buildGaussianPyramid(
    const RGBImage& image, 
    int levels
) {
    std::vector<PyramidLevel> pyramid;
    pyramid.reserve(levels);
    
    // Level 0 is the original image
    PyramidLevel level0;
    level0.image = image;  // Copy
    level0.width = image.width;
    level0.height = image.height;
    pyramid.push_back(std::move(level0));
    
    // Build subsequent levels by downsampling
    for (int i = 1; i < levels; ++i) {
        PyramidLevel level;
        level.image = downsample2x(pyramid[i - 1].image);
        level.width = level.image.width;
        level.height = level.image.height;
        pyramid.push_back(std::move(level));
    }
    
    return pyramid;
}

std::vector<RGBImage> DeghostEnhancer::buildLaplacianPyramid(
    const std::vector<PyramidLevel>& gaussian
) {
    std::vector<RGBImage> laplacian;
    laplacian.reserve(gaussian.size() - 1);
    
    // Laplacian[i] = Gaussian[i] - Upsample(Gaussian[i+1])
    for (size_t i = 0; i < gaussian.size() - 1; ++i) {
        const RGBImage& current = gaussian[i].image;
        RGBImage upsampled = upsample2x(gaussian[i + 1].image, current.width, current.height);
        
        RGBImage lap(current.width, current.height);
        for (int y = 0; y < current.height; ++y) {
            for (int x = 0; x < current.width; ++x) {
                const RGBPixel& c = current.at(x, y);
                const RGBPixel& u = upsampled.at(x, y);
                lap.at(x, y) = RGBPixel(c.r - u.r, c.g - u.g, c.b - u.b);
            }
        }
        laplacian.push_back(std::move(lap));
    }
    
    return laplacian;
}

RGBImage DeghostEnhancer::reconstructFromLaplacian(
    const std::vector<RGBImage>& laplacian,
    const PyramidLevel& base
) {
    // Start from the coarsest level
    RGBImage result = base.image;
    
    // Add Laplacian details from coarse to fine
    for (int i = static_cast<int>(laplacian.size()) - 1; i >= 0; --i) {
        // Upsample current result
        result = upsample2x(result, laplacian[i].width, laplacian[i].height);
        
        // Add Laplacian details
        for (int y = 0; y < result.height; ++y) {
            for (int x = 0; x < result.width; ++x) {
                RGBPixel& r = result.at(x, y);
                const RGBPixel& l = laplacian[i].at(x, y);
                r.r = clamp(r.r + l.r, 0.0f, 1.0f);
                r.g = clamp(r.g + l.g, 0.0f, 1.0f);
                r.b = clamp(r.b + l.b, 0.0f, 1.0f);
            }
        }
    }
    
    return result;
}

RGBImage DeghostEnhancer::downsample2x(const RGBImage& image) {
    int newWidth = image.width / 2;
    int newHeight = image.height / 2;
    
    if (newWidth < 1) newWidth = 1;
    if (newHeight < 1) newHeight = 1;
    
    RGBImage result(newWidth, newHeight);
    
    for (int y = 0; y < newHeight; ++y) {
        for (int x = 0; x < newWidth; ++x) {
            int sx = x * 2;
            int sy = y * 2;
            
            // 2x2 box filter
            float r = 0, g = 0, b = 0;
            int count = 0;
            
            for (int dy = 0; dy < 2; ++dy) {
                for (int dx = 0; dx < 2; ++dx) {
                    int px = std::min(sx + dx, image.width - 1);
                    int py = std::min(sy + dy, image.height - 1);
                    const RGBPixel& p = image.at(px, py);
                    r += p.r;
                    g += p.g;
                    b += p.b;
                    count++;
                }
            }
            
            result.at(x, y) = RGBPixel(r / count, g / count, b / count);
        }
    }
    
    return result;
}

RGBImage DeghostEnhancer::upsample2x(const RGBImage& image, int targetWidth, int targetHeight) {
    RGBImage result(targetWidth, targetHeight);
    
    float scaleX = static_cast<float>(image.width) / targetWidth;
    float scaleY = static_cast<float>(image.height) / targetHeight;
    
    for (int y = 0; y < targetHeight; ++y) {
        for (int x = 0; x < targetWidth; ++x) {
            float srcX = x * scaleX;
            float srcY = y * scaleY;
            
            int x0 = static_cast<int>(srcX);
            int y0 = static_cast<int>(srcY);
            int x1 = std::min(x0 + 1, image.width - 1);
            int y1 = std::min(y0 + 1, image.height - 1);
            
            float fx = srcX - x0;
            float fy = srcY - y0;
            
            const RGBPixel& p00 = image.at(x0, y0);
            const RGBPixel& p10 = image.at(x1, y0);
            const RGBPixel& p01 = image.at(x0, y1);
            const RGBPixel& p11 = image.at(x1, y1);
            
            // Bilinear interpolation
            float r = p00.r * (1 - fx) * (1 - fy) + p10.r * fx * (1 - fy) +
                     p01.r * (1 - fx) * fy + p11.r * fx * fy;
            float g = p00.g * (1 - fx) * (1 - fy) + p10.g * fx * (1 - fy) +
                     p01.g * (1 - fx) * fy + p11.g * fx * fy;
            float b = p00.b * (1 - fx) * (1 - fy) + p10.b * fx * (1 - fy) +
                     p01.b * (1 - fx) * fy + p11.b * fx * fy;
            
            result.at(x, y) = RGBPixel(r, g, b);
        }
    }
    
    return result;
}

void DeghostEnhancer::applyLaplacianSharpening(RGBImage& image) {
    if (config_.pyramidLevels < 2) return;
    
    // Build Gaussian pyramid
    auto gaussian = buildGaussianPyramid(image, config_.pyramidLevels);
    
    // Build Laplacian pyramid
    auto laplacian = buildLaplacianPyramid(gaussian);
    
    // Boost high-frequency details (Laplacian levels)
    // Apply stronger boost to finer levels (more detail)
    for (size_t i = 0; i < laplacian.size(); ++i) {
        // Finer levels get more boost
        float levelBoost = config_.sharpenStrength * (1.0f + 0.3f * (laplacian.size() - 1 - i));
        
        for (int y = 0; y < laplacian[i].height; ++y) {
            for (int x = 0; x < laplacian[i].width; ++x) {
                RGBPixel& p = laplacian[i].at(x, y);
                p.r *= levelBoost;
                p.g *= levelBoost;
                p.b *= levelBoost;
            }
        }
    }
    
    // Reconstruct image with boosted details
    image = reconstructFromLaplacian(laplacian, gaussian.back());
}

// ============================================================================
// Local Contrast Enhancement (CLAHE-like)
// ============================================================================

void DeghostEnhancer::applyLocalContrastEnhancement(RGBImage& image) {
    if (config_.contrastStrength <= 0) return;
    
    int width = image.width;
    int height = image.height;
    int tileSize = config_.claheTileSize;
    
    // Convert to grayscale for luminance processing
    GrayImage luminance(width, height);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const RGBPixel& p = image.at(x, y);
            luminance.at(x, y) = 0.299f * p.r + 0.587f * p.g + 0.114f * p.b;
        }
    }
    
    // Process each tile
    int tilesX = (width + tileSize - 1) / tileSize;
    int tilesY = (height + tileSize - 1) / tileSize;
    
    // Create enhanced luminance
    GrayImage enhancedLum(width, height);
    
    for (int ty = 0; ty < tilesY; ++ty) {
        for (int tx = 0; tx < tilesX; ++tx) {
            int startX = tx * tileSize;
            int startY = ty * tileSize;
            int endX = std::min(startX + tileSize, width);
            int endY = std::min(startY + tileSize, height);
            
            // Compute local histogram
            std::vector<int> histogram(256, 0);
            int pixelCount = 0;
            
            for (int y = startY; y < endY; ++y) {
                for (int x = startX; x < endX; ++x) {
                    int bin = static_cast<int>(luminance.at(x, y) * 255.0f);
                    bin = clamp(bin, 0, 255);
                    histogram[bin]++;
                    pixelCount++;
                }
            }
            
            // Apply clip limit
            int clipLimit = config_.claheClipLimit;
            int excess = 0;
            for (int i = 0; i < 256; ++i) {
                if (histogram[i] > clipLimit) {
                    excess += histogram[i] - clipLimit;
                    histogram[i] = clipLimit;
                }
            }
            
            // Redistribute excess
            int redistribution = excess / 256;
            for (int i = 0; i < 256; ++i) {
                histogram[i] += redistribution;
            }
            
            // Build CDF
            std::vector<float> cdf(256);
            cdf[0] = static_cast<float>(histogram[0]) / pixelCount;
            for (int i = 1; i < 256; ++i) {
                cdf[i] = cdf[i - 1] + static_cast<float>(histogram[i]) / pixelCount;
            }
            
            // Apply equalization
            for (int y = startY; y < endY; ++y) {
                for (int x = startX; x < endX; ++x) {
                    int bin = static_cast<int>(luminance.at(x, y) * 255.0f);
                    bin = clamp(bin, 0, 255);
                    enhancedLum.at(x, y) = cdf[bin];
                }
            }
        }
    }
    
    // Blend enhanced luminance with original
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float origLum = luminance.at(x, y);
            float newLum = enhancedLum.at(x, y);
            
            // Blend based on contrast strength
            float targetLum = origLum + (newLum - origLum) * config_.contrastStrength;
            
            // Apply luminance change to RGB
            if (origLum > 0.001f) {
                float scale = targetLum / origLum;
                RGBPixel& p = image.at(x, y);
                p.r = clamp(p.r * scale, 0.0f, 1.0f);
                p.g = clamp(p.g * scale, 0.0f, 1.0f);
                p.b = clamp(p.b * scale, 0.0f, 1.0f);
            }
        }
    }
}

// ============================================================================
// Edge-Aware Sharpening
// ============================================================================

float DeghostEnhancer::computeEdgeMagnitude(const RGBImage& image, int x, int y) {
    if (x <= 0 || x >= image.width - 1 || y <= 0 || y >= image.height - 1) {
        return 0.0f;
    }
    
    // Sobel operator
    auto lum = [](const RGBPixel& p) {
        return 0.299f * p.r + 0.587f * p.g + 0.114f * p.b;
    };
    
    float gx = -lum(image.at(x - 1, y - 1)) + lum(image.at(x + 1, y - 1))
              - 2.0f * lum(image.at(x - 1, y)) + 2.0f * lum(image.at(x + 1, y))
              - lum(image.at(x - 1, y + 1)) + lum(image.at(x + 1, y + 1));
    
    float gy = -lum(image.at(x - 1, y - 1)) - 2.0f * lum(image.at(x, y - 1)) - lum(image.at(x + 1, y - 1))
              + lum(image.at(x - 1, y + 1)) + 2.0f * lum(image.at(x, y + 1)) + lum(image.at(x + 1, y + 1));
    
    return std::sqrt(gx * gx + gy * gy);
}

void DeghostEnhancer::applyEdgeAwareSharpening(RGBImage& image) {
    int width = image.width;
    int height = image.height;
    
    // Create blurred version for unsharp mask
    RGBImage blurred = image;
    gaussianBlur3x3(blurred);
    
    // Apply edge-aware unsharp mask
    for (int y = 1; y < height - 1; ++y) {
        for (int x = 1; x < width - 1; ++x) {
            float edgeMag = computeEdgeMagnitude(image, x, y);
            
            // Adaptive sharpening: more on edges, less on flat areas
            float adaptiveStrength = config_.sharpenStrength;
            if (edgeMag > config_.edgeThreshold) {
                adaptiveStrength *= config_.edgeBoost;  // Boost edges
            } else {
                adaptiveStrength *= 0.5f;  // Reduce on flat areas to avoid noise
            }
            
            RGBPixel& p = image.at(x, y);
            const RGBPixel& b = blurred.at(x, y);
            
            // Unsharp mask: output = original + strength * (original - blurred)
            p.r = clamp(p.r + adaptiveStrength * (p.r - b.r), 0.0f, 1.0f);
            p.g = clamp(p.g + adaptiveStrength * (p.g - b.g), 0.0f, 1.0f);
            p.b = clamp(p.b + adaptiveStrength * (p.b - b.b), 0.0f, 1.0f);
        }
    }
}

void DeghostEnhancer::gaussianBlur3x3(RGBImage& image) {
    int width = image.width;
    int height = image.height;
    
    RGBImage temp(width, height);
    
    // Gaussian 3x3 kernel: [1 2 1; 2 4 2; 1 2 1] / 16
    const float kernel[3][3] = {
        {1.0f/16, 2.0f/16, 1.0f/16},
        {2.0f/16, 4.0f/16, 2.0f/16},
        {1.0f/16, 2.0f/16, 1.0f/16}
    };
    
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float r = 0, g = 0, b = 0;
            
            for (int ky = -1; ky <= 1; ++ky) {
                for (int kx = -1; kx <= 1; ++kx) {
                    int px = clamp(x + kx, 0, width - 1);
                    int py = clamp(y + ky, 0, height - 1);
                    float w = kernel[ky + 1][kx + 1];
                    
                    const RGBPixel& p = image.at(px, py);
                    r += p.r * w;
                    g += p.g * w;
                    b += p.b * w;
                }
            }
            
            temp.at(x, y) = RGBPixel(r, g, b);
        }
    }
    
    image = std::move(temp);
}

// ============================================================================
// Full Enhancement Pipeline
// ============================================================================

void DeghostEnhancer::enhance(RGBImage& image) {
    LOGI("DeghostEnhancer: Starting enhancement pipeline (%dx%d)", image.width, image.height);
    
    // Step 1: Multi-scale Laplacian sharpening
    if (config_.pyramidLevels >= 2 && config_.sharpenStrength > 0) {
        LOGD("DeghostEnhancer: Applying Laplacian pyramid sharpening (levels=%d, strength=%.2f)",
             config_.pyramidLevels, config_.sharpenStrength);
        applyLaplacianSharpening(image);
    }
    
    // Step 2: Edge-aware sharpening for fine details
    if (config_.sharpenStrength > 0) {
        LOGD("DeghostEnhancer: Applying edge-aware sharpening");
        applyEdgeAwareSharpening(image);
    }
    
    // Step 3: Local contrast enhancement
    if (config_.contrastStrength > 0) {
        LOGD("DeghostEnhancer: Applying local contrast enhancement (strength=%.2f)",
             config_.contrastStrength);
        applyLocalContrastEnhancement(image);
    }
    
    LOGI("DeghostEnhancer: Enhancement complete");
}

} // namespace ultradetail
