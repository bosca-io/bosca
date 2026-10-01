package bosca.content.transition.graphql

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionJobHistory
import bosca.content.collection.repository.CollectionJobHistoryRepository
import bosca.content.collection.service.CollectionJobHistoryService
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataJobHistory
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.repository.MetadataJobHistoryRepository
import bosca.content.metadata.service.MetadataJobHistoryService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.sharedqueue.jobs.JobQueue
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(InternalDI::class)
class JobsMutationControllerCoverageTest {

    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private val metadataService = mockk<MetadataService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val metadataJobHistoryRepository = mockk<MetadataJobHistoryRepository>()
    private val metadataJobHistoryService = mockk<MetadataJobHistoryService>()
    private val collectionService = mockk<CollectionService>()
    private val collectionPermissionEvaluator = mockk<CollectionPermissionEvaluator>()
    private val collectionJobHistoryRepository = mockk<CollectionJobHistoryRepository>()
    private val collectionJobHistoryService = mockk<CollectionJobHistoryService>()

    private val authentication = mockk<AuthenticationContext>()

    private val controller = JobsMutationController(
        groupEvaluator,
        metadataService,
        metadataPermissionEvaluator,
        metadataJobHistoryRepository,
        metadataJobHistoryService,
        collectionService,
        collectionPermissionEvaluator,
        collectionJobHistoryRepository,
        collectionJobHistoryService,
    )

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        ProviderRegistry.clear()
    }

    private fun metadataJob(
        id: UUID,
        version: Int = 1,
        jobName: String = "publish-job",
        jobId: UUID = UUID.random(),
    ) = MetadataJobHistory(
        id = id,
        version = version,
        jobName = jobName,
        jobId = jobId,
        status = "Running",
        principal = null,
    )

    private fun collectionJob(
        id: UUID,
        jobName: String = "publish-job",
        jobId: UUID = UUID.random(),
    ) = CollectionJobHistory(
        id = id,
        jobName = jobName,
        jobId = jobId,
        status = "Running",
        principal = null,
    )

    private fun metadata(id: UUID) = Metadata(
        id = id,
        name = "Item",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "published",
    )

    private fun collection(id: UUID) = Collection(
        id = id,
        name = "Coll",
        languageTag = "en",
        workflowStateId = "published",
    )

    private fun registerEnqueuer(name: String, queue: JobQueue) {
        val enqueuer = mockk<JobConfigurationEnqueuer>()
        coEvery { enqueuer.queue() } returns queue
        provides<JobConfigurationEnqueuer>(name = name) { enqueuer }
    }

    // ---- metadata branch ----

    @Test
    fun `cancel cancels metadata jobs and marks them complete`() = runTest {
        val jobId = UUID.random()
        val metadataId = UUID.random()
        val queried = metadataJob(id = metadataId, version = 3, jobId = jobId)
        val meta = metadata(metadataId)
        val active1 = metadataJob(id = metadataId, version = 3, jobName = "enter-job", jobId = UUID.random())
        val active2 = metadataJob(id = metadataId, version = 3, jobName = "exit-job", jobId = UUID.random())
        val queue = mockk<JobQueue>(relaxed = true)

        coEvery { metadataJobHistoryRepository.getActiveJobByJobId(jobId) } returns queried
        coEvery { metadataService.getById(metadataId, 3) } returns meta
        coEvery {
            metadataPermissionEvaluator.verifyAllowed(authentication, meta, PermissionAction.EXECUTE)
        } returns Unit
        coEvery { metadataJobHistoryService.getActiveJobs(metadataId, 3) } returns listOf(active1, active2)
        coEvery {
            metadataJobHistoryService.setComplete(any(), any(), any(), any(), any())
        } returns Unit
        registerEnqueuer("enter-job", queue)
        registerEnqueuer("exit-job", queue)

        val result = controller.cancel(authentication, jobId)

        assertTrue(result)
        val enterId = active1.jobId
        val exitId = active2.jobId
        coVerify { queue.markCancelled(enterId) }
        coVerify { queue.markCancelled(exitId) }
        coVerify { metadataJobHistoryService.setComplete(metadataId, 3, enterId, "Cancelled", false) }
        coVerify { metadataJobHistoryService.setComplete(metadataId, 3, exitId, "Cancelled", false) }
    }

    @Test
    fun `cancel swallows enqueuer failure for metadata jobs`() = runTest {
        val jobId = UUID.random()
        val metadataId = UUID.random()
        val queried = metadataJob(id = metadataId, version = 2, jobId = jobId)
        val meta = metadata(metadataId)
        // active job whose provider name is NOT registered -> provide() throws -> caught
        val active = metadataJob(id = metadataId, version = 2, jobName = "missing-provider", jobId = UUID.random())

        coEvery { metadataJobHistoryRepository.getActiveJobByJobId(jobId) } returns queried
        coEvery { metadataService.getById(metadataId, 2) } returns meta
        coEvery {
            metadataPermissionEvaluator.verifyAllowed(authentication, meta, PermissionAction.EXECUTE)
        } returns Unit
        coEvery { metadataJobHistoryService.getActiveJobs(metadataId, 2) } returns listOf(active)
        coEvery {
            metadataJobHistoryService.setComplete(any(), any(), any(), any(), any())
        } returns Unit

        val result = controller.cancel(authentication, jobId)

        assertTrue(result)
        val activeId = active.jobId
        coVerify { metadataJobHistoryService.setComplete(metadataId, 2, activeId, "Cancelled", false) }
    }

    @Test
    fun `cancel returns true for metadata with no active jobs`() = runTest {
        val jobId = UUID.random()
        val metadataId = UUID.random()
        val queried = metadataJob(id = metadataId, version = 1, jobId = jobId)
        val meta = metadata(metadataId)

        coEvery { metadataJobHistoryRepository.getActiveJobByJobId(jobId) } returns queried
        coEvery { metadataService.getById(metadataId, 1) } returns meta
        coEvery {
            metadataPermissionEvaluator.verifyAllowed(authentication, meta, PermissionAction.EXECUTE)
        } returns Unit
        coEvery { metadataJobHistoryService.getActiveJobs(metadataId, 1) } returns emptyList()

        val result = controller.cancel(authentication, jobId)

        assertTrue(result)
        coVerify(exactly = 0) { metadataJobHistoryService.setComplete(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `cancel errors when metadata not found`() = runTest {
        val jobId = UUID.random()
        val metadataId = UUID.random()
        val queried = metadataJob(id = metadataId, version = 5, jobId = jobId)

        coEvery { metadataJobHistoryRepository.getActiveJobByJobId(jobId) } returns queried
        coEvery { metadataService.getById(metadataId, 5) } returns null

        assertFailsWith<IllegalStateException> {
            controller.cancel(authentication, jobId)
        }
    }

    @Test
    fun `cancel propagates permission denial for metadata`() = runTest {
        val jobId = UUID.random()
        val metadataId = UUID.random()
        val queried = metadataJob(id = metadataId, version = 1, jobId = jobId)
        val meta = metadata(metadataId)

        coEvery { metadataJobHistoryRepository.getActiveJobByJobId(jobId) } returns queried
        coEvery { metadataService.getById(metadataId, 1) } returns meta
        coEvery {
            metadataPermissionEvaluator.verifyAllowed(authentication, meta, PermissionAction.EXECUTE)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller.cancel(authentication, jobId)
        }
        coVerify(exactly = 0) { metadataJobHistoryService.getActiveJobs(any(), any()) }
    }

    // ---- collection branch ----

    @Test
    fun `cancel cancels collection jobs and marks them complete`() = runTest {
        val jobId = UUID.random()
        val collectionId = UUID.random()
        val queried = collectionJob(id = collectionId, jobId = jobId)
        val coll = collection(collectionId)
        val active1 = collectionJob(id = collectionId, jobName = "enter-job", jobId = UUID.random())
        val active2 = collectionJob(id = collectionId, jobName = "exit-job", jobId = UUID.random())
        val queue = mockk<JobQueue>(relaxed = true)

        coEvery { metadataJobHistoryRepository.getActiveJobByJobId(jobId) } returns null
        coEvery { collectionJobHistoryRepository.getActiveJobByJobId(jobId) } returns queried
        coEvery { collectionService.getById(collectionId) } returns coll
        coEvery {
            collectionPermissionEvaluator.verifyAllowed(authentication, coll, PermissionAction.EXECUTE)
        } returns Unit
        coEvery { collectionJobHistoryService.getActiveJobs(collectionId) } returns listOf(active1, active2)
        coEvery {
            collectionJobHistoryService.setComplete(any(), any(), any(), any())
        } returns Unit
        registerEnqueuer("enter-job", queue)
        registerEnqueuer("exit-job", queue)

        val result = controller.cancel(authentication, jobId)

        assertTrue(result)
        val enterId = active1.jobId
        val exitId = active2.jobId
        coVerify { queue.markCancelled(enterId) }
        coVerify { queue.markCancelled(exitId) }
        coVerify { collectionJobHistoryService.setComplete(collectionId, enterId, "Cancelled", false) }
        coVerify { collectionJobHistoryService.setComplete(collectionId, exitId, "Cancelled", false) }
    }

    @Test
    fun `cancel swallows enqueuer failure for collection jobs`() = runTest {
        val jobId = UUID.random()
        val collectionId = UUID.random()
        val queried = collectionJob(id = collectionId, jobId = jobId)
        val coll = collection(collectionId)
        val active = collectionJob(id = collectionId, jobName = "missing-provider", jobId = UUID.random())

        coEvery { metadataJobHistoryRepository.getActiveJobByJobId(jobId) } returns null
        coEvery { collectionJobHistoryRepository.getActiveJobByJobId(jobId) } returns queried
        coEvery { collectionService.getById(collectionId) } returns coll
        coEvery {
            collectionPermissionEvaluator.verifyAllowed(authentication, coll, PermissionAction.EXECUTE)
        } returns Unit
        coEvery { collectionJobHistoryService.getActiveJobs(collectionId) } returns listOf(active)
        coEvery {
            collectionJobHistoryService.setComplete(any(), any(), any(), any())
        } returns Unit

        val result = controller.cancel(authentication, jobId)

        assertTrue(result)
        val activeId = active.jobId
        coVerify { collectionJobHistoryService.setComplete(collectionId, activeId, "Cancelled", false) }
    }

    @Test
    fun `cancel errors when collection not found`() = runTest {
        val jobId = UUID.random()
        val collectionId = UUID.random()
        val queried = collectionJob(id = collectionId, jobId = jobId)

        coEvery { metadataJobHistoryRepository.getActiveJobByJobId(jobId) } returns null
        coEvery { collectionJobHistoryRepository.getActiveJobByJobId(jobId) } returns queried
        coEvery { collectionService.getById(collectionId) } returns null

        assertFailsWith<IllegalStateException> {
            controller.cancel(authentication, jobId)
        }
    }

    @Test
    fun `cancel propagates permission denial for collection`() = runTest {
        val jobId = UUID.random()
        val collectionId = UUID.random()
        val queried = collectionJob(id = collectionId, jobId = jobId)
        val coll = collection(collectionId)

        coEvery { metadataJobHistoryRepository.getActiveJobByJobId(jobId) } returns null
        coEvery { collectionJobHistoryRepository.getActiveJobByJobId(jobId) } returns queried
        coEvery { collectionService.getById(collectionId) } returns coll
        coEvery {
            collectionPermissionEvaluator.verifyAllowed(authentication, coll, PermissionAction.EXECUTE)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller.cancel(authentication, jobId)
        }
        coVerify(exactly = 0) { collectionJobHistoryService.getActiveJobs(any()) }
    }

    // ---- neither branch ----

    @Test
    fun `cancel returns false when no matching job exists`() = runTest {
        val jobId = UUID.random()

        coEvery { metadataJobHistoryRepository.getActiveJobByJobId(jobId) } returns null
        coEvery { collectionJobHistoryRepository.getActiveJobByJobId(jobId) } returns null

        val result = controller.cancel(authentication, jobId)

        assertFalse(result)
    }
}
