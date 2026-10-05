package bosca.communications.mailers

interface Mailer {

    /** Maximum number of recipients the provider accepts in one message. */
    val maxRecipientsPerMessage: Int
        get() = Int.MAX_VALUE

    /**
     * Creates an email address, optionally carrying non-PII provider callback arguments.
     */
    suspend fun newEmail(
        name: String,
        email: String,
        customArguments: Map<String, String> = emptyMap(),
    ): Email

    suspend fun newContent(type: ContentType, content: String): Content

    /**
     * Creates a provider message.
     *
     * [customArguments] apply to every recipient personalization. Recipient-specific
     * values are supplied through [Email.customArguments].
     */
    suspend fun newMessage(
        from: Email,
        to: List<Email>,
        subject: String,
        content: List<Content>,
        inlineImages: List<InlineImage> = emptyList(),
        customArguments: Map<String, String> = emptyMap(),
    ): EmailMessage

    suspend fun send(message: EmailMessage)
}

/**
 * Stable custom-argument keys used to correlate provider callbacks with Bosca aggregates.
 */
object DeliveryTrackingArguments {
    const val MESSAGE_ID = "bosca_message_id"
    const val RECIPIENT_ID = "bosca_recipient_id"
}

/**
 * An inline image attachment: the HTML body references `cid:[cid]` and the provider delivers
 * the bytes as an inline `multipart/related` part under that Content-ID.
 */
data class InlineImage(
    val cid: String,
    val mediaType: String,
    val filename: String,
    val contentBase64: String,
)
