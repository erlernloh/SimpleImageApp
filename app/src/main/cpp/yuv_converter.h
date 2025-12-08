/**
 * yuv_converter.h - YUV to RGB conversion utilities
 * 
 * Handles conversion from YUV_420_888 format (CameraX output)
 * to float32 RGB for processing.
 */

#ifndef ULTRADETAIL_YUV_CONVERTER_H
#define ULTRADETAIL_YUV_CONVERTER_H

#include "common.h"

namespace ultradetail {

/**
 * YUV frame data structure matching Android YUV_420_888 format
 */
struct YUVFrame {
    const uint8_t* yPlane;
    const uint8_t* uPlane;
    const uint8_t* vPlane;
    int yRowStride;
    int uvRowStride;
    int uvPixelStride;
    int width;
    int height;
};

/**
 * Convert YUV_420_888 frame to float32 RGB image
 * 
 * @param yuv Input YUV frame
 * @param output Output RGB image (pre-allocated)
 */
void yuvToRgbFloat(const YUVFrame& yuv, RGBImage& output);

/**
 * Convert YUV_420_888 frame to grayscale float image (Y channel only)
 * 
 * @param yuv Input YUV frame
 * @param output Output grayscale image (pre-allocated)
 */
void yuvToGray(const YUVFrame& yuv, GrayImage& output);

/**
 * Convert float32 RGB image to uint8 ARGB bitmap format
 * 
 * @param input Input RGB image
 * @param output Output ARGB buffer (pre-allocated, 4 bytes per pixel)
 * @param outputStride Stride of output buffer in bytes
 */
void rgbFloatToArgb(const RGBImage& input, uint8_t* output, int outputStride);

/**
 * Convert grayscale float image to luminance
 * 
 * @param rgb Input RGB image
 * @param output Output grayscale image
 */
void rgbToLuminance(const RGBImage& rgb, GrayImage& output);

/**
 * Compute statistics for an RGB image (min/max/mean per channel, NaN/Inf counts)
 * 
 * @param image Input RGB image
 * @return ImageStats structure with computed statistics
 */
ImageStats computeImageStats(const RGBImage& image);

/**
 * Sanitize an RGB image by replacing NaN/Inf with 0 and clamping to [0,1]
 * 
 * @param image Image to sanitize (modified in place)
 * @return Number of pixels that were sanitized
 */
int sanitizeRGBImage(RGBImage& image);

} // namespace ultradetail

#endif // ULTRADETAIL_YUV_CONVERTER_H
