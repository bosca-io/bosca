package bosca.communications.mailers.mailgun

import bosca.communications.mailers.ContentType
import bosca.communications.mailers.InlineImage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MultipartBody
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MailgunMessageTest {

    private val json = Json

    @Test
    fun `builds a personalized multipart request with inline images`() {
        val message = MailgunMessage(
            from = MailgunEmail("Bosca \"Mail\"", "noreply@example.com"),
            to = listOf(
                MailgunEmail("Ada", "ada@example.com", mapOf("bosca_recipient_id" to "recipient-1")),
                MailgunEmail("Grace", "grace@example.com", mapOf("bosca_recipient_id" to "recipient-2")),
            ),
            subject = "Hello",
            content = listOf(
                MailgunContent(ContentType.HTML, "<p>Hello <img src=\"cid:logo-png\"></p>"),
                MailgunContent(ContentType.TEXT, "Hello"),
            ),
            inlineImages = listOf(InlineImage("logo-png", "image/png", "logo.png", "aW1n")),
            customArguments = mapOf("bosca_message_id" to "message-1"),
        )

        val body = message.toRequestBody(json)

        assertEquals("multipart/form-data", body.type.toString())
        assertEquals(listOf("\"Bosca \\\"Mail\\\"\" <noreply@example.com>"), body.values("from"))
        assertEquals(
            listOf("\"Ada\" <ada@example.com>", "\"Grace\" <grace@example.com>"),
            body.values("to"),
        )
        assertEquals(listOf("Hello"), body.values("text"))
        assertEquals(listOf("<p>Hello <img src=\"cid:logo-png\"></p>"), body.values("html"))
        assertEquals(listOf("no"), body.values("o:tracking"))
        assertEquals(listOf("%recipient.bosca_message_id%"), body.values("v:bosca_message_id"))
        assertEquals(listOf("%recipient.bosca_recipient_id%"), body.values("v:bosca_recipient_id"))

        val recipientVariables = json.parseToJsonElement(body.values("recipient-variables").single()).jsonObject
        assertEquals(
            "message-1",
            recipientVariables.getValue("ada@example.com").jsonObject
                .getValue("bosca_message_id").jsonPrimitive.content,
        )
        assertEquals(
            "recipient-2",
            recipientVariables.getValue("grace@example.com").jsonObject
                .getValue("bosca_recipient_id").jsonPrimitive.content,
        )

        val inline = body.parts.single { it.name() == "inline" }
        assertTrue("filename=\"logo-png\"" in inline.headers.toString())
        assertEquals("img", Buffer().also { inline.body.writeTo(it) }.readUtf8())
        assertEquals("image/png", inline.body.contentType().toString())
    }

    @Test
    fun `single untracked recipient omits recipient variables`() {
        val body = message().toRequestBody(json)

        assertTrue(body.values("recipient-variables").isEmpty())
        assertTrue(body.parts.none { it.name().startsWith("v:") })
    }

    @Test
    fun `validates provider request constraints`() {
        assertEquals(
            "Mailgun accepts between 1 and 1000 recipients per request",
            assertFailsWith<IllegalArgumentException> { message(to = emptyList()) }.message,
        )
        assertFailsWith<IllegalArgumentException> {
            message(to = (1..1001).map { MailgunEmail("User $it", "user$it@example.com") })
        }
        assertEquals(
            "Mailgun requires a text or HTML message body",
            assertFailsWith<IllegalArgumentException> { message(content = emptyList()) }.message,
        )
        assertEquals(
            "Mailgun accepts at most one body for each content type",
            assertFailsWith<IllegalArgumentException> {
                message(
                    content = listOf(
                        MailgunContent(ContentType.TEXT, "one"),
                        MailgunContent(ContentType.TEXT, "two"),
                    )
                )
            }.message,
        )
        assertEquals(
            "Mailgun batch recipients must have distinct email addresses",
            assertFailsWith<IllegalArgumentException> {
                message(
                    to = listOf(
                        MailgunEmail("Ada", "ADA@example.com"),
                        MailgunEmail("Ada", "ada@example.com"),
                    )
                )
            }.message,
        )
        assertEquals(
            "Mailgun inline image content IDs cannot be blank",
            assertFailsWith<IllegalArgumentException> {
                message(inlineImages = listOf(InlineImage("", "image/png", "logo.png", "aW1n")))
                    .toRequestBody(json)
            }.message,
        )
    }

    @Test
    fun `addresses reject line breaks and omit a blank display name`() {
        assertEquals("ada@example.com", MailgunEmail("", "ada@example.com").formatted())
        assertFailsWith<IllegalArgumentException> { MailgunEmail("Ada\nBcc", "ada@example.com") }
        assertFailsWith<IllegalArgumentException> { MailgunEmail("Ada", "ada@example.com\rBcc") }
    }

    private fun message(
        to: List<MailgunEmail> = listOf(MailgunEmail("Ada", "ada@example.com")),
        content: List<MailgunContent> = listOf(MailgunContent(ContentType.TEXT, "Hello")),
        inlineImages: List<InlineImage> = emptyList(),
    ) = MailgunMessage(
        from = MailgunEmail("Bosca", "noreply@example.com"),
        to = to,
        subject = "Hello",
        content = content,
        inlineImages = inlineImages,
    )

    private fun MultipartBody.values(name: String): List<String> = parts
        .filter { it.name() == name }
        .map { Buffer().also { buffer -> it.body.writeTo(buffer) }.readUtf8() }

    private fun MultipartBody.Part.name(): String = headers?.get("Content-Disposition")
        ?.substringAfter("name=\"")
        ?.substringBefore('"')
        .orEmpty()
}
