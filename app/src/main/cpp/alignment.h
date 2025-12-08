/**
 * alignment.h - HDR+ style tile-based alignment
 * 
 * Implements coarse-to-fine tile alignment using Gaussian pyramids
 * with small translational motion estimation per tile.
 */

#ifndef ULTRADETAIL_ALIGNMENT_H
#define ULTRADETAIL_ALIGNMENT_H

#include "common.h"
#include "pyramid.h"

namespace ultradetail {

/**
 * Alignment parameters
 */
struct AlignmentParams {
    int tileSize = ALIGNMENT_TILE_SIZE;      // Tile size in pixels
    int searchRadius = SEARCH_RADIUS;         // Search window radius
    int pyramidLevels = MAX_PYRAMID_LEVELS;   // Number of pyramid levels
    float convergenceThreshold = 0.5f;        // Motion convergence threshold
    bool useSubpixel = false;                 // Enable sub-pixel refinement
};

/**
 * Alignment result for a single frame
 */
struct FrameAlignment {
    MotionField motionField;                  // Per-tile motion vectors
    float averageMotion;                      // Average motion magnitude
    float confidence;                         // Alignment confidence [0, 1]
    bool isValid;                             // Whether alignment succeeded
    
    FrameAlignment() : averageMotion(0), confidence(0), isValid(false) {}
};

/**
 * Tile-based frame aligner
 * 
 * Aligns burst frames to a reference frame using HDR+ style
 * tile-based alignment with coarse-to-fine pyramid search.
 */
class TileAligner {
public:
    /**
     * Constructor
     * 
     * @param params Alignment parameters
     */
    explicit TileAligner(const AlignmentParams& params = AlignmentParams());
    
    /**
     * Set reference frame for alignment
     * 
     * @param reference Grayscale reference frame
     */
    void setReference(const GrayImage& reference);
    
    /**
     * Align a frame to the reference
     * 
     * @param frame Grayscale frame to align
     * @return Alignment result with motion field
     */
    FrameAlignment align(const GrayImage& frame);
    
    /**
     * Apply alignment to warp an RGB image
     * 
     * @param input Input RGB image
     * @param alignment Alignment result
     * @param output Output warped image
     */
    void warpImage(const RGBImage& input, const FrameAlignment& alignment, RGBImage& output);
    
    /**
     * Get number of tiles in X direction
     */
    int numTilesX() const { return numTilesX_; }
    
    /**
     * Get number of tiles in Y direction
     */
    int numTilesY() const { return numTilesY_; }

private:
    AlignmentParams params_;
    GaussianPyramid refPyramid_;
    int numTilesX_;
    int numTilesY_;
    int imageWidth_;
    int imageHeight_;
    
    /**
     * Compute motion vector for a single tile at given pyramid level
     */
    MotionVector alignTile(
        const GrayImage& ref,
        const GrayImage& frame,
        int tileX, int tileY,
        int tileSize,
        const MotionVector& initialMotion
    );
    
    /**
     * Compute SAD (Sum of Absolute Differences) for a tile
     */
    float computeTileSAD(
        const GrayImage& ref,
        const GrayImage& frame,
        int refX, int refY,
        int frameX, int frameY,
        int tileSize
    );
    
    /**
     * Refine motion to sub-pixel accuracy
     */
    MotionVector refineSubpixel(
        const GrayImage& ref,
        const GrayImage& frame,
        int tileX, int tileY,
        int tileSize,
        const MotionVector& integerMotion
    );
};

} // namespace ultradetail

#endif // ULTRADETAIL_ALIGNMENT_H
