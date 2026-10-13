package bosca.artifacts.service

import bosca.artifacts.github.GitHubReleaseClient
import bosca.artifacts.model.*
import bosca.artifacts.repository.ArtifactPublicationRepository
import bosca.db.transaction
import bosca.di.asProvider
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineRun
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineRunService
import bosca.pipelines.service.PipelineSecretService
import bosca.pipelines.service.PipelineService
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ArtifactPublicationPublishTest {
    private val repository = mockk<ArtifactPublicationRepository>()
    private val artifacts = mockk<ArtifactRepositoryService>()
    private val secrets = mockk<PipelineSecretService>()
    private val github = mockk<GitHubReleaseClient>()
    private val pipelines = mockk<PipelineService>()
    private val runs = mockk<PipelineRunService>()
    private val authentication = mockk<AuthenticationContext>()
    private val artifact = ArtifactRepository(UUID.random(), UUID.random(), "bosca-cli", "raw")
    private val version = ArtifactVersion(UUID.random(), artifact.id, "1.2.3", completedCommitSha = "a".repeat(40))
    private val destination = ArtifactPublicationDestination(UUID.random(), artifact.id, "github", 123,
        "bosca-io", "bosca", "cli-v", true, "github-token")
    private val commit = "a".repeat(40)
    private val pipeline = Pipeline(UUID.random(), ArtifactPublicationService.PUBLICATION_PIPELINE_NAME,
        acceptedInputType = "bosca.artifacts.model.ArtifactPublicationTarget")
    private val run = mockk<PipelineRun> { every { id } returns UUID.random() }
    private val service = ArtifactPublicationServiceImpl(repository, artifacts, mockk(), secrets, github,
        pipelines.asProvider(), runs.asProvider())

    @BeforeTest fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { transaction(any<suspend () -> Any?>()) } coAnswers { firstArg<suspend () -> Any?>().invoke() }
        coEvery { pipelines.acceptingInput(any()) } returns listOf(pipeline)
        coEvery { repository.findDestination(destination.id) } returns destination
        coEvery { artifacts.getVersion(version.id) } returns version
        coEvery { artifacts.getRepository(artifact.id) } returns artifact
        coEvery { artifacts.getVersionBlobs(version.id) } returns listOf(ArtifactVersionBlob(version.id,
            "sha256:" + "b".repeat(64), "file", "install.sh"))
        coEvery { secrets.resolve(destination.tokenSecretName) } returns "token"
        coEvery { github.verifyTarget(any(), any(), any(), any()) } returns Unit
        coEvery { repository.publications(version.id, 1, 0) } returns emptyList()
        coEvery { repository.find(destination.id, version.id) } returns null
        coEvery { runs.start(any(), any(), any(), any(), any()) } returns run
    }

    @AfterTest fun cleanup() = unmockkStatic("bosca.db.ConnectionManagerKt")

    @Test fun `publication queues only the selected raw version under the caller without inline publication`() = runTest {
        val input = slot<PipelineValue>()
        assertEquals(run.id, service.publish(authentication, destination.id, version.id, commit))
        coVerify { runs.start(pipeline, capture(input), "manual", any(), authentication) }
        assertEquals(ArtifactPublicationTarget(destination.id, version.id, commit, reverify = true), input.captured.value)
        coVerify(exactly = 0) { artifacts.finalizeVersion(any()) }
        coVerify(exactly = 0) { github.publish(any(), any(), any()) }
    }

    @Test fun `renamed unique body is accepted and a named body selects among multiple candidates`() = runTest {
        val renamed = pipeline.copy(name = "CLI release publication")
        coEvery { pipelines.acceptingInput(any()) } returns listOf(renamed, pipeline.copy(triggered = true))
        service.publish(authentication, destination.id, version.id, commit)
        coVerify { runs.start(renamed, any(), any(), any(), authentication) }
        coEvery { pipelines.acceptingInput(any()) } returns listOf(renamed, pipeline)
        service.publish(authentication, destination.id, version.id, commit)
        coVerify { runs.start(pipeline, any(), any(), any(), authentication) }
    }

    @Test fun `missing and ambiguous bodies explain how to restore the publication pipeline`() = runTest {
        coEvery { pipelines.acceptingInput(any()) } returns listOf(pipeline.copy(triggered = true))
        assertContains(assertFailsWith<IllegalStateException> {
            service.publish(authentication, destination.id, version.id, commit)
        }.message.orEmpty(), "Install 'Default Artifact Sync Pipelines'")
        coEvery { pipelines.acceptingInput(any()) } returns listOf(pipeline.copy(name = "One"), pipeline.copy(name = "Two"))
        assertContains(assertFailsWith<IllegalStateException> {
            service.publish(authentication, destination.id, version.id, commit)
        }.message.orEmpty(), ArtifactPublicationService.PUBLICATION_PIPELINE_NAME)
        coVerify(exactly = 0) { runs.start(any(), any(), any(), any(), any()) }
    }

    @Test fun `disabled missing and mismatched sources cannot queue publication`() = runTest {
        coEvery { repository.findDestination(destination.id) } returns null
        assertFailsWith<NoSuchElementException> { service.publish(authentication, destination.id, version.id, commit) }
        coEvery { repository.findDestination(destination.id) } returns destination.copy(enabled = false)
        assertFailsWith<IllegalStateException> { service.publish(authentication, destination.id, version.id, commit) }
        coEvery { repository.findDestination(destination.id) } returns destination
        coEvery { artifacts.getVersion(version.id) } returns null
        assertFailsWith<NoSuchElementException> { service.publish(authentication, destination.id, version.id, commit) }
        coEvery { artifacts.getVersion(version.id) } returns version.copy(repositoryId = UUID.random())
        assertFailsWith<IllegalArgumentException> { service.publish(authentication, destination.id, version.id, commit) }
        coEvery { artifacts.getVersion(version.id) } returns version
        coEvery { artifacts.getRepository(artifact.id) } returns artifact.copy(type = "docker")
        assertFailsWith<IllegalArgumentException> { service.publish(authentication, destination.id, version.id, commit) }
        coEvery { artifacts.getRepository(artifact.id) } returns artifact
        coEvery { artifacts.getVersionBlobs(version.id) } returns emptyList()
        assertFailsWith<IllegalArgumentException> { service.publish(authentication, destination.id, version.id, commit) }
        coVerify(exactly = 0) { runs.start(any(), any(), any(), any(), any()) }
    }

    @Test fun `missing credentials invalid commits and altered publication provenance are rejected`() = runTest {
        for (sha in listOf("", "main", "a".repeat(39), "g".repeat(40))) {
            assertFailsWith<IllegalArgumentException> { service.publish(authentication, destination.id, version.id, sha) }
        }
        coEvery { secrets.resolve(destination.tokenSecretName) } returns null
        assertFailsWith<IllegalStateException> { service.publish(authentication, destination.id, version.id, commit) }
        coEvery { secrets.resolve(destination.tokenSecretName) } returns "token"
        coEvery { repository.publications(version.id, 1, 0) } returns listOf(ArtifactPublication(UUID.random(),
            destination.id, version.id, "cli-v1.2.3", "c".repeat(40), false, emptyList()))
        assertFailsWith<IllegalArgumentException> { service.publish(authentication, destination.id, version.id, commit) }
        coVerify(exactly = 0) { runs.start(any(), any(), any(), any(), any()) }
    }

    @Test fun `run capacity failures and cancellation propagate to the caller`() = runTest {
        coEvery { runs.start(any(), any(), any(), any(), any()) } returns null
        assertContains(assertFailsWith<IllegalStateException> {
            service.publish(authentication, destination.id, version.id, commit)
        }.message.orEmpty(), "concurrency or rate limit")
        val cancellation = CancellationException("cancelled")
        coEvery { runs.start(any(), any(), any(), any(), any()) } throws cancellation
        assertSame(cancellation, assertFailsWith<CancellationException> {
            service.publish(authentication, destination.id, version.id, commit)
        })
    }
}
