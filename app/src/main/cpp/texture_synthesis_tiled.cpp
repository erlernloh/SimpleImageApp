/**
 * texture_synthesis_tiled.cpp - Tiled Texture Synthesis Implementation
 * 
 * Implements hybrid CPU-GPU tiled processing for texture synthesis.
 */

#include "texture_synthesis_tiled.h"
#include <algorithm>
#include <cmath>

namespace ultradetail {

// ============================================================================
// CPUTileWorker Implementation
// ============================================================================

CPUTileWorker::CPUTileWorker(int workerId, const TextureSynthParams& params)
    : workerId_(workerId)
    , params_(params)
    , processor_(params)
    , tilesProcessed_(0) {
    LOGD("CPUTileWorker %d initialized", workerId_);
}

CPUTileWorker::~CPUTileWorker() {
    LOGD("CPUTileWorker %d processed %d tiles", workerId_, tilesProcessed_.load());
}

TextureTileResult CPUTileWorker::processTile(const RGBImage& input, const TextureTileRegion& region) {
    TextureTileResult result;
    result.region = region;
    
    // Extract tile with overlap
    RGBImage tileImage = extractTile(input, region);
    
    if (tileImage.width == 0 || tileImage.height == 0) {
        LOGE("CPUTileWorker %d: Failed to extract tile %d", workerId_, region.tileIndex);
        return result;
    }
    
    // Compute detail map for this tile
    DetailMap detailMap = processor_.computeDetailMap(tileImage);
    
    // Synthesize using Phase 1 optimized algorithm
    TextureSynthResult synthResult = processor_.synthesizeGuided(tileImage, detailMap);
    
    if (synthResult.success) {
        result.synthesized = synthResult.synthesized;
        result.detailMask = synthResult.detailMask;
        result.patchesProcessed = synthResult.patchesProcessed;
        result.avgDetailAdded = synthResult.avgDetailAdded;
        result.success = true;
        tilesProcessed_++;
        
        LOGD("CPUTileWorker %d: Tile %d processed (%d patches)",
             workerId_, region.tileIndex, synthResult.patchesProcessed);
    } else {
        LOGW("CPUTileWorker %d: Tile %d synthesis failed", workerId_, region.tileIndex);
    }
    
    return result;
}

// ============================================================================
// TileGridLayout Implementation
// ============================================================================

TileGridLayout::TileGridLayout(int imageWidth, int imageHeight, const TileSynthConfig& config)
    : numTilesX_(0)
    , numTilesY_(0) {
    computeTileLayout(imageWidth, imageHeight, config);
    computeOverlapRegions();
    
    LOGD("TileGridLayout: %dx%d grid, %d total tiles, %d overlaps",
         numTilesX_, numTilesY_, (int)tiles_.size(), (int)overlaps_.size());
}

void TileGridLayout::computeTileLayout(int imageWidth, int imageHeight, const TileSynthConfig& config) {
    int coreSize = config.tileSize;
    int overlap = config.overlap;
    int tileSize = coreSize + 2 * overlap;  // Total tile size with overlap on both sides
    
    // Calculate grid dimensions
    numTilesX_ = std::max(1, (imageWidth + coreSize - 1) / coreSize);
    numTilesY_ = std::max(1, (imageHeight + coreSize - 1) / coreSize);
    
    tiles_.clear();
    tiles_.reserve(numTilesX_ * numTilesY_);
    
    for (int ty = 0; ty < numTilesY_; ++ty) {
        for (int tx = 0; tx < numTilesX_; ++tx) {
            TextureTileRegion tile;
            tile.tileIndex = ty * numTilesX_ + tx;
            
            // Core region (no overlap)
            tile.coreX = tx * coreSize;
            tile.coreY = ty * coreSize;
            tile.coreWidth = std::min(coreSize, imageWidth - tile.coreX);
            tile.coreHeight = std::min(coreSize, imageHeight - tile.coreY);
            
            // Extended region (with overlap)
            tile.x = std::max(0, tile.coreX - overlap);
            tile.y = std::max(0, tile.coreY - overlap);
            int endX = std::min(imageWidth, tile.coreX + tile.coreWidth + overlap);
            int endY = std::min(imageHeight, tile.coreY + tile.coreHeight + overlap);
            tile.width = endX - tile.x;
            tile.height = endY - tile.y;
            
            // Assign to CPU or GPU based on schedule mode
            if (config.mode == TileScheduleMode::ALTERNATING) {
                // Checkerboard pattern: (tx + ty) % 2 determines CPU/GPU
                tile.useGPU = ((tx + ty) % 2 == 0) && config.useGPU;
            } else if (config.mode == TileScheduleMode::GPU_ONLY) {
                tile.useGPU = config.useGPU;
            } else {
                tile.useGPU = false;  // CPU_ONLY or ADAPTIVE (default to CPU)
            }
            
            tiles_.push_back(tile);
        }
    }
}

void TileGridLayout::computeOverlapRegions() {
    overlaps_.clear();
    
    // Horizontal overlaps (between horizontally adjacent tiles)
    for (int ty = 0; ty < numTilesY_; ++ty) {
        for (int tx = 0; tx < numTilesX_ - 1; ++tx) {
            int idx1 = ty * numTilesX_ + tx;
            int idx2 = ty * numTilesX_ + (tx + 1);
            
            const TextureTileRegion& tile1 = tiles_[idx1];
            const TextureTileRegion& tile2 = tiles_[idx2];
            
            // Overlap is between tile1's right edge and tile2's left edge
            int overlapX = tile2.coreX;
            int overlapWidth = (tile1.x + tile1.width) - overlapX;
            
            if (overlapWidth > 0) {
                OverlapRegion overlap;
                overlap.x = overlapX;
                overlap.y = std::max(tile1.y, tile2.y);  // Use common y range
                overlap.width = overlapWidth;
                overlap.height = std::min(tile1.y + tile1.height, tile2.y + tile2.height) - overlap.y;
                overlap.tile1Index = idx1;
                overlap.tile2Index = idx2;
                overlap.horizontal = true;
                overlaps_.push_back(overlap);
                
                LOGD("Horizontal overlap: tiles %d-%d, x=%d, y=%d, w=%d, h=%d",
                     idx1, idx2, overlap.x, overlap.y, overlap.width, overlap.height);
            }
        }
    }
    
    // Vertical overlaps (between vertically adjacent tiles)
    for (int ty = 0; ty < numTilesY_ - 1; ++ty) {
        for (int tx = 0; tx < numTilesX_; ++tx) {
            int idx1 = ty * numTilesX_ + tx;
            int idx2 = (ty + 1) * numTilesX_ + tx;
            
            const TextureTileRegion& tile1 = tiles_[idx1];
            const TextureTileRegion& tile2 = tiles_[idx2];
            
            // Overlap is between tile1's bottom edge and tile2's top edge
            int overlapY = tile2.coreY;
            int overlapHeight = (tile1.y + tile1.height) - overlapY;
            
            if (overlapHeight > 0) {
                OverlapRegion overlap;
                overlap.x = std::max(tile1.x, tile2.x);  // Use common x range
                overlap.y = overlapY;
                overlap.width = std::min(tile1.x + tile1.width, tile2.x + tile2.width) - overlap.x;
                overlap.height = overlapHeight;
                overlap.tile1Index = idx1;
                overlap.tile2Index = idx2;
                overlap.horizontal = false;
                overlaps_.push_back(overlap);
                
                LOGD("Vertical overlap: tiles %d-%d, x=%d, y=%d, w=%d, h=%d",
                     idx1, idx2, overlap.x, overlap.y, overlap.width, overlap.height);
            }
        }
    }
}

// ============================================================================
// TiledTextureSynthProcessor Implementation
// ============================================================================

TiledTextureSynthProcessor::TiledTextureSynthProcessor(const TileSynthConfig& config)
    : config_(config)
    , gpuAvailable_(false)
    , gpuContext_(nullptr)
    , shutdown_(false)
    , tilesProcessedCPU_(0)
    , tilesProcessedGPU_(0) {
    
    LOGD("TiledTextureSynthProcessor: Initializing with %d CPU threads, GPU=%s",
         config_.numCPUThreads, config_.useGPU ? "enabled" : "disabled");
    
    // Initialize CPU workers
    initializeCPUWorkers();
    
    // Initialize GPU if requested
    if (config_.useGPU) {
        gpuAvailable_ = initializeGPU();
        if (!gpuAvailable_) {
            LOGW("TiledTextureSynthProcessor: GPU initialization failed, using CPU only");
            config_.mode = TileScheduleMode::CPU_ONLY;
        }
    }
}

TiledTextureSynthProcessor::~TiledTextureSynthProcessor() {
    shutdown();
    LOGD("TiledTextureSynthProcessor: Processed %d CPU tiles, %d GPU tiles",
         tilesProcessedCPU_.load(), tilesProcessedGPU_.load());
}

void TiledTextureSynthProcessor::initializeCPUWorkers() {
    int numThreads = std::max(1, config_.numCPUThreads);
    
    // Create workers
    cpuWorkers_.reserve(numThreads);
    for (int i = 0; i < numThreads; ++i) {
        cpuWorkers_.push_back(
            std::make_unique<CPUTileWorker>(i, config_.synthParams)
        );
    }
    
    // Create thread pool
    cpuThreads_.reserve(numThreads);
    for (int i = 0; i < numThreads; ++i) {
        cpuThreads_.emplace_back([this, i]() {
            LOGD("CPU thread %d started", i);
            
            while (!shutdown_.load()) {
                std::function<void()> task;
                
                {
                    std::unique_lock<std::mutex> lock(queueMutex_);
                    queueCV_.wait(lock, [this]() {
                        return shutdown_.load() || !taskQueue_.empty();
                    });
                    
                    if (shutdown_.load() && taskQueue_.empty()) {
                        break;
                    }
                    
                    if (!taskQueue_.empty()) {
                        task = std::move(taskQueue_.front());
                        taskQueue_.pop();
                    }
                }
                
                if (task) {
                    task();
                }
            }
            
            LOGD("CPU thread %d stopped", i);
        });
    }
    
    LOGD("CPU thread pool initialized with %d threads", numThreads);
}

bool TiledTextureSynthProcessor::initializeGPU() {
    // Phase 2.3-2.5: GPU initialization will be implemented here
    // For now, return false to use CPU-only mode
    LOGD("GPU initialization: Not yet implemented (Phase 2.3-2.5)");
    return false;
}

void TiledTextureSynthProcessor::shutdown() {
    if (shutdown_.load()) return;
    
    LOGD("TiledTextureSynthProcessor: Shutting down...");
    
    // Signal shutdown
    shutdown_.store(true);
    queueCV_.notify_all();
    
    // Wait for threads to finish
    for (auto& thread : cpuThreads_) {
        if (thread.joinable()) {
            thread.join();
        }
    }
    
    cpuThreads_.clear();
    cpuWorkers_.clear();
    
    LOGD("TiledTextureSynthProcessor: Shutdown complete");
}

TextureSynthResult TiledTextureSynthProcessor::synthesize(const RGBImage& input) {
    TextureSynthResult result;
    
    if (input.width == 0 || input.height == 0) {
        LOGE("TiledTextureSynthProcessor: Invalid input");
        return result;
    }
    
    auto startTime = std::chrono::high_resolution_clock::now();
    
    // Create tile layout
    TileGridLayout layout(input.width, input.height, config_);
    LOGD("Processing %d tiles (%dx%d grid)", layout.getTotalTiles(),
         layout.getNumTilesX(), layout.getNumTilesY());
    
    // Process tiles in parallel
    std::vector<TextureTileResult> tileResults = processTilesParallel(input, layout);
    
    auto tilesTime = std::chrono::high_resolution_clock::now();
    auto tilesMs = std::chrono::duration_cast<std::chrono::milliseconds>(tilesTime - startTime).count();
    LOGD("All tiles processed in %lldms", tilesMs);
    
    // Blend tiles into final output
    LOGD("BLENDING: About to call blendTiles with %d tiles, output size %dx%d", 
         (int)tileResults.size(), input.width, input.height);
    result.synthesized = blendTiles(tileResults, layout, input.width, input.height);
    LOGD("BLENDING: blendTiles returned, output size %dx%d", 
         result.synthesized.width, result.synthesized.height);
    result.detailMask.resize(input.width, input.height);
    
    // Aggregate statistics
    int totalPatches = 0;
    float totalDetail = 0;
    int successCount = 0;
    
    for (const auto& tile : tileResults) {
        if (tile.success) {
            totalPatches += tile.patchesProcessed;
            totalDetail += tile.avgDetailAdded * tile.patchesProcessed;
            successCount++;
        }
    }
    
    result.patchesProcessed = totalPatches;
    result.avgDetailAdded = totalPatches > 0 ? totalDetail / totalPatches : 0;
    result.success = (successCount == tileResults.size());
    
    auto endTime = std::chrono::high_resolution_clock::now();
    auto totalMs = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime).count();
    
