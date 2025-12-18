/**
 * kalman_fusion.cpp - Gyro-Flow Kalman Fusion Implementation
 * 
 * Implements Extended Kalman Filter for fusing gyroscope and optical flow.
 */

#include "kalman_fusion.h"
#include "neon_utils.h"
#include <cmath>
#include <algorithm>

namespace ultradetail {

KalmanFusionProcessor::KalmanFusionProcessor(const KalmanFusionParams& params)
    : params_(params) {
    reset();
}

void KalmanFusionProcessor::reset() {
    kalmanState_ = KalmanState();
}

void KalmanFusionProcessor::matMul4x4(const float* A, const float* B, float* C) {
    for (int i = 0; i < 4; ++i) {
        for (int j = 0; j < 4; ++j) {
            float sum = 0;
            for (int k = 0; k < 4; ++k) {
                sum += A[i * 4 + k] * B[k * 4 + j];
            }
            C[i * 4 + j] = sum;
        }
    }
}

void KalmanFusionProcessor::matAdd4x4(const float* A, const float* B, float* C) {
    for (int i = 0; i < 16; ++i) {
        C[i] = A[i] + B[i];
    }
}

void KalmanFusionProcessor::matTranspose4x4(const float* A, float* At) {
    for (int i = 0; i < 4; ++i) {
        for (int j = 0; j < 4; ++j) {
            At[j * 4 + i] = A[i * 4 + j];
        }
    }
}

float KalmanFusionProcessor::mahalanobisDistance(float dx, float dy, float variance) {
    if (variance < 1e-6f) variance = 1e-6f;
    return std::sqrt((dx * dx + dy * dy) / variance);
}

FlowMeasurement KalmanFusionProcessor::gyroToPixels(
    const std::vector<GyroMeasurement>& gyroSamples
) {
    if (gyroSamples.empty()) {
        return FlowMeasurement(0, 0, 0);
    }
    
    // Integrate angular velocity to get total rotation
    float totalRotX = 0, totalRotY = 0;
    float totalTime = 0;
    
    for (const auto& sample : gyroSamples) {
        float dt = sample.dt > 0 ? sample.dt : 0.001f;
        totalRotX += sample.rotX * dt * params_.gyroScale;
        totalRotY += sample.rotY * dt * params_.gyroScale;
        totalTime += dt;
    }
    
    // Convert rotation to pixel displacement
    // For small angles: dx ≈ rotY * focalLength, dy ≈ -rotX * focalLength
    float dx = totalRotY * params_.focalLengthPx;
    float dy = -totalRotX * params_.focalLengthPx;
    
    // Confidence based on sample count and consistency
    float confidence = std::min(1.0f, gyroSamples.size() / 10.0f);
    
    return FlowMeasurement(dx, dy, confidence);
}

MotionState KalmanFusionProcessor::predict(const GyroMeasurement& gyro) {
    // State transition matrix F (constant velocity model)
    // [1, 0, dt, 0 ]
    // [0, 1, 0,  dt]
    // [0, 0, 1,  0 ]
    // [0, 0, 0,  1 ]
    
    float dt = gyro.dt > 0 ? gyro.dt : 0.033f;  // Default 30fps
    
    // Predict state
    MotionState& s = kalmanState_.state;
    s.x += s.vx * dt;
    s.y += s.vy * dt;
    
    // Add gyro-based motion prediction
    float gyroDx = gyro.rotY * params_.focalLengthPx * dt * params_.gyroScale;
    float gyroDy = -gyro.rotX * params_.focalLengthPx * dt * params_.gyroScale;
    s.x += gyroDx;
    s.y += gyroDy;
    
    // Update covariance: P = F * P * F' + Q
    float F[16] = {
        1, 0, dt, 0,
        0, 1, 0, dt,
        0, 0, 1, 0,
        0, 0, 0, 1
    };
    
    float Ft[16];
    matTranspose4x4(F, Ft);
    
    float FP[16], FPFt[16];
    matMul4x4(F, kalmanState_.P.data(), FP);
    matMul4x4(FP, Ft, FPFt);
    
    // Process noise Q
    float Q[16] = {0};
    Q[0] = params_.processNoisePos * dt;
    Q[5] = params_.processNoisePos * dt;
    Q[10] = params_.processNoiseVel * dt;
    Q[15] = params_.processNoiseVel * dt;
    
    matAdd4x4(FPFt, Q, kalmanState_.P.data());
    
    return s;
}

MotionState KalmanFusionProcessor::update(const FlowMeasurement& flow) {
    // Measurement matrix H (observe position only)
    // [1, 0, 0, 0]
    // [0, 1, 0, 0]
    
    MotionState& s = kalmanState_.state;
    float* P = kalmanState_.P.data();
    
    // Innovation (measurement residual)
    float y0 = flow.dx - s.x;
    float y1 = flow.dy - s.y;
    
    // Innovation covariance: S = H * P * H' + R
    // Since H selects top-left 2x2 of P:
    float S00 = P[0] + params_.flowNoise / flow.confidence;
    float S01 = P[1];
    float S10 = P[4];
    float S11 = P[5] + params_.flowNoise / flow.confidence;
    
    // Outlier detection
    if (params_.enableOutlierRejection) {
        float variance = (S00 + S11) / 2;
        float mahaDist = mahalanobisDistance(y0, y1, variance);
        if (mahaDist > params_.outlierThreshold) {
            // Reject this measurement
            return s;
        }
    }
    
    // Kalman gain: K = P * H' * S^-1
    // For 2x2 S, compute inverse directly
    float detS = S00 * S11 - S01 * S10;
    if (std::abs(detS) < 1e-6f) detS = 1e-6f;
    float invDetS = 1.0f / detS;
    
    float Si00 = S11 * invDetS;
    float Si01 = -S01 * invDetS;
    float Si10 = -S10 * invDetS;
    float Si11 = S00 * invDetS;
    
    // K = P * H' * Si (H' is [1,0; 0,1; 0,0; 0,0])
    // So K = first two columns of P * Si
    float K[8];  // 4x2 matrix
    K[0] = P[0] * Si00 + P[1] * Si10;
    K[1] = P[0] * Si01 + P[1] * Si11;
    K[2] = P[4] * Si00 + P[5] * Si10;
    K[3] = P[4] * Si01 + P[5] * Si11;
    K[4] = P[8] * Si00 + P[9] * Si10;
    K[5] = P[8] * Si01 + P[9] * Si11;
    K[6] = P[12] * Si00 + P[13] * Si10;
    K[7] = P[12] * Si01 + P[13] * Si11;
    
    // Update state: x = x + K * y
    s.x += K[0] * y0 + K[1] * y1;
    s.y += K[2] * y0 + K[3] * y1;
    s.vx += K[4] * y0 + K[5] * y1;
    s.vy += K[6] * y0 + K[7] * y1;
    
    // Update covariance: P = (I - K * H) * P
    // K * H is 4x4 with K in first two columns, zeros elsewhere
    float KH[16] = {0};
    KH[0] = K[0]; KH[1] = K[1];
    KH[4] = K[2]; KH[5] = K[3];
    KH[8] = K[4]; KH[9] = K[5];
    KH[12] = K[6]; KH[13] = K[7];
    
    // I - KH
    float IKH[16];
    for (int i = 0; i < 16; ++i) {
        IKH[i] = (i % 5 == 0 ? 1.0f : 0.0f) - KH[i];
    }
    
    float newP[16];
    matMul4x4(IKH, P, newP);
    std::copy(newP, newP + 16, P);
    
    return s;
}

FusionResult KalmanFusionProcessor::fuse(
    const std::vector<GyroMeasurement>& gyroSamples,
    const FlowMeasurement& flow
) {
    FusionResult result;
    
    // Convert gyro to pixel displacement
    FlowMeasurement gyroFlow = gyroToPixels(gyroSamples);
    
    // Predict using gyro
    for (const auto& gyro : gyroSamples) {
        predict(gyro);
    }
    
    // Check for outlier before update
    float predictedX = kalmanState_.state.x;
    float predictedY = kalmanState_.state.y;
    float residualX = flow.dx - predictedX;
    float residualY = flow.dy - predictedY;
    float variance = (kalmanState_.P[0] + kalmanState_.P[5]) / 2 + params_.flowNoise;
    float mahaDist = mahalanobisDistance(residualX, residualY, variance);
    
    result.outlierDetected = mahaDist > params_.outlierThreshold;
    
    // Update with optical flow (if not outlier)
    if (!result.outlierDetected || !params_.enableOutlierRejection) {
        update(flow);
    }
    
    // Compute contributions
    float gyroMag = std::sqrt(gyroFlow.dx * gyroFlow.dx + gyroFlow.dy * gyroFlow.dy);
    float flowMag = std::sqrt(flow.dx * flow.dx + flow.dy * flow.dy);
    float totalMag = gyroMag + flowMag;
    
    if (totalMag > 1e-6f) {
        result.gyroContribution = gyroMag / totalMag * params_.gyroWeight;
        result.flowContribution = flowMag / totalMag * params_.flowWeight;
        
        // Normalize
        float totalContrib = result.gyroContribution + result.flowContribution;
        if (totalContrib > 0) {
            result.gyroContribution /= totalContrib;
            result.flowContribution /= totalContrib;
        }
    } else {
        result.gyroContribution = 0.5f;
        result.flowContribution = 0.5f;
    }
    
    result.motion = kalmanState_.state;
    result.uncertainty = std::sqrt(kalmanState_.P[0] + kalmanState_.P[5]);
    
    return result;
}

std::vector<FusionResult> KalmanFusionProcessor::fuseBatch(
    const std::vector<std::vector<GyroMeasurement>>& allGyroSamples,
    const std::vector<FlowMeasurement>& flows
) {
    std::vector<FusionResult> results;
    
    if (allGyroSamples.size() != flows.size()) {
        LOGW("KalmanFusion: Mismatched gyro/flow counts (%zu vs %zu)",
             allGyroSamples.size(), flows.size());
        return results;
    }
    
    reset();
    results.reserve(flows.size());
    
    for (size_t i = 0; i < flows.size(); ++i) {
        results.push_back(fuse(allGyroSamples[i], flows[i]));
    }
    
    LOGD("KalmanFusion: Processed %zu frame pairs", results.size());
    return results;
}

} // namespace ultradetail
