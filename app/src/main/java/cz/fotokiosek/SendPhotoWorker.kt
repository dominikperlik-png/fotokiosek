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
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Odeslání jedné fotky. Běží ve frontě WorkManageru, takže když vypadne internet,
 * fotka se pošle automaticky později (i po restartu automatu).
 *
 * Záměrně bez podmínky „připojeno k síti“: na některých deskách (ethernet, starý Android)
 * ji WorkManager nikdy nevyhodnotí jako splněnou a fotky by visely ve frontě navždy.
 * Místo toho se při chybě zkouší znovu s rostoucím odstupem.
 */
class SendPhotoWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        AppLog.init(applicationContext)
        val to = inputData.getString(KEY_TO)
        val file = inputData.getString(KEY_PHOTO)?.let { File(it) }
        if (to == null || file == null || !file.exists()) {
            AppLog.w(TAG_LOG, "Fotka ve frontě už neexistuje (${file?.name}), přeskakuji.")
            return@withContext Result.failure()
        }

        val settings = KioskSettings(applicationContext)
        try {
            Mailer.send(settings, to, settings.subject, settings.body, file)
            settings.sentCount += 1
            AppLog.i(TAG_LOG, "Odesláno: ${file.name} (pokus ${runAttemptCount + 1})")
            Result.success()
        } catch (e: Exception) {
            val time = DateFormat.getDateTimeInstance().format(Date())
            val reason = MailDiagnostics.explain(e, settings)
            settings.lastError = "$time: $reason"
            AppLog.e(TAG_LOG, "Odeslání ${file.name} selhalo (pokus ${runAttemptCount + 1}): $reason", e)
            if (runAttemptCount + 1 >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    companion object {
        const val TAG = "send-photo"
        private const val TAG_LOG = "Fronta"
        private const val KEY_TO = "to"
        private const val KEY_PHOTO = "photo"
        private const val MAX_ATTEMPTS = 200

        fun enqueue(context: Context, photo: File, to: String) {
            val request = OneTimeWorkRequestBuilder<SendPhotoWorker>()
                .setInputData(workDataOf(KEY_TO to to, KEY_PHOTO to photo.absolutePath))
                // 30 s, 60 s, 90 s… (WorkManager to zastropuje na 5 h)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
