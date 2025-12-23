/**
 * exposure_fusion.cpp - Mertens Exposure Fusion Implementation
 */

#include "exposure_fusion.h"
#include <cmath>
#include <algorithm>

namespace ultradetail {

ExposureFusionProcessor::ExposureFusionProcessor(const ExposureFusionConfig& config)
    : config_(config) {
}

ExposureFusionResult ExposureFusionProcessor::fuse(const std::vector<RGBImage>& images) {
    ExposureFusionResult result;
    
    if (images.empty()) {
        LOGE("ExposureFusion: No input images");
        return result;
    }
    
    if (images.size() == 1) {
        result.fused = images[0];
        result.success = true;
        return result;
    }
    
    int width = images[0].width;
    int height = images[0].height;
    
    LOGI("ExposureFusion: Fusing %zu images (%dx%d)", images.size(), width, height);
    
    // Step 1: Compute weights for each image
    std::vector<GrayImage> weights;
    weights.reserve(images.size());
    
    float totalContrast = 0, totalSaturation = 0, totalExposure = 0;
    
    for (const auto& image : images) {
        GrayImage weight = computeWeights(image);
        weights.push_back(weight);
        
        // Compute average metrics for logging
        float avgW = 0;
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                avgW += weight.at(x, y);
            }
        }
        avgW /= (width * height);
    }
    
    // Step 2: Normalize weights
    normalizeWeights(weights);
    
    // Step 3: Build pyramids for each image
    std::vector<std::vector<RGBImage>> laplacianPyramids;
    std::vector<std::vector<GrayImage>> gaussianWeightPyramids;
    
    for (size_t i = 0; i < images.size(); ++i) {
        laplacianPyramids.push_back(buildLaplacianPyramid(images[i], config_.pyramidLevels));
        gaussianWeightPyramids.push_back(buildGaussianPyramidGray(weights[i], config_.pyramidLevels));
    }
    
    // Step 4: Blend pyramids
    std::vector<RGBImage> blendedPyramid;
    blendedPyramid.resize(config_.pyramidLevels);
    
    for (int level = 0; level < config_.pyramidLevels; ++level) {
        int levelWidth = laplacianPyramids[0][level].width;
        int levelHeight = laplacianPyramids[0][level].height;
        
        blendedPyramid[level].resize(levelWidth, levelHeight);
        
        for (int y = 0; y < levelHeight; ++y) {
            for (int x = 0; x < levelWidth; ++x) {
                RGBPixel blended(0, 0, 0);
                
                for (size_t i = 0; i < images.size(); ++i) {
                    float w = gaussianWeightPyramids[i][level].at(x, y);
                    const RGBPixel& p = laplacianPyramids[i][level].at(x, y);
                    
                    blended.r += w * p.r;
                    blended.g += w * p.g;
                    blended.b += w * p.b;
                }
                
                blendedPyramid[level].at(x, y) = blended;
            }
        }
    }
    
    // Step 5: Collapse pyramid to get final result
    result.fused = collapsePyramid(blendedPyramid);
    result.weights = weights;
    result.success = true;
    
    LOGI("ExposureFusion: Fusion complete");
    
    return result;
}

GrayImage ExposureFusionProcessor::computeWeights(const RGBImage& image) {
    GrayImage contrast = computeContrast(image);
    GrayImage saturation = computeSaturation(image);
    GrayImage exposure = computeWellExposedness(image);
    
    GrayImage weight;
    weight.resize(image.width, image.height);
    
    for (int y = 0; y < image.height; ++y) {
        for (int x = 0; x < image.width; ++x) {
            float c = std::pow(contrast.at(x, y), config_.contrastWeight);
            float s = std::pow(saturation.at(x, y), config_.saturationWeight);
            float e = std::pow(exposure.at(x, y), config_.exposureWeight);
            
            weight.at(x, y) = c * s * e + 1e-12f;  // Small epsilon to avoid division by zero
        }
    }
    
    // Apply Gaussian blur to smooth weights
    if (config_.sigma > 0) {
        weight = gaussianBlurGray(weight, config_.sigma);
    }
    
    return weight;
}

