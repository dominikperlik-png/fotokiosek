package cz.fotokiosek

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Jedno místo pro obraz a focení: kamera Androidu (CameraX) nebo zrcadlovka Canon EOS přes USB.
 * UI z něj bere živý náhled ([frame]), stav pro menu správce a volá [takePhoto] / [prefocus].
 */
class CameraController(private val activity: ComponentActivity, private val settings: KioskSettings) {
    val android = KioskCamera(activity)
    private val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
    private val main = Handler(Looper.getMainLooper())

    /** Všechna komunikace se zrcadlovkou běží na tomhle jednom vlákně (PTP neumí souběžné příkazy). */
    private val usbDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "canon-usb") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + usbDispatcher)
    /** Zapisuje vlákno canon-usb, čte i hlavní vlákno (takePhoto, applySource) → @Volatile. */
    @Volatile private var canon: CanonCamera? = null
    private var liveViewJob: Job? = null
    @Volatile private var pauseLiveView = false
    private var permissionAsked = false

    enum class Source { ANDROID, CANON }

    var source by mutableStateOf(Source.ANDROID)
        private set
    /** Poslední snímek živého náhledu – otočený a zrcadlený stejně jako výsledná fotka. */
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set
    /** Stav zrcadlovky pro menu správce. */
    var canonStatus by mutableStateOf("Nepřipojena")
        private set
    var canonConnected by mutableStateOf(false)
        private set
    var canonError by mutableStateOf<String?>(null)
        private set

    /** Chyba, kterou má vidět zákazník (žádná kamera k dispozici). */
    val error: String?
        get() = when (source) {
            Source.CANON -> if (canonConnected) null else canonError
            Source.ANDROID -> android.error
        }

    val statusLine: String
        get() = when (source) {
            Source.CANON -> "Zrcadlovka: $canonStatus"
            Source.ANDROID -> android.error ?: "Kamera Androidu ${android.activeId} – OK"
        }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = deviceFrom(intent)
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (device != null && CanonCamera.isCanon(device)) {
                    AppLog.i(TAG, "Připojeno USB: ${device.productName}")
                    permissionAsked = false
                    reconnect()
                } else if (device != null && isWebcam(device)) {
                    AppLog.i(TAG, "Připojena USB webkamera: ${device.productName}")
                    // Android ji zaregistruje chvíli po připojení – pak ji CameraX nabídne jako „externí“.
                    main.postDelayed({ android.bind() }, 2500)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> if (device != null && device.deviceName == canon?.device?.deviceName) {
                    AppLog.i(TAG, "Zrcadlovka odpojena")
                    disconnectCanon("Odpojena (kabel / vypnutá)")
                    applySource()
                } else if (device != null && isWebcam(device)) {
                    AppLog.i(TAG, "Odpojena USB webkamera: ${device.productName}")
                    main.postDelayed({ android.bind() }, 1500)
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    AppLog.i(TAG, "Oprávnění k USB: $granted")
                    if (granted) reconnect() else main.post { canonStatus = "Oprávnění k USB zamítnuto"; canonError = canonStatus }
                }
            }
        }
    }

    fun start() {
        android.onFrame = { bmp -> if (source == Source.ANDROID) postFrame(bmp) }
        android.cameraId = settings.cameraId
        android.mirror = settings.mirror
        android.fill = settings.fillScreen
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(activity, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        android.start()
        reconnect()
    }

    /** Aplikace dostala USB zařízení přímo (spuštění přes připojení fotoaparátu). */
    fun onUsbIntent(intent: Intent) {
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) reconnect()
    }

    /** Po změně nastavení v menu správce. */
    fun applySettings() {
        android.cameraId = settings.cameraId
        android.mirror = settings.mirror
        android.fill = settings.fillScreen
        reconnect()
    }

    fun setViewSize(width: Int, height: Int) = android.setViewSize(width, height)

    /** Najde zrcadlovku, vyžádá oprávnění, převezme ji od systému a spustí živý náhled. */
    fun reconnect() {
        scope.launch {
            disconnectCanonInternal()
            val wantCanon = settings.cameraSource != "android"
            val device = if (wantCanon) usb.deviceList.values.firstOrNull { CanonCamera.isCanon(it) } else null
            if (device == null) {
                post {
                    canonConnected = false
                    canonStatus = if (wantCanon) "Nenalezena – zapni ji a připoj USB kabelem" else "Vypnuto v nastavení"
                    canonError = if (settings.cameraSource == "canon") "Zrcadlovka není připojená. Zapni ji a zkontroluj USB kabel." else null
                }
                applySource()
                return@launch
            }
            if (!usb.hasPermission(device)) {
                post { canonStatus = "Čekám na povolení USB na obrazovce"; canonError = canonStatus }
                if (!permissionAsked) {
                    permissionAsked = true
                    requestPermission(device)
                }
                applySource()
                return@launch
            }
            post { canonStatus = "Připojuji ${device.productName ?: "Canon"}…" }
            var cam = CanonCamera(usb, device, settings.canonSaveToCard)
            try {
                try {
                    cam.open()
                } catch (e: Exception) {
                    // Fotoaparát si mohl zabrat systém (aplikace „MTP host“). Skryjeme ji a zkusíme znovu.
                    AppLog.w(TAG, "První pokus o připojení selhal: ${e.message}")
                    if (withContext(Dispatchers.Main) { Kiosk.releaseUsbFromSystem(activity) }) {
                        delay(1500)
                        cam = CanonCamera(usb, device, settings.canonSaveToCard)
                        cam.open()
                    } else throw e
                }
                canon = cam
                post {
                    canonConnected = true
                    canonError = null
                    canonStatus = "${cam.model} – připojena"
                }
                applySource()
                startLiveView(cam)
            } catch (e: Exception) {
                AppLog.e(TAG, "Připojení zrcadlovky selhalo", e)
                runCatching { cam.close() }
                post {
                    canonConnected = false
                    canonStatus = "Chyba: ${e.message}"
                    canonError = "Zrcadlovku nejde připojit: ${e.message}"
                }
                applySource()
            }
        }
    }

    private fun startLiveView(cam: CanonCamera) {
        liveViewJob?.cancel()
        liveViewJob = scope.launch {
            var failures = 0
            while (isActive && canon === cam) {
                if (pauseLiveView) { delay(30); continue }
                try {
                    val jpeg = cam.liveViewFrame()
                    if (jpeg != null) {
                        failures = 0
                        postFrame(CanonCamera.decode(jpeg, settings.canonRotation, settings.mirror, 1280))
                    }
                } catch (e: Exception) {
                    if (++failures >= 5) {
                        AppLog.e(TAG, "Živý náhled zrcadlovky selhává", e)
                        disconnectCanon("Spojení ztraceno: ${e.message}")
                        applySource()
                        break
                    }
                }
                delay(40)
            }
        }
    }

    private fun disconnectCanon(status: String) {
        scope.launch {
            disconnectCanonInternal()
            post {
                canonConnected = false
                canonStatus = status
                canonError = if (settings.cameraSource == "canon") status else null
            }
        }
    }

    private fun disconnectCanonInternal() {
        liveViewJob?.cancel()
        liveViewJob = null
        canon?.let { runCatching { it.close() } }
        canon = null
    }

    /** Vybere zdroj obrazu: zrcadlovka, když je připojená (a povolená), jinak kamera Androidu. */
    private fun applySource() = post {
        val useCanon = canon != null && settings.cameraSource != "android"
        val newSource = if (useCanon || settings.cameraSource == "canon") Source.CANON else Source.ANDROID
        if (newSource != source) frame = null
        source = newSource
        android.enabled = newSource == Source.ANDROID
        android.bind()
    }

    /** Namáčknutí spouště zrcadlovky (zaostření, expozice) – těsně před koncem odpočtu. */
    fun prefocus() {
        val cam = canon ?: return
        if (source != Source.CANON) return
        scope.launch { runCatching { cam.halfPress() }.onFailure { AppLog.w(TAG, "Namáčknutí spouště", it) } }
    }

    suspend fun takePhoto(): Bitmap = when (source) {
        Source.ANDROID -> android.takePhoto()
        Source.CANON -> {
            val cam = canon ?: throw IllegalStateException(canonError ?: "Zrcadlovka není připojená")
            withContext(usbDispatcher) {
                pauseLiveView = true
                try {
                    val jpeg = cam.capture()
                    CanonCamera.decode(jpeg, settings.canonRotation, settings.mirror, KioskCamera.MAX_SIDE)
                } catch (e: Exception) {
                    AppLog.e(TAG, "Focení zrcadlovkou selhalo", e)
                    throw e
                } finally {
                    pauseLiveView = false
                }
            }
        }
    }

    fun shutdown() {
        runCatching { activity.unregisterReceiver(usbReceiver) }
        disconnectCanonInternal()
        android.shutdown()
    }

    private fun postFrame(bitmap: Bitmap) = post { frame = bitmap.asImageBitmap() }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun requestPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(activity.packageName)
        val pi = PendingIntent.getBroadcast(activity, 0, intent, flags)
        main.post {
            // Dialog oprávnění patří systému – v zamčeném kiosku by se nezobrazil.
            // Po zavření dialogu se kiosek v onResume zase zamkne.
            runCatching { activity.stopLockTask() }
            runCatching { usb.requestPermission(device, pi) }
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceFrom(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    fun dispose() = scope.cancel()

    /** USB zařízení třídy Video (UVC) – webkamera. */
    private fun isWebcam(device: UsbDevice): Boolean =
        device.deviceClass == UsbConstants.USB_CLASS_VIDEO ||
            (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }

    /** Připojené USB webkamery podle USB (i ty, které Android jako kameru nezná) – pro diagnostiku v menu. */
    fun usbWebcams(): List<String> = usb.deviceList.values.filter { isWebcam(it) }.map { it.productName ?: "USB kamera ${it.vendorId}:${it.productId}" }

    companion object {
        private const val TAG = "Kamera"
        const val ACTION_USB_PERMISSION = "cz.fotokiosek.USB_PERMISSION"
    }
}
