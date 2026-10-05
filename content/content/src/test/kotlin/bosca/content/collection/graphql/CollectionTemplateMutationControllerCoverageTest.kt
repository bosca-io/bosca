package bosca.content.collection.graphql

import bosca.content.collection.model.CollectionTemplateMutation
import bosca.content.metadata.model.CollectionTemplateFilterInput
import bosca.content.metadata.model.CollectionTemplateFiltersInput
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.CollectionTemplateService
import bosca.content.ordering.Order
import bosca.content.ordering.OrderingInput
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Coverage for the resolvers not exercised by [CollectionTemplateMutationControllerTest]:
 * `setFilters` and `setOrdering`, plus the permission-denied arm shared by every resolver.
 */
class CollectionTemplateMutationControllerCoverageTest {

    private val templateService = mockk<CollectionTemplateService>()
    private val permissionEvaluator = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val authContext = mockk<AuthenticationContext>(relaxed = true)
    private val controller = CollectionTemplateMutationController(templateService, permissionEvaluator)

    private val metadata = Metadata(
        id = UUID.random(),
        name = "Template",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "draft",
        version = 2
    )
    private val mutation = CollectionTemplateMutation(metadata)

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `setFilters delegates to service and returns metadata`() = runTest {
        val filters = CollectionTemplateFiltersInput(
            filters = listOf(CollectionTemplateFilterInput(filter = "type == 'article'", name = "Articles"))
        )
        coEvery { templateService.setFilters(metadata.id, metadata.version, filters) } returns Unit

        val result = controller.setFilters(authContext, mutation, filters)

        assertEquals(metadata, result)
        coVerify {
            permissionEvaluator.verifyAllowed(authContext, metadata, PermissionAction.EDIT)
            templateService.setFilters(metadata.id, metadata.version, filters)
        }
    }

    @Test
    fun `setOrdering delegates to service and returns metadata`() = runTest {
        val ordering = listOf(
            OrderingInput(field = "name", order = Order.ASCENDING),
            OrderingInput(field = "created", order = Order.DESCENDING)
        )
        coEvery { templateService.setOrdering(metadata.id, metadata.version, ordering) } returns Unit

        val result = controller.setOrdering(authContext, mutation, ordering)

        assertEquals(metadata, result)
        coVerify {
            permissionEvaluator.verifyAllowed(authContext, metadata, PermissionAction.EDIT)
            templateService.setOrdering(metadata.id, metadata.version, ordering)
        }
    }

    @Test
    fun `setOrdering with empty list still delegates`() = runTest {
        val ordering = emptyList<OrderingInput>()
        coEvery { templateService.setOrdering(metadata.id, metadata.version, ordering) } returns Unit

        val result = controller.setOrdering(authContext, mutation, ordering)

        assertEquals(metadata, result)
        coVerify { templateService.setOrdering(metadata.id, metadata.version, ordering) }
    }

    @Test
    fun `setFilters propagates permission denial and skips service`() = runTest {
        val filters = CollectionTemplateFiltersInput(filters = emptyList())
        coEvery {
            permissionEvaluator.verifyAllowed(authContext, metadata, PermissionAction.EDIT)
        } throws IllegalStateException("unauthorized")

        assertFailsWith<IllegalStateException> {
            controller.setFilters(authContext, mutation, filters)
        }

        coVerify(exactly = 0) { templateService.setFilters(any(), any(), any()) }
    }

    @Test
    fun `setOrdering propagates permission denial and skips service`() = runTest {
        val ordering = listOf(OrderingInput(field = "name", order = Order.ASCENDING))
        coEvery {
            permissionEvaluator.verifyAllowed(authContext, metadata, PermissionAction.EDIT)
        } throws IllegalStateException("unauthorized")

        assertFailsWith<IllegalStateException> {
            controller.setOrdering(authContext, mutation, ordering)
        }

        coVerify(exactly = 0) { templateService.setOrdering(any(), any(), any()) }
    }
}
