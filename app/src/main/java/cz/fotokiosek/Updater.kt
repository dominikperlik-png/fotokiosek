package cz.fotokiosek

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Aktualizace jedním tlačítkem: GitHub Actions po každém pushi sestaví podepsané APK a vydá ho
 * jako release (tag vNNN, NNN = versionCode). Tady ho stáhneme a nainstalujeme přes PackageInstaller.
 *
 * Jako správce zařízení (device owner) se aplikace nainstaluje bez ptaní; po instalaci ji
 * [UpdateReceiver] (MY_PACKAGE_REPLACED) sám spustí. Nastavení (SharedPreferences), logo a log
 * jsou v interní paměti aplikace a aktualizace je nemaže.
 */
object Updater {
    private const val TAG = "Aktualizace"
    const val ACTION_INSTALL_STATUS = "cz.fotokiosek.INSTALL_STATUS"

    class Release(val versionCode: Int, val tag: String, val title: String, val notes: String, val apkUrl: String, val apiUrl: String, val size: Long)

    /** Stav pro menu správce. */
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)
    var progress by mutableStateOf(0f)

    val currentVersion: Int get() = BuildConfig.VERSION_CODE
    val currentName: String get() = BuildConfig.VERSION_NAME

    /** Zjistí poslední vydanou verzi na GitHubu (síť – volat na pozadí). */
    fun latest(settings: KioskSettings): Release {
        val repo = settings.githubRepo.trim().removePrefix("https://github.com/").removeSuffix("/").removeSuffix(".git")
        if (!Regex("^[\\w.-]+/[\\w.-]+$").matches(repo)) throw IllegalStateException("Neplatný repozitář „$repo“ (má být vlastník/název)")
        val conn = open("https://api.github.com/repos/$repo/releases/latest", settings.githubToken, "application/vnd.github+json")
        val code = conn.responseCode
        if (code == 404) throw IllegalStateException("Na GitHubu zatím není žádné vydání (nebo je repozitář soukromý a chybí token).")
        if (code == 401 || code == 403) throw IllegalStateException("GitHub odmítl přístup (HTTP $code). Zkontroluj token, případně limit požadavků.")
        if (code != 200) throw IllegalStateException("GitHub vrátil HTTP $code")
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        conn.disconnect()
        val tag = json.optString("tag_name")
        val assets = json.optJSONArray("assets")
        var apkUrl = ""
        var apiUrl = ""
        var size = 0L
        for (i in 0 until (assets?.length() ?: 0)) {
            val a = assets!!.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) {
                apkUrl = a.optString("browser_download_url")
                apiUrl = a.optString("url")
                size = a.optLong("size")
                break
            }
        }
        if (apkUrl.isEmpty()) throw IllegalStateException("Vydání $tag neobsahuje APK.")
        val versionCode = Regex("\\d+").find(tag)?.value?.toIntOrNull() ?: 0
        return Release(versionCode, tag, json.optString("name", tag), json.optString("body"), apkUrl, apiUrl, size)
    }

    /** Stáhne APK (sleduje přesměrování na úložiště GitHubu bez přeposílání tokenu). */
    fun download(context: Context, settings: KioskSettings, release: Release, onProgress: (Float) -> Unit): File {
        val target = File(context.cacheDir, "update.apk")
        target.delete()
        val token = settings.githubToken.trim()
        var url = if (token.isNotEmpty()) release.apiUrl else release.apkUrl
        var auth = token
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = open(url, auth, "application/octet-stream")
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 300..399) {
                url = conn.getHeaderField("Location") ?: throw IllegalStateException("Přesměrování bez adresy")
                conn.disconnect()
                auth = "" // token patří jen GitHubu, ne úložišti souborů
                if (++hops > 5) throw IllegalStateException("Příliš mnoho přesměrování")
                continue
            }
            if (code != 200) throw IllegalStateException("Stažení selhalo: HTTP $code")
            break
        }
        val total = conn.contentLength.toLong().takeIf { it > 0 } ?: release.size
        conn.inputStream.use { input ->
            FileOutputStream(target).use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        conn.disconnect()

        // Kontrola, že jde opravdu o novější fotokiosek (podpis ověří Android při instalaci).
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(target.absolutePath, 0)
            ?: throw IllegalStateException("Stažený soubor není platné APK.")
        if (info.packageName != context.packageName) throw IllegalStateException("APK patří jiné aplikaci (${info.packageName}).")
        AppLog.i(TAG, "Staženo ${release.tag}: ${target.length() / 1024} kB")
        return target
    }

    /** Spustí instalaci. Výsledek přijde do [UpdateReceiver]. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("fotokiosek.apk", 0, apk.length()).use { out ->
                    input.copyTo(out, 64 * 1024)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, UpdateReceiver::class.java).setAction(ACTION_INSTALL_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            AppLog.i(TAG, "Instaluji aktualizaci (session $sessionId)…")
            session.commit(pending.intentSender)
        }
    }

    private fun open(url: String, token: String, accept: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("User-Agent", "fotokiosek-updater")
        if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${token.trim()}")
        return conn
    }
}

/** Výsledek instalace aktualizace + automatický start po aktualizaci. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLog.init(context)
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                AppLog.i("Aktualizace", "Aplikace aktualizována na ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), spouštím")
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Updater.ACTION_INSTALL_STATUS -> {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        // Aplikace není správce zařízení → Android chce potvrzení od člověka.
                        AppLog.i("Aktualizace", "Instalace čeká na potvrzení")
                        Updater.status = "Potvrď instalaci na obrazovce."
                        @Suppress("DEPRECATION")
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        AppLog.i("Aktualizace", "Instalace dokončena")
                        Updater.status = "Nainstalováno, restartuji…"
                    }
                    else -> {
                        val hint = when (status) {
                            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                                " Nové APK je podepsané jiným klíčem než nainstalovaná aplikace – jednou je potřeba přeinstalovat ručně (viz POSTUP.html → Aktualizace)."
                            PackageInstaller.STATUS_FAILURE_STORAGE -> " Málo místa v automatu."
                            PackageInstaller.STATUS_FAILURE_BLOCKED -> " Instalaci zablokoval systém (není aplikace správcem zařízení? povolit „neznámé zdroje“)."
                            else -> ""
                        }
                        AppLog.e("Aktualizace", "Instalace selhala ($status): $message")
                        Updater.status = "Instalace selhala: $message.$hint"
                        Updater.busy = false
                    }
                }
            }
        }
    }
}