GrayImage ExposureFusionProcessor::computeContrast(const RGBImage& image) {
    GrayImage contrast;
    contrast.resize(image.width, image.height);
    
    // Compute Laplacian (local contrast)
    for (int y = 1; y < image.height - 1; ++y) {
        for (int x = 1; x < image.width - 1; ++x) {
            const RGBPixel& center = image.at(x, y);
            const RGBPixel& up = image.at(x, y - 1);
            const RGBPixel& down = image.at(x, y + 1);
            const RGBPixel& left = image.at(x - 1, y);
            const RGBPixel& right = image.at(x + 1, y);
            
            // Grayscale Laplacian
            float centerGray = 0.299f * center.r + 0.587f * center.g + 0.114f * center.b;
            float upGray = 0.299f * up.r + 0.587f * up.g + 0.114f * up.b;
            float downGray = 0.299f * down.r + 0.587f * down.g + 0.114f * down.b;
            float leftGray = 0.299f * left.r + 0.587f * left.g + 0.114f * left.b;
            float rightGray = 0.299f * right.r + 0.587f * right.g + 0.114f * right.b;
            
            float laplacian = std::abs(4.0f * centerGray - upGray - downGray - leftGray - rightGray);
            contrast.at(x, y) = laplacian;
        }
    }
    
    // Fill borders
    for (int x = 0; x < image.width; ++x) {
        contrast.at(x, 0) = contrast.at(x, 1);
        contrast.at(x, image.height - 1) = contrast.at(x, image.height - 2);
    }
    for (int y = 0; y < image.height; ++y) {
        contrast.at(0, y) = contrast.at(1, y);
        contrast.at(image.width - 1, y) = contrast.at(image.width - 2, y);
    }
    
    return contrast;
}

GrayImage ExposureFusionProcessor::computeSaturation(const RGBImage& image) {
    GrayImage saturation;
    saturation.resize(image.width, image.height);
    
    for (int y = 0; y < image.height; ++y) {
        for (int x = 0; x < image.width; ++x) {
            const RGBPixel& p = image.at(x, y);
            
            // Standard deviation of RGB channels
            float mean = (p.r + p.g + p.b) / 3.0f;
            float variance = ((p.r - mean) * (p.r - mean) +
                            (p.g - mean) * (p.g - mean) +
                            (p.b - mean) * (p.b - mean)) / 3.0f;
            
            saturation.at(x, y) = std::sqrt(variance);
        }
    }
    
    return saturation;
}

GrayImage ExposureFusionProcessor::computeWellExposedness(const RGBImage& image) {
    GrayImage exposure;
    exposure.resize(image.width, image.height);
    
    const float sigma = 0.2f;  // Gaussian sigma
    
    for (int y = 0; y < image.height; ++y) {
        for (int x = 0; x < image.width; ++x) {
            const RGBPixel& p = image.at(x, y);
            
            // Gaussian centered at 0.5 for each channel
            float er = std::exp(-((p.r - 0.5f) * (p.r - 0.5f)) / (2.0f * sigma * sigma));
            float eg = std::exp(-((p.g - 0.5f) * (p.g - 0.5f)) / (2.0f * sigma * sigma));
            float eb = std::exp(-((p.b - 0.5f) * (p.b - 0.5f)) / (2.0f * sigma * sigma));
            
            exposure.at(x, y) = er * eg * eb;
        }
    }
    
    return exposure;
}

void ExposureFusionProcessor::normalizeWeights(std::vector<GrayImage>& weights) {
    if (weights.empty()) return;
    
    int width = weights[0].width;
    int height = weights[0].height;
    
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float sum = 0;
            for (auto& w : weights) {
                sum += w.at(x, y);
            }
            
            if (sum > 1e-12f) {
                for (auto& w : weights) {
                    w.at(x, y) /= sum;
                }
            }
        }
    }
}

