/**
 * freq_separation.h - Frequency Separation Refinement
 * 
 * Implements frequency-domain image enhancement:
 * 1. Separate image into low-frequency (structure) and high-frequency (detail)
 * 2. Apply adaptive sharpening to high-frequency component
 * 3. Protect edges from halo artifacts
 * 4. Recombine with adjustable strength
 * 
 * Used by both MAX and ULTRA presets for detail enhancement.
 */

#ifndef ULTRADETAIL_FREQ_SEPARATION_H
#define ULTRADETAIL_FREQ_SEPARATION_H

#include "common.h"

namespace ultradetail {

/**
 * Frequency separation parameters
 */
struct FreqSeparationParams {
    float lowPassSigma = 2.0f;      // Gaussian sigma for low-frequency extraction
    float highBoost = 1.5f;          // High-frequency amplification factor
    float edgeProtection = 0.8f;     // Reduce boost near strong edges (0-1)
    float blendStrength = 1.0f;      // Final blend with original (0=original, 1=full effect)
    int kernelSize = 0;              // Kernel size (0 = auto from sigma)
};

/**
 * Frequency components result
 */
struct FreqComponents {
    GrayImage lowFreq;               // Low-frequency (blurred) component
    GrayImage highFreq;              // High-frequency (detail) component
    GrayImage edgeMask;              // Edge strength mask for protection
};

/**
 * Frequency Separation Processor
 * 
 * Separates an image into frequency bands and applies
 * adaptive enhancement to the high-frequency details.
 */
class FreqSeparationProcessor {
public:
    explicit FreqSeparationProcessor(const FreqSeparationParams& params = FreqSeparationParams());
    
    /**
     * Process a grayscale image with frequency separation enhancement
     * 
     * @param input Input grayscale image
     * @param output Output enhanced image (same size as input)
     */
    void processGray(const GrayImage& input, GrayImage& output);
    
    /**
     * Process an RGB image with frequency separation enhancement
     * 
     * @param input Input RGB image
     * @param output Output enhanced image (same size as input)
     */
    void processRGB(const RGBImage& input, RGBImage& output);
    
    /**
     * Get the separated frequency components (for debugging/visualization)
     * 
     * @param input Input grayscale image
     * @return Frequency components (low, high, edge mask)
     */
    FreqComponents separate(const GrayImage& input);
    
    /**
     * Update parameters
     */
    void setParams(const FreqSeparationParams& params) { params_ = params; }
    const FreqSeparationParams& getParams() const { return params_; }

private:
    FreqSeparationParams params_;
    
    // Gaussian kernel (cached)
    std::vector<float> gaussianKernel_;
    int kernelRadius_;
    
    /**
     * Build Gaussian kernel for given sigma
     */
    void buildGaussianKernel(float sigma);
    
    /**
     * Apply Gaussian blur (separable, horizontal pass)
     */
    void gaussianBlurH(const GrayImage& input, GrayImage& output);
    
    /**
     * Apply Gaussian blur (separable, vertical pass)
     */
    void gaussianBlurV(const GrayImage& input, GrayImage& output);
    
    /**
     * Compute edge magnitude using Sobel operator
     */
    void computeEdgeMask(const GrayImage& input, GrayImage& edgeMask);
    
    /**
     * Apply adaptive high-frequency boost with edge protection
     */
    void applyAdaptiveBoost(
        const GrayImage& highFreq,
        const GrayImage& edgeMask,
        GrayImage& boosted
    );
};

} // namespace ultradetail

#endif // ULTRADETAIL_FREQ_SEPARATION_H
