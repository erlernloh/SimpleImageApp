/**
 * merge.h - Robust frame merging for HDR+ style processing
 * 
 * Implements trimmed mean and M-estimator based merging with
 * optional Wiener filtering for noise reduction.
 */

#ifndef ULTRADETAIL_MERGE_H
#define ULTRADETAIL_MERGE_H

#include "common.h"
#include "alignment.h"
#include <vector>

namespace ultradetail {

/**
 * Merge method enumeration
 */
enum class MergeMethod {
    AVERAGE,        // Simple averaging
    TRIMMED_MEAN,   // Trimmed mean (removes outliers)
    M_ESTIMATOR,    // Robust M-estimator (Huber or Tukey)
    MEDIAN          // Median merge
};

/**
 * Merge parameters
 */
struct MergeParams {
    MergeMethod method = MergeMethod::TRIMMED_MEAN;
    float trimRatio = TRIMMED_MEAN_RATIO;     // Ratio to trim from each end
    float huberDelta = 1.0f;                   // Huber M-estimator threshold
    bool applyWienerFilter = true;             // Apply Wiener denoising
    float wienerNoiseVar = WIENER_NOISE_VAR;   // Assumed noise variance
    int wienerWindowSize = 5;                  // Wiener filter window size
};

/**
 * Frame merger for burst processing
 * 
 * Merges aligned burst frames into a single denoised output
 * using robust statistical methods.
 */
class FrameMerger {
public:
    /**
     * Constructor
     * 
     * @param params Merge parameters
     */
    explicit FrameMerger(const MergeParams& params = MergeParams());
    
    /**
     * Merge aligned frames
     * 
     * @param frames Vector of aligned RGB frames
     * @param output Output merged RGB image
     */
    void merge(const std::vector<RGBImage>& frames, RGBImage& output);
    
    /**
     * Merge with alignment information for weighted merging
     * 
     * @param frames Vector of RGB frames
     * @param alignments Vector of alignment results
     * @param output Output merged RGB image
     */
    void mergeWithWeights(
        const std::vector<RGBImage>& frames,
        const std::vector<FrameAlignment>& alignments,
        RGBImage& output
    );
    
    /**
     * Apply Wiener filter to merged image
     * 
     * @param input Input image
     * @param output Output filtered image
     */
    void applyWienerFilter(const RGBImage& input, RGBImage& output);

private:
    MergeParams params_;
    
    /**
     * Compute trimmed mean for a set of values
     */
    float trimmedMean(std::vector<float>& values);
    
    /**
     * Compute M-estimator (Huber) for a set of values
     */
    float huberMean(const std::vector<float>& values);
    
    /**
     * Compute median for a set of values
     */
    float median(std::vector<float>& values);
    
    /**
     * Estimate local noise variance for Wiener filter
     */
    float estimateLocalVariance(const RGBImage& image, int x, int y, int channel);
};

/**
 * Temporal noise model for adaptive merging
 */
class NoiseModel {
public:
    /**
     * Estimate noise level from a single frame
     */
    static float estimateNoise(const GrayImage& image);
    
    /**
     * Compute per-pixel weights based on noise and motion
     */
    static void computeWeights(
        const RGBImage& reference,
        const RGBImage& frame,
        const FrameAlignment& alignment,
        GrayImage& weights
    );
};

} // namespace ultradetail

#endif // ULTRADETAIL_MERGE_H
