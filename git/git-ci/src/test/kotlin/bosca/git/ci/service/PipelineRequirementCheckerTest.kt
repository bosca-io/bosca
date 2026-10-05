@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.ci.service

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.model.ArtifactRequirement
import bosca.git.model.CommitInfo
import bosca.git.model.Pipeline
import bosca.git.model.PipelineJob
import bosca.git.model.PipelineRequirement
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineRunStatus
import bosca.git.model.PipelineTriggerType
import bosca.git.model.Repository
import bosca.git.model.TagInfo
import bosca.git.service.PipelineAgentService
import bosca.git.service.PipelineJobService
import bosca.git.service.PipelineRunService
import bosca.git.service.RepositoryBrowseService
import bosca.git.service.RepositoryService
import bosca.git.service.RequiredArtifactVerifier
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * [PipelineRequirementChecker]: the requirement dispatch gate. A gated job becomes
 * claimable only when the registry holds every required artifact AND every required upstream
 * pipeline run has SUCCEEDED; a failed upstream fails the job immediately, naming the dependency;
 * a job past its deadline fails LOUDLY naming everything unmet; a missing artifact verifier fails
 * artifact-gated jobs immediately (a gate that cannot check cannot honestly dispatch) but leaves
 * pipeline-only gates untouched; a transient error retries on the next pass.
 */
class PipelineRequirementCheckerTest {

    private val jobService = mockk<PipelineJobService>(relaxed = true)
    private val runService = mockk<PipelineRunService>(relaxed = true)
    private val agentService = mockk<PipelineAgentService>(relaxed = true)
    private val pipelineService = mockk<bosca.git.service.PipelineService>(relaxed = true)
    private val repositoryService = mockk<RepositoryService>(relaxed = true)
    private val repositoryBrowseService = mockk<RepositoryBrowseService>(relaxed = true)
    private val verifier = mockk<RequiredArtifactVerifier>()
    private val json = Json
    private val checker = PipelineRequirementChecker(
        jobService, runService, agentService, pipelineService, repositoryService,
        repositoryBrowseService, json,
    )

    private val jobId = UUID.random()
    private val runId = UUID.random()

    private val requirement = ArtifactRequirement("maven", "bosca-maven", "io.bosca:core-content:6.0.9")

    private val upstreamRepoId = UUID.random()
    private val upstreamPipelineId = UUID.random()
    private val upstreamRepo = Repository(id = upstreamRepoId, slug = "workspace", name = "Workspace", ownerId = UUID.random())
    private val upstreamPipeline = Pipeline(
        id = upstreamPipelineId, repositoryId = upstreamRepoId,
        filePath = ".bosca/pipelines/release.yaml", name = "Bosca Release", configHash = "hash",
    )
    private val pipelineRequirement =
        PipelineRequirement(pipeline = "Bosca Release", repository = "acme/workspace", ref = "refs/tags/1.2.0")

    private fun gatedJob(
        requirements: List<ArtifactRequirement> = listOf(requirement),
        pipelineRequirements: List<PipelineRequirement> = emptyList(),
        deadline: OffsetDateTime? = OffsetDateTime.now().plusMinutes(30),
    ) = PipelineJob(
        id = jobId,
        pipelineRunId = runId,
        name = "build",
        status = PipelineRunStatus.QUEUED,
        requirements = json.encodeToJsonElement(ListSerializer(ArtifactRequirement.serializer()), requirements),
        pipelineRequirements = json.encodeToJsonElement(ListSerializer(PipelineRequirement.serializer()), pipelineRequirements),
        requirementsDeadline = deadline,
    )

    private fun upstreamRun(status: PipelineRunStatus, ref: String = "refs/tags/1.2.0") = PipelineRun(
        id = UUID.random(),
        pipelineId = upstreamPipelineId,
        repositoryId = upstreamRepoId,
        commitSha = "abc123",
        ref = ref,
        triggerType = PipelineTriggerType.TAG,
        status = status,
        number = 7,
    )

