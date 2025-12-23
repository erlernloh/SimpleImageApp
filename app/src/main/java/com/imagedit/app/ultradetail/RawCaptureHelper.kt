/**
 * RawCaptureHelper.kt - RAW Bayer Capture Support
 * 
 * Fix #2: Infrastructure for RAW Bayer processing.
 * 
 * RAW capture provides significant quality benefits:
 * - No demosaicing artifacts (moiré, color fringing)
 * - Full color channel alignment (like Nikon Z8 pixel shift)
 * - Better dynamic range
 * 
 * Limitations:
 * - Not all devices support RAW capture
 * - Requires Camera2 API (CameraX doesn't fully support RAW)
 * - Larger file sizes and processing requirements
 * 
 * This helper checks device capabilities and provides RAW capture
 * when available, falling back to JPEG otherwise.
 */

package com.imagedit.app.ultradetail

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log
import android.util.Size

private const val TAG = "RawCaptureHelper"

/**
 * RAW capture capability information
 */
data class RawCaptureCapability(
    val isRawSupported: Boolean,
    val rawFormat: Int,                    // ImageFormat.RAW_SENSOR, RAW10, RAW12, etc.
    val rawSize: Size?,                    // Maximum RAW resolution
    val bayerPattern: Int,                 // CFA pattern (RGGB, BGGR, etc.)
    val whiteLevel: Int,                   // Maximum pixel value
    val blackLevel: IntArray?,             // Per-channel black levels
    val colorCorrectionGains: FloatArray?, // AWB gains
    val cameraId: String
) {
    companion object {
        val NOT_SUPPORTED = RawCaptureCapability(
            isRawSupported = false,
            rawFormat = 0,
            rawSize = null,
            bayerPattern = 0,
            whiteLevel = 0,
            blackLevel = null,
            colorCorrectionGains = null,
            cameraId = ""
        )
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RawCaptureCapability
        return isRawSupported == other.isRawSupported &&
               rawFormat == other.rawFormat &&
               rawSize == other.rawSize &&
               bayerPattern == other.bayerPattern &&
               cameraId == other.cameraId
    }
    
    override fun hashCode(): Int {
        var result = isRawSupported.hashCode()
        result = 31 * result + rawFormat
        result = 31 * result + (rawSize?.hashCode() ?: 0)
        result = 31 * result + bayerPattern
        result = 31 * result + cameraId.hashCode()
        return result
    }
}

/**
 * Bayer pattern types (CFA - Color Filter Array)
 */
enum class BayerPattern(val value: Int) {
    RGGB(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB),
    GRBG(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG),
    GBRG(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG),
    BGGR(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR),
    RGB(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB),  // No Bayer pattern
    UNKNOWN(-1);
    
    companion object {
        fun fromValue(value: Int): BayerPattern {
            return values().find { it.value == value } ?: UNKNOWN
        }
    }
}

/**
 * Helper class for RAW capture capabilities
 */
