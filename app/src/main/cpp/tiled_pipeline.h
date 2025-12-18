/**
 * tiled_pipeline.h - Tile-Based Processing Pipeline
 * 
 * Implements a memory-safe tile-by-tile processing strategy for the
 * entire MFSR pipeline. This ensures RAM usage stays constant (~200MB)
 * regardless of image size, preventing Android from killing the app.
 * 
 * Pipeline per tile:
 * 1. Extract tile crops from all burst frames
 * 2. Compute dense optical flow for tile region
 * 3. Classical MFSR accumulation to 2x resolution
 * 4. Neural refinement (optional)
 * 5. Write to output and release tile memory
 */

#ifndef ULTRADETAIL_TILED_PIPELINE_H
#define ULTRADETAIL_TILED_PIPELINE_H

#include "common.h"
#include "optical_flow.h"
#include "phase_correlation.h"
#include "mfsr.h"
#include <vector>
#include <functional>
#include <memory>

namespace ultradetail {

/**
 * Tile processing configuration
 */
struct TilePipelineConfig {
    // Tile dimensions (input space)
    int tileWidth = 256;          // Tile width in input pixels
    int tileHeight = 256;         // Tile height in input pixels
    int overlap = 32;             // Overlap between tiles for seamless blending
    
    // Scale factor (2x recommended, 4x experimental)
    int scaleFactor = 2;
    
    // MFSR parameters
    MFSRParams mfsrParams;
    
    // Optical flow parameters
    OpticalFlowParams flowParams;
    
    // Robustness settings
    // Fix #4: Adaptive robustness - threshold is now dynamically adjusted based on
    // local flow confidence. High confidence regions use gentler rejection,
    // low confidence regions use more aggressive rejection.
    enum class RobustnessMethod {
        NONE,           // Simple averaging
        HUBER,          // Huber loss (mild outlier rejection)
        TUKEY           // Tukey biweight (aggressive outlier rejection)
    };
    RobustnessMethod robustness = RobustnessMethod::HUBER;  // HUBER is gentler than TUKEY for low-diversity frames
    float robustnessThreshold = 0.8f;  // Base threshold - actual threshold is adaptive: base * (0.5 + 0.5 * flowConfidence)
    
    // Memory limits
    size_t maxMemoryMB = 200;     // Target max memory per tile
    
    // Processing options
    bool useGyroInit = true;      // Use gyro for flow initialization
    bool enableRefinement = true; // Apply neural refinement
    
    // Fix #5 & #1: Alignment method selection
    enum class AlignmentMethod {
        DENSE_OPTICAL_FLOW,     // Original Lucas-Kanade (slower, more accurate for local deformations)
        PHASE_CORRELATION,      // FFT-based global shift (faster, more robust for translations)
        HYBRID                  // Gyro + Phase correlation + optional sparse flow (recommended)
    };
    AlignmentMethod alignmentMethod = AlignmentMethod::HYBRID;  // Default to hybrid for best quality/speed
    bool useLocalRefinement = true;   // Use tile-based phase correlation for local refinement
    
    TilePipelineConfig() {
        mfsrParams.scaleFactor = scaleFactor;
        // Optimized for speed while maintaining quality
        flowParams.pyramidLevels = 2;  // Fewer levels for faster processing
        flowParams.windowSize = 9;     // Smaller window for speed
        flowParams.maxIterations = 5;  // Fewer iterations
    }
};

/**
 * Tile region in image coordinates
 */
struct TileRegion {
    int x, y;           // Top-left corner in input image
    int width, height;  // Tile dimensions
    int padLeft, padTop, padRight, padBottom;  // Padding for overlap
    
    // Output region (scaled)
    int outX, outY;
    int outWidth, outHeight;
    
    TileRegion() : x(0), y(0), width(0), height(0),
                   padLeft(0), padTop(0), padRight(0), padBottom(0),
                   outX(0), outY(0), outWidth(0), outHeight(0) {}
};

/**
 * Tile processing result
 */
struct TileResult {
    RGBImage outputTile;          // Upscaled tile (2x or 4x)
    float averageFlow;            // Average optical flow magnitude
    float coverage;               // Percentage of pixels with valid data
    int framesContributed;        // Number of frames that contributed
    bool success;
    
    TileResult() : averageFlow(0), coverage(0), framesContributed(0), success(false) {}
};

/**
 * Progress callback for tiled pipeline
 * Parameters: current tile, total tiles, stage description, overall progress [0-1]
 */
using TilePipelineProgress = std::function<void(int, int, const char*, float)>;

/**
 * Fallback reason when MFSR fails
 */
enum class FallbackReason {
    NONE,                   // No fallback needed
    EXCESSIVE_MOTION,       // Too much motion between frames
    LOW_COVERAGE,           // Not enough valid pixels
    FLOW_FAILED,            // Optical flow computation failed
    MEMORY_EXCEEDED,        // Memory limit exceeded
    ALIGNMENT_FAILED        // Frame alignment failed
};

/**
 * Full pipeline result
 */
struct PipelineResult {
    RGBImage outputImage;         // Final upscaled image
    int inputWidth, inputHeight;  // Original dimensions
    int outputWidth, outputHeight; // Output dimensions
    int tilesProcessed;           // Number of tiles processed
    int tilesFailed;              // Number of tiles that fell back
    float averageFlow;            // Average optical flow across image
    float processingTimeMs;       // Total processing time
    FallbackReason fallbackReason; // If fallback was used
    bool usedFallback;            // Whether fallback was triggered
    bool success;
    
