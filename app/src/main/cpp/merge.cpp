/**
 * merge.cpp - Robust frame merging implementation
 * 
 * HDR+ style burst merging with trimmed mean, M-estimator,
 * and Wiener filtering for optimal noise reduction.
 */

#include "merge.h"
#include "neon_utils.h"
#include <algorithm>
#include <cmath>
#include <numeric>

namespace ultradetail {

FrameMerger::FrameMerger(const MergeParams& params)
    : params_(params) {
}

float FrameMerger::trimmedMean(std::vector<float>& values) {
    if (values.empty()) return 0.0f;
    if (values.size() == 1) return values[0];
    
    std::sort(values.begin(), values.end());
    
    int n = static_cast<int>(values.size());
    int trimCount = static_cast<int>(n * params_.trimRatio);
    
    // Ensure we keep at least one value
    trimCount = std::min(trimCount, (n - 1) / 2);
    
    float sum = 0.0f;
    int count = 0;
    for (int i = trimCount; i < n - trimCount; ++i) {
        sum += values[i];
        count++;
    }
    
    return count > 0 ? sum / count : values[n / 2];
}

float FrameMerger::huberMean(const std::vector<float>& values) {
    if (values.empty()) return 0.0f;
    if (values.size() == 1) return values[0];
    
    // Initial estimate: median
    std::vector<float> sorted = values;
    std::sort(sorted.begin(), sorted.end());
    float estimate = sorted[sorted.size() / 2];
    
    // Iteratively refine using Huber weights
    const int maxIter = 10;
    const float delta = params_.huberDelta;
    
    for (int iter = 0; iter < maxIter; ++iter) {
        float weightedSum = 0.0f;
        float weightSum = 0.0f;
        
        for (float v : values) {
            float residual = v - estimate;
            float absRes = std::abs(residual);
            
            // Huber weight function
            float weight = absRes <= delta ? 1.0f : delta / absRes;
            
            weightedSum += weight * v;
            weightSum += weight;
        }
        
        float newEstimate = weightSum > 0 ? weightedSum / weightSum : estimate;
        
        // Check convergence
        if (std::abs(newEstimate - estimate) < 1e-6f) {
            break;
        }
        estimate = newEstimate;
    }
    
    return estimate;
}

float FrameMerger::median(std::vector<float>& values) {
    if (values.empty()) return 0.0f;
    
    size_t n = values.size();
    std::nth_element(values.begin(), values.begin() + n / 2, values.end());
    
    if (n % 2 == 0) {
        float mid1 = values[n / 2];
        std::nth_element(values.begin(), values.begin() + n / 2 - 1, values.end());
        float mid2 = values[n / 2 - 1];
        return (mid1 + mid2) / 2.0f;
    }
    
    return values[n / 2];
}

void FrameMerger::merge(const std::vector<RGBImage>& frames, RGBImage& output) {
    if (frames.empty()) {
        output = RGBImage();
        return;
    }
    
    if (frames.size() == 1) {
        output = frames[0];
        return;
    }
    
    int width = frames[0].width;
    int height = frames[0].height;
    int numFrames = static_cast<int>(frames.size());
    
    output = RGBImage(width, height);
    
    LOGD("Merging %d frames (%dx%d) using method %d",
         numFrames, width, height, static_cast<int>(params_.method));
    
    // Temporary storage for pixel values
    std::vector<float> rValues(numFrames);
    std::vector<float> gValues(numFrames);
    std::vector<float> bValues(numFrames);
    
    for (int y = 0; y < height; ++y) {
        RGBPixel* outRow = output.row(y);
        
        for (int x = 0; x < width; ++x) {
            // Gather values from all frames
            for (int f = 0; f < numFrames; ++f) {
                const RGBPixel& px = frames[f].at(x, y);
                rValues[f] = px.r;
                gValues[f] = px.g;
                bValues[f] = px.b;
            }
            
            // Merge based on method
            RGBPixel merged;
            
            switch (params_.method) {
                case MergeMethod::AVERAGE: {
                    float rSum = 0, gSum = 0, bSum = 0;
                    for (int f = 0; f < numFrames; ++f) {
                        rSum += rValues[f];
                        gSum += gValues[f];
                        bSum += bValues[f];
                    }
                    merged.r = rSum / numFrames;
                    merged.g = gSum / numFrames;
                    merged.b = bSum / numFrames;
                    break;
                }
                
                case MergeMethod::TRIMMED_MEAN:
                    merged.r = trimmedMean(rValues);
                    merged.g = trimmedMean(gValues);
                    merged.b = trimmedMean(bValues);
                    break;
                
                case MergeMethod::M_ESTIMATOR:
                    merged.r = huberMean(rValues);
                    merged.g = huberMean(gValues);
                    merged.b = huberMean(bValues);
                    break;
                
                case MergeMethod::MEDIAN:
                    merged.r = median(rValues);
                    merged.g = median(gValues);
                    merged.b = median(bValues);
                    break;
            }
            
            outRow[x] = merged;
        }
    }
    
    // Apply Wiener filter if enabled
    if (params_.applyWienerFilter) {
        RGBImage filtered;
        applyWienerFilter(output, filtered);
        output = std::move(filtered);
    }
    
    LOGD("Merge complete");
}

void FrameMerger::mergeWithWeights(
    const std::vector<RGBImage>& frames,
    const std::vector<FrameAlignment>& alignments,
    RGBImage& output
) {
    if (frames.empty()) {
        output = RGBImage();
        return;
    }
    
    if (frames.size() == 1) {
        output = frames[0];
        return;
    }
    
    int width = frames[0].width;
    int height = frames[0].height;
    int numFrames = static_cast<int>(frames.size());
    
    output = RGBImage(width, height);
    
    // Compute weights based on alignment confidence
    std::vector<float> frameWeights(numFrames);
    float totalWeight = 0.0f;
    
    for (int f = 0; f < numFrames; ++f) {
        // Higher confidence = higher weight
        // Also penalize high motion
        float confidence = alignments[f].isValid ? alignments[f].confidence : 0.5f;
        float motionPenalty = std::exp(-alignments[f].averageMotion / 10.0f);
        frameWeights[f] = confidence * motionPenalty;
        totalWeight += frameWeights[f];
    }
    
    // Normalize weights
    if (totalWeight > 0) {
        for (int f = 0; f < numFrames; ++f) {
            frameWeights[f] /= totalWeight;
        }
    } else {
        // Equal weights if all failed
        float equalWeight = 1.0f / numFrames;
        std::fill(frameWeights.begin(), frameWeights.end(), equalWeight);
    }
    
    LOGD("Weighted merge: weights = [%.3f, %.3f, ...]",
         frameWeights[0], numFrames > 1 ? frameWeights[1] : 0.0f);
    
    // Weighted merge with NaN/Inf protection
    int invalidPixelCount = 0;
    for (int y = 0; y < height; ++y) {
        RGBPixel* outRow = output.row(y);
        
        for (int x = 0; x < width; ++x) {
            float sumR = 0, sumG = 0, sumB = 0;
            float validWeightSum = 0;
            
            for (int f = 0; f < numFrames; ++f) {
                const RGBPixel& px = frames[f].at(x, y);
                float w = frameWeights[f];
                
                // Skip invalid pixels from this frame
                if (!std::isfinite(px.r) || !std::isfinite(px.g) || !std::isfinite(px.b)) {
                    continue;
                }
                
                sumR += px.r * w;
                sumG += px.g * w;
                sumB += px.b * w;
                validWeightSum += w;
            }
            
            RGBPixel merged;
            if (validWeightSum > 0.0f) {
                // Normalize by actual valid weight sum
                float invWeight = 1.0f / validWeightSum;
                merged.r = clamp(sumR * invWeight, 0.0f, 1.0f);
                merged.g = clamp(sumG * invWeight, 0.0f, 1.0f);
                merged.b = clamp(sumB * invWeight, 0.0f, 1.0f);
            } else {
                // All frames had invalid values at this pixel - use black
                merged.r = merged.g = merged.b = 0.0f;
                invalidPixelCount++;
            }
            
            outRow[x] = merged;
        }
    }
    
    if (invalidPixelCount > 0) {
        LOGW("Weighted merge: %d pixels had no valid input values", invalidPixelCount);
    }
    
    // Apply Wiener filter
    if (params_.applyWienerFilter) {
        RGBImage filtered;
        applyWienerFilter(output, filtered);
        output = std::move(filtered);
    }
}

float FrameMerger::estimateLocalVariance(const RGBImage& image, int x, int y, int channel) {
    int halfWin = params_.wienerWindowSize / 2;
    float sum = 0.0f;
    float sumSq = 0.0f;
    int count = 0;
    
    for (int dy = -halfWin; dy <= halfWin; ++dy) {
        int py = clamp(y + dy, 0, image.height - 1);
        for (int dx = -halfWin; dx <= halfWin; ++dx) {
            int px = clamp(x + dx, 0, image.width - 1);
            
            const RGBPixel& pixel = image.at(px, py);
            float value = channel == 0 ? pixel.r : (channel == 1 ? pixel.g : pixel.b);
            
            sum += value;
            sumSq += value * value;
            count++;
        }
    }
    
    float mean = sum / count;
    float variance = (sumSq / count) - (mean * mean);
    
    return std::max(variance, 0.0f);
}

void FrameMerger::applyWienerFilter(const RGBImage& input, RGBImage& output) {
    int width = input.width;
    int height = input.height;
    int halfWin = params_.wienerWindowSize / 2;
    float noiseVar = params_.wienerNoiseVar;
    
    output = RGBImage(width, height);
    
    for (int y = 0; y < height; ++y) {
        const RGBPixel* inRow = input.row(y);
        RGBPixel* outRow = output.row(y);
        
        for (int x = 0; x < width; ++x) {
            const RGBPixel& pixel = inRow[x];
            RGBPixel filtered;
            
            // Process each channel
            for (int c = 0; c < 3; ++c) {
                float value = c == 0 ? pixel.r : (c == 1 ? pixel.g : pixel.b);
                
                // Compute local mean
                float localSum = 0.0f;
                int count = 0;
                
                for (int dy = -halfWin; dy <= halfWin; ++dy) {
                    int py = clamp(y + dy, 0, height - 1);
                    for (int dx = -halfWin; dx <= halfWin; ++dx) {
                        int px = clamp(x + dx, 0, width - 1);
                        const RGBPixel& p = input.at(px, py);
                        float v = c == 0 ? p.r : (c == 1 ? p.g : p.b);
                        localSum += v;
                        count++;
                    }
                }
                
                float localMean = localSum / count;
                float localVar = estimateLocalVariance(input, x, y, c);
                
                // Wiener filter: output = mean + (var - noise) / var * (input - mean)
                float signalVar = std::max(localVar - noiseVar, 0.0f);
                float wienerGain = localVar > 1e-6f ? signalVar / localVar : 0.0f;
                
                float filteredValue = localMean + wienerGain * (value - localMean);
                
                if (c == 0) filtered.r = clamp(filteredValue, 0.0f, 1.0f);
                else if (c == 1) filtered.g = clamp(filteredValue, 0.0f, 1.0f);
                else filtered.b = clamp(filteredValue, 0.0f, 1.0f);
            }
            
            outRow[x] = filtered;
        }
    }
}

// NoiseModel implementation

float NoiseModel::estimateNoise(const GrayImage& image) {
    // Median Absolute Deviation (MAD) based noise estimation
    // Using Laplacian for high-frequency noise estimation
    
    int width = image.width;
    int height = image.height;
    
    std::vector<float> laplacianValues;
    laplacianValues.reserve((width - 2) * (height - 2));
    
    // Compute Laplacian
    for (int y = 1; y < height - 1; ++y) {
        for (int x = 1; x < width - 1; ++x) {
            float center = image.at(x, y);
            float laplacian = 4.0f * center
                            - image.at(x - 1, y)
                            - image.at(x + 1, y)
                            - image.at(x, y - 1)
                            - image.at(x, y + 1);
            laplacianValues.push_back(std::abs(laplacian));
        }
    }
    
    // Compute MAD
    std::sort(laplacianValues.begin(), laplacianValues.end());
    float mad = laplacianValues[laplacianValues.size() / 2];
    
    // Convert MAD to standard deviation estimate
    // sigma = MAD / 0.6745 for Gaussian noise
    float sigma = mad / 0.6745f;
    
    // Scale for Laplacian response
    return sigma / std::sqrt(20.0f);
}

void NoiseModel::computeWeights(
    const RGBImage& reference,
    const RGBImage& frame,
    const FrameAlignment& alignment,
    GrayImage& weights
) {
    int width = reference.width;
    int height = reference.height;
    
    weights = GrayImage(width, height);
    
    // Compute per-pixel weights based on:
    // 1. Difference from reference (motion/occlusion detection)
    // 2. Alignment confidence
    
    float baseWeight = alignment.isValid ? alignment.confidence : 0.5f;
    
    for (int y = 0; y < height; ++y) {
        const RGBPixel* refRow = reference.row(y);
        const RGBPixel* frameRow = frame.row(y);
        float* weightRow = weights.row(y);
        
        for (int x = 0; x < width; ++x) {
            const RGBPixel& refPx = refRow[x];
            const RGBPixel& framePx = frameRow[x];
            
            // Compute color difference
            float diffR = refPx.r - framePx.r;
            float diffG = refPx.g - framePx.g;
            float diffB = refPx.b - framePx.b;
            float colorDiff = std::sqrt(diffR * diffR + diffG * diffG + diffB * diffB);
            
            // Weight decreases with color difference
            // Using exponential falloff
            float diffWeight = std::exp(-colorDiff * 10.0f);
            
            weightRow[x] = baseWeight * diffWeight;
        }
    }
}

} // namespace ultradetail
