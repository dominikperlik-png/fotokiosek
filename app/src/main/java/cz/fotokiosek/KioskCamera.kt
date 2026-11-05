package cz.fotokiosek

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.os.SystemClock
import android.util.Rational
import android.util.Size
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
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
 * Kamera Androidu přes CameraX. Funguje s vestavěnými kamerami i s USB webkamerou,
 * pokud ji Android sám rozpozná.
 *
 * Živý náhled NEkreslí PreviewView: na deskách automatů PreviewView často otočí obraz jinak,
 * než jak vyjde fotka. Místo toho bereme snímky z ImageAnalysis a otáčíme/zrcadlíme je
 * úplně stejnou funkcí jako výslednou fotku ([orient]) – náhled tak vždy odpovídá fotce.
 */
class KioskCamera(private val activity: ComponentActivity) {
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var observedState: LiveData<CameraState>? = null
    private var viewWidth = 0
    private var viewHeight = 0
    private var lastFrameAt = 0L

    /**
     * Požadovaná kamera z nastavení. Prázdné = automaticky:
     * 1) externí USB webkamera (LENS_FACING_EXTERNAL), 2) přední, 3) první dostupná.
     */
    var cameraId = ""
    @Volatile var mirror = true
    /** Dodatečné otočení JEN živého náhledu vestavěné kamery (fotka se nemění). Externí USB kamera se neotáčí. */
    @Volatile var previewRotation = 0
    @Volatile private var activeIsExternal = false
    var fill = true
    /** Když je aktivní zrcadlovka, kamera Androidu se vypne. */
    var enabled = true

    /** Sem chodí snímky náhledu (z vlákna na pozadí), už otočené a zrcadlené jako fotka. */
    @Volatile var onFrame: ((Bitmap) -> Unit)? = null

    var activeId by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Doba od stisku spouště po hotovou fotku v ms (poslední měření, pro ladění předstihu). */
    var lastLatencyMs by mutableStateOf(0L)
        private set

