/**
 * orb_alignment.cpp - ORB Feature Matching Implementation
 * 
 * Implements ORB feature detection, matching, and RANSAC homography estimation.
 */

#include "orb_alignment.h"
#include "neon_utils.h"
#include <algorithm>
#include <random>
#include <cmath>

namespace ultradetail {

// ORB bit pattern - precomputed sampling pattern for BRIEF descriptor
// Each row: [x1, y1, x2, y2] relative to keypoint center
const int ORBAligner::ORB_PATTERN[256][4] = {
    {8,-3, 9,5}, {4,2, 7,-12}, {-11,9, -8,2}, {7,-12, 12,-13},
    {2,-13, 2,12}, {1,-7, 1,6}, {-2,-10, -2,-4}, {-13,-13, -11,-8},
    {-13,-3, -12,-9}, {10,4, 11,9}, {-13,-8, -8,-9}, {-11,7, -9,12},
    {7,7, 12,6}, {-4,-5, -3,0}, {-13,2, -12,-3}, {-9,0, -7,5},
    {12,-6, 12,-1}, {-3,6, -2,12}, {-6,-13, -4,-8}, {11,-13, 12,-8},
    {4,7, 5,1}, {5,-3, 10,-3}, {3,-7, 6,12}, {-8,-7, -6,-2},
    {-2,11, -1,-10}, {-13,12, -8,10}, {-7,3, -5,-3}, {-4,2, -3,7},
    {-10,-12, -6,11}, {5,-12, 6,-7}, {5,-6, 7,-1}, {1,0, 4,-5},
    {9,11, 11,-13}, {4,7, 4,12}, {2,-1, 4,4}, {-4,-12, -2,7},
    {-8,-5, -7,-10}, {4,11, 9,12}, {0,-8, 1,-13}, {-13,-2, -8,2},
    {-3,-2, -2,3}, {-6,9, -4,-9}, {8,12, 10,7}, {0,9, 1,3},
    {7,-5, 11,-10}, {-13,-6, -11,0}, {10,7, 12,1}, {-6,-3, -6,12},
    {10,-9, 12,-4}, {-13,8, -8,-12}, {-13,0, -8,-4}, {3,3, 7,8},
    {5,7, 10,-7}, {-1,7, 1,-12}, {3,-10, 5,6}, {2,-4, 3,-10},
    {-13,0, -13,5}, {-13,-7, -12,12}, {-13,3, -11,8}, {-7,12, -4,7},
    {6,-10, 12,8}, {-9,-1, -7,-6}, {-2,-5, 0,12}, {-12,5, -7,5},
    {3,-10, 8,-13}, {-7,-7, -4,5}, {-3,-2, -1,-7}, {2,9, 5,-11},
    {-11,-13, -5,-13}, {-1,6, 0,-1}, {5,-3, 5,2}, {-4,-13, -4,12},
    {-9,-6, -9,6}, {-12,-10, -8,-4}, {10,2, 12,-3}, {7,12, 12,12},
    {-7,-13, -6,5}, {-4,9, -3,4}, {7,-1, 12,2}, {-7,6, -5,1},
    {-13,11, -12,5}, {-3,7, -2,-6}, {7,-8, 12,-7}, {-13,-7, -11,-12},
    {1,-3, 12,12}, {2,-6, 3,0}, {-4,3, -2,-13}, {-1,-13, 1,9},
    {7,1, 8,-6}, {1,-1, 3,12}, {9,1, 12,6}, {-1,-9, -1,3},
    {-13,-13, -10,5}, {7,7, 10,12}, {12,-5, 12,9}, {6,3, 7,11},
    {5,-13, 6,10}, {2,-12, 2,3}, {3,8, 4,-6}, {2,6, 12,-13},
    {9,-12, 10,3}, {-8,4, -7,9}, {-11,12, -4,-6}, {1,12, 2,-8},
    {6,-9, 7,-4}, {2,3, 3,-2}, {6,3, 11,0}, {3,-3, 8,-8},
    {7,8, 9,3}, {-11,-5, -6,-4}, {-10,11, -5,10}, {-5,-8, -3,12},
    {-10,5, -9,0}, {8,-1, 12,-6}, {4,-6, 6,-11}, {-10,12, -8,7},
    {4,-2, 6,7}, {-2,0, -2,12}, {-5,-8, -5,2}, {7,-6, 10,12},
    {-9,-13, -8,-8}, {-5,-13, -5,-2}, {8,-8, 9,-13}, {-9,-11, -9,0},
    {1,-8, 1,-2}, {7,-4, 9,1}, {-2,1, -1,-4}, {11,-6, 12,-11},
    {-12,-9, -6,4}, {3,7, 7,12}, {5,5, 10,8}, {0,-4, 2,8},
    {-9,12, -5,-13}, {0,7, 2,12}, {-1,2, 1,7}, {5,11, 7,-9},
    {3,5, 6,-8}, {-13,-4, -8,9}, {-5,9, -3,-3}, {-4,-7, -3,-12},
    {6,5, 8,0}, {-7,6, -6,12}, {-13,6, -5,-2}, {1,-10, 3,10},
    {4,1, 8,-4}, {-2,-2, 2,-13}, {2,-12, 12,12}, {-2,-13, 0,-6},
    {4,1, 9,3}, {-6,-10, -3,-5}, {-3,-13, -1,1}, {7,5, 12,-11},
    {4,-2, 5,-7}, {-13,9, -9,-5}, {7,1, 8,6}, {7,-8, 7,6},
    {-7,-4, -7,1}, {-8,11, -7,-8}, {-13,6, -12,-8}, {2,4, 3,9},
    {10,-5, 12,3}, {-6,-5, -6,7}, {8,-3, 9,-8}, {2,-12, 2,8},
    {-11,-2, -10,3}, {-12,-13, -7,-9}, {-11,0, -10,-5}, {5,-3, 11,8},
    {-2,-13, -1,12}, {-1,-8, 0,9}, {-13,-11, -12,-5}, {-10,-2, -10,11},
    {-3,9, -2,-13}, {2,-3, 3,2}, {-9,-13, -4,0}, {-4,6, -3,-10},
    {-4,12, -2,-7}, {-6,-11, -4,9}, {6,-3, 6,11}, {-13,11, -5,5},
    {11,11, 12,6}, {7,-5, 12,-2}, {-1,12, 0,7}, {-4,-8, -3,-2},
    {-7,1, -6,7}, {-13,-12, -8,-13}, {-7,-2, -6,-8}, {-8,5, -6,-9},
    {-5,-1, -4,5}, {-13,7, -8,10}, {1,5, 5,-13}, {1,0, 10,-13},
    {9,12, 10,-1}, {5,-8, 10,-9}, {-1,11, 1,-13}, {-9,-3, -6,2},
    {-1,-10, 1,12}, {-13,1, -8,-10}, {8,-11, 10,-6}, {2,-13, 3,-6},
    {7,-13, 12,-9}, {-10,-10, -5,-7}, {-10,-8, -8,-13}, {4,-6, 8,5},
    {3,12, 8,-13}, {-4,2, -3,-3}, {5,-13, 10,-12}, {4,-13, 5,-1},
    {-9,9, -4,3}, {0,3, 3,-9}, {-12,1, -6,1}, {3,2, 4,-8},
    {-10,-10, -10,9}, {8,-13, 12,12}, {-8,-12, -6,-5}, {2,2, 3,7},
    {10,6, 11,-8}, {6,8, 8,-12}, {-7,10, -6,5}, {-3,-9, -3,9},
    {-1,-13, -1,5}, {-3,-7, -3,4}, {-8,-2, -8,3}, {4,2, 12,12},
    {2,-5, 3,11}, {6,-9, 11,-13}, {3,-1, 7,12}, {11,-1, 12,4},
    {-3,0, -3,6}, {4,-11, 4,12}, {2,-4, 2,1}, {-10,-6, -8,1},
    {-13,7, -11,1}, {-13,12, -11,-13}, {6,0, 11,-13}, {0,-1, 1,4},
    {-13,3, -9,-2}, {-9,8, -6,-3}, {-13,-6, -8,-2}, {5,-9, 8,10},
    {2,7, 3,-9}, {-1,-6, -1,-1}, {9,5, 11,-2}, {11,-3, 12,-8},
    {3,0, 3,5}, {-1,4, 0,10}, {3,-6, 4,5}, {-13,0, -10,5},
    {5,8, 12,11}, {8,9, 9,-6}, {7,-4, 8,-12}, {-10,4, -10,9},
    {7,3, 12,4}, {9,-7, 10,-2}, {7,0, 12,-2}, {-1,-6, 0,-11}
};

ORBAligner::ORBAligner(const ORBAlignmentParams& params)
    : params_(params) {
}

void ORBAligner::detectFAST(
    const GrayImage& image,
    std::vector<ORBKeypoint>& keypoints,
    int threshold
) {
    const int width = image.width;
    const int height = image.height;
    const int border = 3;
    
    // FAST-9 circle offsets
    static const int circle[16][2] = {
        {0, -3}, {1, -3}, {2, -2}, {3, -1},
        {3, 0}, {3, 1}, {2, 2}, {1, 3},
        {0, 3}, {-1, 3}, {-2, 2}, {-3, 1},
        {-3, 0}, {-3, -1}, {-2, -2}, {-1, -3}
    };
    
    keypoints.clear();
    keypoints.reserve(params_.maxKeypoints * 2);
    
    for (int y = border; y < height - border; ++y) {
        for (int x = border; x < width - border; ++x) {
            float center = image.at(x, y);
            float thresh = static_cast<float>(threshold) / 255.0f;
            
            // Quick rejection test using cardinal points
            int count = 0;
            float p0 = image.at(x, y - 3);
            float p4 = image.at(x + 3, y);
            float p8 = image.at(x, y + 3);
            float p12 = image.at(x - 3, y);
            
            if (p0 > center + thresh) count++;
            if (p4 > center + thresh) count++;
            if (p8 > center + thresh) count++;
            if (p12 > center + thresh) count++;
            
            bool isBrighter = count >= 3;
            
            count = 0;
            if (p0 < center - thresh) count++;
            if (p4 < center - thresh) count++;
            if (p8 < center - thresh) count++;
            if (p12 < center - thresh) count++;
            
            bool isDarker = count >= 3;
            
            if (!isBrighter && !isDarker) continue;
            
            // Full test - need 9 consecutive pixels
            int consecutive = 0;
            int maxConsecutive = 0;
            float sumDiff = 0;
            
            for (int i = 0; i < 32; ++i) {  // Check twice around
                int idx = i % 16;
                float p = image.at(x + circle[idx][0], y + circle[idx][1]);
                float diff = p - center;
                
                bool pass = isBrighter ? (diff > thresh) : (diff < -thresh);
                
                if (pass) {
                    consecutive++;
                    sumDiff += std::abs(diff);
                    maxConsecutive = std::max(maxConsecutive, consecutive);
                } else {
                    consecutive = 0;
                }
                
                if (i >= 15 && maxConsecutive >= 9) break;
            }
            
            if (maxConsecutive >= 9) {
                // Corner response (Harris-like)
                float response = sumDiff / maxConsecutive;
                keypoints.emplace_back(
                    static_cast<float>(x),
                    static_cast<float>(y),
                    0.0f,  // Orientation computed later
                    response,
                    0      // Octave
                );
            }
        }
    }
}

float ORBAligner::computeOrientation(const GrayImage& image, int x, int y, int radius) {
    float m01 = 0, m10 = 0;
    
    for (int dy = -radius; dy <= radius; ++dy) {
        int py = y + dy;
        if (py < 0 || py >= image.height) continue;
        
        for (int dx = -radius; dx <= radius; ++dx) {
            int px = x + dx;
            if (px < 0 || px >= image.width) continue;
            
            if (dx * dx + dy * dy <= radius * radius) {
                float val = image.at(px, py);
                m10 += dx * val;
                m01 += dy * val;
            }
        }
    }
    
    return std::atan2(m01, m10);
}

void ORBAligner::computeDescriptor(
    const GrayImage& image,
    const ORBKeypoint& kp,
    ORBDescriptor& desc
) {
    const int half = params_.patchSize / 2;
    int cx = static_cast<int>(kp.x);
    int cy = static_cast<int>(kp.y);
    
    // Check bounds
    if (cx < half || cx >= image.width - half ||
        cy < half || cy >= image.height - half) {
        desc.bits.reset();
        return;
    }
    
    float cosA = std::cos(kp.angle);
    float sinA = std::sin(kp.angle);
    
    for (int i = 0; i < 256; ++i) {
        // Rotate pattern by keypoint orientation
        float x1 = ORB_PATTERN[i][0] * cosA - ORB_PATTERN[i][1] * sinA;
        float y1 = ORB_PATTERN[i][0] * sinA + ORB_PATTERN[i][1] * cosA;
        float x2 = ORB_PATTERN[i][2] * cosA - ORB_PATTERN[i][3] * sinA;
        float y2 = ORB_PATTERN[i][2] * sinA + ORB_PATTERN[i][3] * cosA;
        
        int px1 = cx + static_cast<int>(x1);
        int py1 = cy + static_cast<int>(y1);
        int px2 = cx + static_cast<int>(x2);
        int py2 = cy + static_cast<int>(y2);
        
        // Clamp to image bounds
        px1 = clamp(px1, 0, image.width - 1);
        py1 = clamp(py1, 0, image.height - 1);
        px2 = clamp(px2, 0, image.width - 1);
        py2 = clamp(py2, 0, image.height - 1);
        
        desc.bits[i] = image.at(px1, py1) < image.at(px2, py2);
    }
}

void ORBAligner::nonMaxSuppression(std::vector<ORBKeypoint>& keypoints, int cellSize) {
    if (keypoints.empty()) return;
    
    // Sort by response (descending)
    std::sort(keypoints.begin(), keypoints.end(),
        [](const ORBKeypoint& a, const ORBKeypoint& b) {
            return a.response > b.response;
        });
    
    std::vector<ORBKeypoint> result;
    result.reserve(std::min(static_cast<int>(keypoints.size()), params_.maxKeypoints));
    
    // Simple grid-based suppression
    std::vector<bool> taken(keypoints.size(), false);
    
    for (size_t i = 0; i < keypoints.size() && result.size() < static_cast<size_t>(params_.maxKeypoints); ++i) {
        if (taken[i]) continue;
        
        result.push_back(keypoints[i]);
        
        // Suppress nearby keypoints
        for (size_t j = i + 1; j < keypoints.size(); ++j) {
            float dx = keypoints[j].x - keypoints[i].x;
            float dy = keypoints[j].y - keypoints[i].y;
            if (dx * dx + dy * dy < cellSize * cellSize) {
                taken[j] = true;
            }
        }
    }
    
    keypoints = std::move(result);
}

void ORBAligner::detectAndCompute(
    const GrayImage& image,
    std::vector<ORBKeypoint>& keypoints,
    std::vector<ORBDescriptor>& descriptors
) {
    // Detect FAST corners
    detectFAST(image, keypoints, params_.fastThreshold);
    
    // Non-maximum suppression
    nonMaxSuppression(keypoints);
    
    // Compute orientation and descriptors
    descriptors.resize(keypoints.size());
    
    const int orientRadius = params_.patchSize / 2;
    
    for (size_t i = 0; i < keypoints.size(); ++i) {
        // Compute orientation
        keypoints[i].angle = computeOrientation(
            image,
            static_cast<int>(keypoints[i].x),
            static_cast<int>(keypoints[i].y),
            orientRadius
        );
        
        // Compute descriptor
        computeDescriptor(image, keypoints[i], descriptors[i]);
    }
    
    LOGD("ORB: Detected %zu keypoints", keypoints.size());
}

std::vector<FeatureMatch> ORBAligner::matchDescriptors(
    const std::vector<ORBDescriptor>& desc1,
    const std::vector<ORBDescriptor>& desc2
) {
    std::vector<FeatureMatch> matches;
    
    if (desc1.empty() || desc2.empty()) return matches;
    
    matches.reserve(desc1.size());
    
    for (size_t i = 0; i < desc1.size(); ++i) {
        int best = 256, secondBest = 256;
        int bestIdx = -1;
        
        for (size_t j = 0; j < desc2.size(); ++j) {
            int dist = desc1[i].distance(desc2[j]);
            
            if (dist < best) {
                secondBest = best;
                best = dist;
                bestIdx = static_cast<int>(j);
            } else if (dist < secondBest) {
                secondBest = dist;
            }
        }
        
        // Lowe's ratio test
        if (bestIdx >= 0 && best < params_.matchRatioThreshold * secondBest) {
            matches.emplace_back(static_cast<int>(i), bestIdx, best);
        }
    }
    
    LOGD("ORB: Matched %zu descriptors", matches.size());
    return matches;
}

HomographyMatrix ORBAligner::computeHomography4Point(
    const std::array<std::pair<float, float>, 4>& src,
    const std::array<std::pair<float, float>, 4>& dst
) {
    // Direct Linear Transform (DLT) for 4 points
    // Solve Ah = 0 where h is the homography vector
    
    // Build matrix A (8x9)
    float A[8][9];
    for (int i = 0; i < 4; ++i) {
        float x = src[i].first, y = src[i].second;
        float u = dst[i].first, v = dst[i].second;
        
        A[2*i][0] = -x; A[2*i][1] = -y; A[2*i][2] = -1;
        A[2*i][3] = 0;  A[2*i][4] = 0;  A[2*i][5] = 0;
        A[2*i][6] = u*x; A[2*i][7] = u*y; A[2*i][8] = u;
        
        A[2*i+1][0] = 0;  A[2*i+1][1] = 0;  A[2*i+1][2] = 0;
        A[2*i+1][3] = -x; A[2*i+1][4] = -y; A[2*i+1][5] = -1;
        A[2*i+1][6] = v*x; A[2*i+1][7] = v*y; A[2*i+1][8] = v;
    }
    
    // Solve using simplified SVD (last column of V)
    // For 4 points, we can use a simpler approach
    float AtA[9][9] = {0};
    for (int i = 0; i < 9; ++i) {
        for (int j = 0; j < 9; ++j) {
            for (int k = 0; k < 8; ++k) {
                AtA[i][j] += A[k][i] * A[k][j];
            }
        }
    }
    
    // Power iteration to find smallest eigenvector
    float h[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    
    for (int iter = 0; iter < 100; ++iter) {
        float newH[9] = {0};
        for (int i = 0; i < 9; ++i) {
            for (int j = 0; j < 9; ++j) {
                newH[i] += AtA[i][j] * h[j];
            }
        }
        
        // Normalize
        float norm = 0;
        for (int i = 0; i < 9; ++i) norm += newH[i] * newH[i];
        norm = std::sqrt(norm);
        if (norm > 1e-6f) {
            for (int i = 0; i < 9; ++i) h[i] = newH[i] / norm;
        }
    }
    
    HomographyMatrix H;
    for (int i = 0; i < 9; ++i) H.data[i] = h[i];
    
    // Normalize so H[8] = 1
    if (std::abs(H.data[8]) > 1e-6f) {
        float scale = 1.0f / H.data[8];
        for (int i = 0; i < 9; ++i) H.data[i] *= scale;
    }
    
    return H;
}

ORBAlignmentResult ORBAligner::estimateHomography(
    const std::vector<ORBKeypoint>& kp1,
    const std::vector<ORBKeypoint>& kp2,
    const std::vector<FeatureMatch>& matches
) {
    ORBAlignmentResult result;
    result.totalMatches = static_cast<int>(matches.size());
    
    if (matches.size() < 4) {
        LOGW("ORB: Not enough matches for homography (%zu < 4)", matches.size());
        return result;
    }
    
    std::random_device rd;
    std::mt19937 rng(rd());
    
    int bestInliers = 0;
    HomographyMatrix bestH;
    std::vector<bool> bestInlierMask(matches.size(), false);
    
    for (int iter = 0; iter < params_.ransacIterations; ++iter) {
        // Select 4 random matches
        std::array<int, 4> indices;
        for (int i = 0; i < 4; ++i) {
            indices[i] = rng() % matches.size();
        }
        
        // Check for duplicates
        bool hasDuplicate = false;
        for (int i = 0; i < 4 && !hasDuplicate; ++i) {
            for (int j = i + 1; j < 4; ++j) {
                if (indices[i] == indices[j]) {
                    hasDuplicate = true;
                    break;
                }
            }
        }
        if (hasDuplicate) continue;
        
        // Build point correspondences
        std::array<std::pair<float, float>, 4> src, dst;
        for (int i = 0; i < 4; ++i) {
            const auto& m = matches[indices[i]];
            src[i] = {kp1[m.queryIdx].x, kp1[m.queryIdx].y};
            dst[i] = {kp2[m.trainIdx].x, kp2[m.trainIdx].y};
        }
        
        // Compute homography
        HomographyMatrix H = computeHomography4Point(src, dst);
        
        // Count inliers
        int inliers = 0;
        std::vector<bool> inlierMask(matches.size(), false);
        
        for (size_t i = 0; i < matches.size(); ++i) {
            const auto& m = matches[i];
            float x1 = kp1[m.queryIdx].x, y1 = kp1[m.queryIdx].y;
            float x2 = kp2[m.trainIdx].x, y2 = kp2[m.trainIdx].y;
            
            float px, py;
            H.transform(x1, y1, px, py);
            
            float dx = px - x2, dy = py - y2;
            float dist = std::sqrt(dx * dx + dy * dy);
            
            if (dist < params_.ransacThreshold) {
                inliers++;
                inlierMask[i] = true;
            }
        }
        
        if (inliers > bestInliers) {
            bestInliers = inliers;
            bestH = H;
            bestInlierMask = inlierMask;
        }
    }
    
    // Collect inlier matches
    for (size_t i = 0; i < matches.size(); ++i) {
        if (bestInlierMask[i]) {
            result.inliers.push_back(matches[i]);
        }
    }
    
    result.homography = bestH;
    result.inlierCount = bestInliers;
    result.inlierRatio = static_cast<float>(bestInliers) / matches.size();
    result.success = bestInliers >= 4 && result.inlierRatio > 0.3f;
    
    LOGD("ORB: Homography estimated, inliers=%d/%zu (%.1f%%)",
         bestInliers, matches.size(), result.inlierRatio * 100);
    
    return result;
}

ORBAlignmentResult ORBAligner::align(const GrayImage& reference, const GrayImage& frame) {
    std::vector<ORBKeypoint> kp1, kp2;
    std::vector<ORBDescriptor> desc1, desc2;
    
    // Detect and compute
    detectAndCompute(reference, kp1, desc1);
    detectAndCompute(frame, kp2, desc2);
    
    // Match
    auto matches = matchDescriptors(desc1, desc2);
    
    // Estimate homography
    return estimateHomography(kp1, kp2, matches);
}

} // namespace ultradetail
