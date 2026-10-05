package bosca.artifacts.service

import bosca.artifacts.github.GitHubPublicationException
import bosca.artifacts.github.GitHubReleaseClient
import bosca.artifacts.model.*
import bosca.artifacts.repository.ArtifactPublicationRepository
import bosca.db.transaction
import bosca.pipelines.service.PipelineSecretService
import bosca.serialization.UUID
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ArtifactPublicationServiceImplTest {
    private val repository = mockk<ArtifactPublicationRepository>()
    private val artifacts = mockk<ArtifactRepositoryService>()
    private val github = mockk<GitHubReleaseClient>()
    private val secrets = mockk<PipelineSecretService>()
    private val publication = ArtifactPublication(UUID.random(), UUID.random(), UUID.random(), "v1", "1".repeat(40), false, emptyList())
    private val destination = ArtifactPublicationDestination(publication.destinationId, UUID.random(), "github", 123,
        "acme", "tool", "v", true, "github-token")
    private val service = ArtifactPublicationServiceImpl(repository, artifacts, mockk(), secrets, github)

    @BeforeTest fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { transaction(any<suspend () -> Any?>()) } coAnswers { firstArg<suspend () -> Any?>().invoke() }
        coEvery { repository.find(publication.id) } returns publication
        coEvery { repository.lock(publication.id) } returns publication
        coEvery { repository.findDestination(destination.id) } returns destination
        coEvery { repository.attempt(publication.id) } returns publication
        coEvery { artifacts.finalizeVersion(publication.versionId) } returns ArtifactVersion(publication.versionId, destination.repositoryId, "1", finalized = true)
        coEvery { secrets.resolve(destination.tokenSecretName) } returns "token"
    }

    @AfterTest fun cleanup() = unmockkStatic("bosca.db.ConnectionManagerKt")

    @Test fun `cancellation propagates without recording an ordinary publication failure`() = runTest {
        val cancellation = CancellationException("cancelled")
        coEvery { github.publish(destination, "token", publication) } throws cancellation
        assertSame(cancellation, assertFailsWith<CancellationException> { service.publish(publication.id) })
        coVerify(exactly = 0) { repository.failed(any(), any(), any()) }
    }

    @Test fun `failure to persist an error retains the original provider failure`() = runTest {
        val failure = GitHubPublicationException("HTTP 503")
        val recordingFailure = IllegalStateException("database unavailable")
        coEvery { github.publish(destination, "token", publication) } throws failure
        coEvery { repository.failed(publication.id, "HTTP 503", true) } throws recordingFailure
        assertSame(failure, assertFailsWith<GitHubPublicationException> { service.publish(publication.id) })
        assertEquals(listOf(recordingFailure), failure.suppressed.toList())
    }
}
