package bosca.backup.pipeline

import bosca.backup.ObjectBackupCaptureInventory
import bosca.backup.ObjectBackupCapturePlan
import bosca.backup.ObjectBackupCaptureService
import bosca.backup.ObjectBackupDiscoveryRequest
import bosca.backup.ObjectBackupDestinationBusyException
import bosca.backup.ObjectBackupManifest
import bosca.backup.ObjectBackupSource
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.node.BackupPipelineNodeSerializersProvider
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineNode
import bosca.pipelines.node.PipelineValue
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.DelayException
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(InternalDI::class)
class ObjectBackupNodeTest {
    private val service = mockk<ObjectBackupCaptureService>()
    private val observed = mutableListOf<ObjectBackupDiscoveryRequest>()
    private val manifest = ObjectBackupManifest("selected", "source", "content", "2026-10-18T00:00:00Z", emptyList())
    private val admin = object : AuthenticationContext(null, null) {
        override fun principal() = AuthenticatedPrincipal(Principal(), listOf(Group(name = "administrators", description = "", type = GroupType.SYSTEM)))
    }
    private val json = Json { serializersModule = SerializersModule { include(BackupPipelineNodeSerializersProvider().module) } }

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<GroupEvaluator> { GroupEvaluator(mockk()) }
        provides<ObjectBackupCaptureService> { service }
        coEvery { service.discoverConfigured(any()) } answers {
            val request = firstArg<ObjectBackupDiscoveryRequest>()
            observed.add(request)
            ObjectBackupCapturePlan("saved", request.destinationId,
                ObjectBackupCaptureInventory(request.id, request.sourceId, request.scope, "2026-10-18T00:00:00Z", emptyMap()),
                request.payloadPrefix, request.manifestPath)
        }
        coEvery { service.captureConfigured("saved", any<OffsetDateTime>()) } returns manifest
        coEvery { service.captureConfigured("saved", any<OffsetDateTime>(), any<String>()) } returns manifest
    }

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    @Test
    fun `discovers then captures using a stable per-run node identity`(): Unit = runBlocking {
        val node = node()
        val context = context()
        val cutoff = "2026-10-18T00:00:00Z"
        val input = NodeInputs(mapOf("verifiedSince" to PipelineValue.ofJson(JsonPrimitive(cutoff))))
        val job = mockk<Job>()
        val queue = mockk<JobQueue>()
        val executionId = UUID.random()
        io.mockk.every { job.getId() } returns executionId
        withContext(queue.asCoroutineContext(job)) {
            val first = node.perform(context, input)
            assertEquals(manifest, first.decode(ObjectBackupManifest.serializer(), json))
            node.perform(context, input)
        }
        assertEquals(observed[0], observed[1])
        val request = observed.first()
        assertEquals("source", request.sourceId)
        assertEquals("content", request.scope)
        assertEquals("retained", request.destinationId)
        assertEquals(mapOf("content" to ObjectBackupSource("source.id", "media/")), request.stores)
        assertEquals("manifests/${request.id}.json", request.manifestPath)
        coVerify(exactly = 2) { service.captureConfigured("saved", OffsetDateTime.parse(cutoff), request.id) }
    }

    @Test
    fun `later runs and distinct nodes get different immutable selections`(): Unit = runBlocking {
        val context = context()
        node().perform(context, NodeInputs(emptyMap()))
        node().perform(context(), NodeInputs(emptyMap()))
        node("another").perform(context, NodeInputs(emptyMap()))
        assertEquals(3, observed.map { it.id }.toSet().size)
        assertEquals(3, observed.map { it.manifestPath }.toSet().size)
    }

    @Test
    fun `inline executions also use independent selection identities`(): Unit = runBlocking {
        val context = PipelineContext(admin, json)
        node().perform(context, NodeInputs(emptyMap()))
        node().perform(context, NodeInputs(emptyMap()))
        assertNotEquals(observed[0].id, observed[1].id)
    }

    @Test
    fun `authorization precedes discovery and source parsing`(): Unit = runBlocking {
        assertFailsWith<SecurityException> {
            ObjectBackupNode("backup", sources = "invalid").perform(context(AuthenticationContext(null, null)), NodeInputs(emptyMap()))
        }
        coVerify(exactly = 0) { service.discoverConfigured(any()) }
    }

    @Test
    fun `malformed sources and cutoffs fail before creating a selection`(): Unit = runBlocking {
        assertFailsWith<SerializationException> { ObjectBackupNode("backup", sources = "invalid").perform(context(), NodeInputs(emptyMap())) }
        assertFailsWith<DateTimeParseException> {
            node().perform(context(), NodeInputs(mapOf("verifiedSince" to PipelineValue.ofJson(JsonPrimitive("yesterday")))))
        }
        coVerify(exactly = 0) { service.discoverConfigured(any()) }
    }

    @Test
    fun `checksum interval uses the stable occurrence time rather than worker wall clock`(): Unit = runBlocking {
        val created = OffsetDateTime.parse("2026-10-18T12:00:00Z")
        val node = ObjectBackupNode("backup", sourceId = "source", scope = "content", destinationId = "retained",
            sources = "{}", verificationMaxAgeDays = 7)
        node.perform(PipelineContext(admin, json, inputCreated = created), NodeInputs(emptyMap()))
        coVerify(exactly = 1) { service.captureConfigured("saved", created.minusDays(7)) }
    }

    @Test
    fun `ordinary copies reuse verified destination observations without requiring a checksum schedule`(): Unit = runBlocking {
        node().perform(context(), NodeInputs(emptyMap()))
        coVerify(exactly = 1) { service.captureConfigured("saved", OffsetDateTime.MIN) }
    }

    @Test
    fun `explicit cutoff overrides interval and invalid intervals fail before discovery`(): Unit = runBlocking {
        val cutoff = OffsetDateTime.parse("2026-10-18T00:00:00Z")
        ObjectBackupNode("backup", sources = "{}", verificationMaxAgeDays = 7).perform(context(),
            NodeInputs(mapOf("verifiedSince" to PipelineValue.ofJson(JsonPrimitive(cutoff.toString())))))
        coVerify(exactly = 1) { service.captureConfigured("saved", cutoff) }
        for (days in listOf(0, -1)) {
            assertFailsWith<IllegalArgumentException> {
                ObjectBackupNode("backup", verificationMaxAgeDays = days).perform(context(), NodeInputs(emptyMap()))
            }
        }
        coVerify(exactly = 1) { service.discoverConfigured(any()) }
    }

    @Test
    fun `discovery and capture failures and cancellation propagate`(): Unit = runBlocking {
        coEvery { service.discoverConfigured(any()) } throws IOException("discovery failed")
        assertEquals("discovery failed", assertFailsWith<IOException> { node().perform(context(), NodeInputs(emptyMap())) }.message)
        coVerify(exactly = 0) { service.captureConfigured(any<String>(), any<OffsetDateTime>()) }
        setUp()
        coEvery { service.captureConfigured("saved", any<OffsetDateTime>()) } throws CancellationException("stopped")
        assertEquals("stopped", assertFailsWith<CancellationException> { node().perform(context(), NodeInputs(emptyMap())) }.message)
    }

    @Test
    fun `queued destination contention requests delay while inline callers retain the original error`(): Unit = runBlocking {
        val busy = ObjectBackupDestinationBusyException("retained")
        coEvery { service.captureConfigured("saved", any<OffsetDateTime>()) } throws busy
        coEvery { service.captureConfigured("saved", any<OffsetDateTime>(), any<String>()) } throws busy
        val job = mockk<Job>()
        io.mockk.every { job.getId() } returns UUID.random()
        withContext(mockk<JobQueue>().asCoroutineContext(job)) {
            val delayed = assertFailsWith<DelayException> { node().perform(context(), NodeInputs(emptyMap())) }
            assertEquals(5.seconds, delayed.time)
            assertTrue(delayed.cause === busy)
        }
        assertTrue(assertFailsWith<ObjectBackupDestinationBusyException> {
            node().perform(context(), NodeInputs(emptyMap()))
        } === busy)
    }

    @Test
    fun `preview records configuration without resolving services or inventing output`(): Unit = runBlocking {
        ProviderRegistry.clear()
        val trace = DryRunTrace()
        val node = ObjectBackupNode("backup", destinationId = "retained", verificationMaxAgeDays = 7)
        val result = node.run(PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace), NodeInputs(emptyMap()))
        assertNull(assertIs<NodeResult.Output>(result).value)
        val action = trace.actions.getValue("backup").jsonObject
        assertEquals(JsonPrimitive("objectBackup.backup"), action["action"])
        assertEquals(JsonPrimitive("retained"), action["destinationId"])
        assertEquals(JsonPrimitive(7), action["verificationMaxAgeDays"])
    }

    @Test
    fun `generated metadata and graph serializer expose standard settings and durable action`() {
        val descriptor = BackupPipelineNodeSerializersProvider().descriptors.single { it.key == "objectBackup.backup" }
        assertEquals(SettingControl.CODE, descriptor.settings.single { it.name == "sources" }.control)
        assertFalse(descriptor.inputs.single().required)
        assertEquals(ObjectBackupManifest.serializer().descriptor.serialName, descriptor.outputs.single().type)
        val restored = assertIs<ObjectBackupNode>(json.decodeFromString(PipelineNode.serializer(), json.encodeToString(PipelineNode.serializer(), node())))
        assertEquals(node().sources, restored.sources)
        assertTrue(restored.willSuspend)
    }

    private suspend fun ObjectBackupNode.perform(context: PipelineContext, inputs: NodeInputs): PipelineValue =
        assertNotNull(assertIs<NodeResult.Output>(run(context, inputs)).value)

    private fun node(id: String = "backup") = ObjectBackupNode(id, sourceId = "source", scope = "content", destinationId = "retained",
        sources = """{"content":{"storageKey":"source.id","prefix":"media/"}}""")
    private fun context(authentication: AuthenticationContext = admin) = PipelineContext(authentication, json)
}
