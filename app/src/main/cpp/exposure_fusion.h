/**
 * exposure_fusion.h - Mertens Exposure Fusion for HDR Detail
 * 
 * Implements the Mertens et al. exposure fusion algorithm to combine
 * multiple exposures into a single well-exposed image without tone mapping.
 * 
 * Key features:
 * - Weight by contrast, saturation, and well-exposedness
 * - Laplacian pyramid blending for seamless fusion
 * - No tone mapping required (direct display output)
 * - Preserves detail from all exposure levels
 * 
 * Reference: "Exposure Fusion" by Mertens, Kautz, Van Reeth (2007)
 */

#ifndef ULTRADETAIL_EXPOSURE_FUSION_H
#define ULTRADETAIL_EXPOSURE_FUSION_H

#include "common.h"
#include <vector>

namespace ultradetail {

/**
 * Exposure fusion configuration
 */
struct ExposureFusionConfig {
    float contrastWeight = 1.0f;      // Weight for local contrast
    float saturationWeight = 1.0f;    // Weight for color saturation
    float exposureWeight = 1.0f;      // Weight for well-exposedness
    int pyramidLevels = 5;            // Number of pyramid levels
    float sigma = 5.0f;               // Gaussian blur sigma for weight smoothing
    
    ExposureFusionConfig() = default;
};

/**
 * Exposure fusion result
 */
struct ExposureFusionResult {
    RGBImage fused;                   // Fused output image
    std::vector<GrayImage> weights;   // Weight maps for each input
    float avgContrast;                // Average contrast metric
    float avgSaturation;              // Average saturation metric
    float avgExposure;                // Average exposure metric
    bool success;
    
    ExposureFusionResult() : avgContrast(0), avgSaturation(0), avgExposure(0), success(false) {}
};

/**
 * Exposure Fusion Processor
 * 
 * Combines multiple exposures using Mertens algorithm.
 */
class ExposureFusionProcessor {
public:
    explicit ExposureFusionProcessor(const ExposureFusionConfig& config = ExposureFusionConfig());
    
    /**
     * Fuse multiple exposure images
     * 
     * @param images Vector of input images (different exposures)
     * @return Fused result with HDR-like detail
     */
    ExposureFusionResult fuse(const std::vector<RGBImage>& images);
    
    /**
     * Update configuration
     */
    void setConfig(const ExposureFusionConfig& config) { config_ = config; }
    const ExposureFusionConfig& getConfig() const { return config_; }

private:
    ExposureFusionConfig config_;
    
    /**
     * Compute quality weights for an image
     * Combines contrast, saturation, and well-exposedness
     */
    GrayImage computeWeights(const RGBImage& image);
    
    /**
     * Compute local contrast using Laplacian
     */
    GrayImage computeContrast(const RGBImage& image);
    
    /**
     * Compute color saturation
     */
    GrayImage computeSaturation(const RGBImage& image);
    
    /**
     * Compute well-exposedness (Gaussian around 0.5)
     */
    GrayImage computeWellExposedness(const RGBImage& image);
    
    /**
     * Normalize weights so they sum to 1 at each pixel
     */
    void normalizeWeights(std::vector<GrayImage>& weights);
    
    /**
     * Build Gaussian pyramid
     */
    std::vector<RGBImage> buildGaussianPyramid(const RGBImage& image, int levels);
    
    /**
     * Build Laplacian pyramid
     */
    std::vector<RGBImage> buildLaplacianPyramid(const RGBImage& image, int levels);
    
    /**
     * Build Gaussian pyramid for grayscale
     */
    std::vector<GrayImage> buildGaussianPyramidGray(const GrayImage& image, int levels);
    
    /**
     * Collapse Laplacian pyramid to reconstruct image
     */
    RGBImage collapsePyramid(const std::vector<RGBImage>& pyramid);
    
    /**
     * Downsample image by 2x with Gaussian blur
     */
    RGBImage downsample(const RGBImage& image);
    
    /**
     * Downsample grayscale image by 2x
     */
    GrayImage downsampleGray(const GrayImage& image);
    
    /**
     * Upsample image by 2x with interpolation
     */
    RGBImage upsample(const RGBImage& image, int targetWidth, int targetHeight);
    
    /**
     * Apply Gaussian blur
     */
    RGBImage gaussianBlur(const RGBImage& image, float sigma);
    
    /**
     * Apply Gaussian blur to grayscale
     */
    GrayImage gaussianBlurGray(const GrayImage& image, float sigma);
};

} // namespace ultradetail

#endif // ULTRADETAIL_EXPOSURE_FUSION_H
