package cz.fotokiosek

import android.Manifest
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
    private lateinit var camera: KioskCamera

    /** Čas posledního dotyku – podle něj se kiosek vrací na úvodní obrazovku. */
    private val lastTouch = mutableLongStateOf(SystemClock.elapsedRealtime())

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { camera.bind() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        settings = KioskSettings(this)
        settings.importConfig(this)
        camera = KioskCamera(this).apply {
            cameraId = settings.cameraId
            mirror = settings.mirror
            fill = settings.fillScreen
        }

        Kiosk.setup(this)
        val keepDays = settings.keepDays
        Thread { PhotoStore.cleanup(applicationContext, keepDays) }.start()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }

        // Tlačítko Zpět v kiosku nic nedělá (jednotlivé obrazovky si ho můžou převzít).
        onBackPressedDispatcher.addCallback(this) { }

        setContent { KioskApp(settings, camera, lastTouch) }
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
