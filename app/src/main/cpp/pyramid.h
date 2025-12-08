/**
 * pyramid.h - Gaussian pyramid construction
 * 
 * Builds multi-scale image pyramids for coarse-to-fine alignment.
 */

#ifndef ULTRADETAIL_PYRAMID_H
#define ULTRADETAIL_PYRAMID_H

#include "common.h"
#include <vector>

namespace ultradetail {

/**
 * Gaussian pyramid for grayscale images
 */
class GaussianPyramid {
public:
    /**
     * Build pyramid from grayscale image
     * 
     * @param image Source grayscale image
     * @param numLevels Number of pyramid levels (including base)
     */
    void build(const GrayImage& image, int numLevels = MAX_PYRAMID_LEVELS);
    
    /**
     * Get pyramid level
     * 
     * @param level Level index (0 = original resolution)
     * @return Reference to image at that level
     */
    const GrayImage& getLevel(int level) const;
    
    /**
     * Get number of levels
     */
    int numLevels() const { return static_cast<int>(levels_.size()); }
    
    /**
     * Get width at specified level
     */
    int widthAt(int level) const { return levels_[level].width; }
    
    /**
     * Get height at specified level
     */
    int heightAt(int level) const { return levels_[level].height; }
    
    /**
     * Clear pyramid data
     */
    void clear() { levels_.clear(); }

private:
    std::vector<GrayImage> levels_;
    
    /**
     * Downsample image by factor of 2 with Gaussian blur
     */
    static void downsample2x(const GrayImage& src, GrayImage& dst);
};

/**
 * Gaussian pyramid for RGB images
 */
class RGBPyramid {
public:
    void build(const RGBImage& image, int numLevels = MAX_PYRAMID_LEVELS);
    const RGBImage& getLevel(int level) const;
    int numLevels() const { return static_cast<int>(levels_.size()); }
    int widthAt(int level) const { return levels_[level].width; }
    int heightAt(int level) const { return levels_[level].height; }
    void clear() { levels_.clear(); }

private:
    std::vector<RGBImage> levels_;
    static void downsample2x(const RGBImage& src, RGBImage& dst);
};

/**
 * Laplacian pyramid for detail preservation
 */
class LaplacianPyramid {
public:
    /**
     * Build Laplacian pyramid from grayscale image
     */
    void build(const GrayImage& image, int numLevels = MAX_PYRAMID_LEVELS);
    
    /**
     * Reconstruct image from Laplacian pyramid
     */
    void reconstruct(GrayImage& output) const;
    
    /**
     * Get detail level (Laplacian)
     */
    const GrayImage& getDetail(int level) const { return details_[level]; }
    
    /**
     * Get residual (lowest frequency)
     */
    const GrayImage& getResidual() const { return residual_; }
    
    int numLevels() const { return static_cast<int>(details_.size()); }
    void clear() { details_.clear(); residual_ = GrayImage(); }

private:
    std::vector<GrayImage> details_;
    GrayImage residual_;
    
    static void upsample2x(const GrayImage& src, GrayImage& dst, int targetW, int targetH);
};

} // namespace ultradetail

#endif // ULTRADETAIL_PYRAMID_H
