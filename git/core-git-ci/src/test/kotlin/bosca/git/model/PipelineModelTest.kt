package bosca.git.model

import bosca.serialization.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PipelineModelTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `Pipeline has sensible defaults`() {
        val pipeline = Pipeline(
            repositoryId = UUID.random(),
            filePath = ".bosca/pipelines/build.yaml",
            name = "Build",
            configHash = "abc123"
        )
        assertEquals(kotlinx.serialization.json.JsonArray(emptyList()), pipeline.triggers)
        assertEquals(null, pipeline.concurrency)
    }

    @Test
    fun `PipelineRun has sensible defaults`() {
        val run = PipelineRun(
            pipelineId = UUID.random(),
            repositoryId = UUID.random(),
            commitSha = "abc123",
            ref = "refs/heads/main",
            triggerType = PipelineTriggerType.PUSH
        )
        assertEquals(PipelineRunStatus.QUEUED, run.status)
        assertEquals(0, run.number)
        assertEquals(null, run.triggeredBy)
        assertEquals(null, run.started)
        assertEquals(null, run.finished)
        assertEquals(null, run.concurrencyGroup)
    }

    @Test
    fun `PipelineJob has sensible defaults`() {
        val job = PipelineJob(
            pipelineRunId = UUID.random(),
            name = "build"
        )
        assertEquals(PipelineRunStatus.QUEUED, job.status)
        assertEquals("default", job.runnerLabel)
        assertEquals(null, job.agentId)
        assertEquals(kotlinx.serialization.json.JsonObject(emptyMap()), job.matrixValues)
        assertTrue(job.dependsOn.isEmpty())
    }

    @Test
    fun `PipelineStep has sensible defaults`() {
        val step = PipelineStep(
            pipelineJobId = UUID.random(),
            name = "Checkout",
            ordinal = 0
        )
        assertEquals(PipelineRunStatus.QUEUED, step.status)
        assertEquals(null, step.exitCode)
        assertEquals(null, step.started)
        assertEquals(null, step.finished)
    }

    @Test
    fun `PipelineAgent has sensible defaults`() {
        val agent = PipelineAgent(
            name = "agent-1",
            tokenHash = "hash"
        )
        assertEquals(listOf("default"), agent.labels)
        assertEquals(AgentMode.RUNNER, agent.mode)
        assertEquals(AgentStatus.OFFLINE, agent.status)
        assertEquals(false, agent.ephemeral)
        assertEquals(null, agent.jobId)
        assertEquals(null, agent.parentAgentId)
        assertEquals(null, agent.instanceId)
        assertEquals(null, agent.expiresAt)
    }

    @Test
    fun `PipelineAgent ephemeral configuration`() {
        val agent = PipelineAgent(
            name = "ephemeral-1",
            tokenHash = "hash",
            ephemeral = true,
            jobId = UUID.random(),
            parentAgentId = UUID.random(),
            instanceId = "droplet-12345"
        )
        assertTrue(agent.ephemeral)
        assertEquals("droplet-12345", agent.instanceId)
    }

    @Test
    fun `PipelineRunStatus has all expected values`() {
        val statuses = PipelineRunStatus.entries
        assertEquals(6, statuses.size)
        assertTrue(statuses.contains(PipelineRunStatus.QUEUED))
        assertTrue(statuses.contains(PipelineRunStatus.RUNNING))
        assertTrue(statuses.contains(PipelineRunStatus.SUCCESS))
        assertTrue(statuses.contains(PipelineRunStatus.FAILURE))
        assertTrue(statuses.contains(PipelineRunStatus.CANCELLED))
        assertTrue(statuses.contains(PipelineRunStatus.SKIPPED))
    }

    @Test
    fun `PipelineTriggerType has all expected values`() {
        val types = PipelineTriggerType.entries
        assertEquals(7, types.size)
        assertTrue(types.contains(PipelineTriggerType.PUSH))
        assertTrue(types.contains(PipelineTriggerType.PULL_REQUEST))
        assertTrue(types.contains(PipelineTriggerType.TAG))
        assertTrue(types.contains(PipelineTriggerType.MANUAL))
        assertTrue(types.contains(PipelineTriggerType.SCHEDULE))
        assertTrue(types.contains(PipelineTriggerType.RELEASE))
        assertTrue(types.contains(PipelineTriggerType.PROMOTION))
    }

    @Test
    fun `AgentMode has RUNNER and ORCHESTRATOR`() {
        assertEquals(2, AgentMode.entries.size)
        assertTrue(AgentMode.entries.contains(AgentMode.RUNNER))
        assertTrue(AgentMode.entries.contains(AgentMode.ORCHESTRATOR))
    }

    @Test
    fun `AgentStatus has all expected values`() {
        assertEquals(4, AgentStatus.entries.size)
        assertTrue(AgentStatus.entries.contains(AgentStatus.ONLINE))
        assertTrue(AgentStatus.entries.contains(AgentStatus.OFFLINE))
        assertTrue(AgentStatus.entries.contains(AgentStatus.BUSY))
        assertTrue(AgentStatus.entries.contains(AgentStatus.DRAINING))
    }

    @Test
    fun `PipelineTrigger has sensible defaults`() {
        val trigger = PipelineTrigger(type = PipelineTriggerType.PUSH)
        assertTrue(trigger.branches.isEmpty())
        assertTrue(trigger.paths.isEmpty())
        assertTrue(trigger.pathsIgnore.isEmpty())
        assertTrue(trigger.tags.isEmpty())
        assertEquals(null, trigger.cron)
    }

    @Test
    fun `PipelineConcurrency stores group and cancel flag`() {
        val concurrency = PipelineConcurrency(group = "ci-main", cancelInProgress = true)
        assertEquals("ci-main", concurrency.group)
        assertTrue(concurrency.cancelInProgress)
    }

    @Test
    fun `PipelineConcurrency defaults cancel to false`() {
        val concurrency = PipelineConcurrency(group = "ci-main")
        assertEquals(false, concurrency.cancelInProgress)
    }

    @Test
    fun `PipelineSecret stores encrypted value`() {
        val secret = PipelineSecret(
            repositoryId = UUID.random(),
            name = "API_KEY",
            encryptedValue = "encrypted-data"
        )
        assertEquals("API_KEY", secret.name)
        assertEquals("encrypted-data", secret.encryptedValue)
    }
}
