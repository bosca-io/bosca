package bosca.cli.ci

import bosca.cli.api.NetworkClient
import bosca.graphql.gen.ListPipelineAgentsData
import bosca.graphql.gen.GitAgentMode
import bosca.graphql.gen.GitAgentStatus
import java.io.File
import java.nio.file.Files
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class OrchestratorReconcileTest {

    private val orchestratorId = Uuid.parse("11111111-1111-1111-1111-111111111111")

    private lateinit var tmpDir: File
    private lateinit var journalFile: File
    private lateinit var journal: VmJournal

    @BeforeTest
    fun setup() {
        tmpDir = Files.createTempDirectory("orchestrator-reconcile").toFile()
        journalFile = File(tmpDir, "vms.log")
        journal = VmJournal(journalFile)
    }

    @AfterTest
    fun teardown() {
        tmpDir.deleteRecursively()
    }

    private fun runner(api: CiApi, provider: CloudProvider): OrchestratorRunner {
        val r = OrchestratorRunner(
            api = api,
            agentId = orchestratorId,
            labels = listOf("linux"),
            journal = journal,
        )
        r.provider = provider
        return r
    }

    @Test
    fun `no orphans is a no-op`() = runTest {
        val api = FakeApi()
        val provider = FakeProvider()
        runner(api, provider).reconcileOrphans()

        assertTrue(provider.deletedIds.isEmpty(), "no deletes should be attempted")
        assertTrue(api.deregistered.isEmpty(), "no deregister calls expected")
    }

    @Test
    fun `journal-only orphan is destroyed and journal compacted`() = runTest {
        val agentUuid = Uuid.parse("22222222-2222-2222-2222-222222222222")
        journal.appendProvisioned(VmInstance(dropletId = "drop-A", ephemeralAgentId = agentUuid.toString(), jobId = "job-A"))

        val api = FakeApi()
        val provider = FakeProvider()
        runner(api, provider).reconcileOrphans()

        assertContentEquals(listOf("drop-A"), provider.deletedIds)
        assertContentEquals(listOf(agentUuid), api.deregistered)
        assertEquals(0L, journalFile.length(), "journal should be compacted to empty after successful reconcile")
    }

    @Test
    fun `server-only orphan is destroyed`() = runTest {
        val agentUuid = Uuid.parse("33333333-3333-3333-3333-333333333333")
        val api = FakeApi(
            agents = listOf(makeAgent(id = agentUuid, parentAgentId = orchestratorId, instanceId = "drop-B"))
        )
        val provider = FakeProvider()
        runner(api, provider).reconcileOrphans()

        assertContentEquals(listOf("drop-B"), provider.deletedIds)
        assertContentEquals(listOf(agentUuid), api.deregistered)
    }

    @Test
    fun `server agents owned by another orchestrator are ignored`() = runTest {
        val otherOrchestrator = Uuid.parse("99999999-9999-9999-9999-999999999999")
        val api = FakeApi(
            agents = listOf(makeAgent(parentAgentId = otherOrchestrator, instanceId = "drop-other"))
        )
        val provider = FakeProvider()
        runner(api, provider).reconcileOrphans()

        assertTrue(provider.deletedIds.isEmpty())
    }

    @Test
    fun `non-ephemeral server agents are ignored`() = runTest {
        val api = FakeApi(
            agents = listOf(makeAgent(parentAgentId = orchestratorId, instanceId = "drop-runner", ephemeral = false))
        )
        val provider = FakeProvider()
        runner(api, provider).reconcileOrphans()

        assertTrue(provider.deletedIds.isEmpty())
    }

    @Test
    fun `server agents without instanceId are ignored`() = runTest {
        val api = FakeApi(
            agents = listOf(makeAgent(parentAgentId = orchestratorId, instanceId = null))
        )
        val provider = FakeProvider()
        runner(api, provider).reconcileOrphans()

        assertTrue(provider.deletedIds.isEmpty())
    }

    @Test
    fun `journal and server orphans are unioned by dropletId without duplicates`() = runTest {
        val agentUuid = Uuid.parse("44444444-4444-4444-4444-444444444444")
        journal.appendProvisioned(VmInstance(dropletId = "drop-X", ephemeralAgentId = agentUuid.toString(), jobId = "job-X"))

        val api = FakeApi(
            agents = listOf(makeAgent(id = agentUuid, parentAgentId = orchestratorId, instanceId = "drop-X"))
        )
        val provider = FakeProvider()
        runner(api, provider).reconcileOrphans()

        assertEquals(1, provider.deletedIds.size, "dedup expected; got: ${provider.deletedIds}")
        assertEquals("drop-X", provider.deletedIds.single())
    }

    @Test
    fun `failed delete leaves entry in journal for next-startup retry`() = runTest {
        journal.appendProvisioned(VmInstance(dropletId = "drop-fail", ephemeralAgentId = "22222222-2222-2222-2222-222222222222", jobId = "job-Y"))
        val api = FakeApi()
        val provider = FakeProvider(alwaysFail = true)
        runner(api, provider).reconcileOrphans()

        assertEquals(3, provider.deleteAttempts, "expected 3 retry attempts")
        val replayed = journal.replay()
        assertEquals(1, replayed.size, "failed orphan must remain in journal")
        assertEquals("drop-fail", replayed[0].dropletId)
    }

    @Test
    fun `server query failure falls back to journal replay`() = runTest {
        journal.appendProvisioned(VmInstance("drop-J", "55555555-5555-5555-5555-555555555555", "job-J"))
        val api = FakeApi(throwOnList = true)
        val provider = FakeProvider()
        runner(api, provider).reconcileOrphans()

        assertContentEquals(listOf("drop-J"), provider.deletedIds)
    }

    @Test
    fun `server query cancellation is not treated as an empty orphan list`() = runTest {
        val api = FakeApi(listFailure = CancellationException("shutdown"))

        val failure = assertFailsWith<CancellationException> {
            runner(api, FakeProvider()).reconcileOrphans()
        }

        assertEquals("shutdown", failure.message)
    }

    @Test
    fun `deregistration failure does not resurrect a deleted VM`() = runTest {
        val agentId = Uuid.random()
        journal.appendProvisioned(VmInstance("drop-clean", agentId.toString(), "job-clean"))
        val api = FakeApi(deregisterFailure = IllegalStateException("server unavailable"))
        val provider = FakeProvider()

        runner(api, provider).reconcileOrphans()

        assertContentEquals(listOf("drop-clean"), provider.deletedIds)
        assertContentEquals(listOf(agentId), api.deregistered)
        assertTrue(journal.replay().isEmpty())
    }

    @Test
    fun `deregistration cancellation remains cancellation`() = runTest {
        val agentId = Uuid.random()
        journal.appendProvisioned(VmInstance("drop-cancel", agentId.toString(), "job-cancel"))
        val api = FakeApi(deregisterFailure = CancellationException("shutdown"))
        val provider = FakeProvider()

        val failure = assertFailsWith<CancellationException> {
            runner(api, provider).reconcileOrphans()
        }

        assertEquals("shutdown", failure.message)
        assertContentEquals(listOf("drop-cancel"), provider.deletedIds)
    }

    @Test
    fun `provider deletion cancellation stops orphan reconciliation`() = runTest {
        val agentId = Uuid.random()
        journal.appendProvisioned(VmInstance("drop-provider-cancel", agentId.toString(), "job-cancel"))
        val provider = object : CloudProvider {
            override val name: String = "cancelling"

            override suspend fun createVm(
                profile: VmProfile,
                name: String,
                userData: String,
            ): String = error("not used")

            override suspend fun deleteVm(vmId: String): Boolean {
                throw CancellationException("provider shutdown")
            }
        }

        val failure = assertFailsWith<CancellationException> {
            runner(FakeApi(), provider).reconcileOrphans()
        }

        assertEquals("provider shutdown", failure.message)
        assertEquals(1, journal.replay().size)
    }

    private fun makeAgent(
        id: Uuid = Uuid.random(),
        parentAgentId: Uuid?,
        instanceId: String?,
        ephemeral: Boolean = true,
    ) = ListPipelineAgentsData.Git.PipelineAgents(
        id = id,
        name = "ephemeral",
        labels = listOf("linux"),
        mode = GitAgentMode.RUNNER,
        status = GitAgentStatus.OFFLINE,
        ephemeral = ephemeral,
        jobId = null,
        parentAgentId = parentAgentId,
        instanceId = instanceId,
        lastHeartbeat = null,
        expiresAt = null,
        created = ZonedDateTime.now(),
    )

    private class FakeApi(
        private val agents: List<ListPipelineAgentsData.Git.PipelineAgents> = emptyList(),
        private val throwOnList: Boolean = false,
        private val listFailure: Exception? = null,
        private val deregisterFailure: Exception? = null,
    ) : CiApi(NetworkClient("http://localhost:0")) {

        val deregistered = mutableListOf<Uuid>()

        override suspend fun listAgents(status: GitAgentStatus?): List<ListPipelineAgentsData.Git.PipelineAgents> {
            listFailure?.let { throw it }
            if (throwOnList) throw RuntimeException("simulated server failure")
            return agents
        }

        override suspend fun deregisterAgent(id: Uuid): Boolean {
            deregistered.add(id)
            deregisterFailure?.let { throw it }
            return true
        }
    }

    private class FakeProvider(
        private val alwaysFail: Boolean = false,
    ) : CloudProvider {
        override val name = "fake"
        val deletedIds = mutableListOf<String>()
        var deleteAttempts = 0

        override suspend fun createVm(profile: VmProfile, name: String, userData: String): String =
            throw UnsupportedOperationException("not used in reconcile tests")

        override suspend fun deleteVm(vmId: String): Boolean {
            deleteAttempts++
            if (alwaysFail) return false
            deletedIds.add(vmId)
            return true
        }
    }
}
