/**
 * freq_separation.cpp - Frequency Separation Refinement Implementation
 * 
 * Implements adaptive high-frequency enhancement with edge protection.
 */

#include "freq_separation.h"
#include "neon_utils.h"
#include <cmath>
#include <algorithm>

namespace ultradetail {

FreqSeparationProcessor::FreqSeparationProcessor(const FreqSeparationParams& params)
    : params_(params)
    , kernelRadius_(0) {
    buildGaussianKernel(params_.lowPassSigma);
}

void FreqSeparationProcessor::buildGaussianKernel(float sigma) {
    // Kernel size: 6*sigma rounded up to odd number
    int size = params_.kernelSize;
    if (size <= 0) {
        size = static_cast<int>(std::ceil(sigma * 6.0f));
        if (size % 2 == 0) size++;
    }
    
    kernelRadius_ = size / 2;
    gaussianKernel_.resize(size);
    
    float sum = 0.0f;
    float sigma2 = 2.0f * sigma * sigma;
    
    for (int i = 0; i < size; ++i) {
        float x = static_cast<float>(i - kernelRadius_);
        gaussianKernel_[i] = std::exp(-(x * x) / sigma2);
        sum += gaussianKernel_[i];
    }
    
    // Normalize
    for (int i = 0; i < size; ++i) {
        gaussianKernel_[i] /= sum;
    }
    
    LOGD("FreqSep: Built Gaussian kernel, sigma=%.2f, size=%d", sigma, size);
}

void FreqSeparationProcessor::gaussianBlurH(const GrayImage& input, GrayImage& output) {
    const int width = input.width;
    const int height = input.height;
    const int kSize = static_cast<int>(gaussianKernel_.size());
    const float* kernel = gaussianKernel_.data();
    
    output.resize(width, height);
    
    for (int y = 0; y < height; ++y) {
        const float* inRow = input.row(y);
        float* outRow = output.row(y);
        
        for (int x = 0; x < width; ++x) {
            float sum = 0.0f;
            float weightSum = 0.0f;
            
            for (int k = 0; k < kSize; ++k) {
                int sx = x + k - kernelRadius_;
                if (sx >= 0 && sx < width) {
                    sum += inRow[sx] * kernel[k];
                    weightSum += kernel[k];
                }
            }
            
            outRow[x] = (weightSum > 0.0f) ? sum / weightSum : inRow[x];
        }
    }
}

void FreqSeparationProcessor::gaussianBlurV(const GrayImage& input, GrayImage& output) {
    const int width = input.width;
    const int height = input.height;
    const int kSize = static_cast<int>(gaussianKernel_.size());
    const float* kernel = gaussianKernel_.data();
    
    output.resize(width, height);
    
    for (int y = 0; y < height; ++y) {
        float* outRow = output.row(y);
        
        for (int x = 0; x < width; ++x) {
            float sum = 0.0f;
            float weightSum = 0.0f;
            
            for (int k = 0; k < kSize; ++k) {
                int sy = y + k - kernelRadius_;
                if (sy >= 0 && sy < height) {
                    sum += input.at(x, sy) * kernel[k];
                    weightSum += kernel[k];
                }
            }
            
            outRow[x] = (weightSum > 0.0f) ? sum / weightSum : input.at(x, y);
        }
    }
}

void FreqSeparationProcessor::computeEdgeMask(const GrayImage& input, GrayImage& edgeMask) {
    const int width = input.width;
    const int height = input.height;
    
    edgeMask.resize(width, height);
    
    // Sobel operator for edge detection
    for (int y = 1; y < height - 1; ++y) {
        for (int x = 1; x < width - 1; ++x) {
            // Sobel X
            float gx = -input.at(x-1, y-1) - 2.0f * input.at(x-1, y) - input.at(x-1, y+1)
                      + input.at(x+1, y-1) + 2.0f * input.at(x+1, y) + input.at(x+1, y+1);
            
            // Sobel Y
            float gy = -input.at(x-1, y-1) - 2.0f * input.at(x, y-1) - input.at(x+1, y-1)
                      + input.at(x-1, y+1) + 2.0f * input.at(x, y+1) + input.at(x+1, y+1);
            
            // Gradient magnitude (normalized to 0-1)
            float mag = std::sqrt(gx * gx + gy * gy) / 4.0f;
            edgeMask.at(x, y) = clamp(mag, 0.0f, 1.0f);
        }
    }
    
    // Handle borders
    for (int x = 0; x < width; ++x) {
        edgeMask.at(x, 0) = edgeMask.at(x, 1);
        edgeMask.at(x, height - 1) = edgeMask.at(x, height - 2);
    }
    for (int y = 0; y < height; ++y) {
        edgeMask.at(0, y) = edgeMask.at(1, y);
        edgeMask.at(width - 1, y) = edgeMask.at(width - 2, y);
    }
}

void FreqSeparationProcessor::applyAdaptiveBoost(
    const GrayImage& highFreq,
    const GrayImage& edgeMask,
    GrayImage& boosted
) {
    const int width = highFreq.width;
    const int height = highFreq.height;
    const float boost = params_.highBoost;
    const float edgeProtect = params_.edgeProtection;
    
    boosted.resize(width, height);
    
#ifdef USE_NEON
    const float32x4_t vBoost = vdupq_n_f32(boost);
    const float32x4_t vEdgeProtect = vdupq_n_f32(edgeProtect);
    const float32x4_t vOne = vdupq_n_f32(1.0f);
#endif
    
    for (int y = 0; y < height; ++y) {
        const float* hfRow = highFreq.row(y);
        const float* edgeRow = edgeMask.row(y);
        float* outRow = boosted.row(y);
        
        int x = 0;
        
#ifdef USE_NEON
        for (; x + 3 < width; x += 4) {
            float32x4_t vHF = vld1q_f32(hfRow + x);
            float32x4_t vEdge = vld1q_f32(edgeRow + x);
            
            // Adaptive boost: reduce boost near strong edges to prevent halos
            // effectiveBoost = boost * (1 - edgeProtection * edgeStrength)
            float32x4_t vEdgeFactor = vmulq_f32(vEdgeProtect, vEdge);
            float32x4_t vEffectiveBoost = vmulq_f32(vBoost, vsubq_f32(vOne, vEdgeFactor));
            
            // Apply boost
            float32x4_t vBoosted = vmulq_f32(vHF, vEffectiveBoost);
            vst1q_f32(outRow + x, vBoosted);
        }
#endif
        
        // Scalar fallback
        for (; x < width; ++x) {
            float edgeFactor = edgeProtect * edgeRow[x];
            float effectiveBoost = boost * (1.0f - edgeFactor);
            outRow[x] = hfRow[x] * effectiveBoost;
        }
    }
}

FreqComponents FreqSeparationProcessor::separate(const GrayImage& input) {
    FreqComponents result;
    
    // Step 1: Compute low-frequency (Gaussian blur)
    GrayImage temp;
    gaussianBlurH(input, temp);
    gaussianBlurV(temp, result.lowFreq);
    
    // Step 2: Compute high-frequency (original - low)
    result.highFreq.resize(input.width, input.height);
    for (int y = 0; y < input.height; ++y) {
        for (int x = 0; x < input.width; ++x) {
            result.highFreq.at(x, y) = input.at(x, y) - result.lowFreq.at(x, y);
        }
    }
    
    // Step 3: Compute edge mask
    computeEdgeMask(input, result.edgeMask);
    
    return result;
}

void FreqSeparationProcessor::processGray(const GrayImage& input, GrayImage& output) {
    // Separate frequencies
    FreqComponents components = separate(input);
    
    // Apply adaptive boost to high-frequency
    GrayImage boostedHF;
    applyAdaptiveBoost(components.highFreq, components.edgeMask, boostedHF);
    
    // Recombine: output = lowFreq + boostedHighFreq
    output.resize(input.width, input.height);
    
    const float blend = params_.blendStrength;
    
    for (int y = 0; y < input.height; ++y) {
        const float* lfRow = components.lowFreq.row(y);
        const float* bhfRow = boostedHF.row(y);
        const float* origRow = input.row(y);
        float* outRow = output.row(y);
        
        for (int x = 0; x < input.width; ++x) {
            // Enhanced = lowFreq + boostedHighFreq
            float enhanced = lfRow[x] + bhfRow[x];
            
            // Blend with original based on strength
            outRow[x] = clamp(
                origRow[x] * (1.0f - blend) + enhanced * blend,
                0.0f, 1.0f
            );
        }
    }
}

void FreqSeparationProcessor::processRGB(const RGBImage& input, RGBImage& output) {
    const int width = input.width;
    const int height = input.height;
    
    output.resize(width, height);
    
    // Process each channel separately
    GrayImage channelIn, channelOut;
    channelIn.resize(width, height);
    
    // Process R channel
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            channelIn.at(x, y) = input.at(x, y).r;
        }
    }
    processGray(channelIn, channelOut);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            output.at(x, y).r = channelOut.at(x, y);
        }
    }
    
    // Process G channel
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            channelIn.at(x, y) = input.at(x, y).g;
        }
    }
    processGray(channelIn, channelOut);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            output.at(x, y).g = channelOut.at(x, y);
        }
    }
    
    // Process B channel
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            channelIn.at(x, y) = input.at(x, y).b;
        }
    }
    processGray(channelIn, channelOut);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            output.at(x, y).b = channelOut.at(x, y);
        }
    }
    
    LOGD("FreqSep: Processed RGB image %dx%d", width, height);
}

} // namespace ultradetail
