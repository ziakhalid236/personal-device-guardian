package com.zia.pdg

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Takes one still photo with the front camera when the OWNER triggers
 * `request_photo` from the dashboard. Android shows the camera indicator
 * while the camera is open — the photo is never silent or hidden.
 */
object PhotoCapture {
    suspend fun takePhoto(context: Context): ByteArray? {
        val output = File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        return suspendCancellableCoroutine { cont ->
            try {
                val providerFuture = ProcessCameraProvider.getInstance(context)
                providerFuture.addListener({
                    try {
                        val provider = providerFuture.get()
                        val owner = ServiceLifecycleOwner()
                        owner.start()
                        val capture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build()
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            owner,
                            CameraSelector.DEFAULT_FRONT_CAMERA,
                            capture
                        )
                        capture.takePicture(
                            ImageCapture.OutputFileOptions.Builder(output).build(),
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                                    try { provider.unbindAll() } catch (e: Exception) { }
                                    owner.stop()
                                    if (cont.isActive) cont.resume(output.readBytes())
                                    output.delete()
                                }
                                override fun onError(e: ImageCaptureException) {
                                    try { provider.unbindAll() } catch (e2: Exception) { }
                                    owner.stop()
                                    if (cont.isActive) cont.resume(null)
                                }
                            }
                        )
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                }, ContextCompat.getMainExecutor(context))
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    /** Minimal LifecycleOwner so CameraX can run from a Service. */
    private class ServiceLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun start() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        fun stop() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
}
