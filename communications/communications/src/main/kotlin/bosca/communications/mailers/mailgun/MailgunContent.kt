package bosca.communications.mailers.mailgun

import bosca.communications.mailers.Content
import bosca.communications.mailers.ContentType

/** A text or HTML body submitted to Mailgun. */
data class MailgunContent(
    val type: ContentType,
    override val content: String,
) : Content
