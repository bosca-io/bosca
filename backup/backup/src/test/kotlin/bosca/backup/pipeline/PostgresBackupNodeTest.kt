package bosca.backup.pipeline

import bosca.backup.PostgresBackupManifest
import bosca.backup.PostgresBackupReceipt
import bosca.backup.PostgresBackupRunService
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
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.modules.SerializersModule

@OptIn(InternalDI::class)
class PostgresBackupNodeTest {
    private val service = mockk<PostgresBackupRunService>()
    private val keys = mutableListOf<String>()
    private val manifest = PostgresBackupManifest("artifact", "instance", "source", "2026-10-19T00:00:00Z", "2026-10-19T00:00:01Z",
        "snapshot", "1:2:", "18", 180000, "UTF8", emptyMap(), 5, "a".repeat(64))
    private val admin = object : AuthenticationContext(null, null) {
        override fun principal() = AuthenticatedPrincipal(Principal(), listOf(Group(name = "administrators", description = "", type = GroupType.SYSTEM)))
    }
    private val json = Json { serializersModule = SerializersModule { include(BackupPipelineNodeSerializersProvider().module) } }

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<GroupEvaluator> { GroupEvaluator(mockk()) }
        provides<PostgresBackupRunService> { service }
        coEvery { service.backupConfigured(any(), "instance", "retained", "postgresql", any()) } answers {
            val key = firstArg<String>()
            keys.add(key)
            PostgresBackupReceipt(key, "retained", "postgresql/$key/artifact", manifest)
        }
    }

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    @Test
    fun `backing job and node identity survive retry while other jobs and nodes are independent`(): Unit = runBlocking {
        val queue = mockk<JobQueue>()
        val job = mockk<Job>()
        every { job.getId() } returns UUID.random()
        withContext(queue.asCoroutineContext(job)) {
            val result = perform(node())
            assertEquals(manifest, result.manifest)
            perform(node())
            perform(node("other"))
        }
        assertEquals(keys[0], keys[1])
        coVerify(exactly = 2) { service.backupConfigured(keys[0], "instance", "retained", "postgresql", keys[0]) }
        assertTrue(keys[0] != keys[2])
        every { job.getId() } returns UUID.random()
        withContext(queue.asCoroutineContext(job)) { perform(node()) }
        assertTrue(keys[0] != keys[3])
        assertTrue(keys.all { it.matches(Regex("[0-9a-f]{64}")) })
    }

    @Test
    fun `unauthorized callers fail before resolving the native backup service`(): Unit = runBlocking {
        assertFailsWith<SecurityException> {
            node().run(PipelineContext(AuthenticationContext(null, null), json), NodeInputs(emptyMap()))
        }
        coVerify(exactly = 0) { service.backupConfigured(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `inline calls have independent requests without resumable job checkpoints`(): Unit = runBlocking {
        repeat(2) { perform(node()) }
        assertTrue(keys[0] != keys[1])
        for (key in keys) coVerify(exactly = 1) {
            service.backupConfigured(key, "instance", "retained", "postgresql", null)
        }
    }

    @Test
    fun `dry run records settings without services or fabricated recovery metadata`(): Unit = runBlocking {
        ProviderRegistry.clear()
        val trace = DryRunTrace()
        val result = node().run(PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace), NodeInputs(emptyMap()))
        assertNull(assertIs<NodeResult.Output>(result).value)
        val action = trace.actions.getValue("backup").jsonObject
        assertEquals(JsonPrimitive("postgresBackup.backup"), action["action"])
        assertEquals(JsonPrimitive("retained"), action["storageKey"])
    }

    @Test
    fun `cancellation and native failures propagate without successful output`(): Unit = runBlocking {
        coEvery { service.backupConfigured(any(), any(), any(), any(), any()) } throws CancellationException("stopped")
        assertEquals("stopped", assertFailsWith<CancellationException> { perform(node()) }.message)
        coEvery { service.backupConfigured(any(), any(), any(), any(), any()) } throws java.io.IOException("upload failed")
        assertEquals("upload failed", assertFailsWith<java.io.IOException> { perform(node()) }.message)
    }

    private fun node(id: String = "backup") = PostgresBackupNode(id, sourceId = "instance", storageKey = "retained")



    private suspend fun perform(node: PostgresBackupNode): PostgresBackupReceipt {
        val result = node.run(PipelineContext(admin, json), NodeInputs(emptyMap()))
        return assertNotNull(assertNotNull(assertIs<NodeResult.Output>(result).value).decode(PostgresBackupReceipt.serializer(), json))
    }
}
