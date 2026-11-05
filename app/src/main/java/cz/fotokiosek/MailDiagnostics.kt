package cz.fotokiosek

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.text.DateFormat
import java.util.Date
import javax.mail.AuthenticationFailedException
import javax.mail.SendFailedException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Test připojení k poště krok po kroku: nastavení → DNS → spojení → TLS → přihlášení → (odeslání).
 * Každý krok vrátí srozumitelnou příčinu a radu, co s tím.
 */
object MailDiagnostics {
    private const val TAG = "Mail"

    class Step(val name: String, val ok: Boolean, val detail: String, val hint: String? = null)
    class Report(val steps: List<Step>, val protocolLog: String) {
        val ok get() = steps.isNotEmpty() && steps.all { it.ok }
    }

    /** Spustit na pozadí (síť). [sendTo] != null → na konci pošle i testovací e-mail. */
    fun run(settings: KioskSettings, sendTo: String?): Report {
        val steps = mutableListOf<Step>()
        val log = ByteArrayOutputStream()
        val a = Mailer.account(settings)
        AppLog.i(TAG, "Test pošty: ${a.host}:${a.port} ${a.security}, uživatel ${a.user.ifEmpty { "(žádný)" }}")

        fun finish(): Report {
            val protocol = log.toString("UTF-8").lines()
                .filterNot { it.startsWith("AUTH ", ignoreCase = true) } // pro jistotu nic s přihlášením
                .takeLast(60).joinToString("\n")
            steps.forEach { s -> if (!s.ok) AppLog.e(TAG, "${s.name}: ${s.detail}${s.hint?.let { " | $it" } ?: ""}") }
            if (steps.all { it.ok }) AppLog.i(TAG, "Test pošty OK")
            return Report(steps, protocol)
        }

        // 1) Nastavení
        val problems = buildList {
            if (a.host.isEmpty()) add("chybí SMTP server")
            if (a.port !in 1..65535) add("neplatný port")
            if (a.user.isNotEmpty() && a.password.isEmpty()) add("chybí heslo")
            if (a.security == "SSL" && a.port == 587) add("port 587 se používá se STARTTLS, ne se SSL")
            if (a.security == "STARTTLS" && a.port == 465) add("port 465 se používá se SSL, ne se STARTTLS")
        }
        val gmailHint = if (a.host.endsWith("gmail.com") && a.password.length != 16) {
            "Heslo pro aplikace od Googlu má přesně 16 písmen (teď ${a.password.length} znaků). Heslo k účtu nefunguje."
        } else null
        if (problems.isNotEmpty()) {
            steps += Step("Nastavení", false, problems.joinToString(", "), "Doplň údaje v sekci E-mail nebo klepni na předvolbu Gmail / Seznam / Brevo.")
            return finish()
        }
        steps += Step("Nastavení", true, "${a.host}:${a.port}, ${a.security}", gmailHint)

        // 2) DNS
        val address = try {
            val all = InetAddress.getAllByName(a.host)
            steps += Step("Překlad adresy (DNS)", true, all.joinToString { it.hostAddress ?: "?" })
            all.first()
        } catch (e: Exception) {
            steps += Step("Překlad adresy (DNS)", false, AppLog.causes(e), explain(e, settings))
            return finish()
        }

        // 3) TCP spojení
        try {
            Socket().use { it.connect(InetSocketAddress(address, a.port), 10_000) }
            steps += Step("Spojení se serverem", true, "port ${a.port} je dostupný")
        } catch (e: Exception) {
            steps += Step("Spojení se serverem", false, AppLog.causes(e), explain(e, settings))
            return finish()
        }

        // 4) TLS (u SSL rovnou, u STARTTLS ho ověří až krok přihlášení)
        if (a.security == "SSL") {
            try {
                val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
                socket.use {
                    it.connect(InetSocketAddress(address, a.port), 10_000)
                    it.soTimeout = 15_000
                    it.startHandshake()
                    val cert = it.session.peerCertificates.firstOrNull() as? X509Certificate
                    val until = cert?.notAfter?.let { d -> DateFormat.getDateInstance().format(d) } ?: "?"
                    steps += Step("Šifrované spojení (TLS)", true, "${it.session.protocol}, certifikát platný do $until")
                }
            } catch (e: Exception) {
                val certProblem = isCertificateProblem(e)
                if (certProblem && settings.smtpTrustAll) {
                    steps += Step("Šifrované spojení (TLS)", true, "certifikát nejde ověřit, ale je zapnuté „Tolerovat certifikát“")
                } else {
                    steps += Step("Šifrované spojení (TLS)", false, AppLog.causes(e), explain(e, settings))
                    return finish()
                }
            }
        }

        // 5) SMTP + přihlášení
        val out = PrintStream(log, true, "UTF-8")
        try {
            val session = Mailer.session(settings, out)
            val transport = session.getTransport("smtp")
            try {
                if (a.user.isEmpty()) transport.connect() else transport.connect(a.host, a.port, a.user, a.password)
                steps += Step("Přihlášení k poště", true, if (a.user.isEmpty()) "bez přihlášení" else "přihlášeno jako ${a.user}")
            } finally {
                runCatching { transport.close() }
            }
        } catch (e: Exception) {
            steps += Step("Přihlášení k poště", false, AppLog.causes(e), explain(e, settings))
            return finish()
        }

        // 6) Testovací e-mail
        if (sendTo != null) {
            try {
                Mailer.send(settings, sendTo, "Test fotokiosku", "Odesílání e-mailů z fotokiosku funguje.", null, out)
                steps += Step("Odeslání testovacího e-mailu", true, "odesláno na $sendTo (zkontroluj i spam)")
            } catch (e: Exception) {
                steps += Step("Odeslání testovacího e-mailu", false, AppLog.causes(e), explain(e, settings))
            }
        }
        return finish()
    }

