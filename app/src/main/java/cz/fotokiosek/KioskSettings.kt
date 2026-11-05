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
    var subject by string("subject", "Tvoje fotka z fotokoutku")
    var body by string("body", "Ahoj,\n\nv příloze posíláme tvoji fotku z fotokoutku.\n\nDíky a měj se hezky!")

    var sentCount by int("sent_count", 0)
    var lastError by string("last_error", "")

    /**
     * Načte /sdcard/Android/data/cz.fotokiosek/files/config.properties, pokud existuje,
     * a pak ho smaže (obsahuje heslo k e-mailu).
     */
    fun importConfig(context: Context): Boolean {
        val file = File(context.getExternalFilesDir(null) ?: return false, "config.properties")
        if (!file.exists()) return false
        val props = Properties().apply {
            load(StringReader(file.readText(Charsets.UTF_8).removePrefix("﻿")))
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
