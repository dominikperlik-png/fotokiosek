package cz.fotokiosek

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimální PTP (Picture Transfer Protocol) přes USB bulk přenosy.
 * Stačí na ovládání zrcadlovek (Canon EOS) bez gPhoto2 – Android má USB Host API přímo.
 *
 * Kontejner PTP: délka (4 B) | typ (2 B) | kód (2 B) | id transakce (4 B) | parametry / data. Little-endian.
 */
class PtpUsb private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint,
) {
    class Response(val code: Int, val params: IntArray, val data: ByteArray?) {
        val ok get() = code == RC_OK
    }

    class PtpException(message: String, val code: Int = 0) : IOException(message)

    private var transactionId = 0
    private val buffer = ByteArray(CHUNK)

    @Synchronized
    fun transaction(code: Int, vararg params: Int, dataOut: ByteArray? = null, timeoutMs: Int = 5000): Response {
        transactionId++
        val tid = transactionId

        // 1) Příkaz
        val command = ByteBuffer.allocate(12 + 4 * params.size).order(ByteOrder.LITTLE_ENDIAN)
        command.putInt(12 + 4 * params.size).putShort(TYPE_COMMAND.toShort()).putShort(code.toShort()).putInt(tid)
        params.forEach { command.putInt(it) }
        write(command.array(), timeoutMs)

        // 2) Data od nás (např. SetDevicePropValue)
        if (dataOut != null) {
            val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(12 + dataOut.size).putShort(TYPE_DATA.toShort()).putShort(code.toShort()).putInt(tid)
            write(header.array() + dataOut, timeoutMs)
        }

        // 3) Data od zařízení (volitelně) a odpověď
        var data: ByteArray? = null
        while (true) {
            val container = readContainer(timeoutMs)
            val bb = ByteBuffer.wrap(container).order(ByteOrder.LITTLE_ENDIAN)
            val type = bb.getShort(4).toInt() and 0xFFFF
            val rcode = bb.getShort(6).toInt() and 0xFFFF
            when (type) {
                TYPE_DATA -> data = container.copyOfRange(12, container.size)
                TYPE_RESPONSE -> {
                    val count = (container.size - 12) / 4
                    val rparams = IntArray(count) { bb.getInt(12 + 4 * it) }
                    return Response(rcode, rparams, data)
                }
                else -> { /* událost na bulk kanálu – ignorujeme */ }
            }
        }
    }

    /** Přečte jeden celý kontejner (může přijít ve více USB paketech). */
    private fun readContainer(timeoutMs: Int): ByteArray {
        var n: Int
        var emptyReads = 0
        do {
            n = connection.bulkTransfer(endpointIn, buffer, buffer.size, timeoutMs)
            if (n < 0) throw PtpException("Zrcadlovka neodpovídá (USB čtení selhalo)")
            if (n == 0 && ++emptyReads > 3) throw PtpException("Zrcadlovka posílá prázdné odpovědi")
        } while (n == 0) // nulový paket po datech dělitelných velikostí paketu
        if (n < 12) throw PtpException("Neplatná odpověď PTP ($n B)")
        val total = ByteBuffer.wrap(buffer, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (total < 12 || total > MAX_CONTAINER) throw PtpException("Neplatná délka PTP kontejneru: $total")
        if (n >= total) return buffer.copyOf(total)

        val out = ByteArrayOutputStream(total)
        out.write(buffer, 0, n)
        var have = n
        while (have < total) {
            val r = connection.bulkTransfer(endpointIn, buffer, minOf(buffer.size, total - have).coerceAtLeast(endpointIn.maxPacketSize), timeoutMs)
            if (r < 0) throw PtpException("Přenos dat ze zrcadlovky se přerušil ($have / $total B)")
            if (r == 0) continue
            out.write(buffer, 0, minOf(r, total - have))
            have += r
        }
        return out.toByteArray()
    }

    private fun write(bytes: ByteArray, timeoutMs: Int) {
        var offset = 0
        while (offset < bytes.size) {
            val len = minOf(CHUNK, bytes.size - offset)
            val chunk = if (offset == 0 && len == bytes.size) bytes else bytes.copyOfRange(offset, offset + len)
            val n = connection.bulkTransfer(endpointOut, chunk, len, timeoutMs)
            if (n < 0) throw PtpException("Zápis do zrcadlovky selhal")
            offset += n
        }
        // Když délka přesně vyplní poslední paket, PTP chce ukončovací nulový paket.
        if (bytes.size % endpointOut.maxPacketSize == 0) connection.bulkTransfer(endpointOut, ByteArray(0), 0, timeoutMs)
    }

    fun close() {
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
    }

    companion object {
        const val TYPE_COMMAND = 1
        const val TYPE_DATA = 2
        const val TYPE_RESPONSE = 3

        const val RC_OK = 0x2001
        const val RC_DEVICE_BUSY = 0x2019
        const val RC_SESSION_ALREADY_OPEN = 0x201E

        /** Starší Androidy (< 9) zvládnou max. 16 kB na jedno volání bulkTransfer. */
        private const val CHUNK = 16 * 1024
        private const val MAX_CONTAINER = 200 * 1024 * 1024

        /** Rozhraní „Still Image“ (třída 6) – to má každý fotoaparát s PTP. */
        fun findPtpInterface(device: UsbDevice): UsbInterface? =
            (0 until device.interfaceCount).map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }

        fun isCamera(device: UsbDevice) = findPtpInterface(device) != null

        /**
         * Otevře zařízení a převezme rozhraní. `force = true` odpojí systémový ovladač,
         * který si fotoaparát mohl zabrat (MTP/PTP v jádře).
         */
        fun open(manager: UsbManager, device: UsbDevice): PtpUsb {
            val intf = findPtpInterface(device) ?: throw PtpException("Zařízení nemá PTP rozhraní (není v režimu PTP?)")
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn = ep else bulkOut = ep
            }
            if (bulkIn == null || bulkOut == null) throw PtpException("PTP rozhraní nemá bulk koncové body")
            val connection = manager.openDevice(device) ?: throw PtpException("USB zařízení nejde otevřít (chybí oprávnění?)")
            if (!connection.claimInterface(intf, true)) {
                connection.close()
                throw PtpException("Fotoaparát drží jiná aplikace nebo systém (převzetí USB rozhraní selhalo)")
            }
            return PtpUsb(connection, intf, bulkIn, bulkOut)
        }

        /** Čtení řetězce PTP (počet znaků + UTF-16LE) z dat. Vrací text a novou pozici. */
        fun readString(data: ByteArray, offset: Int): Pair<String, Int> {
            if (offset >= data.size) return "" to offset
            val count = data[offset].toInt() and 0xFF
            if (count == 0) return "" to offset + 1
            val sb = StringBuilder()
            for (i in 0 until count) {
                val p = offset + 1 + i * 2
                if (p + 1 >= data.size) break
                val ch = (data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8)
                if (ch != 0) sb.append(ch.toChar())
            }
            return sb.toString() to offset + 1 + count * 2
        }
    }
}