    private fun commit(sha: String, message: String) = CommitInfo(
        sha = sha,
        message = message,
        authorName = "Test",
        authorEmail = "test@bosca.io",
        authorDate = "2026-07-28T00:00:00Z",
        committerName = "Test",
        committerEmail = "test@bosca.io",
        committerDate = "2026-07-28T00:00:00Z",
    )

    /** The upstream resolves by `owner/slug`, its pipeline by name, and its latest run has [status]. */
    private fun stubUpstream(status: PipelineRunStatus?) {
        coEvery { repositoryService.findByOwnerAndSlug("acme", "workspace") } returns upstreamRepo
        coEvery { pipelineService.findByRepositoryAndName(upstreamRepoId, "Bosca Release") } returns upstreamPipeline
        coEvery { runService.findLatestByPipelineAndRef(upstreamPipelineId, "refs/tags/1.2.0") } returns
            status?.let { upstreamRun(it) }
    }

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<RequiredArtifactVerifier> { verifier }
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    @Test
    fun `a satisfied job is stamped dispatchable`() = runTest {
        coEvery { jobService.findAwaitingRequirements() } returns listOf(gatedJob())
        coEvery { verifier.unsatisfied(listOf(requirement)) } returns emptyList()

        checker.checkAwaiting()

        coVerify(exactly = 1) { jobService.markRequirementsSatisfied(jobId) }
        coVerify(exactly = 0) { jobService.updateStatus(any(), any(), any()) }
    }

    @Test
    fun `an unsatisfied job before its deadline keeps waiting`() = runTest {
        coEvery { jobService.findAwaitingRequirements() } returns listOf(gatedJob())
        coEvery { verifier.unsatisfied(listOf(requirement)) } returns listOf(requirement)

        checker.checkAwaiting()

        coVerify(exactly = 0) { jobService.markRequirementsSatisfied(any()) }
        coVerify(exactly = 0) { jobService.updateStatus(any(), any(), any()) }
    }

    @Test
    fun `an unsatisfied job past its deadline fails naming the unmet coordinates`() = runTest {
        coEvery { jobService.findAwaitingRequirements() } returns
            listOf(gatedJob(deadline = OffsetDateTime.now().minusMinutes(1)))
        coEvery { verifier.unsatisfied(listOf(requirement)) } returns listOf(requirement)

        checker.checkAwaiting()

        coVerify(exactly = 1) {
            jobService.updateStatus(
                jobId,
                PipelineRunStatus.FAILURE,
                match { "bosca-maven/io.bosca:core-content:6.0.9" in it.orEmpty() && "maven" in it.orEmpty() },
            )
        }
    }

    @Test
    fun `a missing verifier fails the gated job immediately`() = runTest {
        ProviderRegistry.clear() // no RequiredArtifactVerifier registered
        coEvery { jobService.findAwaitingRequirements() } returns listOf(gatedJob())

        checker.checkAwaiting()

        coVerify(exactly = 1) {
            jobService.updateStatus(jobId, PipelineRunStatus.FAILURE, match { "no artifact verifier" in it.orEmpty() })
        }
    }

    @Test
    fun `a transient verification error leaves the job waiting for the next pass`() = runTest {
        coEvery { jobService.findAwaitingRequirements() } returns listOf(gatedJob())
        coEvery { verifier.unsatisfied(any()) } throws RuntimeException("registry hiccup")

        checker.checkAwaiting()

        coVerify(exactly = 0) { jobService.markRequirementsSatisfied(any()) }
        coVerify(exactly = 0) { jobService.updateStatus(any(), any(), any()) }
    }

    @Test
    fun `checkRun evaluates only the run's gated jobs`() = runTest {
        coEvery { jobService.findAwaitingRequirementsByRun(runId) } returns listOf(gatedJob())
        coEvery { verifier.unsatisfied(listOf(requirement)) } returns emptyList()

        checker.checkRun(runId)

        coVerify(exactly = 1) { jobService.markRequirementsSatisfied(jobId) }
        coVerify(exactly = 0) { jobService.findAwaitingRequirements() }
    }

