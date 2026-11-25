package cz.fotokiosek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * QR kódy z webového účetnictví (web/assets/qr.js, vygenerované do resources/qr-web-fixtures.txt skriptem
 * gen-qr-fixtures.js) musí přečíst čtečka automatu (QrScan = ZXing) – i zrcadlově převrácené, jak je vidí kamera.
 */
class QrScanTest {

    private data class Fixture(val text: String, val matrix: List<String>)

    private fun fixtures(): List<Fixture> {
        // Git na Windows může soubor vybalit s CRLF.
        val raw = javaClass.classLoader!!.getResource("qr-web-fixtures.txt")!!.readText().replace("\r\n", "\n")
        return raw.trim().split("\n\n").map { block ->
            val lines = block.trim().lines()
            Fixture(String(Base64.getDecoder().decode(lines[0]), Charsets.UTF_8), lines.drop(1))
        }
    }

    /** Matice → obrázek (4 px na modul, tichá zóna 4 moduly), volitelně zrcadlově. */
    private fun render(matrix: List<String>, mirror: Boolean): Triple<Int, Int, IntArray> {
        val n = matrix.size
        val scale = 4
        val quiet = 4
        val size = (n + 2 * quiet) * scale
        val px = IntArray(size * size) { 0xFFFFFFFF.toInt() }
        for (y in 0 until n) for (x in 0 until n) {
            if (matrix[y][x] != '1') continue
            val mx = if (mirror) n - 1 - x else x
            for (dy in 0 until scale) for (dx in 0 until scale) {
                px[((y + quiet) * scale + dy) * size + (mx + quiet) * scale + dx] = 0xFF000000.toInt()
            }
        }
        return Triple(size, size, px)
    }

    @Test
    fun webQrCodesDecode() {
        val all = fixtures()
        assertTrue("málo testovacích kódů", all.size >= 20)
        for (f in all) {
            val (w, h, px) = render(f.matrix, mirror = false)
            assertEquals("verze ${(f.matrix.size - 17) / 4}", f.text, QrScan.decode(w, h, px))
        }
    }

    @Test
    fun mirroredQrCodesDecode() {
        for (f in fixtures()) {
            val (w, h, px) = render(f.matrix, mirror = true)
            assertEquals("zrcadlově, verze ${(f.matrix.size - 17) / 4}", f.text, QrScan.decode(w, h, px))
        }
    }

    @Test
    fun parsesReportPayload() {
        val d = QrScan.parse("fotokiosek:report?u=https%3A%2F%2Fmuj-server.cz%2Ffotokiosek%2Fapi.php&t=abc123&n=Automat%20%C4%8Dern%C3%BD")
        assertEquals(QrScan.Data.Report("https://muj-server.cz/fotokiosek/api.php", "abc123", "Automat černý"), d)
    }

    @Test
    fun parsesGeminiAndPlainText() {
        assertEquals(QrScan.Data.Gemini("AIzaKey_1-2"), QrScan.parse("fotokiosek:gemini?k=AIzaKey_1-2"))
        val plain = QrScan.parse("  AIzaSyD-plainKEY0123456789abcdefgh ")
        assertEquals(QrScan.Data.Text("AIzaSyD-plainKEY0123456789abcdefgh"), plain)
        assertTrue(QrScan.looksLikeKey((plain as QrScan.Data.Text).text))
        assertEquals(QrScan.Data.Text("fotokiosek:report?u=&t="), QrScan.parse("fotokiosek:report?u=&t="))
        // Novější tvar klíče Google s tečkou a další znaky (z webu zakódované přes encodeURIComponent).
        assertEquals(QrScan.Data.Gemini("AQ.Ab8RN6x_y-z+1/2="), QrScan.parse("fotokiosek:gemini?k=AQ.Ab8RN6x_y-z%2B1%2F2%3D"))
        assertTrue(QrScan.looksLikeKey("AQ.Ab8RN6LbV0aBcDeFgHiJkLmNoPq"))
        assertTrue(!QrScan.looksLikeKey("kratky"))
        assertTrue(!QrScan.looksLikeKey("dva slova jsou tady mezera mezi"))
    }

    @Test
    fun emptyImageGivesNull() {
        val px = IntArray(200 * 200) { 0xFFFFFFFF.toInt() }
        assertEquals(null, QrScan.decode(200, 200, px))
        assertNotNull(fixtures().first())
    }
}
