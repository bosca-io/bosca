package bosca.backup.pipeline

import bosca.backup.ObjectBackupManifest
import bosca.backup.ObjectBackupDestinationBusyException
import bosca.backup.ObjectBackupOperation
import bosca.backup.ObjectBackupRun
import bosca.backup.ObjectBackupRunService
import bosca.backup.ObjectBackupRunStatus
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
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.DelayException
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(InternalDI::class)
class ObjectBackupRestoreNodeTest {
    private val service = mockk<ObjectBackupRunService>()
    private val group = Group(name = "administrators", description = "", type = GroupType.SYSTEM)
    private val admin = authentication(AuthenticatedPrincipal(Principal(), listOf(group)))
    private val json = Json { serializersModule = SerializersModule { include(BackupPipelineNodeSerializersProvider().module) } }
    private val inventory = ObjectBackupManifest("snapshot", "old-instance", "content", "2026-10-18T00:00:00Z", emptyList())
    private val prepared = ObjectBackupRun("run", inventory.sourceId, "destination", inventory.scope,
        ObjectBackupOperation.RESTORE, inventory, "manifests/recovery.json")
    private val observedKeys = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<GroupEvaluator> { GroupEvaluator(mockk()) }
        provides<ObjectBackupRunService> { service }
        coEvery { service.prepareRestoreConfigured(any(), "destination", "retained", "manifests/recovery.json", any()) } answers {
            observedKeys.add(firstArg())
            prepared
        }
        coEvery { service.executeConfigured("run") } returns prepared.copy(status = ObjectBackupRunStatus.COMPLETED)
        coEvery { service.executeConfigured("run", any<String>()) } returns prepared.copy(status = ObjectBackupRunStatus.COMPLETED)
    }

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    @Test
    fun `prepares fixed restore targets then emits the completed manifest`(): Unit = runBlocking {
        val output = perform()
        assertEquals(inventory, output.decode(ObjectBackupManifest.serializer(), json))
        coVerifyOrder {
            service.prepareRestoreConfigured(any(), "destination", "retained", "manifests/recovery.json", mapOf("content" to "target"))
            service.executeConfigured("run")
        }
    }

    @Test
    fun `same backing job and node reuse one request identity while later jobs get another`(): Unit = runBlocking {
        val job = mockk<Job>()
        val queue = mockk<JobQueue>()
        every { job.getId() } returns UUID.random()
        withContext(queue.asCoroutineContext(job)) { perform(); perform() }
        assertEquals(observedKeys[0], observedKeys[1])
        coVerify(exactly = 2) { service.executeConfigured("run", observedKeys[0]) }
        every { job.getId() } returns UUID.random()
        withContext(queue.asCoroutineContext(job)) { perform() }
        assertTrue(observedKeys[0] != observedKeys[2])
        coVerify(exactly = 1) { service.executeConfigured("run", observedKeys[2]) }
    }

    @Test
    fun `queued restore contention defers the same request while inline callers retain the original error`(): Unit = runBlocking {
        val busy = ObjectBackupDestinationBusyException("target")
        coEvery { service.executeConfigured("run") } throws busy
        coEvery { service.executeConfigured("run", any<String>()) } throws busy
        val job = mockk<Job>()
        every { job.getId() } returns UUID.random()
        withContext(mockk<JobQueue>().asCoroutineContext(job)) {
            repeat(2) {
                val delayed = assertFailsWith<DelayException> { perform() }
                assertEquals(5.seconds, delayed.time)
                assertTrue(delayed.cause === busy)
            }
        }
        assertEquals(observedKeys[0], observedKeys[1])
        assertTrue(assertFailsWith<ObjectBackupDestinationBusyException> { perform() } === busy)
    }

    @Test
    fun `anonymous and unscoped administrator tokens fail before preparation`(): Unit = runBlocking {
        for (authentication in listOf(AuthenticationContext(null, null), authentication(
            ScopedAuthenticatedPrincipal(Principal(), listOf(group), listOf("content:edit"), null, 1),
        ))) assertFailsWith<SecurityException> { perform(authentication = authentication) }
        coVerify(exactly = 0) { service.prepareRestoreConfigured(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `service account group may restore through the same boundary authorization`(): Unit = runBlocking {
        val sa = authentication(AuthenticatedPrincipal(Principal(), listOf(Group(name = "sa", description = "", type = GroupType.SYSTEM))))
        assertEquals(inventory, perform(authentication = sa).decode(ObjectBackupManifest.serializer(), json))
    }

    @Test
    fun `missing blank and wrong typed paths fail before preparation`(): Unit = runBlocking {
        assertFailsWith<IllegalStateException> { perform(inputs = NodeInputs(emptyMap())) }
        assertFailsWith<IllegalArgumentException> { perform(inputs = input(" ")) }
        assertFailsWith<Exception> {
            perform(inputs = NodeInputs(mapOf("manifestPath" to PipelineValue.ofJson(JsonObject(emptyMap())))))
        }
        coVerify(exactly = 0) { service.prepareRestoreConfigured(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `invalid targets fail before preparation`(): Unit = runBlocking {
        assertFailsWith<SerializationException> { perform(ObjectBackupRestoreNode("restore", targets = "invalid")) }
        coVerify(exactly = 0) { service.prepareRestoreConfigured(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `cancelled prepared run cannot execute or emit a successful manifest`(): Unit = runBlocking {
        coEvery { service.prepareRestoreConfigured(any(), any(), any(), any(), any()) } returns prepared.copy(status = ObjectBackupRunStatus.CANCELLED)
        assertFailsWith<IllegalStateException> { perform() }
        coVerify(exactly = 0) { service.executeConfigured(any<String>()) }
    }

    @Test
    fun `incomplete or concurrently cancelled outcomes cannot emit success`(): Unit = runBlocking {
        for (status in ObjectBackupRunStatus.entries.filter { it != ObjectBackupRunStatus.COMPLETED }) {
            coEvery { service.executeConfigured("run") } returns prepared.copy(status = status)
            assertFailsWith<IllegalStateException> { perform() }
        }
    }

    @Test
    fun `storage failure and cancellation propagate to durable framework handling`(): Unit = runBlocking {
        coEvery { service.executeConfigured("run") } throws IOException("retained bytes unavailable")
        assertEquals("retained bytes unavailable", assertFailsWith<IOException> { perform() }.message)
        coEvery { service.executeConfigured("run") } throws CancellationException("stopped")
        assertEquals("stopped", assertFailsWith<CancellationException> { perform() }.message)
    }

    @Test
    fun `durable run parks before storage work and delegates enqueue to ordinary suspension`(): Unit = runBlocking {
        val node = node()
        val context = PipelineContext(admin, json, runId = UUID.random())
        val suspension = mockk<NodeSuspensionService>()
        provides<NodeSuspensionService> { suspension }
        coEvery { suspension.suspendNode(context, node, any()) } returns Unit
        val result = assertIs<NodeResult.Suspend>(node.run(context, input()))
        coVerify(exactly = 0) { service.prepareRestoreConfigured(any(), any(), any(), any(), any()) }
        result.enqueue()
        coVerify(exactly = 1) { suspension.suspendNode(context, node, any()) }
        coVerify(exactly = 0) { service.prepareRestoreConfigured(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `preview tolerates missing input and traces target configuration without services`(): Unit = runBlocking {
        ProviderRegistry.clear()
        val trace = DryRunTrace()
        val result = node().run(PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace), NodeInputs(emptyMap()))
        assertNull(assertIs<NodeResult.Output>(result).value)
        val action = trace.actions.getValue("restore").jsonObject
        assertEquals(JsonPrimitive("objectBackup.restore"), action["action"])
        assertEquals(JsonPrimitive("retained"), action["backupKey"])
        assertEquals(JsonPrimitive("destination"), action["destinationId"])
    }

    @Test
    fun `generated metadata and graph codec expose the action and declarative target editor`() {
        val descriptor = BackupPipelineNodeSerializersProvider().descriptors.single { it.key == "objectBackup.restore" }
        assertTrue(descriptor.inputs.single().required)
        assertEquals(SettingControl.CODE, descriptor.settings.single { it.name == "targets" }.control)
        assertEquals(ObjectBackupManifest.serializer().descriptor.serialName, descriptor.outputs.single().type)
        val decoded = assertIs<ObjectBackupRestoreNode>(json.decodeFromString(PipelineNode.serializer(), json.encodeToString(PipelineNode.serializer(), node())))
        assertEquals(node().targets, decoded.targets)
        assertTrue(decoded.willSuspend)
    }

    private suspend fun perform(
        node: ObjectBackupRestoreNode = node(),
        authentication: AuthenticationContext = admin,
        inputs: NodeInputs = input(),
    ): PipelineValue = assertNotNull(assertIs<NodeResult.Output>(node.run(PipelineContext(authentication, json), inputs)).value)

    private fun node() = ObjectBackupRestoreNode("restore", backupKey = "retained", destinationId = "destination",
        targets = """{"content":"target"}""")
    private fun input(path: String = "manifests/recovery.json") = NodeInputs(mapOf("manifestPath" to PipelineValue.ofJson(JsonPrimitive(path))))
    private fun authentication(principal: AuthenticatedPrincipal) = object : AuthenticationContext(null, null) {
        override fun principal() = principal
    }
}
