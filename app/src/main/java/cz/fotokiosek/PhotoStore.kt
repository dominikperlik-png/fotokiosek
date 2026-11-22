package cz.fotokiosek

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fotky v interní paměti aplikace, po `keep_days` se mažou.
 * - `photos/` = fotky čekající na odeslání e-mailem (s logem),
 * - `gallery/` = každá pořízená fotka bez loga (jen když je galerie zapnutá) – z ní jde znovu tisknout a posílat.
 */
object PhotoStore {
    private fun dir(context: Context) = File(context.filesDir, "photos").apply { mkdirs() }
    private fun galleryDir(context: Context) = File(context.filesDir, "gallery").apply { mkdirs() }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    fun save(context: Context, bitmap: Bitmap): File {
        val file = File(dir(context), "fotka_${stamp()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return file
    }

    /**
     * Uloží fotku (bez loga) do galerie a smaže prošlé fotky (kiosek běží týdny bez restartu).
     * Při nedostatku místa neukládá – fronta e-mailů a nastavení mají přednost. Vrací null, když neuložil.
     */
    fun saveToGallery(context: Context, raw: Bitmap, keepDays: Int): File? {
        cleanup(context, keepDays)
        if (context.filesDir.usableSpace < MIN_FREE_BYTES) {
            AppLog.w("Galerie", "Málo místa v paměti (${context.filesDir.usableSpace / 1_000_000} MB) – fotku do galerie neukládám")
            return null
        }
        val file = File(galleryDir(context), "galerie_${stamp()}.jpg")
        FileOutputStream(file).use { raw.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return file
    }

    /** Fotky v galerii, nejnovější první (jen mladší než [keepDays]). */
    fun gallery(context: Context, keepDays: Int): List<File> {
        val limit = limit(keepDays)
        // Jméno začíná časem pořízení → řazení podle jména = podle času.
        return galleryDir(context).listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".jpg") && it.lastModified() >= limit }
            .sortedByDescending { it.name }
    }

    /** Zmenšený náhled pro mřížku galerie (dekóduje rovnou zmenšené – šetří paměť). */
    fun thumbnail(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565 // poloviční paměť, na náhled stačí
            },
        )
    }

    fun load(file: File): Bitmap? = BitmapFactory.decodeFile(file.path)

    /** Vypnutí galerie: smazat všechny fotky v ní (fotky ve frontě e-mailů zůstanou). */
    fun clearGallery(context: Context) {
        val failed = galleryDir(context).listFiles().orEmpty().count { !it.delete() }
        if (failed > 0) AppLog.w("Galerie", "$failed fotek z galerie nešlo smazat") else AppLog.i("Galerie", "Fotky z galerie smazány")
    }

    fun cleanup(context: Context, keepDays: Int) {
        val limit = limit(keepDays)
        listOf(dir(context), galleryDir(context)).forEach { d ->
            d.listFiles()?.filter { it.lastModified() < limit }?.forEach { it.delete() }
        }
    }

    private const val MIN_FREE_BYTES = 200L * 1024 * 1024

    private fun limit(keepDays: Int) = System.currentTimeMillis() - keepDays.coerceAtLeast(1) * 24L * 60 * 60 * 1000
}
