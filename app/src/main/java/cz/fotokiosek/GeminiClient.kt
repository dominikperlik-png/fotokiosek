package cz.fotokiosek

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Úprava fotky přes Gemini API (REST generateContent), bez knihoven – stejné HttpURLConnection jako aktualizace
 * z GitHubu, takže funguje i na Androidu 7 (TLS 1.2).
 *
 * POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
 * hlavička x-goog-api-key, tělo: text + inline_data (JPEG), generationConfig.responseModalities = IMAGE,
 * imageConfig.aspectRatio / imageSize. Výsledek: candidates[0].content.parts[].inlineData.data (base64).
 * Klíč se nikdy neloguje.
 */
object GeminiClient {
    const val DEFAULT_MODEL = "gemini-3.1-flash-image"

    /** Model s popisem pro výběr v menu správce a velikostí výstupu, kterou umí (null = parametr neposílat). */
    class Model(val id: String, val note: String, val imageSize: String?)

    /** Obrazové modely podle dokumentace Google (listopad 2026). Název jde v menu i přepsat ručně. */
    val MODELS = listOf(
        Model(DEFAULT_MODEL, "Nano Banana 2 · stabilní, doporučený · 2K · ~0,10 \$ za fotku", "2K"),
        Model("gemini-nano-banana-2.1", "Nano Banana 2.1 · nejnovější, Google ho doporučuje · 2K · ~0,05 \$ za fotku", "2K"),
        Model("gemini-3.1-flash-lite-image", "Nano Banana 2 Lite · nejlevnější · jen 1K (méně ostrý tisk) · ~0,03 \$", "1K"),
        Model("gemini-3-pro-image", "Nano Banana Pro · nejvyšší kvalita, pomalejší · 2K · ~0,13 \$ za fotku", "2K"),
        Model("gemini-2.5-flash-image", "Nano Banana (starší) · 1K · Google ho vypne 15. 3. 2027 · ~0,04 \$", null),
    )
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
    private const val TAG = "AI"

    /** Delší strana fotky posílané do AI (rychlejší přenos i generování). */
    private const val UPLOAD_MAX_SIDE = 1024
    /**
     * Velikost výstupu podle modelu: 2K (na tisk 10×15 = 1748 px stačí), kde to model umí, jinak 1K / nic.
     * Neznámý (ručně zadaný) model: zkusí se 2K a když ho model odmítne, jede se bez velikosti.
     */
    fun imageSizeFor(model: String): String? {
        val known = MODELS.firstOrNull { it.id == modelId(model) }
        return if (known != null) known.imageSize else "2K"
    }

    /** Chyba „tenhle model takovou velikost neumí“ (Google píše „imageSize“ i „Image size 2K is not supported“). */
    fun isImageSizeError(code: Int, body: String) =
        code == 400 && listOf("imageSize", "image_size", "image size").any { body.contains(it, ignoreCase = true) }
    private val ASPECTS = listOf("1:1", "2:3", "3:2", "3:4", "4:3", "4:5", "5:4", "9:16", "16:9", "21:9")

    /** Chyba se srozumitelnou českou zprávou (pro zákazníka a pro „Poslední chybu“ v adminu). */
    class AiException(message: String) : IOException(message)

    class TestResult(val ok: Boolean, val message: String)

    /** Ověří klíč a model bez generování (models.get – nic se neplatí). */
    fun test(apiKey: String, model: String): TestResult {
        if (apiKey.isBlank()) return TestResult(false, "Chybí API klíč.")
        if (model.isBlank()) return TestResult(false, "Chybí název modelu.")
        return try {
            val conn = open(BASE + modelId(model), apiKey, timeoutMs = 20_000)
            conn.requestMethod = "GET"
            val (code, body) = read(conn)
            if (code != 200) return TestResult(false, httpError(code, body))
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return TestResult(false, "Server odpověděl, ale nečitelně – zkus to znovu.")
            val methods = json.optJSONArray("supportedGenerationMethods")
            val canGenerate = methods == null || (0 until methods.length()).any { methods.optString(it) == "generateContent" }
            val name = json.optString("displayName").ifBlank { model }
            if (canGenerate) TestResult(true, "Spojení OK, klíč funguje – model $name.")
            else TestResult(false, "Klíč funguje, ale model $name neumí generateContent – zvol jiný model.")
        } catch (e: Exception) {
            AppLog.e(TAG, "Test API", e)
            TestResult(false, networkError(e))
        }
    }

    /**
     * Pošle fotku s promptem a vrátí upravenou fotku. Blokuje (desítky sekund) – volat mimo hlavní vlákno.
     * Výsledek má poměr stran nejbližší původní fotce.
     */
    fun stylize(apiKey: String, model: String, photo: Bitmap, prompt: String): Bitmap {
        if (apiKey.isBlank()) throw AiException("AI není nastavená (chybí API klíč).")
        val jpeg = downscaledJpeg(photo)
        val aspect = nearestAspect(photo.width, photo.height)
        val size = imageSizeFor(model)
        val first = call(apiKey, model, jpeg, prompt, aspect, size)
        // Pojistka: model velikost odmítl (ručně zadaný / změnil se) → jednou bez ní (výchozí velikost modelu).
        val (code, response) = if (size != null && isImageSizeError(first.first, first.second)) {
            AppLog.w(TAG, "Model $model neumí velikost $size – zkouším bez ní")
            call(apiKey, model, jpeg, prompt, aspect, null)
        } else first
        if (code != 200) {
            AppLog.e(TAG, "AI vrátila HTTP $code: ${response.take(500)}")
            throw AiException(httpError(code, response))
        }
        return parseImage(response)
    }

