package bosca.communications.mailers.mailgun

import bosca.communications.mailers.Content
import bosca.communications.mailers.ContentType
import bosca.communications.mailers.Email
import bosca.communications.mailers.EmailMessage
import bosca.communications.mailers.InlineImage
import bosca.communications.mailers.Mailer
import bosca.communications.util.executeAsync
import bosca.configuration.service.ConfigurationService
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Mailgun implementation of [Mailer] using the Messages HTTP API. */
class MailgunMailer internal constructor(
    private val json: Json,
    private val configurationService: ConfigurationService,
    private val client: OkHttpClient,
    private val apiBaseUrlOverride: HttpUrl? = null,
) : Mailer {

    override val maxRecipientsPerMessage: Int = MailgunMessage.MAX_RECIPIENTS

    constructor(json: Json, configurationService: ConfigurationService) : this(
        json,
        configurationService,
        OkHttpClient.Builder().build(),
    )

    override suspend fun newEmail(
        name: String,
        email: String,
        customArguments: Map<String, String>,
    ): Email = MailgunEmail(name, email, customArguments)

    override suspend fun newContent(type: ContentType, content: String): Content =
        MailgunContent(type, content)

    override suspend fun newMessage(
        from: Email,
        to: List<Email>,
        subject: String,
        content: List<Content>,
        inlineImages: List<InlineImage>,
        customArguments: Map<String, String>,
    ): EmailMessage = MailgunMessage(
        from = from as MailgunEmail,
        to = to.map { it as MailgunEmail },
        subject = subject,
        content = content.map { it as MailgunContent },
        inlineImages = inlineImages,
        customArguments = customArguments,
    )

    override suspend fun send(message: EmailMessage) {
        val configuration = configurationService.getMailgunConfiguration(json)
            ?: error("Mailgun configuration is not configured")
        val apiKey = configuration.apiKey.takeIf { it.isNotBlank() }
            ?: error("Mailgun API key is not configured")
        val domain = configuration.domain.takeIf { it.isNotBlank() }
            ?: error("Mailgun domain is not configured")
        val apiBaseUrl = apiBaseUrlOverride ?: configuration.apiBaseUrl
            .takeIf { it.isNotBlank() }
            ?.toHttpUrlOrNull()
        ?: error("Mailgun API base URL is not configured or invalid")
        val endpoint = apiBaseUrl.newBuilder()
            .addPathSegment("v3")
            .addPathSegment(domain)
            .addPathSegment("messages")
            .build()
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", Credentials.basic("api", apiKey))
            .header("Accept", "application/json")
            .post((message as MailgunMessage).toRequestBody(json))
            .build()

        client.newCall(request).executeAsync().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Mailgun Error: ${response.body.string()}")
            }
        }
    }
}
