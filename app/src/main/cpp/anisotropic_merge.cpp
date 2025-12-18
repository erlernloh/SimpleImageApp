/**
 * anisotropic_merge.cpp - Edge-Aware Anisotropic Merging Implementation
 * 
 * Implements directional blending based on local edge structure.
 */

#include "anisotropic_merge.h"
#include "neon_utils.h"
#include <cmath>
#include <algorithm>

namespace ultradetail {

void AnisotropicKernel::buildFromStructure(const StructureTensor& st, float sigma, float elongation) {
    angle = st.angle;
    anisotropy = st.anisotropy;
    
    const int half = SIZE / 2;
    float sum = 0.0f;
    
    // Compute rotated anisotropic Gaussian
    float cosA = std::cos(angle);
    float sinA = std::sin(angle);
    
    // Sigma along and perpendicular to edge
    float sigmaAlong = sigma * elongation * anisotropy + sigma * (1.0f - anisotropy);
    float sigmaPerp = sigma;
    
    float invSigmaAlong2 = 1.0f / (2.0f * sigmaAlong * sigmaAlong);
    float invSigmaPerp2 = 1.0f / (2.0f * sigmaPerp * sigmaPerp);
    
    for (int dy = -half; dy <= half; ++dy) {
        for (int dx = -half; dx <= half; ++dx) {
            // Rotate coordinates to align with edge
            float rx = dx * cosA + dy * sinA;   // Along edge
            float ry = -dx * sinA + dy * cosA;  // Perpendicular to edge
            
            // Anisotropic Gaussian
            float w = std::exp(-(rx * rx * invSigmaAlong2 + ry * ry * invSigmaPerp2));
            weights[dy + half][dx + half] = w;
            sum += w;
        }
    }
    
    // Normalize
    if (sum > 0.0f) {
        float invSum = 1.0f / sum;
        for (int y = 0; y < SIZE; ++y) {
            for (int x = 0; x < SIZE; ++x) {
                weights[y][x] *= invSum;
            }
        }
    }
}

AnisotropicMergeProcessor::AnisotropicMergeProcessor(const AnisotropicMergeParams& params)
    : params_(params) {
}

void AnisotropicMergeProcessor::computeGradients(
    const GrayImage& input,
    GrayImage& gradX,
    GrayImage& gradY
) {
    const int width = input.width;
    const int height = input.height;
    
    gradX.resize(width, height);
    gradY.resize(width, height);
    
    // Sobel gradients
    for (int y = 1; y < height - 1; ++y) {
        for (int x = 1; x < width - 1; ++x) {
            // Sobel X: [-1 0 1; -2 0 2; -1 0 1]
            float gx = -input.at(x-1, y-1) + input.at(x+1, y-1)
                      - 2.0f * input.at(x-1, y) + 2.0f * input.at(x+1, y)
                      - input.at(x-1, y+1) + input.at(x+1, y+1);
            
            // Sobel Y: [-1 -2 -1; 0 0 0; 1 2 1]
            float gy = -input.at(x-1, y-1) - 2.0f * input.at(x, y-1) - input.at(x+1, y-1)
                      + input.at(x-1, y+1) + 2.0f * input.at(x, y+1) + input.at(x+1, y+1);
            
            gradX.at(x, y) = gx / 8.0f;  // Normalize
            gradY.at(x, y) = gy / 8.0f;
        }
    }
    
    // Handle borders (copy from adjacent)
    for (int x = 0; x < width; ++x) {
        gradX.at(x, 0) = gradX.at(x, 1);
        gradY.at(x, 0) = gradY.at(x, 1);
        gradX.at(x, height-1) = gradX.at(x, height-2);
        gradY.at(x, height-1) = gradY.at(x, height-2);
    }
    for (int y = 0; y < height; ++y) {
        gradX.at(0, y) = gradX.at(1, y);
        gradY.at(0, y) = gradY.at(1, y);
        gradX.at(width-1, y) = gradX.at(width-2, y);
        gradY.at(width-1, y) = gradY.at(width-2, y);
    }
}

void AnisotropicMergeProcessor::integrateStructureTensor(
    const GrayImage& gradX,
    const GrayImage& gradY,
    StructureTensorField& tensorField
) {
    const int width = gradX.width;
    const int height = gradX.height;
    const int halfWin = params_.windowSize / 2;
    const float sigma = params_.integrationSigma;
    const float sigma2 = 2.0f * sigma * sigma;
    
    tensorField.resize(width, height);
    
    // Precompute Gaussian weights
    std::vector<float> gaussWeights((2 * halfWin + 1) * (2 * halfWin + 1));
    float weightSum = 0.0f;
    for (int dy = -halfWin; dy <= halfWin; ++dy) {
        for (int dx = -halfWin; dx <= halfWin; ++dx) {
            float d2 = static_cast<float>(dx * dx + dy * dy);
            float w = std::exp(-d2 / sigma2);
            gaussWeights[(dy + halfWin) * (2 * halfWin + 1) + (dx + halfWin)] = w;
            weightSum += w;
        }
    }
    // Normalize
    for (auto& w : gaussWeights) w /= weightSum;
    
    // Compute structure tensor at each pixel
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            StructureTensor& st = tensorField.at(x, y);
            st.Ixx = st.Ixy = st.Iyy = 0.0f;
            
            // Integrate over window
            for (int dy = -halfWin; dy <= halfWin; ++dy) {
                int sy = clamp(y + dy, 0, height - 1);
                for (int dx = -halfWin; dx <= halfWin; ++dx) {
                    int sx = clamp(x + dx, 0, width - 1);
                    
                    float gx = gradX.at(sx, sy);
                    float gy = gradY.at(sx, sy);
                    float w = gaussWeights[(dy + halfWin) * (2 * halfWin + 1) + (dx + halfWin)];
                    
                    st.Ixx += w * gx * gx;
                    st.Ixy += w * gx * gy;
                    st.Iyy += w * gy * gy;
                }
            }
            
            // Compute eigenvalues and orientation
            st.computeEigen();
        }
    }
}

