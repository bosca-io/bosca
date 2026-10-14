package bosca.git.ui

import bosca.git.ui.graphql.GitPipelineRunStatus
import bosca.git.ui.graphql.GitUiRunData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class PipelineRunModelTest {
    @Test
    fun `compiled run renders compact steps without loading logs`() = kotlinx.coroutines.test.runTest {
        val model = PipelineRunModel(run(), true, true, true, repositoryName = "Repo", pipelineName = "Build")
        val ctx = bosca.bml.render.RenderContext()
        bml.generated.GitPipelineRunComponent.render(ctx, mapOf("initial" to model)) {}
        val html = ctx.writer.toString()
        assertTrue("Build · Run #3" in html, html)
        assertFalse("data-log-step=" in html, html)
    }

    private fun run(): GitUiRunData.Git.PipelineRun =
        Json.decodeFromString(GitUiRunData.Git.PipelineRun.serializer(),
            requireNotNull(javaClass.getResource("/fixtures/run.json")).readText())

    @Test
    fun `first failed step expands once and manual selection wins on later refreshes`() {
        val run = run()
        val job = run.jobs.single()
        val step = job.steps.single().copy(status = GitPipelineRunStatus.FAILURE)
        val failed = run.copy(status = GitPipelineRunStatus.FAILURE,
            jobs = listOf(job.copy(steps = listOf(step, step.copy(id = "second", ordinal = 1)))))
        val model = PipelineRunModel(failed, true, true, true)
        model.expandFailure()
        assertEquals(step.id, model.expandedStepId)
        model.toggleStepLogs(step.id)
        model.expandFailure()
        assertNull(model.expandedStepId)
        model.toggleStepLogs("second")
        model.expandFailure()
        assertEquals("second", model.expandedStepId)
        assertFailsWith<IllegalArgumentException> { model.toggleStepLogs("foreign-step") }
    }

    @Test
    fun `active run stays collapsed until selected or failed`() {
        val run = run()
        val model = PipelineRunModel(run, true, true, true)
        model.expandFailure()
        assertNull(model.expandedStepId)
        model.run = run.copy(status = GitPipelineRunStatus.FAILURE, jobs = run.jobs.map { job ->
            job.copy(steps = job.steps.map { it.copy(status = GitPipelineRunStatus.FAILURE) })
        })
        model.expandFailure()
        assertEquals("step", model.expandedStepId)
    }

    @Test
    fun `rerunning a job requires a terminal failed run and execution permission`() {
        val run = run()
        val job = run.jobs.single().copy(status = GitPipelineRunStatus.FAILURE)
        val model = PipelineRunModel(run.copy(jobs = listOf(job)), true, true, true)
        assertFalse(model.canRerunJob(job))
        assertEquals(0, model.rerunnableJobCount)
        model.run = model.run.copy(status = GitPipelineRunStatus.FAILURE)
        assertTrue(model.canRerunJob(job))
        assertEquals(1, model.rerunnableJobCount)
        assertFalse(PipelineRunModel(model.run, false, true, true).canRerunJob(job))
        assertFalse(model.canRerunJob(job.copy(status = GitPipelineRunStatus.SUCCESS)))
    }

    @Test
    fun `duration labels and requirement details match Studio`() {
        assertEquals("—", pipelineDuration(null))
        assertEquals("7s", pipelineDuration(7))
        assertEquals("1m 5s", pipelineDuration(65))
        assertEquals("2h 3m", pipelineDuration(7384))
        assertEquals("1m 5s", pipelineElapsed("2026-10-09T00:00:00Z", "2026-10-09T00:01:05Z"))
        assertEquals("—", pipelineElapsed(null, null))
        val job = run().jobs.single()
        assertEquals(listOf("io.bosca / core:1.2.3 (maven)"), artifactRequirementLabels(job.requirements))
        assertEquals(listOf("platform / Publish @ refs/heads/main"), pipelineRequirementLabels(job.pipelineRequirements))
        assertEquals(listOf("unknown coordinate (artifact)"), artifactRequirementLabels(Json.parseToJsonElement("[null, 1, {}]")))
        assertEquals(emptyList(), pipelineMatrixLabels(Json.parseToJsonElement("{}")))
        assertEquals(listOf("os: linux"), pipelineMatrixLabels(Json.parseToJsonElement("""{"os":"linux"}""")))
    }
}
