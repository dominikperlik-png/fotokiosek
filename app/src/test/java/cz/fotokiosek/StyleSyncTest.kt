package cz.fotokiosek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Knihovna stylů z webu (web/styles-api.php): adresa a čtení odpovědi. */
class StyleSyncTest {

    @Test
    fun stylesUrlFromReportUrl() {
        assertEquals("https://muj-server.cz/fk/styles-api.php", StyleSync.stylesUrl(" https://muj-server.cz/fk/api.php "))
        assertEquals("https://x.cz/styles-api.php", StyleSync.stylesUrl("https://x.cz/API.PHP"))
        assertNull(StyleSync.stylesUrl(""))
        assertNull(StyleSync.stylesUrl("https://x.cz/fk/"))
        assertNull(StyleSync.stylesUrl("https://x.cz/myapi.php"))
    }

    @Test
    fun parsesServerResponse() {
        val json = """{"ok":true,"styles":[
            {"id":"comic","name":"Komiks","icon":"💥","prompt":"Comic.","thumb":"comic-9f3e1a2b.jpg","updated":1},
            {"id":"anime","name":"Anime","icon":"","prompt":"Anime.","thumb":null,"updated":1},
            {"id":"Bad Id","name":"x","prompt":"y","thumb":null},
            {"id":"empty","name":" ","prompt":"y","thumb":null},
            {"id":"evil","name":"Evil","prompt":"z","thumb":"../../shared_prefs/kiosk.xml"},
            {"id":"comic","name":"Duplicate","prompt":"d","thumb":null}
        ]}"""
        val styles = StyleSync.parse(json, null)
        assertEquals(listOf("comic", "anime", "evil"), styles.map { it.id })
        assertEquals("comic-9f3e1a2b.jpg", styles[0].thumb)
        assertNull(styles[1].thumb)
        assertNull("cesta mimo složku stylů se nesmí přijmout", styles[2].thumb)
        // Se složkou: cesta jen k existujícímu (staženému) náhledu, jinak ikona.
        val dir = java.nio.file.Files.createTempDirectory("styles").toFile()
        assertNull(StyleSync.parse(json, dir)[0].thumb)
        File(dir, "comic-9f3e1a2b.jpg").writeBytes(byteArrayOf(1))
        assertEquals(File(dir, "comic-9f3e1a2b.jpg").path, StyleSync.parse(json, dir)[0].thumb)
        dir.deleteRecursively()
    }

    @Test
    fun parsesWebConfig() {
        assertNull("starší web bez config", StyleSync.parseConfig("""{"ok":true,"styles":[]}"""))
        val c = StyleSync.parseConfig(
            """{"ok":true,"styles":[],"config":{"max_copies":4,"price_photo":100,"price_ai":49.9,"price_email":0,"currency":"Kč"}}""",
        )!!
        assertEquals(StyleSync.WebConfig(4, "100", "49.9", "0", "Kč"), c)
        val none = StyleSync.parseConfig(
            """{"config":{"max_copies":null,"price_photo":-5,"price_ai":"abc","currency":""}}""",
        )!!
        assertEquals(StyleSync.WebConfig(0, "", "", "", "Kč"), none)
        assertEquals(20, StyleSync.parseConfig("""{"config":{"max_copies":99}}""")!!.maxCopies)
    }
}
