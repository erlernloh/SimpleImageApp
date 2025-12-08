/**
 * optical_flow.cpp - Dense optical flow implementation
 * 
 * Hierarchical Lucas-Kanade optical flow with NEON optimization.
 */

#include "optical_flow.h"
#include "neon_utils.h"
#include <cmath>
#include <algorithm>

namespace ultradetail {

// Scharr kernel coefficients (more accurate than Sobel)
static constexpr float SCHARR_X[3][3] = {
    {-3, 0, 3},
    {-10, 0, 10},
    {-3, 0, 3}
};

static constexpr float SCHARR_Y[3][3] = {
    {-3, -10, -3},
    {0, 0, 0},
    {3, 10, 3}
};

DenseOpticalFlow::DenseOpticalFlow(const OpticalFlowParams& params)
    : params_(params)
    , imageWidth_(0)
    , imageHeight_(0) {
}

void DenseOpticalFlow::setReference(const GrayImage& reference) {
    imageWidth_ = reference.width;
    imageHeight_ = reference.height;
    
    // Build reference pyramid
    refPyramid_.build(reference, params_.pyramidLevels);
    
    // Precompute gradients for each pyramid level
    computeGradients(reference, refGradX_, refGradY_);
    
    LOGD("DenseOpticalFlow: Reference set %dx%d, %d pyramid levels",
         imageWidth_, imageHeight_, params_.pyramidLevels);
}

void DenseOpticalFlow::computeGradients(const GrayImage& image, 
                                        GrayImage& gradX, 
                                        GrayImage& gradY) {
    int width = image.width;
    int height = image.height;
    
    gradX = GrayImage(width, height);
    gradY = GrayImage(width, height);
    
    // Apply Scharr operator
    for (int y = 1; y < height - 1; ++y) {
        float* gxRow = gradX.row(y);
        float* gyRow = gradY.row(y);
        
        for (int x = 1; x < width - 1; ++x) {
            float gx = 0, gy = 0;
            
            for (int ky = -1; ky <= 1; ++ky) {
                for (int kx = -1; kx <= 1; ++kx) {
                    float val = image.at(x + kx, y + ky);
                    gx += val * SCHARR_X[ky + 1][kx + 1];
                    gy += val * SCHARR_Y[ky + 1][kx + 1];
                }
            }
            
            // Normalize by kernel sum (32)
            gxRow[x] = gx / 32.0f;
            gyRow[x] = gy / 32.0f;
        }
    }
    
    // Zero borders
    for (int x = 0; x < width; ++x) {
        gradX.at(x, 0) = 0; gradX.at(x, height - 1) = 0;
        gradY.at(x, 0) = 0; gradY.at(x, height - 1) = 0;
    }
    for (int y = 0; y < height; ++y) {
        gradX.at(0, y) = 0; gradX.at(width - 1, y) = 0;
        gradY.at(0, y) = 0; gradY.at(width - 1, y) = 0;
    }
}

float DenseOpticalFlow::sampleBilinear(const GrayImage& image, float x, float y) {
    // Clamp to image bounds
    x = clamp(x, 0.0f, static_cast<float>(image.width - 1));
    y = clamp(y, 0.0f, static_cast<float>(image.height - 1));
    
    int x0 = static_cast<int>(x);
    int y0 = static_cast<int>(y);
    int x1 = std::min(x0 + 1, image.width - 1);
    int y1 = std::min(y0 + 1, image.height - 1);
    
    float fx = x - x0;
    float fy = y - y0;
    
    float v00 = image.at(x0, y0);
    float v10 = image.at(x1, y0);
    float v01 = image.at(x0, y1);
    float v11 = image.at(x1, y1);
    
    return (1 - fx) * (1 - fy) * v00 +
           fx * (1 - fy) * v10 +
           (1 - fx) * fy * v01 +
           fx * fy * v11;
}

FlowVector DenseOpticalFlow::computePixelFlow(
    const GrayImage& ref,
    const GrayImage& target,
    const GrayImage& gradX,
    const GrayImage& gradY,
    int x, int y,
    const FlowVector& initialFlow
) {
    int halfWin = params_.windowSize / 2;
    
    // Start with initial flow estimate
    float flowX = initialFlow.dx;
    float flowY = initialFlow.dy;
    
    // Variables to track final structure tensor for confidence computation
    float finalSumIxIx = 0, finalSumIyIy = 0, finalSumIxIy = 0;
    float finalMinEigen = 0;
    
    // Iterative Lucas-Kanade refinement
    for (int iter = 0; iter < params_.maxIterations; ++iter) {
        // Compute structure tensor and image difference
        float sumIxIx = 0, sumIxIy = 0, sumIyIy = 0;
        float sumIxIt = 0, sumIyIt = 0;
        int validPixels = 0;
        
        for (int wy = -halfWin; wy <= halfWin; ++wy) {
            int py = y + wy;
            if (py < 1 || py >= ref.height - 1) continue;
            
            for (int wx = -halfWin; wx <= halfWin; ++wx) {
                int px = x + wx;
                if (px < 1 || px >= ref.width - 1) continue;
                
                // Sample target at current flow position
                float targetX = px + flowX;
                float targetY = py + flowY;
                
                if (targetX < 0 || targetX >= target.width - 1 ||
                    targetY < 0 || targetY >= target.height - 1) {
                    continue;
                }
                
                float Ix = gradX.at(px, py);
                float Iy = gradY.at(px, py);
                float It = sampleBilinear(target, targetX, targetY) - ref.at(px, py);
                
                // Accumulate structure tensor
                sumIxIx += Ix * Ix;
                sumIxIy += Ix * Iy;
                sumIyIy += Iy * Iy;
                sumIxIt += Ix * It;
                sumIyIt += Iy * It;
                validPixels++;
            }
        }
        
        if (validPixels < halfWin * halfWin / 4) {
            // Not enough valid pixels
            return FlowVector(flowX, flowY, 0.0f);
        }
        
        // Store for confidence computation
        finalSumIxIx = sumIxIx;
        finalSumIyIy = sumIyIy;
        finalSumIxIy = sumIxIy;
        
        // Solve 2x2 linear system: [Ix*Ix  Ix*Iy] [du]   [-Ix*It]
        //                          [Ix*Iy  Iy*Iy] [dv] = [-Iy*It]
        float det = sumIxIx * sumIyIy - sumIxIy * sumIxIy;
        
        // Check for singular matrix (flat region or aperture problem)
        float discriminant = (sumIxIx - sumIyIy) * (sumIxIx - sumIyIy) + 4 * sumIxIy * sumIxIy;
        float minEigen = 0.5f * (sumIxIx + sumIyIy - std::sqrt(std::max(0.0f, discriminant)));
        finalMinEigen = minEigen;
        
        if (std::abs(det) < 1e-6f || minEigen < params_.minEigenThreshold) {
            // Unreliable flow
            return FlowVector(flowX, flowY, 0.1f);
        }
        
        float invDet = 1.0f / det;
        float du = invDet * (sumIyIy * (-sumIxIt) - sumIxIy * (-sumIyIt));
        float dv = invDet * (sumIxIx * (-sumIyIt) - sumIxIy * (-sumIxIt));
        
        // Update flow
        flowX += du;
        flowY += dv;
        
        // Check convergence
        if (std::abs(du) < params_.convergenceThreshold && 
            std::abs(dv) < params_.convergenceThreshold) {
            break;
        }
    }
    
    // Compute confidence based on eigenvalue ratio
    float trace = finalSumIxIx + finalSumIyIy;
    float confidence = (trace > 0) ? std::min(1.0f, finalMinEigen / (trace * 0.1f)) : 0.0f;
    
    return FlowVector(flowX, flowY, confidence);
}

void DenseOpticalFlow::refineFlowLevel(
    const GrayImage& ref,
    const GrayImage& target,
    FlowField& flow,
    int level
) {
    // Compute gradients for this level
    GrayImage gradX, gradY;
    computeGradients(ref, gradX, gradY);
    
    int width = flow.width;
    int height = flow.height;
    
    // Refine flow for each pixel
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            FlowVector& currentFlow = flow.at(x, y);
            
            // Compute refined flow
            FlowVector refined = computePixelFlow(
                ref, target, gradX, gradY,
                x, y, currentFlow
            );
            
            // Update if valid
            if (refined.confidence > 0.1f) {
                currentFlow = refined;
            }
        }
    }
}

