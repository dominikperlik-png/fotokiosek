package cz.fotokiosek

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Fotky čekající na odeslání. Leží v interní paměti aplikace a po pár dnech se mažou. */
object PhotoStore {
    private fun dir(context: Context) = File(context.filesDir, "photos").apply { mkdirs() }

    fun save(context: Context, bitmap: Bitmap): File {
        val name = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val file = File(dir(context), "fotka_$name.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return file
    }

    fun cleanup(context: Context, keepDays: Int) {
        val limit = System.currentTimeMillis() - keepDays.coerceAtLeast(1) * 24L * 60 * 60 * 1000
        dir(context).listFiles()?.filter { it.lastModified() < limit }?.forEach { it.delete() }
    }
}
