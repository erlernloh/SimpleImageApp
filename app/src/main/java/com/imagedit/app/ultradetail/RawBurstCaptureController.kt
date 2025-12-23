package com.imagedit.app.ultradetail

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.DngCreator
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.imagedit.app.util.image.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "RawBurstCapture"

data class RawBurstCaptureFrame(
    val timestampNs: Long,
    val captureResult: TotalCaptureResult,
    val rawImage: Image,
    val yuvImage: Image?,
    val gyroSamples: List<GyroSample>,
    val sharpnessScore: Float,
    val gyroVector: FloatArray
)

data class RawBurstCaptureResult(
    val dngUris: List<android.net.Uri>,
    val rawCacheFiles: List<File>,
    val selectedFrameCount: Int,
    val capturedFrameCount: Int
)

class RawBurstCaptureController(
    private val context: Context,
    private val rawCapability: RawCaptureCapability
) : SensorEventListener {

    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gyroBuffer = ConcurrentLinkedQueue<GyroSample>()
    private var isGyroRecording = false

    private fun startGyroRecording() {
        if (isGyroRecording) return
        isGyroRecording = true
        gyroBuffer.clear()
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    private fun stopGyroRecording() {
        if (!isGyroRecording) return
        isGyroRecording = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isGyroRecording) return
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        gyroBuffer.add(
            GyroSample(
                timestamp = event.timestamp,
                rotationX = event.values[0],
                rotationY = event.values[1],
                rotationZ = event.values[2]
            )
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun getGyroSamplesForFrame(frameTimestampNs: Long, exposureTimeNs: Long?): List<GyroSample> {
        val halfWindow = (exposureTimeNs ?: 30_000_000L) / 2L
        val start = frameTimestampNs - halfWindow
        val end = frameTimestampNs + halfWindow
        return gyroBuffer.filter { it.timestamp in start..end }
    }

    suspend fun captureRawBurst(
        preset: UltraDetailPreset,
        deviceCapability: DeviceCapability,
        totalFrames: Int
    ): RawBurstCaptureResult = withContext(Dispatchers.Default) {
        require(rawCapability.isRawSupported) { "RAW not supported" }
        val cameraId = rawCapability.cameraId
        val bestK = deviceCapability.getRawBurstBestFrameCount(preset)

        val backgroundThread = HandlerThread("RawBurstCamera").apply { start() }
        val backgroundHandler = Handler(backgroundThread.looper)

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val rawSize = rawCapability.rawSize ?: throw IllegalStateException("RAW size missing")
        val yuvSize = chooseYuvSize(characteristics, rawSize)

        val rawReader = ImageReader.newInstance(
            rawSize.width,
            rawSize.height,
            rawCapability.rawFormat,
            totalFrames + 2
        )
        val yuvReader = ImageReader.newInstance(
            yuvSize.width,
            yuvSize.height,
            ImageFormat.YUV_420_888,
            totalFrames + 2
        )

        val pending = ConcurrentHashMap<Long, PendingFrame>()
        val latch = CountDownLatch(totalFrames)

        fun tryMarkComplete(ts: Long) {
            val pf = pending[ts] ?: return
            if (pf.tryMarkReady()) {
                latch.countDown()
            }
        }

        rawReader.setOnImageAvailableListener({ reader ->
            val img = reader.acquireNextImage() ?: return@setOnImageAvailableListener
            val ts = img.timestamp
            val pf = pending.computeIfAbsent(ts) { PendingFrame(ts) }
            pf.rawImage = img
            tryMarkComplete(ts)
        }, backgroundHandler)

        yuvReader.setOnImageAvailableListener({ reader ->
            val img = reader.acquireNextImage() ?: return@setOnImageAvailableListener
            val ts = img.timestamp
            val pf = pending.computeIfAbsent(ts) { PendingFrame(ts) }
            pf.yuvImage = img
            tryMarkComplete(ts)
        }, backgroundHandler)

        val camera = openCamera(cameraId, backgroundHandler)
        val session = createSession(camera, listOf(rawReader.surface, yuvReader.surface), backgroundHandler)

        startGyroRecording()

        try {
            val requests = (0 until totalFrames).map {
                camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(rawReader.surface)
                    addTarget(yuvReader.surface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }.build()
            }

            session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val ts = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: return
                    val pf = pending.computeIfAbsent(ts) { PendingFrame(ts) }
                    pf.captureResult = result
                    pf.exposureTimeNs = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
                    tryMarkComplete(ts)
                }
            }, backgroundHandler)

            val timeoutMs = maxOf(10_000L, totalFrames * 1_500L)
            withTimeout(timeoutMs) {
                while (!latch.await(250, TimeUnit.MILLISECONDS)) {
                    // wait
                }
            }
        } finally {
            stopGyroRecording()
        }

        val completedFrames = pending.values
            .filter { it.isReady() }
            .sortedBy { it.timestampNs }
            .take(totalFrames)

        val frames = completedFrames.mapNotNull { pf ->
            val raw = pf.rawImage ?: return@mapNotNull null
            val result = pf.captureResult ?: return@mapNotNull null
            val yuv = pf.yuvImage

            val exposure = pf.exposureTimeNs
            val gyroSamples = getGyroSamplesForFrame(pf.timestampNs, exposure)
            val gyroVector = computeAvgGyroVector(gyroSamples)
            val sharpness = yuv?.let { computeLumaSharpness(it) } ?: 0f

            RawBurstCaptureFrame(
                timestampNs = pf.timestampNs,
                captureResult = result,
                rawImage = raw,
                yuvImage = yuv,
                gyroSamples = gyroSamples,
                sharpnessScore = sharpness,
                gyroVector = gyroVector
            )
        }

        val selected = selectBestFrames(frames, bestK, preset)

        val dngUris = mutableListOf<android.net.Uri>()
        val rawCacheFiles = mutableListOf<File>()

        selected.forEachIndexed { idx, frame ->
            val timestampMs = frame.timestampNs / 1_000_000L
            val filename = "UltraDetailRAW_${timestampMs}_${preset.name}_$idx.dng"
            val description = "Ultra Detail+ RAW burst (${preset.name})"

            val uri = ImageUtils.createDngUri(
                context = context,
                filename = filename,
                originalDateTaken = System.currentTimeMillis(),
                description = description
            ).getOrElse { throw it }

            context.contentResolver.openOutputStream(uri)?.use { os ->
                val creator = DngCreator(characteristics, frame.captureResult)
                try {
                    creator.writeImage(os, frame.rawImage)
                } finally {
                    kotlin.runCatching { creator.close() }
                }
            } ?: throw IllegalStateException("Failed to open DNG output stream")

            dngUris.add(uri)

            val cacheFile = writeRawCache(frame, idx)
            rawCacheFiles.add(cacheFile)
        }

        pending.values.forEach { pf ->
            kotlin.runCatching { pf.yuvImage?.close() }
            kotlin.runCatching { pf.rawImage?.close() }
        }

        kotlin.runCatching { session.close() }
        kotlin.runCatching { camera.close() }
        kotlin.runCatching { rawReader.close() }
        kotlin.runCatching { yuvReader.close() }
        kotlin.runCatching { backgroundThread.quitSafely() }

        RawBurstCaptureResult(
            dngUris = dngUris,
            rawCacheFiles = rawCacheFiles,
            selectedFrameCount = selected.size,
            capturedFrameCount = frames.size
        )
    }

    private fun chooseYuvSize(characteristics: CameraCharacteristics, rawSize: Size): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1920, 1080)

        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return Size(1920, 1080)
        val targetArea = 1920L * 1080L
        val rawAspect = rawSize.width.toDouble() / rawSize.height.toDouble()

        val aspectFiltered = sizes
            .filter { s ->
                val aspect = s.width.toDouble() / s.height.toDouble()
                kotlin.math.abs(aspect - rawAspect) < 0.15
            }

        val underTarget = aspectFiltered.filter { s ->
            s.width.toLong() * s.height.toLong() <= targetArea
        }

        return underTarget.maxByOrNull { s ->
            s.width.toLong() * s.height.toLong()
        } ?: aspectFiltered.minByOrNull { s ->
            val area = s.width.toLong() * s.height.toLong()
            kotlin.math.abs(area - targetArea)
        } ?: Size(1920, 1080)
    }

    private suspend fun openCamera(cameraId: String, handler: Handler): CameraDevice =
        suspendCancellableCoroutine { cont ->
            try {
                cameraManager.openCamera(
                    cameraId,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            cont.resume(camera)
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            camera.close()
                            if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera disconnected"))
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            camera.close()
                            if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera error: $error"))
                        }
                    },
                    handler
                )
            } catch (e: SecurityException) {
                cont.resumeWithException(e)
            }
        }

    private suspend fun createSession(
        camera: CameraDevice,
        surfaces: List<Surface>,
        handler: Handler
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        camera.createCaptureSession(
            surfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    cont.resume(session)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    cont.resumeWithException(IllegalStateException("Capture session configure failed"))
                }
            },
            handler
        )
    }

    private fun computeAvgGyroVector(samples: List<GyroSample>): FloatArray {
        if (samples.isEmpty()) return floatArrayOf(0f, 0f, 0f)
        var sx = 0f
        var sy = 0f
        var sz = 0f
        for (s in samples) {
            sx += s.rotationX
            sy += s.rotationY
            sz += s.rotationZ
        }
        val n = samples.size.toFloat()
        return floatArrayOf(sx / n, sy / n, sz / n)
    }

    private fun computeLumaSharpness(image: Image): Float {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height

        val step = 4
        var sum = 0f
        var count = 0

        for (y in step until height - step step step) {
            val row = y * rowStride
            for (x in step until width - step step step) {
                val center = buf.get(row + x).toInt() and 0xFF
                val up = buf.get((y - step) * rowStride + x).toInt() and 0xFF
                val down = buf.get((y + step) * rowStride + x).toInt() and 0xFF
                val left = buf.get(row + (x - step)).toInt() and 0xFF
                val right = buf.get(row + (x + step)).toInt() and 0xFF

                val lap = kotlin.math.abs(4 * center - up - down - left - right)
                sum += lap
                count++
            }
        }

        return if (count > 0) sum / count else 0f
    }

    private fun selectBestFrames(
        frames: List<RawBurstCaptureFrame>,
        maxFrames: Int,
        preset: UltraDetailPreset
    ): List<RawBurstCaptureFrame> {
        if (frames.size <= maxFrames) return frames
        if (maxFrames <= 1) return frames.take(1)

        val motionWeight = when (preset) {
            UltraDetailPreset.ULTRA -> 0.4f
            UltraDetailPreset.MAX -> 0.25f
            else -> 0.25f
        }
        val sharpWeight = 1f - motionWeight

        val sharpMin = frames.minOf { it.sharpnessScore }
        val sharpMax = frames.maxOf { it.sharpnessScore }

        fun sharpNorm(v: Float): Float {
            if (sharpMax <= sharpMin) return 0f
            return (v - sharpMin) / (sharpMax - sharpMin)
        }

        fun combinedScore(f: RawBurstCaptureFrame): Float {
            val motion = kotlin.math.sqrt(
                f.gyroVector[0] * f.gyroVector[0] +
                    f.gyroVector[1] * f.gyroVector[1] +
                    f.gyroVector[2] * f.gyroVector[2]
            )
            val motionScaled = (motion / 2f).coerceIn(0f, 1f)
            return sharpWeight * sharpNorm(f.sharpnessScore) + motionWeight * motionScaled
        }

        val remaining = frames.toMutableList()
        val selected = mutableListOf<RawBurstCaptureFrame>()

        val first = remaining.maxByOrNull { combinedScore(it) } ?: return frames.take(maxFrames)
        selected.add(first)
        remaining.remove(first)

        while (selected.size < maxFrames && remaining.isNotEmpty()) {
            val next = remaining.maxByOrNull { candidate ->
                val base = combinedScore(candidate)
                val diversity = selected.minOf { sel -> gyroDistance(candidate.gyroVector, sel.gyroVector) }
                base + 0.25f * (diversity / 2f).coerceIn(0f, 1f)
            } ?: break
            selected.add(next)
            remaining.remove(next)
        }

        return selected
    }

    private fun gyroDistance(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun writeRawCache(frame: RawBurstCaptureFrame, index: Int): File {
        val outFile = File(context.cacheDir, "ultradetail_rawcache_${frame.timestampNs}_$index.raw")
        val img = frame.rawImage
        val plane = img.planes[0]

        val header = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0x52415743) // 'RAWC'
        header.putInt(1) // version
        header.putInt(img.width)
        header.putInt(img.height)
        header.putInt(plane.rowStride)
        header.putInt(plane.pixelStride)
        header.putInt(rawCapability.rawFormat)
        header.putInt(rawCapability.bayerPattern)
        header.putInt(rawCapability.whiteLevel)
        header.putLong(frame.timestampNs)
        header.putInt(0)
        header.putInt(0)
        header.flip()

        FileOutputStream(outFile).use { fos ->
            fos.channel.write(header)
            val dup = plane.buffer.duplicate()
            dup.rewind()
            val chunk = ByteArray(1024 * 1024)
            while (dup.hasRemaining()) {
                val n = minOf(dup.remaining(), chunk.size)
                dup.get(chunk, 0, n)
                fos.write(chunk, 0, n)
            }
        }

        return outFile
    }

    private class PendingFrame(val timestampNs: Long) {
        @Volatile var rawImage: Image? = null
        @Volatile var yuvImage: Image? = null
        @Volatile var captureResult: TotalCaptureResult? = null
        @Volatile var exposureTimeNs: Long? = null
        private val marked = AtomicBoolean(false)

        fun isReady(): Boolean = rawImage != null && captureResult != null

        fun tryMarkReady(): Boolean {
            if (!isReady()) return false
            return marked.compareAndSet(false, true)
        }
    }
}
