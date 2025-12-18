/**
 * rolling_shutter.cpp - Rolling Shutter Correction Implementation
 * 
 * Corrects per-row distortion using gyroscope motion data.
 */

#include "rolling_shutter.h"
#include "neon_utils.h"
#include <cmath>
#include <algorithm>

namespace ultradetail {

RollingShutterCorrector::RollingShutterCorrector(const RollingShutterParams& params)
    : params_(params) {
}

void RollingShutterCorrector::interpolateGyro(
    const std::vector<GyroSampleRS>& samples,
    float time,
    float& rotX, float& rotY, float& rotZ
) {
    if (samples.empty()) {
        rotX = rotY = rotZ = 0;
        return;
    }
    
    if (samples.size() == 1 || time <= samples.front().timestamp) {
        rotX = samples.front().rotX;
        rotY = samples.front().rotY;
        rotZ = samples.front().rotZ;
        return;
    }
    
    if (time >= samples.back().timestamp) {
        rotX = samples.back().rotX;
        rotY = samples.back().rotY;
        rotZ = samples.back().rotZ;
        return;
    }
    
    // Find bracketing samples
    size_t i = 0;
    while (i < samples.size() - 1 && samples[i + 1].timestamp < time) {
        ++i;
    }
    
    const GyroSampleRS& s0 = samples[i];
    const GyroSampleRS& s1 = samples[i + 1];
    
    float dt = s1.timestamp - s0.timestamp;
    float t = (dt > 1e-6f) ? (time - s0.timestamp) / dt : 0.0f;
    t = clamp(t, 0.0f, 1.0f);
    
    // Apply smoothing
    float smooth = params_.smoothingFactor;
    t = t * (1.0f - smooth) + 0.5f * smooth;
    
    rotX = s0.rotX + t * (s1.rotX - s0.rotX);
    rotY = s0.rotY + t * (s1.rotY - s0.rotY);
    rotZ = s0.rotZ + t * (s1.rotZ - s0.rotZ);
}

std::vector<RowMotion> RollingShutterCorrector::computeRowMotion(
    int height,
    const std::vector<GyroSampleRS>& gyroSamples,
    float exposureStartTime
) {
    std::vector<RowMotion> motion(height);
    
    if (gyroSamples.empty()) {
        return motion;
    }
    
    float readoutTimeSec = params_.readoutTimeMs / 1000.0f;
    float rowTime = readoutTimeSec / height;
    
    // Reference row (middle of frame)
    int refRow = height / 2;
    float refTime = exposureStartTime + refRow * rowTime;
    
    float refRotX, refRotY, refRotZ;
    interpolateGyro(gyroSamples, refTime, refRotX, refRotY, refRotZ);
    
    // Compute motion for each row relative to reference
    for (int row = 0; row < height; ++row) {
        float rowTimestamp = exposureStartTime + row * rowTime;
        
        float rotX, rotY, rotZ;
        interpolateGyro(gyroSamples, rowTimestamp, rotX, rotY, rotZ);
        
        // Relative rotation since reference row
        float deltaRotX = rotX - refRotX;
        float deltaRotY = rotY - refRotY;
        float deltaRotZ = rotZ - refRotZ;
        
        // Time difference from reference
        float dt = (row - refRow) * rowTime;
        
        // Integrate angular velocity to get angle
        float angleX = deltaRotX * dt;
        float angleY = deltaRotY * dt;
        float angleZ = deltaRotZ * dt;
        
        // Convert rotation to pixel displacement
        // For small angles: dx ≈ angleY * focalLength, dy ≈ -angleX * focalLength
        if (params_.correctRotation) {
            motion[row].dx = angleY * params_.focalLengthPx;
            motion[row].dy = -angleX * params_.focalLengthPx;
            motion[row].angle = angleZ;
        }
    }
    
    return motion;
}

RGBPixel RollingShutterCorrector::sampleBilinear(const RGBImage& image, float x, float y) {
    int x0 = static_cast<int>(std::floor(x));
    int y0 = static_cast<int>(std::floor(y));
    int x1 = x0 + 1;
    int y1 = y0 + 1;
    
    // Clamp to image bounds
    x0 = clamp(x0, 0, image.width - 1);
    x1 = clamp(x1, 0, image.width - 1);
    y0 = clamp(y0, 0, image.height - 1);
    y1 = clamp(y1, 0, image.height - 1);
    
    float fx = x - std::floor(x);
    float fy = y - std::floor(y);
    
    const RGBPixel& p00 = image.at(x0, y0);
    const RGBPixel& p10 = image.at(x1, y0);
    const RGBPixel& p01 = image.at(x0, y1);
    const RGBPixel& p11 = image.at(x1, y1);
    
    float w00 = (1 - fx) * (1 - fy);
    float w10 = fx * (1 - fy);
    float w01 = (1 - fx) * fy;
    float w11 = fx * fy;
    
    return RGBPixel(
        p00.r * w00 + p10.r * w10 + p01.r * w01 + p11.r * w11,
        p00.g * w00 + p10.g * w10 + p01.g * w01 + p11.g * w11,
        p00.b * w00 + p10.b * w10 + p01.b * w01 + p11.b * w11
    );
}

float RollingShutterCorrector::sampleBilinearGray(const GrayImage& image, float x, float y) {
    int x0 = static_cast<int>(std::floor(x));
    int y0 = static_cast<int>(std::floor(y));
    int x1 = x0 + 1;
    int y1 = y0 + 1;
    
    x0 = clamp(x0, 0, image.width - 1);
    x1 = clamp(x1, 0, image.width - 1);
    y0 = clamp(y0, 0, image.height - 1);
    y1 = clamp(y1, 0, image.height - 1);
    
    float fx = x - std::floor(x);
    float fy = y - std::floor(y);
    
    float p00 = image.at(x0, y0);
    float p10 = image.at(x1, y0);
    float p01 = image.at(x0, y1);
    float p11 = image.at(x1, y1);
    
    return p00 * (1-fx) * (1-fy) + p10 * fx * (1-fy) +
           p01 * (1-fx) * fy + p11 * fx * fy;
}

RSCorrectionResult RollingShutterCorrector::correct(
    const RGBImage& input,
    const std::vector<GyroSampleRS>& gyroSamples,
    float exposureStartTime
) {
    RSCorrectionResult result;
    
    const int width = input.width;
    const int height = input.height;
    
    if (width == 0 || height == 0) {
        LOGE("RS: Invalid input image");
        return result;
    }
    
    // Compute per-row motion
    std::vector<RowMotion> rowMotion = computeRowMotion(height, gyroSamples, exposureStartTime);
    
    result.corrected.resize(width, height);
    
    float maxDisp = 0;
    float sumDisp = 0;
    int dispCount = 0;
    
    // Apply inverse warp
    for (int y = 0; y < height; ++y) {
        const RowMotion& motion = rowMotion[y];
        
        // Center of rotation for this row
        float cx = width / 2.0f;
        float cy = static_cast<float>(y);
        
        float cosA = std::cos(-motion.angle);
        float sinA = std::sin(-motion.angle);
        
        for (int x = 0; x < width; ++x) {
            // Apply inverse transformation to find source pixel
            float srcX, srcY;
            
            if (params_.correctRotation && std::abs(motion.angle) > 1e-6f) {
                // Rotate around row center
                float rx = x - cx;
                float ry = 0;  // Rotation is in-plane
                
                float rotX = rx * cosA - ry * sinA;
                
                srcX = cx + rotX - motion.dx;
                srcY = y - motion.dy;
            } else {
                // Translation only
                srcX = x - motion.dx;
                srcY = y - motion.dy;
            }
            
            // Track displacement
            float disp = std::sqrt((srcX - x) * (srcX - x) + (srcY - y) * (srcY - y));
            maxDisp = std::max(maxDisp, disp);
            sumDisp += disp;
            dispCount++;
            
            // Sample with bilinear interpolation
            if (srcX >= 0 && srcX < width - 1 && srcY >= 0 && srcY < height - 1) {
                result.corrected.at(x, y) = sampleBilinear(input, srcX, srcY);
            } else {
                // Out of bounds - use nearest edge pixel
                int clampX = clamp(static_cast<int>(srcX), 0, width - 1);
                int clampY = clamp(static_cast<int>(srcY), 0, height - 1);
                result.corrected.at(x, y) = input.at(clampX, clampY);
            }
        }
    }
    
    result.maxDisplacement = maxDisp;
    result.avgDisplacement = dispCount > 0 ? sumDisp / dispCount : 0;
    result.success = true;
    
    LOGD("RS: Corrected %dx%d, max_disp=%.2f, avg_disp=%.2f",
         width, height, maxDisp, result.avgDisplacement);
    
    return result;
}

RSCorrectionResult RollingShutterCorrector::correctGray(
    const GrayImage& input,
    const std::vector<GyroSampleRS>& gyroSamples,
    float exposureStartTime
) {
    RSCorrectionResult result;
    
    const int width = input.width;
    const int height = input.height;
    
    if (width == 0 || height == 0) {
        return result;
    }
    
    std::vector<RowMotion> rowMotion = computeRowMotion(height, gyroSamples, exposureStartTime);
    
    // Create grayscale output, convert to RGB for result
    GrayImage correctedGray;
    correctedGray.resize(width, height);
    
    float maxDisp = 0;
    float sumDisp = 0;
    
    for (int y = 0; y < height; ++y) {
        const RowMotion& motion = rowMotion[y];
        float cx = width / 2.0f;
        float cosA = std::cos(-motion.angle);
        float sinA = std::sin(-motion.angle);
        
        for (int x = 0; x < width; ++x) {
            float srcX, srcY;
            
            if (params_.correctRotation && std::abs(motion.angle) > 1e-6f) {
                float rx = x - cx;
                float rotX = rx * cosA;
                srcX = cx + rotX - motion.dx;
                srcY = y - motion.dy;
            } else {
                srcX = x - motion.dx;
                srcY = y - motion.dy;
            }
            
            float disp = std::sqrt((srcX - x) * (srcX - x) + (srcY - y) * (srcY - y));
            maxDisp = std::max(maxDisp, disp);
            sumDisp += disp;
            
            if (srcX >= 0 && srcX < width - 1 && srcY >= 0 && srcY < height - 1) {
                correctedGray.at(x, y) = sampleBilinearGray(input, srcX, srcY);
            } else {
                int clampX = clamp(static_cast<int>(srcX), 0, width - 1);
                int clampY = clamp(static_cast<int>(srcY), 0, height - 1);
                correctedGray.at(x, y) = input.at(clampX, clampY);
            }
        }
    }
    
    // Convert to RGB
    result.corrected.resize(width, height);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float v = correctedGray.at(x, y);
            result.corrected.at(x, y) = RGBPixel(v, v, v);
        }
    }
    
    result.maxDisplacement = maxDisp;
    result.avgDisplacement = sumDisp / (width * height);
    result.success = true;
    
    return result;
}

} // namespace ultradetail
