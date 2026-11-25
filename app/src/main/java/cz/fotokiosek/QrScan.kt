package cz.fotokiosek

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.net.URLDecoder

/**
 * Čtení QR kódů z obrazu kamery (ZXing) a rozpoznání obsahu:
 * - `fotokiosek:report?u=<adresa API>&t=<token>&n=<jméno automatu>` – z webového účetnictví (Nastavení → Přidat automat),
 * - `fotokiosek:gemini?k=<klíč>` – z účetnictví (Nastavení → QR kód pro Gemini API klíč),
 * - jakýkoli jiný text (např. samotný klíč z jiné aplikace na QR kódy).
 */
object QrScan {
    sealed interface Data {
        data class Report(val url: String, val token: String, val name: String?) : Data
        data class Gemini(val key: String) : Data
        data class Text(val text: String) : Data
    }

    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /**
     * Najde a přečte QR kód v obrázku (pixely ARGB). Zkusí i zrcadlově převrácený obraz –
     * náhled kamery automatu bývá zrcadlený. Nenalezeno = null.
     */
    fun decode(width: Int, height: Int, argb: IntArray): String? {
        decodeOnce(width, height, argb)?.let { return it }
        val mirrored = IntArray(argb.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) mirrored[row + x] = argb[row + width - 1 - x]
        }
        return decodeOnce(width, height, mirrored)
    }

    private fun decodeOnce(width: Int, height: Int, argb: IntArray): String? = try {
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, argb)))
        MultiFormatReader().apply { setHints(hints) }.decodeWithState(bitmap).text
    } catch (e: NotFoundException) {
        null
    } catch (e: Exception) {
        null // poškozený / neúplný kód – zkusí se další snímek
    }

    fun parse(raw: String): Data {
        val text = raw.trim()
        fun params(query: String): Map<String, String> = query.split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i) to runCatching { URLDecoder.decode(part.substring(i + 1), "UTF-8") }.getOrDefault("")
        }.toMap()
        return when {
            text.startsWith("fotokiosek:report?") -> {
                val p = params(text.removePrefix("fotokiosek:report?"))
                val url = p["u"].orEmpty()
                val token = p["t"].orEmpty()
                if (url.isBlank() || token.isBlank()) Data.Text(text) else Data.Report(url, token, p["n"]?.takeIf { it.isNotBlank() })
            }
            text.startsWith("fotokiosek:gemini?") -> {
                val key = params(text.removePrefix("fotokiosek:gemini?"))["k"].orEmpty()
                if (key.isBlank()) Data.Text(text) else Data.Gemini(key)
            }
            else -> Data.Text(text)
        }
    }

    /** Vypadá jako API klíč / token (jen písmena, čísla, - a _, rozumná délka). */
    fun looksLikeKey(text: String) = Regex("^[A-Za-z0-9_\\-]{20,128}$").matches(text)
}
