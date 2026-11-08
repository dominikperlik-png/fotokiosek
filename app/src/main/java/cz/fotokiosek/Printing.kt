package cz.fotokiosek

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Fotka 10×15 cm (300 DPI, bez okrajů). Rozměry podle modelu tiskárny:
 * Canon SELPHY CP1500 = papír 100×148 mm (KP-108IN), DNP DP-QW410 / obecná = 4×6″ (101,6×152,4 mm).
 */
enum class PrintMedia(
    val label: String,
    /** Rozměr v setinách mm (IPP media-size), kratší × delší strana. */
    val widthHmm: Int,
    val heightHmm: Int,
    /** Pixely při 300 DPI, kratší × delší strana. */
    val shortPx: Int,
    val longPx: Int,
    /** IPP název média (PWG). */
    val ippName: String,
) {
    POSTCARD_100x148("10×15 cm (100×148 mm)", 10000, 14800, 1181, 1748, "jpn_hagaki_100x148mm"),
    INDEX_4x6("10×15 cm (4×6″)", 10160, 15240, 1200, 1800, "na_index-4x6_4x6in");

    val androidSize: PrintAttributes.MediaSize
        get() = if (this == POSTCARD_100x148) PrintAttributes.MediaSize.JPN_HAGAKI else PrintAttributes.MediaSize.NA_INDEX_4X6

    companion object {
        fun forModel(model: String) = if (model == "selphy_cp1500") POSTCARD_100x148 else INDEX_4x6
    }
}

/** Úprava fotky na tiskový formát: pevně 10×15 při 300 DPI, vyplnit celou plochu (ořez uprostřed, žádné bílé okraje). */
object PrintLayout {
    fun fit(photo: Bitmap, media: PrintMedia): Bitmap {
        val landscape = photo.width >= photo.height
        val w = if (landscape) media.longPx else media.shortPx
        val h = if (landscape) media.shortPx else media.longPx
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.WHITE)
        val scale = max(w.toFloat() / photo.width, h.toFloat() / photo.height)
        val m = Matrix().apply {
            postScale(scale, scale)
            postTranslate((w - photo.width * scale) / 2f, (h - photo.height * scale) / 2f)
        }
        canvas.drawBitmap(photo, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        return out
    }
}

/** Tiskárny připojené přímo do USB automatu (třída Printer). */
object UsbPrinters {
    fun list(context: Context): List<String> {
        val usb = context.getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager ?: return emptyList()
        return usb.deviceList.values.filter { d ->
            (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_PRINTER }
        }.map { it.productName ?: "USB tiskárna ${it.vendorId}:${it.productId}" }
    }
}

/** Tiskárna nalezená v síti. */
data class FoundPrinter(val name: String, val url: String)

/**
 * Tisk na síťovou tiskárnu protokolem IPP (umí ho skoro každá tiskárna s Wi-Fi/LAN od roku ~2012,
 * „AirPrint“ / „Mopria“ / „IPP Everywhere“). Posílá rovnou JPEG – bez dialogů, s počtem kopií.
 */
object IppClient {
    private const val TAG = "Tisk"

    class Result(val ok: Boolean, val message: String, val attributes: Map<String, List<Any>> = emptyMap(), val status: Int = 0)

    /**
     * Tisk JPEG fotky. S [media] se pošle i přesný formát papíru a nulové okraje (bez okrajů);
     * když to tiskárna neumí (odmítne atributy), zkusí se to znovu jen s `print-scaling=fill`.
     */
    fun print(printerUrl: String, jpeg: ByteArray, copies: Int, jobName: String, media: PrintMedia? = null): Result {
        fun request(withMedia: Boolean) = ipp(operation = 0x0002, printerUrl = printerUrl) {
            operationAttr(0x42, "job-name", jobName)
            operationAttr(0x49, "document-format", "image/jpeg")
            beginGroup(0x02) // job attributes
            intAttr("copies", copies.coerceIn(1, 99))
            keywordAttr("print-scaling", "fill")
            keywordAttr("print-color-mode", "color")
            if (withMedia && media != null) borderlessMedia(media)
        }
        var result = send(printerUrl, request(true) + jpeg)
        if (!result.ok && media != null && result.status in setOf(0x0400, 0x040B)) {
            AppLog.w(TAG, "Tiskárna odmítla formát/okraje (0x${result.status.toString(16)}), tisknu bez nich")
            result = send(printerUrl, request(false) + jpeg)
        }
        return result.also {
            if (it.ok) AppLog.i(TAG, "Tisk odeslán: $copies× ${media?.label ?: ""} na $printerUrl") else AppLog.e(TAG, "Tisk selhal: ${it.message}")
        }
    }

