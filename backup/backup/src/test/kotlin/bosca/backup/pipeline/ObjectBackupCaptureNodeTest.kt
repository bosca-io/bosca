package bosca.backup.pipeline

import bosca.backup.ObjectBackupCaptureService
import bosca.backup.ObjectBackupDestinationBusyException
import bosca.backup.ObjectBackupManifest
import bosca.backup.ObjectBackupOperation
import bosca.backup.objectBackupIdentity
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.model.RetryPolicy
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.BackupPipelineNodeSerializersProvider
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineNode
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.NodeSuspensionService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.sharedqueue.jobs.DelayException
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(InternalDI::class)
class ObjectBackupCaptureNodeTest {
    private val service = mockk<ObjectBackupCaptureService>()
    private val manifest = ObjectBackupManifest("selected", "source", "content", "2026-10-18T00:00:00Z", emptyList())
    private val adminGroup = Group(id = UUID.random(), name = "administrators", description = "", type = GroupType.SYSTEM)
    private val admin = authentication(AuthenticatedPrincipal(Principal(), listOf(adminGroup)))
    private val json = Json {
        serializersModule = SerializersModule {
            contextual(UUIDSerializer())
            include(BackupPipelineNodeSerializersProvider().module)
        }
    }

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<GroupEvaluator> { GroupEvaluator(mockk()) }
        provides<ObjectBackupCaptureService> { service }
        coEvery { service.captureConfigured("saved-plan", any<OffsetDateTime>()) } returns manifest
    }

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    @Test
    fun `executes only the saved configured plan and emits its typed published manifest`(): Unit = runBlocking {
        val output = assertIs<NodeResult.Output>(ObjectBackupCaptureNode("capture").run(context(), inputs()))
        val value = assertNotNull(output.value)
        assertEquals(ObjectBackupManifest.serializer().descriptor.serialName, value.typeName)
        assertEquals(manifest, value.decode(ObjectBackupManifest.serializer(), json))
        coVerify(exactly = 1) { service.captureConfigured("saved-plan", null) }
    }

    @Test
    fun `passes the explicit offset timestamp without changing its precision or policy`(): Unit = runBlocking {
        val cutoff = "2026-10-18T00:00:00.123456789-05:00"
        ObjectBackupCaptureNode("capture").run(context(), inputs(JsonPrimitive(cutoff)))
        coVerify(exactly = 1) { service.captureConfigured("saved-plan", OffsetDateTime.parse(cutoff)) }
    }

    @Test
    fun `explicit JSON null cutoff requests full verification`(): Unit = runBlocking {
        ObjectBackupCaptureNode("capture").run(context(), inputs(JsonNull))
        coVerify(exactly = 1) { service.captureConfigured("saved-plan", null) }
    }

    @Test
    fun `missing blank and malformed required keys fail before resolving capture work`(): Unit = runBlocking {
        val node = ObjectBackupCaptureNode("capture")
        assertFailsWith<IllegalStateException> { node.run(context(), NodeInputs(emptyMap())) }
        for (key in listOf("", " ")) {
            assertFailsWith<IllegalArgumentException> {
                node.run(context(), NodeInputs(mapOf("planKey" to PipelineValue.ofJson(JsonPrimitive(key)))))
            }
        }
        assertFailsWith<Exception> {
            node.run(context(), NodeInputs(mapOf("planKey" to PipelineValue.ofJson(JsonObject(emptyMap())))))
        }
        coVerify(exactly = 0) { service.captureConfigured(any<String>(), any<OffsetDateTime>()) }
    }

    @Test
    fun `malformed cutoffs fail before capture instead of silently changing verification`(): Unit = runBlocking {
        for (cutoff in listOf("", " ", "yesterday", "2026-10-18T00:00:00")) {
            assertFailsWith<DateTimeParseException> {
                ObjectBackupCaptureNode("capture").run(context(), inputs(JsonPrimitive(cutoff)))
            }
        }
        assertFailsWith<Exception> {
            ObjectBackupCaptureNode("capture").run(context(), inputs(JsonObject(emptyMap())))
        }
        coVerify(exactly = 0) { service.captureConfigured(any<String>(), any<OffsetDateTime>()) }
    }

    @Test
    fun `anonymous and ordinary principals cannot execute capture`(): Unit = runBlocking {
        for (authentication in listOf(AuthenticationContext(null, null),
            authentication(AuthenticatedPrincipal(Principal(), listOf(Group(name = "viewers", description = "", type = GroupType.SYSTEM)))))) {
            assertFailsWith<SecurityException> { ObjectBackupCaptureNode("capture").run(context(authentication), inputs()) }
        }
        coVerify(exactly = 0) { service.captureConfigured(any<String>(), any<OffsetDateTime>()) }
    }

    @Test
    fun `administrator API tokens require security management scope and effective group membership`(): Unit = runBlocking {
        val node = ObjectBackupCaptureNode("capture")
        for (principal in listOf(
            ScopedAuthenticatedPrincipal(Principal(), listOf(adminGroup), listOf("content:edit"), null, 1),
            ScopedAuthenticatedPrincipal(Principal(), listOf(adminGroup), listOf("security:manage"), emptySet(), 1),
        )) {
            assertFailsWith<SecurityException> { node.run(context(authentication(principal)), inputs()) }
        }
        coVerify(exactly = 0) { service.captureConfigured(any<String>(), any<OffsetDateTime>()) }
        val allowed = ScopedAuthenticatedPrincipal(Principal(), listOf(adminGroup), listOf("security:manage"), setOf(adminGroup.id), 1)
        assertNotNull(assertIs<NodeResult.Output>(node.run(context(authentication(allowed)), inputs())).value)
        coVerify(exactly = 1) { service.captureConfigured("saved-plan", null) }
    }

    @Test
    fun `service account membership grants the same backup authority`(): Unit = runBlocking {
        val sa = authentication(AuthenticatedPrincipal(Principal(), listOf(Group(name = "sa", description = "", type = GroupType.SYSTEM))))
        assertNotNull(assertIs<NodeResult.Output>(ObjectBackupCaptureNode("capture").run(context(sa), inputs())).value)
        coVerify(exactly = 1) { service.captureConfigured("saved-plan", null) }
    }

    @Test
    fun `durable execution parks before work and delegates enqueue to the existing suspension service`(): Unit = runBlocking {
        val node = ObjectBackupCaptureNode("capture")
        val context = context(runId = UUID.random())
        val inputs = inputs()
        val suspension = mockk<NodeSuspensionService>()
        provides<NodeSuspensionService> { suspension }
        coEvery { suspension.suspendNode(context, node, inputs) } returns Unit
        val result = assertIs<NodeResult.Suspend>(node.run(context, inputs))
        coVerify(exactly = 0) { suspension.suspendNode(any(), any(), any()) }
        coVerify(exactly = 0) { service.captureConfigured(any<String>(), any<OffsetDateTime>()) }
        result.enqueue()
        coVerify(exactly = 1) { suspension.suspendNode(context, node, inputs) }
        coVerify(exactly = 0) { service.captureConfigured(any<String>(), any<OffsetDateTime>()) }
    }

    @Test
    fun `storage failure destination contention and missing plans propagate to framework retries`(): Unit = runBlocking {
        for (failure in listOf(IOException("storage failed"), ObjectBackupDestinationBusyException("retained"),
            IllegalStateException("capture plan missing"))) {
            coEvery { service.captureConfigured("saved-plan", any<OffsetDateTime>()) } throws failure
            val actual = assertFailsWith<Exception> { ObjectBackupCaptureNode("capture").run(context(), inputs()) }
            assertEquals(failure::class, actual::class)
            assertEquals(failure.message, actual.message)
        }
        coVerify(exactly = 3) { service.captureConfigured("saved-plan", null) }
    }

    @Test
    fun `queued saved capture defers destination contention instead of failing the job`(): Unit = runBlocking {
        val busy = ObjectBackupDestinationBusyException("retained")
        coEvery { service.captureConfigured("saved-plan", any<OffsetDateTime>()) } throws busy
        coEvery { service.captureConfigured("saved-plan", any<OffsetDateTime>(), any<String>()) } throws busy
        val job = mockk<Job> { every { getId() } returns UUID.random() }
        withContext(mockk<JobQueue>().asCoroutineContext(job)) {
            val delayed = assertFailsWith<DelayException> { ObjectBackupCaptureNode("capture").run(context(), inputs()) }
            assertEquals(5.seconds, delayed.time)
            assertTrue(delayed.cause === busy)
        }
        coVerify(exactly = 1) { service.captureConfigured("saved-plan", null, any<String>()) }
    }

    @Test
    fun `queued retries reuse the same job checkpoint and distinct nodes remain independent`(): Unit = runBlocking {
        coEvery { service.captureConfigured("saved-plan", any<OffsetDateTime>(), any<String>()) } returns manifest
        val invocation = UUID.random()
        val job = mockk<Job> { every { getId() } returns invocation }
        withContext(mockk<JobQueue>().asCoroutineContext(job)) {
            repeat(2) { ObjectBackupCaptureNode("capture").run(context(), inputs()) }
            ObjectBackupCaptureNode("other").run(context(), inputs())
        }
        val checkpoint = objectBackupIdentity(ObjectBackupOperation.BACKUP, listOf(invocation.toString(), "capture"))
        val other = objectBackupIdentity(ObjectBackupOperation.BACKUP, listOf(invocation.toString(), "other"))
        coVerify(exactly = 2) { service.captureConfigured("saved-plan", null, checkpoint) }
        coVerify(exactly = 1) { service.captureConfigured("saved-plan", null, other) }
        coVerify(exactly = 0) { service.captureConfigured("saved-plan", null) }
    }

    @Test
    fun `cancellation propagates without an output or another capture attempt`(): Unit = runBlocking {
        coEvery { service.captureConfigured("saved-plan", any<OffsetDateTime>()) } throws CancellationException("stopped")
        val failure = assertFailsWith<CancellationException> { ObjectBackupCaptureNode("capture").run(context(), inputs()) }
        assertEquals("stopped", failure.message)
        coVerify(exactly = 1) { service.captureConfigured("saved-plan", null) }
    }

    @Test
    fun `preview traces supplied intent without services storage or an invented manifest`(): Unit = runBlocking {
        ProviderRegistry.clear()
        val trace = DryRunTrace()
        val cutoff = "2026-10-18T00:00:00Z"
        val context = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace, runId = UUID.random())
        val output = assertIs<NodeResult.Output>(ObjectBackupCaptureNode("capture").run(context, inputs(JsonPrimitive(cutoff))))
        assertNull(output.value)
        assertEquals("objectBackup.capture", trace.actions.getValue("capture").jsonObject.getValue("action").jsonPrimitive.content)
        assertEquals("saved-plan", trace.actions.getValue("capture").jsonObject.getValue("planKey").jsonPrimitive.content)
        assertEquals(cutoff, trace.actions.getValue("capture").jsonObject.getValue("verifiedSince").jsonPrimitive.content)
    }

    @Test
    fun `preview tolerates missing input while a pipeline is being edited`(): Unit = runBlocking {
        ProviderRegistry.clear()
        val trace = DryRunTrace()
        val output = ObjectBackupCaptureNode("capture").run(PipelineContext(admin, json, dryRun = true, trace = trace), NodeInputs(emptyMap()))
        assertNull(assertIs<NodeResult.Output>(output).value)
        assertEquals(JsonNull, trace.actions.getValue("capture").jsonObject["planKey"])
        assertEquals(JsonNull, trace.actions.getValue("capture").jsonObject["verifiedSince"])
    }

    @Test
    fun `generated palette metadata and graph serializers expose the action and typed manifest`() {
        val descriptor = BackupPipelineNodeSerializersProvider().descriptors.single { it.key == "objectBackup.capture" }
        assertEquals(NodeCategory.ACTION, descriptor.category)
        assertEquals("Backups", descriptor.group)
        assertEquals(listOf("planKey", "verifiedSince"), descriptor.inputs.map { it.name })
        assertTrue(descriptor.inputs[0].required)
        assertFalse(descriptor.inputs[1].required)
        assertEquals(listOf(SlotKind.STRING, SlotKind.STRING), descriptor.inputs.map { it.kind })
        val output = descriptor.outputs.single()
        assertEquals(SlotKind.OBJECT, output.kind)
        assertEquals(ObjectBackupManifest.serializer().descriptor.serialName, output.type)
        val original = ObjectBackupCaptureNode("capture", "Assets", "Copy selected revisions", NodePosition(10.0, 20.0)).apply {
            retry = RetryPolicy(3)
            timeoutSeconds = 60
        }
        val roundTrip = assertIs<ObjectBackupCaptureNode>(json.decodeFromString(PipelineNode.serializer(),
            json.encodeToString(PipelineNode.serializer(), original)))
        assertEquals(original.name, roundTrip.name)
        assertEquals(original.description, roundTrip.description)
        assertEquals(original.position, roundTrip.position)
        assertEquals(original.retry, roundTrip.retry)
        assertEquals(original.timeoutSeconds, roundTrip.timeoutSeconds)
        assertTrue(roundTrip.willSuspend)
    }

    private fun context(authentication: AuthenticationContext = admin, runId: UUID? = null) =
        PipelineContext(authentication, json, runId = runId)

    private fun inputs(cutoff: JsonElement? = null) = NodeInputs(buildMap {
        put("planKey", PipelineValue.ofJson(JsonPrimitive("saved-plan")))
        if (cutoff != null) put("verifiedSince", PipelineValue.ofJson(cutoff))
    })

    private fun authentication(principal: AuthenticatedPrincipal) = object : AuthenticationContext(null, null) {
        override fun principal(): AuthenticatedPrincipal = principal
    }
}