void DenseOpticalFlow::upsampleFlow(const FlowField& coarse, FlowField& fine) {
    int fineWidth = fine.width;
    int fineHeight = fine.height;
    int coarseWidth = coarse.width;
    int coarseHeight = coarse.height;
    
    float scaleX = static_cast<float>(coarseWidth) / fineWidth;
    float scaleY = static_cast<float>(coarseHeight) / fineHeight;
    
    for (int y = 0; y < fineHeight; ++y) {
        for (int x = 0; x < fineWidth; ++x) {
            // Map to coarse coordinates
            float cx = x * scaleX;
            float cy = y * scaleY;
            
            int cx0 = clamp(static_cast<int>(cx), 0, coarseWidth - 1);
            int cy0 = clamp(static_cast<int>(cy), 0, coarseHeight - 1);
            int cx1 = clamp(cx0 + 1, 0, coarseWidth - 1);
            int cy1 = clamp(cy0 + 1, 0, coarseHeight - 1);
            
            float fx = cx - cx0;
            float fy = cy - cy0;
            
            // Bilinear interpolation of flow
            const FlowVector& f00 = coarse.at(cx0, cy0);
            const FlowVector& f10 = coarse.at(cx1, cy0);
            const FlowVector& f01 = coarse.at(cx0, cy1);
            const FlowVector& f11 = coarse.at(cx1, cy1);
            
            float dx = (1 - fx) * (1 - fy) * f00.dx +
                       fx * (1 - fy) * f10.dx +
                       (1 - fx) * fy * f01.dx +
                       fx * fy * f11.dx;
            
            float dy = (1 - fx) * (1 - fy) * f00.dy +
                       fx * (1 - fy) * f10.dy +
                       (1 - fx) * fy * f01.dy +
                       fx * fy * f11.dy;
            
            float conf = (1 - fx) * (1 - fy) * f00.confidence +
                         fx * (1 - fy) * f10.confidence +
                         (1 - fx) * fy * f01.confidence +
                         fx * fy * f11.confidence;
            
            // Scale flow by 2 (pyramid upsampling)
            fine.at(x, y) = FlowVector(dx * 2.0f, dy * 2.0f, conf);
        }
    }
}

