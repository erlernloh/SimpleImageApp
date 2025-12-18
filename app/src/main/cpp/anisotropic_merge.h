/**
 * anisotropic_merge.h - Edge-Aware Anisotropic Merging
 * 
 * Implements Google's key innovation for Super Res Zoom:
 * - Merge pixels along edge directions, not across them
 * - Preserves sharp edges while reducing noise in flat areas
 * - Uses structure tensor for robust edge orientation estimation
 * 
 * Used by both MAX and ULTRA presets for improved detail preservation.
 */

#ifndef ULTRADETAIL_ANISOTROPIC_MERGE_H
#define ULTRADETAIL_ANISOTROPIC_MERGE_H

#include "common.h"
#include <vector>

namespace ultradetail {

/**
 * Structure tensor for local image structure analysis
 */
struct StructureTensor {
    float Ixx;          // Gradient correlation xx
    float Ixy;          // Gradient correlation xy
    float Iyy;          // Gradient correlation yy
    float lambda1;      // Larger eigenvalue (edge strength)
    float lambda2;      // Smaller eigenvalue (corner/noise)
    float angle;        // Dominant edge orientation (radians)
    float anisotropy;   // 0 = isotropic, 1 = strongly directional
    
    StructureTensor() : Ixx(0), Ixy(0), Iyy(0), lambda1(0), lambda2(0), angle(0), anisotropy(0) {}
    
    void computeEigen() {
        // Eigenvalue decomposition of 2x2 symmetric matrix
        float trace = Ixx + Iyy;
        float det = Ixx * Iyy - Ixy * Ixy;
        float disc = std::sqrt(std::max(0.0f, trace * trace / 4.0f - det));
        
        lambda1 = trace / 2.0f + disc;
        lambda2 = trace / 2.0f - disc;
        
        // Edge orientation (perpendicular to gradient direction)
        if (std::abs(Ixy) > 1e-6f) {
            angle = 0.5f * std::atan2(2.0f * Ixy, Ixx - Iyy);
        } else {
            angle = (Ixx > Iyy) ? 0.0f : M_PI / 2.0f;
        }
        
        // Anisotropy measure
        if (lambda1 + lambda2 > 1e-6f) {
            anisotropy = (lambda1 - lambda2) / (lambda1 + lambda2);
        } else {
            anisotropy = 0.0f;
        }
    }
};

/**
 * Anisotropic kernel for directional blending
 */
struct AnisotropicKernel {
    static const int SIZE = 7;
    float weights[SIZE][SIZE];
    float angle;
    float anisotropy;
    
    AnisotropicKernel() : angle(0), anisotropy(0) {
        // Initialize to uniform
        float w = 1.0f / (SIZE * SIZE);
        for (int y = 0; y < SIZE; ++y) {
            for (int x = 0; x < SIZE; ++x) {
                weights[y][x] = w;
            }
        }
    }
    
    /**
     * Build anisotropic kernel from structure tensor
     * 
     * @param st Structure tensor with computed eigenvalues
     * @param sigma Base sigma for Gaussian
     * @param elongation How much to stretch along edge direction
     */
    void buildFromStructure(const StructureTensor& st, float sigma = 1.5f, float elongation = 3.0f);
};

/**
 * Anisotropic merge parameters
 */
struct AnisotropicMergeParams {
    int windowSize = 5;              // Window for structure tensor computation
    float integrationSigma = 1.5f;   // Gaussian sigma for tensor integration
    float kernelSigma = 1.5f;        // Base sigma for anisotropic kernel
    float elongation = 3.0f;         // Kernel elongation along edges
    float noiseThreshold = 0.01f;    // Below this, use isotropic kernel
    bool adaptiveStrength = true;    // Vary anisotropy based on edge strength
};

/**
 * Structure tensor field for an image
 */
using StructureTensorField = ImageBuffer<StructureTensor>;

/**
 * Anisotropic Merge Processor
 * 
 * Merges multiple frames using edge-aware directional kernels.
 */
class AnisotropicMergeProcessor {
public:
    explicit AnisotropicMergeProcessor(const AnisotropicMergeParams& params = AnisotropicMergeParams());
    
    /**
     * Compute structure tensor field for an image
     * 
     * @param input Grayscale input image
     * @return Structure tensor at each pixel
     */
    StructureTensorField computeStructureTensors(const GrayImage& input);
    
    /**
     * Merge multiple grayscale frames using anisotropic kernels
     * 
     * @param frames Input frames (aligned)
     * @param referenceIdx Index of reference frame
     * @param output Output merged image
     */
    void mergeGray(
        const std::vector<GrayImage>& frames,
        int referenceIdx,
        GrayImage& output
    );
    
    /**
     * Merge multiple RGB frames using anisotropic kernels
     * 
     * @param frames Input frames (aligned)
     * @param referenceIdx Index of reference frame
     * @param output Output merged image
     */
    void mergeRGB(
        const std::vector<RGBImage>& frames,
        int referenceIdx,
        RGBImage& output
    );
    
    /**
     * Apply anisotropic filtering to a single image (denoising)
     * 
     * @param input Input image
     * @param output Filtered output
     */
    void filterGray(const GrayImage& input, GrayImage& output);
    void filterRGB(const RGBImage& input, RGBImage& output);
    
    /**
     * Update parameters
     */
    void setParams(const AnisotropicMergeParams& params) { params_ = params; }
    const AnisotropicMergeParams& getParams() const { return params_; }

private:
    AnisotropicMergeParams params_;
    
    /**
     * Compute image gradients using Sobel
     */
    void computeGradients(const GrayImage& input, GrayImage& gradX, GrayImage& gradY);
    
    /**
     * Integrate structure tensor over window
     */
    void integrateStructureTensor(
        const GrayImage& gradX,
        const GrayImage& gradY,
        StructureTensorField& tensorField
    );
    
    /**
     * Apply anisotropic kernel at a single pixel
     */
    float applyKernel(
        const GrayImage& image,
        int cx, int cy,
        const AnisotropicKernel& kernel
    );
    
    /**
     * Apply anisotropic kernel for RGB at a single pixel
     */
    RGBPixel applyKernelRGB(
        const RGBImage& image,
        int cx, int cy,
        const AnisotropicKernel& kernel
    );
};

} // namespace ultradetail

#endif // ULTRADETAIL_ANISOTROPIC_MERGE_H