    // ── Pipeline requirements ────────────────────────────────────────────────────────

    @Test
    fun `a successful upstream run satisfies a pipeline requirement`() = runTest {
        coEvery { jobService.findAwaitingRequirements() } returns
            listOf(gatedJob(requirements = emptyList(), pipelineRequirements = listOf(pipelineRequirement)))
        stubUpstream(PipelineRunStatus.SUCCESS)

        checker.checkAwaiting()

        coVerify(exactly = 1) { jobService.markRequirementsSatisfied(jobId) }
        coVerify(exactly = 0) { jobService.updateStatus(any(), any(), any()) }
    }

    @Test
    fun `a marked future patch accepts its earlier patch pipeline and artifacts`() = runTest {
        val requiringRepositoryId = UUID.random()
        val currentCommit = "current-commit"
        val intermediateCommit = "intermediate-commit"
        val baseCommit = "base-commit"
        val patchArtifact =
            ArtifactRequirement("maven", "bosca-maven", "io.bosca:core-content:1.2.10")
        val patchPipeline =
            PipelineRequirement("Bosca Release", "acme/workspace", "refs/tags/1.2.10")
        val job = gatedJob(
            requirements = listOf(patchArtifact),
            pipelineRequirements = listOf(patchPipeline),
        )
        coEvery { jobService.findAwaitingRequirements() } returns listOf(job)
        coEvery { runService.findById(runId) } returns PipelineRun(
            id = runId,
            pipelineId = UUID.random(),
            repositoryId = requiringRepositoryId,
            commitSha = currentCommit,
            ref = "refs/tags/1.2.10",
            triggerType = PipelineTriggerType.TAG,
            status = PipelineRunStatus.QUEUED,
            number = 3,
        )
        coEvery { repositoryBrowseService.listTags(requiringRepositoryId) } returns listOf(
            TagInfo(
                name = "1.2.10",
                sha = "current-tag",
                targetSha = currentCommit,
                message = "Release 1.2.10\n\nPatch for [1.2.0]",
                isAnnotated = true,
            ),
            TagInfo(name = "1.2.7", sha = intermediateCommit),
            TagInfo(name = "1.2.0", sha = baseCommit),
        )
        coEvery {
            repositoryBrowseService.listCommits(requiringRepositoryId, currentCommit, null, 256, 0)
        } returns listOf(
            commit(currentCommit, "Fix release packaging"),
            commit(intermediateCommit, "Release 1.2.7"),
            commit(baseCommit, "Release 1.2.0"),
        )
        coEvery { repositoryService.findByOwnerAndSlug("acme", "workspace") } returns upstreamRepo
        coEvery { pipelineService.findByRepositoryAndName(upstreamRepoId, "Bosca Release") } returns upstreamPipeline
        coEvery {
            runService.findLatestByPipelineAndRef(upstreamPipelineId, "refs/tags/1.2.10")
        } returns null
        coEvery {
            runService.findLatestByPipelineAndRef(upstreamPipelineId, "refs/tags/1.2.0")
        } returns upstreamRun(PipelineRunStatus.SUCCESS, "refs/tags/1.2.0")
        coEvery { verifier.unsatisfied(listOf(patchArtifact)) } returns listOf(patchArtifact)
        coEvery {
            verifier.unsatisfied(match { it.single().coordinate == "io.bosca:core-content:1.2.0" })
        } returns emptyList()

        checker.checkAwaiting()

        coVerify(exactly = 1) {
            runService.findLatestByPipelineAndRef(upstreamPipelineId, "refs/tags/1.2.0")
        }
        coVerify(exactly = 1) {
            verifier.unsatisfied(match { it.single().coordinate == "io.bosca:core-content:1.2.0" })
        }
        coVerify(exactly = 1) { jobService.markRequirementsSatisfied(jobId) }
    }