    /** Ověří, že tiskárna odpovídá, a vrátí její model a stav. */
    fun test(printerUrl: String): Result {
        val request = ipp(operation = 0x000B, printerUrl = printerUrl) { }
        val r = send(printerUrl, request)
        if (!r.ok) return r
        val model = r.attributes["printer-make-and-model"]?.firstOrNull() ?: r.attributes["printer-name"]?.firstOrNull() ?: "?"
        val state = when (r.attributes["printer-state"]?.firstOrNull()) {
            3 -> "připravena"
            4 -> "tiskne"
            5 -> "zastavena"
            else -> "neznámý stav"
        }
        val reasons = r.attributes["printer-state-reasons"]?.filter { it != "none" }?.joinToString().orEmpty()
        val formats = r.attributes["document-format-supported"].orEmpty()
        val jpeg = formats.isEmpty() || formats.any { it == "image/jpeg" }
        val msg = buildString {
            append("$model – $state")
            if (reasons.isNotEmpty()) append(" ($reasons)")
            if (!jpeg) append("\nPozor: tiskárna nehlásí podporu JPEG. Použij režim „Systémový tisk Androidu“.")
        }
        return Result(jpeg, msg, r.attributes)
    }

    private fun send(printerUrl: String, body: ByteArray): Result {
        val http = httpUrl(printerUrl)
        return try {
            val conn = URL(http).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 60_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/ipp")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code != 200) return Result(false, "Tiskárna odpověděla HTTP $code ($http)")
            val response = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            parse(response)
        } catch (e: Exception) {
            AppLog.e(TAG, "IPP $http", e)
            Result(false, "Tiskárna nedostupná: ${AppLog.causes(e)}. Je zapnutá a ve stejné síti? Adresa: $http")
        }
    }

    private fun parse(data: ByteArray): Result {
        if (data.size < 8) return Result(false, "Neplatná odpověď tiskárny")
        val bb = ByteBuffer.wrap(data)
        val status = bb.getShort(2).toInt() and 0xFFFF
        val attrs = mutableMapOf<String, MutableList<Any>>()
        var pos = 8
        var lastName = ""
        while (pos < data.size) {
            val tag = data[pos].toInt() and 0xFF
            pos++
            if (tag == 0x03) break
            if (tag < 0x10) continue // oddělovač skupiny
            if (pos + 2 > data.size) break
            val nameLen = bb.getShort(pos).toInt() and 0xFFFF; pos += 2
            val name = if (nameLen > 0) String(data, pos, nameLen, Charsets.UTF_8) else lastName
            pos += nameLen
            if (pos + 2 > data.size) break
            val valueLen = bb.getShort(pos).toInt() and 0xFFFF; pos += 2
            if (pos + valueLen > data.size) break
            val value: Any = when (tag) {
                0x21, 0x23 -> if (valueLen == 4) bb.getInt(pos) else 0
                0x22 -> data[pos].toInt() != 0
                in 0x40..0x4F -> String(data, pos, valueLen, Charsets.UTF_8)
                else -> valueLen
            }
            pos += valueLen
            lastName = name
            attrs.getOrPut(name) { mutableListOf() }.add(value)
        }
        val message = attrs["status-message"]?.firstOrNull()?.toString().orEmpty()
        return if (status < 0x0100) {
            Result(true, "OK", attrs, status)
        } else {
            val hint = when (status) {
                0x040A -> "tiskárna nepodporuje JPEG – použij systémový tisk Androidu"
                0x0507 -> "tiskárna je zaneprázdněná"
                0x0506 -> "tiskárna nepřijímá úlohy"
                else -> "kód 0x${status.toString(16)}"
            }
            Result(false, "Tiskárna úlohu odmítla: $hint ${message}".trim(), attrs, status)
        }
    }

