package bosca.communications.mailers.sendgrid

import bosca.communications.mailers.Content
import bosca.communications.mailers.ContentType
import bosca.communications.mailers.Email
import bosca.communications.mailers.InlineImage
import bosca.communications.mailers.Mailer
import bosca.communications.mailers.EmailMessage
import bosca.communications.util.executeAsync
import bosca.configuration.service.ConfigurationService
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

class SendGridMailer internal constructor(
    private val json: Json,
    private val configurationService: ConfigurationService,
    private val client: OkHttpClient,
    private val endpoint: String,
) : Mailer {

    override val maxRecipientsPerMessage: Int = SendGridMessage.MAX_PERSONALIZATIONS

    constructor(json: Json, configurationService: ConfigurationService) : this(
        json,
        configurationService,
        OkHttpClient.Builder().build(),
        SENDGRID_ENDPOINT,
    )

    override suspend fun newEmail(
        name: String,
        email: String,
        customArguments: Map<String, String>,
    ): Email = SendGridEmail(name, email, customArguments)

    override suspend fun newContent(type: ContentType, content: String): Content = SendGridContent(
        when (type) {
            ContentType.TEXT -> "text/plain"
            ContentType.HTML -> "text/html"
        }, content
    )

    override suspend fun newMessage(
        from: Email,
        to: List<Email>,
        subject: String,
        content: List<Content>,
        inlineImages: List<InlineImage>,
        customArguments: Map<String, String>,
    ): EmailMessage = SendGridMessage(
        from as SendGridEmail,
        to.map { it as SendGridEmail },
        subject,
        content.map { it as SendGridContent },
        inlineImages,
        customArguments,
    )

    override suspend fun send(message: EmailMessage) {
        val message = message as SendGridMessage
        val messageRequest = message.toRequest()
        val apiKey = configurationService.getSendGridConfiguration(json)?.apiKey
            ?.takeIf { it.isNotBlank() }
            ?: error("SendGrid API key is not configured")
        val request = Request.Builder().apply {
            url(endpoint)
            addHeader("Authorization", "Bearer $apiKey")
            addHeader("Content-Type", "application/json")
            addHeader("Accept", "application/json")
            post(streamingBody(messageRequest))
        }
        client.newCall(request.build()).executeAsync().use { response ->
            if (!response.isSuccessful) {
                throw Exception("SendGrid Error: ${response.body.string()}")
            }
        }
    }

    /**
     * Serializes the request straight into the socket sink — no whole-body String. Bulk
     * templated sends share one cached base64 instance per inline image; materializing the
     * body would have re-copied it per send. Repeatable (a pure function of [messageRequest]),
     * so OkHttp retries can re-invoke [RequestBody.writeTo].
     */
    @OptIn(ExperimentalSerializationApi::class)
    internal fun streamingBody(messageRequest: SendGridRequest): RequestBody = object : RequestBody() {
        override fun contentType(): MediaType = JSON_MEDIA_TYPE

        override fun writeTo(sink: BufferedSink) {
            json.encodeToStream(SendGridRequest.serializer(), messageRequest, sink.outputStream())
        }
    }

    private companion object {
        const val SENDGRID_ENDPOINT = "https://api.sendgrid.com/v3/mail/send"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
