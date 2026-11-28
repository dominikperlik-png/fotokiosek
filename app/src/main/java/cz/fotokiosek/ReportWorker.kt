package cz.fotokiosek

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Hlášení do webového účetnictví (složka `web/` v repozitáři): každý tisk, AI úprava, vyfocení a e-mail jako jedna
 * událost POST JSON na `report_url` s hlavičkou `Authorization: Bearer <report_token>`.
 * Jde přes frontu WorkManageru jako e-maily – bez internetu se pošle později (i po restartu).
 * Každá událost má UUID, server duplicity ignoruje, takže opakované odeslání nic nezdvojí.
 */
class ReportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        AppLog.init(applicationContext)
        val settings = KioskSettings(applicationContext)
        val event = inputData.getString(KEY_EVENT) ?: return@withContext Result.failure()
        if (settings.reportUrl.isBlank() || settings.reportToken.isBlank()) {
            // Účetnictví mezitím vypnuto – událost zahodit.
            return@withContext Result.success()
        }
        val (code, message) = post(settings.reportUrl, settings.reportToken, event)
        when {
            code in 200..299 -> Result.success()
            code == 401 || code == 403 -> {
                AppLog.e(TAG_LOG, "Účetnictví odmítlo token (HTTP $code) – zkontroluj ho v menu správce")
                if (runAttemptCount + 1 >= MAX_ATTEMPTS) Result.failure() else Result.retry()
            }
            code == 422 -> {
                // Validace účetnictví – událost je špatně a opakování nepomůže.
                AppLog.e(TAG_LOG, "Účetnictví událost odmítlo (HTTP $code): $message")
                Result.failure()
            }
            code == 400 && runAttemptCount + 1 >= 20 -> {
                // 400 může vrátit i proxy/firewall před serverem – chvíli zkoušet, pak vzdát.
                AppLog.e(TAG_LOG, "Účetnictví opakovaně vrací HTTP 400, událost zahazuji: $message")
                Result.failure()
            }
            else -> {
                AppLog.w(TAG_LOG, "Hlášení do účetnictví se nepovedlo (pokus ${runAttemptCount + 1}): $message")
                if (runAttemptCount + 1 >= MAX_ATTEMPTS) Result.failure() else Result.retry()
            }
        }
    }

    companion object {
        const val TAG = "report"
        private const val TAG_LOG = "Účetnictví"
        private const val KEY_EVENT = "event"
        private const val MAX_ATTEMPTS = 500

        /** Tisk [copies] kopií. [source] = foto / galerie, [style] = název AI stylu, nebo null; [paid] = zaplaceno v samoobsluze. */
        fun print(context: Context, settings: KioskSettings, copies: Int, source: String, style: String?, paid: java.math.BigDecimal? = null) =
            enqueue(context, settings, "print", copies, source, style, paid)

        /** Pořízená fotka (z fotoaparátu, ne z galerie). */
        fun photo(context: Context, settings: KioskSettings) =
            enqueue(context, settings, "photo", 0, "foto", null)

        /** Fotka zařazená k odeslání e-mailem ([source] = foto / galerie, [style] = AI styl fotky, nebo null). */
        fun email(context: Context, settings: KioskSettings, source: String, style: String?, paid: java.math.BigDecimal? = null) =
            enqueue(context, settings, "email", 0, source, style, paid)

        /** Povedená AI úprava (placená). */
        fun ai(context: Context, settings: KioskSettings, style: String) =
            enqueue(context, settings, "ai", 0, "ai", style)

        private fun enqueue(
            context: Context, settings: KioskSettings, type: String, copies: Int, source: String, style: String?,
            paid: java.math.BigDecimal? = null,
        ) {
            if (settings.reportUrl.isBlank() || settings.reportToken.isBlank()) return
            val event = JSONObject()
                .put("uuid", UUID.randomUUID().toString())
                .put("type", type)
                .put("copies", copies)
                .put("source", source)
                .put("style", style ?: "")
                .put("mode", settings.printMode)
                .put("device", settings.deviceName)
                .put("time", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
                .apply { if (paid != null) put("paid", Pricing.plain(paid)) } // samoobsluha: zaplacená částka (web ji zatím jen přijme)
                .toString()
            val request = OneTimeWorkRequestBuilder<ReportWorker>()
                .setInputData(workDataOf(KEY_EVENT to event))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            runCatching { WorkManager.getInstance(context).enqueue(request) }
                .onFailure { AppLog.e(TAG_LOG, "Událost nejde zařadit", it) }
        }

        /** Test z menu správce: událost „ping“ (server ji neuloží). Vrací (ok, zpráva). */
        fun test(url: String, token: String): Pair<Boolean, String> {
            if (url.isBlank() || token.isBlank()) return false to "Vyplň adresu i token."
            val (code, message) = post(url, token, JSONObject().put("type", "ping").toString())
            return when {
                code in 200..299 -> true to "Spojení s účetnictvím OK."
                code == 401 || code == 403 -> false to "Server odmítl token – zkontroluj ho (v účetnictví → Nastavení → Automaty)."
                code == 404 -> false to "Adresa nenalezena – má končit na /api.php."
                code < 0 -> false to message
                else -> false to "Server odpověděl HTTP $code: ${message.take(120)}"
            }
        }

        private fun post(url: String, token: String, json: String): Pair<Int, String> = try {
            val body = json.toByteArray(Charsets.UTF_8)
            val conn = URL(url.trim()).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Authorization", "Bearer ${token.trim()}")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
                code to text
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            -1 to "Server nedostupný: ${AppLog.causes(e)}"
        }
    }
}
