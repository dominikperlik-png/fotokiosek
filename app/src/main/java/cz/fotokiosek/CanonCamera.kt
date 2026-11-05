package cz.fotokiosek

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Zrcadlovka Canon EOS přes USB (PTP + rozšíření Canonu, stejné příkazy jako používá gPhoto2).
 * Všechny metody jsou blokující – volat z jednoho vlákna na pozadí (viz [CameraController]).
 *
 * Fotoaparát musí být zapnutý, v režimu M/Av/Tv/P (ne video) a připojený USB kabelem.
 */
class CanonCamera(
    private val manager: UsbManager,
    val device: UsbDevice,
    /** true = fotka se uloží i na SD kartu ve fotoaparátu (pomalejší), false = jen do RAM a rovnou do aplikace. */
    private val saveToCard: Boolean = false,
) {
    private var ptp: PtpUsb? = null
    private val supported = mutableSetOf<Int>()
    var model: String = device.productName ?: "Canon EOS"
        private set
    private var liveViewOn = false
    private var halfPressed = false

    val isOpen get() = ptp != null

    fun open() {
        close()
        val p = PtpUsb.open(manager, device)
        ptp = p
        try {
            readDeviceInfo(p)
            val r = p.transaction(OC_OPEN_SESSION, 1)
            if (!r.ok && r.code != PtpUsb.RC_SESSION_ALREADY_OPEN) throw PtpUsb.PtpException("Otevření relace selhalo", r.code)
            if (OC_EOS_SET_REMOTE_MODE in supported || supported.isEmpty()) checked(p.transaction(OC_EOS_SET_REMOTE_MODE, 1), "SetRemoteMode")
            if (OC_EOS_SET_EVENT_MODE in supported || supported.isEmpty()) checked(p.transaction(OC_EOS_SET_EVENT_MODE, 1), "SetEventMode")
            drainEvents()
            // Cíl ukládání: 4 = do počítače (RAM fotoaparátu → aplikace), 2 = na kartu.
            // Bez nastavení cíle některá těla po cvaknutí nic nepošlou.
            val target = if (saveToCard) DEST_CARD else DEST_PC
            if (!setProp(PROP_CAPTURE_DESTINATION, target) && saveToCard) {
                AppLog.w(TAG, "Ukládání na kartu nejde nastavit, zkouším RAM")
                setProp(PROP_CAPTURE_DESTINATION, DEST_PC)
            }
            // Fotoaparát musí vědět, že „počítač“ má dost místa, jinak hlásí plnou paměť a fotku nepředá.
            runCatching { p.transaction(OC_EOS_PC_HDD_CAPACITY, 0x0fffffff, 0x00001000, 0x00000001) }
            AppLog.i(TAG, "Cíl ukládání: ${if (saveToCard) "karta" else "RAM → aplikace"}")
            AppLog.i(TAG, "Připojeno: $model, podporuje ${supported.size} příkazů")
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    fun close() {
        val p = ptp ?: return
        runCatching { if (liveViewOn) setProp(PROP_EVF_OUTPUT_DEVICE, 0) }
        runCatching { p.transaction(OC_EOS_SET_REMOTE_MODE, 0) }
        runCatching { p.transaction(OC_CLOSE_SESSION) }
        p.close()
        ptp = null
        liveViewOn = false
        halfPressed = false
    }

    /** Jeden snímek živého náhledu (JPEG z hledáčku) nebo null, když ještě není připravený. */
    fun liveViewFrame(): ByteArray? {
        val p = ptp ?: return null
        if (!liveViewOn) {
            setProp(PROP_EVF_MODE, 1)
            setProp(PROP_EVF_OUTPUT_DEVICE, 2) // 2 = PC
            liveViewOn = true
        }
        val r = p.transaction(OC_EOS_GET_VIEWFINDER_DATA, 0x00200000, 0, 0, timeoutMs = 3000)
        if (r.code == RC_EOS_NOT_READY || r.code == PtpUsb.RC_DEVICE_BUSY) return null
        if (!r.ok) {
            // Některá těla po chvíli živý náhled vypnou – příště ho zkusíme zapnout znovu.
            liveViewOn = false
            return null
        }
        return extractJpeg(r.data ?: return null)
    }

    /** Namáčknutí spouště (zaostření a expozice) – volá se těsně před koncem odpočtu. */
    fun halfPress() {
        val p = ptp ?: return
        if (OC_EOS_REMOTE_RELEASE_ON !in supported || halfPressed) return
        val r = p.transaction(OC_EOS_REMOTE_RELEASE_ON, 1, 0)
        halfPressed = r.ok
    }

    /** Objekt (soubor), který fotoaparát po cvaknutí nahlásil. */
    private class NewObject(val handle: Int, val size: Long, val name: String, val format: Int, val event: Int)

    /**
     * Vyfotí a stáhne JPEG.
     *
     * Příčina chyby „cvakne, ale fotka nedorazí“: při ukládání do počítače (RAM) Canon nehlásí
     * ObjectAddedEx (0xC181), ale RequestObjectTransfer (0xC186, u novějších těl 0xC1A9).
     * Původní kód čekal jen na 0xC181/0xC1A7 → po 20 s timeout → „Fotku se nepodařilo pořídit“.
     * Teď bereme všechny čtyři události a stahujeme až po dokončení zápisu.
     */
    fun capture(): ByteArray {
        val p = ptp ?: throw PtpUsb.PtpException("Zrcadlovka není připojená")
        drainEvents()
        val started = System.currentTimeMillis()
        if (OC_EOS_REMOTE_RELEASE_ON in supported) {
            val shutter = retryBusy { p.transaction(OC_EOS_REMOTE_RELEASE_ON, 3, 0) }
            AppLog.i(TAG, "Spoušť (RemoteReleaseOn 3): 0x${shutter.code.toString(16)}")
            if (!shutter.ok && halfPressed) {
                // Jinak by spoušť zůstala namáčknutá a další pokus by mohl selhat taky.
                runCatching { p.transaction(OC_EOS_REMOTE_RELEASE_OFF, 1) }
                halfPressed = false
            }
            checked(shutter, "Spoušť")
            runCatching { p.transaction(OC_EOS_REMOTE_RELEASE_OFF, 3) }
            if (halfPressed) runCatching { p.transaction(OC_EOS_REMOTE_RELEASE_OFF, 1) }
            halfPressed = false
        } else {
            val r = retryBusy { p.transaction(OC_EOS_REMOTE_RELEASE) }
            AppLog.i(TAG, "Spoušť (RemoteRelease): 0x${r.code.toString(16)}")
            checked(r, "Spoušť")
        }

        // Čekáme na nový soubor. Velký JPEG/RAW na pomalé kartě může trvat i desítky sekund.
        val timeout = if (saveToCard) 60_000L else 30_000L
        val objects = mutableListOf<NewObject>()
        val seenEvents = mutableSetOf<String>()
        while (System.currentTimeMillis() - started < timeout) {
            objects += pollNewObjects(seenEvents)
            if (objects.isNotEmpty()) {
                // RAW+JPEG pošle dva objekty po sobě – chvíli počkáme na druhý.
                Thread.sleep(400)
                objects += pollNewObjects(seenEvents)
                break
            }
            Thread.sleep(100)
        }
        if (objects.isEmpty()) {
            AppLog.e(TAG, "Po ${timeout / 1000} s nepřišla žádná fotka. Události od fotoaparátu: ${seenEvents.joinToString().ifEmpty { "žádné" }}")
            throw PtpUsb.PtpException(
                "Fotoaparát nepředal fotku do ${timeout / 1000} s (události: ${seenEvents.joinToString().ifEmpty { "žádné" }}). " +
                    "Nezaostřil? Zkus ruční ostření (MF). Je v něm karta, pokud ukládá na kartu?"
            )
        }
        AppLog.i(TAG, "Nové soubory za ${System.currentTimeMillis() - started} ms: " +
            objects.joinToString { "${it.name.ifEmpty { "?" }} (0x${it.handle.toString(16)}, ${it.size / 1024} kB, událost 0x${it.event.toString(16)})" })

        val pick = objects.firstOrNull { isJpeg(it) } ?: objects.first()
        var jpeg: ByteArray? = null
        var lastError: Exception? = null
        for (obj in objects) {
            if (obj === pick) {
                try {
                    jpeg = download(p, obj)
                } catch (e: Exception) {
                    lastError = e
                    AppLog.e(TAG, "Stažení ${obj.name} selhalo", e)
                }
            }
            // Uvolní RAM ve fotoaparátu (jinak se po pár fotkách „zasekne“).
            runCatching { p.transaction(OC_EOS_TRANSFER_COMPLETE, obj.handle) }
        }
        val data = jpeg ?: throw lastError ?: PtpUsb.PtpException("Stažení fotky selhalo")
        if (extractJpeg(data) == null && !(data.size > 2 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte())) {
            throw PtpUsb.PtpException("Fotoaparát poslal soubor, který není JPEG (${pick.name}). Nastav na něm kvalitu JPEG, ne jen RAW.")
        }
        AppLog.i(TAG, "Fotka stažena: ${data.size / 1024} kB za ${System.currentTimeMillis() - started} ms")
        return data
    }

    private fun isJpeg(o: NewObject) = o.format == FORMAT_JPEG || o.name.endsWith(".JPG", true) || o.name.endsWith(".JPEG", true)

    /** Stáhne soubor: nejdřív GetObject, při neúspěchu po částech (GetPartialObject Canonu). */
    private fun download(p: PtpUsb, obj: NewObject): ByteArray {
        var lastCode = 0
        repeat(3) { attempt ->
            val r = retryBusy { p.transaction(OC_GET_OBJECT, obj.handle, timeoutMs = 30_000) }
            val d = r.data
            if (r.ok && d != null && d.isNotEmpty()) return d
            lastCode = r.code
            AppLog.w(TAG, "GetObject pokus ${attempt + 1}: 0x${r.code.toString(16)}, ${d?.size ?: 0} B")
            Thread.sleep(300L * (attempt + 1)) // soubor se možná ještě zapisuje
        }
        if (obj.size > 0 && OC_EOS_GET_PARTIAL_OBJECT in supported) {
            AppLog.i(TAG, "Zkouším stažení po částech (${obj.size} B)")
            val out = java.io.ByteArrayOutputStream(obj.size.toInt())
            var offset = 0L
            while (offset < obj.size) {
                val len = minOf(PARTIAL_CHUNK.toLong(), obj.size - offset).toInt()
                val r = retryBusy { p.transaction(OC_EOS_GET_PARTIAL_OBJECT, obj.handle, offset.toInt(), len, timeoutMs = 30_000) }
                val d = r.data
                if (!r.ok || d == null || d.isEmpty()) throw PtpUsb.PtpException("Stažení po částech selhalo na ${offset} B (0x${r.code.toString(16)})", r.code)
                out.write(d)
                offset += d.size
            }
            return out.toByteArray()
        }
        throw PtpUsb.PtpException("Stažení fotky selhalo (0x${lastCode.toString(16)})", lastCode)
    }

    private fun readDeviceInfo(p: PtpUsb) {
        val r = p.transaction(OC_GET_DEVICE_INFO)
        val d = r.data ?: return
        runCatching {
            val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 8
            pos = PtpUsb.readString(d, pos).second // VendorExtensionDesc
            pos += 2 // FunctionalMode
            val opCount = bb.getInt(pos); pos += 4
            repeat(opCount) { supported += bb.getShort(pos).toInt() and 0xFFFF; pos += 2 }
            repeat(4) { pos += 4 + 2 * bb.getInt(pos) } // události, vlastnosti, formáty
            val manufacturer = PtpUsb.readString(d, pos).also { pos = it.second }.first
            val name = PtpUsb.readString(d, pos).first
            if (name.isNotBlank()) model = name
            AppLog.i(TAG, "DeviceInfo: $manufacturer $model")
        }
    }

    private fun setProp(code: Int, value: Int): Boolean {
        val p = ptp ?: return false
        val data = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(12).putInt(code).putInt(value).array()
        val r = retryBusy { p.transaction(OC_EOS_SET_DEVICE_PROP_VALUE_EX, dataOut = data) }
        if (!r.ok) AppLog.w(TAG, "Nastavení vlastnosti 0x${code.toString(16)} = $value vrátilo 0x${r.code.toString(16)}")
        return r.ok
    }

    private fun drainEvents() {
        repeat(5) { if (pollNewObjects(null).isEmpty()) return }
    }

    /**
     * Přečte frontu událostí (GetEvent) a vrátí nově nahlášené soubory.
     * Záznam: délka (4 B) | typ (4 B) | data. Pro všechny čtyři „soubor“ události je
     * handle na +8, velikost na +28 (64bit varianta: +28 nižší, +32 vyšší) a jméno souboru na +40 / +44.
     */
    private fun pollNewObjects(seen: MutableSet<String>?): List<NewObject> {
        val p = ptp ?: return emptyList()
        val r = p.transaction(OC_EOS_GET_EVENT)
        val d = r.data ?: return emptyList()
        val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val found = mutableListOf<NewObject>()
        var pos = 0
        while (pos + 8 <= d.size) {
            val size = bb.getInt(pos)
            val type = bb.getInt(pos + 4)
            if (size < 8 || type == 0 || pos + size > d.size) break
            seen?.add("0x" + type.toString(16))
            if (type in OBJECT_EVENTS && size >= 12) {
                val is64 = type == EVENT_OBJECT_ADDED_EX64 || type == EVENT_REQUEST_OBJECT_TRANSFER64
                val handle = bb.getInt(pos + 8)
                val format = if (size >= 18) bb.getShort(pos + 16).toInt() and 0xFFFF else 0
                val objSize = when {
                    is64 && size >= 36 -> (bb.getInt(pos + 28).toLong() and 0xFFFFFFFFL) or (bb.getInt(pos + 32).toLong() shl 32)
                    size >= 32 -> bb.getInt(pos + 28).toLong() and 0xFFFFFFFFL
                    else -> 0L
                }
                val nameAt = pos + if (is64) 44 else 40
                val name = if (nameAt < pos + size) {
                    val end = (nameAt until pos + size).firstOrNull { d[it] == 0.toByte() } ?: (pos + size)
                    String(d, nameAt, end - nameAt, Charsets.US_ASCII).trim()
                } else ""
                if (found.none { it.handle == handle }) found += NewObject(handle, objSize, name, format, type)
            }
            pos += size
        }
        return found
    }

    private fun retryBusy(block: () -> PtpUsb.Response): PtpUsb.Response {
        var r = block()
        var tries = 0
        while (r.code == PtpUsb.RC_DEVICE_BUSY && tries++ < 20) {
            Thread.sleep(50)
            r = block()
        }
        return r
    }

    private fun checked(r: PtpUsb.Response, what: String): PtpUsb.Response {
        if (!r.ok) throw PtpUsb.PtpException("$what: fotoaparát vrátil chybu 0x${r.code.toString(16)}", r.code)
        return r
    }

    companion object {
        private const val TAG = "Canon"
        const val CANON_VENDOR_ID = 0x04A9

        private const val OC_GET_DEVICE_INFO = 0x1001
        private const val OC_OPEN_SESSION = 0x1002
        private const val OC_CLOSE_SESSION = 0x1003
        private const val OC_GET_OBJECT_INFO = 0x1008
        private const val OC_GET_OBJECT = 0x1009

        private const val OC_EOS_GET_PARTIAL_OBJECT = 0x9107
        private const val OC_EOS_REMOTE_RELEASE = 0x910F
        private const val OC_EOS_SET_DEVICE_PROP_VALUE_EX = 0x9110
        private const val OC_EOS_SET_REMOTE_MODE = 0x9114
        private const val OC_EOS_SET_EVENT_MODE = 0x9115
        private const val OC_EOS_GET_EVENT = 0x9116
        private const val OC_EOS_TRANSFER_COMPLETE = 0x9117
        private const val OC_EOS_PC_HDD_CAPACITY = 0x911A
        private const val OC_EOS_REMOTE_RELEASE_ON = 0x9128
        private const val OC_EOS_REMOTE_RELEASE_OFF = 0x9129
        private const val OC_EOS_GET_VIEWFINDER_DATA = 0x9153

        private const val RC_EOS_NOT_READY = 0xA102

        private const val PROP_CAPTURE_DESTINATION = 0xD11C
        private const val PROP_EVF_OUTPUT_DEVICE = 0xD1B0
        private const val PROP_EVF_MODE = 0xD1B3

        private const val DEST_CARD = 2
        private const val DEST_PC = 4
        private const val PARTIAL_CHUNK = 1024 * 1024

        /** Soubor uložený (na kartu) */
        private const val EVENT_OBJECT_ADDED_EX = 0xC181
        private const val EVENT_OBJECT_ADDED_EX64 = 0xC1A7
        /** Soubor čeká v RAM na převzetí počítačem (cíl ukládání = PC) */
        private const val EVENT_REQUEST_OBJECT_TRANSFER = 0xC186
        private const val EVENT_REQUEST_OBJECT_TRANSFER64 = 0xC1A9
        private val OBJECT_EVENTS = setOf(
            EVENT_OBJECT_ADDED_EX, EVENT_OBJECT_ADDED_EX64, EVENT_REQUEST_OBJECT_TRANSFER, EVENT_REQUEST_OBJECT_TRANSFER64,
        )
        private const val FORMAT_JPEG = 0x3801

        fun isCanon(device: UsbDevice) = device.vendorId == CANON_VENDOR_ID && PtpUsb.isCamera(device)

        /** Najde JPEG (FFD8 … FFD9) v datech živého náhledu. */
        fun extractJpeg(data: ByteArray): ByteArray? {
            // Obvyklý formát: záznamy [délka 4 B][typ 4 B][data]; typ 1 = JPEG obrázek.
            if (data.size >= 12) {
                val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                var pos = 0
                while (pos + 8 <= data.size) {
                    val size = bb.getInt(pos)
                    val type = bb.getInt(pos + 4)
                    if (size < 8 || pos + size > data.size) break
                    if (type == 1 && size > 10 && data[pos + 8] == 0xFF.toByte() && data[pos + 9] == 0xD8.toByte()) {
                        return data.copyOfRange(pos + 8, pos + size)
                    }
                    pos += size
                }
            }
            var start = -1
            for (i in 0 until data.size - 1) {
                if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte()) { start = i; break }
            }
            if (start < 0) return null
            for (i in data.size - 2 downTo start + 2) {
                if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD9.toByte()) return data.copyOfRange(start, i + 2)
            }
            return null
        }

        /** Dekóduje JPEG, otočí/zrcadlí a zmenší (delší strana max [maxSide]). */
        fun decode(jpeg: ByteArray, rotation: Int, mirror: Boolean, maxSide: Int): Bitmap {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw IllegalStateException("JPEG ze zrcadlovky nejde dekódovat")
            val m = Matrix()
            if (rotation != 0) m.postRotate(rotation.toFloat())
            if (mirror) m.postScale(-1f, 1f)
            val longSide = max(src.width, src.height)
            if (longSide > maxSide) (maxSide.toFloat() / longSide).let { m.postScale(it, it) }
            if (m.isIdentity) return src
            return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        }
    }
}
