/**
 * neon_utils.h - NEON SIMD utilities for ARM processors
 * 
 * Provides optimized vector operations for image processing
 * using ARM NEON intrinsics.
 */

#ifndef ULTRADETAIL_NEON_UTILS_H
#define ULTRADETAIL_NEON_UTILS_H

#include "common.h"

#ifdef USE_NEON
#include <arm_neon.h>
#endif

namespace ultradetail {
namespace neon {

#ifdef USE_NEON

/**
 * Load 8 uint8 values and convert to float32x4 (first 4 values)
 */
inline float32x4_t load_u8_to_f32_low(const uint8_t* ptr) {
    uint8x8_t u8 = vld1_u8(ptr);
    uint16x8_t u16 = vmovl_u8(u8);
    uint32x4_t u32 = vmovl_u16(vget_low_u16(u16));
    return vcvtq_f32_u32(u32);
}

/**
 * Load 8 uint8 values and convert to float32x4 (high 4 values)
 */
inline float32x4_t load_u8_to_f32_high(const uint8_t* ptr) {
    uint8x8_t u8 = vld1_u8(ptr);
    uint16x8_t u16 = vmovl_u8(u8);
    uint32x4_t u32 = vmovl_u16(vget_high_u16(u16));
    return vcvtq_f32_u32(u32);
}

/**
 * Convert float32x4 to uint8 and store (saturating)
 */
inline void store_f32_to_u8(uint8_t* ptr, float32x4_t val) {
    // Clamp to [0, 255]
    val = vmaxq_f32(val, vdupq_n_f32(0.0f));
    val = vminq_f32(val, vdupq_n_f32(255.0f));
    
    // Convert to int32
    int32x4_t i32 = vcvtq_s32_f32(val);
    
    // Narrow to int16
    int16x4_t i16 = vmovn_s32(i32);
    
    // Narrow to uint8 (saturating)
    uint8x8_t u8 = vqmovun_s16(vcombine_s16(i16, i16));
    
    // Store first 4 bytes
    vst1_lane_u32(reinterpret_cast<uint32_t*>(ptr), vreinterpret_u32_u8(u8), 0);
}

/**
 * Compute sum of absolute differences for 8 pixels
 */
inline uint32_t sad_u8x8(const uint8_t* a, const uint8_t* b) {
    uint8x8_t va = vld1_u8(a);
    uint8x8_t vb = vld1_u8(b);
    uint8x8_t diff = vabd_u8(va, vb);
    
    // Horizontal sum
    uint16x4_t sum16 = vpaddl_u8(diff);
    uint32x2_t sum32 = vpaddl_u16(sum16);
    uint64x1_t sum64 = vpaddl_u32(sum32);
    
    return static_cast<uint32_t>(vget_lane_u64(sum64, 0));
}

/**
 * Compute sum of squared differences for 4 float pixels
 */
inline float ssd_f32x4(float32x4_t a, float32x4_t b) {
    float32x4_t diff = vsubq_f32(a, b);
    float32x4_t sq = vmulq_f32(diff, diff);
    
    // Horizontal sum
    float32x2_t sum = vadd_f32(vget_low_f32(sq), vget_high_f32(sq));
    sum = vpadd_f32(sum, sum);
    
    return vget_lane_f32(sum, 0);
}

/**
 * Apply 3x3 Sobel X kernel using NEON
 * Kernel: [-1 0 1; -2 0 2; -1 0 1]
 */
inline float32x4_t sobel_x_3x3(const float* row0, const float* row1, const float* row2) {
    // Load rows (need 6 values per row for 4 output pixels)
    float32x4_t r0_0 = vld1q_f32(row0);
    float32x4_t r0_2 = vld1q_f32(row0 + 2);
    float32x4_t r1_0 = vld1q_f32(row1);
    float32x4_t r1_2 = vld1q_f32(row1 + 2);
    float32x4_t r2_0 = vld1q_f32(row2);
    float32x4_t r2_2 = vld1q_f32(row2 + 2);
    
    // Compute: -r0[x-1] + r0[x+1] - 2*r1[x-1] + 2*r1[x+1] - r2[x-1] + r2[x+1]
    float32x4_t result = vsubq_f32(r0_2, r0_0);
    result = vmlaq_n_f32(result, vsubq_f32(r1_2, r1_0), 2.0f);
    result = vaddq_f32(result, vsubq_f32(r2_2, r2_0));
    
    return result;
}

/**
 * Apply 3x3 Sobel Y kernel using NEON
 * Kernel: [-1 -2 -1; 0 0 0; 1 2 1]
 */
inline float32x4_t sobel_y_3x3(const float* row0, const float* row1, const float* row2) {
    // Load center column values
    float32x4_t r0 = vld1q_f32(row0 + 1);
    float32x4_t r2 = vld1q_f32(row2 + 1);
    
    // Load side columns
    float32x4_t r0_l = vld1q_f32(row0);
    float32x4_t r0_r = vld1q_f32(row0 + 2);
    float32x4_t r2_l = vld1q_f32(row2);
    float32x4_t r2_r = vld1q_f32(row2 + 2);
    
    // Compute: -r0[x-1] - 2*r0[x] - r0[x+1] + r2[x-1] + 2*r2[x] + r2[x+1]
    float32x4_t top = vaddq_f32(r0_l, r0_r);
    top = vmlaq_n_f32(top, r0, 2.0f);
    
    float32x4_t bot = vaddq_f32(r2_l, r2_r);
    bot = vmlaq_n_f32(bot, r2, 2.0f);
    
    return vsubq_f32(bot, top);
}

/**
 * Compute gradient magnitude from Gx and Gy
 */
inline float32x4_t gradient_magnitude(float32x4_t gx, float32x4_t gy) {
    float32x4_t gx2 = vmulq_f32(gx, gx);
    float32x4_t gy2 = vmulq_f32(gy, gy);
    float32x4_t sum = vaddq_f32(gx2, gy2);
    
    // Approximate sqrt using Newton-Raphson
    float32x4_t estimate = vrsqrteq_f32(sum);
    estimate = vmulq_f32(estimate, vrsqrtsq_f32(vmulq_f32(sum, estimate), estimate));
    
    return vmulq_f32(sum, estimate);
}

/**
 * Bilinear interpolation for 4 pixels
 */
inline float32x4_t bilinear_interp_4(const float* src, int stride,
                                      float32x4_t fx, float32x4_t fy) {
    // This is a simplified version - full implementation would handle
    // arbitrary sub-pixel positions
    float32x4_t one = vdupq_n_f32(1.0f);
    float32x4_t fx1 = vsubq_f32(one, fx);
    float32x4_t fy1 = vsubq_f32(one, fy);
    
    // Load 4 corners (simplified - assumes aligned access)
    float32x4_t p00 = vld1q_f32(src);
    float32x4_t p10 = vld1q_f32(src + 1);
    float32x4_t p01 = vld1q_f32(src + stride);
    float32x4_t p11 = vld1q_f32(src + stride + 1);
    
    // Interpolate
    float32x4_t top = vmlaq_f32(vmulq_f32(p00, fx1), p10, fx);
    float32x4_t bot = vmlaq_f32(vmulq_f32(p01, fx1), p11, fx);
    
    return vmlaq_f32(vmulq_f32(top, fy1), bot, fy);
}

#else // !USE_NEON

// Fallback scalar implementations

inline uint32_t sad_u8x8(const uint8_t* a, const uint8_t* b) {
    uint32_t sum = 0;
    for (int i = 0; i < 8; ++i) {
        sum += std::abs(static_cast<int>(a[i]) - static_cast<int>(b[i]));
    }
    return sum;
}

inline float ssd_f32x4(const float* a, const float* b) {
    float sum = 0;
    for (int i = 0; i < 4; ++i) {
        float diff = a[i] - b[i];
        sum += diff * diff;
    }
    return sum;
}

#endif // USE_NEON

} // namespace neon
} // namespace ultradetail

#endif // ULTRADETAIL_NEON_UTILS_H
