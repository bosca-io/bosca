@file:OptIn(Internal::class)

package bosca.pipelines.builtin

import bosca.communications.model.EmailPreview
import bosca.communications.model.Message
import bosca.communications.model.MessageChannel
import bosca.communications.model.MessageBmlTemplate
import bosca.communications.service.BmlMessageTemplateRendererService
import bosca.communications.service.MessageService
import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.testutil.executeForTest
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The BML message-email nodes: `Render Message Template` (generates the rendered email as the
 * node output) and `Send Email Template` (data-driven delivery — recipients arrive on input
 * ports, single and/or array, and the send goes to the union). The inbound payload port is the
 * template payload in both.
 */
class EmailTemplateNodesTest {

    private val context get() = PipelineContext(AuthenticationContext(null, null), Json)

    private val payloadValue = PipelineValue.ofJson(Json.parseToJsonElement("""{"courseName":"Exploring Truth"}"""))

    private fun recipientValue(id: UUID): PipelineValue {
        @Suppress("UNCHECKED_CAST")
        return PipelineValue(id, UUIDSerializer() as kotlinx.serialization.KSerializer<Any?>)
    }

    private fun recipientsValue(vararg ids: UUID): PipelineValue =
        PipelineValue.ofJson(Json.parseToJsonElement(ids.joinToString(",", "[", "]") { "\"$it\"" }))

    @OptIn(InternalDI::class)
    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    // ── Render Email Template ────────────────────────────────────────────────

    @Test
    fun `render outputs the rendered email with the payload passed through`() = runTest {
        val renderer = mockk<BmlMessageTemplateRendererService>()
        provides<BmlMessageTemplateRendererService> { renderer }
        val template = slot<MessageBmlTemplate>()
        coEvery { renderer.preview(capture(template), any(), any(), any()) } returns
            EmailPreview("acme", "welcome", "1.0", "Hi Ada", "<p>hi</p>", "hi")

        val node = RenderEmailTemplateNode(
            id = "render", template = "acme/welcome",
            version = "1.0", recipientName = "Ada",
        )
        val result = node.executeForTest(context, NodeInputs(mapOf("payload" to payloadValue)))

        val rendered = result?.value as EmailPreview
        assertEquals("Hi Ada", rendered.subject)
        assertEquals("<p>hi</p>", rendered.html)
        assertEquals("hi", rendered.text)
        assertEquals("1.0", rendered.version)

        assertEquals("acme", template.captured.project)
        assertEquals("welcome", template.captured.templateKey)
        assertEquals(
            "Exploring Truth",
            template.captured.payload?.jsonObject?.get("courseName")?.jsonPrimitive?.contentOrNull,
        )
        coVerify { renderer.preview(any(), version = "1.0", recipientName = "Ada", recipientEmail = null) }
    }

    @Test
    fun `render resolves separate project and template settings`() = runTest {
        val renderer = mockk<BmlMessageTemplateRendererService>()
        provides<BmlMessageTemplateRendererService> { renderer }
        val template = slot<MessageBmlTemplate>()
        coEvery { renderer.preview(capture(template), any(), any(), any()) } returns
            EmailPreview("acme", "welcome", "1.0", "Hi", "<p>hi</p>", "hi")

        val node = RenderEmailTemplateNode(id = "render", project = "acme", template = "welcome")
        node.executeForTest(context, NodeInputs(mapOf("payload" to payloadValue)))

        assertEquals("acme", template.captured.project)
        assertEquals("welcome", template.captured.templateKey)
    }

    @Test
    fun `render supports an email-only recipient and an absent payload`() = runTest {
        val renderer = mockk<BmlMessageTemplateRendererService>()
        provides<BmlMessageTemplateRendererService> { renderer }
        val template = slot<MessageBmlTemplate>()
        coEvery { renderer.preview(capture(template), any(), any(), any()) } returns
            EmailPreview("acme", "welcome", "1.0", "Hi", "<p>hi</p>", "hi")
        val node = RenderEmailTemplateNode(
            id = "render",
            name = "Welcome preview",
            project = "acme",
            template = "welcome",
            recipientEmail = "ada@example.com",
        )

        node.executeForTest(context, NodeInputs(emptyMap()))

        assertNull(template.captured.payload)
        coVerify {
            renderer.preview(
                any(),
                version = null,
                recipientName = null,
                recipientEmail = "ada@example.com",
            )
        }
    }

    @Test
    fun `a legacy slash reference wins over a chosen project`() = runTest {
        // A node saved before the project/template split, later edited: the full reference stays authoritative.
        val service = mockk<MessageService>(relaxed = true)
        provides<MessageService> { service }
        val node = SendEmailTemplateNode(id = "send", project = "other", template = "acme/welcome")

        node.executeForTest(context, NodeInputs(mapOf("recipient" to recipientValue(UUID.random()))))

        val message = slot<Message>()
        coVerify { service.send(capture(message)) }
        assertEquals("acme", message.captured.bmlTemplate?.project)
        assertEquals("welcome", message.captured.bmlTemplate?.templateKey)
    }

