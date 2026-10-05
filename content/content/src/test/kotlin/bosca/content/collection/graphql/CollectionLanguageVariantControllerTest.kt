package bosca.content.collection.graphql

import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.service.CollectionJobHistoryService
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.slug.service.SlugService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CollectionLanguageVariantControllerTest {
    private val slugService = mockk<SlugService>()
    private val collectionService = mockk<CollectionService>()
    private val collectionJobHistoryService = mockk<CollectionJobHistoryService> {
        coEvery { getActiveJobs(any()) } returns emptyList()
    }
    private val metadataService = mockk<MetadataService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val controller = CollectionLanguageVariantController(
        slugService = slugService,
        collectionService = collectionService,
        metadataService = metadataService,
        metadataPermissionEvaluator = metadataPermissionEvaluator
    )
    private val workflowController = CollectionWorkflowController(collectionService, collectionJobHistoryService)

    private fun createVariant(
        workflowStateId: String = "draft",
        workflowStatePendingId: String? = null,
        workflowStateValid: OffsetDateTime? = null,
        deleteWorkflowId: String? = null,
    ) = CollectionLanguageVariant(
        id = UUID.random(),
        languageTag = "es",
        name = "Spanish Variant",
        description = "Test description",
        attributes = null,
        workflowStateId = workflowStateId,
        workflowStatePendingId = workflowStatePendingId,
        workflowStateValid = workflowStateValid,
        deleteWorkflowId = deleteWorkflowId,
    )

    @Test
    fun `workflow field returns CollectionWorkflow wrapper`() {
        val variant = createVariant()
        val workflow = controller.workflow(variant)
        assertNotNull(workflow)
        assertEquals(variant.workflowStateId, workflow.collection.workflowStateId)
    }

    @Test
    fun `workflow state returns workflowStateId`() {
        val variant = createVariant(workflowStateId = "published")
        val workflow = controller.workflow(variant)
        assertEquals("published", workflowController.state(workflow))
    }

    @Test
    fun `workflow pending returns workflowStatePendingId`() {
        val variant = createVariant(workflowStatePendingId = "review")
        val workflow = controller.workflow(variant)
        assertEquals("review", workflowController.pending(workflow))
    }

    @Test
    fun `workflow pending returns null when no pending state`() {
        val variant = createVariant()
        val workflow = controller.workflow(variant)
        assertNull(workflowController.pending(workflow))
    }

    @Test
    fun `workflow stateValid returns workflowStateValid`() {
        val now = OffsetDateTime.now()
        val variant = createVariant(workflowStateValid = now)
        val workflow = controller.workflow(variant)
        assertEquals(now, workflowController.stateValid(workflow))
    }

    @Test
    fun `workflow deleteWorkflow returns deleteWorkflowId`() {
        val variant = createVariant(deleteWorkflowId = "delete-wf-1")
        val workflow = controller.workflow(variant)
        assertEquals("delete-wf-1", workflowController.deleteWorkflow(workflow))
    }

    @Test
    fun `workflow running returns zero`() = runTest {
        val variant = createVariant()
        val workflow = controller.workflow(variant)
        assertEquals(0, workflowController.running(workflow))
    }

    @Test
    fun `variant defaults to pending workflow state`() {
        val variant = CollectionLanguageVariant(
            id = UUID.random(),
            languageTag = "fr",
            name = "French",
        )
        assertEquals("pending", variant.workflowStateId)
        assertNull(variant.workflowStatePendingId)
        assertNull(variant.workflowStateValid)
        assertNull(variant.deleteWorkflowId)
    }

    @Test
    fun `isPublished returns true when state is published`() {
        val variant = createVariant(workflowStateId = "published")
        assertEquals(true, variant.isPublished)
        assertEquals(false, variant.isAdvertised)
    }

    @Test
    fun `isPublished returns false when state is draft`() {
        val variant = createVariant(workflowStateId = "draft")
        assertEquals(false, variant.isPublished)
        assertEquals(false, variant.isAdvertised)
    }

    @Test
    fun `isAdvertised returns true when state is advertised`() {
        val variant = createVariant(workflowStateId = "advertised")
        assertEquals(true, variant.isAdvertised)
        assertEquals(false, variant.isPublished)
    }

    @Test
    fun `isPublished and isAdvertised both false for review state`() {
        val variant = createVariant(workflowStateId = "review")
        assertEquals(false, variant.isPublished)
        assertEquals(false, variant.isAdvertised)
    }

    @Test
    fun `controller returns correct languageTag`() {
        val variant = createVariant()
        assertEquals("es", controller.languageTag(variant))
    }

    @Test
    fun `controller returns correct name`() {
        val variant = createVariant()
        assertEquals("Spanish Variant", controller.name(variant))
    }

    @Test
    fun `controller returns variant recommendation eligibility`() {
        assertEquals(false, controller.recommendable(createVariant().copy(recommendable = false)))
    }

    @Test
    fun `controller returns correct description`() {
        val variant = createVariant()
        assertEquals("Test description", controller.description(variant))
    }

    @Test
    fun `controller returns null description when not set`() {
        val variant = CollectionLanguageVariant(
            id = UUID.random(),
            languageTag = "fr",
            name = "French",
        )
        assertNull(controller.description(variant))
    }

    @Test
    fun `controller returns correct attributes`() {
        val variant = createVariant()
        assertNull(controller.attributes(variant))
    }

    @Test
    fun `workflow stateValid returns null when not set`() {
        val variant = createVariant()
        val workflow = controller.workflow(variant)
        assertNull(workflowController.stateValid(workflow))
    }

    @Test
    fun `workflow deleteWorkflow returns null when not set`() {
        val variant = createVariant()
        val workflow = controller.workflow(variant)
        assertNull(workflowController.deleteWorkflow(workflow))
    }

    @Test
    fun `workflow state returns pending for default variant`() {
        val variant = CollectionLanguageVariant(
            id = UUID.random(),
            languageTag = "fr",
            name = "French",
        )
        val workflow = controller.workflow(variant)
        assertEquals("pending", workflowController.state(workflow))
    }

    @Test
    fun `workflow state returns advertised`() {
        val variant = createVariant(workflowStateId = "advertised")
        val workflow = controller.workflow(variant)
        assertEquals("advertised", workflowController.state(workflow))
    }

    @Test
    fun `isPublished and isAdvertised both false for pending state`() {
        val variant = createVariant(workflowStateId = "pending")
        assertEquals(false, variant.isPublished)
        assertEquals(false, variant.isAdvertised)
    }

    @Test
    fun `variant with all workflow fields populated`() = runTest {
        val now = OffsetDateTime.now()
        val variant = createVariant(
            workflowStateId = "review",
            workflowStatePendingId = "published",
            workflowStateValid = now,
            deleteWorkflowId = "delete-wf"
        )
        val workflow = controller.workflow(variant)
        assertEquals("review", workflowController.state(workflow))
        assertEquals("published", workflowController.pending(workflow))
        assertEquals(now, workflowController.stateValid(workflow))
        assertEquals("delete-wf", workflowController.deleteWorkflow(workflow))
        assertEquals(0, workflowController.running(workflow))
    }

    @Test
    fun `workflow wraps variant data into Collection correctly`() {
        val id = UUID.random()
        val now = OffsetDateTime.now()
        val variant = CollectionLanguageVariant(
            id = id,
            languageTag = "es",
            name = "Spanish",
            workflowStateId = "published",
            workflowStatePendingId = "advertised",
            workflowStateValid = now,
            deleteWorkflowId = "del-wf",
        )
        val workflow = controller.workflow(variant)
        assertEquals(id, workflow.collection.id)
        assertEquals("es", workflow.collection.languageTag)
        assertEquals("Spanish", workflow.collection.name)
        assertEquals("published", workflow.collection.workflowStateId)
        assertEquals("advertised", workflow.collection.workflowStatePendingId)
        assertEquals(now, workflow.collection.workflowStateValid)
        assertEquals("del-wf", workflow.collection.deleteWorkflowId)
    }
}
