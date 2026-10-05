package bosca.workops.service

import bosca.git.model.ArtifactDefinition
import bosca.git.model.PipelineEvent
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineRunStatus
import bosca.git.model.PipelineTriggerType
import bosca.git.service.PipelineRunService
import bosca.serialization.UUID
import bosca.workops.model.artifact.ArtifactPublication
import bosca.workops.model.artifact.ArtifactType
import bosca.workops.model.artifact.PublicationStatus
import bosca.workops.model.project.ProjectRepository
import bosca.workops.model.release.ReleaseProjectVersion
import bosca.workops.model.version.Version
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PipelineArtifactPublicationSyncTest {

    private val runs = mockk<PipelineRunService>()
    private val projectRepositories = mockk<ProjectRepositoryService>()
    private val releases = mockk<ReleaseService>()
    private val versions = mockk<VersionService>()
    private val publications = mockk<ArtifactPublicationService>()
    private val sync = PipelineArtifactPublicationSync(runs, projectRepositories, releases, versions, publications)

    private val runId = UUID.random()
    private val pipelineId = UUID.random()
    private val repositoryId = UUID.random()
    private val projectId = UUID.random()
    private val versionId = UUID.random()
    private val releaseId = UUID.random()
    private val actorId = UUID.random()

    @Test
    fun `successful release run registers and publishes every declared artifact`() = runTest {
        val run = run(parameters = buildJsonObject { put("release.id", releaseId.toString()) })
        val artifact = ArtifactDefinition("docker", "bosca-docker", "server:6.0.0", listOf("production"))
        val pending = publication(artifact.coordinate)
        coEvery { runs.findById(runId) } returns run
        coEvery { runs.artifacts(runId) } returns listOf(artifact)
        coEvery { projectRepositories.listByRepository(repositoryId) } returns listOf(
            ProjectRepository(projectId = projectId, repositoryId = repositoryId),
        )
        coEvery { releases.listVersions(releaseId) } returns listOf(
            ReleaseProjectVersion(releaseId, projectId, versionId),
        )
        coEvery { versions.getById(versionId) } returns version()
        coEvery { publications.listByVersion(versionId) } returns emptyList()
        coEvery { publications.register(any()) } returns pending
        coEvery { publications.markPublished(pending.id, actorId, 0) } returns pending.copy(status = PublicationStatus.PUBLISHED)

        sync.apply(event(PipelineRunStatus.SUCCESS))

        coVerify(exactly = 1) {
            publications.register(match {
                it.versionId == versionId && it.projectId == projectId &&
                    it.artifactType == ArtifactType.DOCKER && it.coordinates == "server:6.0.0" &&
                    it.namespace == "bosca-docker" && it.environments == listOf("production")
            })
        }
        coVerify(exactly = 1) { publications.markPublished(pending.id, actorId, 0) }
    }

    @Test
    fun `tag run falls back to the linked project's matching version`() = runTest {
        val run = run(ref = "refs/tags/v6.0.1", parameters = buildJsonObject { })
        val artifact = ArtifactDefinition("maven", "bosca-maven", "io.bosca:core:6.0.1")
        val existing = publication(artifact.coordinate).copy(status = PublicationStatus.PUBLISHED)
        coEvery { runs.findById(runId) } returns run
        coEvery { runs.artifacts(runId) } returns listOf(artifact)
        coEvery { projectRepositories.listByRepository(repositoryId) } returns listOf(
            ProjectRepository(projectId = projectId, repositoryId = repositoryId),
        )
        coEvery { versions.listByProject(projectId) } returns listOf(version("6.0.1"))
        coEvery { publications.listByVersion(versionId) } returns listOf(existing)

        sync.apply(event(PipelineRunStatus.SUCCESS))

        coVerify(exactly = 0) { publications.register(any()) }
        coVerify(exactly = 0) { publications.markPublished(any(), any(), any()) }
    }

    @Test
    fun `non-success event does not inspect the run`() = runTest {
        sync.apply(event(PipelineRunStatus.FAILURE))
        coVerify(exactly = 0) { runs.findById(any()) }
    }

    @Test
    fun `successful events stop at missing run empty artifacts and unlinked repository`() = runTest {
        coEvery { runs.findById(runId) } returns null
        sync.apply(event(PipelineRunStatus.SUCCESS))
        coVerify(exactly = 0) { runs.artifacts(any()) }

        val run = run(parameters = JsonPrimitive("not-an-object"))
        coEvery { runs.findById(runId) } returns run
        coEvery { runs.artifacts(runId) } returns emptyList()
        sync.apply(event(PipelineRunStatus.SUCCESS))
        coVerify(exactly = 0) { projectRepositories.listByRepository(any()) }

        coEvery { runs.artifacts(runId) } returns listOf(ArtifactDefinition("docker", "", "server:1"))
        coEvery { projectRepositories.listByRepository(repositoryId) } returns emptyList()
        sync.apply(event(PipelineRunStatus.SUCCESS))
        coVerify(exactly = 0) { versions.listByProject(any()) }
    }

    @Test
    fun `release and tag resolution tolerate invalid absent and normalized version selectors`() = runTest {
        val artifact = ArtifactDefinition("docker", "", "server:1")
        val link = ProjectRepository(projectId = projectId, repositoryId = repositoryId)
        coEvery { runs.findById(runId) } returnsMany listOf(
            run(parameters = buildJsonObject { put("release.id", "not-a-uuid") }),
            run(parameters = buildJsonObject { put("release.id", JsonNull) }),
            run(parameters = buildJsonObject { put("release.version", "v6.0.0") }),
            run(ref = "refs/heads/main", parameters = JsonPrimitive("not-an-object")),
        )
        coEvery { runs.artifacts(runId) } returns listOf(artifact)
        coEvery { projectRepositories.listByRepository(repositoryId) } returns listOf(link)
        coEvery { versions.listByProject(projectId) } returns listOf(version("6.0.0"))
        val existing = publication(artifact.coordinate).copy(status = PublicationStatus.PUBLISHED)
        coEvery { publications.listByVersion(versionId) } returns listOf(existing)

        sync.apply(event(PipelineRunStatus.SUCCESS))
        sync.apply(event(PipelineRunStatus.SUCCESS))
        sync.apply(event(PipelineRunStatus.SUCCESS))
        coVerify(exactly = 1) { publications.listByVersion(versionId) }
        sync.apply(event(PipelineRunStatus.SUCCESS))
        coVerify(exactly = 1) { publications.listByVersion(versionId) }
    }

    @Test
    fun `release mapping skips vanished versions and rejects ambiguous project ownership`() = runTest {
        val secondProjectId = UUID.random()
        val secondVersionId = UUID.random()
        val run = run(parameters = buildJsonObject { put("release.id", releaseId.toString()) })
        val artifact = ArtifactDefinition("docker", "", "server:1")
        coEvery { runs.findById(runId) } returns run
        coEvery { runs.artifacts(runId) } returns listOf(artifact)
        coEvery { projectRepositories.listByRepository(repositoryId) } returns listOf(
            ProjectRepository(projectId = projectId, repositoryId = repositoryId),
            ProjectRepository(projectId = projectId, repositoryId = repositoryId),
        )
        coEvery { releases.listVersions(releaseId) } returns listOf(
            ReleaseProjectVersion(releaseId, projectId, versionId),
        )
        coEvery { versions.getById(versionId) } returns null
        sync.apply(event(PipelineRunStatus.SUCCESS))
        coVerify(exactly = 0) { publications.listByVersion(any()) }

        coEvery { projectRepositories.listByRepository(repositoryId) } returns listOf(
            ProjectRepository(projectId = projectId, repositoryId = repositoryId),
            ProjectRepository(projectId = secondProjectId, repositoryId = repositoryId),
        )
        coEvery { releases.listVersions(releaseId) } returns listOf(
            ReleaseProjectVersion(releaseId, projectId, versionId),
            ReleaseProjectVersion(releaseId, secondProjectId, secondVersionId),
        )
        coEvery { versions.getById(versionId) } returns version()
        coEvery { versions.getById(secondVersionId) } returns Version(
            id = secondVersionId,
            projectId = secondProjectId,
            name = "6.0.0",
            sequenceNumber = 1,
        )
        assertFailsWith<IllegalStateException> { sync.apply(event(PipelineRunStatus.SUCCESS)) }
    }

    @Test
    fun `pending publication without initiating principal remains pending`() = runTest {
        val run = run(parameters = buildJsonObject { put("release.version", "6.0.0") }).copy(triggeredBy = null)
        val artifact = ArtifactDefinition("docker", "", "server:1")
        val pending = publication(artifact.coordinate)
        coEvery { runs.findById(runId) } returns run
        coEvery { runs.artifacts(runId) } returns listOf(artifact)
        coEvery { projectRepositories.listByRepository(repositoryId) } returns listOf(
            ProjectRepository(projectId = projectId, repositoryId = repositoryId),
        )
        coEvery { versions.listByProject(projectId) } returns listOf(version())
        coEvery { publications.listByVersion(versionId) } returns emptyList()
        coEvery { publications.register(any()) } returns pending

        sync.apply(event(PipelineRunStatus.SUCCESS))

        coVerify(exactly = 0) { publications.markPublished(any(), any(), any()) }
    }

    private fun event(status: PipelineRunStatus) = PipelineEvent(
        repositoryId = repositoryId,
        pipelineRunId = runId,
        pipelineId = pipelineId,
        status = status,
    )

    private fun run(
        ref: String = "refs/heads/main",
        parameters: kotlinx.serialization.json.JsonElement,
    ) = PipelineRun(
        id = runId,
        pipelineId = pipelineId,
        repositoryId = repositoryId,
        commitSha = "abc123",
        ref = ref,
        triggerType = PipelineTriggerType.RELEASE,
        triggeredBy = actorId,
        parameters = parameters,
    )

    private fun version(name: String = "6.0.0") = Version(
        id = versionId,
        projectId = projectId,
        name = name,
        sequenceNumber = 1,
    )

    private fun publication(coordinate: String) = ArtifactPublication(
        id = UUID.random(),
        versionId = versionId,
        projectId = projectId,
        artifactType = ArtifactType.DOCKER,
        coordinates = coordinate,
    )
}
