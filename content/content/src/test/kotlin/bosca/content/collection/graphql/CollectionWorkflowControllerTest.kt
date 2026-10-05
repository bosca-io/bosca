package bosca.content.collection.graphql

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionType
import bosca.content.collection.service.CollectionJobHistoryService
import bosca.content.collection.service.CollectionService
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollectionWorkflowControllerTest {

    private val collectionService = mockk<CollectionService>()
    private val collectionJobHistoryService = mockk<CollectionJobHistoryService> {
        coEvery { getActiveJobs(any()) } returns emptyList()
    }
    private val controller = CollectionWorkflowController(collectionService, collectionJobHistoryService)

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun createCollection(
        workflowStateId: String = "draft",
        workflowStateValid: OffsetDateTime? = null,
        workflowStatePendingId: String? = null,
        deleteWorkflowId: String? = null
    ) = Collection(
        id = UUID.random(),
        name = "Test",
        languageTag = "en",
        type = CollectionType.STANDARD,
        workflowStateId = workflowStateId,
        workflowStateValid = workflowStateValid,
        workflowStatePendingId = workflowStatePendingId,
        deleteWorkflowId = deleteWorkflowId
    )

    @Test
    fun `state returns workflowStateId`() {
        val collection = createCollection(workflowStateId = "published")
        val workflow = CollectionWorkflow(collection)

        assertEquals("published", controller.state(workflow))
    }

    @Test
    fun `stateValid returns workflowStateValid`() {
        val validUntil = OffsetDateTime.now().plusDays(1)
        val collection = createCollection(workflowStateValid = validUntil)
        val workflow = CollectionWorkflow(collection)

        assertEquals(validUntil, controller.stateValid(workflow))
    }

    @Test
    fun `stateValid returns null when not set`() {
        val collection = createCollection(workflowStateValid = null)
        val workflow = CollectionWorkflow(collection)

        assertNull(controller.stateValid(workflow))
    }

    @Test
    fun `pending returns workflowStatePendingId`() {
        val collection = createCollection(workflowStatePendingId = "review")
        val workflow = CollectionWorkflow(collection)

        assertEquals("review", controller.pending(workflow))
    }

    @Test
    fun `pending returns null when no pending state`() {
        val collection = createCollection(workflowStatePendingId = null)
        val workflow = CollectionWorkflow(collection)

        assertNull(controller.pending(workflow))
    }

    @Test
    fun `deleteWorkflow returns deleteWorkflowId`() {
        val collection = createCollection(deleteWorkflowId = "delete-wf")
        val workflow = CollectionWorkflow(collection)

        assertEquals("delete-wf", controller.deleteWorkflow(workflow))
    }

    @Test
    fun `running returns 0 when no active jobs`() = runTest {
        val collection = createCollection()
        val workflow = CollectionWorkflow(collection)

        assertEquals(0, controller.running(workflow))
    }

    @Test
    fun `activeJobs returns empty list when no active jobs`() = runTest {
        val collection = createCollection()
        val workflow = CollectionWorkflow(collection)

        assertEquals(emptyList(), controller.activeJobs(workflow))
    }
}
