package bosca.bml.message

import bosca.bml.email.RenderedEmail
import bosca.bml.render.EmailRenderer
import bosca.bml.render.PlainTextRenderer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BmlMessageTest {

    @Serializable
    private data class Payload(val courseName: String, val classCount: Int)

    @Test
    fun `payload decodes with an explicit serializer and ignores unknown keys`() {
        val ctx = BmlMessageContext(
            payload = Json.parseToJsonElement("""{"courseName":"Essential Theology","classCount":12,"extra":true}"""),
        )
        val payload = ctx.payload(Payload.serializer())
        assertEquals("Essential Theology", payload.courseName)
        assertEquals(12, payload.classCount)
    }

    @Test
    fun `context defaults are all optional`() {
        val ctx = BmlMessageContext()
        assertNull(ctx.recipientName)
        assertNull(ctx.gql)
        assertNull(ctx.pushOptions)
        assertEquals("en", ctx.locale)
    }

    @Test
    fun `context carries producer supplied push options`() {
        val context = BmlMessageContext(
            recipientName = "Ada",
            pushOptions = BmlPushOptions(threadId = "chat-1"),
        )

        assertEquals("Ada", context.recipientName)
        assertEquals("chat-1", context.pushOptions?.threadId)
    }

    @Test
    fun `a hand-written template renders channels through the message module`() {
        val template = object : BmlMessageTemplate {
            override val key: String = "welcome"
            override val supportsEmail: Boolean = true
            override suspend fun renderMessage(message: BmlMessageContext): RenderedMessage {
                val body = "<html><body><h1>Hi, ${message.recipientName}</h1><script>x()</script></body></html>"
                return RenderedMessage(
                    email = RenderedEmail(
                        subject = "Hi, ${message.recipientName}",
                        html = EmailRenderer.render(body),
                        text = PlainTextRenderer.render(body),
                    ),
                )
            }
        }
        val module = object : BmlMessageModule {
            override val templates: List<BmlMessageTemplate> = listOf(template)
        }
        val rendered = runBlocking {
            module.templates.single().renderMessage(BmlMessageContext(recipientName = "Ada"))
        }
        val email = requireNotNull(rendered.email)
        assertEquals("Hi, Ada", email.subject)
        assertTrue(email.html.contains("<h1>Hi, Ada</h1>"), email.html)
        assertFalse(email.html.contains("<script>"), "email html must strip scripts")
        assertTrue(email.text.contains("Hi, Ada"), email.text)
    }

    @Test
    fun `rich presentation selects producer supplied image attachments and conversation`() {
        val options = BmlPushOptions(
            richContent = BmlPushRichContent(
                attachments = listOf(BmlPushAttachment(url = "https://cdn.example/image.jpg")),
                conversation = BmlPushConversation(
                    id = "planning",
                    messageId = "42",
                    senderName = "Ada",
                    body = "Project update",
                ),
            ),
        ).selectRichContent(image = true, attachments = true, conversation = true)

        assertEquals("https://cdn.example/image.jpg", options.imageUrl)
        assertEquals("https://cdn.example/image.jpg", options.richContent?.attachments?.single()?.url)
        assertEquals("42", options.richContent?.conversation?.messageId)
    }

    @Test
    fun `rich presentation omits producer concepts not declared by the template`() {
        val options = BmlPushOptions(
            imageUrl = "https://cdn.example/hero.jpg",
            richContent = BmlPushRichContent(
                attachments = listOf(BmlPushAttachment(url = "https://cdn.example/image.jpg")),
                conversation = BmlPushConversation(
                    id = "planning",
                    messageId = "42",
                    senderName = "Ada",
                    body = "Project update",
                ),
            ),
        ).selectRichContent(image = false, attachments = false, conversation = false)

        assertNull(options.imageUrl)
        assertNull(options.richContent)
    }

    @Test
    fun `action presentation selects producer routes and overlays localized labels`() {
        val options = BmlPushOptions(
            defaultAction = BmlPushAction("open", url = "https://example.com/chat"),
            actions = listOf(
                BmlPushAction("accept", data = mapOf("invitation_id" to "7")),
                BmlPushAction("decline", destructive = true, data = mapOf("invitation_id" to "7")),
            ),
        ).selectActions(
            listOf(
                BmlPushActionSelection("open", "Abrir chat", isDefault = true),
                BmlPushActionSelection("accept", "Aceptar"),
                BmlPushActionSelection("decline", "Rechazar"),
            ),
        )

        assertEquals("Abrir chat", options.defaultAction?.label)
        assertEquals("https://example.com/chat", options.defaultAction?.url)
        assertEquals(listOf("accept", "decline"), options.actions.map { it.id })
        assertEquals("7", options.actions.first().data?.get("invitation_id"))
        assertTrue(options.actions.last().destructive)
    }

    @Test
    fun `action presentation fails when producer did not supply a selected action`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            BmlPushOptions().selectActions(listOf(BmlPushActionSelection("open", "Open", true)))
        }

        assertEquals("BML push action 'open' was not supplied by the message producer", failure.message)
    }
}