std::vector<RGBImage> ExposureFusionProcessor::buildLaplacianPyramid(const RGBImage& image, int levels) {
    std::vector<RGBImage> gaussian = buildGaussianPyramid(image, levels);
    std::vector<RGBImage> laplacian;
    laplacian.resize(levels);
    
    for (int i = 0; i < levels - 1; ++i) {
        RGBImage upsampled = upsample(gaussian[i + 1], gaussian[i].width, gaussian[i].height);
        
        laplacian[i].resize(gaussian[i].width, gaussian[i].height);
        
        for (int y = 0; y < gaussian[i].height; ++y) {
            for (int x = 0; x < gaussian[i].width; ++x) {
                const RGBPixel& g = gaussian[i].at(x, y);
                const RGBPixel& u = upsampled.at(x, y);
                
                laplacian[i].at(x, y) = RGBPixel(g.r - u.r, g.g - u.g, g.b - u.b);
            }
        }
    }
    
    // Last level is just the Gaussian
    laplacian[levels - 1] = gaussian[levels - 1];
    
    return laplacian;
}

std::vector<RGBImage> ExposureFusionProcessor::buildGaussianPyramid(const RGBImage& image, int levels) {
    std::vector<RGBImage> pyramid;
    pyramid.resize(levels);
    pyramid[0] = image;
    
    for (int i = 1; i < levels; ++i) {
        pyramid[i] = downsample(pyramid[i - 1]);
    }
    
    return pyramid;
}

std::vector<GrayImage> ExposureFusionProcessor::buildGaussianPyramidGray(const GrayImage& image, int levels) {
    std::vector<GrayImage> pyramid;
    pyramid.resize(levels);
    pyramid[0] = image;
    
    for (int i = 1; i < levels; ++i) {
        pyramid[i] = downsampleGray(pyramid[i - 1]);
    }
    
    return pyramid;
}

RGBImage ExposureFusionProcessor::collapsePyramid(const std::vector<RGBImage>& pyramid) {
    if (pyramid.empty()) return RGBImage();
    
    RGBImage result = pyramid.back();
    
    for (int i = pyramid.size() - 2; i >= 0; --i) {
        result = upsample(result, pyramid[i].width, pyramid[i].height);
        
        for (int y = 0; y < result.height; ++y) {
            for (int x = 0; x < result.width; ++x) {
                RGBPixel& r = result.at(x, y);
                const RGBPixel& p = pyramid[i].at(x, y);
                
                r.r += p.r;
                r.g += p.g;
                r.b += p.b;
            }
        }
    }
    
    return result;
}

RGBImage ExposureFusionProcessor::downsample(const RGBImage& image) {
    RGBImage blurred = gaussianBlur(image, 1.0f);
    
    int newWidth = image.width / 2;
    int newHeight = image.height / 2;
    
    RGBImage result;
    result.resize(newWidth, newHeight);
    
    for (int y = 0; y < newHeight; ++y) {
        for (int x = 0; x < newWidth; ++x) {
            result.at(x, y) = blurred.at(x * 2, y * 2);
        }
    }
    
    return result;
}

GrayImage ExposureFusionProcessor::downsampleGray(const GrayImage& image) {
    GrayImage blurred = gaussianBlurGray(image, 1.0f);
    
    int newWidth = image.width / 2;
    int newHeight = image.height / 2;
    
    GrayImage result;
    result.resize(newWidth, newHeight);
    
    for (int y = 0; y < newHeight; ++y) {
        for (int x = 0; x < newWidth; ++x) {
            result.at(x, y) = blurred.at(x * 2, y * 2);
        }
    }
    
    return result;
}

RGBImage ExposureFusionProcessor::upsample(const RGBImage& image, int targetWidth, int targetHeight) {
    RGBImage result;
    result.resize(targetWidth, targetHeight);
    
    float scaleX = (float)image.width / targetWidth;
    float scaleY = (float)image.height / targetHeight;
    
    for (int y = 0; y < targetHeight; ++y) {
        for (int x = 0; x < targetWidth; ++x) {
            float srcX = x * scaleX;
            float srcY = y * scaleY;
            
            int x0 = (int)srcX;
            int y0 = (int)srcY;
            int x1 = std::min(x0 + 1, image.width - 1);
            int y1 = std::min(y0 + 1, image.height - 1);
            
            float fx = srcX - x0;
            float fy = srcY - y0;
            
            const RGBPixel& p00 = image.at(x0, y0);
            const RGBPixel& p10 = image.at(x1, y0);
            const RGBPixel& p01 = image.at(x0, y1);
            const RGBPixel& p11 = image.at(x1, y1);
            
            float r = (1 - fx) * (1 - fy) * p00.r + fx * (1 - fy) * p10.r +
                     (1 - fx) * fy * p01.r + fx * fy * p11.r;
            float g = (1 - fx) * (1 - fy) * p00.g + fx * (1 - fy) * p10.g +
                     (1 - fx) * fy * p01.g + fx * fy * p11.g;
            float b = (1 - fx) * (1 - fy) * p00.b + fx * (1 - fy) * p10.b +
                     (1 - fx) * fy * p01.b + fx * fy * p11.b;
            
            result.at(x, y) = RGBPixel(r, g, b);
        }
    }
    
    return result;
}

