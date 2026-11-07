package com.phonoteka.ui.components

import android.content.pm.PackageManager
import android.Manifest
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Local back-camera decoder. Never stores frames or sends camera bytes to a service. */
@Composable fun OwnedQrScanner(modifier: Modifier = Modifier, onCode: (String) -> Unit, onError: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latestCode by rememberUpdatedState(onCode)
    val latestError by rememberUpdatedState(onError)
    val previewView = remember(context) { PreviewView(context) }
    AndroidView(factory = { previewView }, modifier = modifier)
    DisposableEffect(owner, previewView) {
        val alive = AtomicBoolean(true)
        val delivered = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(1280, 720)).build()
        analysis.setAnalyzer(executor) { image ->
            try {
                if (alive.get() && !delivered.get() && image.width * image.height <= 1920 * 1080) {
                    val plane = image.planes.first()
                    val buffer = plane.buffer.duplicate()
                    val y = ByteArray(image.width * image.height)
                    for (row in 0 until image.height) for (column in 0 until image.width) {
                        y[row * image.width + column] = buffer.get(row * plane.rowStride + column * plane.pixelStride)
                    }
                    val source = PlanarYUVLuminanceSource(y, image.width, image.height, 0, 0, image.width, image.height, false)
                    val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true))
                    if (result.text.startsWith("PHONOTEKA-INVITE-1\n") && result.text.length <= 512 && delivered.compareAndSet(false, true)) {
                        ContextCompat.getMainExecutor(context).execute { if (alive.get()) latestCode(result.text) }
                    }
                }
            } catch (_: ReaderException) { /* No QR in this frame; expected, no logging. */ }
            catch (_: Exception) {
                if (delivered.compareAndSet(false, true)) ContextCompat.getMainExecutor(context).execute { if (alive.get()) latestError() }
            } finally { image.close() }
        }
        future.addListener({
            if (alive.get()) try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) throw SecurityException("Camera permission absent")
                provider = future.get()
                provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) { if (alive.get()) latestError() }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            alive.set(false); analysis.clearAnalyzer()
            provider?.unbind(preview, analysis) // Only our use cases; never unbind another feature.
            executor.shutdown()
        }
    }
}