    @Test
    fun `an exact failed patch pipeline is not bypassed by an earlier successful run`() = runTest {
        val requiringRepositoryId = UUID.random()
        val currentCommit = "current-commit"
        val baseCommit = "base-commit"
        val patchPipeline =
            PipelineRequirement("Bosca Release", "acme/workspace", "refs/tags/1.2.10")
        coEvery { jobService.findAwaitingRequirements() } returns listOf(
            gatedJob(requirements = emptyList(), pipelineRequirements = listOf(patchPipeline))
        )
        coEvery { runService.findById(runId) } returns PipelineRun(
            id = runId,
            pipelineId = UUID.random(),
            repositoryId = requiringRepositoryId,
            commitSha = currentCommit,
            ref = "refs/tags/1.2.10",
            triggerType = PipelineTriggerType.TAG,
            status = PipelineRunStatus.QUEUED,
            number = 3,
        )
        coEvery { repositoryBrowseService.listTags(requiringRepositoryId) } returns listOf(
            TagInfo(name = "1.2.10", sha = currentCommit, message = "Patch for [1.2.0]"),
            TagInfo(name = "1.2.0", sha = baseCommit),
        )
        coEvery {
            repositoryBrowseService.listCommits(requiringRepositoryId, currentCommit, null, 256, 0)
        } returns listOf(
            commit(currentCommit, "Fix release packaging"),
            commit(baseCommit, "Release 1.2.0"),
        )
        coEvery { repositoryService.findByOwnerAndSlug("acme", "workspace") } returns upstreamRepo
        coEvery { pipelineService.findByRepositoryAndName(upstreamRepoId, "Bosca Release") } returns upstreamPipeline
        coEvery {
            runService.findLatestByPipelineAndRef(upstreamPipelineId, "refs/tags/1.2.10")
        } returns upstreamRun(PipelineRunStatus.FAILURE, "refs/tags/1.2.10")
        coEvery {
            runService.findLatestByPipelineAndRef(upstreamPipelineId, "refs/tags/1.2.0")
        } returns upstreamRun(PipelineRunStatus.SUCCESS, "refs/tags/1.2.0")

        checker.checkAwaiting()

        coVerify(exactly = 0) {
            runService.findLatestByPipelineAndRef(upstreamPipelineId, "refs/tags/1.2.0")
        }
        coVerify(exactly = 1) {
            jobService.updateStatus(jobId, PipelineRunStatus.FAILURE, match { "1.2.10" in it.orEmpty() })
        }
        coVerify(exactly = 0) { jobService.markRequirementsSatisfied(any()) }
    }

    @Test
    fun `a failed upstream run fails the job immediately naming the dependency`() = runTest {
        coEvery { jobService.findAwaitingRequirements() } returns
            listOf(gatedJob(requirements = emptyList(), pipelineRequirements = listOf(pipelineRequirement)))
        stubUpstream(PipelineRunStatus.FAILURE)

        checker.checkAwaiting()

        coVerify(exactly = 0) { jobService.markRequirementsSatisfied(any()) }
        coVerify(exactly = 1) {
            jobService.updateStatus(
                jobId,
                PipelineRunStatus.FAILURE,
                match { "Bosca Release" in it.orEmpty() && "FAILURE" in it.orEmpty() },
            )
        }
    }

    @Test
    fun `a missing upstream run keeps the job waiting before its deadline`() = runTest {
        coEvery { jobService.findAwaitingRequirements() } returns
            listOf(gatedJob(requirements = emptyList(), pipelineRequirements = listOf(pipelineRequirement)))
        stubUpstream(status = null)

        checker.checkAwaiting()

        coVerify(exactly = 0) { jobService.markRequirementsSatisfied(any()) }
        coVerify(exactly = 0) { jobService.updateStatus(any(), any(), any()) }
    }

    @Test
    fun `a still-running upstream run keeps the job waiting`() = runTest {
        coEvery { jobService.findAwaitingRequirements() } returns
            listOf(gatedJob(requirements = emptyList(), pipelineRequirements = listOf(pipelineRequirement)))
        stubUpstream(PipelineRunStatus.RUNNING)

        checker.checkAwaiting()

        coVerify(exactly = 0) { jobService.markRequirementsSatisfied(any()) }
        coVerify(exactly = 0) { jobService.updateStatus(any(), any(), any()) }
    }

