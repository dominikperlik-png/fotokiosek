package cz.fotokiosek

import android.os.SystemClock

object EmailCheck {
    private val pattern = Regex("^[a-z0-9._%+-]+@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}$")

    /** Časté překlepy v doménách → správná doména. */
    private val typos = mapOf(
        "gmial.com" to "gmail.com",
        "gmai.com" to "gmail.com",
        "gmal.com" to "gmail.com",
        "gamil.com" to "gmail.com",
        "gmail.cz" to "gmail.com",
        "gmail.co" to "gmail.com",
        "gmail.con" to "gmail.com",
        "seznam.com" to "seznam.cz",
        "sezam.cz" to "seznam.cz",
        "seznma.cz" to "seznam.cz",
        "sezanm.cz" to "seznam.cz",
        "seznam.c" to "seznam.cz",
        "emial.cz" to "email.cz",
        "centrum.c" to "centrum.cz",
        "icloud.cz" to "icloud.com",
        "outlook.cz" to "outlook.com",
        "hotmail.cz" to "hotmail.com",
        "yahoo.cz" to "yahoo.com",
    )

    fun isValid(email: String) =
        pattern.matches(email) && !email.contains("..") && !email.startsWith(".") && !email.contains(".@")

    fun suggestion(email: String): String? {
        val domain = email.substringAfter('@', "")
        val fix = typos[domain] ?: return null
        return email.substringBefore('@') + "@" + fix
    }
}

/** Ochrana proti zahlcení cizí schránky: max. 5 fotek na jednu adresu za hodinu. */
object RateLimit {
    private const val MAX_PER_HOUR = 5
    private val sent = mutableMapOf<String, MutableList<Long>>()

    @Synchronized
    fun allow(email: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val times = sent.getOrPut(email) { mutableListOf() }
        times.removeAll { now - it > 60 * 60 * 1000L }
        if (times.size >= MAX_PER_HOUR) return false
        times += now
        return true
    }
}
