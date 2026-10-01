package bosca.content.metadata.graphql

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataJobHistoryService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetadataWorkflowControllerTest {

    private val metadataJobHistoryService = mockk<MetadataJobHistoryService> {
        coEvery { getActiveJobs(any(), any()) } returns emptyList()
    }
    private val controller = MetadataWorkflowController(metadataJobHistoryService)

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun createMetadata(
        workflowStateId: String = "draft",
        workflowStateValid: OffsetDateTime? = null,
        workflowStatePendingId: String? = null,
        deleteWorkflowId: String? = null
    ) = Metadata(
        id = UUID.random(),
        name = "Test",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = workflowStateId,
        workflowStateValid = workflowStateValid,
        workflowStatePendingId = workflowStatePendingId,
        deleteWorkflowId = deleteWorkflowId
    )

    @Test
    fun `state returns workflowStateId`() {
        val metadata = createMetadata(workflowStateId = "published")
        val workflow = MetadataWorkflow(metadata)

        assertEquals("published", controller.state(workflow))
    }

    @Test
    fun `stateValid returns workflowStateValid`() {
        val validUntil = OffsetDateTime.now().plusDays(1)
        val metadata = createMetadata(workflowStateValid = validUntil)
        val workflow = MetadataWorkflow(metadata)

        assertEquals(validUntil, controller.stateValid(workflow))
    }

    @Test
    fun `stateValid returns null when not set`() {
        val metadata = createMetadata(workflowStateValid = null)
        val workflow = MetadataWorkflow(metadata)

        assertNull(controller.stateValid(workflow))
    }

    @Test
    fun `pending returns workflowStatePendingId`() {
        val metadata = createMetadata(workflowStatePendingId = "review")
        val workflow = MetadataWorkflow(metadata)

        assertEquals("review", controller.pending(workflow))
    }

    @Test
    fun `pending returns null when no pending state`() {
        val metadata = createMetadata(workflowStatePendingId = null)
        val workflow = MetadataWorkflow(metadata)

        assertNull(controller.pending(workflow))
    }

    @Test
    fun `deleteWorkflow returns deleteWorkflowId`() {
        val metadata = createMetadata(deleteWorkflowId = "delete-wf")
        val workflow = MetadataWorkflow(metadata)

        assertEquals("delete-wf", controller.deleteWorkflow(workflow))
    }

    @Test
    fun `running returns 0 when no active jobs`() = runTest {
        val metadata = createMetadata()
        val workflow = MetadataWorkflow(metadata)

        assertEquals(0, controller.running(workflow))
    }

    @Test
    fun `activeJobs returns empty list when no active jobs`() = runTest {
        val metadata = createMetadata()
        val workflow = MetadataWorkflow(metadata)

        assertEquals(emptyList(), controller.activeJobs(workflow))
    }
}
