/**
 * phase_correlation.cpp - FFT-based Phase Correlation Implementation
 * 
 * Fix #5 & Fix #1: Phase correlation for robust global shift detection.
 */

#include "phase_correlation.h"
#include <cmath>
#include <algorithm>
#include <android/log.h>

#undef LOG_TAG
#define LOG_TAG "PhaseCorrelation"

namespace ultradetail {

PhaseCorrelationAligner::PhaseCorrelationAligner(const PhaseCorrelationConfig& config)
    : config_(config) {
    
    // Ensure window size is power of 2
    int size = config_.windowSize;
    if ((size & (size - 1)) != 0) {
        // Round up to next power of 2
        size = 1;
        while (size < config_.windowSize) size <<= 1;
        config_.windowSize = size;
    }
    
    // Initialize Hanning window
    initHanningWindow(config_.windowSize);
    
    // Allocate FFT buffers
    int bufferSize = config_.windowSize * config_.windowSize;
    fftBuffer1_.resize(bufferSize);
    fftBuffer2_.resize(bufferSize);
    crossPowerSpectrum_.resize(bufferSize);
    correlationSurface_.resize(bufferSize);
    
    LOGI("PhaseCorrelationAligner initialized: windowSize=%d", config_.windowSize);
}

PhaseCorrelationAligner::~PhaseCorrelationAligner() = default;

void PhaseCorrelationAligner::initHanningWindow(int size) {
    hanningWindow_.resize(size * size);
    
    for (int y = 0; y < size; ++y) {
        float wy = 0.5f * (1.0f - std::cos(2.0f * M_PI * y / (size - 1)));
        for (int x = 0; x < size; ++x) {
            float wx = 0.5f * (1.0f - std::cos(2.0f * M_PI * x / (size - 1)));
            hanningWindow_[y * size + x] = wx * wy;
        }
    }
}

void PhaseCorrelationAligner::applyHanningWindow(const float* input, float* output, int size) {
    for (int i = 0; i < size * size; ++i) {
        output[i] = input[i] * hanningWindow_[i];
    }
}

// Simple radix-2 FFT implementation
// For production, consider using kissfft or FFTW
void PhaseCorrelationAligner::fft1D(std::complex<float>* data, int n, bool inverse) {
    // Bit-reversal permutation
    int j = 0;
    for (int i = 0; i < n - 1; ++i) {
        if (i < j) {
            std::swap(data[i], data[j]);
        }
        int k = n >> 1;
        while (k <= j) {
            j -= k;
            k >>= 1;
        }
        j += k;
    }
    
    // Cooley-Tukey FFT
    float sign = inverse ? 1.0f : -1.0f;
    
    for (int len = 2; len <= n; len <<= 1) {
        float angle = sign * 2.0f * M_PI / len;
        std::complex<float> wn(std::cos(angle), std::sin(angle));
        
        for (int i = 0; i < n; i += len) {
            std::complex<float> w(1.0f, 0.0f);
            for (int jj = 0; jj < len / 2; ++jj) {
                std::complex<float> u = data[i + jj];
                std::complex<float> t = w * data[i + jj + len / 2];
                data[i + jj] = u + t;
                data[i + jj + len / 2] = u - t;
                w *= wn;
            }
        }
    }
    
    // Normalize for inverse FFT
    if (inverse) {
        for (int i = 0; i < n; ++i) {
            data[i] /= static_cast<float>(n);
        }
    }
}

void PhaseCorrelationAligner::fft2D(std::complex<float>* data, int size, bool inverse) {
    // Row-wise FFT
    for (int y = 0; y < size; ++y) {
        fft1D(data + y * size, size, inverse);
    }
    
    // Column-wise FFT (transpose, FFT, transpose back)
    std::vector<std::complex<float>> column(size);
    for (int x = 0; x < size; ++x) {
        // Extract column
        for (int y = 0; y < size; ++y) {
            column[y] = data[y * size + x];
        }
        
        // FFT column
        fft1D(column.data(), size, inverse);
        
        // Put back
        for (int y = 0; y < size; ++y) {
            data[y * size + x] = column[y];
        }
    }
}

PhaseCorrelationResult PhaseCorrelationAligner::findPeak(
    const std::vector<float>& surface,
    int size,
    float gyroShiftX,
    float gyroShiftY
) {
    PhaseCorrelationResult result;
    
    // Find global maximum
    int peakX = 0, peakY = 0;
    float maxVal = -1e30f;
    
    // Search near gyro estimate first (within ±10 pixels)
    int searchRadius = 10;
    int centerX = static_cast<int>(gyroShiftX) % size;
    int centerY = static_cast<int>(gyroShiftY) % size;
    if (centerX < 0) centerX += size;
    if (centerY < 0) centerY += size;
    
    // Search in local region around gyro estimate
    for (int dy = -searchRadius; dy <= searchRadius; ++dy) {
        int y = (centerY + dy + size) % size;
        for (int dx = -searchRadius; dx <= searchRadius; ++dx) {
            int x = (centerX + dx + size) % size;
            float val = surface[y * size + x];
            if (val > maxVal) {
                maxVal = val;
                peakX = x;
                peakY = y;
            }
        }
    }
    
    // If local search failed, do global search
    if (maxVal < 0.01f) {
        for (int y = 0; y < size; ++y) {
            for (int x = 0; x < size; ++x) {
                float val = surface[y * size + x];
                if (val > maxVal) {
                    maxVal = val;
                    peakX = x;
                    peakY = y;
                }
            }
        }
    }
    
    result.peakValue = maxVal;
    
    // Sub-pixel refinement
    float subX, subY;
    refineSubPixel(surface, size, peakX, peakY, subX, subY);
    
    // Convert to shift (handle wraparound)
    result.shiftX = (peakX > size / 2) ? (peakX - size + subX) : (peakX + subX);
    result.shiftY = (peakY > size / 2) ? (peakY - size + subY) : (peakY + subY);
    
    // Compute confidence based on peak sharpness
    // Compare peak to mean of surface
    float sum = 0;
    for (int i = 0; i < size * size; ++i) {
        sum += surface[i];
    }
    float mean = sum / (size * size);
    result.confidence = (mean > 0) ? std::min(1.0f, (maxVal - mean) / (maxVal + 0.001f)) : 0.0f;
    result.isValid = result.confidence > 0.1f;
    
    return result;
}

void PhaseCorrelationAligner::refineSubPixel(
    const std::vector<float>& surface,
    int size,
    int peakX, int peakY,
    float& subX, float& subY
) {
    // Parabolic fitting for sub-pixel accuracy
    // f(x) = ax² + bx + c, peak at x = -b/(2a)
    
    auto getVal = [&](int x, int y) -> float {
        x = (x + size) % size;
        y = (y + size) % size;
        return surface[y * size + x];
    };
    
    float v0 = getVal(peakX, peakY);
    float vxm = getVal(peakX - 1, peakY);
    float vxp = getVal(peakX + 1, peakY);
    float vym = getVal(peakX, peakY - 1);
    float vyp = getVal(peakX, peakY + 1);
    
    // Parabolic fit in X
    float denomX = 2.0f * (vxm + vxp - 2.0f * v0);
    if (std::abs(denomX) > 1e-6f) {
        subX = (vxm - vxp) / denomX;
        subX = clamp(subX, -0.5f, 0.5f);
    } else {
        subX = 0.0f;
    }
    
    // Parabolic fit in Y
    float denomY = 2.0f * (vym + vyp - 2.0f * v0);
    if (std::abs(denomY) > 1e-6f) {
        subY = (vym - vyp) / denomY;
        subY = clamp(subY, -0.5f, 0.5f);
    } else {
        subY = 0.0f;
    }
}

PhaseCorrelationResult PhaseCorrelationAligner::computeShift(
    const GrayImage& reference,
    const GrayImage& target,
    float gyroShiftX,
    float gyroShiftY
) {
    int size = config_.windowSize;
    
    // Sample from center of image
    int startX = (reference.width - size) / 2;
    int startY = (reference.height - size) / 2;
    
    if (startX < 0 || startY < 0 || 
        startX + size > reference.width || startY + size > reference.height) {
        // Image too small, use smaller window
        size = std::min({reference.width, reference.height, target.width, target.height});
        // Round down to power of 2
        int newSize = 1;
        while (newSize * 2 <= size) newSize *= 2;
        size = newSize;
        startX = (reference.width - size) / 2;
        startY = (reference.height - size) / 2;
        
        if (size < 32) {
            LOGW("Image too small for phase correlation");
            return PhaseCorrelationResult();
        }
        
        // Resize buffers
        fftBuffer1_.resize(size * size);
        fftBuffer2_.resize(size * size);
        crossPowerSpectrum_.resize(size * size);
        correlationSurface_.resize(size * size);
        initHanningWindow(size);
    }
    
    // Extract and window reference patch
    std::vector<float> refPatch(size * size);
    std::vector<float> tarPatch(size * size);
    
    for (int y = 0; y < size; ++y) {
        for (int x = 0; x < size; ++x) {
            refPatch[y * size + x] = reference.at(startX + x, startY + y);
            tarPatch[y * size + x] = target.at(startX + x, startY + y);
        }
    }
    
    // Apply Hanning window
    if (config_.useHanning) {
        std::vector<float> windowedRef(size * size);
        std::vector<float> windowedTar(size * size);
        applyHanningWindow(refPatch.data(), windowedRef.data(), size);
        applyHanningWindow(tarPatch.data(), windowedTar.data(), size);
        refPatch = std::move(windowedRef);
        tarPatch = std::move(windowedTar);
    }
    
    // Convert to complex
    for (int i = 0; i < size * size; ++i) {
        fftBuffer1_[i] = std::complex<float>(refPatch[i], 0.0f);
        fftBuffer2_[i] = std::complex<float>(tarPatch[i], 0.0f);
    }
    
    // Forward FFT
    fft2D(fftBuffer1_.data(), size, false);
    fft2D(fftBuffer2_.data(), size, false);
    
    // Cross-power spectrum: (F1 * conj(F2)) / |F1 * conj(F2)|
    for (int i = 0; i < size * size; ++i) {
        std::complex<float> product = fftBuffer1_[i] * std::conj(fftBuffer2_[i]);
        float magnitude = std::abs(product);
        if (magnitude > 1e-10f) {
            crossPowerSpectrum_[i] = product / magnitude;
        } else {
            crossPowerSpectrum_[i] = std::complex<float>(0.0f, 0.0f);
        }
    }
    
    // Inverse FFT to get correlation surface
    fft2D(crossPowerSpectrum_.data(), size, true);
    
    // Extract real part
    for (int i = 0; i < size * size; ++i) {
        correlationSurface_[i] = crossPowerSpectrum_[i].real();
    }
    
    // Find peak
    PhaseCorrelationResult result = findPeak(correlationSurface_, size, gyroShiftX, gyroShiftY);
    
    LOGD("PhaseCorrelation: shift=(%.2f, %.2f), confidence=%.2f", 
         result.shiftX, result.shiftY, result.confidence);
    
    return result;
}

PhaseCorrelationResult PhaseCorrelationAligner::computeShiftInRegion(
    const GrayImage& reference,
    const GrayImage& target,
    int regionX, int regionY,
    int regionWidth, int regionHeight
) {
    // Extract region and compute shift
    int size = std::min({regionWidth, regionHeight, config_.windowSize});
    
    // Round down to power of 2
    int newSize = 1;
    while (newSize * 2 <= size) newSize *= 2;
    size = newSize;
    
    if (size < 32) {
        return PhaseCorrelationResult();
    }
    
    // Center the window in the region
    int startX = regionX + (regionWidth - size) / 2;
    int startY = regionY + (regionHeight - size) / 2;
    
    // Bounds check
    startX = clamp(startX, 0, reference.width - size);
    startY = clamp(startY, 0, reference.height - size);
    
    // Resize buffers if needed
    if (static_cast<int>(fftBuffer1_.size()) != size * size) {
        fftBuffer1_.resize(size * size);
        fftBuffer2_.resize(size * size);
        crossPowerSpectrum_.resize(size * size);
        correlationSurface_.resize(size * size);
        initHanningWindow(size);
    }
    
    // Extract patches
    std::vector<float> refPatch(size * size);
    std::vector<float> tarPatch(size * size);
    
    for (int y = 0; y < size; ++y) {
        for (int x = 0; x < size; ++x) {
            int srcX = clamp(startX + x, 0, reference.width - 1);
            int srcY = clamp(startY + y, 0, reference.height - 1);
            refPatch[y * size + x] = reference.at(srcX, srcY);
            
            srcX = clamp(startX + x, 0, target.width - 1);
            srcY = clamp(startY + y, 0, target.height - 1);
            tarPatch[y * size + x] = target.at(srcX, srcY);
        }
    }
    
    // Apply Hanning window
    if (config_.useHanning) {
        std::vector<float> windowedRef(size * size);
        std::vector<float> windowedTar(size * size);
        applyHanningWindow(refPatch.data(), windowedRef.data(), size);
        applyHanningWindow(tarPatch.data(), windowedTar.data(), size);
        refPatch = std::move(windowedRef);
        tarPatch = std::move(windowedTar);
    }
    
    // Convert to complex and compute
    for (int i = 0; i < size * size; ++i) {
        fftBuffer1_[i] = std::complex<float>(refPatch[i], 0.0f);
        fftBuffer2_[i] = std::complex<float>(tarPatch[i], 0.0f);
    }
    
    fft2D(fftBuffer1_.data(), size, false);
    fft2D(fftBuffer2_.data(), size, false);
    
    for (int i = 0; i < size * size; ++i) {
        std::complex<float> product = fftBuffer1_[i] * std::conj(fftBuffer2_[i]);
        float magnitude = std::abs(product);
        if (magnitude > 1e-10f) {
            crossPowerSpectrum_[i] = product / magnitude;
        } else {
            crossPowerSpectrum_[i] = std::complex<float>(0.0f, 0.0f);
        }
    }
    
    fft2D(crossPowerSpectrum_.data(), size, true);
    
    for (int i = 0; i < size * size; ++i) {
        correlationSurface_[i] = crossPowerSpectrum_[i].real();
    }
    
    return findPeak(correlationSurface_, size, 0.0f, 0.0f);
}

// HybridAligner implementation

HybridAligner::HybridAligner() 
    : phaseAligner_(PhaseCorrelationConfig()) {
}

FlowField HybridAligner::computeAlignment(
    const GrayImage& reference,
    const GrayImage& target,
    const GyroHomography* gyroHomography,
    bool useLocalRefinement
) {
    FlowField result(reference.width, reference.height);
    
    // Step 1: Get initial estimate from gyro
    float gyroShiftX = 0.0f, gyroShiftY = 0.0f;
    if (gyroHomography && gyroHomography->isValid) {
        // Get average shift from gyro homography (center of image)
        float cx = reference.width / 2.0f;
        float cy = reference.height / 2.0f;
        FlowVector gyroFlow = gyroHomography->getInitialFlow(cx, cy);
        gyroShiftX = gyroFlow.dx;
        gyroShiftY = gyroFlow.dy;
        LOGD("HybridAligner: Gyro estimate = (%.2f, %.2f)", gyroShiftX, gyroShiftY);
    }
    
    // Step 2: Refine with phase correlation
    PhaseCorrelationResult pcResult = phaseAligner_.computeShift(
        reference, target, gyroShiftX, gyroShiftY
    );
    
    float finalShiftX, finalShiftY, confidence;
    
    // Maximum allowed shift in pixels - larger shifts indicate misalignment or excessive motion
    // For handheld burst capture, shifts should typically be <20 pixels
    const float maxAllowedShift = 30.0f;
    
    // Check if phase correlation result is reasonable
    float pcShiftMagnitude = std::sqrt(pcResult.shiftX * pcResult.shiftX + 
                                        pcResult.shiftY * pcResult.shiftY);
    bool pcShiftReasonable = pcShiftMagnitude < maxAllowedShift;
    
    // Check if gyro estimate is reasonable
    float gyroShiftMagnitude = std::sqrt(gyroShiftX * gyroShiftX + gyroShiftY * gyroShiftY);
    bool gyroShiftReasonable = gyroShiftMagnitude < maxAllowedShift;
    
    if (pcResult.isValid && pcResult.confidence > 0.5f && pcShiftReasonable) {
        // Use phase correlation result - high confidence and reasonable magnitude
        finalShiftX = pcResult.shiftX;
        finalShiftY = pcResult.shiftY;
        confidence = pcResult.confidence;
        LOGD("HybridAligner: Using phase correlation = (%.2f, %.2f), conf=%.2f, mag=%.2f",
             finalShiftX, finalShiftY, confidence, pcShiftMagnitude);
    } else if (pcResult.isValid && pcResult.confidence > 0.3f && pcShiftReasonable) {
        // Use phase correlation with moderate confidence
        finalShiftX = pcResult.shiftX;
        finalShiftY = pcResult.shiftY;
        confidence = pcResult.confidence * 0.8f;  // Reduce confidence slightly
        LOGD("HybridAligner: Using phase correlation (moderate) = (%.2f, %.2f), conf=%.2f, mag=%.2f",
             finalShiftX, finalShiftY, confidence, pcShiftMagnitude);
    } else if (gyroHomography && gyroHomography->isValid && gyroShiftReasonable) {
        // Fall back to gyro if it's reasonable
        finalShiftX = gyroShiftX;
        finalShiftY = gyroShiftY;
        confidence = 0.4f;
        LOGD("HybridAligner: Falling back to gyro = (%.2f, %.2f), mag=%.2f", 
             finalShiftX, finalShiftY, gyroShiftMagnitude);
    } else {
        // Both estimates are unreasonable - use zero shift (frame likely has too much motion)
        finalShiftX = 0.0f;
        finalShiftY = 0.0f;
        confidence = 0.1f;  // Very low confidence signals this frame should be weighted less
        LOGW("HybridAligner: Shifts too large (pc=%.2f, gyro=%.2f), using zero shift - frame may have excessive motion",
             pcShiftMagnitude, gyroShiftMagnitude);
    }
    
    // Step 3: Fill flow field with uniform shift (or local refinement if enabled)
    if (!useLocalRefinement) {
        // Uniform shift - much faster than dense flow
        for (int y = 0; y < result.height; ++y) {
            for (int x = 0; x < result.width; ++x) {
                result.at(x, y) = FlowVector(finalShiftX, finalShiftY, confidence);
            }
        }
    } else {
        // Local refinement using phase correlation in tiles
        // This is still faster than dense Lucas-Kanade
        const int tileSize = 128;
        
        for (int ty = 0; ty < reference.height; ty += tileSize) {
            for (int tx = 0; tx < reference.width; tx += tileSize) {
                int tw = std::min(tileSize, reference.width - tx);
                int th = std::min(tileSize, reference.height - ty);
                
                PhaseCorrelationResult tileResult = phaseAligner_.computeShiftInRegion(
                    reference, target, tx, ty, tw, th
                );
                
                float tileShiftX, tileShiftY, tileConf;
                
                // Check if tile shift is reasonable
                float tileMagnitude = std::sqrt(tileResult.shiftX * tileResult.shiftX + 
                                                 tileResult.shiftY * tileResult.shiftY);
                bool tileShiftReasonable = tileMagnitude < maxAllowedShift;
                
                if (tileResult.isValid && tileResult.confidence > 0.3f && tileShiftReasonable) {
                    tileShiftX = tileResult.shiftX;
                    tileShiftY = tileResult.shiftY;
                    tileConf = tileResult.confidence;
                } else {
                    // Use global shift for this tile (or zero if global is also bad)
                    tileShiftX = finalShiftX;
                    tileShiftY = finalShiftY;
                    tileConf = confidence * 0.5f;
                }
                
                // Fill tile region
                for (int y = ty; y < ty + th && y < result.height; ++y) {
                    for (int x = tx; x < tx + tw && x < result.width; ++x) {
                        result.at(x, y) = FlowVector(tileShiftX, tileShiftY, tileConf);
                    }
                }
            }
        }
    }
    
    return result;
}

} // namespace ultradetail
