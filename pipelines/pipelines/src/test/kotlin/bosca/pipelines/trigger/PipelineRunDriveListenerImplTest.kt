@file:OptIn(bosca.di.annotation.InternalDI::class, bosca.core.annotations.Internal::class)

package bosca.pipelines.trigger

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.pipelines.node.PipelineResumeCorrelation
import bosca.pipelines.service.PipelineRunService
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobExecutor
import bosca.sharedqueue.jobs.JobStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * [PipelineRunDriveListenerImpl]: a failed backing child resumes the run with the child's OWN error —
 * reduced to a human-readable message. The queue stores the child's full stack trace (right for job
 * history); the run error is read by a person, so only the first line survives, minus a bare
 * exception-class prefix.
 */
class PipelineRunDriveListenerImplTest {

    private val json = Json { serializersModule = SerializersModule { contextual(UUIDSerializer()) } }
    private val runService = mockk<PipelineRunService>(relaxed = true)
    private val listener = PipelineRunDriveListenerImpl(json)

    private val runId = UUID.random()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<PipelineRunService> { runService }
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private fun runJob(): Job = InternalJobConstructor(Json.parseToJsonElement("{}"), DriveDummyExecutor::class)

    private fun backingChild(): Job = InternalJobConstructor(Json.parseToJsonElement("{}"), DriveDummyExecutor::class).apply {
        setContext(json, PipelineResumeCorrelation(runId, "wait"))
    }

    @Test
    fun `a failed child resumes the run with the first line of its error, exception prefix stripped`() = runTest {
        val trace = "bosca.sharedqueue.jobs.FailException: git pipeline run 42 finished FAILURE — " +
            "build-and-publish: declared artifacts never landed in the registry: bosca-helm/bosca-values:6.0.8\n" +
            "\tat bosca.workops.jobs.WaitForGitPipelineExecutor.awaitTerminal(WaitForGitPipelineExecutor.kt:57)\n" +
            "\tat some.other.Frame(Frame.kt:1)"

        listener.onChildStatusChanged(runJob(), backingChild(), JobStatus.FAILED_AND_COMPLETE, trace)

        coVerify(exactly = 1) {
            runService.resume(
                runId, "wait", false,
                "git pipeline run 42 finished FAILURE — build-and-publish: declared artifacts never landed in the registry: bosca-helm/bosca-values:6.0.8",
                runJob = any(),
            )
        }
    }

    @Test
    fun `a failed child with no error falls back to naming the parked node`() = runTest {
        listener.onChildStatusChanged(runJob(), backingChild(), JobStatus.FAILED_AND_COMPLETE, null)

        coVerify(exactly = 1) {
            runService.resume(runId, "wait", false, "backing work for node 'wait' failed", runJob = any())
        }
    }

    @Test
    fun `a completed child resumes with no error`() = runTest {
        listener.onChildStatusChanged(runJob(), backingChild(), JobStatus.COMPLETE, null)

        coVerify(exactly = 1) { runService.resume(runId, "wait", true, null, runJob = any()) }
    }

    @Test
    fun `failed child errors preserve human text and ignore blank stack trace lines`() = runTest {
        val cases = listOf(
            "plain failure\nignored detail" to "plain failure",
            "Failure: useful detail" to "Failure: useful detail",
            "two words: useful detail" to "two words: useful detail",
            ": useful detail" to ": useful detail",
        )
        for ((error, expected) in cases) {
            listener.onChildStatusChanged(runJob(), backingChild(), JobStatus.FAILED_AND_COMPLETE, error)
            coVerify(exactly = 1) { runService.resume(runId, "wait", false, expected, runJob = any()) }
        }

        listener.onChildStatusChanged(runJob(), backingChild(), JobStatus.FAILED_AND_COMPLETE, "  \nignored")
        coVerify(exactly = 1) {
            runService.resume(runId, "wait", false, "backing work for node 'wait' failed", runJob = any())
        }
    }

    @Test
    fun `a child that is not a pipeline backing job is ignored`() = runTest {
        val foreign = InternalJobConstructor(Json.parseToJsonElement("{}"), DriveDummyExecutor::class)

        listener.onChildStatusChanged(runJob(), foreign, JobStatus.FAILED_AND_COMPLETE, "boom")

        coVerify(exactly = 0) { runService.resume(any(), any(), any(), any(), runJob = any()) }
    }
}

private class DriveDummyExecutor : JobExecutor {
    override suspend fun execute() {}
}
