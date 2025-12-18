/**
 * drizzle.h - Drizzle Algorithm for Sub-Pixel Super-Resolution
 * 
 * Implements the Drizzle algorithm (Fruchter & Hook, 2002) for
 * combining dithered images with sub-pixel accuracy.
 * 
 * Key concepts:
 * - Each input pixel is "drizzled" onto output grid as a smaller drop
 * - Drop size (pixfrac) controls sharpness vs noise tradeoff
 * - Accumulates contributions with proper weighting
 * - Handles fractional pixel shifts from alignment
 * 
 * Used by ULTRA preset for maximum detail recovery.
 */

#ifndef ULTRADETAIL_DRIZZLE_H
#define ULTRADETAIL_DRIZZLE_H

#include "common.h"
#include "orb_alignment.h"
#include <vector>

namespace ultradetail {

/**
 * Drizzle algorithm parameters
 */
struct DrizzleParams {
    int scaleFactor = 2;          // Output scale factor (2x, 3x, 4x)
    float pixfrac = 0.7f;         // Drop size as fraction of input pixel (0.1-1.0)
    float weightPower = 1.0f;     // Weight falloff power (higher = sharper drops)
    bool useVarianceWeighting = true;  // Weight by inverse variance
    float minWeight = 0.01f;      // Minimum weight threshold
};

/**
 * Sub-pixel shift for a frame
 */
struct SubPixelShift {
    float dx;                     // X shift in input pixels (fractional)
    float dy;                     // Y shift in input pixels (fractional)
    float weight;                 // Frame quality weight (0-1)
    
    SubPixelShift() : dx(0), dy(0), weight(1.0f) {}
    SubPixelShift(float dx_, float dy_, float w = 1.0f) : dx(dx_), dy(dy_), weight(w) {}
};

/**
 * Drizzle accumulator pixel
 */
struct DrizzleAccumulator {
    float sumR, sumG, sumB;       // Weighted color sums
    float sumWeight;              // Total weight
    
    DrizzleAccumulator() : sumR(0), sumG(0), sumB(0), sumWeight(0) {}
    
    void add(float r, float g, float b, float w) {
        sumR += r * w;
        sumG += g * w;
        sumB += b * w;
        sumWeight += w;
    }
    
    RGBPixel normalize() const {
        if (sumWeight > 0) {
            float invW = 1.0f / sumWeight;
            return RGBPixel(
                clamp(sumR * invW, 0.0f, 1.0f),
                clamp(sumG * invW, 0.0f, 1.0f),
                clamp(sumB * invW, 0.0f, 1.0f)
            );
        }
        return RGBPixel(0, 0, 0);
    }
};

/**
 * Drizzle result
 */
struct DrizzleResult {
    RGBImage output;              // Drizzled output image
    GrayImage weightMap;          // Per-pixel weight coverage
    int outputWidth;
    int outputHeight;
    float avgCoverage;            // Average weight coverage
    bool success;
    
    DrizzleResult() : outputWidth(0), outputHeight(0), avgCoverage(0), success(false) {}
};

/**
 * Drizzle Processor
 * 
 * Combines multiple sub-pixel shifted frames into a higher resolution output.
 */
class DrizzleProcessor {
public:
    explicit DrizzleProcessor(const DrizzleParams& params = DrizzleParams());
    
    /**
     * Process multiple frames with known sub-pixel shifts
     * 
     * @param frames Input frames (same size, aligned)
     * @param shifts Sub-pixel shifts for each frame relative to reference
     * @param referenceIdx Index of reference frame (shift should be 0,0)
     * @return Drizzled result at higher resolution
     */
    DrizzleResult process(
        const std::vector<RGBImage>& frames,
        const std::vector<SubPixelShift>& shifts,
        int referenceIdx = 0
    );
    
    /**
     * Process grayscale frames
     */
    DrizzleResult processGray(
        const std::vector<GrayImage>& frames,
        const std::vector<SubPixelShift>& shifts,
        int referenceIdx = 0
    );
    
    /**
     * Estimate sub-pixel shifts from homographies
     * Extracts translation component from homography matrices
     */
    static std::vector<SubPixelShift> shiftsFromHomographies(
        const std::vector<HomographyMatrix>& homographies,
        int referenceIdx
    );
    
    /**
     * Update parameters
     */
    void setParams(const DrizzleParams& params) { params_ = params; }
    const DrizzleParams& getParams() const { return params_; }

private:
    DrizzleParams params_;
    
    /**
     * Compute drop weight at a given distance from drop center
     */
    float computeDropWeight(float dx, float dy, float dropRadius) const;
    
    /**
     * Drizzle a single input pixel onto the output grid
     */
    void drizzlePixel(
        std::vector<DrizzleAccumulator>& accum,
        int outWidth, int outHeight,
        float inX, float inY,
        const RGBPixel& color,
        float frameWeight
    );
    
    /**
     * Drizzle a single grayscale pixel
     */
    void drizzlePixelGray(
        std::vector<float>& accumSum,
        std::vector<float>& accumWeight,
        int outWidth, int outHeight,
        float inX, float inY,
        float value,
        float frameWeight
    );
};

} // namespace ultradetail

#endif // ULTRADETAIL_DRIZZLE_H