StructureTensorField AnisotropicMergeProcessor::computeStructureTensors(const GrayImage& input) {
    GrayImage gradX, gradY;
    computeGradients(input, gradX, gradY);
    
    StructureTensorField tensorField;
    integrateStructureTensor(gradX, gradY, tensorField);
    
    return tensorField;
}

float AnisotropicMergeProcessor::applyKernel(
    const GrayImage& image,
    int cx, int cy,
    const AnisotropicKernel& kernel
) {
    const int half = AnisotropicKernel::SIZE / 2;
    float sum = 0.0f;
    float weightSum = 0.0f;
    
    for (int dy = -half; dy <= half; ++dy) {
        int sy = cy + dy;
        if (sy < 0 || sy >= image.height) continue;
        
        for (int dx = -half; dx <= half; ++dx) {
            int sx = cx + dx;
            if (sx < 0 || sx >= image.width) continue;
            
            float w = kernel.weights[dy + half][dx + half];
            sum += image.at(sx, sy) * w;
            weightSum += w;
        }
    }
    
    return (weightSum > 0.0f) ? sum / weightSum : image.at(cx, cy);
}

RGBPixel AnisotropicMergeProcessor::applyKernelRGB(
    const RGBImage& image,
    int cx, int cy,
    const AnisotropicKernel& kernel
) {
    const int half = AnisotropicKernel::SIZE / 2;
    float sumR = 0.0f, sumG = 0.0f, sumB = 0.0f;
    float weightSum = 0.0f;
    
    for (int dy = -half; dy <= half; ++dy) {
        int sy = cy + dy;
        if (sy < 0 || sy >= image.height) continue;
        
        for (int dx = -half; dx <= half; ++dx) {
            int sx = cx + dx;
            if (sx < 0 || sx >= image.width) continue;
            
            float w = kernel.weights[dy + half][dx + half];
            const RGBPixel& p = image.at(sx, sy);
            sumR += p.r * w;
            sumG += p.g * w;
            sumB += p.b * w;
            weightSum += w;
        }
    }
    
    if (weightSum > 0.0f) {
        float invW = 1.0f / weightSum;
        return RGBPixel(sumR * invW, sumG * invW, sumB * invW);
    }
    return image.at(cx, cy);
}

void AnisotropicMergeProcessor::filterGray(const GrayImage& input, GrayImage& output) {
    const int width = input.width;
    const int height = input.height;
    
    // Compute structure tensors
    StructureTensorField tensorField = computeStructureTensors(input);
    
    output.resize(width, height);
    
    // Apply anisotropic filtering
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const StructureTensor& st = tensorField.at(x, y);
            
            // Build kernel based on local structure
            AnisotropicKernel kernel;
            if (st.lambda1 > params_.noiseThreshold && params_.adaptiveStrength) {
                kernel.buildFromStructure(st, params_.kernelSigma, params_.elongation);
            }
            // else: use default isotropic kernel
            
            output.at(x, y) = applyKernel(input, x, y, kernel);
        }
    }
    
    LOGD("AnisotropicMerge: Filtered grayscale %dx%d", width, height);
}

