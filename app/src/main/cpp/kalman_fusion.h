/**
 * kalman_fusion.h - Gyro-Flow Kalman Fusion
 * 
 * Fuses gyroscope-based motion estimates with optical flow for
 * robust sub-pixel motion estimation.
 * 
 * Key concepts:
 * - Gyro provides high-frequency, low-latency rotation estimates
 * - Optical flow provides accurate but noisy translation estimates
 * - Kalman filter optimally combines both sources
 * - Handles sensor noise and drift compensation
 * 
 * Used by ULTRA preset for maximum alignment accuracy.
 */

#ifndef ULTRADETAIL_KALMAN_FUSION_H
#define ULTRADETAIL_KALMAN_FUSION_H

#include "common.h"
#include <vector>
#include <array>

namespace ultradetail {

/**
 * 2D motion state for Kalman filter
 * State vector: [x, y, vx, vy] (position and velocity)
 */
struct MotionState {
    float x, y;       // Position (pixels)
    float vx, vy;     // Velocity (pixels/frame)
    
    MotionState() : x(0), y(0), vx(0), vy(0) {}
    MotionState(float x_, float y_, float vx_ = 0, float vy_ = 0)
        : x(x_), y(y_), vx(vx_), vy(vy_) {}
};

/**
 * Gyroscope measurement for fusion
 */
struct GyroMeasurement {
    float timestamp;      // Time in seconds
    float rotX, rotY, rotZ;  // Angular velocity (rad/s)
    float dt;             // Time delta from previous sample
    
    GyroMeasurement() : timestamp(0), rotX(0), rotY(0), rotZ(0), dt(0) {}
};

/**
 * Optical flow measurement for fusion
 */
struct FlowMeasurement {
    float dx, dy;         // Flow displacement (pixels)
    float confidence;     // Measurement confidence (0-1)
    
    FlowMeasurement() : dx(0), dy(0), confidence(1.0f) {}
    FlowMeasurement(float dx_, float dy_, float conf = 1.0f)
        : dx(dx_), dy(dy_), confidence(conf) {}
};

/**
 * Kalman fusion parameters
 */
struct KalmanFusionParams {
    // Process noise (motion model uncertainty)
    float processNoisePos = 0.1f;     // Position process noise
    float processNoiseVel = 0.5f;     // Velocity process noise
    
    // Measurement noise
    float gyroNoise = 0.05f;          // Gyro measurement noise
    float flowNoise = 0.5f;           // Optical flow measurement noise
    
    // Sensor parameters
    float focalLengthPx = 3000.0f;    // Focal length in pixels
    float gyroScale = 1.0f;           // Gyro calibration scale
    
    // Fusion weights
    float gyroWeight = 0.7f;          // Weight for gyro (vs flow)
    float flowWeight = 0.3f;          // Weight for optical flow
    
    // Outlier rejection
    float outlierThreshold = 5.0f;    // Mahalanobis distance threshold
    bool enableOutlierRejection = true;
};

/**
 * Kalman filter state (4x4 covariance matrix)
 */
struct KalmanState {
    MotionState state;
    std::array<float, 16> P;  // 4x4 covariance matrix (row-major)
    
    KalmanState() {
        // Initialize covariance with high uncertainty
        P.fill(0);
        P[0] = P[5] = 10.0f;   // Position variance
        P[10] = P[15] = 1.0f;  // Velocity variance
    }
};

/**
 * Fusion result for a frame pair
 */
struct FusionResult {
    MotionState motion;           // Estimated motion
    float uncertainty;            // Motion uncertainty (pixels)
    float gyroContribution;       // How much gyro contributed (0-1)
    float flowContribution;       // How much flow contributed (0-1)
    bool outlierDetected;         // Whether outlier was detected
    
    FusionResult() : uncertainty(0), gyroContribution(0), 
                     flowContribution(0), outlierDetected(false) {}
};

/**
 * Gyro-Flow Kalman Fusion Processor
 * 
 * Combines gyroscope and optical flow measurements using
 * an Extended Kalman Filter for optimal motion estimation.
 */
class KalmanFusionProcessor {
public:
    explicit KalmanFusionProcessor(const KalmanFusionParams& params = KalmanFusionParams());
    
    /**
     * Reset the filter state
     */
    void reset();
    
    /**
     * Predict step using gyroscope data
     * 
     * @param gyro Gyroscope measurement
     * @return Predicted motion state
     */
    MotionState predict(const GyroMeasurement& gyro);
    
    /**
     * Update step using optical flow measurement
     * 
     * @param flow Optical flow measurement
     * @return Updated motion state
     */
    MotionState update(const FlowMeasurement& flow);
    
    /**
     * Fuse gyro and flow measurements for a frame pair
     * 
     * @param gyroSamples Gyroscope samples during frame interval
     * @param flow Optical flow measurement between frames
     * @return Fused motion estimate
     */
    FusionResult fuse(
        const std::vector<GyroMeasurement>& gyroSamples,
        const FlowMeasurement& flow
    );
    
    /**
     * Batch process multiple frame pairs
     * 
     * @param allGyroSamples Gyro samples for each frame interval
     * @param flows Optical flow for each frame pair
     * @return Fused motion estimates
     */
    std::vector<FusionResult> fuseBatch(
        const std::vector<std::vector<GyroMeasurement>>& allGyroSamples,
        const std::vector<FlowMeasurement>& flows
    );
    
    /**
     * Convert gyro rotation to pixel displacement
     */
    FlowMeasurement gyroToPixels(
        const std::vector<GyroMeasurement>& gyroSamples
    );
    
    /**
     * Get current filter state
     */
    const KalmanState& getState() const { return kalmanState_; }
    
    /**
     * Update parameters
     */
    void setParams(const KalmanFusionParams& params) { params_ = params; }
    const KalmanFusionParams& getParams() const { return params_; }

private:
    KalmanFusionParams params_;
    KalmanState kalmanState_;
    
    /**
     * Matrix operations for Kalman filter
     */
    void matMul4x4(const float* A, const float* B, float* C);
    void matAdd4x4(const float* A, const float* B, float* C);
    void matTranspose4x4(const float* A, float* At);
    
    /**
     * Compute Mahalanobis distance for outlier detection
     */
    float mahalanobisDistance(float dx, float dy, float variance);
};

} // namespace ultradetail

#endif // ULTRADETAIL_KALMAN_FUSION_H
