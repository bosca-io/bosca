package bosca.communications.mailers.smtp

import kotlinx.serialization.Serializable
import java.util.Properties

/** SMTP connection settings and credentials stored in encrypted platform configuration. */
@Serializable
data class SmtpConfiguration(
    val host: String,
    val port: Int = 587,
    val security: SmtpSecurity = SmtpSecurity.STARTTLS,
    val username: String = "",
    val password: String = "",
) {
    internal fun properties(): Properties {
        require(host.isNotBlank() && host.none { it.isWhitespace() }) { "SMTP host is not configured or invalid" }
        require(port in 1..65535) { "SMTP port must be between 1 and 65535" }
        require(username.isNotBlank() == password.isNotEmpty()) { "SMTP username and password must both be set or both be empty" }
        return Properties().apply {
            setProperty("mail.smtp.auth", username.isNotBlank().toString())
            setProperty("mail.smtp.starttls.enable", (security == SmtpSecurity.STARTTLS).toString())
            setProperty("mail.smtp.starttls.required", (security == SmtpSecurity.STARTTLS).toString())
            setProperty("mail.smtp.ssl.enable", (security == SmtpSecurity.TLS).toString())
            setProperty("mail.smtp.ssl.checkserveridentity", "true")
            setProperty("mail.smtp.connectiontimeout", "10000")
            setProperty("mail.smtp.timeout", "60000")
            setProperty("mail.smtp.writetimeout", "60000")
        }
    }

    companion object {
        const val KEY = "smtp"
    }
}