void AnisotropicMergeProcessor::filterRGB(const RGBImage& input, RGBImage& output) {
    const int width = input.width;
    const int height = input.height;
    
    // Convert to grayscale for structure analysis
    GrayImage gray;
    gray.resize(width, height);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const RGBPixel& p = input.at(x, y);
            gray.at(x, y) = 0.299f * p.r + 0.587f * p.g + 0.114f * p.b;
        }
    }
    
    // Compute structure tensors from luminance
    StructureTensorField tensorField = computeStructureTensors(gray);
    
    output.resize(width, height);
    
    // Apply anisotropic filtering to RGB
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const StructureTensor& st = tensorField.at(x, y);
            
            AnisotropicKernel kernel;
            if (st.lambda1 > params_.noiseThreshold && params_.adaptiveStrength) {
                kernel.buildFromStructure(st, params_.kernelSigma, params_.elongation);
            }
            
            output.at(x, y) = applyKernelRGB(input, x, y, kernel);
        }
    }
    
    LOGD("AnisotropicMerge: Filtered RGB %dx%d", width, height);
}

void AnisotropicMergeProcessor::mergeGray(
    const std::vector<GrayImage>& frames,
    int referenceIdx,
    GrayImage& output
) {
    if (frames.empty()) return;
    
    const int width = frames[0].width;
    const int height = frames[0].height;
    const int numFrames = static_cast<int>(frames.size());
    
    // Compute structure tensors from reference frame
    StructureTensorField tensorField = computeStructureTensors(frames[referenceIdx]);
    
    output.resize(width, height);
    
    // Merge frames using anisotropic kernels
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const StructureTensor& st = tensorField.at(x, y);
            
            AnisotropicKernel kernel;
            if (st.lambda1 > params_.noiseThreshold && params_.adaptiveStrength) {
                kernel.buildFromStructure(st, params_.kernelSigma, params_.elongation);
            }
            
            // Apply kernel to each frame and average
            float sum = 0.0f;
            for (int f = 0; f < numFrames; ++f) {
                sum += applyKernel(frames[f], x, y, kernel);
            }
            
            output.at(x, y) = sum / numFrames;
        }
    }
    
    LOGD("AnisotropicMerge: Merged %d grayscale frames %dx%d", numFrames, width, height);
}

void AnisotropicMergeProcessor::mergeRGB(
    const std::vector<RGBImage>& frames,
    int referenceIdx,
    RGBImage& output
) {
    if (frames.empty()) return;
    
    const int width = frames[0].width;
    const int height = frames[0].height;
    const int numFrames = static_cast<int>(frames.size());
    
    // Convert reference to grayscale for structure analysis
    GrayImage gray;
    gray.resize(width, height);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const RGBPixel& p = frames[referenceIdx].at(x, y);
            gray.at(x, y) = 0.299f * p.r + 0.587f * p.g + 0.114f * p.b;
        }
    }
    
    StructureTensorField tensorField = computeStructureTensors(gray);
    
    output.resize(width, height);
    
    // Merge frames using anisotropic kernels
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const StructureTensor& st = tensorField.at(x, y);
            
            AnisotropicKernel kernel;
            if (st.lambda1 > params_.noiseThreshold && params_.adaptiveStrength) {
                kernel.buildFromStructure(st, params_.kernelSigma, params_.elongation);
            }
            
            // Apply kernel to each frame and average
            float sumR = 0.0f, sumG = 0.0f, sumB = 0.0f;
            for (int f = 0; f < numFrames; ++f) {
                RGBPixel p = applyKernelRGB(frames[f], x, y, kernel);
                sumR += p.r;
                sumG += p.g;
                sumB += p.b;
            }
            
            float invN = 1.0f / numFrames;
            output.at(x, y) = RGBPixel(sumR * invN, sumG * invN, sumB * invN);
        }
    }
    
    LOGD("AnisotropicMerge: Merged %d RGB frames %dx%d", numFrames, width, height);
}

} // namespace ultradetail
