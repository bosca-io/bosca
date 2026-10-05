@file:OptIn(InternalDI::class)

package bosca.pipelines.builtin
import bosca.pipelines.testutil.executeForTestValue

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.forms.model.FormSchema
import bosca.forms.service.FormSchemaService
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUIDSerializer
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class GetFormNodeTest {

    private val service = mockk<FormSchemaService>()

    private val json = Json
    private val context get() = PipelineContext(AuthenticationContext(null, null), json)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<FormSchemaService> { service }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    private fun inputs(value: PipelineValue) = NodeInputs(mapOf("in" to value))

    private fun form(id: Uuid) = FormSchema(
        id = id,
        key = "newsletter-signup",
        name = "Newsletter Signup",
        description = "Collects an email address.",
        schema = JsonObject(mapOf("type" to JsonPrimitive("object"))),
        uiSchema = JsonObject(emptyMap()),
        version = 1,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
    )

    // --- resolution: typed UUID and bare-string inputs ---

    @Test
    fun `resolves the form for a typed UUID value`() = runTest {
        val formId = Uuid.random()
        val form = form(formId)
        coEvery { service.getById(formId) } returns form

        val node = GetFormNode(id = "fetch")
        val result = node.executeForTestValue(context, inputs(PipelineValue.of(formId, UUIDSerializer())))
        assertSame(form, result.value, "the node emits the fetched form itself")
    }

    @Test
    fun `resolves a bare uuid string input`() = runTest {
        val formId = Uuid.random()
        val form = form(formId)
        coEvery { service.getById(formId) } returns form

        val node = GetFormNode(id = "fetch")
        val result = node.executeForTestValue(context, inputs(PipelineValue.ofJson(JsonPrimitive(formId.toString()))))
        assertSame(form, result.value)
    }

    @Test
    fun `the output declares its FormSchema origin`() = runTest {
        val formId = Uuid.random()
        coEvery { service.getById(formId) } returns form(formId)

        val node = GetFormNode(id = "fetch")
        val result = node.executeForTestValue(context, inputs(PipelineValue.of(formId, UUIDSerializer())))
        assertEquals(FormSchema.serializer().descriptor.serialName, result.typeName)
    }

    // --- error: form not found (both ifBlank arms) ---

    @Test
    fun `a missing form fails naming the node name`() = runTest {
        val formId = Uuid.random()
        coEvery { service.getById(formId) } returns null

        val node = GetFormNode(id = "fetch", name = "Resolve form")
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTestValue(context, inputs(PipelineValue.of(formId, UUIDSerializer())))
        }
        assertTrue("Resolve form" in (failure.message ?: ""), failure.message ?: "")
        assertTrue(formId.toString() in (failure.message ?: ""), failure.message ?: "")
    }

    @Test
    fun `a missing form on an unnamed node falls back to the node id`() = runTest {
        val formId = Uuid.random()
        coEvery { service.getById(formId) } returns null

        val node = GetFormNode(id = "fetch") // blank name
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTestValue(context, inputs(PipelineValue.of(formId, UUIDSerializer())))
        }
        assertTrue("'fetch'" in (failure.message ?: ""), failure.message ?: "")
    }

    // --- error: missing input (the generated codec's required-input message) ---

    @Test
    fun `no input fails with the generated required-input error`() = runTest {
        val node = GetFormNode(id = "fetch")
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTestValue(context, NodeInputs(emptyMap()))
        }
        assertTrue("required input 'in'" in (failure.message ?: ""), failure.message ?: "")
    }

    // --- run wrapping + dry run ---

    @Test
    fun `run wraps the fetched form as a NodeResult Output`() = runTest {
        val formId = Uuid.random()
        val form = form(formId)
        coEvery { service.getById(formId) } returns form

        val node = GetFormNode(id = "fetch")
        val result = node.run(context, inputs(PipelineValue.of(formId, UUIDSerializer())))
        assertTrue(result is NodeResult.Output, "the default run emits an Output")
        assertSame(form, result.result?.value)
    }

    @Test
    fun `a dry run still fetches the form`() = runTest {
        // TransformNode.dryRun delegates to execute — a read-only fetch is safe to run dry.
        val formId = Uuid.random()
        val form = form(formId)
        coEvery { service.getById(formId) } returns form

        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true)
        val node = GetFormNode(id = "fetch")
        val result = node.executeForTestValue(dry, inputs(PipelineValue.of(formId, UUIDSerializer())))
        assertSame(form, result.value)
    }

    // --- node identity / defaults / serialization ---

    @Test
    fun `default constructor params default to empty name and description and origin position`() {
        val node = GetFormNode(id = "fetch")
        assertEquals("fetch", node.id)
        assertEquals("", node.name)
        assertEquals("", node.description)
        assertEquals(NodePosition(), node.position)
    }

    @Test
    fun `explicit constructor params are retained`() {
        val node = GetFormNode(
            id = "n1",
            name = "Resolve form",
            description = "from the submission",
            position = NodePosition(3.0, 4.0),
        )
        assertEquals("n1", node.id)
        assertEquals("Resolve form", node.name)
        assertEquals("from the submission", node.description)
        assertEquals(NodePosition(3.0, 4.0), node.position)
    }

    @Test
    fun `description and position pass through the defaulting constructor`() {
        // A partial subset of the optional params — flips individual default-ctor mask bits.
        val node = GetFormNode(id = "x", description = "why", position = NodePosition(9.0, 9.0))
        assertEquals("why", node.description)
        assertEquals(NodePosition(9.0, 9.0), node.position)
        assertEquals("", node.name)
    }

    @Test
    fun `node serializes to and from JSON via its explicit serializer`() {
        val node = GetFormNode(
            id = "n1",
            name = "Resolve form",
            description = "from the submission",
            position = NodePosition(3.0, 4.0),
        )
        val encoded = json.encodeToString(GetFormNode.serializer(), node)
        val decoded = json.decodeFromString(GetFormNode.serializer(), encoded)
        assertEquals(node.id, decoded.id)
        assertEquals(node.name, decoded.name)
        assertEquals(node.description, decoded.description)
        assertEquals(node.position, decoded.position)
    }

    @Test
    fun `encoding a default-valued node omits the defaulted fields and still round-trips`() {
        // Exercises the generated write$Self "skip default" arms (encodeDefaults is off).
        val node = GetFormNode(id = "d")
        val encoded = json.encodeToString(GetFormNode.serializer(), node)
        assertTrue("name" !in encoded, encoded)
        assertTrue("position" !in encoded, encoded)
        val decoded = json.decodeFromString(GetFormNode.serializer(), encoded)
        assertEquals("d", decoded.id)
        assertEquals("", decoded.name)
    }

    @Test
    fun `node decodes from a minimal JSON exercising default-value arms`() {
        val decoded = json.decodeFromString(GetFormNode.serializer(), """{"id":"only-id"}""")
        assertEquals("only-id", decoded.id)
        assertEquals("", decoded.name)
        assertEquals("", decoded.description)
        assertEquals(NodePosition(), decoded.position)
    }

    @Test
    fun `decoding JSON missing the required id throws`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString(GetFormNode.serializer(), "{}")
        }
    }
}