    @Test
    fun `a pending pipeline requirement past the deadline fails naming the pipeline`() = runTest {
        coEvery { jobService.findAwaitingRequirements() } returns listOf(
            gatedJob(
                requirements = emptyList(),
                pipelineRequirements = listOf(pipelineRequirement),
                deadline = OffsetDateTime.now().minusMinutes(1),
            )
        )
        stubUpstream(status = null)

        checker.checkAwaiting()

        coVerify(exactly = 1) {
            jobService.updateStatus(
                jobId,
                PipelineRunStatus.FAILURE,
                match { "Bosca Release" in it.orEmpty() && "deadline" in it.orEmpty() },
            )
        }
    }

    @Test
    fun `a job gated only on pipelines needs no artifact verifier`() = runTest {
        ProviderRegistry.clear() // no RequiredArtifactVerifier registered — must not matter here
        coEvery { jobService.findAwaitingRequirements() } returns
            listOf(gatedJob(requirements = emptyList(), pipelineRequirements = listOf(pipelineRequirement)))
        stubUpstream(PipelineRunStatus.SUCCESS)

        checker.checkAwaiting()

        coVerify(exactly = 1) { jobService.markRequirementsSatisfied(jobId) }
        coVerify(exactly = 0) { jobService.updateStatus(any(), any(), any()) }
    }

    @Test
    fun `a satisfied pipeline requirement does not dispatch while artifacts are still missing`() = runTest {
        coEvery { jobService.findAwaitingRequirements() } returns
            listOf(gatedJob(requirements = listOf(requirement), pipelineRequirements = listOf(pipelineRequirement)))
        stubUpstream(PipelineRunStatus.SUCCESS)
        coEvery { verifier.unsatisfied(listOf(requirement)) } returns listOf(requirement)

        checker.checkAwaiting()

        coVerify(exactly = 0) { jobService.markRequirementsSatisfied(any()) }
        coVerify(exactly = 0) { jobService.updateStatus(any(), any(), any()) }
    }

    @Test
    fun `a bare repository slug resolves within the requiring repository's owner namespace`() = runTest {
        val ownerId = UUID.random()
        val requiringRepoId = UUID.random()
        val sibling = upstreamRepo.copy(ownerId = ownerId)
        coEvery { jobService.findAwaitingRequirements() } returns listOf(
            gatedJob(
                requirements = emptyList(),
                pipelineRequirements = listOf(pipelineRequirement.copy(repository = "workspace")),
            )
        )
        coEvery { runService.findById(runId) } returns upstreamRun(PipelineRunStatus.QUEUED).copy(
            repositoryId = requiringRepoId, ref = "refs/tags/1.2.0",
        )
        coEvery { repositoryService.findById(requiringRepoId) } returns
            Repository(id = requiringRepoId, slug = "server", name = "Server", ownerId = ownerId)
        coEvery { repositoryService.findByOwner(ownerId) } returns listOf(sibling)
        coEvery { pipelineService.findByRepositoryAndName(upstreamRepoId, "Bosca Release") } returns upstreamPipeline
        coEvery { runService.findLatestByPipelineAndRef(upstreamPipelineId, "refs/tags/1.2.0") } returns
            upstreamRun(PipelineRunStatus.SUCCESS)

        checker.checkAwaiting()

        coVerify(exactly = 1) { jobService.markRequirementsSatisfied(jobId) }
    }

    @Test
    fun `a transient pipeline-requirement lookup error leaves the job waiting for the next pass`() = runTest {
        coEvery { jobService.findAwaitingRequirements() } returns
            listOf(gatedJob(requirements = emptyList(), pipelineRequirements = listOf(pipelineRequirement)))
        coEvery { repositoryService.findByOwnerAndSlug(any(), any()) } throws RuntimeException("db hiccup")

        checker.checkAwaiting()

        coVerify(exactly = 0) { jobService.markRequirementsSatisfied(any()) }
        coVerify(exactly = 0) { jobService.updateStatus(any(), any(), any()) }
    }
}
