package cz.fotokiosek

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Jednoduchý log do souboru v interní paměti (filesDir/logs/fotokiosek.log).
 * Zobrazuje se v menu správce, aby šla příčina chyby vidět přímo na automatu.
 * Hesla se sem nikdy nezapisují.
 */
object AppLog {
    private const val MAX_BYTES = 256 * 1024L
    private var file: File? = null
    private val time = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.US)

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        file = File(dir, "fotokiosek.log")
    }

    fun i(tag: String, message: String) = write("I", tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = write("W", tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = write("E", tag, message, error)

    @Synchronized
    private fun write(level: String, tag: String, message: String, error: Throwable?) {
        when (level) {
            "E" -> Log.e("Fotokiosek/$tag", message, error)
            "W" -> Log.w("Fotokiosek/$tag", message, error)
            else -> Log.i("Fotokiosek/$tag", message)
        }
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) {
                val old = File(f.parentFile, "fotokiosek.old.log")
                old.delete()
                f.renameTo(old)
            }
            val line = buildString {
                append(time.format(Date())).append(' ').append(level).append('/').append(tag).append(": ").append(message)
                if (error != null) append('\n').append(stackTrace(error))
                append('\n')
            }
            f.appendText(line)
        }
    }

    /** Posledních [lines] řádků logu (pro zobrazení v administraci). */
    @Synchronized
    fun tail(lines: Int = 80): String {
        val f = file ?: return ""
        return runCatching { f.readLines().takeLast(lines).joinToString("\n") }.getOrDefault("")
    }

    @Synchronized
    fun clear() {
        file?.let { runCatching { it.writeText("") } }
    }

    /** Řetězec příčin chyby v čitelné podobě: „SSLHandshakeException: … ← CertPathValidatorException: …“. */
    fun causes(error: Throwable): String {
        val parts = mutableListOf<String>()
        var t: Throwable? = error
        while (t != null && parts.size < 6) {
            parts += "${t.javaClass.simpleName}: ${t.message ?: ""}".trim()
            t = if (t.cause === t) null else t.cause
        }
        return parts.distinct().joinToString(" ← ")
    }

    private fun stackTrace(error: Throwable): String {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        return sw.toString().lines().take(25).joinToString("\n")
    }
}
