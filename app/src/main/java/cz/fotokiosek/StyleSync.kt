package cz.fotokiosek

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Knihovna AI stylů z webového účetnictví (`web/styles-api.php`, stejný token jako hlášení tisků):
 * seznam + náhledy se stáhnou do `filesDir/styles/` (styles.json + `<id>-<hash>.jpg`). Nový stav se zapíše,
 * až když se stáhne celý – při výpadku internetu zůstane poslední funkční. Náhledy mají hash v názvu,
 * takže se stahují jen změněné. Spouští se při startu aplikace, každých 6 h a tlačítkem v menu správce.
 */
object StyleSync {
    private const val TAG = "AI styly"
    private const val WORK = "style-sync"
    private val ID = Regex("^[a-z0-9-]{1,40}$")
    private val THUMB = Regex("^[a-z0-9-]{1,40}-[0-9a-f]{8}\\.(jpg|png|webp)$")
    private val lock = Any()

    /** Max. délka promptu – stejný limit má web (`PROMPT_MAX` ve `web/inc/styles.php`). */
    const val PROMPT_MAX = 8000

    /** Zvýší se po každé synchronizaci se změnou – Compose podle něj znovu načte seznam. */
    var version by mutableIntStateOf(0)
        private set

    private fun dir(context: Context) = File(context.filesDir, "styles")
    private fun listFile(context: Context) = File(dir(context), "styles.json")

    /** Adresa knihovny z adresy účetnictví (…/api.php → …/styles-api.php), jinak null. */
    fun stylesUrl(reportUrl: String): String? {
        val url = reportUrl.trim()
        return if (url.endsWith("/api.php", ignoreCase = true)) url.dropLast("api.php".length) + "styles-api.php" else null
    }

    /** Stažené styly (s cestou k náhledu), null = ještě nikdy nestaženo. */
    fun cached(context: Context): List<AiStyle>? {
        val file = listFile(context)
        if (!file.exists()) return null
        return runCatching { parse(file.readText(), dir(context)) }
            .onFailure { AppLog.e(TAG, "Uložená knihovna stylů nejde přečíst", it) }
            .getOrNull()
    }

