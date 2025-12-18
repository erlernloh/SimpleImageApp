/**
 * rolling_shutter.h - Rolling Shutter Correction
 * 
 * Corrects rolling shutter distortion in CMOS sensor images.
 * 
 * Rolling shutter causes:
 * - Skew/wobble during horizontal motion
 * - Jello effect during vibration
 * - Partial exposure differences between rows
 * 
 * Uses gyroscope data to model per-row camera motion and
 * applies inverse warping to correct distortion.
 * 
 * Used by ULTRA preset for improved alignment accuracy.
 */

#ifndef ULTRADETAIL_ROLLING_SHUTTER_H
#define ULTRADETAIL_ROLLING_SHUTTER_H

#include "common.h"
#include <vector>

namespace ultradetail {

/**
 * Gyroscope sample for rolling shutter correction
 */
struct GyroSampleRS {
    float timestamp;      // Time in seconds (relative to frame start)
    float rotX, rotY, rotZ;  // Angular velocity (rad/s)
    
    GyroSampleRS() : timestamp(0), rotX(0), rotY(0), rotZ(0) {}
    GyroSampleRS(float t, float rx, float ry, float rz)
        : timestamp(t), rotX(rx), rotY(ry), rotZ(rz) {}
};

/**
 * Rolling shutter correction parameters
 */
struct RollingShutterParams {
    float readoutTimeMs = 33.0f;      // Time to read full frame (ms)
    float focalLengthPx = 3000.0f;    // Focal length in pixels
    int interpolationOrder = 1;        // 0=nearest, 1=bilinear, 2=bicubic
    bool correctRotation = true;       // Correct rotational motion
    bool correctTranslation = false;   // Correct translational motion (requires depth)
    float smoothingFactor = 0.5f;      // Temporal smoothing for gyro data
};

/**
 * Per-row motion model
 */
struct RowMotion {
    float dx, dy;         // Translation at this row
    float angle;          // Rotation at this row
    
    RowMotion() : dx(0), dy(0), angle(0) {}
};

/**
 * Rolling shutter correction result
 */
struct RSCorrectionResult {
    RGBImage corrected;
    float maxDisplacement;    // Maximum pixel displacement applied
    float avgDisplacement;    // Average displacement
    bool success;
    
    RSCorrectionResult() : maxDisplacement(0), avgDisplacement(0), success(false) {}
};

/**
 * Rolling Shutter Corrector
 * 
 * Uses gyroscope data to model and correct rolling shutter distortion.
 */
class RollingShutterCorrector {
public:
    explicit RollingShutterCorrector(const RollingShutterParams& params = RollingShutterParams());
    
    /**
     * Correct rolling shutter distortion in an RGB image
     * 
     * @param input Input image with rolling shutter distortion
     * @param gyroSamples Gyroscope samples during frame exposure
     * @param exposureStartTime Start time of exposure (for gyro alignment)
     * @return Corrected image
     */
    RSCorrectionResult correct(
        const RGBImage& input,
        const std::vector<GyroSampleRS>& gyroSamples,
        float exposureStartTime = 0.0f
    );
    
    /**
     * Correct grayscale image
     */
    RSCorrectionResult correctGray(
        const GrayImage& input,
        const std::vector<GyroSampleRS>& gyroSamples,
        float exposureStartTime = 0.0f
    );
    
    /**
     * Compute per-row motion model from gyro data
     * 
     * @param height Image height (number of rows)
     * @param gyroSamples Gyroscope samples
     * @param exposureStartTime Start time offset
     * @return Motion model for each row
     */
    std::vector<RowMotion> computeRowMotion(
        int height,
        const std::vector<GyroSampleRS>& gyroSamples,
        float exposureStartTime = 0.0f
    );
    
    /**
     * Update parameters
     */
    void setParams(const RollingShutterParams& params) { params_ = params; }
    const RollingShutterParams& getParams() const { return params_; }

private:
    RollingShutterParams params_;
    
    /**
     * Interpolate gyro rotation at a given time
     */
    void interpolateGyro(
        const std::vector<GyroSampleRS>& samples,
        float time,
        float& rotX, float& rotY, float& rotZ
    );
    
    /**
     * Apply bilinear interpolation for warping
     */
    RGBPixel sampleBilinear(const RGBImage& image, float x, float y);
    float sampleBilinearGray(const GrayImage& image, float x, float y);
};

} // namespace ultradetail

#endif // ULTRADETAIL_ROLLING_SHUTTER_H
