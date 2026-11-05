package cz.fotokiosek

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/**
 * Obsluha kamery přes CameraX. Funguje s vestavěnými kamerami i s USB kamerou,
 * pokud ji Android sám rozpozná (jinak bude potřeba knihovna pro UVC kamery).
 */
class KioskCamera(private val activity: ComponentActivity) {
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var previewView: PreviewView? = null
    private var imageCapture: ImageCapture? = null
    private var observedState: LiveData<CameraState>? = null

    /** Požadovaná kamera z nastavení; prázdné = přední, jinak první dostupná. */
    var cameraId = ""
    var mirror = true
    var fill = true

    var activeId by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun attach(view: PreviewView) {
        previewView = view
        view.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) bind()
        }
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            provider = future.get()
            bind()
        }, ContextCompat.getMainExecutor(activity))
    }

    fun bind() {
        val p = provider ?: return
        val view = previewView ?: return
        if (view.width == 0 || view.height == 0) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            error = "Aplikace nemá oprávnění ke kameře."
            return
        }

        val infos = p.availableCameraInfos
        if (infos.isEmpty()) {
            p.unbindAll()
            error = "Kamera nenalezena. Android tuhle kameru nevidí."
            return
        }
        val info = infos.firstOrNull { idOf(it) == cameraId }
            ?: infos.firstOrNull { facingOf(it) == CameraCharacteristics.LENS_FACING_FRONT }
            ?: infos.first()
        val id = idOf(info)
        val isFront = facingOf(info) == CameraCharacteristics.LENS_FACING_FRONT
        val selector = CameraSelector.Builder()
            .addCameraFilter { list -> list.filter { idOf(it) == id } }
            .build()

        // PreviewView sám zrcadlí přední kameru; tady to srovnáme podle nastavení.
        view.scaleType = if (fill) PreviewView.ScaleType.FILL_CENTER else PreviewView.ScaleType.FIT_CENTER
        view.scaleX = if (mirror != isFront) -1f else 1f

        val rotation = view.display?.rotation ?: Surface.ROTATION_0
        val preview = Preview.Builder().setTargetRotation(rotation).build()
        preview.setSurfaceProvider(view.surfaceProvider)
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(rotation)
            .build()
        val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture)
        // Při náhledu přes celou obrazovku se fotka ořízne stejně jako náhled.
        if (fill) view.viewPort?.let { group.setViewPort(it) }

        try {
            p.unbindAll()
            val camera = p.bindToLifecycle(activity, selector, group.build())
            imageCapture = capture
            activeId = id
            error = null
            observedState?.removeObservers(activity)
            observedState = camera.cameraInfo.cameraState.also { state ->
                state.observe(activity) { s -> error = s.error?.let { "Chyba kamery (kód ${it.code})" } }
            }
        } catch (e: Exception) {
            imageCapture = null
            error = "Kameru nelze spustit: ${e.message}"
        }
    }

    /** Seznam kamer pro nastavení: id → popis. */
    fun cameras(): List<Pair<String, String>> = provider?.availableCameraInfos.orEmpty().map { info ->
        val facing = when (facingOf(info)) {
            CameraCharacteristics.LENS_FACING_FRONT -> "přední"
            CameraCharacteristics.LENS_FACING_BACK -> "zadní"
            else -> "externí / USB"
        }
        idOf(info) to "Kamera ${idOf(info)} ($facing)"
    }

    /** Vyfotí, otočí, případně zrcadlí a zmenší fotku na velikost vhodnou pro e-mail. */
    suspend fun takePhoto(): Bitmap {
        val capture = imageCapture ?: throw IllegalStateException("Kamera neběží")
        val doMirror = mirror
        return suspendCancellableCoroutine { cont ->
            capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    runCatching { image.use { toFinalBitmap(it, doMirror) } }
                        .onSuccess { cont.resume(it) }
                        .onFailure { cont.resumeWithException(it) }
                }

                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            })
        }
    }

    fun shutdown() {
        executor.shutdown()
    }

    private fun toFinalBitmap(image: ImageProxy, mirror: Boolean): Bitmap {
        var source = image.toBitmap()
        val crop = image.cropRect
        val needsCrop = crop.width() != source.width || crop.height() != source.height
        if (needsCrop && crop.left >= 0 && crop.top >= 0 && crop.right <= source.width && crop.bottom <= source.height) {
            source = Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height())
        }
        val matrix = Matrix()
        matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
        if (mirror) matrix.postScale(-1f, 1f)
        val longSide = max(source.width, source.height)
        if (longSide > MAX_SIDE) {
            val scale = MAX_SIDE.toFloat() / longSide
            matrix.postScale(scale, scale)
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun idOf(info: CameraInfo): String = Camera2CameraInfo.from(info).cameraId

    @OptIn(ExperimentalCamera2Interop::class)
    private fun facingOf(info: CameraInfo): Int? =
        Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.LENS_FACING)

    private companion object {
        /** Delší strana fotky v pixelech – stačí na tisk 10×15 a e-mail má cca 1 MB. */
        const val MAX_SIDE = 2048
    }
}