    @Test
    fun `render rejects a malformed template reference with a message naming the format`() = runTest {
        provides<BmlMessageTemplateRendererService> { mockk() }
        val node = RenderEmailTemplateNode(id = "render", template = "welcome")
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, NodeInputs(emptyMap()))
        }
        assertTrue("project/template-key" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `template references reject either missing slash component`() = runTest {
        provides<BmlMessageTemplateRendererService> { mockk() }

        listOf("/welcome", "acme/").forEach { reference ->
            val failure = assertFailsWith<IllegalStateException> {
                RenderEmailTemplateNode(id = "render", template = reference)
                    .executeForTest(context, NodeInputs(emptyMap()))
            }
            assertTrue("project/template-key" in failure.message.orEmpty(), failure.message.orEmpty())
        }
    }

    @Test
    fun `render dry run records the would-be render and resolves no service`() = runTest {
        // No renderer registered; touching provide<...>() would throw.
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace)
        val node = RenderEmailTemplateNode(id = "render", template = "acme/welcome")

        val result = node.executeForTest(dry, NodeInputs(mapOf("payload" to payloadValue)))

        assertNull(result, "a dry run produces no output — downstream must not act on a fake render")
        val recorded = trace.actions["render"] as JsonObject
        assertEquals("renderEmailTemplate", recorded["action"]?.jsonPrimitive?.contentOrNull)
        assertEquals("acme/welcome", recorded["template"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "Exploring Truth",
            recorded["payload"]?.jsonObject?.get("courseName")?.jsonPrimitive?.contentOrNull,
        )
    }

    // ── Send Email Template ──────────────────────────────────────────────────

    @Test
    fun `send unions the single recipient port with the recipients array`() = runTest {
        val service = mockk<MessageService>(relaxed = true)
        provides<MessageService> { service }
        val single = UUID.random()
        val a = UUID.random()
        val b = UUID.random()
        val node = SendEmailTemplateNode(id = "send", template = "acme/welcome", notificationType = "transactional")

        val result = node.executeForTest(
            context,
            NodeInputs(
                mapOf(
                    "recipient" to recipientValue(single),
                    "recipients" to recipientsValue(a, b),
                    "payload" to payloadValue,
                ),
            ),
        )

        assertNull(result, "a side-effect node returns no output")
        val message = slot<Message>()
        coVerify { service.send(capture(message)) }
        assertEquals(listOf(single, a, b), message.captured.recipients)
        assertEquals(listOf(MessageChannel.EMAIL), message.captured.channels)
        assertEquals("transactional", message.captured.type)
        val template = message.captured.bmlTemplate
        assertEquals("acme", template?.project)
        assertEquals("welcome", template?.templateKey)
        assertEquals(
            "Exploring Truth",
            template?.payload?.jsonObject?.get("courseName")?.jsonPrimitive?.contentOrNull,
        )
    }

    @Test
    fun `either port alone suffices and duplicates collapse`() = runTest {
        val service = mockk<MessageService>(relaxed = true)
        provides<MessageService> { service }
        val id = UUID.random()
        val node = SendEmailTemplateNode(id = "send", template = "acme/welcome")

        node.executeForTest(
            context,
            NodeInputs(mapOf("recipient" to recipientValue(id), "recipients" to recipientsValue(id))),
        )

        val message = slot<Message>()
        coVerify { service.send(capture(message)) }
        assertEquals(listOf(id), message.captured.recipients, "the union must de-duplicate")
        assertNull(message.captured.type, "a blank type sends as null")
    }

    @Test
    fun `no recipients on either port fails with a message naming both`() = runTest {
        provides<MessageService> { mockk<MessageService>(relaxed = true) }
        val node = SendEmailTemplateNode(id = "send", template = "acme/welcome")
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, NodeInputs(mapOf("payload" to payloadValue)))
        }
        assertTrue("'recipient'" in failure.message.orEmpty(), failure.message.orEmpty())
        assertTrue("'recipients'" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `a non-UUID recipients element fails typed`() = runTest {
        provides<MessageService> { mockk<MessageService>(relaxed = true) }
        val node = SendEmailTemplateNode(id = "send", template = "acme/welcome")
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTest(
                context,
                NodeInputs(mapOf("recipients" to PipelineValue.ofJson(Json.parseToJsonElement("""["nope"]""")))),
            )
        }
        assertTrue("not a UUID" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `a null recipients element fails typed`() = runTest {
        provides<MessageService> { mockk<MessageService>(relaxed = true) }
        val failure = assertFailsWith<IllegalStateException> {
            SendEmailTemplateNode(id = "send", template = "acme/welcome").executeForTest(
                context,
                NodeInputs(mapOf("recipients" to PipelineValue.ofJson(Json.parseToJsonElement("[null]")))),
            )
        }
        assertTrue("not a UUID" in failure.message.orEmpty())
    }

    @Test
    fun `send execution names a configured node and requires both template settings`() = runTest {
        provides<MessageService> { mockk<MessageService>(relaxed = true) }
        val recipient = recipientValue(UUID.random())

        val missingTemplate = assertFailsWith<IllegalStateException> {
            SendEmailTemplateNode(id = "send", name = "Welcome mail", project = "acme").executeForTest(
                context,
                NodeInputs(mapOf("recipient" to recipient)),
            )
        }
        assertTrue("Welcome mail" in missingTemplate.message.orEmpty())
    }

    @Test
    fun `send dry run records the would-be send and resolves no service`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace)
        val a = UUID.random()
        val node = SendEmailTemplateNode(id = "send", template = "acme/welcome")

        val result = node.executeForTest(dry, NodeInputs(mapOf("recipients" to recipientsValue(a))))

        assertNull(result)
        val recorded = trace.actions["send"] as JsonObject
        assertEquals("sendEmailTemplate", recorded["action"]?.jsonPrimitive?.contentOrNull)
        assertEquals("acme/welcome", recorded["template"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1, recorded["recipientCount"]?.jsonPrimitive?.contentOrNull?.toInt())
    }

    @Test
    fun `template dry runs cover optional settings single recipients and absent traces`() = runTest {
        val noTrace = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true)
        assertNull(SendEmailTemplateNode(id = "send-empty").executeForTest(noTrace, NodeInputs(emptyMap())))
        assertNull(RenderEmailTemplateNode(id = "render-empty").executeForTest(noTrace, NodeInputs(emptyMap())))

        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace)
        val recipient = UUID.random()
        val send = SendEmailTemplateNode(
            id = "send-rich", project = "acme", template = "welcome",
        )
        send.executeForTest(
            dry,
            NodeInputs(mapOf("recipient" to recipientValue(recipient), "payload" to payloadValue)),
        )
        val sent = trace.actions.getValue("send-rich").jsonObject
        assertEquals(recipient.toString(), sent["recipient"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1, sent["recipientCount"]?.jsonPrimitive?.contentOrNull?.toInt())
        assertEquals("acme", sent["project"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Exploring Truth", sent["payload"]?.jsonObject?.get("courseName")?.jsonPrimitive?.contentOrNull)

        val render = RenderEmailTemplateNode(
            id = "render-rich", project = "acme", template = "welcome", version = "2.0",
        )
        render.executeForTest(dry, NodeInputs(emptyMap()))
        val rendered = trace.actions.getValue("render-rich").jsonObject
        assertEquals("acme", rendered["project"]?.jsonPrimitive?.contentOrNull)
        assertEquals("2.0", rendered["version"]?.jsonPrimitive?.contentOrNull)
        assertEquals(JsonNull, rendered["payload"])
    }

    // ---- declared payload contract (HasDeclaredInputs) ----

    @Test
    fun `a picked template pins the payload port to its contract`() {
        assertEquals(
            mapOf("payload" to "email:acme/welcome"),
            SendEmailTemplateNode(id = "n", project = "acme", template = "welcome").declaredInputTypes,
        )
        assertEquals(
            mapOf("payload" to "email:acme/welcome"),
            RenderEmailTemplateNode(id = "n", project = "acme", template = "welcome").declaredInputTypes,
        )
    }

    @Test
    fun `the legacy single-setting template form pins the payload port the same way`() {
        assertEquals(
            mapOf("payload" to "email:acme/welcome"),
            SendEmailTemplateNode(id = "n", template = "acme/welcome").declaredInputTypes,
        )
    }

    @Test
    fun `no or partial template means no payload requirement`() {
        assertEquals(emptyMap(), SendEmailTemplateNode(id = "n").declaredInputTypes)
        assertEquals(emptyMap(), SendEmailTemplateNode(id = "n", project = "acme").declaredInputTypes)
        assertEquals(emptyMap(), RenderEmailTemplateNode(id = "n", template = "welcome").declaredInputTypes)
        // Malformed legacy references (a missing half) must not pin a bogus contract.
        assertEquals(emptyMap(), SendEmailTemplateNode(id = "n", template = "/welcome").declaredInputTypes)
        assertEquals(emptyMap(), SendEmailTemplateNode(id = "n", template = "acme/").declaredInputTypes)
    }
}
