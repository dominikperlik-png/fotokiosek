package cz.fotokiosek

import android.content.Context
import android.graphics.Bitmap
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Záloha fotek na Google Disk přes webové účetnictví (`web/photos-api.php`, stejný token jako hlášení):
 * každá pořízená fotka a každá AI verze (bez loga) se uloží do `filesDir/upload/<uuid>.jpg` a fronta
 * WorkManageru ji pošle na web, ten ji nahraje na Disk do složky podle data. Bez internetu / s nepřipojeným
 * Diskem se zkouší později; po odeslání (nebo vzdání) se soubor smaže.
 */
class PhotoUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        AppLog.init(applicationContext)
        val settings = KioskSettings(applicationContext)
        val file = File(inputData.getString(KEY_FILE) ?: return@withContext Result.failure())
        if (!file.exists()) return@withContext Result.success()
        val url = photosUrl(settings.reportUrl)
        if (!settings.driveBackup || url == null || settings.reportToken.isBlank()) {
            // Záloha mezitím vypnutá → fotku nenechávat ležet.
            file.delete()
            return@withContext Result.success()
        }
        // Nejdřív levně zjistit, jestli záloha jde (Disk připojený, token platný) – ať se při výpadku
        // neposílá pořád dokola celá fotka.
        val (check, why) = request(url, settings.reportToken.trim(), null)
        if (check !in 200..299) {
            if (runAttemptCount == 0 || runAttemptCount % 10 == 0) {
                AppLog.w(TAG_LOG, "Záloha teď nejde (HTTP $check): $why")
            }
            return@withContext giveUpOrRetry(file)
        }
        val uuid = file.nameWithoutExtension
        val query = "?uuid=$uuid&kind=${inputData.getString(KEY_KIND) ?: "photo"}&taken=${inputData.getLong(KEY_TAKEN, 0)}"
        val (code, message) = request(url + query, settings.reportToken.trim(), file)
        when {
            code in 200..299 -> {
                file.delete()
                Result.success()
            }
            // 413 sem nepatří: to bývá limit serveru (nginx client_max_body_size) – po jeho opravě fotka projde.
            code == 400 || code == 415 || code == 422 -> {
                AppLog.e(TAG_LOG, "Web fotku ${file.name} odmítl (HTTP $code): $message – zahazuji")
                file.delete()
                Result.failure()
            }
            else -> {
                if (runAttemptCount == 0 || runAttemptCount % 10 == 0) {
                    AppLog.w(TAG_LOG, "Záloha ${file.name} se nepovedla (pokus ${runAttemptCount + 1}, HTTP $code): $message")
                }
                giveUpOrRetry(file)
            }
        }
    }

    private fun giveUpOrRetry(file: File): Result {
        if (runAttemptCount + 1 < MAX_ATTEMPTS) return Result.retry()
        AppLog.e(TAG_LOG, "Záloha ${file.name} se nepovedla ani po $MAX_ATTEMPTS pokusech – zahazuji")
        file.delete()
        return Result.failure()
    }

    companion object {
        const val TAG = "upload"
        private const val TAG_LOG = "Záloha"
        private const val KEY_FILE = "file"
        private const val KEY_KIND = "kind"
        private const val KEY_TAKEN = "taken"
        /** Exponenciální čekání (max. 5 h mezi pokusy) → zhruba 2 měsíce zkoušení. */
        private const val MAX_ATTEMPTS = 300
        private const val MIN_FREE_BYTES = 300L * 1024 * 1024
        /** Soubory starší než tohle už fronta nemá (zrušená práce) → úklid. */
        private const val ORPHAN_DAYS = 90L

        private fun dir(context: Context) = File(context.filesDir, "upload").apply { mkdirs() }

        /** Adresa zálohy z adresy účetnictví (…/api.php → …/photos-api.php), jinak null. */
        fun photosUrl(reportUrl: String): String? {
            val url = reportUrl.trim()
            return if (url.endsWith("/api.php", ignoreCase = true)) url.dropLast("api.php".length) + "photos-api.php" else null
        }

        /** Fotky čekající na zálohu (pro menu správce). */
        fun pending(context: Context): Int = dir(context).listFiles()?.count { it.name.endsWith(".jpg") } ?: 0

        /**
         * Zařadit fotku k záloze ([kind] = photo / ai). Volat mimo hlavní vlákno (ukládá JPEG).
         * Nic nedělá, když je záloha vypnutá nebo chybí účetnictví.
         */
        fun enqueue(context: Context, settings: KioskSettings, photo: Bitmap, kind: String) {
            if (!settings.driveBackup || photosUrl(settings.reportUrl) == null || settings.reportToken.isBlank()) return
            val dir = dir(context)
            if (dir.usableSpace < MIN_FREE_BYTES) {
                AppLog.w(TAG_LOG, "Málo místa v automatu – fotka se nezálohuje")
                return
            }
            val file = File(dir, "${UUID.randomUUID()}.jpg")
            try {
                val tmp = File(dir, file.name + ".tmp")
                FileOutputStream(tmp).use { photo.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                if (!tmp.renameTo(file)) throw java.io.IOException("Soubor nejde přejmenovat")
                val request = OneTimeWorkRequestBuilder<PhotoUploadWorker>()
                    .setInputData(
                        workDataOf(KEY_FILE to file.path, KEY_KIND to kind, KEY_TAKEN to System.currentTimeMillis() / 1000),
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .addTag(TAG)
                    .build()
                WorkManager.getInstance(context).enqueue(request)
            } catch (e: Exception) {
                file.delete()
                File(dir, file.name + ".tmp").delete()
                AppLog.e(TAG_LOG, "Fotku nejde zařadit k záloze", e)
            }
        }

        /** Úklid zapomenutých souborů (fronta je už nemá) – volá se při startu. */
        fun cleanup(context: Context) {
            val limit = System.currentTimeMillis() - ORPHAN_DAYS * 24 * 60 * 60 * 1000
            dir(context).listFiles()?.filter { it.name.endsWith(".tmp") || it.lastModified() < limit }?.forEach { it.delete() }
        }

        /** [file] = null → GET (jen kontrola, jestli záloha jde), jinak POST fotky. */
        private fun request(url: String, token: String, file: File?): Pair<Int, String> = try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 200_000 // web nahrává na Disk, než odpoví
                conn.setRequestProperty("Authorization", "Bearer $token")
                if (file != null) {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "image/jpeg")
                    conn.setFixedLengthStreamingMode(file.length())
                    conn.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
                code to text.take(300)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            -1 to "Server nedostupný: ${AppLog.causes(e)}"
        }
    }
}
