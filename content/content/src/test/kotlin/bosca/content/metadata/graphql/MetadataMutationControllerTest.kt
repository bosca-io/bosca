package bosca.content.metadata.graphql

import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.CollectionTemplateService
import bosca.content.metadata.service.DataService
import bosca.content.metadata.service.DataTemplateService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.DocumentTemplateService
import bosca.content.metadata.service.GuideService
import bosca.content.metadata.service.GuideTemplateService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.content.metadata.model.MetadataParentCollection
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.model.MetadataRelationshipInput
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.slug.service.SlugService
import bosca.source.service.SourceService
import bosca.storage.service.ObjectStorageService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class MetadataMutationControllerTest {

    private val metadataService = mockk<MetadataService>()
    private val collectionService = mockk<CollectionService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val collectionPermissionEvaluator = mockk<CollectionPermissionEvaluator>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val storage = mockk<ObjectStorageService>()
    private val guideService = mockk<GuideService>()
    private val slugService = mockk<SlugService>()
    private val documentService = mockk<DocumentService>()
    private val dataService = mockk<DataService>()
    private val collectionTemplateService = mockk<CollectionTemplateService>()
    private val guideTemplateService = mockk<GuideTemplateService>()
    private val documentTemplateService = mockk<DocumentTemplateService>()
    private val dataTemplateService = mockk<DataTemplateService>()
    private val json = Json
    private val videoService = mockk<bosca.content.video.service.VideoService>()
    private val sourceService = mockk<SourceService>()

    private val controller = MetadataMutationController(
        service = metadataService,
        collectionService = collectionService,
        permissionEvaluator = metadataPermissionEvaluator,
        collectionPermissionEvaluator = collectionPermissionEvaluator,
        groupEvaluator = groupEvaluator,
        storage = storage,
        guideService = guideService,
        slugService = slugService,
        documentService = documentService,
        dataService = dataService,
        collectionTemplateService = collectionTemplateService,
        guideTemplateService = guideTemplateService,
        documentTemplateService = documentTemplateService,
        dataTemplateService = dataTemplateService,
        json = json,
        videoService = videoService,
        sourceService = sourceService,
    )

    private val connectionManager = mockk<bosca.db.ConnectionManager>()

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery {
            bosca.db.connection()
        } returns connectionManager
        coEvery {
            connectionManager.commitTransaction(any())
        } returns Unit
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
    fun `add marks metadata as ready`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val principal = Principal(id = UUID.random())
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        val metadataInput = MetadataInput(name = "test", languageTag = "en", contentType = "text/plain")
        val metadata = Metadata(id = UUID.random(), name = "test", type = MetadataType.STANDARD, contentType = "text/plain", contentLength = null, languageTag = "en", workflowStateId = "draft")

        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.asPrincipal() } returns principal
        coEvery { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { metadataService.add(any(), any(), any()) } returns metadata
        coEvery { metadataService.setReady(any(), any()) } returns metadata

        controller.add(authentication, metadataInput, null, true)

        coVerify {
            metadataService.add(null, null, metadataInput)
            metadataService.setReady(metadata, principal)
        }
    }

    @Test
    fun `addDocument marks metadata as ready`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val principal = Principal(id = UUID.random())
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        val collectionId = UUID.random()
        val templateId = UUID.random()
        val template = Metadata(id = templateId, name = "template", type = MetadataType.STANDARD, contentType = "text/plain", contentLength = null, languageTag = "en", workflowStateId = "draft")
        val metadata = Metadata(id = UUID.random(), name = "test", type = MetadataType.STANDARD, contentType = "text/plain", contentLength = null, languageTag = "en", workflowStateId = "draft")
        val collection = mockk<bosca.content.collection.model.Collection>()

        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.asPrincipal() } returns principal
        every { collection.id } returns collectionId
        every { collection.itemsLocked } returns false
        coEvery { collectionService.getById(collectionId) } returns collection
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, collection, any()) } returns Unit
        coEvery { metadataService.getById(templateId, 1) } returns template
        coEvery { metadataService.addDocument(collectionId, template) } returns metadata
        coEvery { collectionService.addMetadataItem(collectionId, metadata.id, any()) } returns Unit
        coEvery { metadataService.setReady(any(), any()) } returns metadata

        controller.addDocument(authentication, collectionId, templateId, 1, true)

        coVerify {
            metadataService.addDocument(collectionId, template)
            metadataService.setReady(metadata, principal)
        }
    }

    @Test
    fun `addGuide marks metadata as ready`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val principal = Principal(id = UUID.random())
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        val collectionId = UUID.random()
        val templateId = UUID.random()
        val template = Metadata(id = templateId, name = "template", type = MetadataType.STANDARD, contentType = "text/plain", contentLength = null, languageTag = "en", workflowStateId = "draft")
        val metadata = Metadata(id = UUID.random(), name = "test", type = MetadataType.STANDARD, contentType = "text/plain", contentLength = null, languageTag = "en", workflowStateId = "draft")
        val collection = mockk<bosca.content.collection.model.Collection>()

        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.asPrincipal() } returns principal
        every { collection.id } returns collectionId
        every { collection.itemsLocked } returns false
        coEvery { collectionService.getById(collectionId) } returns collection
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, collection, any()) } returns Unit
        coEvery { metadataService.getById(templateId, 1) } returns template
        coEvery { metadataService.addGuide(collectionId, template) } returns metadata
        coEvery { collectionService.addMetadataItem(collectionId, metadata.id, any()) } returns Unit
        coEvery { metadataService.setReady(any(), any()) } returns metadata

        controller.addGuide(authentication, collectionId, templateId, 1, true)

        coVerify {
            metadataService.addGuide(collectionId, template)
            metadataService.setReady(metadata, principal)
        }
    }

    @Test
    fun `setMetadataRelationships updates attributes of existing relationships`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val id1 = UUID.random()
        val id2 = UUID.random()
        val relationship = "test-relationship"
        val metadata = mockk<Metadata>()
        val existingRelationship = MetadataRelationship(id1, id2, relationship, JsonObject(mapOf("old" to JsonPrimitive(true))))
        val newRelationshipInput = MetadataRelationshipInput(id1, id2, relationship, JsonObject(mapOf("new" to JsonPrimitive(true))))

        coEvery { metadataService.getById(id1) } returns metadata
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.getRelationships(id1) } returns listOf(existingRelationship)
        coEvery { metadataService.mergeAttributes(id1, id2, relationship, any()) } returns Unit

        controller.setMetadataRelationships(authentication, id1, listOf(newRelationshipInput))

        coVerify {
            metadataService.mergeAttributes(id1, id2, relationship, newRelationshipInput.attributes!!)
        }
        coVerify(exactly = 0) {
            metadataService.removeRelationship(any(), any(), any())
            metadataService.addRelationship(any<MetadataRelationshipInput>())
        }
    }

    @Test
    fun `setMetadataParentCollections updates attributes of existing parents`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val id = UUID.random()
        val parentId = UUID.random()
        val metadata = mockk<Metadata>()
        val parent = mockk<bosca.content.collection.model.Collection>()
        val existingParent = mockk<bosca.content.collection.model.Collection>()
        val newParentInput = MetadataParentCollection(parentId, JsonObject(mapOf("new" to JsonPrimitive(true))))

        coEvery { metadataService.getById(id) } returns metadata
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.getParents(id) } returns listOf(existingParent)
        coEvery { existingParent.id } returns parentId
        coEvery { collectionService.getById(parentId) } returns parent
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, parent, PermissionAction.EDIT) } returns Unit
        coEvery { collectionService.mergeMetadataItemAttributes(parentId, id, any()) } returns Unit

        controller.setMetadataParentCollections(authentication, id, listOf(newParentInput))

        coVerify {
            collectionService.mergeMetadataItemAttributes(parentId, id, newParentInput.attributes!!)
        }
        coVerify(exactly = 0) {
            collectionService.removeMetadataItem(any(), any())
            collectionService.addMetadataItem(any(), any(), any())
        }
    }

    @Test
    fun `guide returns GuideMutation with correct metadata`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val metadataId = UUID.random()
        val metadataVersion = 1
        val metadata = Metadata(
            id = metadataId,
            name = "test-guide",
            type = MetadataType.STANDARD,
            contentType = "text/plain",
            contentLength = null,
            languageTag = "en",
            workflowStateId = "draft",
            version = metadataVersion,
        )

        coEvery { metadataService.getById(metadataId, metadataVersion) } returns metadata
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT) } returns Unit

        val result = controller.guide(authentication, metadataId, metadataVersion)

        kotlin.test.assertEquals(metadata, result.metadata)
        coVerify { metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT) }
    }

    @Test
    fun `guide throws when metadata not found`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val metadataId = UUID.random()

        coEvery { metadataService.getById(metadataId, 1) } returns null

        try {
            controller.guide(authentication, metadataId, 1)
            kotlin.test.fail("Expected NoSuchElementException")
        } catch (e: NoSuchElementException) {
            // expected
        }
    }

    @Test
    fun `guide throws SecurityException when metadata is locked and user is not SA`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val metadataId = UUID.random()
        val metadata = Metadata(
            id = metadataId,
            name = "locked-guide",
            type = MetadataType.STANDARD,
            contentType = "text/plain",
            contentLength = null,
            languageTag = "en",
            workflowStateId = "draft",
            version = 1,
            locked = true,
        )

        coEvery { metadataService.getById(metadataId, 1) } returns metadata
        coEvery { groupEvaluator.hasSaGroup(authentication) } returns false

        try {
            controller.guide(authentication, metadataId, 1)
            kotlin.test.fail("Expected SecurityException")
        } catch (e: SecurityException) {
            kotlin.test.assertEquals("locked", e.message)
        }
    }

    @Test
    fun `guide allows SA users to access locked metadata`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val metadataId = UUID.random()
        val metadata = Metadata(
            id = metadataId,
            name = "locked-guide",
            type = MetadataType.STANDARD,
            contentType = "text/plain",
            contentLength = null,
            languageTag = "en",
            workflowStateId = "draft",
            version = 1,
            locked = true,
        )

        coEvery { metadataService.getById(metadataId, 1) } returns metadata
        coEvery { groupEvaluator.hasSaGroup(authentication) } returns true
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT) } returns Unit

        val result = controller.guide(authentication, metadataId, 1)

        kotlin.test.assertEquals(metadata, result.metadata)
    }

    private fun commentMetadata(id: UUID, locked: Boolean = false, repliesEnabled: Boolean = false) = Metadata(
        id = id,
        name = "commentable",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "draft",
        version = 1,
        locked = locked,
        commentRepliesEnabled = repliesEnabled,
    )

    @Test
    fun `setMetadataCommentsEnabled enables comments without touching replies`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val metadataId = UUID.random()
        val metadata = commentMetadata(metadataId)

        coEvery { metadataService.getById(metadataId) } returns metadata
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setCommentsEnabled(metadataId, true) } returns Unit

        val result = controller.setMetadataCommentsEnabled(authentication, metadataId, true)

        kotlin.test.assertEquals(true, result)
        coVerify(exactly = 1) { metadataService.setCommentsEnabled(metadataId, true) }
        coVerify(exactly = 0) { metadataService.setCommentRepliesEnabled(any(), any()) }
    }

    @Test
    fun `setMetadataCommentsEnabled disabling comments cascades replies off`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val metadataId = UUID.random()
        val metadata = commentMetadata(metadataId, repliesEnabled = true)

        coEvery { metadataService.getById(metadataId) } returns metadata
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setCommentsEnabled(metadataId, false) } returns Unit
        coEvery { metadataService.setCommentRepliesEnabled(metadataId, false) } returns Unit

        val result = controller.setMetadataCommentsEnabled(authentication, metadataId, false)

        kotlin.test.assertEquals(true, result)
        coVerify(exactly = 1) { metadataService.setCommentsEnabled(metadataId, false) }
        coVerify(exactly = 1) { metadataService.setCommentRepliesEnabled(metadataId, false) }
    }

    @Test
    fun `setMetadataCommentsEnabled throws SecurityException when locked and not SA`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val metadataId = UUID.random()
        val metadata = commentMetadata(metadataId, locked = true)

        coEvery { metadataService.getById(metadataId) } returns metadata
        coEvery { groupEvaluator.hasSaGroup(authentication) } returns false

        try {
            controller.setMetadataCommentsEnabled(authentication, metadataId, true)
            kotlin.test.fail("Expected SecurityException")
        } catch (e: SecurityException) {
            kotlin.test.assertEquals("locked", e.message)
        }

        coVerify(exactly = 0) { metadataService.setCommentsEnabled(any(), any()) }
    }
}