    private fun call(apiKey: String, model: String, jpeg: ByteArray, prompt: String, aspect: String, size: String?): Pair<Int, String> {
        val imageConfig = JSONObject().put("aspectRatio", aspect)
        if (size != null) imageConfig.put("imageSize", size)
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray()
                            .put(JSONObject().put("text", prompt))
                            .put(
                                JSONObject().put(
                                    "inline_data",
                                    JSONObject().put("mime_type", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)),
                                ),
                            ),
                    ),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("IMAGE"))
                    .put("imageConfig", imageConfig),
            )
            .toString()
            .toByteArray(Charsets.UTF_8)

        val started = System.currentTimeMillis()
        AppLog.i(TAG, "Posílám fotku do AI: model $model, ${jpeg.size / 1024} kB, poměr $aspect, velikost ${size ?: "výchozí"}")
        return try {
            val conn = open(BASE + modelId(model) + ":generateContent", apiKey, timeoutMs = 120_000)
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            read(conn).also { AppLog.i(TAG, "AI odpověděla za ${(System.currentTimeMillis() - started) / 1000} s (HTTP ${it.first})") }
        } catch (e: Exception) {
            AppLog.e(TAG, "Volání AI selhalo", e)
            throw AiException(networkError(e))
        }
    }

    /** „models/xyz“ i „xyz“ → „xyz“. */
    private fun modelId(model: String) = model.trim().removePrefix("models/")

    private fun parseImage(response: String): Bitmap {
        val json = try {
            JSONObject(response)
        } catch (e: Exception) {
            throw AiException("AI poslala nečitelnou odpověď.")
        }
        json.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }?.let { reason ->
            AppLog.w(TAG, "AI požadavek zablokovala: $reason")
            throw AiException("AI tuhle fotku odmítla upravit (ochranný filtr). Zkus jiný styl nebo použij původní fotku.")
        }
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw AiException("AI nevrátila žádný výsledek.")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
        if (parts != null) {
            // Poslední obrázek, který není jen „mezikrok přemýšlení“ (thought = true).
            for (i in parts.length() - 1 downTo 0) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optBoolean("thought", false)) continue
                val data = (part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data"))?.optString("data")
                if (!data.isNullOrBlank()) {
                    val bytes = Base64.decode(data, Base64.DEFAULT)
                    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: throw AiException("AI poslala obrázek, který nejde otevřít.")
                }
            }
        }
        val finish = candidate.optString("finishReason")
        AppLog.w(TAG, "AI nevrátila obrázek (finishReason $finish)")
        throw AiException(
            if (finish.contains("SAFETY") || finish.contains("PROHIBITED") || finish == "BLOCKLIST" || finish == "SPII")
                "AI tuhle fotku odmítla upravit (ochranný filtr). Zkus jiný styl nebo použij původní fotku."
            else "AI tentokrát obrázek nevytvořila. Zkus to znovu nebo jiný styl.",
        )
    }

    private fun open(url: String, apiKey: String, timeoutMs: Int): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("x-goog-api-key", apiKey.trim())
        conn.setRequestProperty("Accept", "application/json")
        return conn
    }

    private fun read(conn: HttpURLConnection): Pair<Int, String> {
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    /** Chyba z API → česky, co dělat. */
    private fun httpError(code: Int, body: String): String {
        val err = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val status = err?.optString("status").orEmpty()
        val message = err?.optString("message").orEmpty()
        return when {
            message.contains("API key", true) || message.contains("API_KEY", true) ->
                "API klíč nefunguje – zkontroluj ho v nastavení."
            code == 403 || status == "PERMISSION_DENIED" ->
                "Klíč nemá oprávnění k tomuto modelu (zapnuté placené účtování v Google AI Studio?)."
            code == 404 || status == "NOT_FOUND" -> "Model neexistuje – zkontroluj název modelu."
            code == 429 || status == "RESOURCE_EXHAUSTED" ->
                "Vyčerpaný limit u Google AI – zkus to za chvíli. Když to trvá, zkontroluj, že má klíč zapnuté placené účtování."
            code >= 500 -> "Služba Google AI je teď přetížená nebo nedostupná – zkus to za chvíli."
            else -> "AI vrátila chybu $code${if (message.isNotBlank()) ": ${message.take(160)}" else ""}"
        }
    }

    private fun networkError(e: Exception) = when (e) {
        is AiException -> e.message ?: "Chyba AI"
        is UnknownHostException -> "Automat není připojený k internetu (nebo nejde najít server Google)."
        is SocketTimeoutException -> "AI neodpověděla včas – zkus to znovu."
        is SSLException -> "Zabezpečené spojení selhalo – zkontroluj datum a čas automatu (případně jeho Android nezná certifikát Google)."
        else -> "Spojení s AI selhalo: ${AppLog.causes(e)}"
    }

    /** Zmenší fotku na delší stranu [UPLOAD_MAX_SIDE] a zakóduje do JPEG. */
    private fun downscaledJpeg(photo: Bitmap): ByteArray {
        val scale = UPLOAD_MAX_SIDE.toFloat() / max(photo.width, photo.height)
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(photo, (photo.width * scale).roundToInt(), (photo.height * scale).roundToInt(), true)
        } else photo
        return ByteArrayOutputStream().use { out ->
            small.compress(Bitmap.CompressFormat.JPEG, 88, out)
            if (small !== photo) small.recycle()
            out.toByteArray()
        }
    }

    /** Nejbližší poměr stran, který API podporuje. */
    fun nearestAspect(w: Int, h: Int): String {
        val target = ln(w.toDouble() / h)
        return ASPECTS.minByOrNull { a ->
            val (x, y) = a.split(":").map { it.toDouble() }
            abs(ln(x / y) - target)
        } ?: "1:1"
    }
}