    /** „192.168.1.50“ nebo „ipp://tiskarna.local“ → úplná adresa ipp://host:631/ipp/print. */
    fun normalize(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return t
        val withScheme = if (t.contains("://")) t else "ipp://$t"
        val m = Regex("^([a-zA-Z]+)://([^/]+)(/.*)?$").find(withScheme) ?: return withScheme
        val scheme = m.groupValues[1].lowercase()
        var host = m.groupValues[2]
        if (!Regex(":\\d+$").containsMatchIn(host) && !host.endsWith("]")) host += if (scheme == "http" || scheme == "https") "" else ":631"
        val path = m.groupValues[3].ifEmpty { "/ipp/print" }
        return "$scheme://$host$path"
    }

    /** IPP adresa → HTTP adresa, na kterou se posílá POST. */
    private fun httpUrl(printerUrl: String): String {
        val u = normalize(printerUrl)
        return when {
            u.startsWith("ipps://", true) -> "https://" + u.substring(7)
            u.startsWith("ipp://", true) -> "http://" + u.substring(6)
            else -> u
        }
    }

    // --- sestavení IPP požadavku ---

    private class IppWriter(printerUrl: String, operation: Int) {
        val bytes = ByteArrayOutputStream()
        private val out = DataOutputStream(bytes)

        init {
            out.writeShort(0x0200) // IPP 2.0
            out.writeShort(operation)
            out.writeInt(1)
            out.writeByte(0x01) // operation attributes
            attr(0x47, "attributes-charset", "utf-8")
            attr(0x48, "attributes-natural-language", "en")
            attr(0x45, "printer-uri", printerUrl.trim())
            attr(0x42, "requesting-user-name", "fotokiosek")
        }

        fun operationAttr(tag: Int, name: String, value: String) = attr(tag, name, value)
        fun beginGroup(tag: Int) = out.writeByte(tag)
        fun keywordAttr(name: String, value: String) = attr(0x44, name, value)
        fun intAttr(name: String, value: Int) {
            out.writeByte(0x21)
            out.writeShort(name.length)
            out.write(name.toByteArray(Charsets.UTF_8))
            out.writeShort(4)
            out.writeInt(value)
        }

        /**
         * media-col: přesný rozměr papíru + všechny okraje 0 = tisk bez okrajů (IPP „borderless“).
         * Kolekce: begCollection (0x34) → memberAttrName (0x4A) + hodnota → endCollection (0x37).
         */
        fun borderlessMedia(media: PrintMedia) {
            raw(0x34, "media-col", ByteArray(0))
            member("media-size")
            raw(0x34, "", ByteArray(0))
            member("x-dimension"); int("", media.widthHmm)
            member("y-dimension"); int("", media.heightHmm)
            raw(0x37, "", ByteArray(0))
            for (side in listOf("media-top-margin", "media-bottom-margin", "media-left-margin", "media-right-margin")) {
                member(side); int("", 0)
            }
            raw(0x37, "", ByteArray(0))
        }

        private fun member(name: String) = raw(0x4A, "", name.toByteArray(Charsets.UTF_8))
        private fun int(name: String, value: Int) = raw(0x21, name, byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()))

        private fun raw(tag: Int, name: String, value: ByteArray) {
            val n = name.toByteArray(Charsets.UTF_8)
            out.writeByte(tag)
            out.writeShort(n.size)
            out.write(n)
            out.writeShort(value.size)
            out.write(value)
        }

        fun attr(tag: Int, name: String, value: String) {
            val v = value.toByteArray(Charsets.UTF_8)
            out.writeByte(tag)
            out.writeShort(name.length)
            out.write(name.toByteArray(Charsets.UTF_8))
            out.writeShort(v.size)
            out.write(v)
        }

        fun finish(): ByteArray {
            out.writeByte(0x03)
            out.flush()
            return bytes.toByteArray()
        }
    }

    private fun ipp(operation: Int, printerUrl: String, block: IppWriter.() -> Unit): ByteArray {
        val uri = normalize(printerUrl).let { if (it.startsWith("http://")) "ipp://" + it.removePrefix("http://") else it }
        return IppWriter(uri, operation).apply(block).finish()
    }
}

