package bosca.communications.mailers.smtp

import bosca.communications.mailers.Email
import jakarta.mail.internet.InternetAddress

/** SMTP sender or recipient address. */
data class SmtpEmail(
    override val name: String,
    override val email: String,
    override val customArguments: Map<String, String> = emptyMap(),
) : Email {
    internal fun address(): InternetAddress {
        require(name.none { it == '\r' || it == '\n' } && email.none { it == '\r' || it == '\n' }) {
            "SMTP addresses and display names cannot contain line breaks"
        }
        return InternetAddress(email, name, "UTF-8").apply { validate() }
    }
}