    LOGD("TiledTextureSynth: Total time %lldms, %d patches, avg detail=%.3f",
         totalMs, totalPatches, result.avgDetailAdded);
    LOGD("TiledTextureSynth: CPU tiles=%d, GPU tiles=%d",
         tilesProcessedCPU_.load(), tilesProcessedGPU_.load());
    
    return result;
}

std::vector<TextureTileResult> TiledTextureSynthProcessor::processTilesParallel(
    const RGBImage& input,
    const TileGridLayout& layout
) {
    const auto& tiles = layout.getTiles();
    std::vector<TextureTileResult> results(tiles.size());
    std::vector<std::future<TextureTileResult>> futures;
    futures.reserve(tiles.size());
    
    // Submit all tiles as tasks
    for (size_t i = 0; i < tiles.size(); ++i) {
        const TextureTileRegion& region = tiles[i];
        
        if (region.useGPU && gpuAvailable_) {
            // GPU tile - process directly (for now, fallback to CPU)
            // Phase 2.3-2.5 will implement actual GPU processing
            futures.push_back(std::async(std::launch::async, [this, &input, region]() {
                return processTileCPU(input, region);  // Temporary fallback
            }));
        } else {
            // CPU tile - submit to thread pool
            futures.push_back(std::async(std::launch::async, [this, &input, region]() {
                return processTileCPU(input, region);
            }));
        }
    }
    
    // Collect results with progress reporting
    int completed = 0;
    for (size_t i = 0; i < futures.size(); ++i) {
        results[i] = futures[i].get();
        completed++;
        
        // Report progress on every tile for responsive UI feedback
        if (config_.progressCallback) {
            config_.progressCallback(completed, (int)futures.size(), 0.0f);
        }
    }
    
    return results;
}

