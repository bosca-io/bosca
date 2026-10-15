package bosca.communications.mailers.smtp

import bosca.communications.mailers.Content
import bosca.communications.mailers.ContentType
import bosca.communications.mailers.Email
import bosca.communications.mailers.EmailMessage
import bosca.communications.mailers.InlineImage
import bosca.communications.mailers.Mailer
import bosca.configuration.service.ConfigurationService
import jakarta.mail.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json

/** SMTP delivery with current credentials and an isolated message for each recipient. */
class SmtpMailer(private val json: Json, private val configurationService: ConfigurationService) : Mailer {
    override val maxRecipientsPerMessage: Int = 1000

    override suspend fun newEmail(name: String, email: String, customArguments: Map<String, String>): Email =
        SmtpEmail(name, email, customArguments)

    override suspend fun newContent(type: ContentType, content: String): Content = SmtpContent(type, content)

    override suspend fun newMessage(
        from: Email,
        to: List<Email>,
        subject: String,
        content: List<Content>,
        inlineImages: List<InlineImage>,
        customArguments: Map<String, String>,
    ): EmailMessage = SmtpMessage(
        from as SmtpEmail, to.map { it as SmtpEmail }, subject, content.map { it as SmtpContent }, inlineImages,
    )

    override suspend fun send(message: EmailMessage) {
        val value = configurationService.getByKey(SmtpConfiguration.KEY)?.let { configurationService.getValue(it.id) }
            ?: error("SMTP configuration is not configured")
        val configuration = json.decodeFromJsonElement(SmtpConfiguration.serializer(), value)
        val session = Session.getInstance(configuration.properties())
        val smtpMessage = message as SmtpMessage
        require(smtpMessage.to.isNotEmpty() && smtpMessage.to.size <= maxRecipientsPerMessage) {
            "SMTP messages require between 1 and $maxRecipientsPerMessage recipients"
        }
        val sendContext = currentCoroutineContext()
        runInterruptible(Dispatchers.IO) {
            // Validate every address before the first handoff to avoid partial sends on malformed input.
            smtpMessage.from.address()
            smtpMessage.to.forEach { it.address() }
            session.getTransport("smtp").use { transport ->
                transport.connect(configuration.host, configuration.port, configuration.username.ifBlank { null }, configuration.password.ifEmpty { null })
                smtpMessage.to.forEach { recipient ->
                    val mimeMessage = smtpMessage.toMimeMessage(session, recipient)
                    sendContext.ensureActive()
                    transport.sendMessage(mimeMessage, mimeMessage.allRecipients)
                }
            }
        }
    }
}
