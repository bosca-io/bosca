@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.ci.service

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.model.ArtifactDefinition
import bosca.git.model.PipelineJob
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineRunStatus
import bosca.git.model.PipelineTriggerType
import bosca.git.service.PipelineAgentService
import bosca.git.service.PipelineJobService
import bosca.git.service.PipelineRunService
import bosca.git.service.ProducedArtifactVerifier
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * [PipelineRunFinalizer]'s declared-artifact verification: a job whose steps all
 * succeeded but whose DECLARED artifacts never landed in the registry is failed HERE (with the missing
 * coordinates named), so the break surfaces at the build — not downstream in a release relay that
 * simply skips a channel with nothing to deploy.
 */
class PipelineRunFinalizerTest {

    private val jobService = mockk<PipelineJobService>(relaxed = true)
    private val runService = mockk<PipelineRunService>(relaxed = true)
    private val agentService = mockk<PipelineAgentService>(relaxed = true)
    private val finalizer = PipelineRunFinalizer(jobService, runService, agentService)

    private val runId = UUID.random()
    private val jobId = UUID.random()
    private val json = Json

    private val declared = listOf(
        ArtifactDefinition("docker", "bosca-docker", "bosca:6.0.5"),
        ArtifactDefinition("helm-values", "bosca-helm", "bosca-values:6.0.5", environments = listOf("development")),
    )

    private fun job(artifacts: List<ArtifactDefinition> = emptyList(), status: PipelineRunStatus = PipelineRunStatus.SUCCESS) = PipelineJob(
        id = jobId,
        pipelineRunId = runId,
        name = "build-and-publish",
        status = status,
        artifacts = json.encodeToJsonElement(ListSerializer(ArtifactDefinition.serializer()), artifacts),
    )

    private fun run() = PipelineRun(
        id = runId, pipelineId = UUID.random(), repositoryId = UUID.random(),
        commitSha = "abc", ref = "refs/tags/v6.0.5", triggerType = PipelineTriggerType.TAG,
        status = PipelineRunStatus.RUNNING, number = 1,
    )

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { json }
        coEvery { runService.findById(runId) } returns run()
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    @Test
    fun `fails the job naming the artifacts that never landed in the registry`() = runTest {
        provides<ProducedArtifactVerifier> {
            object : ProducedArtifactVerifier {
                override suspend fun missing(artifacts: List<ArtifactDefinition>) = artifacts.take(1)
            }
        }
        coEvery { jobService.findById(jobId) } returns job(declared)
        coEvery { jobService.findByRun(runId) } returns listOf(job(declared, PipelineRunStatus.FAILURE))

        finalizer.finalizeJob(jobId, PipelineRunStatus.SUCCESS, releaseAgent = false)

        coVerify(exactly = 1) {
            jobService.updateStatus(jobId, PipelineRunStatus.FAILURE, match { "bosca-docker/bosca:6.0.5" in it })
        }
        // The aggregate run status reflects the verification failure.
        coVerify(exactly = 1) { runService.updateStatus(runId, PipelineRunStatus.FAILURE) }
    }

    @Test
    fun `a job whose declared artifacts all landed finalizes SUCCESS`() = runTest {
        provides<ProducedArtifactVerifier> {
            object : ProducedArtifactVerifier {
                override suspend fun missing(artifacts: List<ArtifactDefinition>) = emptyList<ArtifactDefinition>()
            }
        }
        coEvery { jobService.findById(jobId) } returns job(declared)
        coEvery { jobService.findByRun(runId) } returns listOf(job(declared))

        finalizer.finalizeJob(jobId, PipelineRunStatus.SUCCESS, releaseAgent = false)

        coVerify(exactly = 0) { jobService.updateStatus(any(), PipelineRunStatus.FAILURE, any()) }
        coVerify(exactly = 1) { runService.updateStatus(runId, PipelineRunStatus.SUCCESS) }
    }

    @Test
    fun `a job with no declared artifacts skips verification entirely`() = runTest {
        // No verifier registered at all — must not even be needed.
        coEvery { jobService.findById(jobId) } returns job()
        coEvery { jobService.findByRun(runId) } returns listOf(job())

        finalizer.finalizeJob(jobId, PipelineRunStatus.SUCCESS, releaseAgent = false)

        coVerify(exactly = 1) { runService.updateStatus(runId, PipelineRunStatus.SUCCESS) }
    }

    @Test
    fun `declared artifacts with NO verifier available fail the job — verification is mandatory`() = runTest {
        coEvery { jobService.findById(jobId) } returns job(declared)
        coEvery { jobService.findByRun(runId) } returns listOf(job(declared, PipelineRunStatus.FAILURE))

        finalizer.finalizeJob(jobId, PipelineRunStatus.SUCCESS, releaseAgent = false)

        coVerify(exactly = 1) {
            jobService.updateStatus(jobId, PipelineRunStatus.FAILURE, match { "no artifact verifier" in it })
        }
        coVerify(exactly = 1) { runService.updateStatus(runId, PipelineRunStatus.FAILURE) }
    }

    @Test
    fun `a verification error fails the job carrying the error`() = runTest {
        provides<ProducedArtifactVerifier> {
            object : ProducedArtifactVerifier {
                override suspend fun missing(artifacts: List<ArtifactDefinition>): List<ArtifactDefinition> =
                    error("registry unreachable")
            }
        }
        coEvery { jobService.findById(jobId) } returns job(declared)
        coEvery { jobService.findByRun(runId) } returns listOf(job(declared, PipelineRunStatus.FAILURE))

        finalizer.finalizeJob(jobId, PipelineRunStatus.SUCCESS, releaseAgent = false)

        coVerify(exactly = 1) {
            jobService.updateStatus(jobId, PipelineRunStatus.FAILURE, match { "registry unreachable" in it })
        }
        coVerify(exactly = 1) { runService.updateStatus(runId, PipelineRunStatus.FAILURE) }
    }
}