    private fun isCertificateProblem(e: Throwable): Boolean = generateSequence(e) { it.cause.takeIf { c -> c !== it } }
        .any {
            it is CertPathValidatorException || it is CertificateExpiredException || it is CertificateNotYetValidException ||
                (it.message ?: "").contains("Trust anchor", true) || (it.message ?: "").contains("certification path", true)
        }

    /** Lidské vysvětlení chyby + rada. Používá i fronta odesílání pro „Poslední chybu“. */
    fun explain(e: Throwable, settings: KioskSettings): String {
        val chain = generateSequence(e) { it.cause.takeIf { c -> c !== it } }.toList()
        val text = chain.joinToString(" ") { it.message ?: "" }
        val port = settings.smtpPort
        return when {
            chain.any { it is AuthenticationFailedException } || text.contains("535") || text.contains("Username and Password not accepted", true) ->
                "Server odmítl jméno nebo heslo. U Gmailu musí být heslo pro aplikace (16 písmen z myaccount.google.com/apppasswords), ne heslo k účtu. U Seznamu povol v nastavení schránky přístup z poštovních programů. U Brevo je jméno login z SMTP & API a heslo SMTP klíč."
            chain.any { it is UnknownHostException } ->
                "Název serveru „${settings.smtpHost}“ nejde najít. Buď automat nemá internet (zkontroluj Wi-Fi), nebo je v názvu serveru překlep."
            chain.any { it is SocketTimeoutException } ->
                "Server na portu $port neodpovídá (vypršel čas). Síť ten port možná blokuje – zkus druhou kombinaci: 465 + SSL, nebo 587 + STARTTLS."
            chain.any { it is ConnectException || it is NoRouteToHostException } ->
                "Spojení na port $port bylo odmítnuto. Zkontroluj port (465 = SSL, 587 = STARTTLS) a jestli síť nepoužívá firewall."
            isCertificateProblem(e) -> {
                val now = DateFormat.getDateTimeInstance().format(Date())
                "Certifikát serveru nejde ověřit. 1) Zkontroluj datum a čas na automatu (teď ukazuje: $now). 2) Je-li čas správně, automat má starý Android bez aktuálních certifikátů – zapni v sekci E-mail „Tolerovat neověřený certifikát“."
            }
            text.contains("Unrecognized SSL message", true) || text.contains("plaintext connection", true) ||
                text.contains("wrong version number", true) || (chain.any { it is SSLException } && port == 587) ->
                "Port a zabezpečení k sobě nesedí. Port 465 = SSL, port 587 = STARTTLS."
            text.contains("STARTTLS", true) && text.contains("not support", true) ->
                "Server nepodporuje STARTTLS na portu $port. Použij 465 + SSL."
            chain.any { it is SSLHandshakeException } ->
                "Šifrované spojení se nepodařilo navázat (${chain.last().message}). Zkus druhou kombinaci portu a zabezpečení, případně „Tolerovat neověřený certifikát“."
            chain.any { it is SendFailedException } || text.contains("550") || text.contains("553") ->
                "Server odmítl adresu příjemce nebo odesílatele. U Brevo musí být adresa odesílatele ověřená (Senders). Adresa odesílatele: ${settings.fromAddress.ifEmpty { settings.smtpUser }}."
            text.contains("454") || text.contains("421") || text.contains("limit", true) ->
                "Server dočasně odmítá poštu (limit odeslaných e-mailů nebo přetížení). Fotky ve frontě se pošlou později samy."
            else -> "Nečekaná chyba: ${AppLog.causes(e)}"
        }
    }
}
