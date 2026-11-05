package cz.fotokiosek

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
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
            // Tiskový dialog Androidu musí jít otevřít i v zamčeném kiosku.
            dpm.setLockTaskPackages(admin, arrayOf(activity.packageName, PRINT_SPOOLER))
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

    /**
     * Restart celé aplikace (nový proces) – CameraX si při startu znovu načte seznam kamer,
     * takže uvidí i USB webkameru připojenou až po spuštění.
     */
    fun restartApp(activity: Activity) {
        AppLog.i("Kiosk", "Restart aplikace z menu správce")
        val intent = Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val flags = PendingIntent.FLAG_CANCEL_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val pending = PendingIntent.getActivity(activity, 4711, intent, flags)
        val alarm = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.set(AlarmManager.RTC, System.currentTimeMillis() + 1200, pending)
        runCatching { activity.stopLockTask() }
        activity.finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    /** Dočasně pustí zámek kiosku kvůli systémovému dialogu (výběr souboru, oprávnění). */
    fun pauseLock(activity: Activity) {
        runCatching { activity.stopLockTask() }
    }

    /**
     * Systémová aplikace „MTP host“ si na novějších Androidech zabírá připojené fotoaparáty
     * a zrcadlovka pak nejde ovládat. Jako správce zařízení ji můžeme skrýt (jde vrátit).
     * Vrací true, pokud se něco změnilo (má smysl zkusit připojení znovu).
     */
    fun releaseUsbFromSystem(activity: Activity): Boolean {
        if (!isDeviceOwner(activity)) return false
        val dpm = dpm(activity)
        val admin = admin(activity)
        var changed = false
        for (pkg in USB_GRABBERS) {
            val installed = runCatching { activity.packageManager.getPackageInfo(pkg, 0) }.isSuccess
            if (!installed) continue
            runCatching {
                if (!dpm.isApplicationHidden(admin, pkg) && dpm.setApplicationHidden(admin, pkg, true)) {
                    AppLog.i("Kiosk", "Skryta systémová aplikace $pkg (zabírala USB fotoaparát)")
                    changed = true
                }
            }
        }
        return changed
    }

    private const val PRINT_SPOOLER = "com.android.printspooler"
    private val USB_GRABBERS = listOf("com.android.mtp")

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
        USB_GRABBERS.forEach { pkg -> runCatching { dpm.setApplicationHidden(admin, pkg, false) } }
        runCatching { dpm.clearDeviceOwnerApp(activity.packageName) }
    }
}
