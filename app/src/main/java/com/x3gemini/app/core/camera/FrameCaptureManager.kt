package com.x3gemini.app.core.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import android.util.Base64
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FrameCaptureManager(
    private val context: Context
) {

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var lastFrameMs = 0L

    fun start(
        owner: LifecycleOwner,
        previewSurfaceProvider: Preview.SurfaceProvider? = null,
        onFrameBase64: (String) -> Unit
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cameraProvider = future.get()
            provider = cameraProvider

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // 720p target: CameraX picks the closest sensor-supported
                // size. Roughly 12× the pixels of the previous default
                // (~640×480) so saved photos look like real photos
                // instead of thumbnails, while staying small enough for
                // Gemini Live multimodal streaming.
                .setTargetResolution(Size(1280, 720))
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastFrameMs < FRAME_INTERVAL_MS) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                lastFrameMs = now
                val jpeg = imageProxyToJpeg(imageProxy)
                imageProxy.close()

                if (jpeg != null) {
                    val encoded = Base64.encodeToString(jpeg, Base64.NO_WRAP)
                    onFrameBase64(encoded)
                }
            }

            val previewUseCase = previewSurfaceProvider?.let { surfaceProvider ->
                // 4:3 preview — the X3's native photo proportions, full
                // sensor field of view (the 16:9 analysis stream Gemini
                // receives is a crop of this).
                Preview.Builder()
                    .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
                    .build().apply {
                        setSurfaceProvider(surfaceProvider)
                    }
            }

            runCatching {
                cameraProvider.unbindAll()
                bindUseCases(cameraProvider, owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis, previewUseCase)
            }.onFailure {
                runCatching {
                    cameraProvider.unbindAll()
                    bindUseCases(cameraProvider, owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis, previewUseCase)
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        provider?.unbindAll()
    }

    fun shutdown() {
        cameraExecutor.shutdownNow()
    }

    private fun imageProxyToJpeg(image: ImageProxy): ByteArray? {
        return runCatching {
            val nv21 = yuv420ToNv21(image)
            val out = ByteArrayOutputStream()
            val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            yuv.compressToJpeg(Rect(0, 0, image.width, image.height), JPEG_QUALITY, out)
            val jpeg = out.toByteArray()
            // The sensor delivers frames in its native orientation; CameraX
            // reports how far they must be rotated to appear upright via
            // imageInfo.rotationDegrees. YuvImage ignores that, so without this
            // the frames Gemini/Hermes receive are rotated (90° on the X3 Pro),
            // which is why the model reported "the image is rotated." Apply the
            // rotation to the encoded JPEG so downstream consumers always get an
            // upright image.
            val rotation = image.imageInfo.rotationDegrees
            if (rotation == 0) jpeg else rotateJpeg(jpeg, rotation)
        }.getOrNull()
    }

    /** Rotate an encoded JPEG by [degrees] (clockwise) and re-encode. */
    private fun rotateJpeg(jpeg: ByteArray, degrees: Int): ByteArray {
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        return try {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
            val out = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (rotated !== src) rotated.recycle()
            out.toByteArray()
        } catch (e: Throwable) {
            jpeg
        } finally {
            src.recycle()
        }
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        val yBuffer = yPlane.buffer
        var position = 0
        for (row in 0 until height) {
            yBuffer.position(row * yPlane.rowStride)
            yBuffer.get(nv21, position, width)
            position += width
        }

        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val chromaHeight = height / 2
        val chromaWidth = width / 2

        for (row in 0 until chromaHeight) {
            val uRowStart = row * uPlane.rowStride
            val vRowStart = row * vPlane.rowStride
            for (col in 0 until chromaWidth) {
                val uIndex = uRowStart + col * uPlane.pixelStride
                val vIndex = vRowStart + col * vPlane.pixelStride
                val u = uBuffer.getSafe(uIndex)
                val v = vBuffer.getSafe(vIndex)
                nv21[position++] = v
                nv21[position++] = u
            }
        }

        return nv21
    }

    private fun java.nio.ByteBuffer.getSafe(index: Int): Byte {
        return if (index in 0 until limit()) get(index) else 0
    }

    private fun bindUseCases(
        cameraProvider: ProcessCameraProvider,
        owner: LifecycleOwner,
        selector: CameraSelector,
        analysis: ImageAnalysis,
        preview: Preview?
    ) {
        if (preview != null) {
            cameraProvider.bindToLifecycle(owner, selector, analysis, preview)
        } else {
            cameraProvider.bindToLifecycle(owner, selector, analysis)
        }
    }

    companion object {
        private const val FRAME_INTERVAL_MS = 1100L
        // Bumped 62 → 88 so save_photo writes look like real photos
        // (sharp text, recognizable detail) instead of low-bitrate
        // streaming preview frames. The wire cost for Gemini Live
        // is acceptable at 720p.
        private const val JPEG_QUALITY = 88
    }
}