/** Vyhledání tiskáren v místní síti (mDNS / Bonjour, služba _ipp._tcp). */
class PrinterDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val toResolve = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start(onFound: (FoundPrinter) -> Unit) {
        stop()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.w("Tisk", "Vyhledávání tiskáren nejde spustit ($errorCode)")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                synchronized(toResolve) { toResolve.addLast(serviceInfo) }
                resolveNext(onFound)
            }
        }
        listener = l
        runCatching { nsd.discoverServices("_ipp._tcp", NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { AppLog.w("Tisk", "discoverServices", it) }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext(onFound: (FoundPrinter) -> Unit) {
        val next = synchronized(toResolve) {
            if (resolving || toResolve.isEmpty()) return
            resolving = true
            toResolve.removeFirst()
        }
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(toResolve) { resolving = false }
                resolveNext(onFound)
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress
                if (host != null) {
                    val rp = info.attributes["rp"]?.let { String(it, Charsets.UTF_8) }?.ifEmpty { null } ?: "ipp/print"
                    val h = if (host.contains(':')) "[$host]" else host
                    onFound(FoundPrinter(info.serviceName, "ipp://$h:${info.port}/$rp"))
                }
                synchronized(toResolve) { resolving = false }
                resolveNext(onFound)
            }
        })
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        synchronized(toResolve) { toResolve.clear() }
    }
}

/**
 * Záloha: tisk přes systémový tiskový dialog Androidu (tiskové služby Mopria, HP, Canon…).
 * Počet kopií posíláme jako stejný počet stránek, ať ho zákazník nemusí nastavovat znovu.
 */
object SystemPrint {
    fun print(activity: Activity, photo: Bitmap, copies: Int, jobName: String, media: PrintMedia = PrintMedia.INDEX_4x6) {
        val manager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val attributes = PrintAttributes.Builder()
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .setMediaSize(if (photo.width >= photo.height) media.androidSize.asLandscape() else media.androidSize.asPortrait())
            .setResolution(PrintAttributes.Resolution("300dpi", "300 DPI", 300, 300))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        // Zámek kiosku se NEpouští: tiskový dialog (com.android.printspooler) je povolený v Kiosk.setup,
        // takže se otevře i v zamčeném kiosku a zákazník se z něj nedostane do jiných aplikací.
        manager.print(jobName, PhotoAdapter(activity, photo, copies.coerceIn(1, 99), jobName), attributes)
        AppLog.i("Tisk", "Systémový tisk: $copies×")
    }

    private class PhotoAdapter(
        private val context: Context,
        private val photo: Bitmap,
        private val copies: Int,
        private val name: String,
    ) : PrintDocumentAdapter() {
        private var attributes: PrintAttributes? = null

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) {
            attributes = newAttributes
            if (cancellationSignal.isCanceled) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder("$name.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_PHOTO)
                .setPageCount(copies)
                .build()
            callback.onLayoutFinished(info, newAttributes != oldAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal,
            callback: WriteResultCallback,
        ) {
            // Android může onWrite volat víckrát (náhled, pak tisk) – PDF vždy vytvořit znovu.
            val attrs = attributes ?: return callback.onWriteFailed("Dokument není připravený")
            val doc = PrintedPdfDocument(context, attrs)
            try {
                for (i in 0 until copies) {
                    if (cancellationSignal.isCanceled) {
                        callback.onWriteCancelled()
                        return
                    }
                    val page = doc.startPage(i)
                    val box = RectF(page.info.contentRect)
                    // Vyplnit stránku (ořez na krajích), fotka uprostřed
                    val scale = max(box.width() / photo.width, box.height() / photo.height)
                    val m = Matrix().apply {
                        postScale(scale, scale)
                        postTranslate(box.centerX() - photo.width * scale / 2, box.centerY() - photo.height * scale / 2)
                    }
                    page.canvas.clipRect(box)
                    page.canvas.drawBitmap(photo, m, null)
                    doc.finishPage(page)
                }
                FileOutputStream(destination.fileDescriptor).use { doc.writeTo(it) }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                AppLog.e("Tisk", "Systémový tisk", e)
                callback.onWriteFailed(e.message)
            } finally {
                doc.close()
            }
        }
    }
}
