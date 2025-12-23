/**
 * texture_synthesis_tiled.h - Tiled Texture Synthesis with Hybrid CPU-GPU Processing
 * 
 * Implements parallel texture synthesis by splitting images into tiles and processing
 * odd tiles on CPU threads while even tiles are processed on GPU compute shaders.
 * 
 * Phase 2 of texture synthesis optimization.
 */

#ifndef ULTRADETAIL_TEXTURE_SYNTHESIS_TILED_H
#define ULTRADETAIL_TEXTURE_SYNTHESIS_TILED_H

#include "texture_synthesis.h"
#include <thread>
#include <mutex>
#include <condition_variable>
#include <queue>
#include <atomic>
#include <future>

namespace ultradetail {

/**
 * Texture synthesis tile region definition
 */
struct TextureTileRegion {
    int x, y;              // Top-left corner (including overlap)
    int width, height;     // Tile dimensions (including overlap)
    int coreX, coreY;      // Core region start (excluding overlap)
    int coreWidth, coreHeight;  // Core dimensions (excluding overlap)
    int tileIndex;         // Linear tile index
    bool useGPU;           // True if processed on GPU, false for CPU
    
    TextureTileRegion() : x(0), y(0), width(0), height(0), 
                   coreX(0), coreY(0), coreWidth(0), coreHeight(0),
                   tileIndex(0), useGPU(false) {}
};

/**
 * Tile schedule mode
 */
enum class TileScheduleMode {
    ALTERNATING,           // Odd tiles CPU, even tiles GPU (checkerboard)
    CPU_ONLY,              // All tiles on CPU (fallback)
    GPU_ONLY,              // All tiles on GPU (Phase 3)
    ADAPTIVE               // Dynamic based on load
};

/**
 * Tiled synthesis configuration
 */
struct TileSynthConfig {
    int tileSize = 512;              // Base tile size (core region)
    int overlap = 96;                // Overlap between tiles for blending (increased for smoother transitions)
    bool useGPU = true;              // Enable GPU processing
    int numCPUThreads = 4;           // CPU worker threads
    int numGPUStreams = 2;           // Concurrent GPU command streams
    TileScheduleMode mode = TileScheduleMode::ALTERNATING;
    TextureSynthParams synthParams;  // Base synthesis parameters
    TextureSynthProgressCallback progressCallback = nullptr;
    
    TileSynthConfig() {
        // Optimize for tiled processing
        synthParams.patchSize = 7;
        synthParams.searchRadius = 20;
        synthParams.blendWeight = 0.4f;
    }
};

/**
 * Texture synthesis tile processing result
 */
struct TextureTileResult {
    TextureTileRegion region;
    RGBImage synthesized;     // Synthesized tile (with overlap)
    GrayImage detailMask;     // Detail mask for this tile
    int patchesProcessed;
    float avgDetailAdded;
    bool success;
    
    TextureTileResult() : patchesProcessed(0), avgDetailAdded(0), success(false) {}
};

/**
 * Overlap blending region
 */
struct OverlapRegion {
    int x, y;              // Start position in output
    int width, height;     // Overlap dimensions
    int tile1Index;        // First tile index
    int tile2Index;        // Second tile index
    bool horizontal;       // True for horizontal overlap, false for vertical
    
    OverlapRegion() : x(0), y(0), width(0), height(0),
                      tile1Index(-1), tile2Index(-1), horizontal(true) {}
};

/**
 * CPU Tile Worker
 * Processes tiles using Phase 1 optimized synthesis on CPU threads
 */
class CPUTileWorker {
public:
    CPUTileWorker(int workerId, const TextureSynthParams& params);
    ~CPUTileWorker();
    
    /**
     * Process a single tile
     */
    TextureTileResult processTile(const RGBImage& input, const TextureTileRegion& region);
    
