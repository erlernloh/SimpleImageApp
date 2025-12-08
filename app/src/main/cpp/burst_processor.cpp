/**
 * burst_processor.cpp - Main burst processing pipeline implementation
 * 
 * Complete HDR+ style processing pipeline with progress reporting.
 */

#include "burst_processor.h"
#include "yuv_converter.h"
#include <chrono>

namespace ultradetail {

BurstProcessor::BurstProcessor(const BurstProcessorParams& params)
    : params_(params)
    , currentStage_(ProcessingStage::IDLE)
    , cancelled_(false) {
}

void BurstProcessor::reset() {
    currentStage_ = ProcessingStage::IDLE;
    cancelled_ = false;
}

int BurstProcessor::selectReferenceFrame(int numFrames) const {
    if (params_.referenceFrameIndex >= 0 && params_.referenceFrameIndex < numFrames) {
        return params_.referenceFrameIndex;
    }
    // Default: use middle frame
    return numFrames / 2;
}

void BurstProcessor::reportProgress(
    ProgressCallback callback,
    ProcessingStage stage,
    float progress,
    const char* message
) {
    currentStage_ = stage;
    if (callback) {
        callback(stage, progress, message);
    }
}

void BurstProcessor::convertFrames(
    const std::vector<YUVFrame>& yuvFrames,
    std::vector<RGBImage>& rgbFrames,
    std::vector<GrayImage>& grayFrames,
    ProgressCallback progressCallback
) {
    int numFrames = static_cast<int>(yuvFrames.size());
    rgbFrames.resize(numFrames);
    grayFrames.resize(numFrames);
    
    for (int i = 0; i < numFrames && !cancelled_; ++i) {
        float progress = static_cast<float>(i) / numFrames;
        reportProgress(progressCallback, ProcessingStage::CONVERTING_YUV, progress,
                      "Converting YUV to RGB...");
        
        yuvToRgbFloat(yuvFrames[i], rgbFrames[i]);
        yuvToGray(yuvFrames[i], grayFrames[i]);
        
        LOGD("Converted frame %d/%d: %dx%d",
             i + 1, numFrames, rgbFrames[i].width, rgbFrames[i].height);
    }
}

void BurstProcessor::alignFrames(
    const std::vector<GrayImage>& grayFrames,
    std::vector<RGBImage>& rgbFrames,
    std::vector<FrameAlignment>& alignments,
    int referenceIndex,
    ProgressCallback progressCallback
) {
    int numFrames = static_cast<int>(grayFrames.size());
    alignments.resize(numFrames);
    
    // Reference frame has identity alignment
    alignments[referenceIndex].isValid = true;
    alignments[referenceIndex].confidence = 1.0f;
    alignments[referenceIndex].averageMotion = 0.0f;
    
    // Choose alignment method based on mode
    if (params_.alignmentMode == AlignmentMode::DENSE_FLOW) {
        alignFramesDenseFlow(grayFrames, rgbFrames, alignments, referenceIndex, progressCallback);
    } else {
        alignFramesTileBased(grayFrames, rgbFrames, alignments, referenceIndex, progressCallback);
    }
}

void BurstProcessor::alignFramesTileBased(
    const std::vector<GrayImage>& grayFrames,
    std::vector<RGBImage>& rgbFrames,
    std::vector<FrameAlignment>& alignments,
    int referenceIndex,
    ProgressCallback progressCallback
) {
    int numFrames = static_cast<int>(grayFrames.size());
    
    // Create aligner with reference frame
    TileAligner aligner(params_.alignment);
    aligner.setReference(grayFrames[referenceIndex]);
    
    // Align other frames
    for (int i = 0; i < numFrames && !cancelled_; ++i) {
        if (i == referenceIndex) continue;
        
        float progress = static_cast<float>(i) / numFrames;
        reportProgress(progressCallback, ProcessingStage::ALIGNING_FRAMES, progress,
                      "Aligning frames (tile-based)...");
        
        alignments[i] = aligner.align(grayFrames[i]);
        
        // Warp RGB frame if alignment succeeded
        if (alignments[i].isValid) {
            RGBImage warped;
            aligner.warpImage(rgbFrames[i], alignments[i], warped);
            rgbFrames[i] = std::move(warped);
        }
        
        LOGD("Tile-aligned frame %d/%d: motion=%.2f, confidence=%.3f",
             i + 1, numFrames, alignments[i].averageMotion, alignments[i].confidence);
    }
}

void BurstProcessor::alignFramesDenseFlow(
    const std::vector<GrayImage>& grayFrames,
    std::vector<RGBImage>& rgbFrames,
    std::vector<FrameAlignment>& alignments,
    int referenceIndex,
    ProgressCallback progressCallback
) {
    int numFrames = static_cast<int>(grayFrames.size());
    
    // Create dense optical flow estimator
    DenseOpticalFlow flowEstimator(params_.opticalFlow);
    flowEstimator.setReference(grayFrames[referenceIndex]);
    
    LOGI("Using dense optical flow alignment (%d pyramid levels, window=%d)",
         params_.opticalFlow.pyramidLevels, params_.opticalFlow.windowSize);
    
    // Align other frames using dense flow
    for (int i = 0; i < numFrames && !cancelled_; ++i) {
        if (i == referenceIndex) continue;
        
        float progress = static_cast<float>(i) / numFrames;
        reportProgress(progressCallback, ProcessingStage::ALIGNING_FRAMES, progress,
                      "Aligning frames (dense flow)...");
        
        // Compute dense optical flow
        // TODO: Pass gyro homography here when available from JNI
        GyroHomography gyroInit;  // Empty for now
        DenseFlowResult flowResult = flowEstimator.computeFlow(grayFrames[i], gyroInit);
        
        if (flowResult.isValid) {
            // Warp RGB frame using flow
            RGBImage warped;
            flowEstimator.warpImage(rgbFrames[i], flowResult.flowField, warped);
            rgbFrames[i] = std::move(warped);
            
            // Convert flow to motion field for compatibility
            MotionField motionField = flowEstimator.flowToMotionField(
                flowResult.flowField, params_.alignment.tileSize);
            
            alignments[i].motionField = std::move(motionField);
            alignments[i].isValid = true;
            alignments[i].averageMotion = flowResult.averageFlow;
            alignments[i].confidence = flowResult.coverage;
            
            LOGD("Dense-flow aligned frame %d/%d: avgFlow=%.2f, coverage=%.1f%%",
                 i + 1, numFrames, flowResult.averageFlow, flowResult.coverage * 100.0f);
        } else {
            LOGW("Dense flow failed for frame %d, falling back to tile-based", i + 1);
            
            // Fallback to tile-based alignment
            TileAligner aligner(params_.alignment);
            aligner.setReference(grayFrames[referenceIndex]);
            alignments[i] = aligner.align(grayFrames[i]);
            
            if (alignments[i].isValid) {
                RGBImage warped;
                aligner.warpImage(rgbFrames[i], alignments[i], warped);
                rgbFrames[i] = std::move(warped);
            }
        }
    }
}

void BurstProcessor::process(
    const std::vector<YUVFrame>& frames,
    BurstProcessingResult& result,
    ProgressCallback progressCallback
) {
    auto startTime = std::chrono::high_resolution_clock::now();
    
    reset();
    result = BurstProcessingResult();
    
    // Validate input
    int numFrames = static_cast<int>(frames.size());
    if (numFrames < 2) {
        result.errorMessage = "Need at least 2 frames for burst processing";
        reportProgress(progressCallback, ProcessingStage::ERROR, 0, result.errorMessage.c_str());
        return;
    }
    
    LOGI("Starting burst processing with %d frames", numFrames);
    
    try {
        // Stage 1: Convert YUV to RGB and grayscale
        std::vector<RGBImage> rgbFrames;
        std::vector<GrayImage> grayFrames;
        convertFrames(frames, rgbFrames, grayFrames, progressCallback);
        
        if (cancelled_) {
            result.errorMessage = "Processing cancelled";
            return;
        }
        
        // Process RGB frames
        processRGB(rgbFrames, result, progressCallback);
        
        // Update timing
        auto endTime = std::chrono::high_resolution_clock::now();
        result.processingTimeMs = std::chrono::duration<float, std::milli>(endTime - startTime).count();
        
    } catch (const std::exception& e) {
        result.errorMessage = std::string("Processing failed: ") + e.what();
        reportProgress(progressCallback, ProcessingStage::ERROR, 0, result.errorMessage.c_str());
        LOGE("Burst processing failed: %s", e.what());
    }
}

void BurstProcessor::processRGB(
    const std::vector<RGBImage>& frames,
    BurstProcessingResult& result,
    ProgressCallback progressCallback
) {
    auto startTime = std::chrono::high_resolution_clock::now();
    
    if (currentStage_ == ProcessingStage::IDLE) {
        reset();
    }
    result = BurstProcessingResult();
    
    int numFrames = static_cast<int>(frames.size());
    if (numFrames < 1) {
        result.errorMessage = "No frames provided";
        reportProgress(progressCallback, ProcessingStage::ERROR, 0, result.errorMessage.c_str());
        return;
    }
    
    // Single frame: just copy
    if (numFrames == 1) {
        result.mergedImage = frames[0];
        result.numFramesUsed = 1;
        result.success = true;
        
        if (params_.computeDetailMask) {
            GrayImage luminance;
            rgbToLuminance(frames[0], luminance);
            EdgeDetector detector(params_.detailMask);
            detector.detectDetails(luminance, result.detailMask);
        }
        
        reportProgress(progressCallback, ProcessingStage::COMPLETE, 1.0f, "Complete");
        return;
    }
    
    LOGI("Processing %d RGB frames", numFrames);
    
    try {
        // Convert to grayscale for alignment
        std::vector<GrayImage> grayFrames(numFrames);
        reportProgress(progressCallback, ProcessingStage::BUILDING_PYRAMIDS, 0, "Building pyramids...");
        
        for (int i = 0; i < numFrames && !cancelled_; ++i) {
            rgbToLuminance(frames[i], grayFrames[i]);
        }
        
        if (cancelled_) {
            result.errorMessage = "Processing cancelled";
            return;
        }
        
        // Select reference frame
        int refIndex = selectReferenceFrame(numFrames);
        LOGD("Using frame %d as reference", refIndex);
        
        // Align frames
        std::vector<RGBImage> alignedFrames = frames; // Copy for warping
        std::vector<FrameAlignment> alignments;
        alignFrames(grayFrames, alignedFrames, alignments, refIndex, progressCallback);
        
        if (cancelled_) {
            result.errorMessage = "Processing cancelled";
            return;
        }
        
        // Count valid alignments
        int validCount = 0;
        for (const auto& align : alignments) {
            if (align.isValid) validCount++;
        }
        
        LOGD("Valid alignments: %d/%d", validCount, numFrames);
        
        // Check if MFSR is enabled and we have enough valid alignments
        if (params_.enableMFSR && validCount >= 3) {
            // Multi-Frame Super-Resolution path
            reportProgress(progressCallback, ProcessingStage::MULTI_FRAME_SR, 0, "Applying multi-frame super-resolution...");
            
            try {
                MultiFrameSR mfsr(params_.mfsr);
                MFSRResult mfsrResult;
                
                // Use original (non-warped) frames for MFSR - it handles alignment internally
                mfsr.process(frames, alignments, refIndex, mfsrResult,
                    [&progressCallback](const char* msg, float progress) {
                        if (progressCallback) {
                            progressCallback(ProcessingStage::MULTI_FRAME_SR, progress, msg);
                        }
                    }
                );
                
                if (mfsrResult.success) {
                    result.mergedImage = std::move(mfsrResult.upscaledImage);
                    result.mfsrApplied = true;
                    result.mfsrScaleFactor = params_.mfsr.scaleFactor;
                    result.mfsrCoverage = mfsrResult.coverage;
                    result.avgSubPixelShift = mfsrResult.averageSubPixelShift;
                    
                    LOGI("MFSR applied: %dx upscale, coverage=%.1f%%, avgShift=%.3f",
                         params_.mfsr.scaleFactor, mfsrResult.coverage * 100.0f,
                         mfsrResult.averageSubPixelShift);
                } else {
                    // MFSR failed, fall back to regular merge
                    LOGW("MFSR failed, falling back to regular merge");
                    FrameMerger merger(params_.merge);
                    merger.mergeWithWeights(alignedFrames, alignments, result.mergedImage);
                }
            } catch (const std::exception& e) {
                LOGE("MFSR exception: %s, falling back to regular merge", e.what());
                FrameMerger merger(params_.merge);
                merger.mergeWithWeights(alignedFrames, alignments, result.mergedImage);
            }
        } else {
            // Regular merge path
            reportProgress(progressCallback, ProcessingStage::MERGING_FRAMES, 0, "Merging frames...");
            
            FrameMerger merger(params_.merge);
            
            if (validCount >= numFrames / 2) {
                // Use weighted merge if we have alignment info
                merger.mergeWithWeights(alignedFrames, alignments, result.mergedImage);
            } else {
                // Fall back to simple merge
                merger.merge(alignedFrames, result.mergedImage);
            }
        }
        
        result.numFramesUsed = numFrames;
        
        if (cancelled_) {
            result.errorMessage = "Processing cancelled";
            return;
        }
        
        // Compute detail mask if requested
        if (params_.computeDetailMask) {
            reportProgress(progressCallback, ProcessingStage::COMPUTING_EDGES, 0, "Computing edges...");
            
            GrayImage luminance;
            rgbToLuminance(result.mergedImage, luminance);
            
            reportProgress(progressCallback, ProcessingStage::GENERATING_MASK, 0.5f, "Generating detail mask...");
            
            EdgeDetector detector(params_.detailMask);
            detector.detectDetails(luminance, result.detailMask);
        }
        
        result.success = true;
        
        auto endTime = std::chrono::high_resolution_clock::now();
        result.processingTimeMs = std::chrono::duration<float, std::milli>(endTime - startTime).count();
        
        // Store result for later retrieval
        lastResult_ = result;
        
        reportProgress(progressCallback, ProcessingStage::COMPLETE, 1.0f, "Complete");
        
        LOGI("Burst processing complete: %.1f ms, %d frames used",
             result.processingTimeMs, result.numFramesUsed);
        
    } catch (const std::exception& e) {
        result.errorMessage = std::string("Processing failed: ") + e.what();
        reportProgress(progressCallback, ProcessingStage::ERROR, 0, result.errorMessage.c_str());
        LOGE("Burst processing failed: %s", e.what());
    }
}

} // namespace ultradetail
