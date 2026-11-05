package cz.fotokiosek

import java.io.File
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

    fun send(settings: KioskSettings, to: String, subject: String, body: String, attachment: File?) {
        Thread.currentThread().contextClassLoader = Mailer::class.java.classLoader

        val host = settings.smtpHost.trim()
        val user = settings.smtpUser.trim()
        // Google zobrazuje heslo pro aplikace s mezerami, ale posílá se bez nich.
        val password = if (host.endsWith("gmail.com")) settings.smtpPassword.replace(" ", "") else settings.smtpPassword

        val props = Properties().apply {
            put("mail.smtp.host", host)
            put("mail.smtp.port", settings.smtpPort.toString())
            put("mail.smtp.auth", user.isNotEmpty().toString())
            put("mail.smtp.connectiontimeout", "20000")
            put("mail.smtp.timeout", "30000")
            put("mail.smtp.writetimeout", "60000")
            when (settings.smtpSecurity) {
                "SSL" -> {
                    put("mail.smtp.ssl.enable", "true")
                    put("mail.smtp.ssl.checkserveridentity", "true")
                }
                "STARTTLS" -> {
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.starttls.required", "true")
                    put("mail.smtp.ssl.checkserveridentity", "true")
                }
            }
        }
        val session = if (user.isEmpty()) {
            Session.getInstance(props)
        } else {
            Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication() = PasswordAuthentication(user, password)
            })
        }

        val message = MimeMessage(session)
        message.setFrom(InternetAddress(settings.fromAddress.trim().ifEmpty { user }, settings.fromName, "UTF-8"))
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
        Transport.send(message)
    }
}
