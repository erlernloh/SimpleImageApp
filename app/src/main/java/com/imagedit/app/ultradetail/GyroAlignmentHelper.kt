/**
 * GyroAlignmentHelper.kt - Gyroscope-based frame alignment
 * 
 * Uses gyroscope data to compute coarse alignment (homography) between frames.
 * This provides an initial estimate that can be refined with image-based alignment.
 * 
 * Based on research from Google's Handheld Multi-Frame Super-Resolution paper.
 */

package com.imagedit.app.ultradetail

import android.util.Log
import kotlin.math.cos
import kotlin.math.sin

private const val TAG = "GyroAlignmentHelper"

/**
 * Camera intrinsic parameters (approximate values for typical smartphone)
 * These should ideally be calibrated per-device
 */
data class CameraIntrinsics(
    val focalLengthPx: Float = 3000f,  // Focal length in pixels (typical for 12MP sensor)
    val principalPointX: Float = 0f,    // Will be set to image center
    val principalPointY: Float = 0f     // Will be set to image center
) {
    fun withImageSize(width: Int, height: Int): CameraIntrinsics {
        return copy(
            principalPointX = width / 2f,
            principalPointY = height / 2f
        )
    }
}

/**
 * 3x3 Homography matrix for 2D image transformation
 */
data class Homography(
    val m00: Float, val m01: Float, val m02: Float,
    val m10: Float, val m11: Float, val m12: Float,
    val m20: Float, val m21: Float, val m22: Float
) {
    companion object {
        /**
         * Identity homography (no transformation)
         */
        fun identity() = Homography(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )
    }
    
    /**
     * Transform a point using this homography
     */
    fun transformPoint(x: Float, y: Float): Pair<Float, Float> {
        val w = m20 * x + m21 * y + m22
        if (w == 0f) return Pair(x, y)
        
        val newX = (m00 * x + m01 * y + m02) / w
        val newY = (m10 * x + m11 * y + m12) / w
        return Pair(newX, newY)
    }
    
    /**
     * Compute inverse homography
     */
    fun inverse(): Homography {
        val det = m00 * (m11 * m22 - m12 * m21) -
                  m01 * (m10 * m22 - m12 * m20) +
                  m02 * (m10 * m21 - m11 * m20)
        
        if (det == 0f) return identity()
        
        val invDet = 1f / det
        
        return Homography(
            (m11 * m22 - m12 * m21) * invDet,
            (m02 * m21 - m01 * m22) * invDet,
            (m01 * m12 - m02 * m11) * invDet,
            (m12 * m20 - m10 * m22) * invDet,
            (m00 * m22 - m02 * m20) * invDet,
            (m02 * m10 - m00 * m12) * invDet,
            (m10 * m21 - m11 * m20) * invDet,
            (m01 * m20 - m00 * m21) * invDet,
            (m00 * m11 - m01 * m10) * invDet
        )
    }
    
    /**
     * Multiply two homographies
     */
    operator fun times(other: Homography): Homography {
        return Homography(
            m00 * other.m00 + m01 * other.m10 + m02 * other.m20,
            m00 * other.m01 + m01 * other.m11 + m02 * other.m21,
            m00 * other.m02 + m01 * other.m12 + m02 * other.m22,
            m10 * other.m00 + m11 * other.m10 + m12 * other.m20,
            m10 * other.m01 + m11 * other.m11 + m12 * other.m21,
            m10 * other.m02 + m11 * other.m12 + m12 * other.m22,
            m20 * other.m00 + m21 * other.m10 + m22 * other.m20,
            m20 * other.m01 + m21 * other.m11 + m22 * other.m21,
            m20 * other.m02 + m21 * other.m12 + m22 * other.m22
        )
    }
    
    /**
     * Get translation component (approximate for small rotations)
     */
    fun getTranslation(): Pair<Float, Float> = Pair(m02, m12)
    
    /**
     * Convert to float array for JNI
     */
    fun toFloatArray(): FloatArray = floatArrayOf(
        m00, m01, m02,
        m10, m11, m12,
        m20, m21, m22
    )
}