class RawCaptureHelper(private val context: Context) {
    
    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }
    
    var rawCapability: RawCaptureCapability? = null
        private set
    
    /**
     * Check if RAW capture is supported on the back camera
     */
    fun checkRawSupport(): RawCaptureCapability {
        val capability = try {
            val cameraId = getBackCameraId() ?: return RawCaptureCapability.NOT_SUPPORTED
            checkRawSupportForCamera(cameraId)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking RAW support", e)
            RawCaptureCapability.NOT_SUPPORTED
        }
        rawCapability = capability
        return capability
    }
    
    /**
     * Check RAW support for a specific camera
     */
    fun checkRawSupportForCamera(cameraId: String): RawCaptureCapability {
        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            
            // Check hardware level - RAW requires at least FULL level
            val hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            if (hardwareLevel == null || hardwareLevel < CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL) {
                Log.d(TAG, "Camera $cameraId: Hardware level $hardwareLevel doesn't support RAW")
                return RawCaptureCapability.NOT_SUPPORTED
            }
            
            // Check available capabilities
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val hasRawCapability = capabilities?.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW
            ) == true
            
            if (!hasRawCapability) {
                Log.d(TAG, "Camera $cameraId: RAW capability not available")
                return RawCaptureCapability.NOT_SUPPORTED
            }
            
            // Get stream configuration map
            val streamConfigMap = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            ) ?: return RawCaptureCapability.NOT_SUPPORTED
            
            // Check for RAW_SENSOR format
            val outputFormats = streamConfigMap.outputFormats
            val rawFormat = when {
                outputFormats.contains(ImageFormat.RAW_SENSOR) -> ImageFormat.RAW_SENSOR
                outputFormats.contains(ImageFormat.RAW10) -> ImageFormat.RAW10
                outputFormats.contains(ImageFormat.RAW12) -> ImageFormat.RAW12
                else -> {
                    Log.d(TAG, "Camera $cameraId: No RAW format available")
                    return RawCaptureCapability.NOT_SUPPORTED
                }
            }
            
            // Get RAW sizes
            val rawSizes = streamConfigMap.getOutputSizes(rawFormat)
            if (rawSizes.isNullOrEmpty()) {
                Log.d(TAG, "Camera $cameraId: No RAW sizes available")
                return RawCaptureCapability.NOT_SUPPORTED
            }
            
            // Get largest RAW size
            val maxRawSize = rawSizes.maxByOrNull { it.width * it.height }
            
            // Get Bayer pattern
            val bayerPattern = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
            ) ?: 0
            
            // Get white level
            val whiteLevel = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL
            ) ?: 1023  // Default to 10-bit
            
            // Get black level pattern
            val blackLevelPattern = characteristics.get(
                CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN
            )
            val blackLevels = if (blackLevelPattern != null) {
                IntArray(4) { i -> blackLevelPattern.getOffsetForIndex(i % 2, i / 2) }
            } else null
            
            Log.i(TAG, "Camera $cameraId: RAW supported! Format=$rawFormat, Size=$maxRawSize, " +
                       "Pattern=${BayerPattern.fromValue(bayerPattern)}, WhiteLevel=$whiteLevel")
            
            RawCaptureCapability(
                isRawSupported = true,
                rawFormat = rawFormat,
                rawSize = maxRawSize,
                bayerPattern = bayerPattern,
                whiteLevel = whiteLevel,
                blackLevel = blackLevels,
                colorCorrectionGains = null,  // Retrieved at capture time
                cameraId = cameraId
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking RAW support for camera $cameraId", e)
            RawCaptureCapability.NOT_SUPPORTED
        }
    }
    
    /**
     * Get the back camera ID
     */
    private fun getBackCameraId(): String? {
        return try {
            cameraManager.cameraIdList.find { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                facing == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting back camera ID", e)
            null
        }
    }
    
    /**
     * Get a human-readable description of RAW capabilities
     */
    fun getCapabilityDescription(capability: RawCaptureCapability): String {
        if (!capability.isRawSupported) {
            return "RAW capture not supported on this device"
        }
        
        val formatName = when (capability.rawFormat) {
            ImageFormat.RAW_SENSOR -> "RAW_SENSOR (16-bit)"
            ImageFormat.RAW10 -> "RAW10 (10-bit packed)"
            ImageFormat.RAW12 -> "RAW12 (12-bit packed)"
            else -> "Unknown RAW format"
        }
        
        val pattern = BayerPattern.fromValue(capability.bayerPattern)
        
        return buildString {
            append("RAW Capture Available\n")
            append("Format: $formatName\n")
            append("Resolution: ${capability.rawSize?.width}×${capability.rawSize?.height}\n")
            append("Bayer Pattern: $pattern\n")
            append("White Level: ${capability.whiteLevel}\n")
            if (capability.blackLevel != null) {
                append("Black Levels: ${capability.blackLevel.joinToString()}")
            }
        }
    }
    
    companion object {
        /**
         * Check if the Android version supports RAW capture
         */
        fun isAndroidVersionSupported(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
        }
    }
}
