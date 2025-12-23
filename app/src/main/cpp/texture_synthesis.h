/**
 * texture_synthesis.h - Texture Synthesis for Detail Enhancement
 * 
 * Synthesizes plausible high-frequency details in areas lacking
 * original information (e.g., upscaled regions, low-texture areas).
 * 
 * Key techniques:
 * - Patch-based texture synthesis (Efros-Leung style)
 * - Guided synthesis using edge/gradient information
 * - Multi-scale detail transfer from similar regions
 * - Noise-aware detail injection
 * 
 * Used by ULTRA preset for maximum detail recovery.
 */

#ifndef ULTRADETAIL_TEXTURE_SYNTHESIS_H
#define ULTRADETAIL_TEXTURE_SYNTHESIS_H

#include "common.h"
#include <vector>
#include <functional>

namespace ultradetail {

/**
 * Texture patch for synthesis
 */
struct TexturePatch {
    int x, y;                     // Patch center location
    int size;                     // Patch size (odd number)
    float variance;               // Texture variance (detail level)
    float edgeMagnitude;          // Edge strength in patch
    
    TexturePatch() : x(0), y(0), size(7), variance(0), edgeMagnitude(0) {}
};

/**
 * Progress callback for texture synthesis
 * Parameters: (processed, total, avgDetail)
 */
using TextureSynthProgressCallback = std::function<void(int, int, float)>;

/**
 * Texture synthesis parameters
 */
struct TextureSynthParams {
    int patchSize = 7;            // Synthesis patch size
    int searchRadius = 32;        // Search radius for similar patches
    int numCandidates = 5;        // Number of candidate patches to consider
    float blendWeight = 0.5f;     // Blend weight for synthesized detail
    float varianceThreshold = 0.01f;  // Min variance to consider textured
    float edgeWeight = 0.3f;      // Weight for edge-guided synthesis
    bool useMultiScale = true;    // Multi-scale synthesis
    int numScales = 3;            // Number of scales for multi-scale
    TextureSynthProgressCallback progressCallback = nullptr;  // Optional progress callback
};

/**
 * Detail map for guiding synthesis
 */
struct DetailMap {
    GrayImage variance;           // Local variance map
    GrayImage edges;              // Edge magnitude map
    GrayImage confidence;         // Synthesis confidence map
    int width, height;
    
    DetailMap() : width(0), height(0) {}
    void resize(int w, int h) {
        width = w; height = h;
        variance.resize(w, h);
        edges.resize(w, h);
        confidence.resize(w, h);
    }
};

/**
 * Texture synthesis result
 */
struct TextureSynthResult {
    RGBImage synthesized;         // Output with synthesized details
    GrayImage detailMask;         // Where details were added
    float avgDetailAdded;         // Average detail magnitude added
    int patchesProcessed;         // Number of patches processed
    bool success;
    
    TextureSynthResult() : avgDetailAdded(0), patchesProcessed(0), success(false) {}
};

/**
 * Texture Synthesis Processor
 * 
 * Synthesizes plausible texture details using patch-based methods.
 */
class TextureSynthProcessor {
public:
    explicit TextureSynthProcessor(const TextureSynthParams& params = TextureSynthParams());
    
    /**
     * Synthesize texture details for an image
     * 
     * @param input Input image (potentially lacking detail)
     * @param reference Optional reference with more detail (e.g., original before upscale)
     * @return Image with synthesized details
     */
    TextureSynthResult synthesize(
        const RGBImage& input,
        const RGBImage* reference = nullptr
    );
    
    /**
     * Synthesize details guided by a detail map
     * 
     * @param input Input image
     * @param detailMap Map indicating where details are needed
     * @return Image with synthesized details
     */
    TextureSynthResult synthesizeGuided(
        const RGBImage& input,
        const DetailMap& detailMap
    );
    
    /**
     * Compute detail map for an image
     * Identifies regions needing texture synthesis
     */
    DetailMap computeDetailMap(const RGBImage& input);
    
    /**
     * Transfer texture from source to target regions
     * 
     * @param target Target image to enhance
     * @param source Source image with texture
     * @param mask Regions to transfer texture to
     * @return Enhanced target image
     */
    RGBImage transferTexture(
        const RGBImage& target,
        const RGBImage& source,
        const GrayImage& mask
    );
    
    /**
     * Analyze image quality to determine if texture synthesis is beneficial
     * Returns a score from 0.0 (no synthesis needed) to 1.0 (synthesis highly beneficial)
     */
    static float analyzeImageQuality(const RGBImage& input);
    
    /**
     * Update parameters
     */
    void setParams(const TextureSynthParams& params) { params_ = params; }
    const TextureSynthParams& getParams() const { return params_; }

private:
    TextureSynthParams params_;
    
    /**
     * Find best matching patch in search region
     */
    TexturePatch findBestPatch(
        const RGBImage& image,
        int targetX, int targetY,
        const RGBPixel& targetColor,
        float targetVariance
    );
    
    /**
     * Compute patch similarity (SSD)
     */
    float computePatchSSD(
        const RGBImage& image,
        int x1, int y1,
        int x2, int y2,
        int patchSize
    );
    
    /**
     * Compute local variance at a point
     */
    float computeLocalVariance(
        const RGBImage& image,
        int x, int y,
        int radius
    );
    
    /**
     * Blend synthesized patch into output
     */
    void blendPatch(
        RGBImage& output,
        const RGBImage& source,
        int targetX, int targetY,
        int sourceX, int sourceY,
        int patchSize,
        float weight
    );
    
    /**
     * Compute edge magnitude using Sobel
     */
    float computeEdgeMagnitude(
        const RGBImage& image,
        int x, int y
    );
};

} // namespace ultradetail

#endif // ULTRADETAIL_TEXTURE_SYNTHESIS_H
