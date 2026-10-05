@file:OptIn(InternalDI::class)

package bosca.pipelines.builtin
import bosca.pipelines.testutil.executeForTestValue

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.forms.model.FormSubmission
import bosca.forms.model.FormSubmissionStatus
import bosca.forms.service.FormSubmissionService
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

class GetFormSubmissionNodeTest {

    private val service = mockk<FormSubmissionService>()

    private val json = Json
    private val context get() = PipelineContext(AuthenticationContext(null, null), json)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<FormSubmissionService> { service }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    private fun inputs(value: PipelineValue) = NodeInputs(mapOf("in" to value))

    private fun submission(id: Uuid) = FormSubmission(
        id = id,
        formSchemaId = Uuid.random(),
        profileId = Uuid.random(),
        attributes = JsonObject(mapOf("email" to JsonPrimitive("alex@example.com"))),
        status = FormSubmissionStatus.PENDING,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
    )

    // --- resolution: typed UUID and bare-string inputs ---

    @Test
    fun `resolves the submission for a typed UUID value`() = runTest {
        val submissionId = Uuid.random()
        val submission = submission(submissionId)
        coEvery { service.getById(submissionId) } returns submission

        val node = GetFormSubmissionNode(id = "fetch")
        val result = node.executeForTestValue(context, inputs(PipelineValue.of(submissionId, UUIDSerializer())))
        assertSame(submission, result.value, "the node emits the fetched submission itself")
    }

    @Test
    fun `resolves a bare uuid string input`() = runTest {
        val submissionId = Uuid.random()
        val submission = submission(submissionId)
        coEvery { service.getById(submissionId) } returns submission

        val node = GetFormSubmissionNode(id = "fetch")
        val result = node.executeForTestValue(context, inputs(PipelineValue.ofJson(JsonPrimitive(submissionId.toString()))))
        assertSame(submission, result.value)
    }

    @Test
    fun `the output declares its FormSubmission origin`() = runTest {
        val submissionId = Uuid.random()
        coEvery { service.getById(submissionId) } returns submission(submissionId)

        val node = GetFormSubmissionNode(id = "fetch")
        val result = node.executeForTestValue(context, inputs(PipelineValue.of(submissionId, UUIDSerializer())))
        assertEquals(FormSubmission.serializer().descriptor.serialName, result.typeName)
    }

    // --- error: submission not found (both ifBlank arms) ---

    @Test
    fun `a missing submission fails naming the node name`() = runTest {
        val submissionId = Uuid.random()
        coEvery { service.getById(submissionId) } returns null

        val node = GetFormSubmissionNode(id = "fetch", name = "Resolve submission")
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTestValue(context, inputs(PipelineValue.of(submissionId, UUIDSerializer())))
        }
        assertTrue("Resolve submission" in (failure.message ?: ""), failure.message ?: "")
        assertTrue(submissionId.toString() in (failure.message ?: ""), failure.message ?: "")
    }

    @Test
    fun `a missing submission on an unnamed node falls back to the node id`() = runTest {
        val submissionId = Uuid.random()
        coEvery { service.getById(submissionId) } returns null

        val node = GetFormSubmissionNode(id = "fetch") // blank name
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTestValue(context, inputs(PipelineValue.of(submissionId, UUIDSerializer())))
        }
        assertTrue("'fetch'" in (failure.message ?: ""), failure.message ?: "")
    }

    // --- error: missing input (the generated codec's required-input message) ---

    @Test
    fun `no input fails with the generated required-input error`() = runTest {
        val node = GetFormSubmissionNode(id = "fetch")
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTestValue(context, NodeInputs(emptyMap()))
        }
        assertTrue("required input 'in'" in (failure.message ?: ""), failure.message ?: "")
    }

    // --- run wrapping + dry run ---

    @Test
    fun `run wraps the fetched submission as a NodeResult Output`() = runTest {
        val submissionId = Uuid.random()
        val submission = submission(submissionId)
        coEvery { service.getById(submissionId) } returns submission

        val node = GetFormSubmissionNode(id = "fetch")
        val result = node.run(context, inputs(PipelineValue.of(submissionId, UUIDSerializer())))
        assertTrue(result is NodeResult.Output, "the default run emits an Output")
        assertSame(submission, result.result?.value)
    }

    @Test
    fun `a dry run still fetches the submission`() = runTest {
        // TransformNode.dryRun delegates to execute — a read-only fetch is safe to run dry.
        val submissionId = Uuid.random()
        val submission = submission(submissionId)
        coEvery { service.getById(submissionId) } returns submission

        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true)
        val node = GetFormSubmissionNode(id = "fetch")
        val result = node.executeForTestValue(dry, inputs(PipelineValue.of(submissionId, UUIDSerializer())))
        assertSame(submission, result.value)
    }

    // --- node identity / defaults / serialization ---

    @Test
    fun `default constructor params default to empty name and description and origin position`() {
        val node = GetFormSubmissionNode(id = "fetch")
        assertEquals("fetch", node.id)
        assertEquals("", node.name)
        assertEquals("", node.description)
        assertEquals(NodePosition(), node.position)
    }

    @Test
    fun `explicit constructor params are retained`() {
        val node = GetFormSubmissionNode(
            id = "n1",
            name = "Resolve submission",
            description = "from the event",
            position = NodePosition(3.0, 4.0),
        )
        assertEquals("n1", node.id)
        assertEquals("Resolve submission", node.name)
        assertEquals("from the event", node.description)
        assertEquals(NodePosition(3.0, 4.0), node.position)
    }

    @Test
    fun `description and position pass through the defaulting constructor`() {
        // A partial subset of the optional params — flips individual default-ctor mask bits.
        val node = GetFormSubmissionNode(id = "x", description = "why", position = NodePosition(9.0, 9.0))
        assertEquals("why", node.description)
        assertEquals(NodePosition(9.0, 9.0), node.position)
        assertEquals("", node.name)
    }

    @Test
    fun `node serializes to and from JSON via its explicit serializer`() {
        val node = GetFormSubmissionNode(
            id = "n1",
            name = "Resolve submission",
            description = "from the event",
            position = NodePosition(3.0, 4.0),
        )
        val encoded = json.encodeToString(GetFormSubmissionNode.serializer(), node)
        val decoded = json.decodeFromString(GetFormSubmissionNode.serializer(), encoded)
        assertEquals(node.id, decoded.id)
        assertEquals(node.name, decoded.name)
        assertEquals(node.description, decoded.description)
        assertEquals(node.position, decoded.position)
    }

    @Test
    fun `encoding a default-valued node omits the defaulted fields and still round-trips`() {
        // Exercises the generated write$Self "skip default" arms (encodeDefaults is off).
        val node = GetFormSubmissionNode(id = "d")
        val encoded = json.encodeToString(GetFormSubmissionNode.serializer(), node)
        assertTrue("name" !in encoded, encoded)
        assertTrue("position" !in encoded, encoded)
        val decoded = json.decodeFromString(GetFormSubmissionNode.serializer(), encoded)
        assertEquals("d", decoded.id)
        assertEquals("", decoded.name)
    }

    @Test
    fun `node decodes from a minimal JSON exercising default-value arms`() {
        val decoded = json.decodeFromString(GetFormSubmissionNode.serializer(), """{"id":"only-id"}""")
        assertEquals("only-id", decoded.id)
        assertEquals("", decoded.name)
        assertEquals("", decoded.description)
        assertEquals(NodePosition(), decoded.position)
    }

    @Test
    fun `decoding JSON missing the required id throws`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString(GetFormSubmissionNode.serializer(), "{}")
        }
    }
}