TextureTileResult TiledTextureSynthProcessor::processTileCPU(
    const RGBImage& input,
    const TextureTileRegion& region
) {
    // Select worker (round-robin based on tile index)
    int workerIdx = region.tileIndex % cpuWorkers_.size();
    TextureTileResult result = cpuWorkers_[workerIdx]->processTile(input, region);
    
    if (result.success) {
        tilesProcessedCPU_++;
    }
    
    return result;
}

TextureTileResult TiledTextureSynthProcessor::processTileGPU(
    const RGBImage& input,
    const TextureTileRegion& region
) {
    // Phase 2.3-2.5: GPU tile processing will be implemented here
    // For now, fallback to CPU
    LOGD("GPU tile processing not yet implemented, using CPU fallback");
    return processTileCPU(input, region);
}

RGBImage TiledTextureSynthProcessor::blendTiles(
    const std::vector<TextureTileResult>& tiles,
    const TileGridLayout& layout,
    int outputWidth,
    int outputHeight
) {
    RGBImage output;
    output.resize(outputWidth, outputHeight);
    
    LOGD("Blending %d tiles into %dx%d output using weighted accumulation", (int)tiles.size(), outputWidth, outputHeight);
    
    // Accumulation buffers for weighted blending
    std::vector<float> accumR(outputWidth * outputHeight, 0.0f);
    std::vector<float> accumG(outputWidth * outputHeight, 0.0f);
    std::vector<float> accumB(outputWidth * outputHeight, 0.0f);
    std::vector<float> accumW(outputWidth * outputHeight, 0.0f);
    
    // Blend all tiles with distance-based weights
    int tilesBlended = 0;
    const float overlapSize = 96.0f; // Match overlap size
    
    for (const auto& tile : tiles) {
        if (!tile.success) continue;
        
        const auto& region = tile.region;
        
        // Process entire tile region (including overlaps)
        for (int ty = 0; ty < tile.synthesized.height; ++ty) {
            int oy = region.y + ty;
            if (oy < 0 || oy >= outputHeight) continue;
            
            for (int tx = 0; tx < tile.synthesized.width; ++tx) {
                int ox = region.x + tx;
                if (ox < 0 || ox >= outputWidth) continue;
                
                // Compute distance from each edge
                float distLeft = static_cast<float>(tx);
                float distRight = static_cast<float>(tile.synthesized.width - 1 - tx);
                float distTop = static_cast<float>(ty);
                float distBottom = static_cast<float>(tile.synthesized.height - 1 - ty);
                
                // Compute weight for each edge (1.0 if beyond overlap, ramps 0->1 within overlap)
                float wLeft = std::min(1.0f, distLeft / overlapSize);
                float wRight = std::min(1.0f, distRight / overlapSize);
                float wTop = std::min(1.0f, distTop / overlapSize);
                float wBottom = std::min(1.0f, distBottom / overlapSize);
                
                // Combined weight is product of all edge weights (smooth corners)
                float weight = wLeft * wRight * wTop * wBottom;
                
                // Ensure minimum weight so every pixel contributes
                weight = std::max(0.001f, weight);
                
                // Accumulate weighted pixel values
                int idx = oy * outputWidth + ox;
                const RGBPixel& pixel = tile.synthesized.at(tx, ty);
                accumR[idx] += pixel.r * weight;
                accumG[idx] += pixel.g * weight;
                accumB[idx] += pixel.b * weight;
                accumW[idx] += weight;
            }
        }
        tilesBlended++;
    }
    
    LOGD("Accumulated contributions from %d tiles", tilesBlended);
    
    // Normalize and write output (RGBPixel uses float 0.0-1.0, not int 0-255)
    int pixelsWritten = 0;
    for (int y = 0; y < outputHeight; ++y) {
        for (int x = 0; x < outputWidth; ++x) {
            int idx = y * outputWidth + x;
            
            if (accumW[idx] > 0.0f) {
                float invW = 1.0f / accumW[idx];
                output.at(x, y).r = std::min(1.0f, std::max(0.0f, accumR[idx] * invW));
                output.at(x, y).g = std::min(1.0f, std::max(0.0f, accumG[idx] * invW));
                output.at(x, y).b = std::min(1.0f, std::max(0.0f, accumB[idx] * invW));
                pixelsWritten++;
            } else {
                output.at(x, y) = {0, 0, 0};
            }
        }
    }
    
    LOGD("Blending complete: %d pixels written", pixelsWritten);
    
    return output;
}

