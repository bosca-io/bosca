@file:OptIn(ExperimentalUuidApi::class)

package bosca.recommendations.graphql

import bosca.recommendations.model.RecommendationCollectionFilter
import bosca.recommendations.model.RecommendationContentFilter
import bosca.recommendations.model.RecommendationContext
import bosca.recommendations.model.RecommendationContextInput
import bosca.recommendations.model.RecommendationMetadataFilter
import bosca.recommendations.service.RecommendationContextService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

class RecommendationContextsControllerTest {

    private val contextService = mockk<RecommendationContextService>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private val query = RecommendationContextsController(contextService, groupEvaluator, mockk())
    private val mutation = RecommendationContextsMutationController(contextService, groupEvaluator, mockk())
    private val authentication = mockk<AuthenticationContext>(relaxed = true)

    @Test
    fun `queries verify admin access and delegate by id and type`() = runTest {
        val context = RecommendationContext(type = "default", name = "Default")
        coEvery { contextService.getAll() } returns listOf(context)
        coEvery { contextService.getById(context.id) } returns context
        coEvery { contextService.getByType("default") } returns context

        assertEquals(listOf(context), query.all(authentication))
        assertEquals(context, query.context(authentication, context.id))
        assertEquals(context, query.contextByType(authentication, "default"))

        coVerify(exactly = 3) { groupEvaluator.verifyHasAdminGroup(authentication) }
        coVerify { contextService.getAll() }
        coVerify { contextService.getById(context.id) }
        coVerify { contextService.getByType("default") }
    }

    @Test
    fun `query returns null for a missing context`() = runTest {
        val id = UUID.random()
        coEvery { contextService.getById(id) } returns null

        assertNull(query.context(authentication, id))

        coVerify { groupEvaluator.verifyHasAdminGroup(authentication) }
    }

    @Test
    fun `mutations verify admin access and delegate`() = runTest {
        val id = UUID.random()
        val input = RecommendationContextInput(type = "image_picker", name = "Image picker")
        val created = RecommendationContext(id = id, type = input.type, name = input.name)
        val edited = created.copy(name = "Images")
        coEvery { contextService.add(input) } returns created
        coEvery { contextService.edit(id, input) } returns edited

        assertEquals(created, mutation.add(authentication, input))
        assertEquals(edited, mutation.edit(authentication, id, input))
        assertTrue(mutation.delete(authentication, id))

        coVerify(exactly = 3) { groupEvaluator.verifyHasAdminGroup(authentication) }
        coVerify { contextService.add(input) }
        coVerify { contextService.edit(id, input) }
        coVerify { contextService.delete(id) }
    }

    @Test
    fun `type controllers expose context and filter fields`() {
        val filter = RecommendationContentFilter(
            metadata = RecommendationMetadataFilter(
                includedContentTypePrefixes = listOf("image/"),
                includedAttributeTypes = listOf("hero"),
            ),
            collections = RecommendationCollectionFilter(
                includedTypes = listOf("standard"),
                includedAttributeTypes = listOf("gallery"),
            ),
        )
        val context = RecommendationContext(type = "image_picker", name = "Image picker", contentFilter = filter)
        val contextController = RecommendationContextController()
        val filterController = RecommendationContentFilterController()

        assertEquals(context.id, contextController.id(context))
        assertEquals("image_picker", contextController.type(context))
        assertEquals("Image picker", contextController.name(context))
        assertEquals("", contextController.description(context))
        assertEquals(filter, contextController.contentFilter(context))
        assertEquals(context.created, contextController.created(context))
        assertEquals(context.modified, contextController.modified(context))
        assertEquals(filter.metadata, filterController.metadata(filter))
        assertEquals(filter.collections, filterController.collections(filter))

        val metadataController = RecommendationMetadataFilterController()
        assertEquals(listOf("image/"), metadataController.includedContentTypePrefixes(filter.metadata))
        assertEquals(filter.metadata.excludedContentTypePrefixes, metadataController.excludedContentTypePrefixes(filter.metadata))
        assertEquals(listOf("hero"), metadataController.includedAttributeTypes(filter.metadata))
        assertEquals(emptyList(), metadataController.excludedAttributeTypes(filter.metadata))

        val collectionFilter = checkNotNull(filter.collections)
        val collectionController = RecommendationCollectionFilterController()
        assertEquals(listOf("standard"), collectionController.includedTypes(collectionFilter))
        assertEquals(emptyList(), collectionController.excludedTypes(collectionFilter))
        assertEquals(listOf("gallery"), collectionController.includedAttributeTypes(collectionFilter))
        assertEquals(emptyList(), collectionController.excludedAttributeTypes(collectionFilter))
    }
}
