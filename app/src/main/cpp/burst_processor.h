/**
 * burst_processor.h - Main burst processing pipeline
 * 
 * Orchestrates the complete HDR+ style burst capture processing:
 * alignment, merging, and output generation.
 */

#ifndef ULTRADETAIL_BURST_PROCESSOR_H
#define ULTRADETAIL_BURST_PROCESSOR_H

#include "common.h"
#include "yuv_converter.h"
#include "alignment.h"
#include "optical_flow.h"
#include "merge.h"
#include "edge_detection.h"
#include "mfsr.h"
#include <vector>
#include <functional>
#include <string>

namespace ultradetail {

/**
 * Processing stage enumeration
 */
enum class ProcessingStage {
    IDLE,
    CONVERTING_YUV,
    BUILDING_PYRAMIDS,
    ALIGNING_FRAMES,
    MERGING_FRAMES,
    COMPUTING_EDGES,
    GENERATING_MASK,
    MULTI_FRAME_SR,
    COMPLETE,
    ERROR
};

/**
 * Progress callback type
 * Parameters: stage, progress (0-1), message
 */
using ProgressCallback = std::function<void(ProcessingStage, float, const char*)>;

/**
 * Alignment mode selection
 */
enum class AlignmentMode {
    TILE_BASED,      // Original HDR+ style tile-based alignment
    DENSE_FLOW,      // Dense optical flow (per-pixel)
    HYBRID           // Tile-based with optical flow refinement
};

/**
 * Burst processing parameters
 */
struct BurstProcessorParams {
    AlignmentParams alignment;
    OpticalFlowParams opticalFlow;
    MergeParams merge;
    DetailMaskParams detailMask;
    MFSRParams mfsr;
    int referenceFrameIndex = 0;  // Which frame to use as reference (0 = first, -1 = middle)
    bool computeDetailMask = true; // Whether to compute detail mask
    bool enableMFSR = false;       // Whether to enable multi-frame super-resolution
    AlignmentMode alignmentMode = AlignmentMode::TILE_BASED;  // Alignment algorithm to use
};

/**
 * Burst processing result
 */
struct BurstProcessingResult {
    RGBImage mergedImage;         // Final merged RGB image (or MFSR upscaled)
    DetailMask detailMask;        // Detail mask for SR
    float processingTimeMs;       // Total processing time
    int numFramesUsed;            // Number of frames successfully used
    bool success;                 // Whether processing succeeded
    std::string errorMessage;     // Error message if failed
    
    // MFSR specific results
    bool mfsrApplied = false;     // Whether MFSR was applied
    int mfsrScaleFactor = 1;      // Scale factor used (1 = no MFSR)
    float mfsrCoverage = 0;       // MFSR pixel coverage percentage
    float avgSubPixelShift = 0;   // Average sub-pixel shift detected
    
    BurstProcessingResult() : processingTimeMs(0), numFramesUsed(0), success(false) {}
};

/**
 * Main burst processor class
 * 
 * Handles the complete pipeline from YUV frames to merged output.
 */
class BurstProcessor {
public:
    /**
     * Constructor
     * 
     * @param params Processing parameters
     */
    explicit BurstProcessor(const BurstProcessorParams& params = BurstProcessorParams());
    
    /**
     * Process a burst of YUV frames
     * 
     * @param frames Vector of YUV frames
     * @param result Output processing result
     * @param progressCallback Optional progress callback
     */
    void process(
        const std::vector<YUVFrame>& frames,
        BurstProcessingResult& result,
        ProgressCallback progressCallback = nullptr
    );
    
    /**
     * Process pre-converted RGB frames
     * 
     * @param frames Vector of RGB frames
     * @param result Output processing result
     * @param progressCallback Optional progress callback
     */
    void processRGB(
        const std::vector<RGBImage>& frames,
        BurstProcessingResult& result,
        ProgressCallback progressCallback = nullptr
    );
    
    /**
     * Get current processing stage
     */
    ProcessingStage getCurrentStage() const { return currentStage_; }
    
    /**
     * Cancel ongoing processing
     */
    void cancel() { cancelled_ = true; }
    
    /**
     * Check if processing was cancelled
     */
    bool isCancelled() const { return cancelled_; }
    
    /**
     * Reset processor state
     */
    void reset();
    
    /**
     * Get the last processing result
     */
    const BurstProcessingResult& getLastResult() const { return lastResult_; }
    
    /**
     * Check if there's a valid result available
     */
    bool hasResult() const { return lastResult_.success; }

private:
    BurstProcessorParams params_;
    ProcessingStage currentStage_;
    bool cancelled_;
    BurstProcessingResult lastResult_;  // Store last result for retrieval
    
    /**
     * Convert YUV frames to RGB
     */
    void convertFrames(
        const std::vector<YUVFrame>& yuvFrames,
        std::vector<RGBImage>& rgbFrames,
        std::vector<GrayImage>& grayFrames,
        ProgressCallback progressCallback
    );
    
    /**
     * Align frames to reference (dispatches to tile-based or dense flow)
     */
    void alignFrames(
        const std::vector<GrayImage>& grayFrames,
        std::vector<RGBImage>& rgbFrames,
        std::vector<FrameAlignment>& alignments,
        int referenceIndex,
        ProgressCallback progressCallback
    );
    
    /**
     * Align frames using tile-based method (original HDR+ style)
     */
    void alignFramesTileBased(
        const std::vector<GrayImage>& grayFrames,
        std::vector<RGBImage>& rgbFrames,
        std::vector<FrameAlignment>& alignments,
        int referenceIndex,
        ProgressCallback progressCallback
    );
    
    /**
     * Align frames using dense optical flow
     */
    void alignFramesDenseFlow(
        const std::vector<GrayImage>& grayFrames,
        std::vector<RGBImage>& rgbFrames,
        std::vector<FrameAlignment>& alignments,
        int referenceIndex,
        ProgressCallback progressCallback
    );
    
    /**
     * Select reference frame index
     */
    int selectReferenceFrame(int numFrames) const;
    
    /**
     * Report progress
     */
    void reportProgress(
        ProgressCallback callback,
        ProcessingStage stage,
        float progress,
        const char* message
    );
};

} // namespace ultradetail

#endif // ULTRADETAIL_BURST_PROCESSOR_H
