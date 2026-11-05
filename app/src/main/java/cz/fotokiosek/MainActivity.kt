package cz.fotokiosek

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableLongStateOf
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cz.fotokiosek.ui.KioskApp

class MainActivity : ComponentActivity() {
    private lateinit var settings: KioskSettings
    private lateinit var camera: CameraController

    /** Čas posledního dotyku – podle něj se kiosek vrací na úvodní obrazovku. */
    private val lastTouch = mutableLongStateOf(SystemClock.elapsedRealtime())

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { camera.android.bind() }

    /** Výběr souboru s logem (systémový dialog – kiosek se na tu chvíli odemkne). */
    private var onLogoPicked: ((android.net.Uri?) -> Unit)? = null
    private val pickLogo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        onLogoPicked?.invoke(uri)
        onLogoPicked = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AppLog.init(this)
        AppLog.i("Start", "Fotokiosek ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), Android ${android.os.Build.VERSION.RELEASE}")

        settings = KioskSettings(this)
        if (settings.importConfig(this)) AppLog.i("Start", "Načteno nastavení z config.properties")
        LogoStore.importFromExternal(this)

        camera = CameraController(this, settings)

        Kiosk.setup(this)
        val keepDays = settings.keepDays
        Thread { PhotoStore.cleanup(applicationContext, keepDays) }.start()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
        camera.start()
        camera.onUsbIntent(intent)

        // Tlačítko Zpět v kiosku nic nedělá (jednotlivé obrazovky si ho můžou převzít).
        onBackPressedDispatcher.addCallback(this) { }

        setContent {
            KioskApp(settings, camera, lastTouch, pickLogo = { callback ->
                onLogoPicked = callback
                Kiosk.pauseLock(this)
                runCatching { pickLogo.launch("image/*") }.onFailure {
                    AppLog.e("Logo", "Výběr souboru nejde otevřít", it)
                    onLogoPicked = null
                    callback(null)
                }
            })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        camera.onUsbIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        Kiosk.enter(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastTouch.longValue = SystemClock.elapsedRealtime()
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        camera.shutdown()
        camera.dispose()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
