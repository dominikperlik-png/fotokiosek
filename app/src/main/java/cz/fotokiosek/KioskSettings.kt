package cz.fotokiosek

import android.content.Context
import java.io.File
import java.io.StringReader
import java.util.Properties
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Nastavení kiosku uložené v SharedPreferences.
 * Stejné klíče jde nahrát ze souboru config.properties (viz [importConfig]).
 */
class KioskSettings(context: Context) {
    private val prefs = context.getSharedPreferences("kiosk", Context.MODE_PRIVATE)
    private val intKeys = mutableSetOf<String>()
    private val boolKeys = mutableSetOf<String>()

    var pin by string("pin", "1234")
    var welcomeText by string("welcome_text", "Usměj se a vyfoť se!")
    var cameraId by string("camera_id", "")
    var mirror by bool("mirror", true)
    var fillScreen by bool("fill_screen", true)
    var countdown by int("countdown", 3)
    var idleTimeout by int("idle_timeout", 45)
    var keepDays by int("keep_days", 7)

    var smtpHost by string("smtp_host", "smtp.gmail.com")
    var smtpPort by int("smtp_port", 465)
    var smtpSecurity by string("smtp_security", "SSL") // SSL, STARTTLS nebo NONE
    var smtpUser by string("smtp_user", "")
    var smtpPassword by string("smtp_password", "")
    var fromAddress by string("from_address", "")
    var fromName by string("from_name", "Fotokoutek")
    /** Kam má zákazník odpovídat (prázdné = adresa odesílatele). */
    var replyTo by string("reply_to", "")
    var subject by string("subject", "Tvoje fotka z fotokoutku")
    var body by string("body", "Ahoj,\n\nv příloze posíláme tvoji fotku z fotokoutku.\n\nDíky a měj se hezky!")

    /** Tolerovat neověřitelný certifikát SMTP serveru (staré Androidy bez aktuálních kořenových certifikátů). */
    var smtpTrustAll by bool("smtp_trust_all", false)

    /** Zdroj obrazu: auto (Canon, když je připojený, jinak kamera Androidu), android, canon. */
    var cameraSource by string("camera_source", "auto")
    /** O kolik ms dřív se pustí spoušť, aby fotka vznikla přesně s nápisem „Úsměv!“. */
    var shutterLeadMs by int("shutter_lead_ms", 250)
    /** Otočení obrazu zrcadlovky (0/90/180/270) – když je fotoaparát namontovaný na výšku. */
    var canonRotation by int("canon_rotation", 0)
    /**
     * Otočení JEN živého náhledu vestavěné kamery Androidu (0/90/180/270). Fotka se tím nemění.
     * Výchozí 180: na automatu byl náhled vestavěné kamery vzhůru nohama, i když fotka byla správně.
     */
    var previewRotation by int("preview_rotation", 180)
    /** Zrcadlovka uloží fotku i na SD kartu (pomalejší; jinak jen RAM → rovnou do aplikace). */
    var canonSaveToCard by bool("canon_save_to_card", false)

    var printEnabled by bool("print_enabled", false)
    /** ipp = síťová tiskárna přímo (bez dialogů), system = tiskový dialog Androidu. */
    var printMode by string("print_mode", "ipp")
    var printerUrl by string("printer_url", "")
    var printerName by string("printer_name", "")
    var printMaxCopies by int("print_max_copies", 5)
    /** Model tiskárny – určuje formát 10×15: selphy_cp1500 (100×148 mm), dnp_qw410 / generic (4×6″). */
    var printerModel by string("printer_model", "generic")

    var logoOnScreen by bool("logo_on_screen", true)
    var logoOnPhoto by bool("logo_on_photo", true)
    /** TL, TC, TR, BL, BC, BR (roh nebo střed horního/dolního okraje fotky). */
    var logoPosition by string("logo_position", "BR")
    /** Které logo: none, custom (nahrané), builtin:allin, builtin:satisfactory, builtin:watt. */
    var logoChoice by string("logo_choice", "custom")
    /** Šířka loga v procentech šířky fotky. */
    var logoSize by int("logo_size", 18)

    var githubRepo by string("github_repo", BuildConfig.GITHUB_REPO)
    /** Jen pro soukromý repozitář: token s právem číst obsah. */
    var githubToken by string("github_token", "")

    /** Okamžitě a trvale uloží nový PIN (commit, ne apply – ať přežije i okamžitý restart). */
    fun savePin(newPin: String): Boolean = prefs.edit().putString("pin", newPin).commit()

    var sentCount by int("sent_count", 0)
    var printCount by int("print_count", 0)
    var lastError by string("last_error", "")

    /**
     * Načte /sdcard/Android/data/cz.fotokiosek/files/config.properties, pokud existuje,
     * a pak ho smaže (obsahuje heslo k e-mailu).
     */
    fun importConfig(context: Context): Boolean {
        val file = File(context.getExternalFilesDir(null) ?: return false, "config.properties")
        if (!file.exists()) return false
        val props = try {
            Properties().apply {
                load(StringReader(file.readText(Charsets.UTF_8).removePrefix("﻿")))
            }
        } catch (e: Exception) {
            // Např. „\u“ v textu (cesta C:\Users…) → Properties.load spadne. Bez tohohle by aplikace
            // padala při každém startu (soubor by se nikdy nesmazal). Soubor obsahuje heslo → smazat.
            AppLog.e("Nastavení", "config.properties nejde přečíst – ignoruji ho a mažu", e)
            file.delete()
            return false
        }
        val editor = prefs.edit()
        for (key in props.stringPropertyNames()) {
            val value = props.getProperty(key).trim()
            when (key) {
                in intKeys -> value.toIntOrNull()?.let { editor.putInt(key, it) }
                in boolKeys -> editor.putBoolean(key, value.lowercase() in setOf("true", "1", "ano", "yes"))
                else -> editor.putString(key, value)
            }
        }
        editor.apply()
        file.delete()
        return true
    }

    private fun string(key: String, default: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getString(key, default) ?: default
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            prefs.edit().putString(key, value).apply()
        }
    }

    private fun int(key: String, default: Int): ReadWriteProperty<Any?, Int> {
        intKeys += key
        return object : ReadWriteProperty<Any?, Int> {
            override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getInt(key, default)
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
                prefs.edit().putInt(key, value).apply()
            }
        }
    }

    private fun bool(key: String, default: Boolean): ReadWriteProperty<Any?, Boolean> {
        boolKeys += key
        return object : ReadWriteProperty<Any?, Boolean> {
            override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getBoolean(key, default)
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
                prefs.edit().putBoolean(key, value).apply()
            }
        }
    }
}