void TiledTextureSynthProcessor::blendOverlap(
    RGBImage& output,
    const TextureTileResult& tile1,
    const TextureTileResult& tile2,
    const OverlapRegion& overlap
) {
    // Validate overlap dimensions
    if (overlap.width <= 0 || overlap.height <= 0) {
        LOGW("Invalid overlap dimensions: %dx%d", overlap.width, overlap.height);
        return;
    }
    
    // Linear blending across entire overlap region
    // Simple linear interpolation from tile1 (weight=0) to tile2 (weight=1)
    int pixelsBlended = 0;
    
    for (int dy = 0; dy < overlap.height; ++dy) {
        int oy = overlap.y + dy;
        if (oy < 0 || oy >= output.height) continue;
        
        for (int dx = 0; dx < overlap.width; ++dx) {
            int ox = overlap.x + dx;
            if (ox < 0 || ox >= output.width) continue;
            
            // Get pixels from both tiles
            int tx1 = ox - tile1.region.x;
            int ty1 = oy - tile1.region.y;
            int tx2 = ox - tile2.region.x;
            int ty2 = oy - tile2.region.y;
            
            // Validate coordinates
            if (tx1 < 0 || tx1 >= tile1.synthesized.width ||
                ty1 < 0 || ty1 >= tile1.synthesized.height ||
                tx2 < 0 || tx2 >= tile2.synthesized.width ||
                ty2 < 0 || ty2 >= tile2.synthesized.height) {
                continue;
            }
            
            // Compute linear blend weight across overlap
            float weight;
            if (overlap.horizontal) {
                // Horizontal overlap: blend linearly from left (0) to right (1)
                weight = static_cast<float>(dx) / std::max(1.0f, static_cast<float>(overlap.width - 1));
            } else {
                // Vertical overlap: blend linearly from top (0) to bottom (1)
                weight = static_cast<float>(dy) / std::max(1.0f, static_cast<float>(overlap.height - 1));
            }
            
            // Apply smoothstep for C2 continuity
            weight = weight * weight * (3.0f - 2.0f * weight);
            
            const RGBPixel& p1 = tile1.synthesized.at(tx1, ty1);
            const RGBPixel& p2 = tile2.synthesized.at(tx2, ty2);
            
            // Blend with clamping (RGBPixel uses float 0.0-1.0, not int 0-255)
            RGBPixel& out = output.at(ox, oy);
            out.r = std::min(1.0f, std::max(0.0f, p1.r * (1.0f - weight) + p2.r * weight));
            out.g = std::min(1.0f, std::max(0.0f, p1.g * (1.0f - weight) + p2.g * weight));
            out.b = std::min(1.0f, std::max(0.0f, p1.b * (1.0f - weight) + p2.b * weight));
            pixelsBlended++;
        }
    }
    
    LOGD("Blended %d pixels in overlap region %dx%d", pixelsBlended, overlap.width, overlap.height);
}