    /**
     * Get worker statistics
     */
    int getTilesProcessed() const { return tilesProcessed_; }
    
private:
    int workerId_;
    TextureSynthParams params_;
    TextureSynthProcessor processor_;
    std::atomic<int> tilesProcessed_;
};

/**
 * Tile Grid Layout
 * Manages tile splitting and overlap regions
 */
class TileGridLayout {
public:
    TileGridLayout(int imageWidth, int imageHeight, const TileSynthConfig& config);
    
    /**
     * Get all tile regions
     */
    const std::vector<TextureTileRegion>& getTiles() const { return tiles_; }
    
    /**
     * Get overlap regions for blending
     */
    const std::vector<OverlapRegion>& getOverlaps() const { return overlaps_; }
    
    /**
     * Get grid dimensions
     */
    int getNumTilesX() const { return numTilesX_; }
    int getNumTilesY() const { return numTilesY_; }
    int getTotalTiles() const { return tiles_.size(); }
    
private:
    void computeTileLayout(int imageWidth, int imageHeight, const TileSynthConfig& config);
    void computeOverlapRegions();
    
    int numTilesX_;
    int numTilesY_;
    std::vector<TextureTileRegion> tiles_;
    std::vector<OverlapRegion> overlaps_;
};

/**
 * Tiled Texture Synthesis Processor
 * Main class for hybrid CPU-GPU tiled processing
 */
class TiledTextureSynthProcessor {
public:
    explicit TiledTextureSynthProcessor(const TileSynthConfig& config);
    ~TiledTextureSynthProcessor();
    
    /**
     * Synthesize texture using tiled approach
     * 
     * @param input Input image
     * @return Synthesized result
     */
    TextureSynthResult synthesize(const RGBImage& input);
    
    /**
     * Check if GPU is available and initialized
     */
    bool isGPUAvailable() const { return gpuAvailable_; }
    
    /**
     * Get configuration
     */
    const TileSynthConfig& getConfig() const { return config_; }
    
private:
    /**
     * Initialize CPU thread pool
     */
    void initializeCPUWorkers();
    
    /**
     * Initialize GPU compute resources
     */
    bool initializeGPU();
    
    /**
     * Shutdown workers and cleanup
     */
    void shutdown();
    
    /**
     * Process tiles in parallel (CPU + GPU)
     */
    std::vector<TextureTileResult> processTilesParallel(
        const RGBImage& input,
        const TileGridLayout& layout
    );
    
    /**
     * Process a single tile on CPU
     */
    TextureTileResult processTileCPU(const RGBImage& input, const TextureTileRegion& region);
    
    /**
     * Process a single tile on GPU
     */
    TextureTileResult processTileGPU(const RGBImage& input, const TextureTileRegion& region);
    
    /**
     * Blend tiles into final output
     */
    RGBImage blendTiles(
        const std::vector<TextureTileResult>& tiles,
        const TileGridLayout& layout,
        int outputWidth,
        int outputHeight
    );
    
    /**
     * Blend overlap region between two tiles
     */
    void blendOverlap(
        RGBImage& output,
        const TextureTileResult& tile1,
        const TextureTileResult& tile2,
        const OverlapRegion& overlap
    );
    
    TileSynthConfig config_;
    bool gpuAvailable_;
    
    // CPU thread pool
    std::vector<std::unique_ptr<CPUTileWorker>> cpuWorkers_;
    std::vector<std::thread> cpuThreads_;
    std::queue<std::function<void()>> taskQueue_;
    std::mutex queueMutex_;
    std::condition_variable queueCV_;
    std::atomic<bool> shutdown_;
    
    // GPU resources (placeholder for Phase 2.3-2.5)
    void* gpuContext_;  // Will be GLContext or similar
    
    // Statistics
    std::atomic<int> tilesProcessedCPU_;
    std::atomic<int> tilesProcessedGPU_;
};

/**
 * Extract tile from image with overlap
 */
RGBImage extractTile(const RGBImage& image, const TextureTileRegion& region);

/**
 * Insert tile into output image (core region only)
 */
void insertTileCore(RGBImage& output, const RGBImage& tile, const TextureTileRegion& region);

} // namespace ultradetail

#endif // ULTRADETAIL_TEXTURE_SYNTHESIS_TILED_H
