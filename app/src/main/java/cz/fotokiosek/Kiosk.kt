package cz.fotokiosek

import android.Manifest
import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/** Správce zařízení – registruje se příkazem `dpm set-device-owner` (viz POSTUP.html). */
class KioskAdminReceiver : DeviceAdminReceiver()

/**
 * Kioskový režim. Plně funguje jen když je aplikace „správce zařízení“ (device owner):
 * pak nejde aplikaci opustit, zmizí lišta a aplikace je napevno domovskou obrazovkou.
 * Bez toho aspoň běží přes celou obrazovku a jako domovská obrazovka.
 */
object Kiosk {
    private fun dpm(context: Context) = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private fun admin(context: Context) = ComponentName(context, KioskAdminReceiver::class.java)

    fun isDeviceOwner(context: Context) = dpm(context).isDeviceOwnerApp(context.packageName)

    /** Jednorázové nastavení při startu aplikace. */
    fun setup(activity: Activity) {
        if (!isDeviceOwner(activity)) return
        val dpm = dpm(activity)
        val admin = admin(activity)
        runCatching {
            dpm.setLockTaskPackages(admin, arrayOf(activity.packageName))
            val home = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(admin, home, ComponentName(activity, MainActivity::class.java))
        }
        if (Build.VERSION.SDK_INT >= 23) {
            runCatching { dpm.setKeyguardDisabled(admin, true) }
            runCatching {
                dpm.setPermissionGrantState(
                    admin, activity.packageName, Manifest.permission.CAMERA,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            }
        }
    }

    /** Zamknutí do aplikace – volá se při každém návratu do aplikace. */
    fun enter(activity: Activity) {
        if (!isDeviceOwner(activity)) return
        if (Build.VERSION.SDK_INT >= 23) runCatching { dpm(activity).setStatusBarDisabled(admin(activity), true) }
        activity.window.decorView.post { runCatching { activity.startLockTask() } }
    }

    /** Dočasné opuštění kiosku (Wi-Fi, nastavení Androidu). Tlačítko Domů vrátí zpět do aplikace. */
    fun openSystemScreen(activity: Activity, action: String) {
        runCatching { activity.stopLockTask() }
        if (isDeviceOwner(activity) && Build.VERSION.SDK_INT >= 23) {
            runCatching { dpm(activity).setStatusBarDisabled(admin(activity), false) }
        }
        runCatching { activity.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Zruší správce zařízení – potřeba před odinstalováním aplikace. */
    fun removeDeviceOwner(activity: Activity) {
        val dpm = dpm(activity)
        val admin = admin(activity)
        runCatching { activity.stopLockTask() }
        runCatching { dpm.clearPackagePersistentPreferredActivities(admin, activity.packageName) }
        if (Build.VERSION.SDK_INT >= 23) {
            runCatching { dpm.setStatusBarDisabled(admin, false) }
            runCatching { dpm.setKeyguardDisabled(admin, false) }
        }
        runCatching { dpm.setLockTaskPackages(admin, emptyArray<String>()) }
        runCatching { dpm.clearDeviceOwnerApp(activity.packageName) }
    }
}
