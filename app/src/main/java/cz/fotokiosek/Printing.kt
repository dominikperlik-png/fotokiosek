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

/** Tiskárna nalezená v síti. */
data class FoundPrinter(val name: String, val url: String)

/**
 * Tisk na síťovou tiskárnu protokolem IPP (umí ho skoro každá tiskárna s Wi-Fi/LAN od roku ~2012,
 * „AirPrint“ / „Mopria“ / „IPP Everywhere“). Posílá rovnou JPEG – bez dialogů, s počtem kopií.
 */
object IppClient {
    private const val TAG = "Tisk"

    class Result(val ok: Boolean, val message: String, val attributes: Map<String, List<Any>> = emptyMap())

    fun print(printerUrl: String, jpeg: ByteArray, copies: Int, jobName: String): Result {
        val request = ipp(operation = 0x0002, printerUrl = printerUrl) {
            operationAttr(0x42, "job-name", jobName)
            operationAttr(0x49, "document-format", "image/jpeg")
            beginGroup(0x02) // job attributes
            intAttr("copies", copies.coerceIn(1, 99))
            keywordAttr("print-scaling", "fill")
            keywordAttr("print-color-mode", "color")
        }
        return send(printerUrl, request + jpeg).also {
            if (it.ok) AppLog.i(TAG, "Tisk odeslán: $copies× na $printerUrl") else AppLog.e(TAG, "Tisk selhal: ${it.message}")
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
            Result(true, "OK", attrs)
        } else {
            val hint = when (status) {
                0x040A -> "tiskárna nepodporuje JPEG – použij systémový tisk Androidu"
                0x0507 -> "tiskárna je zaneprázdněná"
                0x0506 -> "tiskárna nepřijímá úlohy"
                else -> "kód 0x${status.toString(16)}"
            }
            Result(false, "Tiskárna úlohu odmítla: $hint ${message}".trim(), attrs)
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
    fun print(activity: Activity, photo: Bitmap, copies: Int, jobName: String) {
        val manager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val attributes = PrintAttributes.Builder()
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .setMediaSize(if (photo.width >= photo.height) PrintAttributes.MediaSize.NA_INDEX_4X6.asLandscape() else PrintAttributes.MediaSize.NA_INDEX_4X6)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        Kiosk.pauseLock(activity) // dialog patří systému
        manager.print(jobName, PhotoAdapter(activity, photo, copies.coerceIn(1, 99), jobName), attributes)
        AppLog.i("Tisk", "Systémový tisk: $copies×")
    }

    private class PhotoAdapter(
        private val context: Context,
        private val photo: Bitmap,
        private val copies: Int,
        private val name: String,
    ) : PrintDocumentAdapter() {
        private var pdf: PrintedPdfDocument? = null

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) {
            pdf = PrintedPdfDocument(context, newAttributes)
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
            val doc = pdf ?: return callback.onWriteFailed("Dokument není připravený")
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
                pdf = null
            }
        }
    }
}