    PipelineResult() : inputWidth(0), inputHeight(0), 
                       outputWidth(0), outputHeight(0),
                       tilesProcessed(0), tilesFailed(0),
                       averageFlow(0), processingTimeMs(0),
                       fallbackReason(FallbackReason::NONE),
                       usedFallback(false), success(false) {}
};

/**
 * Tiled MFSR Pipeline
 * 
 * Processes burst frames tile-by-tile to maintain constant memory usage.
 * Each tile goes through: Flow -> MFSR -> Refinement independently.
 */
class TiledMFSRPipeline {
public:
    /**
     * Constructor
     * 
     * @param config Pipeline configuration
     */
    explicit TiledMFSRPipeline(const TilePipelineConfig& config = TilePipelineConfig());
    
    /**
     * Process burst frames to produce upscaled image
     * 
     * @param frames Input RGB frames (already converted from YUV)
     * @param grayFrames Grayscale versions for flow computation
     * @param referenceIndex Index of reference frame
     * @param gyroHomographies Optional gyro-based homographies for flow init
     * @param result Output pipeline result
     * @param progressCallback Optional progress callback
     */
    void process(
        const std::vector<RGBImage>& frames,
        const std::vector<GrayImage>& grayFrames,
        int referenceIndex,
        const std::vector<GyroHomography>* gyroHomographies,
        PipelineResult& result,
        TilePipelineProgress progressCallback = nullptr
    );
    
    /**
     * Compute tile grid for given image dimensions
     * 
     * @param width Image width
     * @param height Image height
     * @return Vector of tile regions
     */
    std::vector<TileRegion> computeTileGrid(int width, int height) const;
    
    /**
     * Process a single tile
     * 
     * @param frames Input RGB frames
     * @param grayFrames Grayscale frames
     * @param tile Tile region to process
     * @param referenceIndex Reference frame index
     * @param gyroHomographies Optional gyro homographies
     * @param result Output tile result
     */
    void processTile(
        const std::vector<RGBImage>& frames,
        const std::vector<GrayImage>& grayFrames,
        const TileRegion& tile,
        int referenceIndex,
        const std::vector<GyroHomography>* gyroHomographies,
        TileResult& result
    );
    
    /**
     * Check if MFSR should fall back to single-frame upscale
     * 
     * @param frames Input frames
     * @param grayFrames Grayscale frames
     * @param referenceIndex Reference frame index
     * @return Fallback reason (NONE if MFSR should proceed)
     */
    FallbackReason checkFallbackConditions(
        const std::vector<RGBImage>& frames,
        const std::vector<GrayImage>& grayFrames,
        int referenceIndex
    );
    
    /**
     * Perform single-frame fallback upscale
     * 
     * @param referenceFrame Reference frame to upscale
     * @param result Output result
     */
    void fallbackUpscale(
        const RGBImage& referenceFrame,
        PipelineResult& result
    );

private:
    TilePipelineConfig config_;
    
    // Optical flow processor (reused across tiles) - used when alignmentMethod == DENSE_OPTICAL_FLOW
    std::unique_ptr<DenseOpticalFlow> flowProcessor_;
    
    // Hybrid aligner (Fix #5 & #1) - used when alignmentMethod == HYBRID or PHASE_CORRELATION
    std::unique_ptr<HybridAligner> hybridAligner_;
    
    // MFSR processor (reused across tiles)
    std::unique_ptr<MultiFrameSR> mfsrProcessor_;
    
    /**
     * Extract tile crop from image with padding
     */
    void extractTileCrop(
        const RGBImage& source,
        const TileRegion& tile,
        RGBImage& crop
    );
    
    void extractTileCrop(
        const GrayImage& source,
        const TileRegion& tile,
        GrayImage& crop
    );
    
    /**
     * Compute robustness weight for a pixel
     */
    float computeRobustnessWeight(
        const RGBPixel& pixel,
        const RGBPixel& reference,
        float flowConfidence
    );
    
    /**
     * Blend tile into output image with overlap handling
     */
    void blendTileToOutput(
        const RGBImage& tile,
        const TileRegion& region,
        RGBImage& output,
        ImageBuffer<float>& weightMap
    );
    
    /**
     * Compute blending weight for overlap regions
     */
    float computeBlendWeight(int x, int y, int width, int height, int overlap);
    
    /**
     * Estimate global motion to check for excessive shake
     */
    float estimateGlobalMotion(
        const GrayImage& reference,
        const GrayImage& frame
    );
};

/**
 * Utility: Compute Tukey biweight function
 */
inline float tukeyBiweight(float residual, float c) {
    float u = residual / c;
    if (std::abs(u) > 1.0f) return 0.0f;
    float t = 1.0f - u * u;
    return t * t;
}

/**
 * Utility: Compute Huber weight function
 */
inline float huberWeight(float residual, float delta) {
    float absR = std::abs(residual);
    if (absR <= delta) return 1.0f;
    return delta / absR;
}

} // namespace ultradetail

#endif // ULTRADETAIL_TILED_PIPELINE_H
