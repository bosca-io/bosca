@file:OptIn(bosca.di.annotation.InternalDI::class, kotlin.uuid.ExperimentalUuidApi::class)

package bosca.recommendations.jobs

import bosca.artifacts.model.ArtifactType
import bosca.artifacts.model.ArtifactRepository
import bosca.artifacts.model.ArtifactVersion
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DeleteContextModelArtifactsJobExecutorTest {
    private val contextId = UUID.random()
    private val artifacts = mockk<ArtifactRepositoryService>()
    private val repositoryId = UUID.random()
    private val artifactId = UUID.random()

    @AfterTest
    fun clearRegistry() = ProviderRegistry.clear()

    private suspend fun execute() {
        provides<Json>(singleton = true) { Json }
        provides<ArtifactRepositoryService>(singleton = true) { artifacts }
        val job = Job(definition = DeleteContextModelArtifactsJob(contextId, 16), executor = DeleteContextModelArtifactsJobExecutor::class)
        withContext(mockk<JobQueue>(relaxed = true).asCoroutineContext(job)) {
            DeleteContextModelArtifactsJobExecutor().execute()
        }
    }

    @Test
    fun `cleanup removes both exact exports and tolerates missing artifacts on redelivery`() = runBlocking {
        val repository = mockk<ArtifactRepository> { every { id } returns repositoryId }
        val version = mockk<ArtifactVersion> { every { id } returns artifactId }
        val personalizedRepositoryId = UUID.random()
        val personalizedArtifactId = UUID.random()
        val personalizedRepository = mockk<ArtifactRepository> { every { id } returns personalizedRepositoryId }
        val personalizedVersion = mockk<ArtifactVersion> { every { id } returns personalizedArtifactId }
        coEvery { artifacts.findRepository("model", "recommender-$contextId-content", ArtifactType.ML) } returns repository
        coEvery { artifacts.findRepository("model", "recommender-$contextId-personalized", ArtifactType.ML) } returns personalizedRepository
        coEvery { artifacts.findVersion(personalizedRepositoryId, "16") } returns personalizedVersion
        coEvery { artifacts.deleteVersion(personalizedArtifactId) } returns Unit
        coEvery { artifacts.findVersion(repositoryId, "16") } returns version
        coEvery { artifacts.deleteVersion(artifactId) } returns Unit
        execute()
        coVerify(exactly = 1) { artifacts.deleteVersion(artifactId) }
        coVerify(exactly = 1) { artifacts.deleteVersion(personalizedArtifactId) }
        coEvery { artifacts.findVersion(repositoryId, "16") } returns null
        coEvery { artifacts.findRepository("model", "recommender-$contextId-personalized", ArtifactType.ML) } returns null
        execute()
        coVerify(exactly = 1) { artifacts.deleteVersion(artifactId) }
        coVerify(exactly = 1) { artifacts.deleteVersion(personalizedArtifactId) }
    }

    @Test
    fun `cleanup failures and cancellation propagate for durable retry`() = runBlocking<Unit> {
        coEvery { artifacts.findRepository(any(), any(), any()) } throws IllegalStateException("Registry unavailable")
        assertFailsWith<IllegalStateException> { execute() }
        coEvery { artifacts.findRepository(any(), any(), any()) } throws CancellationException("Cancelled")
        assertFailsWith<CancellationException> { execute() }
    }
}
