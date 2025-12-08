/**
 * mfsr.h - Multi-Frame Super-Resolution
 * 
 * Implements shift-and-add style multi-frame super-resolution
 * using sub-pixel aligned burst frames to reconstruct a higher
 * resolution image.
 */

#ifndef ULTRADETAIL_MFSR_H
#define ULTRADETAIL_MFSR_H

#include "common.h"
#include "alignment.h"
#include <vector>
#include <functional>

namespace ultradetail {

/**
 * Sub-pixel motion vector with float precision
 */
struct SubPixelMotion {
    float dx, dy;       // Sub-pixel offsets
    float confidence;   // Alignment confidence [0, 1]
    
    SubPixelMotion() : dx(0), dy(0), confidence(0) {}
    SubPixelMotion(float dx_, float dy_, float conf_) 
        : dx(dx_), dy(dy_), confidence(conf_) {}
};

/**
 * Sub-pixel motion field
 */
using SubPixelMotionField = ImageBuffer<SubPixelMotion>;

/**
 * MFSR parameters
 */
struct MFSRParams {
    int scaleFactor = 2;              // Upscale factor (2x or 4x)
    int tileSize = 32;                // Tile size for sub-pixel alignment
    int searchRadius = 4;             // Sub-pixel search radius
    float convergenceThreshold = 0.01f; // Sub-pixel convergence threshold
    int maxIterations = 5;            // Max iterations for sub-pixel refinement
    float regularizationWeight = 0.1f; // Regularization for gap filling
    bool useWeightedAccumulation = true; // Weight by distance and confidence
};

/**
 * MFSR result
 */
struct MFSRResult {
    RGBImage upscaledImage;           // Output high-resolution image
    float averageSubPixelShift;       // Average sub-pixel shift detected
    int framesContributed;            // Number of frames that contributed
    float coverage;                   // Percentage of output pixels with data
    bool success;
    
    MFSRResult() : averageSubPixelShift(0), framesContributed(0), 
                   coverage(0), success(false) {}
};

/**
 * Progress callback for MFSR
 * Parameters: stage description, progress (0-1)
 */
using MFSRProgressCallback = std::function<void(const char*, float)>;

/**
 * Multi-Frame Super-Resolution processor
 * 
 * Uses sub-pixel alignment information from multiple frames to
 * reconstruct a higher resolution image through shift-and-add.
 */
class MultiFrameSR {
public:
    /**
     * Constructor
     * 
     * @param params MFSR parameters
     */
    explicit MultiFrameSR(const MFSRParams& params = MFSRParams());
    
    /**
     * Process aligned frames to produce upscaled image
     * 
     * @param frames Input RGB frames (already coarsely aligned)
     * @param alignments Coarse alignments from TileAligner
     * @param referenceIndex Index of reference frame
     * @param result Output MFSR result
     * @param progressCallback Optional progress callback
     */
    void process(
        const std::vector<RGBImage>& frames,
        const std::vector<FrameAlignment>& alignments,
        int referenceIndex,
        MFSRResult& result,
        MFSRProgressCallback progressCallback = nullptr
    );
    
    /**
     * Compute sub-pixel motion field for a frame
     * 
     * @param reference Reference grayscale image
     * @param frame Frame to align
     * @param coarseAlignment Coarse integer alignment
     * @return Sub-pixel motion field
     */
    SubPixelMotionField computeSubPixelMotion(
        const GrayImage& reference,
        const GrayImage& frame,
        const FrameAlignment& coarseAlignment
    );

private:
    MFSRParams params_;
    
    /**
     * Accumulator pixel for high-res grid
     */
    struct AccumulatorPixel {
        float r, g, b;      // Accumulated color
        float weight;       // Total weight
        int sampleCount;    // Number of samples
        
        AccumulatorPixel() : r(0), g(0), b(0), weight(0), sampleCount(0) {}
        
        void add(const RGBPixel& pixel, float w) {
            // Skip invalid input pixels
            if (!std::isfinite(pixel.r) || !std::isfinite(pixel.g) || !std::isfinite(pixel.b)) {
                return;
            }
            // Skip invalid weights
            if (!std::isfinite(w) || w <= 0.0f) {
                return;
            }
            
            r += pixel.r * w;
            g += pixel.g * w;
            b += pixel.b * w;
            weight += w;
            sampleCount++;
        }
        
        RGBPixel normalize() const {
            if (weight > 0.0f && std::isfinite(weight)) {
                float invW = 1.0f / weight;
                float nr = r * invW;
                float ng = g * invW;
                float nb = b * invW;
                
                // Ensure result is finite and clamped
                nr = std::isfinite(nr) ? clamp(nr, 0.0f, 1.0f) : 0.0f;
                ng = std::isfinite(ng) ? clamp(ng, 0.0f, 1.0f) : 0.0f;
                nb = std::isfinite(nb) ? clamp(nb, 0.0f, 1.0f) : 0.0f;
                
                return RGBPixel(nr, ng, nb);
            }
            return RGBPixel();
        }
    };
    
    using AccumulatorImage = ImageBuffer<AccumulatorPixel>;
    
    /**
     * Refine motion to sub-pixel accuracy using parabolic fitting
     */
    SubPixelMotion refineToSubPixel(
        const GrayImage& ref,
        const GrayImage& frame,
        int tileX, int tileY,
        int tileSize,
        const MotionVector& integerMotion
    );
    
    /**
     * Compute SAD at fractional offset using bilinear interpolation
     */
    float computeSubPixelSAD(
        const GrayImage& ref,
        const GrayImage& frame,
        int refX, int refY,
        float frameX, float frameY,
        int tileSize
    );
    
    /**
     * Scatter frame pixels to high-res accumulator
     */
    void scatterToAccumulator(
        const RGBImage& frame,
        const SubPixelMotionField& motion,
        AccumulatorImage& accumulator,
        int scaleFactor
    );
    
    /**
     * Fill gaps in accumulator using interpolation
     */
    void fillGaps(AccumulatorImage& accumulator);
    
    /**
     * Convert accumulator to final image
     */
    void finalizeImage(const AccumulatorImage& accumulator, RGBImage& output);
    
    /**
     * Compute Lanczos weight for sub-pixel contribution
     */
    float lanczosWeight(float distance, float a = 2.0f);
    
    /**
     * Compute Gaussian weight
     */
    float gaussianWeight(float distance, float sigma = 0.5f);
};

} // namespace ultradetail

#endif // ULTRADETAIL_MFSR_H