// ============================================================================
// Utility Functions
// ============================================================================

RGBImage extractTile(const RGBImage& image, const TextureTileRegion& region) {
    RGBImage tile;
    tile.resize(region.width, region.height);
    
    for (int y = 0; y < region.height; ++y) {
        int sy = region.y + y;
        if (sy < 0 || sy >= image.height) continue;
        
        for (int x = 0; x < region.width; ++x) {
            int sx = region.x + x;
            if (sx < 0 || sx >= image.width) continue;
            
            tile.at(x, y) = image.at(sx, sy);
        }
    }
    
    return tile;
}

void insertTileCore(RGBImage& output, const RGBImage& tile, const TextureTileRegion& region) {
    // Insert only the core region (no overlap)
    int coreStartX = region.coreX - region.x;
    int coreStartY = region.coreY - region.y;
    
    for (int y = 0; y < region.coreHeight; ++y) {
        int oy = region.coreY + y;
        int ty = coreStartY + y;
        
        if (oy < 0 || oy >= output.height || ty < 0 || ty >= tile.height) continue;
        
        for (int x = 0; x < region.coreWidth; ++x) {
            int ox = region.coreX + x;
            int tx = coreStartX + x;
            
            if (ox < 0 || ox >= output.width || tx < 0 || tx >= tile.width) continue;
            
            output.at(ox, oy) = tile.at(tx, ty);
        }
    }
}

} // namespace ultradetail
