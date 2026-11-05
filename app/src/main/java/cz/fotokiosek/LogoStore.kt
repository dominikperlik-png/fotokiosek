package cz.fotokiosek

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.caverock.androidsvg.SVG
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max

/**
 * Vlastní logo. Ukládá se jako PNG (zachová průhlednost) do interní paměti aplikace:
 * přežije restart i aktualizaci aplikace a do Gitu se nikdy nedostane.
 */
object LogoStore {
    private const val TAG = "Logo"
    private const val MAX_SIDE = 1600
    /** Načtené logo a pro kterou volbu platí. */
    private var cached: Bitmap? = null
    private var cachedChoice: String? = null

    /** Loga přibalená přímo v aplikaci (složka Loga v projektu → res/drawable-nodpi). */
    class Builtin(val id: String, val label: String, val resId: Int)

    val builtins = listOf(
        Builtin("builtin:satisfactory", "Satisfactory", R.drawable.logo_satisfactory),
        Builtin("builtin:watt", "WATT", R.drawable.logo_watt),
        Builtin("builtin:allin", "Kluby Plzeň", R.drawable.logo_allin),
    )

    /** Zvyšuje se při každé změně loga – UI podle toho překreslí. */
    var version by mutableIntStateOf(0)
        private set

    private fun file(context: Context) = File(File(context.filesDir, "branding").apply { mkdirs() }, "logo.png")

    fun exists(context: Context) = file(context).exists()

    /** Logo podle aktuální volby v nastavení (nebo null = bez loga). */
    fun load(context: Context): Bitmap? = load(context, KioskSettings(context).logoChoice)

    /** Logo pro konkrétní volbu: none / custom / builtin:… */
    @Synchronized
    fun load(context: Context, choice: String): Bitmap? {
        if (choice == cachedChoice) return cached
        val bitmap = when {
            choice == "none" -> null
            choice.startsWith("builtin:") -> builtins.firstOrNull { it.id == choice }?.let { b ->
                BitmapFactory.decodeResource(context.resources, b.resId, BitmapFactory.Options().apply { inScaled = false })
            }
            else -> file(context).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
        }
        cached = bitmap
        cachedChoice = choice
        return bitmap
    }

    /** Přepne logo (okamžitě se projeví na obrazovce i na dalších fotkách). */
    fun select(context: Context, choice: String) {
        KioskSettings(context).logoChoice = choice
        invalidate()
        AppLog.i(TAG, "Vybráno logo: $choice")
    }

    @Synchronized
    private fun invalidate() {
        cached = null
        cachedChoice = null
        synchronized(thumbs) { thumbs.clear() }
        version++
    }

    /** Malé náhledy log pro menu správce – vlastní mezipaměť, ať nepřepisují logo pro fotky. */
    private val thumbs = HashMap<String, Bitmap?>()

