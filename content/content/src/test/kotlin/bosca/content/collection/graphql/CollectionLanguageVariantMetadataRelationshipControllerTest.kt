package bosca.content.collection.graphql

import bosca.content.collection.model.CollectionLanguageVariantMetadataRelationship
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

class CollectionLanguageVariantMetadataRelationshipControllerTest {

    private val metadataService = mockk<MetadataService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>()

    private val controller = CollectionLanguageVariantMetadataRelationshipController(
        metadataService,
        metadataPermissionEvaluator
    )

    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `metadata returns metadata when found and permission verified`() = runTest {
        val metadataId = UUID.random()
        val relationship = CollectionLanguageVariantMetadataRelationship(
            collectionId = UUID.random(),
            metadataId = metadataId,
            languageTag = "en",
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
    fun `metadata throws when not found`() = runTest {
        val metadataId = UUID.random()
        val relationship = CollectionLanguageVariantMetadataRelationship(
            collectionId = UUID.random(),
            metadataId = metadataId,
            languageTag = "en",
            relationship = "related"
        )

        coEvery { metadataService.getById(metadataId) } returns null

        assertFailsWith<NoSuchElementException> {
            controller.metadata(authentication, relationship)
        }
    }

    @Test
    fun `relationship returns relationship string`() {
        val relationship = CollectionLanguageVariantMetadataRelationship(
            collectionId = UUID.random(),
            metadataId = UUID.random(),
            languageTag = "en",
            relationship = "associated"
        )

        assertEquals("associated", controller.relationship(relationship))
    }

    @Test
    fun `languageTag returns the language tag`() {
        val relationship = CollectionLanguageVariantMetadataRelationship(
            collectionId = UUID.random(),
            metadataId = UUID.random(),
            languageTag = "fr",
            relationship = "related"
        )

        assertEquals("fr", controller.languageTag(relationship))
    }

    @Test
    fun `attributes returns attributes from relationship`() {
        val attrs = JsonObject(mapOf("key" to JsonPrimitive("value")))
        val relationship = CollectionLanguageVariantMetadataRelationship(
            collectionId = UUID.random(),
            metadataId = UUID.random(),
            languageTag = "en",
            relationship = "related",
            attributes = attrs
        )

        assertEquals(attrs, controller.attributes(relationship))
    }
}
