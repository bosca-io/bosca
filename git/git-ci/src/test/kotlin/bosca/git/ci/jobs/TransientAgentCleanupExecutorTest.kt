@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.ci.jobs

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.model.AgentMode
import bosca.git.model.AgentStatus
import bosca.git.model.PipelineAgent
import bosca.git.model.PipelineJob
import bosca.git.model.PipelineRunStatus
import bosca.git.service.PipelineAgentService
import bosca.git.service.PipelineJobService
import bosca.git.service.PipelineRunService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class TransientAgentCleanupExecutorTest {

    private val agentService = mockk<PipelineAgentService>(relaxed = true)
    private val jobService = mockk<PipelineJobService>(relaxed = true)
    private val runService = mockk<PipelineRunService>(relaxed = true)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<PipelineAgentService>(singleton = true) { agentService }
        provides<PipelineJobService>(singleton = true) { jobService }
        provides<PipelineRunService>(singleton = true) { runService }
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    @Test
    fun `marks expired agents offline and fails their running jobs`() = runTest {
        val jobId = UUID.random()
        val dispatchId = UUID.random()
        val agent = testAgent(jobId = jobId)
        val job = testJob(
            id = jobId,
            agentId = agent.id,
            status = PipelineRunStatus.RUNNING,
            kubernetesDispatchId = dispatchId,
        )

        coEvery { agentService.findExpiredEphemeralAgents() } returns listOf(agent)
        coEvery { jobService.findById(jobId) } returns job

        executeCleanup()

        coVerify { agentService.updateStatus(agent.id, AgentStatus.OFFLINE) }
        coVerify { jobService.cancelKubernetesDispatch(job) }
        coVerify { jobService.updateStatus(jobId, PipelineRunStatus.FAILURE, any()) }
        coVerify { agentService.deregister(agent.id) }
    }

    @Test
    fun `skips job update when job already finished`() = runTest {
        val jobId = UUID.random()
        val agent = testAgent(jobId = jobId)
        val job = testJob(id = jobId, agentId = agent.id, status = PipelineRunStatus.SUCCESS)

        coEvery { agentService.findExpiredEphemeralAgents() } returns listOf(agent)
        coEvery { jobService.findById(jobId) } returns job

        executeCleanup()

        coVerify { agentService.updateStatus(agent.id, AgentStatus.OFFLINE) }
        coVerify(exactly = 0) { jobService.updateStatus(any(), any()) }
        coVerify { agentService.deregister(agent.id) }
    }

    @Test
    fun `handles agent with no job ID`() = runTest {
        val agent = testAgent(jobId = null)

        coEvery { agentService.findExpiredEphemeralAgents() } returns listOf(agent)

        executeCleanup()

        coVerify { agentService.updateStatus(agent.id, AgentStatus.OFFLINE) }
        coVerify(exactly = 0) { jobService.findById(any()) }
        coVerify { agentService.deregister(agent.id) }
    }

    @Test
    fun `handles job not found in database`() = runTest {
        val jobId = UUID.random()
        val agent = testAgent(jobId = jobId)

        coEvery { agentService.findExpiredEphemeralAgents() } returns listOf(agent)
        coEvery { jobService.findById(jobId) } returns null

        executeCleanup()

        coVerify { agentService.updateStatus(agent.id, AgentStatus.OFFLINE) }
        coVerify(exactly = 0) { jobService.updateStatus(any(), any()) }
        coVerify { agentService.deregister(agent.id) }
    }

    @Test
    fun `processes multiple expired agents`() = runTest {
        val agent1 = testAgent(jobId = UUID.random())
        val agent2 = testAgent(jobId = UUID.random())

        coEvery { agentService.findExpiredEphemeralAgents() } returns listOf(agent1, agent2)
        coEvery { jobService.findById(any()) } returns null

        executeCleanup()

        coVerify(exactly = 2) { agentService.updateStatus(any(), AgentStatus.OFFLINE) }
        coVerify(exactly = 2) { agentService.deregister(any()) }
    }

    @Test
    fun `does nothing when no expired agents`() = runTest {
        coEvery { agentService.findExpiredEphemeralAgents() } returns emptyList()

        executeCleanup()

        coVerify(exactly = 0) { agentService.updateStatus(any(), any()) }
        coVerify(exactly = 0) { agentService.deregister(any()) }
    }

    @Test
    fun `fails an assigned queued job when its worker credential expires before startup`() = runTest {
        val jobId = UUID.random()
        val agent = testAgent(jobId = jobId)
        val job = testJob(
            id = jobId,
            agentId = agent.id,
            status = PipelineRunStatus.QUEUED,
            kubernetesDispatchId = UUID.random(),
        )

        coEvery { agentService.findExpiredEphemeralAgents() } returns listOf(agent)
        coEvery { jobService.findById(jobId) } returns job

        executeCleanup()

        coVerify { jobService.cancelKubernetesDispatch(job) }
        coVerify { jobService.updateStatus(job.id, PipelineRunStatus.FAILURE, any()) }
    }

    @Test
    fun `does not fail a newer attempt that no longer belongs to the expired agent`() = runTest {
        val jobId = UUID.random()
        val expiredAgent = testAgent(jobId = jobId)
        val currentAgentId = UUID.random()
        val job = testJob(
            id = jobId,
            agentId = currentAgentId,
            status = PipelineRunStatus.RUNNING,
        )
        coEvery { agentService.findExpiredEphemeralAgents() } returns listOf(expiredAgent)
        coEvery { jobService.findById(jobId) } returns job

        executeCleanup()

        coVerify(exactly = 0) { jobService.updateStatus(any(), any(), any()) }
        coVerify { agentService.deregister(expiredAgent.id) }
    }

    @Test
    fun `marks stale persistent agents offline`() = runTest {
        val agent = testPersistentAgent(status = AgentStatus.ONLINE)

        coEvery { agentService.findExpiredEphemeralAgents() } returns emptyList()
        coEvery { agentService.findStalePersistentAgents() } returns listOf(agent)

        executeCleanup()

        coVerify { agentService.updateStatus(agent.id, AgentStatus.OFFLINE) }
        coVerify(exactly = 0) { agentService.deregister(agent.id) }
    }

    @Test
    fun `marks multiple stale persistent agents offline`() = runTest {
        val agent1 = testPersistentAgent(status = AgentStatus.ONLINE)
        val agent2 = testPersistentAgent(status = AgentStatus.BUSY)

        coEvery { agentService.findExpiredEphemeralAgents() } returns emptyList()
        coEvery { agentService.findStalePersistentAgents() } returns listOf(agent1, agent2)

        executeCleanup()

        coVerify { agentService.updateStatus(agent1.id, AgentStatus.OFFLINE) }
        coVerify { agentService.updateStatus(agent2.id, AgentStatus.OFFLINE) }
    }

    @Test
    fun `does nothing when no stale persistent agents`() = runTest {
        coEvery { agentService.findExpiredEphemeralAgents() } returns emptyList()
        coEvery { agentService.findStalePersistentAgents() } returns emptyList()

        executeCleanup()

        coVerify(exactly = 0) { agentService.updateStatus(any(), any()) }
    }

    private suspend fun executeCleanup() {
        TransientAgentCleanupExecutor().execute()
    }

    private fun testAgent(
        jobId: UUID? = UUID.random()
    ) = PipelineAgent(
        id = UUID.random(),
        name = "ephemeral-${UUID.random()}",
        labels = listOf("linux"),
        mode = AgentMode.RUNNER,
        status = AgentStatus.BUSY,
        ephemeral = true,
        jobId = jobId,
        parentAgentId = UUID.random(),
        tokenHash = "hash"
    )

    private fun testPersistentAgent(
        status: AgentStatus = AgentStatus.ONLINE
    ) = PipelineAgent(
        id = UUID.random(),
        name = "persistent-${UUID.random()}",
        labels = listOf("linux"),
        mode = AgentMode.RUNNER,
        status = status,
        ephemeral = false,
        tokenHash = "hash"
    )

    private fun testJob(
        id: UUID = UUID.random(),
        agentId: UUID? = null,
        status: PipelineRunStatus = PipelineRunStatus.RUNNING,
        kubernetesDispatchId: UUID? = null,
    ) = PipelineJob(
        id = id,
        pipelineRunId = UUID.random(),
        name = "build",
        status = status,
        runnerLabel = "linux",
        agentId = agentId,
        kubernetesDispatchId = kubernetesDispatchId,
    )
}
