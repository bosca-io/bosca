package bosca.communications.mailers.smtp

import kotlinx.serialization.Serializable

/** Transport security for an SMTP connection. STARTTLS requires a successful TLS upgrade. */
@Serializable
enum class SmtpSecurity {
    NONE,
    STARTTLS,
    TLS,
}
