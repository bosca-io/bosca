@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.workops.service

import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.pubsub.Message
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.workops.model.spec.Spec
import bosca.workops.repository.SpecRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SpecDocumentSyncListenerTest {

    private val pubSubService = mockk<PubSubService>()
    private val specRepository = mockk<SpecRepository>()
    private val connectionPool = mockk<ConnectionPool>()
    private val connectionManager = mockk<ConnectionManager>()
    private val jobQueue = mockk<JobQueue>()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { Json }
        provides<JobQueue>(name = "workops", singleton = true) { jobQueue }
        every { connectionPool.connection() } returns connectionManager
        coEvery { connectionManager.release() } just Runs
        coEvery { jobQueue.enqueue(any<Job>()) } returns UUID.random()
        every {
            pubSubService.subscribe(any(), any<DeserializationStrategy<Any>>())
        } returns flow { awaitCancellation() }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `process always releases its connection and skips metadata without a spec`() = runTest {
        val metadataId = UUID.random()
        coEvery { specRepository.getByMetadataId(metadataId) } returns null
        val listener = SpecDocumentSyncListener(pubSubService, specRepository, connectionPool)
        try {
            listener.process(metadataId)

            coVerify(exactly = 1) { connectionManager.release() }
            coVerify(exactly = 0) { jobQueue.enqueue(any<Job>()) }
        } finally {
            listener.close()
        }
    }

    @Test
    fun `process enqueues context sync for the resolved spec`() = runTest {
        val metadataId = UUID.random()
        val spec = spec(metadataId)
        coEvery { specRepository.getByMetadataId(metadataId) } returns spec
        val listener = SpecDocumentSyncListener(pubSubService, specRepository, connectionPool)
        try {
            listener.process(metadataId)

            coVerify(exactly = 1) { jobQueue.enqueue(any<Job>()) }
            coVerify(exactly = 1) { connectionManager.release() }
        } finally {
            listener.close()
        }
    }

    @Test
    fun `process preserves cancellation and still releases its connection`() = runTest {
        val metadataId = UUID.random()
        coEvery { specRepository.getByMetadataId(metadataId) } throws CancellationException("cancelled")
        val listener = SpecDocumentSyncListener(pubSubService, specRepository, connectionPool)
        try {
            assertFailsWith<CancellationException> { listener.process(metadataId) }
            coVerify(exactly = 1) { connectionManager.release() }
        } finally {
            listener.close()
        }
    }

    @Test
    fun `subscription processes metadata update events`() = runTest {
        val metadataId = UUID.random()
        val spec = spec(metadataId)
        every {
            pubSubService.subscribe(any(), any<DeserializationStrategy<Any>>())
        } returns flow {
            emit(Message("bosca.content.metadata.updated", MetadataUpdated(metadataId)))
            awaitCancellation()
        }
        coEvery { specRepository.getByMetadataId(metadataId) } returns spec
        val listener = SpecDocumentSyncListener(pubSubService, specRepository, connectionPool)
        try {
            coVerify(timeout = 5_000, exactly = 1) { jobQueue.enqueue(any<Job>()) }
            coVerify(exactly = 1) { connectionManager.release() }
        } finally {
            listener.close()
        }
    }

    private fun spec(metadataId: UUID): Spec {
        val principalId = UUID.random()
        return Spec(
            id = UUID.random(),
            key = "GIT-SPEC-6",
            metadataId = metadataId,
            projectId = UUID.random(),
            statusId = UUID.random(),
            workflowId = UUID.random(),
            ownerProfileId = UUID.random(),
            createdByPrincipalId = principalId,
            modifiedByPrincipalId = principalId,
        )
    }
}
