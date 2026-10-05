@file:OptIn(Internal::class)

package bosca.pipelines.builtin
import bosca.pipelines.testutil.executeForTest

import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.communications.model.Message
import bosca.communications.model.MessageChannel
import bosca.communications.model.MessageContentType
import bosca.communications.service.MessageService
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendEmailNodeTest {

    private val context get() = PipelineContext(AuthenticationContext(null, null), Json)
    private val inputs get() = NodeInputs(emptyMap())

    @OptIn(InternalDI::class)
    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    // ---------------------------------------------------------------------------------------------
    // Dry-run path: records the trace action and returns null. No MessageService is resolved.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `dry run with a trace records the would-be send and returns null`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace)
        val r1 = UUID.random()
        val r2 = UUID.random()
        val node = SendEmailNode(
            id = "email",
            recipients = listOf(r1, r2),
            subject = "Welcome",
            body = "<b>hi</b>",
            html = true,
        )

        val result = node.executeForTest(dry, inputs)

        assertNull(result, "a side-effect node returns no output")
        val recorded = trace.actions["email"] as JsonObject
        assertEquals("sendEmail", recorded["action"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Welcome", recorded["subject"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, recorded["html"]?.jsonPrimitive?.boolean)
        val recipients = recorded["recipients"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf(r1.toString(), r2.toString()), recipients)
    }

    @Test
    fun `dry run records html false and empty recipients as an empty array`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace)
        val node = SendEmailNode(
            id = "email",
            recipients = emptyList(),
            subject = "",
            body = "plain",
            html = false,
        )

        val result = node.executeForTest(dry, inputs)

        assertNull(result)
        val recorded = trace.actions["email"] as JsonObject
        assertEquals("sendEmail", recorded["action"]?.jsonPrimitive?.contentOrNull)
        assertEquals("", recorded["subject"]?.jsonPrimitive?.contentOrNull)
        assertEquals(false, recorded["html"]?.jsonPrimitive?.boolean)
        assertTrue(recorded["recipients"]!!.jsonArray.isEmpty(), "no recipients -> empty array")
    }

    @Test
    fun `dry run with a null trace records nothing and still returns null`() = runTest {
        // trace == null arm of context.trace?.actions?.set(...) — the safe-call short-circuits.
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = null)
        val node = SendEmailNode(id = "email", subject = "x")

        val result = node.executeForTest(dry, inputs)

        assertNull(result, "dry run without a trace still produces no output and does not throw")
    }

    @Test
    fun `dry run never resolves the MessageService`() = runTest {
        // No MessageService registered; if the node touched provide<MessageService>() it would throw.
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace)
        val node = SendEmailNode(id = "email", recipients = listOf(UUID.random()), subject = "s")

        val result = node.executeForTest(dry, inputs)

        assertNull(result)
        assertTrue("email" in trace.actions)
    }

    // ---------------------------------------------------------------------------------------------
    // Live path: resolves provide<MessageService>() and calls send(...).
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `live html true sends an HTML message with the configured fields`() = runTest {
        val service = mockk<MessageService>(relaxed = true)
        provides<MessageService> { service }
        val r1 = UUID.random()
        val r2 = UUID.random()
        val node = SendEmailNode(
            id = "email",
            recipients = listOf(r1, r2),
            subject = "Hello",
            body = "<p>body</p>",
            html = true,
        )

        val result = node.executeForTest(context, inputs)

        assertNull(result, "a live send returns no output")
        val captured = slot<Message>()
        coVerify(exactly = 1) { service.send(capture(captured)) }
        val msg = captured.captured
        assertEquals(listOf(MessageChannel.EMAIL), msg.channels)
        assertEquals("Hello", msg.subject)
        assertEquals(listOf(r1, r2), msg.recipients)
        assertEquals(1, msg.content.size)
        assertEquals(MessageContentType.HTML, msg.content.single().type)
        assertEquals("<p>body</p>", msg.content.single().content)
    }

    @Test
    fun `live html false sends a TEXT message`() = runTest {
        val service = mockk<MessageService>(relaxed = true)
        provides<MessageService> { service }
        val node = SendEmailNode(
            id = "email",
            recipients = listOf(UUID.random()),
            subject = "Plain",
            body = "just text",
            html = false,
        )

        val result = node.executeForTest(context, inputs)

        assertNull(result)
        coVerify(exactly = 1) {
            service.send(
                match { msg ->
                    msg.channels == listOf(MessageChannel.EMAIL) &&
                        msg.subject == "Plain" &&
                        msg.content.single().type == MessageContentType.TEXT &&
                        msg.content.single().content == "just text"
                },
            )
        }
    }

    @Test
    fun `live with empty recipients still sends with an empty recipient list`() = runTest {
        val service = mockk<MessageService>(relaxed = true)
        provides<MessageService> { service }
        val node = SendEmailNode(
            id = "email",
            recipients = emptyList(),
            subject = "No one",
            body = "x",
            html = true,
        )

        val result = node.executeForTest(context, inputs)

        assertNull(result)
        coVerify(exactly = 1) {
            service.send(
                match { msg ->
                    msg.recipients.isEmpty() &&
                        msg.subject == "No one" &&
                        msg.content.single().type == MessageContentType.HTML
                },
            )
        }
    }

    @Test
    fun `defaults produce a single populated recipient when given one`() = runTest {
        // Exercise the node's default-valued constructor params (name/description/position/body=""
        // /subject=""/html=true) on the live path.
        val service = mockk<MessageService>(relaxed = true)
        provides<MessageService> { service }
        val recipient = UUID.random()
        val node = SendEmailNode(id = "email", recipients = listOf(recipient))

        val result = node.executeForTest(context, inputs)

        assertNull(result)
        coVerify(exactly = 1) {
            service.send(
                match { msg ->
                    msg.recipients == listOf(recipient) &&
                        msg.subject == "" &&
                        msg.content.single().type == MessageContentType.HTML &&
                        msg.content.single().content == ""
                },
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Synthetic default-ctor mask arms: PARTIAL subsets of the optional params.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `constructing with id and subject only leaves name description recipients body html position default`() {
        // subject set, everything else defaulted: flips one mask bit (not all-or-nothing).
        val node = SendEmailNode(id = "email", subject = "Hi")
        assertEquals("Hi", node.subject)
        assertEquals("", node.name)
        assertEquals("", node.description)
        assertTrue(node.recipients.isEmpty())
        assertEquals("", node.body)
        assertEquals(true, node.html, "html defaults to true")
    }

    @Test
    fun `constructing with id and html false leaves the other optionals default`() {
        // A different partial subset: only html flipped, subject/body/recipients/name defaulted.
        val node = SendEmailNode(id = "email", html = false)
        assertEquals(false, node.html)
        assertEquals("", node.subject)
        assertEquals("", node.body)
        assertTrue(node.recipients.isEmpty())
    }

    @Test
    fun `constructing with id name and body leaves recipients subject html and position default`() {
        val node = SendEmailNode(id = "email", name = "Welcome", body = "hello")
        assertEquals("Welcome", node.name)
        assertEquals("hello", node.body)
        assertEquals("", node.subject)
        assertEquals(true, node.html)
        assertTrue(node.recipients.isEmpty())
    }

    @Test
    fun `dry run traces the would-be send for a node with html false and a set name`() = runTest {
        // Drives the trace-set line (47) on a node with non-default name/html, recording html=false.
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace)
        val node = SendEmailNode(id = "email", name = "Plain Note", subject = "S", body = "b", html = false)

        val result = node.executeForTest(dry, inputs)

        assertNull(result)
        val recorded = trace.actions["email"] as JsonObject
        assertEquals("sendEmail", recorded["action"]?.jsonPrimitive?.contentOrNull)
        assertEquals(false, recorded["html"]?.jsonPrimitive?.boolean)
    }

    // --- generated deserializer: the throwMissingFieldException arm (the required id absent) ---

    @Test
    fun `decoding JSON missing the required id throws`() {
        // id is the only no-default field; an empty object omits it, driving the generated
        // deserializer's `(seen & required) != required -> throwMissingFieldException` arm — the arm a
        // valid round-trip never reaches.
        assertFailsWith<SerializationException> {
            Json.decodeFromString(SendEmailNode.serializer(), "{}")
        }
    }
}
