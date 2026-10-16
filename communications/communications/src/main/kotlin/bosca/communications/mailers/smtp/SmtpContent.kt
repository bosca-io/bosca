package bosca.communications.mailers.smtp

import bosca.communications.mailers.Content
import bosca.communications.mailers.ContentType

/** Text or HTML content in an SMTP message. */
data class SmtpContent(val type: ContentType, override val content: String) : Content