/**
 * Helper class for computing frame alignment from gyroscope data
 */
class GyroAlignmentHelper(
    private val intrinsics: CameraIntrinsics = CameraIntrinsics()
) {
    
    /**
     * Compute rotation matrix from gyro samples using integration
     * 
     * @param samples Gyroscope samples between two frames
     * @return 3x3 rotation matrix
     */
    fun integrateGyroRotation(samples: List<GyroSample>): FloatArray {
        if (samples.isEmpty()) {
            return floatArrayOf(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f
            )
        }
        
        // Integrate angular velocities to get total rotation
        var totalAngleX = 0f
        var totalAngleY = 0f
        var totalAngleZ = 0f
        
        for (i in 0 until samples.size - 1) {
            val dt = (samples[i + 1].timestamp - samples[i].timestamp) / 1_000_000_000f // Convert ns to seconds
            
            // Trapezoidal integration
            val avgX = (samples[i].rotationX + samples[i + 1].rotationX) / 2f
            val avgY = (samples[i].rotationY + samples[i + 1].rotationY) / 2f
            val avgZ = (samples[i].rotationZ + samples[i + 1].rotationZ) / 2f
            
            totalAngleX += avgX * dt
            totalAngleY += avgY * dt
            totalAngleZ += avgZ * dt
        }
        
        // Build rotation matrix from Euler angles (small angle approximation for speed)
        // For small rotations, we can use: R ≈ I + [ω]× where [ω]× is the skew-symmetric matrix
        // For more accuracy, use Rodrigues' formula
        
        return if (totalAngleX * totalAngleX + totalAngleY * totalAngleY + totalAngleZ * totalAngleZ < 0.01f) {
            // Small angle approximation (faster)
            floatArrayOf(
                1f, -totalAngleZ, totalAngleY,
                totalAngleZ, 1f, -totalAngleX,
                -totalAngleY, totalAngleX, 1f
            )
        } else {
            // Full Rodrigues' formula for larger rotations
            computeRodriguesRotation(totalAngleX, totalAngleY, totalAngleZ)
        }
    }
    
    /**
     * Compute rotation matrix using Rodrigues' formula
     */
    private fun computeRodriguesRotation(rx: Float, ry: Float, rz: Float): FloatArray {
        val theta = kotlin.math.sqrt(rx * rx + ry * ry + rz * rz)
        
        if (theta < 1e-6f) {
            return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        }
        
        // Normalize rotation axis
        val kx = rx / theta
        val ky = ry / theta
        val kz = rz / theta
        
        val c = cos(theta)
        val s = sin(theta)
        val v = 1f - c
        
        return floatArrayOf(
            kx * kx * v + c,      kx * ky * v - kz * s, kx * kz * v + ky * s,
            ky * kx * v + kz * s, ky * ky * v + c,      ky * kz * v - kx * s,
            kz * kx * v - ky * s, kz * ky * v + kx * s, kz * kz * v + c
        )
    }
    
    /**
     * Convert 3D rotation to 2D homography using camera intrinsics
     * 
     * H = K * R * K^(-1)
     * 
     * where K is the camera intrinsic matrix
     */
    fun rotationToHomography(rotation: FloatArray, imageWidth: Int, imageHeight: Int): Homography {
        val K = intrinsics.withImageSize(imageWidth, imageHeight)
        val fx = K.focalLengthPx
        val fy = K.focalLengthPx
        val cx = K.principalPointX
        val cy = K.principalPointY
        
        // K matrix
        // [fx  0  cx]
        // [0  fy  cy]
        // [0   0   1]
        
        // K^(-1) matrix
        // [1/fx   0   -cx/fx]
        // [0    1/fy  -cy/fy]
        // [0      0      1  ]
        
        // Compute H = K * R * K^(-1)
        // This is the homography induced by pure rotation
        
        val r00 = rotation[0]; val r01 = rotation[1]; val r02 = rotation[2]
        val r10 = rotation[3]; val r11 = rotation[4]; val r12 = rotation[5]
        val r20 = rotation[6]; val r21 = rotation[7]; val r22 = rotation[8]
        
        // First compute R * K^(-1)
        val rk00 = r00 / fx
        val rk01 = r01 / fy
        val rk02 = r02 - r00 * cx / fx - r01 * cy / fy
        
        val rk10 = r10 / fx
        val rk11 = r11 / fy
        val rk12 = r12 - r10 * cx / fx - r11 * cy / fy
        
        val rk20 = r20 / fx
        val rk21 = r21 / fy
        val rk22 = r22 - r20 * cx / fx - r21 * cy / fy
        
        // Then compute K * (R * K^(-1))
        val h00 = fx * rk00 + cx * rk20
        val h01 = fx * rk01 + cx * rk21
        val h02 = fx * rk02 + cx * rk22
        
        val h10 = fy * rk10 + cy * rk20
        val h11 = fy * rk11 + cy * rk21
        val h12 = fy * rk12 + cy * rk22
        
        val h20 = rk20
        val h21 = rk21
        val h22 = rk22
        
        return Homography(h00, h01, h02, h10, h11, h12, h20, h21, h22)
    }
    
    /**
     * Compute homography between two frames using their gyro samples
     * 
     * @param referenceFrame Reference frame
     * @param targetFrame Frame to align to reference
     * @return Homography that transforms points from target to reference
     */
    fun computeFrameHomography(
        referenceFrame: CapturedFrame,
        targetFrame: CapturedFrame
    ): Homography {
        // Collect all gyro samples between the two frames
        val allSamples = mutableListOf<GyroSample>()
        
        // Add samples from reference frame (if any after its timestamp)
        allSamples.addAll(referenceFrame.gyroSamples.filter { 
            it.timestamp >= referenceFrame.timestamp 
        })
        
        // Add samples from target frame (up to its timestamp)
        allSamples.addAll(targetFrame.gyroSamples.filter { 
            it.timestamp <= targetFrame.timestamp 
        })
        
        // Sort by timestamp
        allSamples.sortBy { it.timestamp }
        
        if (allSamples.isEmpty()) {
            Log.d(TAG, "No gyro samples between frames, returning identity homography")
            return Homography.identity()
        }
        
        // Integrate rotation
        val rotation = integrateGyroRotation(allSamples)
        
        // Convert to homography
        val homography = rotationToHomography(
            rotation, 
            targetFrame.width, 
            targetFrame.height
        )
        
        // Log the estimated translation for debugging
        val (tx, ty) = homography.getTranslation()
        Log.d(TAG, "Gyro homography: translation=(%.2f, %.2f) px from %d samples".format(tx, ty, allSamples.size))
        
        return homography
    }
    
    /**
     * Compute homographies for all frames relative to reference (first frame)
     * 
     * @param frames List of captured frames
     * @return List of homographies (first is identity)
     */
    fun computeAllHomographies(frames: List<CapturedFrame>): List<Homography> {
        if (frames.isEmpty()) return emptyList()
        
        val homographies = mutableListOf<Homography>()
        val referenceFrame = frames[0]
        
        // First frame is identity (reference)
        homographies.add(Homography.identity())
        
        // Compute cumulative homography for each subsequent frame
        var cumulativeHomography = Homography.identity()
        
        for (i in 1 until frames.size) {
            val frameHomography = computeFrameHomography(frames[i - 1], frames[i])
            cumulativeHomography = cumulativeHomography * frameHomography
            homographies.add(cumulativeHomography)
        }
        
        Log.i(TAG, "Computed ${homographies.size} gyro-based homographies")
        
        return homographies
    }
    
    /**
     * Estimate the search window reduction from gyro pre-alignment
     * 
     * @param homography The gyro-estimated homography
     * @param originalSearchRadius Original search radius in pixels
     * @return Reduced search radius
     */
    fun estimateReducedSearchRadius(
        homography: Homography,
        originalSearchRadius: Int,
        gyroAccuracyPixels: Float = 5f  // Expected gyro accuracy in pixels
    ): Int {
        // With gyro pre-alignment, we only need to search within the gyro error margin
        // This significantly speeds up the image-based alignment
        return minOf(originalSearchRadius, (gyroAccuracyPixels * 2).toInt())
    }
}
