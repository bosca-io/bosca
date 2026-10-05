package bosca.communications.mailers.mailgun

import bosca.communications.mailers.Email

/** Mailgun recipient or sender address. */
data class MailgunEmail(
    override val name: String,
    override val email: String,
    override val customArguments: Map<String, String> = emptyMap(),
) : Email {

    init {
        require('\r' !in name && '\n' !in name) { "Mailgun display names cannot contain line breaks" }
        require('\r' !in email && '\n' !in email) { "Mailgun email addresses cannot contain line breaks" }
    }

    internal fun formatted(): String {
        if (name.isBlank()) return email
        val escapedName = name.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escapedName\" <$email>"
    }
}
