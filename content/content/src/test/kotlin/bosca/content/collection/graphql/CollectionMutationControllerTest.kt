package bosca.content.collection.graphql

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionInput
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.model.CollectionMetadataRelationship
import bosca.content.collection.model.CollectionMetadataRelationshipInput
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.slug.service.SlugService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CollectionMutationControllerTest {

    private val collectionService = mockk<CollectionService>()
    private val collectionPermissionEvaluator = mockk<CollectionPermissionEvaluator>()
    private val metadataService = mockk<MetadataService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val slugService = mockk<SlugService>()
    private val json = Json

    private val controller = CollectionMutationController(
        collectionService,
        collectionPermissionEvaluator,
        metadataService,
        metadataPermissionEvaluator,
        groupEvaluator,
        slugService,
        json
    )

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery {
            bosca.db.transaction<Any?>(any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[0] as suspend () -> Any?
            block()
        }
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    @Test
    fun `add marks collection as ready`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val principal = Principal(id = UUID.random())
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        val collectionInput = CollectionInput(name = "test", languageTag = "en")
        val collection = Collection(id = UUID.random(), name = "test", languageTag = "en", workflowStateId = "draft")

        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.asPrincipal() } returns principal
        coEvery { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { collectionService.add(any(), any(), any()) } returns collection
        coEvery { collectionService.setReady(any() as Collection, any()) } returns Unit

        controller.add(authentication, collectionInput, null, true)

        coVerify {
            collectionService.add(collectionInput, null, null)
            collectionService.setReady(collection, principal)
        }
    }

    private fun createCollection(id: UUID = UUID.random()) = Collection(
        id = id,
        name = "test",
        languageTag = "en",
        workflowStateId = "draft",
        public = false,
        publicList = false,
        publicSupplementary = false
    )

    private fun createVariant(id: UUID = UUID.random(), languageTag: String = "es") = CollectionLanguageVariant(
        id = id,
        languageTag = languageTag,
        name = "Spanish Variant",
        public = false,
        publicList = false,
        publicSupplementary = false
    )

    @Test
    fun `setPublic without languageTag updates collection`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val id = UUID.random()
        val collection = createCollection(id)

        coEvery { collectionService.getById(id) } returns collection
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT) } returns Unit
        coEvery { collectionService.setPublic(id, true, null) } returns Unit

        val result = controller.setPublic(authentication, id, true)

        assertEquals(true, result.public)
        coVerify { collectionService.setPublic(id, true, null) }
        coVerify(exactly = 0) { collectionService.getLanguageVariant(any(), any()) }
    }

    @Test
    fun `setPublic with languageTag updates variant`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val id = UUID.random()
        val collection = createCollection(id)
        val variant = createVariant(id, "es")

        coEvery { collectionService.getById(id) } returns collection
        coEvery { collectionService.getLanguageVariant(id, "es") } returns variant
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, variant, PermissionAction.EDIT) } returns Unit
        coEvery { collectionService.setPublic(id, true, "es") } returns Unit

        val result = controller.setPublic(authentication, id, true, "es")

        // Collection returned should not have its own public changed
        assertEquals(false, result.public)
        coVerify { collectionService.setPublic(id, true, "es") }
        coVerify { collectionPermissionEvaluator.verifyAllowed(authentication, variant, PermissionAction.EDIT) }
    }

    @Test
    fun `setPublicList without languageTag updates collection`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val id = UUID.random()
        val collection = createCollection(id)

        coEvery { collectionService.getById(id) } returns collection
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT) } returns Unit
        coEvery { collectionService.setPublicList(id, true, null) } returns Unit

        val result = controller.setPublicList(authentication, id, true)

        assertEquals(true, result.publicList)
        coVerify { collectionService.setPublicList(id, true, null) }
    }

    @Test
    fun `setPublicList with languageTag updates variant`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val id = UUID.random()
        val collection = createCollection(id)
        val variant = createVariant(id, "es")

        coEvery { collectionService.getById(id) } returns collection
        coEvery { collectionService.getLanguageVariant(id, "es") } returns variant
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, variant, PermissionAction.EDIT) } returns Unit
        coEvery { collectionService.setPublicList(id, true, "es") } returns Unit

        val result = controller.setPublicList(authentication, id, true, "es")

        assertEquals(false, result.publicList)
        coVerify { collectionService.setPublicList(id, true, "es") }
    }

    @Test
    fun `setPublicSupplementary without languageTag updates collection`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val id = UUID.random()
        val collection = createCollection(id)

        coEvery { collectionService.getById(id) } returns collection
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT) } returns Unit
        coEvery { collectionService.setPublicSupplementary(id, true, null) } returns Unit

        val result = controller.setPublicSupplementary(authentication, id, true)

        assertEquals(true, result.publicSupplementary)
        coVerify { collectionService.setPublicSupplementary(id, true, null) }
    }

    @Test
    fun `setPublicSupplementary with languageTag updates variant`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val id = UUID.random()
        val collection = createCollection(id)
        val variant = createVariant(id, "es")

        coEvery { collectionService.getById(id) } returns collection
        coEvery { collectionService.getLanguageVariant(id, "es") } returns variant
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, variant, PermissionAction.EDIT) } returns Unit
        coEvery { collectionService.setPublicSupplementary(id, true, "es") } returns Unit

        val result = controller.setPublicSupplementary(authentication, id, true, "es")

        assertEquals(false, result.publicSupplementary)
        coVerify { collectionService.setPublicSupplementary(id, true, "es") }
    }

    @Test
    fun `setMetadataRelationships updates attributes of existing relationships`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val id = UUID.random()
        val metadataId = UUID.random()
        val relationship = "test-relationship"
        val collection = createCollection(id)
        val existingRelationship = CollectionMetadataRelationship(id, metadataId, relationship, JsonObject(mapOf("old" to JsonPrimitive(true))))
        val newRelationshipInput = CollectionMetadataRelationshipInput(id = id, metadataId = metadataId, relationship = relationship, attributes = JsonObject(mapOf("new" to JsonPrimitive(true))))

        coEvery { collectionService.getById(id) } returns collection
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT) } returns Unit
        coEvery { collectionService.getMetadataRelationships(id) } returns listOf(existingRelationship)
        coEvery { collectionService.mergeMetadataRelationshipAttributes(id, metadataId, relationship, any()) } returns existingRelationship

        controller.setMetadataRelationships(authentication, id, listOf(newRelationshipInput))

        coVerify {
            collectionService.mergeMetadataRelationshipAttributes(id, metadataId, relationship, newRelationshipInput.attributes!!)
        }
        coVerify(exactly = 0) {
            collectionService.deleteMetadataRelationship(any(), any(), any())
            collectionService.addMetadataRelationship(any())
        }
    }
}