    /**
     * Zmenšený náhled loga (delší strana cca [maxSide] px). Volat mimo hlavní vlákno –
     * dekódování velkého PNG by menu na pomalé desce zaseklo.
     */
    fun thumbnail(context: Context, choice: String, maxSide: Int = 320): Bitmap? {
        if (choice == "none") return null
        synchronized(thumbs) { if (thumbs.containsKey(choice)) return thumbs[choice] }
        fun opts(sample: Int) = BitmapFactory.Options().apply { inScaled = false; inSampleSize = sample }
        val builtin = builtins.firstOrNull { it.id == choice }
        val custom = file(context)
        fun decode(o: BitmapFactory.Options): Bitmap? = when {
            builtin != null -> BitmapFactory.decodeResource(context.resources, builtin.resId, o)
            choice == "custom" && custom.exists() -> BitmapFactory.decodeFile(custom.absolutePath, o)
            else -> null
        }
        val bitmap = runCatching {
            val bounds = opts(1).apply { inJustDecodeBounds = true }
            decode(bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            decode(opts(sample))
        }.onFailure { AppLog.w(TAG, "Náhled loga $choice", it) }.getOrNull()
        synchronized(thumbs) { thumbs[choice] = bitmap }
        return bitmap
    }

    /** Nahraje logo z vybraného souboru (PNG, JPG, WEBP nebo SVG). */
    fun import(context: Context, uri: Uri): Result<Unit> = runCatching {
        val mime = context.contentResolver.getType(uri).orEmpty()
        val name = uri.lastPathSegment.orEmpty().lowercase()
        val isSvg = mime.contains("svg") || name.endsWith(".svg")
        val bitmap = context.contentResolver.openInputStream(uri)?.use { decode(it, isSvg) }
            ?: throw IllegalStateException("Soubor nejde otevřít")
        save(context, bitmap)
    }.onFailure { AppLog.e(TAG, "Nahrání loga z $uri", it) }

    /**
     * Logo nahrané instalací (adb push do /sdcard/Android/data/cz.fotokiosek/files/logo.*).
     * Po načtení se soubor smaže.
     */
    fun importFromExternal(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: return
        val f = listOf("logo.png", "logo.jpg", "logo.jpeg", "logo.webp", "logo.svg").map { File(dir, it) }.firstOrNull { it.exists() } ?: return
        runCatching {
            val bitmap = f.inputStream().use { decode(it, f.name.endsWith(".svg")) }
            save(context, bitmap)
            f.delete()
            AppLog.i(TAG, "Logo načteno z ${f.name}")
        }.onFailure { AppLog.e(TAG, "Logo z ${f.name}", it) }
    }

    fun delete(context: Context) {
        file(context).delete()
        val settings = KioskSettings(context)
        if (settings.logoChoice == "custom") settings.logoChoice = "none"
        invalidate()
        AppLog.i(TAG, "Logo smazáno")
    }

    private fun save(context: Context, bitmap: Bitmap) {
        val tmp = File(file(context).parentFile, "logo.tmp")
        FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.renameTo(file(context))
        // Nahrané logo se rovnou vybere.
        KioskSettings(context).logoChoice = "custom"
        invalidate()
        AppLog.i(TAG, "Logo uloženo (${bitmap.width}×${bitmap.height})")
    }

    private fun decode(input: InputStream, svg: Boolean): Bitmap {
        if (svg) {
            val doc = SVG.getFromInputStream(input)
            val box = doc.documentViewBox
            var w = doc.documentWidth.takeIf { it > 0 } ?: box?.width() ?: 512f
            var h = doc.documentHeight.takeIf { it > 0 } ?: box?.height() ?: 512f
            val scale = 1024f / max(w, h)
            w *= scale
            h *= scale
            doc.setDocumentWidth(w)
            doc.setDocumentHeight(h)
            val bmp = Bitmap.createBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            doc.renderToCanvas(Canvas(bmp))
            return bmp
        }
        val bytes = input.readBytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) throw IllegalArgumentException("Nepodporovaný formát obrázku (použij PNG, JPG nebo SVG)")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("Obrázek nejde načíst")
    }

    /** Vrátí kopii fotky s logem v rohu (podle nastavení). Bez loga vrací původní fotku. */
    fun applyToPhoto(context: Context, settings: KioskSettings, photo: Bitmap): Bitmap {
        if (!settings.logoOnPhoto) return photo
        val logo = load(context) ?: return photo
        val result = photo.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val targetW = result.width * settings.logoSize.coerceIn(5, 60) / 100f
        val targetH = targetW * logo.height / logo.width
        val margin = result.width * 0.03f
        val left = when {
            settings.logoPosition.endsWith("L") -> margin
            settings.logoPosition.endsWith("C") -> (result.width - targetW) / 2f
            else -> result.width - margin - targetW
        }
        val top = if (settings.logoPosition.startsWith("T")) margin else result.height - margin - targetH
        canvas.drawBitmap(logo, null, RectF(left, top, left + targetW, top + targetH), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return result
    }
}
