package cz.fotokiosek

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * Tisk na Canon SELPHY CP (CP820 … CP1500) přímo přes USB – Android pro ni ovladač nemá.
 *
 * Protokol podle popisu v otevřeném ovladači Gutenprint (backend_canonselphyneo.c, Solomon Peachy).
 * Kód je napsaný nově, nic z ovladače se nekopíruje.
 *
 * Úloha = hlavička 32 B + obrázek jako tři vrstvy Y, U, V (každá sloupce × řádky bajtů).
 * Stav tiskárny = 12 B čtených z bulk IN: [0] stav, [2] chyba, [6] vložený papír, [7] napájení.
 * Netestováno na skutečné tiskárně – každý krok se zapisuje do logu.
 */
object SelphyUsb {
    private const val TAG = "SELPHY"
    private const val CANON_VENDOR_ID = 0x04A9
    private const val ACTION_PERMISSION = "cz.fotokiosek.SELPHY_PERMISSION"

    private val MODELS = mapOf(
        0x327B to "CP820",
        0x327A to "CP910",
        0x32AE to "CP1000",
        0x32B1 to "CP1200",
        0x32DB to "CP1300",
        0x3302 to "CP1500",
    )

    /** Papír „P“ = pohlednice 100×148 mm (KP-108IN). Obrázek na výšku. */
    private const val COLS = 1248
    private const val ROWS = 1872
    private const val SIZE_P = 0x50

    private const val STATE_IDLE = 0x01
    private const val ERROR_MISMATCH = 0x0A

    /** Starší Androidy (< 9) zvládnou max. 16 kB na jedno volání bulkTransfer. */
    private const val CHUNK = 16 * 1024
    private const val IO_TIMEOUT_MS = 15_000

    /** Čekání, než je tiskárna volná (dotisk předchozí fotky trvá ~45 s). */
    private const val WAIT_IDLE_MS = 150_000L
    /** Čekání, než tiskárna po odeslání dat začne tisknout. */
    private const val WAIT_START_MS = 30_000L

    class Status(val state: Int, val error: Int, val media: Int, val raw: ByteArray) {
        val idle get() = state == STATE_IDLE
        val started get() = state >= 0x02 && state != STATE_IDLE
        val stateText get() = when (state) {
            0x01 -> "připravena"
            0x02 -> "podává papír"
            0x04, 0x08, 0x10 -> "tiskne"
            0x20 -> "tiskne ochrannou vrstvu"
            else -> "stav 0x%02x".format(state)
        }
        val mediaText get() = when (media) {
            0x01 -> "10×15 cm (KP-108IN)"
            0x02 -> "9×13 cm (L)"
            0x03 -> "kartička 5,4×8,6 cm (C)"
            0x00 -> "žádný / nezjištěn"
            else -> "neznámý (0x%x)".format(media)
        }
        override fun toString() = raw.joinToString(" ") { "%02x".format(it) }
    }

    private class PrinterException(message: String) : Exception(message)

    /** Jen známé modely – starší SELPHY (ES, CP-starší) mají jiný protokol a tisknout se na ně nesmí. */
    fun isSelphy(device: UsbDevice): Boolean = device.vendorId == CANON_VENDOR_ID && device.productId in MODELS

    /** Jakákoli SELPHY i podle názvu – jen aby se nezaměnila za zrcadlovku. */
    fun isAnySelphy(device: UsbDevice): Boolean =
        isSelphy(device) || (device.vendorId == CANON_VENDOR_ID && device.productName?.contains("SELPHY", ignoreCase = true) == true)

    fun modelName(device: UsbDevice) = "Canon SELPHY " + (MODELS[device.productId] ?: "?")

    fun find(context: Context): UsbDevice? = usb(context)?.deviceList?.values?.firstOrNull { isSelphy(it) }

    fun hasPermission(context: Context, device: UsbDevice) = usb(context)?.hasPermission(device) == true

    /** Všechna USB zařízení, která automat vidí – pro diagnostiku v menu správce. */
    fun describeAll(context: Context): List<String> =
        usb(context)?.deviceList?.values.orEmpty().map { d ->
            val classes = (0 until d.interfaceCount).joinToString(",") { d.getInterface(it).interfaceClass.toString() }
            // Název zařízení nesmí jít do format() – znak % v něm by shodil menu.
            (d.productName ?: "?") + " (" + "%04x:%04x".format(d.vendorId, d.productId) + ", třídy $classes)"
        }

