/**
 * optical_flow.h - Dense optical flow for frame alignment
 * 
 * Implements hierarchical Lucas-Kanade optical flow with:
 * - Coarse-to-fine pyramid processing
 * - Gyro-based initialization for reduced search
 * - Per-pixel flow estimation
 * - NEON optimization
 * 
 * Based on research from Google's Handheld Multi-Frame Super-Resolution.
 */

#ifndef ULTRADETAIL_OPTICAL_FLOW_H
#define ULTRADETAIL_OPTICAL_FLOW_H

#include "common.h"
#include "pyramid.h"

namespace ultradetail {

/**
 * 2D flow vector (sub-pixel precision)
 */
struct FlowVector {
    float dx, dy;
    float confidence;  // Flow reliability [0, 1]
    
    FlowVector() : dx(0), dy(0), confidence(0) {}
    FlowVector(float dx_, float dy_, float conf = 1.0f) 
        : dx(dx_), dy(dy_), confidence(conf) {}
    
    float magnitude() const {
        return std::sqrt(dx * dx + dy * dy);
    }
    
    FlowVector operator+(const FlowVector& other) const {
        return FlowVector(dx + other.dx, dy + other.dy, 
                         (confidence + other.confidence) / 2.0f);
    }
    
    FlowVector operator*(float scale) const {
        return FlowVector(dx * scale, dy * scale, confidence);
    }
};

using FlowField = ImageBuffer<FlowVector>;

/**
 * Optical flow parameters
 */
struct OpticalFlowParams {
    int pyramidLevels = 4;           // Number of pyramid levels
    int windowSize = 15;             // Lucas-Kanade window size (odd number)
    int maxIterations = 10;          // Max iterations per level
    float convergenceThreshold = 0.01f;  // Flow convergence threshold
    float minEigenThreshold = 0.001f;    // Minimum eigenvalue for valid flow
    bool useGyroInit = true;         // Use gyro-based initialization
    float gyroSearchRadius = 5.0f;   // Search radius when using gyro init (pixels)
    float noGyroSearchRadius = 20.0f; // Search radius without gyro init
};

/**
 * Gyro-based homography for flow initialization
 */
struct GyroHomography {
    float h[9];  // 3x3 homography matrix (row-major)
    bool isValid;
    
    GyroHomography() : isValid(false) {
        // Identity
        h[0] = 1; h[1] = 0; h[2] = 0;
        h[3] = 0; h[4] = 1; h[5] = 0;
        h[6] = 0; h[7] = 0; h[8] = 1;
    }
    
    GyroHomography(const float* matrix) : isValid(true) {
        for (int i = 0; i < 9; ++i) h[i] = matrix[i];
    }
    
    // Transform point using homography
    void transformPoint(float x, float y, float& outX, float& outY) const {
        float w = h[6] * x + h[7] * y + h[8];
        if (std::abs(w) < 1e-6f) w = 1.0f;
        outX = (h[0] * x + h[1] * y + h[2]) / w;
        outY = (h[3] * x + h[4] * y + h[5]) / w;
    }
    
    // Get initial flow at a point
    FlowVector getInitialFlow(float x, float y) const {
        float newX, newY;
        transformPoint(x, y, newX, newY);
        return FlowVector(newX - x, newY - y, 1.0f);
    }
};

/**
 * Dense optical flow result
 */
struct DenseFlowResult {
    FlowField flowField;          // Per-pixel flow vectors
    float averageFlow;            // Average flow magnitude
    float coverage;               // Percentage of pixels with valid flow
    bool isValid;
    
    DenseFlowResult() : averageFlow(0), coverage(0), isValid(false) {}
};

/**
 * Dense optical flow estimator
 * 
 * Computes per-pixel optical flow using hierarchical Lucas-Kanade
 * with optional gyro-based initialization.
 */
class DenseOpticalFlow {
public:
    explicit DenseOpticalFlow(const OpticalFlowParams& params = OpticalFlowParams());
    
    /**
     * Set reference frame
     */
    void setReference(const GrayImage& reference);
    
    /**
     * Compute dense flow from reference to target frame
     * 
     * @param target Target frame
     * @param gyroInit Optional gyro-based initialization
     * @return Dense flow result
     */
    DenseFlowResult computeFlow(const GrayImage& target, 
                                const GyroHomography& gyroInit = GyroHomography());
    
    /**
     * Warp image using computed flow
     */
    void warpImage(const RGBImage& input, const FlowField& flow, RGBImage& output);
    
    /**
     * Convert flow field to motion field (for compatibility with existing code)
     */
    MotionField flowToMotionField(const FlowField& flow, int tileSize);

private:
    OpticalFlowParams params_;
    GaussianPyramid refPyramid_;
    int imageWidth_;
    int imageHeight_;
    
    // Precomputed gradients for reference
    GrayImage refGradX_;
    GrayImage refGradY_;
    
    /**
     * Compute image gradients using Scharr operator
     */
    void computeGradients(const GrayImage& image, GrayImage& gradX, GrayImage& gradY);
    
    /**
     * Compute flow at a single pixel using Lucas-Kanade
     */
    FlowVector computePixelFlow(
        const GrayImage& ref,
        const GrayImage& target,
        const GrayImage& gradX,
        const GrayImage& gradY,
        int x, int y,
        const FlowVector& initialFlow
    );
    
    /**
     * Refine flow at a pyramid level
     */
    void refineFlowLevel(
        const GrayImage& ref,
        const GrayImage& target,
        FlowField& flow,
        int level
    );
    
    /**
     * Upsample flow field to next pyramid level
     */
    void upsampleFlow(const FlowField& coarse, FlowField& fine);
    
    /**
     * Bilinear interpolation for sub-pixel sampling
     */
    float sampleBilinear(const GrayImage& image, float x, float y);
};

} // namespace ultradetail

#endif // ULTRADETAIL_OPTICAL_FLOW_H
