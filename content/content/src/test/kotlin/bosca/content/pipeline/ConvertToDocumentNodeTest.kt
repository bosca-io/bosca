@file:OptIn(InternalDI::class)

package bosca.content.pipeline

import bosca.content.metadata.pipeline.ConvertToDocumentNode
import bosca.content.metadata.service.BibleService
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.documents.Content
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.PipelineValue
import bosca.security.service.AuthenticationContext
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * the convertToDocument node converts an inbound raw HTML **string** into a Bosca Document
 * [Content] value (TipTap/ProseMirror) and emits it as a fresh output for downstream nodes.
 *
 * It is a pure transform — it reads the HTML off its single input, never resolves a Metadata or calls
 * a service, and produces a brand-new value rather than passing its input through. [BibleService] only
 * satisfies the converter's constructor; the HTML path never invokes it.
 */
class ConvertToDocumentNodeTest {

    private val bibleService = mockk<BibleService>()

    private val json = Json
    private val context = PipelineContext(AuthenticationContext(null, null), json)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<BibleService> { bibleService }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    /** Wrap a raw HTML string as the node's single (STRING-kind) input. */
    private fun htmlInput(html: String) =
        NodeInputs(mapOf("in" to PipelineValue.of(html, String.serializer())))

    @Test
    fun `converts the html input into a Content document`() = runTest {
        val out = ConvertToDocumentNode(id = "n1")
            .executeForTest(context, htmlInput("<p>hello <b>world</b></p>"))

        val content = out?.value as? Content
        assertTrue(content != null, "expected a Content value, got ${out?.value}")
        assertTrue(content.document.content.isNotEmpty(), "expected the parsed HTML to produce document nodes")
    }

    @Test
    fun `produces no output when the html is blank`() = runTest {
        val out = ConvertToDocumentNode(id = "n1").executeForTest(context, htmlInput("   "))

        assertNull(out)
    }

    @Test
    fun `produces no output when there is no input`() = runTest {
        val out = ConvertToDocumentNode(id = "n1").executeForTest(context, NodeInputs(emptyMap()))

        assertNull(out)
    }

    @Test
    fun `dry run produces no output and records nothing when the html is blank`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)

        val out = ConvertToDocumentNode(id = "n1").executeForTest(dry, htmlInput("   "))

        assertNull(out)
        assertNull(trace.actions["n1"])
    }

    @Test
    fun `dry run without a trace still produces the content`() = runTest {
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true)

        val out = ConvertToDocumentNode(id = "n1").executeForTest(dry, htmlInput("<p>x</p>"))

        assertTrue(out?.value is Content)
    }

    @Test
    fun `dry run records the action and still produces the content`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)

        val out = ConvertToDocumentNode(id = "n1").executeForTest(dry, htmlInput("<p>x</p>"))

        // The node is a pure transform, so a dry run converts for real (no side effect to skip) ...
        assertTrue(out?.value is Content)
        // ... and records what it did under the node's id.
        val action = trace.actions["n1"]?.jsonObject
        assertTrue(action != null, "expected a traced action for n1")
        assertEquals("convertToDocument", action["action"]?.jsonPrimitive?.content)
    }
}
