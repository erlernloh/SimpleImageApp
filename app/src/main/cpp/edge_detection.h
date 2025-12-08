/**
 * edge_detection.h - Edge-aware detail mask generation
 * 
 * Implements Sobel/Scharr edge detection and tile-based
 * detail classification for selective super-resolution.
 */

#ifndef ULTRADETAIL_EDGE_DETECTION_H
#define ULTRADETAIL_EDGE_DETECTION_H

#include "common.h"

namespace ultradetail {

/**
 * Edge detection operator type
 */
enum class EdgeOperator {
    SOBEL,      // Standard Sobel 3x3
    SCHARR,     // Scharr 3x3 (more accurate)
    PREWITT     // Prewitt 3x3
};

/**
 * Detail mask parameters
 */
struct DetailMaskParams {
    EdgeOperator edgeOperator = EdgeOperator::SCHARR;
    int tileSize = DETAIL_TILE_SIZE;              // Tile size for classification
    float detailThreshold = DETAIL_TILE_THRESHOLD; // Threshold for detail classification
    bool applyMorphology = true;                   // Apply dilation/erosion
    int dilationRadius = 1;                        // Dilation radius for expanding detail regions
};

/**
 * Detail mask result
 */
struct DetailMask {
    ByteImage tileMask;           // Per-tile mask (255 = detail, 0 = smooth)
    GrayImage edgeMagnitude;      // Full-resolution edge magnitude
    int numDetailTiles;           // Number of detail-rich tiles
    int numSmoothTiles;           // Number of smooth tiles
    float averageEdgeMagnitude;   // Average edge magnitude
    
    DetailMask() : numDetailTiles(0), numSmoothTiles(0), averageEdgeMagnitude(0) {}
};

/**
 * Edge detector and detail mask generator
 */
class EdgeDetector {
public:
    /**
     * Constructor
     * 
     * @param params Detection parameters
     */
    explicit EdgeDetector(const DetailMaskParams& params = DetailMaskParams());
    
    /**
     * Compute edge magnitude map
     * 
     * @param luminance Input grayscale/luminance image
     * @param output Output edge magnitude image
     */
    void computeEdgeMagnitude(const GrayImage& luminance, GrayImage& output);
    
    /**
     * Generate detail mask from edge magnitude
     * 
     * @param edgeMagnitude Edge magnitude image
     * @param result Output detail mask result
     */
    void generateDetailMask(const GrayImage& edgeMagnitude, DetailMask& result);
    
    /**
     * Convenience method: compute edges and generate mask in one call
     * 
     * @param luminance Input grayscale image
     * @param result Output detail mask result
     */
    void detectDetails(const GrayImage& luminance, DetailMask& result);
    
    /**
     * Check if a tile is marked as detail-rich
     * 
     * @param mask Detail mask
     * @param tileX Tile X index
     * @param tileY Tile Y index
     * @return true if tile is detail-rich
     */
    static bool isDetailTile(const DetailMask& mask, int tileX, int tileY);
    
    /**
     * Get tile coordinates for a pixel position
     * 
     * @param x Pixel X coordinate
     * @param y Pixel Y coordinate
     * @param tileX Output tile X index
     * @param tileY Output tile Y index
     */
    void getTileForPixel(int x, int y, int& tileX, int& tileY) const;

private:
    DetailMaskParams params_;
    
    /**
     * Apply Sobel operator
     */
    void applySobel(const GrayImage& input, GrayImage& gradX, GrayImage& gradY);
    
    /**
     * Apply Scharr operator
     */
    void applyScharr(const GrayImage& input, GrayImage& gradX, GrayImage& gradY);
    
    /**
     * Apply Prewitt operator
     */
    void applyPrewitt(const GrayImage& input, GrayImage& gradX, GrayImage& gradY);
    
    /**
     * Compute gradient magnitude from X and Y gradients
     */
    void computeMagnitude(const GrayImage& gradX, const GrayImage& gradY, GrayImage& magnitude);
    
    /**
     * Apply morphological dilation to tile mask
     */
    void dilateMask(ByteImage& mask, int radius);
    
    /**
     * Apply morphological erosion to tile mask
     */
    void erodeMask(ByteImage& mask, int radius);
};

} // namespace ultradetail

#endif // ULTRADETAIL_EDGE_DETECTION_H
