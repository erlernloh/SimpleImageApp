/**
 * phase_correlation.h - FFT-based Phase Correlation for Global Shift Detection
 * 
 * Fix #5 & Fix #1: Replace dense Lucas-Kanade with phase correlation for
 * coarse alignment. Phase correlation is:
 * - More robust to noise and illumination changes
 * - Faster than dense optical flow
 * - Better for detecting global translations
 * 
 * The approach:
 * 1. Use gyro homography for initial alignment estimate
 * 2. Use phase correlation to refine global shift
 * 3. Apply shift uniformly (no per-pixel flow needed for well-aligned frames)
 * 
 * Reference: Kuglin & Hines, "The Phase Correlation Image Alignment Method" (1975)
 */

#ifndef ULTRADETAIL_PHASE_CORRELATION_H
#define ULTRADETAIL_PHASE_CORRELATION_H

#include "common.h"
#include "optical_flow.h"  // For FlowField, FlowVector, GyroHomography
#include <vector>
#include <complex>

namespace ultradetail {

/**
 * Phase correlation result
 */
struct PhaseCorrelationResult {
    float shiftX;           // Sub-pixel shift in X
    float shiftY;           // Sub-pixel shift in Y
    float confidence;       // Peak sharpness (0-1)
    float peakValue;        // Correlation peak value
    bool isValid;
    
    PhaseCorrelationResult() 
        : shiftX(0), shiftY(0), confidence(0), peakValue(0), isValid(false) {}
};

/**
 * Phase correlation configuration
 */
struct PhaseCorrelationConfig {
    int windowSize = 256;       // FFT window size (power of 2)
    int numSamples = 4;         // Number of sample windows to average
    float subPixelRadius = 2.0f; // Radius for sub-pixel refinement
    bool useHanning = true;     // Apply Hanning window to reduce edge effects
};

/**
 * Phase Correlation Aligner
 * 
 * Uses FFT-based phase correlation to detect global translation between frames.
 * Much faster and more robust than dense optical flow for global shifts.
 */
class PhaseCorrelationAligner {
public:
    explicit PhaseCorrelationAligner(const PhaseCorrelationConfig& config = PhaseCorrelationConfig());
    ~PhaseCorrelationAligner();
    
    /**
     * Compute global shift between reference and target frame
     * 
     * @param reference Reference grayscale image
     * @param target Target grayscale image
     * @param gyroShiftX Initial X shift estimate from gyro (optional)
     * @param gyroShiftY Initial Y shift estimate from gyro (optional)
     * @return Phase correlation result with sub-pixel shift
     */
    PhaseCorrelationResult computeShift(
        const GrayImage& reference,
        const GrayImage& target,
        float gyroShiftX = 0.0f,
        float gyroShiftY = 0.0f
    );
    
    /**
     * Compute shift for a specific region (tile)
     */
    PhaseCorrelationResult computeShiftInRegion(
        const GrayImage& reference,
        const GrayImage& target,
        int regionX, int regionY,
        int regionWidth, int regionHeight
    );

private:
    PhaseCorrelationConfig config_;
    
    // Precomputed Hanning window
    std::vector<float> hanningWindow_;
    
    // FFT buffers (reused)
    std::vector<std::complex<float>> fftBuffer1_;
    std::vector<std::complex<float>> fftBuffer2_;
    std::vector<std::complex<float>> crossPowerSpectrum_;
    std::vector<float> correlationSurface_;
    
    /**
     * Initialize Hanning window
     */
    void initHanningWindow(int size);
    
    /**
     * Apply Hanning window to image patch
     */
    void applyHanningWindow(const float* input, float* output, int size);
    
    /**
     * Simple in-place 2D FFT (Cooley-Tukey radix-2)
     * Note: For production, consider using FFTW or kissfft
     */
    void fft2D(std::complex<float>* data, int size, bool inverse);
    
    /**
     * 1D FFT helper
     */
    void fft1D(std::complex<float>* data, int n, bool inverse);
    
    /**
     * Find peak in correlation surface with sub-pixel refinement
     */
    PhaseCorrelationResult findPeak(
        const std::vector<float>& surface,
        int size,
        float gyroShiftX,
        float gyroShiftY
    );
    
    /**
     * Sub-pixel refinement using parabolic fitting
     */
    void refineSubPixel(
        const std::vector<float>& surface,
        int size,
        int peakX, int peakY,
        float& subX, float& subY
    );
};

/**
 * Hybrid Aligner: Gyro + Phase Correlation + Sparse Flow
 * 
 * Fix #1: Reduces reliance on dense optical flow by using:
 * 1. Gyro homography for initial estimate
 * 2. Phase correlation for global shift refinement
 * 3. Sparse Lucas-Kanade only for local deformations (optional)
 */
class HybridAligner {
public:
    HybridAligner();
    
    /**
     * Compute alignment using hybrid approach
     * 
     * @param reference Reference frame
     * @param target Target frame
     * @param gyroHomography Gyro-based homography (optional)
     * @param useLocalRefinement Whether to use sparse LK for local refinement
     * @return Flow field (uniform shift or refined)
     */
    FlowField computeAlignment(
        const GrayImage& reference,
        const GrayImage& target,
        const GyroHomography* gyroHomography,
        bool useLocalRefinement = false
    );

private:
    PhaseCorrelationAligner phaseAligner_;
};

} // namespace ultradetail

#endif // ULTRADETAIL_PHASE_CORRELATION_H
