package bosca.communications.mailers.sendgrid

import bosca.communications.mailers.EmailMessage
import bosca.communications.mailers.InlineImage

class SendGridMessage(
    override val from: SendGridEmail,
    override val to: List<SendGridEmail>,
    override val subject: String,
    override val content: List<SendGridContent>,
    val inlineImages: List<InlineImage> = emptyList(),
    val customArguments: Map<String, String> = emptyMap(),
) : EmailMessage {

    fun toRequest(): SendGridRequest {
        require(to.size in 1..MAX_PERSONALIZATIONS) {
            "SendGrid accepts between 1 and $MAX_PERSONALIZATIONS personalizations per request"
        }
        return SendGridRequest(
            from = from,
            subject = subject,
            personalization = to.map { recipient ->
                Personalization(
                    subject = subject,
                    to = listOf(recipient),
                    customArguments = (customArguments + recipient.customArguments).ifEmpty { null },
                )
            },
            content = content.sortedByDescending { it.type },
            attachments = inlineImages.map {
                SendGridAttachment(
                    content = it.contentBase64,
                    type = it.mediaType,
                    filename = it.filename,
                    contentId = it.cid,
                )
            }.ifEmpty { null },
        )
    }

    companion object {
        const val MAX_PERSONALIZATIONS = 1000
    }
}