    /**
     * Jen pro menu správce: zobrazí systémový dialog „Povolit aplikaci přístup k USB zařízení?“.
     * Zaškrtnutím „Použít ve výchozím nastavení pro toto zařízení USB“ (novější Androidy: „Vždy“) se povolení
     * zapamatuje i po vypnutí tiskárny – funguje díky filtru Canon v manifestu (usb_device_filter.xml). Zákazníkovi se dialog nikdy neukazuje (vede ven z kiosku).
     */
    fun requestPermission(activity: Activity, device: UsbDevice) {
        val manager = usb(activity) ?: return
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(activity, 1, Intent(ACTION_PERMISSION).setPackage(activity.packageName), flags)
        // Dialog patří systému – v zamčeném kiosku by se nezobrazil. Kiosek se zamkne zpátky v onResume.
        runCatching { activity.stopLockTask() }
        runCatching { manager.requestPermission(device, pi) }
    }

    /** Test z menu správce: najde tiskárnu a přečte její stav. */
    @Synchronized
    fun test(context: Context): IppClient.Result {
        AppLog.i(TAG, "USB zařízení: " + describeAll(context).joinToString("; ").ifEmpty { "žádné" })
        val device = find(context) ?: run {
            val other = usb(context)?.deviceList?.values?.firstOrNull { isAnySelphy(it) }
            return IppClient.Result(
                false,
                if (other != null) "Připojená ${other.productName ?: "SELPHY"} (${"%04x".format(other.productId)}) není podporovaný model – USB tisk umí CP820 až CP1500."
                else "SELPHY na USB nenalezena. Je zapnutá a připojená kabelem?",
            )
        }
        val manager = usb(context) ?: return IppClient.Result(false, "USB není k dispozici")
        if (!manager.hasPermission(device)) return IppClient.Result(false, "Aplikace nemá povolený přístup k tiskárně.")
        return try {
            Link.open(manager, device).use { link ->
                link.readStatus() // první odpověď bývá stará
                val s = link.readStatus() ?: throw PrinterException("neposlala svůj stav")
                AppLog.i(TAG, "Stav: $s")
                val msg = buildString {
                    append("${modelName(device)} – ${s.stateText}, papír: ${s.mediaText}")
                    if (s.error != 0) append("\nChyba: ${errorText(s.error)}")
                }
                IppClient.Result(s.error == 0, msg)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Test tiskárny", e)
            IppClient.Result(false, "Tiskárna neodpovídá: ${e.message}")
        }
    }

    /**
     * Vytiskne fotku (fotka se ořízne na celý papír 10×15). Blokuje, volat mimo hlavní vlákno.
     * Vrátí se, jakmile tiskárna přijme první kopii; další kopie se dotisknou na pozadí
     * (jejich chyba přijde do [onLaterError]). Další tisk mezitím počká, až tiskárna dotiskne.
     */
    fun print(context: Context, photo: Bitmap, copies: Int, onLaterError: (String) -> Unit = {}): IppClient.Result {
        val count = copies.coerceIn(1, 20)
        synchronized(this) {
            val device = find(context) ?: return fail("SELPHY na USB nenalezena (vypnutá nebo odpojená?)")
            val manager = usb(context) ?: return fail("USB není k dispozici")
            if (!manager.hasPermission(device)) return fail("Chybí povolení k USB tiskárně – v menu správce dej u tisku „Otestovat tiskárnu“ a povol přístup.")
            val job = try {
                buildJob(photo)
            } catch (e: OutOfMemoryError) {
                return fail("Málo paměti na přípravu fotky k tisku")
            }
            AppLog.i(TAG, "Tisk na ${modelName(device)}: $count×, ${job.size} B")
            val first = runCopies(manager, device, job, 1..1, count)
            if (!first.ok || count == 1) return first
            Thread({
                synchronized(this) {
                    val rest = runCopies(manager, device, job, 2..count, count)
                    if (!rest.ok) onLaterError(rest.message)
                }
            }, "selphy-copies").start()
            return first
        }
    }

    /** Pošle kopie [range] (číslováno pro log z [total]). Volat se zámkem `synchronized(this)`. */
    private fun runCopies(manager: UsbManager, device: UsbDevice, job: ByteArray, range: IntRange, total: Int): IppClient.Result =
        try {
            Link.open(manager, device).use { link ->
                for (copy in range) {
                    link.readStatus() // první odpověď bývá stará
                    val ready = link.waitFor(WAIT_IDLE_MS) { it.idle }
                        ?: throw PrinterException("tiskárna je pořád zaneprázdněná")
                    if (ready.media == 0x02 || ready.media == 0x03) {
                        throw PrinterException("ve tiskárně je jiný papír (${ready.mediaText}), potřeba je 10×15 cm (KP-108IN)")
                    }
                    link.write(job)
                    link.readStatus()
                    // Data jsou celá v tiskárně – i když začátek tisku nezachytíme, nehlásit chybu (zákazník by tiskl znovu).
                    if (link.waitFor(WAIT_START_MS) { it.started } == null) {
                        AppLog.w(TAG, "Kopie $copy/$total: tiskárna data přijala, začátek tisku jsem nezachytil")
                    } else {
                        AppLog.i(TAG, "Kopie $copy/$total se tiskne")
                    }
                }
                IppClient.Result(true, "OK")
            }
        } catch (e: PrinterException) {
            fail(e.message ?: "chyba tiskárny")
        } catch (e: Exception) {
            AppLog.e(TAG, "Tisk", e)
            fail("spojení s tiskárnou selhalo: ${e.message}")
        }

    private fun fail(message: String): IppClient.Result {
        AppLog.e(TAG, "Tisk selhal: $message")
        return IppClient.Result(false, message)
    }

    private fun usb(context: Context) = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    fun errorText(code: Int) = when (code) {
        0x01 -> "slabá baterie"
        0x02 -> "došel papír nebo se nepodal"
        0x03 -> "chybí zásobník papíru"
        0x04 -> "špatný zásobník papíru"
        0x05 -> "vložený papír neodpovídá"
        0x06 -> "došla barvicí páska (vyměň kazetu)"
        0x07 -> "chybí kazeta s barvicí páskou"
        0x08 -> "špatná kazeta s barvicí páskou"
        0x09 -> "došel papír i barvicí páska"
        ERROR_MISMATCH -> "papír nebo páska neodpovídá fotce 10×15 (vlož sadu KP-108IN)"
        0x0B -> "zaseknutý papír"
        0x0C -> "zaseknutá barvicí páska"
        0x0D, 0x0E -> "nepodporovaná barvicí páska"
        0x0F -> "neznámý papír"
        0x10 -> "tiskárna nezná příkaz"
        else -> "kód 0x%02x".format(code)
    }

    /**
     * Hlavička + obrázek. Fotka se vyplní na 10×15 (ořez uprostřed), fotka na šířku se otočí o 90°.
     * Barvy: RGB → YUV 4:4:4 (BT.601, celý rozsah), vrstvy Y, U, V za sebou.
     */
    private fun buildJob(photo: Bitmap): ByteArray {
        val landscape = photo.width > photo.height
        val fitted = if (landscape) PrintLayout.fill(photo, ROWS, COLS) else PrintLayout.fill(photo, COLS, ROWS)
        val pixels = IntArray(COLS * ROWS)
        fitted.getPixels(pixels, 0, fitted.width, 0, 0, fitted.width, fitted.height)
        val fittedHeight = fitted.height
        fitted.recycle()

        val plane = COLS * ROWS
        val out = ByteArray(HEADER_SIZE + 3 * plane)
        writeHeader(out)
        var i = 0
        for (y in 0 until ROWS) {
            for (x in 0 until COLS) {
                // Na šířku: otočení o 90° po směru hodinek (výstup x,y ← zdroj y, výška-1-x).
                val p = if (landscape) pixels[(fittedHeight - 1 - x) * ROWS + y] else pixels[y * COLS + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                out[HEADER_SIZE + i] = clamp((77 * r + 150 * g + 29 * b + 128) shr 8)
                out[HEADER_SIZE + plane + i] = clamp(((-43 * r - 85 * g + 128 * b + 128) shr 8) + 128)
                out[HEADER_SIZE + 2 * plane + i] = clamp(((128 * r - 107 * g - 21 * b + 128) shr 8) + 128)
                i++
            }
        }
        return out
    }

    private const val HEADER_SIZE = 32

    private fun writeHeader(out: ByteArray) {
        out[0] = 0x0F
        out[3] = 0x40
        out[14] = 0x01
        out[16] = 0x01
        out[18] = SIZE_P.toByte()
        out[23] = 0x00 // 0 = data YUV 4:4:4
        putInt(out, 24, COLS)
        putInt(out, 28, ROWS)
    }

    private fun putInt(out: ByteArray, offset: Int, value: Int) {
        for (k in 0 until 4) out[offset + k] = (value ushr (8 * k)).toByte()
    }

    private fun clamp(v: Int): Byte = v.coerceIn(0, 255).toByte()

    /** Otevřené USB spojení s tiskárnou. */
    private class Link(
        private val connection: UsbDeviceConnection,
        private val intf: UsbInterface,
        private val bulkIn: UsbEndpoint,
        private val bulkOut: UsbEndpoint,
    ) : AutoCloseable {
        private val statusBuf = ByteArray(64)

        fun readStatus(): Status? {
            val n = connection.bulkTransfer(bulkIn, statusBuf, statusBuf.size, 2000)
            if (n < 12) {
                AppLog.w(TAG, "Stav nepřečten (přijato $n B)")
                return null
            }
            val raw = statusBuf.copyOf(12)
            return Status(raw[0].toInt() and 0xFF, raw[2].toInt() and 0xFF, raw[6].toInt() and 0x0F, raw)
        }

        /** Čte stav každou sekundu, dokud nesplní [done]. Chyba tiskárny = výjimka, vypršení času = null. */
        fun waitFor(timeoutMs: Long, done: (Status) -> Boolean): Status? {
            val end = System.currentTimeMillis() + timeoutMs
            var last: String? = null
            while (System.currentTimeMillis() < end) {
                val s = readStatus()
                if (s != null) {
                    if (s.toString() != last) AppLog.i(TAG, "Stav: $s (${s.stateText})")
                    last = s.toString()
                    if (s.error != 0) throw PrinterException(errorText(s.error))
                    if (done(s)) return s
                }
                Thread.sleep(1000)
            }
            return null
        }

        fun write(data: ByteArray) {
            val chunk = ByteArray(CHUNK)
            var offset = 0
            while (offset < data.size) {
                val len = minOf(CHUNK, data.size - offset)
                System.arraycopy(data, offset, chunk, 0, len)
                val n = connection.bulkTransfer(bulkOut, chunk, len, IO_TIMEOUT_MS)
                if (n <= 0) throw PrinterException("odeslání dat do tiskárny selhalo ($offset / ${data.size} B)")
                offset += n
            }
        }

        override fun close() {
            runCatching { connection.releaseInterface(intf) }
            runCatching { connection.close() }
        }

        companion object {
            fun open(manager: UsbManager, device: UsbDevice): Link {
                // Rozhraní s bulk IN i OUT; přednostně třída Printer (7), jinak první vyhovující.
                val candidates = (0 until device.interfaceCount).map { device.getInterface(it) }.mapNotNull { intf ->
                    var bulkIn: UsbEndpoint? = null
                    var bulkOut: UsbEndpoint? = null
                    for (k in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(k)
                        if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                        if (ep.direction == UsbConstants.USB_DIR_IN) { if (bulkIn == null) bulkIn = ep } else if (bulkOut == null) bulkOut = ep
                    }
                    if (bulkIn != null && bulkOut != null) Triple(intf, bulkIn, bulkOut) else null
                }
                val (intf, bulkIn, bulkOut) = candidates.firstOrNull { it.first.interfaceClass == UsbConstants.USB_CLASS_PRINTER }
                    ?: candidates.firstOrNull()
                    ?: throw PrinterException("tiskárna nemá datové USB rozhraní")
                AppLog.i(TAG, "Rozhraní ${intf.id}, třída ${intf.interfaceClass}, IN ${bulkIn.address}, OUT ${bulkOut.address}")
                val connection = manager.openDevice(device) ?: throw PrinterException("USB tiskárnu nejde otevřít")
                if (!connection.claimInterface(intf, true)) {
                    connection.close()
                    throw PrinterException("USB tiskárnu drží systém nebo jiná aplikace")
                }
                return Link(connection, intf, bulkIn, bulkOut)
            }
        }
    }
}