RGBImage ExposureFusionProcessor::gaussianBlur(const RGBImage& image, float sigma) {
    // Simple 5x5 Gaussian kernel
    int kernelSize = 5;
    int halfSize = kernelSize / 2;
    
    std::vector<float> kernel(kernelSize);
    float sum = 0;
    
    for (int i = 0; i < kernelSize; ++i) {
        float x = i - halfSize;
        kernel[i] = std::exp(-(x * x) / (2 * sigma * sigma));
        sum += kernel[i];
    }
    
    for (int i = 0; i < kernelSize; ++i) {
        kernel[i] /= sum;
    }
    
    RGBImage temp, result;
    temp.resize(image.width, image.height);
    result.resize(image.width, image.height);
    
    // Horizontal pass
    for (int y = 0; y < image.height; ++y) {
        for (int x = 0; x < image.width; ++x) {
            float r = 0, g = 0, b = 0;
            
            for (int k = 0; k < kernelSize; ++k) {
                int xx = x + k - halfSize;
                xx = std::max(0, std::min(image.width - 1, xx));
                
                const RGBPixel& p = image.at(xx, y);
                r += kernel[k] * p.r;
                g += kernel[k] * p.g;
                b += kernel[k] * p.b;
            }
            
            temp.at(x, y) = RGBPixel(r, g, b);
        }
    }
    
    // Vertical pass
    for (int y = 0; y < image.height; ++y) {
        for (int x = 0; x < image.width; ++x) {
            float r = 0, g = 0, b = 0;
            
            for (int k = 0; k < kernelSize; ++k) {
                int yy = y + k - halfSize;
                yy = std::max(0, std::min(image.height - 1, yy));
                
                const RGBPixel& p = temp.at(x, yy);
                r += kernel[k] * p.r;
                g += kernel[k] * p.g;
                b += kernel[k] * p.b;
            }
            
            result.at(x, y) = RGBPixel(r, g, b);
        }
    }
    
    return result;
}

GrayImage ExposureFusionProcessor::gaussianBlurGray(const GrayImage& image, float sigma) {
    int kernelSize = 5;
    int halfSize = kernelSize / 2;
    
    std::vector<float> kernel(kernelSize);
    float sum = 0;
    
    for (int i = 0; i < kernelSize; ++i) {
        float x = i - halfSize;
        kernel[i] = std::exp(-(x * x) / (2 * sigma * sigma));
        sum += kernel[i];
    }
    
    for (int i = 0; i < kernelSize; ++i) {
        kernel[i] /= sum;
    }
    
    GrayImage temp, result;
    temp.resize(image.width, image.height);
    result.resize(image.width, image.height);
    
    // Horizontal pass
    for (int y = 0; y < image.height; ++y) {
        for (int x = 0; x < image.width; ++x) {
            float val = 0;
            
            for (int k = 0; k < kernelSize; ++k) {
                int xx = x + k - halfSize;
                xx = std::max(0, std::min(image.width - 1, xx));
                val += kernel[k] * image.at(xx, y);
            }
            
            temp.at(x, y) = val;
        }
    }
    
    // Vertical pass
    for (int y = 0; y < image.height; ++y) {
        for (int x = 0; x < image.width; ++x) {
            float val = 0;
            
            for (int k = 0; k < kernelSize; ++k) {
                int yy = y + k - halfSize;
                yy = std::max(0, std::min(image.height - 1, yy));
                val += kernel[k] * temp.at(x, yy);
            }
            
            result.at(x, y) = val;
        }
    }
    
    return result;
}

} // namespace ultradetail