    fun start() {
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            provider = runCatching { future.get() }.getOrElse {
                error = "Kamera nejde spustit: ${it.message}"
                AppLog.e(TAG, "ProcessCameraProvider", it)
                null
            }
            bind()
        }, ContextCompat.getMainExecutor(activity))
    }

    /** Velikost plochy náhledu v pixelech – podle ní se fotka ořízne (režim přes celou obrazovku). */
    fun setViewSize(width: Int, height: Int) {
        if (width == viewWidth && height == viewHeight) return
        viewWidth = width
        viewHeight = height
        bind()
    }

    fun stop() {
        runCatching { provider?.unbindAll() }
        imageCapture = null
        activeId = ""
    }

    fun bind() {
        val p = provider ?: return
        if (!enabled) {
            stop()
            return
        }
        if (viewWidth == 0 || viewHeight == 0) return
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
            ?: infos.lastOrNull { facingOf(it) == CameraCharacteristics.LENS_FACING_EXTERNAL } // naposledy připojená USB
            ?: infos.firstOrNull { facingOf(it) == CameraCharacteristics.LENS_FACING_FRONT }
            ?: infos.first()
        if (cameraId.isNotEmpty() && idOf(info) != cameraId) {
            AppLog.w(TAG, "Vybraná kamera $cameraId není připojená, používám ${idOf(info)}")
        }
        val id = idOf(info)
        val selector = CameraSelector.Builder()
            .addCameraFilter { list -> list.filter { idOf(it) == id } }
            .build()

        @Suppress("DEPRECATION")
        val rotation = activity.windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetRotation(rotation)
            .build()
        analysis.setAnalyzer(analysisExecutor) { image -> deliverFrame(image) }

        val capture = ImageCapture.Builder()
            // Nejkratší prodleva spouště – fotka vznikne s nápisem „Úsměv!“, ne o vteřinu později.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(rotation)
            .build()
        val group = UseCaseGroup.Builder().addUseCase(analysis).addUseCase(capture)
        // Přes celou obrazovku: náhled i fotka se oříznou na poměr stran obrazovky.
        if (fill) group.setViewPort(ViewPort.Builder(Rational(viewWidth, viewHeight), rotation).build())

        try {
            p.unbindAll()
            val camera = p.bindToLifecycle(activity, selector, group.build())
            imageCapture = capture
            activeId = id
            activeIsExternal = facingOf(info) == CameraCharacteristics.LENS_FACING_EXTERNAL
            error = null
            observedState?.removeObservers(activity)
            observedState = camera.cameraInfo.cameraState.also { state ->
                state.observe(activity) { s ->
                    error = s.error?.let { "Chyba kamery (kód ${it.code})" }
                    s.error?.let { AppLog.w(TAG, "Stav kamery: chyba ${it.code}") }
                }
            }
            AppLog.i(TAG, "Kamera $id (${facingName(facingOf(info))}) spuštěna, rotace displeje ${rotation * 90}°, fill=$fill; " +
                "Android vidí: ${infos.joinToString { "${idOf(it)}=${facingName(facingOf(it))}" }}")
        } catch (e: Exception) {
            imageCapture = null
            error = "Kameru nelze spustit: ${e.message}"
            AppLog.e(TAG, "bindToLifecycle", e)
        }
    }

    private fun deliverFrame(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            val listener = onFrame
            if (listener == null || now - lastFrameAt < FRAME_INTERVAL_MS) return
            lastFrameAt = now
            // Jen náhled: dodatečné otočení (např. 180°) – na některých deskách ImageAnalysis hlásí jinou
            // rotaci senzoru než ImageCapture, takže náhled byl vzhůru nohama, i když fotka byla správně.
            val extra = if (activeIsExternal) 0 else previewRotation
            listener(orient(image, mirror, PREVIEW_MAX_SIDE, extra))
        } catch (e: Exception) {
            AppLog.w(TAG, "Snímek náhledu", e)
        } finally {
            image.close()
        }
    }

    /** Seznam kamer pro nastavení: id → popis. */
    fun cameras(): List<Pair<String, String>> = provider?.availableCameraInfos.orEmpty().map { info ->
        idOf(info) to "Kamera ${idOf(info)} (${facingName(facingOf(info))})"
    }

    /** Je mezi kamerami Androidu nějaká externí (USB)? */
    fun hasExternal(): Boolean = provider?.availableCameraInfos.orEmpty().any { facingOf(it) == CameraCharacteristics.LENS_FACING_EXTERNAL }

    private fun facingName(facing: Int?) = when (facing) {
        CameraCharacteristics.LENS_FACING_FRONT -> "přední"
        CameraCharacteristics.LENS_FACING_BACK -> "zadní"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "externí USB"
        else -> "neznámá"
    }

    /** Vyfotí, otočí, případně zrcadlí a zmenší fotku na velikost vhodnou pro e-mail. */
    suspend fun takePhoto(): Bitmap {
        val capture = imageCapture ?: throw IllegalStateException("Kamera neběží")
        val doMirror = mirror
        val started = SystemClock.elapsedRealtime()
        return suspendCancellableCoroutine { cont ->
            capture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    lastLatencyMs = SystemClock.elapsedRealtime() - started
                    runCatching { image.use { orient(it, doMirror, MAX_SIDE) } }
                        .onSuccess { cont.resume(it) }
                        .onFailure { cont.resumeWithException(it) }
                }

                override fun onError(exception: ImageCaptureException) {
                    AppLog.e(TAG, "takePicture", exception)
                    cont.resumeWithException(exception)
                }
            })
        }
    }

    fun shutdown() {
        captureExecutor.shutdown()
        analysisExecutor.shutdown()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun idOf(info: CameraInfo): String = Camera2CameraInfo.from(info).cameraId

    @OptIn(ExperimentalCamera2Interop::class)
    private fun facingOf(info: CameraInfo): Int? =
        Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.LENS_FACING)

    companion object {
        private const val TAG = "Kamera"
        /** Delší strana fotky v pixelech – stačí na tisk 10×15 a e-mail má cca 1 MB. */
        const val MAX_SIDE = 2048
        private const val PREVIEW_MAX_SIDE = 1280
        private const val FRAME_INTERVAL_MS = 45L

        /**
         * Ořez (viewport), otočení podle senzoru, zrcadlení a zmenšení.
         * Stejná funkce pro náhled i fotku → obojí má vždy stejnou orientaci.
         */
        fun orient(image: ImageProxy, mirror: Boolean, maxSide: Int, extraRotation: Int = 0): Bitmap {
            var source = image.toBitmap()
            val crop = image.cropRect
            val needsCrop = crop.width() != source.width || crop.height() != source.height
            if (needsCrop && crop.left >= 0 && crop.top >= 0 && crop.right <= source.width && crop.bottom <= source.height) {
                source = Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height())
            }
            val matrix = Matrix()
            matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
            if (mirror) matrix.postScale(-1f, 1f)
            if (extraRotation % 360 != 0) matrix.postRotate(extraRotation.toFloat())
            val longSide = max(source.width, source.height)
            if (longSide > maxSide) {
                val scale = maxSide.toFloat() / longSide
                matrix.postScale(scale, scale)
            }
            return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }
    }
}