DenseFlowResult DenseOpticalFlow::computeFlow(
    const GrayImage& target,
    const GyroHomography& gyroInit
) {
    DenseFlowResult result;
    
    if (refPyramid_.numLevels() == 0) {
        LOGE("DenseOpticalFlow: Reference not set");
        return result;
    }
    
    // Build target pyramid
    GaussianPyramid targetPyramid;
    targetPyramid.build(target, params_.pyramidLevels);
    
    // Start from coarsest level
    int numLevels = refPyramid_.numLevels();
    
    // Initialize flow at coarsest level
    const GrayImage& coarsestRef = refPyramid_.getLevel(numLevels - 1);
    FlowField currentFlow(coarsestRef.width, coarsestRef.height);
    
    // Initialize with gyro if available
    if (gyroInit.isValid && params_.useGyroInit) {
        float scale = 1.0f / (1 << (numLevels - 1));
        for (int y = 0; y < currentFlow.height; ++y) {
            for (int x = 0; x < currentFlow.width; ++x) {
                // Map to full resolution and get gyro flow
                float fullX = x / scale;
                float fullY = y / scale;
                FlowVector gyroFlow = gyroInit.getInitialFlow(fullX, fullY);
                // Scale flow to current pyramid level
                currentFlow.at(x, y) = FlowVector(gyroFlow.dx * scale, gyroFlow.dy * scale, 1.0f);
            }
        }
        LOGD("DenseOpticalFlow: Initialized with gyro homography");
    }
    
    // Coarse-to-fine refinement
    for (int level = numLevels - 1; level >= 0; --level) {
        const GrayImage& refLevel = refPyramid_.getLevel(level);
        const GrayImage& targetLevel = targetPyramid.getLevel(level);
        
        // Upsample flow if not at coarsest level
        if (level < numLevels - 1) {
            FlowField upsampled(refLevel.width, refLevel.height);
            upsampleFlow(currentFlow, upsampled);
            currentFlow = std::move(upsampled);
        }
        
        // Refine flow at this level
        refineFlowLevel(refLevel, targetLevel, currentFlow, level);
        
        LOGD_DEBUG("DenseOpticalFlow: Level %d (%dx%d) refined", 
                   level, refLevel.width, refLevel.height);
    }
    
    // Compute statistics
    float totalFlow = 0;
    int validCount = 0;
    
    for (int y = 0; y < currentFlow.height; ++y) {
        for (int x = 0; x < currentFlow.width; ++x) {
            const FlowVector& f = currentFlow.at(x, y);
            if (f.confidence > 0.3f) {
                totalFlow += f.magnitude();
                validCount++;
            }
        }
    }
    
    result.flowField = std::move(currentFlow);
    result.averageFlow = (validCount > 0) ? totalFlow / validCount : 0;
    result.coverage = static_cast<float>(validCount) / (imageWidth_ * imageHeight_);
    result.isValid = result.coverage > 0.5f;
    
    LOGI("DenseOpticalFlow: avgFlow=%.2f px, coverage=%.1f%%, valid=%s",
         result.averageFlow, result.coverage * 100.0f,
         result.isValid ? "yes" : "no");
    
    return result;
}

