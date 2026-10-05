package bosca.content.collection.graphql

import bosca.content.collection.model.CollectionMetadataRelationship
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CollectionMetadataRelationshipControllerTest {

    private val metadataService = mockk<MetadataService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>()

    private val controller = CollectionMetadataRelationshipController(
        metadataService,
        metadataPermissionEvaluator
    )

    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `metadata returns metadata when found and allowed`() = runTest {
        val metadataId = UUID.random()
        val collectionId = UUID.random()
        val relationship = CollectionMetadataRelationship(
            collectionId = collectionId,
            metadataId = metadataId,
            relationship = "related"
        )
        val metadata = Metadata(
            id = metadataId,
            name = "Test",
            type = MetadataType.STANDARD,
            contentType = "text/plain",
            contentLength = null,
            languageTag = "en",
            workflowStateId = "published"
        )

        coEvery { metadataService.getById(metadataId) } returns metadata
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW) } returns Unit

        val result = controller.metadata(authentication, relationship)

        assertEquals(metadata, result)
    }

    @Test
    fun `metadata throws when metadata not found`() = runTest {
        val metadataId = UUID.random()
        val collectionId = UUID.random()
        val relationship = CollectionMetadataRelationship(
            collectionId = collectionId,
            metadataId = metadataId,
            relationship = "related"
        )

        coEvery { metadataService.getById(metadataId) } returns null

        assertFailsWith<NoSuchElementException> {
            controller.metadata(authentication, relationship)
        }
    }

    @Test
    fun `relationship returns relationship string`() {
        val relationship = CollectionMetadataRelationship(
            collectionId = UUID.random(),
            metadataId = UUID.random(),
            relationship = "linked"
        )

        assertEquals("linked", controller.relationship(relationship))
    }

    @Test
    fun `attributes returns attributes from relationship`() {
        val attrs = JsonObject(mapOf("key" to JsonPrimitive("value")))
        val relationship = CollectionMetadataRelationship(
            collectionId = UUID.random(),
            metadataId = UUID.random(),
            relationship = "linked",
            attributes = attrs
        )

        assertEquals(attrs, controller.attributes(relationship))
    }

    @Test
    fun `attributes returns null when no attributes`() {
        val relationship = CollectionMetadataRelationship(
            collectionId = UUID.random(),
            metadataId = UUID.random(),
            relationship = "linked"
        )

        assertEquals(null, controller.attributes(relationship))
    }
}
