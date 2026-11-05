package cz.fotokiosek

import java.io.File
import java.io.PrintStream
import java.util.Date
import java.util.Properties
import javax.activation.CommandMap
import javax.activation.DataHandler
import javax.activation.MailcapCommandMap
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.Part
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource

/** Odeslání e-mailu přes SMTP (Gmail, Seznam, Brevo, …) podle nastavení kiosku. */
object Mailer {
    init {
        // Na Androidu se mailcap z knihovny nemusí načíst sám → chyba „no object DCH for MIME type“.
        val mailcap = CommandMap.getDefaultCommandMap() as MailcapCommandMap
        mailcap.addMailcap("text/plain;; x-java-content-handler=com.sun.mail.handlers.text_plain")
        mailcap.addMailcap("text/html;; x-java-content-handler=com.sun.mail.handlers.text_html")
        mailcap.addMailcap("multipart/*;; x-java-content-handler=com.sun.mail.handlers.multipart_mixed; x-java-fallback-entry=true")
        mailcap.addMailcap("message/rfc822;; x-java-content-handler=com.sun.mail.handlers.message_rfc822")
        CommandMap.setDefaultCommandMap(mailcap)
    }

    /** Údaje pro přihlášení, jak se skutečně posílají serveru. */
    class Account(val host: String, val port: Int, val security: String, val user: String, val password: String)

    fun account(settings: KioskSettings): Account {
        val host = settings.smtpHost.trim()
        // Google zobrazuje heslo pro aplikace s mezerami, ale posílá se bez nich.
        val password = if (host.endsWith("gmail.com") || host.endsWith("googlemail.com")) {
            settings.smtpPassword.replace(" ", "")
        } else {
            settings.smtpPassword
        }
        return Account(host, settings.smtpPort, settings.smtpSecurity.uppercase(), settings.smtpUser.trim(), password)
    }

    /**
     * SMTP session. [debugOut] zachytí protokol se serverem (bez přihlašovacích údajů –
     * `mail.debug.auth` je vypnuté).
     */
    fun session(settings: KioskSettings, debugOut: PrintStream? = null): Session {
        Thread.currentThread().contextClassLoader = Mailer::class.java.classLoader
        val a = account(settings)
        val props = Properties().apply {
            put("mail.transport.protocol", "smtp")
            put("mail.smtp.host", a.host)
            put("mail.smtp.port", a.port.toString())
            put("mail.smtp.auth", a.user.isNotEmpty().toString())
            // Android často nezná vlastní jméno počítače → server by dostal nesmyslné EHLO.
            put("mail.smtp.localhost", "fotokiosek.local")
            put("mail.smtp.connectiontimeout", "20000")
            put("mail.smtp.timeout", "30000")
            put("mail.smtp.writetimeout", "60000")
            put("mail.debug.auth", "false")
            when (a.security) {
                "SSL" -> put("mail.smtp.ssl.enable", "true")
                "STARTTLS" -> {
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.starttls.required", "true")
                }
            }
            if (a.security != "NONE") {
                if (settings.smtpTrustAll) {
                    put("mail.smtp.ssl.trust", "*")
                    put("mail.smtp.ssl.checkserveridentity", "false")
                } else {
                    put("mail.smtp.ssl.checkserveridentity", "true")
                }
            }
        }
        val session = if (a.user.isEmpty()) {
            Session.getInstance(props)
        } else {
            Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication() = PasswordAuthentication(a.user, a.password)
            })
        }
        if (debugOut != null) {
            session.debug = true
            session.debugOut = debugOut
        }
        return session
    }

    fun send(settings: KioskSettings, to: String, subject: String, body: String, attachment: File?, debugOut: PrintStream? = null) {
        val a = account(settings)
        if (a.host.isEmpty()) throw IllegalStateException("Není vyplněný SMTP server (menu správce → E-mail).")
        val session = session(settings, debugOut)

        val message = MimeMessage(session)
        message.setFrom(InternetAddress(settings.fromAddress.trim().ifEmpty { a.user }, settings.fromName, "UTF-8"))
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, true))
        message.setSubject(subject, "UTF-8")
        message.sentDate = Date()

        if (attachment == null) {
            message.setText(body, "UTF-8")
        } else {
            val text = MimeBodyPart().apply { setText(body, "UTF-8") }
            val photo = MimeBodyPart().apply {
                dataHandler = DataHandler(ByteArrayDataSource(attachment.readBytes(), "image/jpeg"))
                fileName = attachment.name
                disposition = Part.ATTACHMENT
            }
            message.setContent(MimeMultipart(text, photo))
        }
        message.saveChanges()

        // Explicitní připojení místo Transport.send – ať je jasné, jestli selhalo spojení, přihlášení, nebo odeslání.
        val transport = session.getTransport("smtp")
        try {
            if (a.user.isEmpty()) transport.connect() else transport.connect(a.host, a.port, a.user, a.password)
            transport.sendMessage(message, message.allRecipients)
        } finally {
            runCatching { transport.close() }
        }
    }
}
