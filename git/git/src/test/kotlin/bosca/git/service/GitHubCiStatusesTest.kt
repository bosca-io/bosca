package bosca.git.service

import bosca.git.model.*
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class GitHubCiStatusesTest {
    private val runs = mockk<PipelineRunService>()
    private val pipelines = mockk<PipelineService>()
    private val statuses = GitHubCiStatuses(runs, pipelines)
    private val repositoryId = UUID.random()
    private val sha = "a".repeat(40)
    private val file = ".bosca/pipelines/test.yaml"
    private val run = PipelineRun(id = UUID.random(), pipelineId = UUID.random(), repositoryId = repositoryId,
        commitSha = sha, ref = "refs/heads/feature", triggerType = PipelineTriggerType.PUSH, number = 1)
    private val evaluation = PipelineEvaluation(UUID.random(), repositoryId, sha, run.ref,
        completed = true, selections = listOf(PipelineSelection(file, run.id)))

    @BeforeTest fun setup() {
        coEvery { pipelines.findEvaluationsByCommit(repositoryId, sha) } returns listOf(evaluation)
        coEvery { runs.findLatestAttempt(run.id) } returns run
    }

    @Test fun `multiple files wait for all selected work and report intentional skips`() = runTest {
        val second = run.copy(id = UUID.random(), pipelineId = UUID.random(), status = PipelineRunStatus.SUCCESS)
        coEvery { pipelines.findEvaluationsByCommit(repositoryId, sha) } returns listOf(evaluation.copy(selections = listOf(
            PipelineSelection(file, run.id), PipelineSelection(".bosca/pipelines/lint.yml", second.id),
            PipelineSelection(".bosca/pipelines/deploy.yaml"),
        )))
        coEvery { runs.findLatestAttempt(second.id) } returns second
        val pending = statuses.forCommit(repositoryId, sha).associateBy { it.context }
        assertEquals("pending", pending.getValue("bosca/ci").state)
        assertEquals("success", pending.getValue("bosca/ci/lint.yml").state)
        assertEquals("Not applicable to this push", pending.getValue("bosca/ci/deploy.yaml").description)
        for ((state, expected) in listOf(PipelineRunStatus.SUCCESS to "success", PipelineRunStatus.FAILURE to "failure",
            PipelineRunStatus.RUNNING to "pending", PipelineRunStatus.CANCELLED to "failure", PipelineRunStatus.SKIPPED to "success")) {
            coEvery { runs.findLatestAttempt(run.id) } returns run.copy(status = state)
            assertEquals(expected, statuses.forCommit(repositoryId, sha).first().state)
        }
    }

    @Test fun `no applicable work passes only after a complete valid evaluation`() = runTest {
        for (selections in listOf(emptyList(), listOf(PipelineSelection(file)))) {
            coEvery { pipelines.findEvaluationsByCommit(repositoryId, sha) } returns listOf(evaluation.copy(selections = selections))
            val result = statuses.forCommit(repositoryId, sha).first()
            assertEquals("success", result.state)
            assertEquals("No CI pipelines apply to this commit", result.description)
            coEvery { pipelines.findEvaluationsByCommit(repositoryId, sha) } returns listOf(evaluation.copy(selections = selections, completed = false))
            assertEquals("pending", statuses.forCommit(repositoryId, sha).first().state)
            coEvery { pipelines.findEvaluationsByCommit(repositoryId, sha) } returns listOf(evaluation.copy(selections = selections, error = "invalid YAML"))
            assertEquals("error", statuses.forCommit(repositoryId, sha).first().state)
        }
        coEvery { pipelines.findEvaluationsByCommit(repositoryId, sha) } returns emptyList()
        assertTrue(statuses.forCommit(repositoryId, sha).isEmpty())
        coEvery { pipelines.findEvaluationsByCommit(repositoryId, sha) } returns listOf(evaluation.copy(ref = "refs/tags/1.0"))
        assertTrue(statuses.forCommit(repositoryId, sha).isEmpty())
    }

    @Test fun `another branch skip or manual success cannot clear a failed selected run`() = runTest {
        coEvery { runs.findLatestAttempt(run.id) } returns run.copy(status = PipelineRunStatus.FAILURE)
        coEvery { pipelines.findEvaluationsByCommit(repositoryId, sha) } returns listOf(evaluation,
            evaluation.copy(id = UUID.random(), ref = "refs/heads/main", selections = listOf(PipelineSelection(file))))
        val manual = run.copy(id = UUID.random(), triggerType = PipelineTriggerType.MANUAL, number = 2, status = PipelineRunStatus.SUCCESS)
        coEvery { runs.findById(manual.id) } returns manual
        coEvery { runs.findLatestAttempt(manual.id) } returns manual
        coEvery { pipelines.findById(run.pipelineId) } returns Pipeline(id = run.pipelineId, repositoryId = repositoryId,
            filePath = file, name = "Tests", configHash = "hash")
        val result = assertNotNull(statuses.forRun(PipelineEvent(repositoryId, manual.id, run.pipelineId, manual.status, manual.modified))).second.associateBy { it.context }
        assertEquals("success", result.getValue("bosca/run/test.yaml/manual/2").state)
        assertEquals("failure", result.getValue("bosca/ci/test.yaml").state)
        assertEquals("failure", result.getValue("bosca/ci").state)
        coEvery { runs.findLatestAttempt(run.id) } returns run.copy(id = UUID.random(), rerunOf = run.id, number = 3, status = PipelineRunStatus.SUCCESS)
        assertEquals("success", statuses.forCommit(repositoryId, sha).first().state)
    }

    @Test fun `a fresh evaluation recovers errors but a later skip retains failed selected work`() = runTest {
        coEvery { pipelines.findEvaluationsByCommit(repositoryId, sha) } returns listOf(
            evaluation.copy(error = "Execution permission denied", selections = emptyList()),
            evaluation.copy(id = UUID.random(), selections = listOf(PipelineSelection(file))),
        )
        assertEquals("success", statuses.forCommit(repositoryId, sha).first().state)
        coEvery { runs.findLatestAttempt(run.id) } returns run.copy(status = PipelineRunStatus.FAILURE)
        coEvery { pipelines.findEvaluationsByCommit(repositoryId, sha) } returns listOf(
            evaluation, evaluation.copy(id = UUID.random(), selections = listOf(PipelineSelection(file))),
        )
        assertEquals("failure", statuses.forCommit(repositoryId, sha).first().state)
    }

    @Test fun `a new push on the same ref replaces cancelled work but a skip does not`() = runTest {
        val replacement = run.copy(id = UUID.random(), number = 2, status = PipelineRunStatus.SUCCESS)
        coEvery { runs.findLatestAttempt(run.id) } returns run.copy(status = PipelineRunStatus.CANCELLED)
        coEvery { runs.findLatestAttempt(replacement.id) } returns replacement
        assertEquals("failure", statuses.forCommit(repositoryId, sha).first().state)
        assertEquals("Required CI was cancelled before completion", statuses.forCommit(repositoryId, sha).first().description)
        coEvery { pipelines.findEvaluationsByCommit(repositoryId, sha) } returns listOf(evaluation,
            evaluation.copy(id = UUID.random(), selections = listOf(PipelineSelection(file, replacement.id))))
        assertEquals("success", statuses.forCommit(repositoryId, sha).first().state)
        coEvery { pipelines.findEvaluationsByCommit(repositoryId, sha) } returns listOf(evaluation,
            evaluation.copy(id = UUID.random(), selections = listOf(PipelineSelection(file))))
        assertEquals("failure", statuses.forCommit(repositoryId, sha).first().state)
        coEvery { pipelines.findEvaluationsByCommit(repositoryId, sha) } returns listOf(evaluation,
            evaluation.copy(id = UUID.random(), ref = "refs/heads/other", selections = listOf(PipelineSelection(file, replacement.id))))
        assertEquals("failure", statuses.forCommit(repositoryId, sha).first().state)
    }

    @Test fun `missing selected run fails closed and unrelated records are rejected`() = runTest {
        coEvery { runs.findLatestAttempt(run.id) } returns null
        assertEquals("error", statuses.forCommit(repositoryId, sha).first().state)
        coEvery { runs.findLatestAttempt(run.id) } returns run.copy(commitSha = "b".repeat(40))
        assertFailsWith<IllegalArgumentException> { statuses.forCommit(repositoryId, sha) }
        coEvery { runs.findLatestAttempt(run.id) } returns run.copy(repositoryId = UUID.random())
        assertFailsWith<IllegalArgumentException> { statuses.forCommit(repositoryId, sha) }
    }

    @Test fun `incomplete selection on another ref cannot expose an old successful file check`() = runTest {
        coEvery { runs.findLatestAttempt(run.id) } returns run.copy(status = PipelineRunStatus.SUCCESS)
        coEvery { pipelines.findEvaluationsByCommit(repositoryId, sha) } returns listOf(evaluation,
            evaluation.copy(id = UUID.random(), completed = false, selections = emptyList()))
        assertTrue(statuses.forCommit(repositoryId, sha).all { it.state == "pending" })
    }

    @Test fun `rerun event keeps the original check identity and reads its latest attempt`() = runTest {
        val retry = run.copy(id = UUID.random(), rerunOf = run.id, number = 3, status = PipelineRunStatus.SUCCESS)
        coEvery { runs.findById(retry.id) } returns retry
        coEvery { runs.findById(run.id) } returns run
        coEvery { runs.findLatestAttempt(run.id) } returns retry
        coEvery { pipelines.findById(run.pipelineId) } returns Pipeline(id = run.pipelineId, repositoryId = repositoryId,
            filePath = file, name = "Renamed", configHash = "hash")
        val result = assertNotNull(statuses.forRun(PipelineEvent(repositoryId, retry.id, run.pipelineId, PipelineRunStatus.QUEUED, retry.modified)))
        assertEquals(sha, result.first)
        assertEquals("bosca/run/test.yaml/push/1", result.second.first().context)
        assertTrue(result.second.all { it.state == "success" })
        coEvery { runs.findById(run.id) } returns null
        assertNull(statuses.forRun(PipelineEvent(repositoryId, retry.id, run.pipelineId, retry.status, retry.modified)))
    }
}