    /** Server → styly; [thumbDir] = kde leží náhledy (null = cesty neřešit). Neplatné položky přeskočí. */
    fun parse(json: String, thumbDir: File?): List<AiStyle> {
        val arr = JSONObject(json).getJSONArray("styles")
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            val name = o.optString("name").trim()
            val prompt = o.optString("prompt").trim()
            if (!ID.matches(id) || name.isEmpty() || prompt.isEmpty()) return@mapNotNull null
            val thumb = o.optString("thumb").takeIf { !o.isNull("thumb") && THUMB.matches(it) }
            AiStyle(
                id = id,
                name = name.take(40),
                icon = o.optString("icon").take(8),
                prompt = prompt.take(PROMPT_MAX),
                // S [thumbDir] jen existující soubor (nestažený náhled = ikona).
                thumb = thumb?.let { if (thumbDir == null) it else File(thumbDir, it).takeIf { f -> f.exists() }?.path },
            )
        }.distinctBy { it.id }
    }

    /** Pravidla z webu: max. kopií (0 = neurčuje) a ceny (text čísla; "" = neplatná/neznámá → neprodávat) pro samoobslužný režim. */
    data class WebConfig(val maxCopies: Int, val pricePhoto: String, val priceAi: String, val priceEmail: String, val currency: String)

    /** `config` z odpovědi styles-api.php; null = server ho neposílá (starší web). */
    fun parseConfig(json: String): WebConfig? {
        val c = JSONObject(json).optJSONObject("config") ?: return null
        fun price(key: String): String {
            val v = c.optDouble(key, Double.NaN)
            if (v.isNaN() || v < 0 || v > 100_000) return ""
            val d = java.math.BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP)
            // Starší Android vrací u nuly ze stripTrailingZeros „0.00“.
            return if (d.signum() == 0) "0" else d.stripTrailingZeros().toPlainString()
        }
        return WebConfig(
            maxCopies = if (c.isNull("max_copies")) 0 else c.optInt("max_copies", 0).coerceIn(0, 20),
            pricePhoto = price("price_photo"),
            priceAi = price("price_ai"),
            priceEmail = price("price_email"),
            currency = (if (c.isNull("currency")) "" else c.optString("currency")).trim().take(10).ifEmpty { "Kč" },
        )
    }

    /** Stáhne knihovnu. Vrací (ok, zpráva pro správce). Volat mimo hlavní vlákno. */
    fun sync(context: Context, settings: KioskSettings, reportUrl: String, token: String): Pair<Boolean, String> = synchronized(lock) {
        val result = try {
            val url = stylesUrl(reportUrl)
                ?: throw SyncException(
                    if (reportUrl.isBlank()) "Není nastavené účetnictví (adresa a token) – používají se přibalené styly."
                    else "Adresa účetnictví má končit na /api.php.",
                )
            if (token.isBlank()) throw SyncException("Chybí token automatu (sekce Účetnictví).")
            val (code, body) = get(url, token.trim())
            when {
                code == 401 || code == 403 -> throw SyncException("Server odmítl token – zkontroluj ho v sekci Účetnictví.")
                code == 404 -> throw SyncException("Na serveru chybí styles-api.php – aktualizuj web účetnictví.")
                code != 200 -> throw SyncException("Server odpověděl HTTP $code.")
            }
            val json = String(body, Charsets.UTF_8)
            val styles = runCatching { parse(json, null) }.getOrElse { throw SyncException("Server poslal neplatná data.") }
            // Pravidla z webu hned (nezávisle na náhledech); starší web je neposílá → nechat, jak jsou.
            parseConfig(json)?.let { c ->
                settings.webMaxCopies = c.maxCopies
                settings.pricePhoto = c.pricePhoto
                settings.priceAi = c.priceAi
                settings.priceEmail = c.priceEmail
                settings.priceCurrency = c.currency
            }
            val dir = dir(context).apply { mkdirs() }
            var downloaded = 0
            styles.mapNotNull { it.thumb }.distinct().forEach { name ->
                val target = File(dir, name)
                if (target.length() > 0) return@forEach
                val (tc, bytes) = get(url + "?thumb=" + URLEncoder.encode(name, "UTF-8"), token.trim())
                if (tc == 404) {
                    // Na serveru chybí (nebo se mezitím změnil) – styl zatím jen s ikonou, ostatní se synchronizují dál.
                    AppLog.w(TAG, "Náhled $name na serveru není – styl bude s ikonou")
                    return@forEach
                }
                if (tc != 200) throw SyncException("Náhled $name nejde stáhnout (HTTP $tc).")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0) throw SyncException("Náhled $name není obrázek.")
                val tmp = File(dir, "$name.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) throw SyncException("Náhled nejde uložit.")
                downloaded++
            }
            // Poprvé ze staženého seznamu: zachovat dosavadní výběr (nové styly z webu pak budou vypnuté).
            var note = ""
            if (settings.aiStylesOn == null) {
                settings.aiStylesOn = AiStyles.enabledIds(settings)
                val lost = AiStyles.locallyChanged(settings)
                if (lost.isNotEmpty()) {
                    note = " Vlastní nebo upravené styly z automatu (${lost.joinToString()}) teď platí z webu – případně je přidej na webu."
                    AppLog.w(TAG, "Místní vlastní/upravené styly se už nepoužijí: ${lost.joinToString()}")
                }
            }
            // Smazané styly z výběru vyřadit (stejné id založené znovu pak nebude samo zapnuté).
            val ids = styles.map { it.id }.toSet()
            settings.aiStylesOn = settings.aiStylesOn.orEmpty().filter { it in ids }.toSet()
            val old = listFile(context).takeIf { it.exists() }?.readText()
            if (old != json) {
                val tmp = File(dir, "styles.json.tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(listFile(context))) throw SyncException("Seznam stylů nejde uložit.")
            }
            // Úklid: náhledy, které už žádný styl nemá (a nedokončená stažení).
            val keep = styles.mapNotNull { it.thumb }.toSet() + "styles.json"
            dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
            if (old != json || downloaded > 0) version++
            AppLog.i(TAG, "Synchronizováno: ${styles.size} stylů, staženo náhledů: $downloaded")
            true to "${styles.size} stylů z webu, synchronizováno ${now()}.$note"
        } catch (e: SyncException) {
            AppLog.w(TAG, "Synchronizace se nepovedla: ${e.message}")
            false to "${e.message} (${now()})"
        } catch (e: Exception) {
            AppLog.w(TAG, "Synchronizace se nepovedla: ${AppLog.causes(e)}")
            false to "Server nedostupný: ${AppLog.causes(e)} (${now()})"
        }
        settings.styleSyncInfo = (if (result.first) "✔ " else "✘ ") + result.second
        result
    }

    private class SyncException(message: String) : Exception(message)

    private fun now() = SimpleDateFormat("d. M. HH:mm", Locale.getDefault()).format(Date())

    private fun get(url: String, token: String): Pair<Int, ByteArray> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            val code = conn.responseCode
            val stream = (if (code in 200..299) conn.inputStream else conn.errorStream) ?: return code to ByteArray(0)
            stream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > 5_000_000) throw SyncException("Odpověď serveru je moc velká.")
                }
                return code to out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Při startu aplikace: hned jednou a pak každých 6 h (WorkManager, přežije restart). */
    fun schedule(context: Context) {
        runCatching {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniqueWork("$WORK-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<StyleSyncWorker>().build())
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<StyleSyncWorker>(6, TimeUnit.HOURS).build())
        }.onFailure { AppLog.e(TAG, "Synchronizaci nejde naplánovat", it) }
    }

    // ---------------------------------------------------------------- náhledy pro UI

    /** Mezipaměť náhledů, max. 6 MB. */
    private val bitmaps = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Náhled zmenšený tak, aby delší strana byla kolem [maxSize] px (RGB_565, v mezipaměti). Volat mimo hlavní vlákno. */
    fun thumbnail(path: String, maxSize: Int = 360): Bitmap? {
        bitmaps.get(path)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }) ?: return null
        bitmaps.put(path, bitmap)
        return bitmap
    }
}

/** Synchronizace stylů na pozadí (bez síťové podmínky – na některých deskách se nikdy nesplní; bez sítě prostě selže). */
class StyleSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        AppLog.init(applicationContext)
        val settings = KioskSettings(applicationContext)
        if (StyleSync.stylesUrl(settings.reportUrl) != null && settings.reportToken.isNotBlank()) {
            StyleSync.sync(applicationContext, settings, settings.reportUrl, settings.reportToken)
        }
        Result.success()
    }
}
