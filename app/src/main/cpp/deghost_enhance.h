/**
 * deghost_enhance.h - Ghosting Prevention and Detail Enhancement
 * 
 * Implements professional-grade techniques for:
 * 1. Temporal median merging for robust outlier rejection
 * 2. Motion mask detection to identify moving pixels
 * 3. Reference frame fallback for high-motion regions
 * 4. Multi-scale Laplacian pyramid sharpening
 * 5. Local contrast enhancement (CLAHE-like)
 * 
 * Based on techniques from Google HDR+, Apple Deep Fusion, and Topaz Photo AI.
 */

#ifndef ULTRADETAIL_DEGHOST_ENHANCE_H
#define ULTRADETAIL_DEGHOST_ENHANCE_H

#include "common.h"
#include <vector>
#include <algorithm>
#include <cmath>

namespace ultradetail {

/**
 * Configuration for deghosting and enhancement
 */
struct DeghostEnhanceConfig {
    // Ghosting prevention
    float motionThreshold = 0.08f;        // Color difference threshold for motion detection (0-1) - reduced for better detection
    float confidenceThreshold = 0.75f;    // Minimum confidence to include pixel - increased: 0.3 -> 0.5 -> 0.65 -> 0.75 for maximum ghosting elimination
    bool useTemporalMedian = true;        // Use median instead of mean for merging
    bool useMotionMask = true;            // Detect and exclude moving pixels
    bool useReferenceFallback = true;     // Fall back to reference for high-motion
    
    // Detail enhancement
    float sharpenStrength = 0.7f;         // Sharpening intensity (0-2)
    int pyramidLevels = 3;                // Laplacian pyramid levels
    float contrastStrength = 0.3f;        // Local contrast enhancement (0-1)
    int claheClipLimit = 40;              // CLAHE clip limit (histogram bins)
    int claheTileSize = 8;                // CLAHE tile size
    
    // Edge preservation
    float edgeThreshold = 0.05f;          // Edge detection threshold
    float edgeBoost = 1.3f;               // Extra sharpening on edges
};

/**
 * Motion mask for a single pixel
 */
struct MotionInfo {
    bool isMoving;           // True if pixel detected as moving
    float motionMagnitude;   // Magnitude of detected motion (0-1)
    float confidence;        // Confidence in motion detection
    
    MotionInfo() : isMoving(false), motionMagnitude(0), confidence(1.0f) {}
};

using MotionMask = ImageBuffer<MotionInfo>;

/**
 * Sample from a single frame for temporal merging
 */
struct TemporalSample {
    RGBPixel color;
    float weight;
    float confidence;
    int frameIndex;
    
    TemporalSample() : weight(0), confidence(0), frameIndex(-1) {}
    TemporalSample(const RGBPixel& c, float w, float conf, int idx)
        : color(c), weight(w), confidence(conf), frameIndex(idx) {}
};

/**
 * Deghosting and Enhancement Processor
 * 
 * Provides comprehensive ghosting prevention and detail enhancement
 * using techniques from professional computational photography.
 */
class DeghostEnhancer {
public:
    explicit DeghostEnhancer(const DeghostEnhanceConfig& config = DeghostEnhanceConfig());
    
    /**
     * Compute motion mask by comparing frames to reference
     * 
     * @param reference Reference frame
     * @param frames All input frames
     * @param referenceIndex Index of reference frame
     * @return Motion mask indicating moving pixels
     */
    MotionMask computeMotionMask(
        const RGBImage& reference,
        const std::vector<RGBImage>& frames,
        int referenceIndex
    );
    
    /**
     * Merge pixels using temporal median (robust to outliers/ghosting)
     * 
     * @param samples Samples from all frames for this pixel
     * @return Merged pixel value
     */
    RGBPixel temporalMedianMerge(std::vector<TemporalSample>& samples);
    
    /**
     * Merge pixels using weighted mean with motion rejection
     * 
     * @param samples Samples from all frames
     * @param motionInfo Motion information for this pixel
     * @param referencePixel Reference frame pixel (fallback)
     * @return Merged pixel value
     */
    RGBPixel robustMerge(
        std::vector<TemporalSample>& samples,
        const MotionInfo& motionInfo,
        const RGBPixel& referencePixel
    );
    
    /**
     * Apply multi-scale Laplacian pyramid sharpening
     * 
     * @param image Input image (modified in place)
     */
    void applyLaplacianSharpening(RGBImage& image);
    
    /**
     * Apply local contrast enhancement (CLAHE-like)
     * 
     * @param image Input image (modified in place)
     */
    void applyLocalContrastEnhancement(RGBImage& image);
    
    /**
     * Apply edge-aware sharpening
     * 
     * @param image Input image (modified in place)
     */
    void applyEdgeAwareSharpening(RGBImage& image);
    
    /**
     * Full enhancement pipeline
     * 
     * @param image Input image (modified in place)
     */
    void enhance(RGBImage& image);
    
    /**
     * Check if a pixel is likely moving based on frame differences
     */
    bool isMovingPixel(
        const RGBPixel& reference,
        const RGBPixel& frame,
        float threshold
    ) const;
    
    /**
     * Compute color difference between two pixels
     */
    float colorDifference(const RGBPixel& a, const RGBPixel& b) const;

private:
    DeghostEnhanceConfig config_;
    
    // Gaussian pyramid for multi-scale processing
    struct PyramidLevel {
        RGBImage image;
        int width, height;
    };
    
    /**
     * Build Gaussian pyramid
     */
    std::vector<PyramidLevel> buildGaussianPyramid(const RGBImage& image, int levels);
    
    /**
     * Build Laplacian pyramid from Gaussian pyramid
     */
    std::vector<RGBImage> buildLaplacianPyramid(const std::vector<PyramidLevel>& gaussian);
    
    /**
     * Reconstruct image from Laplacian pyramid
     */
    RGBImage reconstructFromLaplacian(
        const std::vector<RGBImage>& laplacian,
        const PyramidLevel& base
    );
    
    /**
     * Downsample image by 2x using Gaussian blur
     */
    RGBImage downsample2x(const RGBImage& image);
    
    /**
     * Upsample image by 2x using bilinear interpolation
     */
    RGBImage upsample2x(const RGBImage& image, int targetWidth, int targetHeight);
    
    /**
     * Apply Gaussian blur (3x3 kernel)
     */
    void gaussianBlur3x3(RGBImage& image);
    
    /**
     * Compute edge magnitude at a pixel
     */
    float computeEdgeMagnitude(const RGBImage& image, int x, int y);
};

// ============================================================================
// Inline implementations for performance-critical functions
// ============================================================================

inline bool DeghostEnhancer::isMovingPixel(
    const RGBPixel& reference,
    const RGBPixel& frame,
    float threshold
) const {
    return colorDifference(reference, frame) > threshold;
}

inline float DeghostEnhancer::colorDifference(const RGBPixel& a, const RGBPixel& b) const {
    float dr = a.r - b.r;
    float dg = a.g - b.g;
    float db = a.b - b.b;
    return std::sqrt(dr * dr + dg * dg + db * db);
}

} // namespace ultradetail

#endif // ULTRADETAIL_DEGHOST_ENHANCE_H
