package bosca.communications.mailers.mailgun

import bosca.communications.mailers.ContentType
import bosca.communications.mailers.EmailMessage
import bosca.communications.mailers.InlineImage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.Locale

/** One Mailgun Messages API request. */
class MailgunMessage(
    override val from: MailgunEmail,
    override val to: List<MailgunEmail>,
    override val subject: String,
    override val content: List<MailgunContent>,
    val inlineImages: List<InlineImage> = emptyList(),
    val customArguments: Map<String, String> = emptyMap(),
) : EmailMessage {

    init {
        require(to.size in 1..MAX_RECIPIENTS) {
            "Mailgun accepts between 1 and $MAX_RECIPIENTS recipients per request"
        }
        require(content.isNotEmpty()) { "Mailgun requires a text or HTML message body" }
        require(content.map { it.type }.distinct().size == content.size) {
            "Mailgun accepts at most one body for each content type"
        }
        require(to.map { it.email.lowercase(Locale.ROOT) }.distinct().size == to.size) {
            "Mailgun batch recipients must have distinct email addresses"
        }
    }

    internal fun toRequestBody(json: Json): MultipartBody {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("from", from.formatted())
            .addFormDataPart("subject", subject)
            // Bosca's BML renderer owns click/open tracking. Override Mailgun domain defaults.
            .addFormDataPart("o:tracking", "no")

        to.forEach { builder.addFormDataPart("to", it.formatted()) }
        content.sortedBy { it.type }.forEach {
            builder.addFormDataPart(
                when (it.type) {
                    ContentType.TEXT -> "text"
                    ContentType.HTML -> "html"
                },
                it.content,
            )
        }

        val recipientArguments = to.associate { recipient ->
            recipient.email to (customArguments + recipient.customArguments)
        }
        if (to.size > 1 || recipientArguments.values.any { it.isNotEmpty() }) {
            val variables = JsonObject(recipientArguments.mapValues { (_, arguments) ->
                JsonObject(arguments.mapValues { JsonPrimitive(it.value) })
            })
            builder.addFormDataPart(
                "recipient-variables",
                json.encodeToString(JsonObject.serializer(), variables),
            )
            recipientArguments.values.flatMap { it.keys }.distinct().sorted().forEach { key ->
                // Substitution copies the recipient-specific value into webhook user-variables.
                builder.addFormDataPart("v:$key", "%recipient.$key%")
            }
        }

        inlineImages.forEach { image ->
            require(image.cid.isNotBlank()) { "Mailgun inline image content IDs cannot be blank" }
            builder.addFormDataPart(
                "inline",
                // Mailgun derives Content-ID from the multipart filename, so use the requested cid.
                image.cid,
                Base64.getDecoder().decode(image.contentBase64)
                    .toRequestBody(image.mediaType.toMediaType()),
            )
        }
        return builder.build()
    }

    companion object {
        /** Mailgun's documented batch-recipient limit. */
        const val MAX_RECIPIENTS = 1000
    }
}
