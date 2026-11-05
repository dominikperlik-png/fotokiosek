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
class CanonCamera(private val manager: UsbManager, val device: UsbDevice) {
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
            // Fotky posílat do počítače (RAM), ne jen na kartu – a říct fotoaparátu, že máme dost místa.
            setProp(PROP_CAPTURE_DESTINATION, 4)
            runCatching { p.transaction(OC_EOS_PC_HDD_CAPACITY, 0x0fffffff, 0x00001000, 0x00000001) }
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

    /** Vyfotí a stáhne JPEG. Vrací bajty JPEG. */
    fun capture(): ByteArray {
        val p = ptp ?: throw PtpUsb.PtpException("Zrcadlovka není připojená")
        drainEvents()
        if (OC_EOS_REMOTE_RELEASE_ON in supported) {
            val shutter = retryBusy { p.transaction(OC_EOS_REMOTE_RELEASE_ON, 3, 0) }
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
            checked(retryBusy { p.transaction(OC_EOS_REMOTE_RELEASE) }, "Spoušť")
        }

        // Čekáme na událost „přidán objekt“ (hotová fotka v paměti fotoaparátu).
        val deadline = System.currentTimeMillis() + 20_000
        val handles = mutableListOf<Int>()
        while (System.currentTimeMillis() < deadline) {
            handles += pollObjectAdded()
            if (handles.isNotEmpty()) {
                // RAW+JPEG pošle dva objekty těsně po sobě
                Thread.sleep(150)
                handles += pollObjectAdded()
                break
            }
            Thread.sleep(80)
        }
        if (handles.isEmpty()) throw PtpUsb.PtpException("Zrcadlovka nevyfotila (nezaostřila? Zkus manuální ostření nebo víc světla)")

        var jpeg: ByteArray? = null
        for (handle in handles) {
            val info = p.transaction(OC_GET_OBJECT_INFO, handle)
            val format = info.data?.takeIf { it.size >= 6 }?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).getShort(4).toInt() and 0xFFFF }
            if (jpeg == null && (format == FORMAT_JPEG || format == null)) {
                val obj = p.transaction(OC_GET_OBJECT, handle, timeoutMs = 20_000)
                checked(obj, "Stažení fotky")
                jpeg = obj.data
            }
            runCatching { p.transaction(OC_EOS_TRANSFER_COMPLETE, handle) }
        }
        return jpeg ?: throw PtpUsb.PtpException("Fotoaparát neposlal JPEG – nastav na něm kvalitu JPEG (ne jen RAW)")
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

    private fun setProp(code: Int, value: Int) {
        val p = ptp ?: return
        val data = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(12).putInt(code).putInt(value).array()
        val r = retryBusy { p.transaction(OC_EOS_SET_DEVICE_PROP_VALUE_EX, dataOut = data) }
        if (!r.ok) AppLog.w(TAG, "Nastavení vlastnosti 0x${code.toString(16)} = $value vrátilo 0x${r.code.toString(16)}")
    }

    private fun drainEvents() {
        repeat(5) { if (pollObjectAdded().isEmpty()) return }
    }

    /** Přečte frontu událostí a vrátí handly nově přidaných objektů. */
    private fun pollObjectAdded(): List<Int> {
        val p = ptp ?: return emptyList()
        val r = p.transaction(OC_EOS_GET_EVENT)
        val d = r.data ?: return emptyList()
        val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val handles = mutableListOf<Int>()
        var pos = 0
        while (pos + 8 <= d.size) {
            val size = bb.getInt(pos)
            val type = bb.getInt(pos + 4)
            if (size < 8 || type == 0) break
            if ((type == EVENT_OBJECT_ADDED_EX || type == EVENT_OBJECT_ADDED_EX64) && pos + 12 <= d.size) {
                handles += bb.getInt(pos + 8)
            }
            pos += size
        }
        return handles
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

        private const val EVENT_OBJECT_ADDED_EX = 0xC181
        private const val EVENT_OBJECT_ADDED_EX64 = 0xC1A7
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
