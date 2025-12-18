/**
 * orb_alignment.h - ORB Feature Matching for Robust Alignment
 * 
 * Implements ORB (Oriented FAST and Rotated BRIEF) feature detection
 * and matching for robust frame alignment that handles:
 * - Large motions
 * - Rotation
 * - Scale changes
 * 
 * Based on: Rublee et al., "ORB: An efficient alternative to SIFT or SURF"
 */

#ifndef ULTRADETAIL_ORB_ALIGNMENT_H
#define ULTRADETAIL_ORB_ALIGNMENT_H

#include "common.h"
#include <vector>
#include <array>
#include <bitset>

namespace ultradetail {

/**
 * ORB Keypoint with orientation and scale
 */
struct ORBKeypoint {
    float x, y;           // Position
    float angle;          // Orientation in radians
    float response;       // Corner response
    int octave;           // Pyramid level
    
    ORBKeypoint() : x(0), y(0), angle(0), response(0), octave(0) {}
    ORBKeypoint(float x_, float y_, float angle_, float resp_, int oct_)
        : x(x_), y(y_), angle(angle_), response(resp_), octave(oct_) {}
};

/**
 * ORB Descriptor - 256-bit binary descriptor
 */
struct ORBDescriptor {
    std::bitset<256> bits;
    
    // Hamming distance to another descriptor
    int distance(const ORBDescriptor& other) const {
        return (bits ^ other.bits).count();
    }
};

/**
 * Feature match between two keypoints
 */
struct FeatureMatch {
    int queryIdx;         // Index in query keypoints
    int trainIdx;         // Index in train keypoints
    int distance;         // Hamming distance
    
    FeatureMatch() : queryIdx(-1), trainIdx(-1), distance(256) {}
    FeatureMatch(int q, int t, int d) : queryIdx(q), trainIdx(t), distance(d) {}
};

/**
 * Homography matrix (3x3)
 */
struct HomographyMatrix {
    float data[9];  // Row-major 3x3 matrix
    
    HomographyMatrix() {
        // Identity matrix
        data[0] = 1; data[1] = 0; data[2] = 0;
        data[3] = 0; data[4] = 1; data[5] = 0;
        data[6] = 0; data[7] = 0; data[8] = 1;
    }
    
    // Transform a point
    void transform(float x, float y, float& outX, float& outY) const {
        float w = data[6] * x + data[7] * y + data[8];
        if (std::abs(w) > 1e-6f) {
            outX = (data[0] * x + data[1] * y + data[2]) / w;
            outY = (data[3] * x + data[4] * y + data[5]) / w;
        } else {
            outX = x;
            outY = y;
        }
    }
};

/**
 * ORB Alignment parameters
 */
struct ORBAlignmentParams {
    int maxKeypoints = 500;           // Maximum keypoints to detect
    int nLevels = 4;                  // Number of pyramid levels
    float scaleFactor = 1.2f;         // Scale factor between levels
    int fastThreshold = 20;           // FAST corner threshold
    int patchSize = 31;               // Patch size for descriptor
    float matchRatioThreshold = 0.75f; // Lowe's ratio test threshold
    int ransacIterations = 500;       // RANSAC iterations
    float ransacThreshold = 3.0f;     // RANSAC inlier threshold (pixels)
};

/**
 * ORB Alignment result
 */
struct ORBAlignmentResult {
    HomographyMatrix homography;
    std::vector<FeatureMatch> inliers;
    int totalMatches;
    int inlierCount;
    float inlierRatio;
    bool success;
    
    ORBAlignmentResult() : totalMatches(0), inlierCount(0), inlierRatio(0), success(false) {}
};

/**
 * ORB Feature Aligner
 * 
 * Provides robust alignment using ORB features with RANSAC-based
 * homography estimation.
 */
class ORBAligner {
public:
    explicit ORBAligner(const ORBAlignmentParams& params = ORBAlignmentParams());
    
    /**
     * Detect ORB keypoints and compute descriptors
     */
    void detectAndCompute(
        const GrayImage& image,
        std::vector<ORBKeypoint>& keypoints,
        std::vector<ORBDescriptor>& descriptors
    );
    
    /**
     * Match descriptors using brute-force with ratio test
     */
    std::vector<FeatureMatch> matchDescriptors(
        const std::vector<ORBDescriptor>& desc1,
        const std::vector<ORBDescriptor>& desc2
    );
    
    /**
     * Estimate homography using RANSAC
     */
    ORBAlignmentResult estimateHomography(
        const std::vector<ORBKeypoint>& kp1,
        const std::vector<ORBKeypoint>& kp2,
        const std::vector<FeatureMatch>& matches
    );
    
    /**
     * Full alignment pipeline: detect, match, estimate homography
     */
    ORBAlignmentResult align(const GrayImage& reference, const GrayImage& frame);

private:
    ORBAlignmentParams params_;
    
    // FAST corner detection
    void detectFAST(
        const GrayImage& image,
        std::vector<ORBKeypoint>& keypoints,
        int threshold
    );
    
    // Compute orientation using intensity centroid
    float computeOrientation(const GrayImage& image, int x, int y, int radius);
    
    // Compute ORB descriptor for a keypoint
    void computeDescriptor(
        const GrayImage& image,
        const ORBKeypoint& kp,
        ORBDescriptor& desc
    );
    
    // Non-maximum suppression
    void nonMaxSuppression(
        std::vector<ORBKeypoint>& keypoints,
        int cellSize = 8
    );
    
    // Compute homography from 4 point correspondences
    HomographyMatrix computeHomography4Point(
        const std::array<std::pair<float, float>, 4>& src,
        const std::array<std::pair<float, float>, 4>& dst
    );
    
    // ORB bit pattern (precomputed)
    static const int ORB_PATTERN[256][4];
};

} // namespace ultradetail

#endif // ULTRADETAIL_ORB_ALIGNMENT_H
