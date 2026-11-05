package cz.fotokiosek

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
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
 */
class SendPhotoWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val to = inputData.getString(KEY_TO)
        val file = inputData.getString(KEY_PHOTO)?.let { File(it) }
        if (to == null || file == null || !file.exists()) return@withContext Result.failure()

        val settings = KioskSettings(applicationContext)
        try {
            Mailer.send(settings, to, settings.subject, settings.body, file)
            settings.sentCount += 1
            Result.success()
        } catch (e: Exception) {
            val time = DateFormat.getDateTimeInstance().format(Date())
            settings.lastError = "$time: ${e.message ?: e.javaClass.simpleName}"
            if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    companion object {
        const val TAG = "send-photo"
        private const val KEY_TO = "to"
        private const val KEY_PHOTO = "photo"
        private const val MAX_ATTEMPTS = 50

        fun enqueue(context: Context, photo: File, to: String) {
            val request = OneTimeWorkRequestBuilder<SendPhotoWorker>()
                .setInputData(workDataOf(KEY_TO to to, KEY_PHOTO to photo.absolutePath))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