void DenseOpticalFlow::warpImage(const RGBImage& input, const FlowField& flow, RGBImage& output) {
    int width = input.width;
    int height = input.height;
    
    output = RGBImage(width, height);
    
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            // Get flow at this pixel (may need to scale if flow is at different resolution)
            int fx = clamp(x * flow.width / width, 0, flow.width - 1);
            int fy = clamp(y * flow.height / height, 0, flow.height - 1);
            const FlowVector& f = flow.at(fx, fy);
            
            // Source position
            float srcX = x + f.dx;
            float srcY = y + f.dy;
            
            // Bilinear interpolation
            if (srcX >= 0 && srcX < width - 1 && srcY >= 0 && srcY < height - 1) {
                int x0 = static_cast<int>(srcX);
                int y0 = static_cast<int>(srcY);
                int x1 = x0 + 1;
                int y1 = y0 + 1;
                
                float fx = srcX - x0;
                float fy = srcY - y0;
                
                const RGBPixel& p00 = input.at(x0, y0);
                const RGBPixel& p10 = input.at(x1, y0);
                const RGBPixel& p01 = input.at(x0, y1);
                const RGBPixel& p11 = input.at(x1, y1);
                
                output.at(x, y) = RGBPixel(
                    (1-fx)*(1-fy)*p00.r + fx*(1-fy)*p10.r + (1-fx)*fy*p01.r + fx*fy*p11.r,
                    (1-fx)*(1-fy)*p00.g + fx*(1-fy)*p10.g + (1-fx)*fy*p01.g + fx*fy*p11.g,
                    (1-fx)*(1-fy)*p00.b + fx*(1-fy)*p10.b + (1-fx)*fy*p01.b + fx*fy*p11.b
                );
            } else {
                // Out of bounds - use nearest
                int nx = clamp(static_cast<int>(srcX + 0.5f), 0, width - 1);
                int ny = clamp(static_cast<int>(srcY + 0.5f), 0, height - 1);
                output.at(x, y) = input.at(nx, ny);
            }
        }
    }
}

MotionField DenseOpticalFlow::flowToMotionField(const FlowField& flow, int tileSize) {
    int numTilesX = (flow.width + tileSize - 1) / tileSize;
    int numTilesY = (flow.height + tileSize - 1) / tileSize;
    
    MotionField motionField(numTilesX, numTilesY);
    
    for (int ty = 0; ty < numTilesY; ++ty) {
        for (int tx = 0; tx < numTilesX; ++tx) {
            // Average flow within tile
            float sumDx = 0, sumDy = 0;
            float sumConf = 0;
            int count = 0;
            
            int startX = tx * tileSize;
            int startY = ty * tileSize;
            int endX = std::min(startX + tileSize, flow.width);
            int endY = std::min(startY + tileSize, flow.height);
            
            for (int y = startY; y < endY; ++y) {
                for (int x = startX; x < endX; ++x) {
                    const FlowVector& f = flow.at(x, y);
                    if (f.confidence > 0.3f) {
                        sumDx += f.dx;
                        sumDy += f.dy;
                        sumConf += f.confidence;
                        count++;
                    }
                }
            }
            
            if (count > 0) {
                motionField.at(tx, ty) = MotionVector(
                    static_cast<int>(sumDx / count + 0.5f),
                    static_cast<int>(sumDy / count + 0.5f),
                    sumConf / count
                );
            }
        }
    }
    
    return motionField;
}

} // namespace ultradetail
