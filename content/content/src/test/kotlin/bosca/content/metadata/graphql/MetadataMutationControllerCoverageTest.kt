package bosca.content.metadata.graphql

import bosca.content.collection.service.CollectionService
import bosca.content.metadata.events.MetadataUpdated
import bosca.content.metadata.events.dispatch
import bosca.content.metadata.model.BibleInput
import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.model.Guide
import bosca.content.metadata.model.GuideStep
import bosca.content.metadata.model.GuideStepModule
import bosca.content.metadata.model.MediaProcessingOptions
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.model.MetadataParentCollection
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.model.MetadataRelationshipInput
import bosca.content.metadata.model.MetadataSupplementary
import bosca.content.metadata.model.MetadataSupplementaryInput
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.model.MetadataWorkflowCompleteState
import bosca.content.metadata.model.MetadataWorkflowState
import bosca.content.metadata.model.SourceStatus
import bosca.content.metadata.service.CollaborationSyncMode
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
import bosca.content.video.service.VideoService
import bosca.jobs.BibleProcessJob
import bosca.jobs.ImportUrlJob
import bosca.jobs.enqueue
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.slug.model.Slug
import bosca.slug.service.SlugService
import bosca.source.model.Source
import bosca.source.service.SourceService
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.ObjectPath
import bosca.graphql.scalars.UploadedFile
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class MetadataMutationControllerCoverageTest {

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
    private val videoService = mockk<VideoService>()
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

    private val authentication = mockk<AuthenticationContext>()

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
        mockkStatic("bosca.content.metadata.events.MetadataUpdatedExtKt")
        coEvery { any<MetadataUpdated>().dispatch() } just Runs
        mockkStatic("bosca.jobs.ImportUrlJobExecutorExecutorKt")
        coEvery { any<ImportUrlJob>().enqueue() } returns mockk()
        mockkStatic("bosca.jobs.BibleProcessExecutorExecutorKt")
        coEvery { any<BibleProcessJob>().enqueue() } returns mockk()
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    private fun metadata(
        id: UUID = UUID.random(),
        version: Int = 1,
        locked: Boolean = false,
        public: Boolean = false,
        publicContent: Boolean = false,
        publicSupplementary: Boolean = false,
        commentRepliesEnabled: Boolean = false,
        contentType: String = "text/plain",
        sourceId: UUID? = null,
    ) = Metadata(
        id = id,
        name = "test",
        type = MetadataType.STANDARD,
        contentType = contentType,
        contentLength = null,
        languageTag = "en",
        workflowStateId = "draft",
        version = version,
        locked = locked,
        public = public,
        publicContent = publicContent,
        publicSupplementary = publicSupplementary,
        commentRepliesEnabled = commentRepliesEnabled,
        sourceId = sourceId,
    )

    private fun principalAuth(): Principal {
        val principal = Principal(id = UUID.random())
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.asPrincipal() } returns principal
        every { authenticatedPrincipal.id } returns principal.id
        return principal
    }

    // ---- simple field resolvers ----

    @Test
    fun `comments and ai singletons`() {
        assertEquals(bosca.comments.graphql.CommentsMutation, controller.comments())
        assertEquals(MetadataAIMutation, controller.ai())
    }

    // ---- add ----

    @Test
    fun `add without parent and without setReady`() = runTest {
        val input = MetadataInput(name = "n", languageTag = "en", contentType = "text/plain")
        val created = metadata()
        coEvery { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { metadataService.add(null, null, input) } returns created

        val result = controller.add(authentication, input, null, null)

        assertEquals(created, result)
        coVerify(exactly = 0) { metadataService.setReady(any(), any()) }
    }

    @Test
    fun `add with parent collection and parent metadata`() = runTest {
        val parentCollectionId = UUID.random()
        val parentMetadataId = UUID.random()
        val input = MetadataInput(
            name = "n",
            languageTag = "en",
            contentType = "text/plain",
            parentCollectionId = parentCollectionId,
            parentId = parentMetadataId,
        )
        val parent = mockk<bosca.content.collection.model.Collection>()
        val parentMetadata = metadata(id = parentMetadataId)
        val created = metadata()

        coEvery { collectionService.getById(parentCollectionId) } returns parent
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, parent, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.getById(parentMetadataId) } returns parentMetadata
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, parentMetadata, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.add(parent, null, input) } returns created

        val result = controller.add(authentication, input, null, false)

        assertEquals(created, result)
    }

    @Test
    fun `add throws when parent collection not found`() = runTest {
        val parentCollectionId = UUID.random()
        val input = MetadataInput(name = "n", languageTag = "en", contentType = "text/plain", parentCollectionId = parentCollectionId)
        coEvery { collectionService.getById(parentCollectionId) } returns null
        assertFailsExc<NoSuchElementException> { controller.add(authentication, input, null, null) }
    }

    @Test
    fun `add throws when parent metadata not found`() = runTest {
        val parentMetadataId = UUID.random()
        val input = MetadataInput(name = "n", languageTag = "en", contentType = "text/plain", parentId = parentMetadataId)
        coEvery { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { metadataService.getById(parentMetadataId) } returns null
        assertFailsExc<NoSuchElementException> { controller.add(authentication, input, null, null) }
    }

    @Test
    fun `add setReady with missing principal errors`() = runTest {
        val input = MetadataInput(name = "n", languageTag = "en", contentType = "text/plain")
        val created = metadata()
        coEvery { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { metadataService.add(null, null, input) } returns created
        every { authentication.principal() } returns null
        assertFailsExc<IllegalStateException> { controller.add(authentication, input, null, true) }
    }

    // ---- setParentId ----

    @Test
    fun `setParentId happy path`() = runTest {
        val id = UUID.random()
        val parentId = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setParent(id, parentId) } returns Unit

        val result = controller.setParentId(authentication, id, parentId)
        assertEquals(parentId, result.parentId)
    }

    @Test
    fun `setParentId not found`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns null
        assertFailsExc<NoSuchElementException> { controller.setParentId(authentication, id, UUID.random()) }
    }

    // ---- setMetadataName ----

    @Test
    fun `setMetadataName happy path`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setName(id, "new") } returns md
        assertEquals(md, controller.setMetadataName(authentication, id, "new"))
    }

    @Test
    fun `setMetadataName not found`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns null
        assertFailsExc<NoSuchElementException> { controller.setMetadataName(authentication, id, "new") }
    }

    @Test
    fun `setMetadataName throws when service returns null`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setName(id, "new") } returns null
        assertFailsExc<IllegalStateException> { controller.setMetadataName(authentication, id, "new") }
    }

    // ---- setMetadataSlug ----

    @Test
    fun `setMetadataSlug happy path`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { slugService.deleteMetadataSlug(id) } returns Unit
        coEvery { slugService.get("my-slug") } returns null
        coEvery { slugService.add(any()) } returns Slug(slug = "my-slug", metadataId = id)
        assertEquals("my-slug", controller.setMetadataSlug(authentication, id, "my-slug"))
    }

    @Test
    fun `setMetadataSlug errors when slug exists`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { slugService.deleteMetadataSlug(id) } returns Unit
        coEvery { slugService.get("my-slug") } returns Slug(slug = "my-slug")
        assertFailsExc<IllegalStateException> { controller.setMetadataSlug(authentication, id, "my-slug") }
    }

    @Test
    fun `setMetadataSlug not found`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns null
        assertFailsExc<NoSuchElementException> { controller.setMetadataSlug(authentication, id, "my-slug") }
    }

    // ---- addPermission / deletePermission ----

    @Test
    fun `addPermission happy path`() = runTest {
        val entityId = UUID.random()
        val input = PermissionInput(action = PermissionAction.VIEW, entityId = entityId, groupId = UUID.random())
        val md = metadata(id = entityId)
        val perm = mockk<EntityPermission>()
        coEvery { metadataService.getById(entityId) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.MANAGE) } returns Unit
        coEvery { metadataService.addPermission(input) } returns perm
        assertEquals(perm, controller.addPermission(authentication, input))
    }

    @Test
    fun `addPermission not found`() = runTest {
        val entityId = UUID.random()
        val input = PermissionInput(action = PermissionAction.VIEW, entityId = entityId, groupId = UUID.random())
        coEvery { metadataService.getById(entityId) } returns null
        assertFailsExc<NoSuchElementException> { controller.addPermission(authentication, input) }
    }

    @Test
    fun `deletePermission happy path`() = runTest {
        val entityId = UUID.random()
        val input = PermissionInput(action = PermissionAction.VIEW, entityId = entityId, groupId = UUID.random())
        val md = metadata(id = entityId)
        val perm = mockk<EntityPermission>()
        coEvery { metadataService.getById(entityId) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.MANAGE) } returns Unit
        coEvery { metadataService.deletePermission(input) } returns perm
        assertEquals(perm, controller.deletePermission(authentication, input))
    }

    @Test
    fun `deletePermission not found`() = runTest {
        val entityId = UUID.random()
        val input = PermissionInput(action = PermissionAction.VIEW, entityId = entityId, groupId = UUID.random())
        coEvery { metadataService.getById(entityId) } returns null
        assertFailsExc<NoSuchElementException> { controller.deletePermission(authentication, input) }
    }

    // ---- setPublic / setPublicContent / setPublicSupplementary ----

    @Test
    fun `setPublic happy path`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setPublic(md, true) } returns Unit
        val result = controller.setPublic(authentication, id, true)
        assertTrue(result.public)
    }

    @Test
    fun `setPublic locked non-sa throws`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id, locked = true)
        coEvery { metadataService.getById(id) } returns md
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        assertFailsExc<SecurityException> { controller.setPublic(authentication, id, true) }
    }

    @Test
    fun `setPublic locked sa allowed`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id, locked = true)
        coEvery { metadataService.getById(id) } returns md
        every { groupEvaluator.hasSaGroup(authentication) } returns true
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setPublic(md, false) } returns Unit
        val result = controller.setPublic(authentication, id, false)
        assertFalse(result.public)
    }

    @Test
    fun `setPublic not found`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns null
        assertFailsExc<NoSuchElementException> { controller.setPublic(authentication, id, true) }
    }

    @Test
    fun `setPublicContent happy and locked and not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setPublicContent(md, true) } returns Unit
        assertTrue(controller.setPublicContent(authentication, id, true).publicContent)

        val lockedId = UUID.random()
        val locked = metadata(id = lockedId, locked = true)
        coEvery { metadataService.getById(lockedId) } returns locked
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        assertFailsExc<SecurityException> { controller.setPublicContent(authentication, lockedId, true) }

        val missing = UUID.random()
        coEvery { metadataService.getById(missing) } returns null
        assertFailsExc<NoSuchElementException> { controller.setPublicContent(authentication, missing, true) }
    }

    @Test
    fun `setPublicSupplementary happy and locked and not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setPublicSupplementary(md, true) } returns Unit
        assertTrue(controller.setPublicSupplementary(authentication, id, true).publicSupplementary)

        val lockedId = UUID.random()
        val locked = metadata(id = lockedId, locked = true)
        coEvery { metadataService.getById(lockedId) } returns locked
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        assertFailsExc<SecurityException> { controller.setPublicSupplementary(authentication, lockedId, true) }

        val missing = UUID.random()
        coEvery { metadataService.getById(missing) } returns null
        assertFailsExc<NoSuchElementException> { controller.setPublicSupplementary(authentication, missing, true) }
    }

    // ---- setPublicAll ----

    @Test
    fun `setPublicAll processes allowed items as sa`() = runTest {
        val a = metadata()
        val b = metadata(locked = true)
        val ids = listOf(a.id, b.id)
        every { groupEvaluator.hasSaGroup(authentication) } returns true
        coEvery { metadataService.getByIds(ids) } returns listOf(a, b)
        coEvery { metadataPermissionEvaluator.filterAllowed(authentication, listOf(a, b), PermissionAction.EDIT) } returns listOf(a, b)
        coEvery { metadataService.setPublic(any(), any()) } returns Unit
        coEvery { metadataService.setPublicContent(any(), any()) } returns Unit
        coEvery { metadataService.setPublicSupplementary(any(), any()) } returns Unit
        coEvery { metadataService.setSearchable(any(), any()) } returns Unit

        val count = controller.setPublicAll(authentication, ids, public = true, publicContent = true, publicSupplementary = true, searchable = true)
        assertEquals(2, count)
    }

    @Test
    fun `setPublicAll filters locked when not sa`() = runTest {
        val a = metadata()
        val b = metadata(locked = true)
        val ids = listOf(a.id, b.id)
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        coEvery { metadataService.getByIds(ids) } returns listOf(a, b)
        coEvery { metadataPermissionEvaluator.filterAllowed(authentication, listOf(a), PermissionAction.EDIT) } returns listOf(a)
        coEvery { metadataService.setPublic(any(), any()) } returns Unit
        coEvery { metadataService.setPublicContent(any(), any()) } returns Unit
        coEvery { metadataService.setPublicSupplementary(any(), any()) } returns Unit
        coEvery { metadataService.setSearchable(any(), any()) } returns Unit

        val count = controller.setPublicAll(authentication, ids, public = false, publicContent = false, publicSupplementary = false, searchable = false)
        assertEquals(1, count)
    }

    @Test
    fun `setPublicAll rejects too many`() = runTest {
        val ids = (0..500).map { UUID.random() }
        assertFailsExc<IllegalArgumentException> {
            controller.setPublicAll(authentication, ids, public = true, publicContent = true, publicSupplementary = true, searchable = true)
        }
    }

    // ---- searchable / comments / replies / syncVariant toggles ----

    @Test
    fun `setMetadataSearchable happy locked notfound`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setSearchable(id, true) } returns Unit
        assertTrue(controller.setMetadataSearchable(authentication, id, true))

        val lockedId = UUID.random()
        coEvery { metadataService.getById(lockedId) } returns metadata(id = lockedId, locked = true)
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        assertFailsExc<SecurityException> { controller.setMetadataSearchable(authentication, lockedId, true) }

        val missing = UUID.random()
        coEvery { metadataService.getById(missing) } returns null
        assertFailsExc<NoSuchElementException> { controller.setMetadataSearchable(authentication, missing, true) }
    }

    @Test
    fun `setMetadataRecommendable updates eligible metadata and rejects locked or missing items`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setRecommendable(id, false) } returns Unit
        assertTrue(controller.setMetadataRecommendable(authentication, id, false))

        val lockedId = UUID.random()
        coEvery { metadataService.getById(lockedId) } returns metadata(id = lockedId, locked = true)
        every { groupEvaluator.hasSaGroup(authentication) } returns true
        coEvery {
            metadataPermissionEvaluator.verifyAllowed(authentication, any(), PermissionAction.EDIT)
        } returns Unit
        coEvery { metadataService.setRecommendable(lockedId, true) } returns Unit
        assertTrue(controller.setMetadataRecommendable(authentication, lockedId, true))

        every { groupEvaluator.hasSaGroup(authentication) } returns false
        assertFailsExc<SecurityException> { controller.setMetadataRecommendable(authentication, lockedId, false) }

        val missing = UUID.random()
        coEvery { metadataService.getById(missing) } returns null
        assertFailsExc<NoSuchElementException> { controller.setMetadataRecommendable(authentication, missing, false) }
    }

    @Test
    fun `setRecommendableAll filters locked metadata and enforces the bulk limit`() = runTest {
        val unlocked = metadata()
        val locked = metadata(locked = true)
        val ids = listOf(unlocked.id, locked.id)
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        coEvery { metadataService.getByIds(ids) } returns listOf(unlocked, locked)
        coEvery {
            metadataPermissionEvaluator.filterAllowed(authentication, listOf(unlocked), PermissionAction.EDIT)
        } returns listOf(unlocked)
        coEvery { metadataService.setRecommendable(unlocked.id, false) } returns Unit

        assertEquals(1, controller.setRecommendableAll(authentication, ids, false))
        coVerify(exactly = 1) { metadataService.setRecommendable(unlocked.id, false) }
        coVerify(exactly = 0) { metadataService.setRecommendable(locked.id, any()) }
        every { groupEvaluator.hasSaGroup(authentication) } returns true
        coEvery {
            metadataPermissionEvaluator.filterAllowed(authentication, listOf(unlocked, locked), PermissionAction.EDIT)
        } returns listOf(unlocked, locked)
        coEvery { metadataService.setRecommendable(any(), true) } returns Unit
        assertEquals(2, controller.setRecommendableAll(authentication, ids, true))

        assertFailsExc<IllegalArgumentException> {
            controller.setRecommendableAll(authentication, List(501) { UUID.random() }, true)
        }
    }

    @Test
    fun `setMetadataCommentsEnabled locked-sa allowed enabling`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id, locked = true)
        coEvery { metadataService.getById(id) } returns md
        every { groupEvaluator.hasSaGroup(authentication) } returns true
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setCommentsEnabled(id, true) } returns Unit
        assertTrue(controller.setMetadataCommentsEnabled(authentication, id, true))
    }

    @Test
    fun `setMetadataCommentsEnabled not found`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns null
        assertFailsExc<NoSuchElementException> { controller.setMetadataCommentsEnabled(authentication, id, true) }
    }

    @Test
    fun `setMetadataCommentsEnabled disabling with replies already off does not cascade`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id, commentRepliesEnabled = false)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setCommentsEnabled(id, false) } returns Unit
        assertTrue(controller.setMetadataCommentsEnabled(authentication, id, false))
        coVerify(exactly = 0) { metadataService.setCommentRepliesEnabled(any(), any()) }
    }

    @Test
    fun `setMetadataCommentRepliesEnabled happy locked notfound`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setCommentRepliesEnabled(id, true) } returns Unit
        assertTrue(controller.setMetadataCommentRepliesEnabled(authentication, id, true))

        val lockedId = UUID.random()
        coEvery { metadataService.getById(lockedId) } returns metadata(id = lockedId, locked = true)
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        assertFailsExc<SecurityException> { controller.setMetadataCommentRepliesEnabled(authentication, lockedId, true) }

        val missing = UUID.random()
        coEvery { metadataService.getById(missing) } returns null
        assertFailsExc<NoSuchElementException> { controller.setMetadataCommentRepliesEnabled(authentication, missing, true) }
    }

    @Test
    fun `setMetadataSyncVariantCollections happy locked notfound`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setSyncVariantCollections(id, true) } returns Unit
        assertTrue(controller.setMetadataSyncVariantCollections(authentication, id, true))

        val lockedId = UUID.random()
        coEvery { metadataService.getById(lockedId) } returns metadata(id = lockedId, locked = true)
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        assertFailsExc<SecurityException> { controller.setMetadataSyncVariantCollections(authentication, lockedId, true) }

        val missing = UUID.random()
        coEvery { metadataService.getById(missing) } returns null
        assertFailsExc<NoSuchElementException> { controller.setMetadataSyncVariantCollections(authentication, missing, true) }
    }

    @Test
    fun `setMetadataSyncVariantRelationships happy locked notfound`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setSyncVariantRelationships(id, true) } returns Unit
        assertTrue(controller.setMetadataSyncVariantRelationships(authentication, id, true))

        val lockedId = UUID.random()
        coEvery { metadataService.getById(lockedId) } returns metadata(id = lockedId, locked = true)
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        assertFailsExc<SecurityException> { controller.setMetadataSyncVariantRelationships(authentication, lockedId, true) }

        val missing = UUID.random()
        coEvery { metadataService.getById(missing) } returns null
        assertFailsExc<NoSuchElementException> { controller.setMetadataSyncVariantRelationships(authentication, missing, true) }
    }

    // ---- addLanguageVariant ----

    @Test
    fun `addLanguageVariant copies minimal metadata`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        val created = metadata()
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { documentService.getDocument(md.id, md.version) } returns null
        coEvery { dataService.getData(md.id, md.version) } returns null
        coEvery { guideService.getGuide(md.id, md.version) } returns null
        coEvery { slugService.getMetadataSlug(id) } returns null
        coEvery { metadataService.getTraitIds(md.id) } returns emptyList()
        coEvery { metadataService.getCategories(md.id) } returns emptyList()
        coEvery { collectionTemplateService.getCollectionTemplate(md.id, md.version) } returns null
        coEvery { guideTemplateService.getTemplate(md.id, md.version) } returns null
        coEvery { documentTemplateService.getTemplate(md.id, md.version) } returns null
        coEvery { dataTemplateService.getTemplate(md.id, md.version) } returns null
        coEvery { metadataService.add(null, null, any()) } returns created
        coEvery { collectionService.getMetadataParents(md.id) } returns emptyList()
        coEvery { metadataService.getRelationships(md.id) } returns emptyList()
        coEvery { metadataService.getPermissions(md) } returns emptyList()

        val result = controller.addLanguageVariant(authentication, id, 1, "es", null)
        assertEquals(created, result)
    }

    @Test
    fun `addLanguageVariant copies parents relationships permissions and sets ready`() = runTest {
        val principal = principalAuth()
        val id = UUID.random()
        val md = metadata(id = id, sourceId = UUID.random())
        val created = metadata()
        val parent = mockk<bosca.content.collection.model.Collection>()
        every { parent.id } returns UUID.random()
        every { parent.attributes } returns null
        val relationship = MetadataRelationship(md.id, UUID.random(), "rel", null)
        val perm = mockk<EntityPermission>()
        every { perm.action } returns PermissionAction.VIEW
        every { perm.groupId } returns UUID.random()

        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { documentService.getDocument(md.id, md.version) } returns null
        coEvery { dataService.getData(md.id, md.version) } returns null
        coEvery { guideService.getGuide(md.id, md.version) } returns null
        coEvery { slugService.getMetadataSlug(id) } returns "the-slug"
        coEvery { metadataService.getTraitIds(md.id) } returns listOf("trait")
        coEvery { metadataService.getCategories(md.id) } returns emptyList()
        coEvery { collectionTemplateService.getCollectionTemplate(md.id, md.version) } returns null
        coEvery { guideTemplateService.getTemplate(md.id, md.version) } returns null
        coEvery { documentTemplateService.getTemplate(md.id, md.version) } returns null
        coEvery { dataTemplateService.getTemplate(md.id, md.version) } returns null
        coEvery { metadataService.add(null, null, any()) } returns created
        coEvery { collectionService.getMetadataParents(md.id) } returns listOf(parent)
        coEvery { collectionService.addMetadataItem(parent.id, created.id, any()) } returns Unit
        coEvery { metadataService.getRelationships(md.id) } returns listOf(relationship)
        coEvery { metadataService.addRelationship(any<MetadataRelationshipInput>()) } returns relationship
        coEvery { metadataService.getPermissions(md) } returns listOf(perm)
        coEvery { metadataService.addPermission(any()) } returns mockk()
        coEvery { metadataService.setReady(created, principal) } returns created

        val result = controller.addLanguageVariant(authentication, id, 1, "es", true)
        assertEquals(created, result)
        coVerify { metadataService.setReady(created, principal) }
    }

    @Test
    fun `addLanguageVariant not found`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id, 1) } returns null
        assertFailsExc<NoSuchElementException> { controller.addLanguageVariant(authentication, id, 1, "es", null) }
    }

    @Test
    fun `addLanguageVariant locked non-sa throws`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id, 1) } returns metadata(id = id, locked = true)
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        assertFailsExc<SecurityException> { controller.addLanguageVariant(authentication, id, 1, "es", null) }
    }

    // ---- addDocument ----

    @Test
    fun `addDocument without setReady`() = runTest {
        val collectionId = UUID.random()
        val templateId = UUID.random()
        val collection = mockk<bosca.content.collection.model.Collection>()
        every { collection.id } returns collectionId
        every { collection.itemsLocked } returns false
        val template = metadata(id = templateId)
        val created = metadata()
        coEvery { collectionService.getById(collectionId) } returns collection
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.getById(templateId, 1) } returns template
        coEvery { metadataService.addDocument(collectionId, template) } returns created
        coEvery { collectionService.addMetadataItem(collectionId, created.id, any()) } returns Unit
        assertEquals(created, controller.addDocument(authentication, collectionId, templateId, 1, false))
    }

    @Test
    fun `addDocument collection not found`() = runTest {
        val collectionId = UUID.random()
        coEvery { collectionService.getById(collectionId) } returns null
        assertFailsExc<NoSuchElementException> { controller.addDocument(authentication, collectionId, UUID.random(), 1, null) }
    }

    @Test
    fun `addDocument items locked non-editor throws`() = runTest {
        val collectionId = UUID.random()
        val collection = mockk<bosca.content.collection.model.Collection>()
        every { collection.id } returns collectionId
        every { collection.itemsLocked } returns true
        coEvery { collectionService.getById(collectionId) } returns collection
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT) } returns Unit
        every { groupEvaluator.hasEditorGroup(authentication) } returns false
        assertFailsExc<IllegalStateException> { controller.addDocument(authentication, collectionId, UUID.random(), 1, null) }
    }

    @Test
    fun `addDocument template not found`() = runTest {
        val collectionId = UUID.random()
        val templateId = UUID.random()
        val collection = mockk<bosca.content.collection.model.Collection>()
        every { collection.id } returns collectionId
        every { collection.itemsLocked } returns false
        coEvery { collectionService.getById(collectionId) } returns collection
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.getById(templateId, 1) } returns null
        assertFailsExc<NoSuchElementException> { controller.addDocument(authentication, collectionId, templateId, 1, null) }
    }

    // ---- setMetadataDocument / setMetadataMarkdown ----

    @Test
    fun `setMetadataDocument happy default sync`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        val doc = DocumentInput(title = "t", content = null)
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setDocument(md, doc, CollaborationSyncMode.NONE) } returns Unit
        assertTrue(controller.setMetadataDocument(authentication, id, 1, doc, null))
    }

    @Test
    fun `setMetadataDocument explicit sync mode`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        val doc = DocumentInput(title = "t", content = null)
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setDocument(md, doc, CollaborationSyncMode.MERGE) } returns Unit
        assertTrue(controller.setMetadataDocument(authentication, id, 1, doc, CollaborationSyncMode.MERGE))
    }

    @Test
    fun `setMetadataDocument not found`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id, 1) } returns null
        assertFailsExc<NoSuchElementException> { controller.setMetadataDocument(authentication, id, 1, DocumentInput(title = "t", content = null), null) }
    }

    @Test
    fun `setMetadataMarkdown happy path`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setDocument(eq(md), any(), eq(CollaborationSyncMode.NONE)) } returns Unit
        assertTrue(controller.setMetadataMarkdown(authentication, id, 1, "Title", "# Hello", null))
    }

    @Test
    fun `setMetadataMarkdown not found`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id, 1) } returns null
        assertFailsExc<NoSuchElementException> { controller.setMetadataMarkdown(authentication, id, 1, "t", "# h", null) }
    }

    // ---- setMetadataAttributes / mergeMetadataAttributes ----

    @Test
    fun `setMetadataAttributes happy and not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        val attrs = JsonObject(mapOf("a" to JsonPrimitive(1)))
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setAttributes(md, attrs) } returns Unit
        assertTrue(controller.setMetadataAttributes(authentication, id, attrs))

        val missing = UUID.random()
        coEvery { metadataService.getById(missing) } returns null
        assertFailsExc<NoSuchElementException> { controller.setMetadataAttributes(authentication, missing, attrs) }
    }

    @Test
    fun `mergeMetadataAttributes happy and not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        val attrs = JsonObject(mapOf("a" to JsonPrimitive(1)))
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.mergeAttributes(md, attrs) } returns Unit
        assertTrue(controller.mergeMetadataAttributes(authentication, id, attrs))

        val missing = UUID.random()
        coEvery { metadataService.getById(missing) } returns null
        assertFailsExc<NoSuchElementException> { controller.mergeMetadataAttributes(authentication, missing, attrs) }
    }

    // ---- addGuide / addData ----

    @Test
    fun `addGuide without parent collection`() = runTest {
        val templateId = UUID.random()
        val template = metadata(id = templateId)
        val created = metadata()
        coEvery { metadataService.getById(templateId, 1) } returns template
        coEvery { metadataService.addGuide(null, template) } returns created
        assertEquals(created, controller.addGuide(authentication, null, templateId, 1, false))
    }

    @Test
    fun `addGuide collection not found`() = runTest {
        val collectionId = UUID.random()
        coEvery { collectionService.getById(collectionId) } returns null
        assertFailsExc<NoSuchElementException> { controller.addGuide(authentication, collectionId, UUID.random(), 1, null) }
    }

    @Test
    fun `addGuide items locked non-editor`() = runTest {
        val collectionId = UUID.random()
        val collection = mockk<bosca.content.collection.model.Collection>()
        every { collection.itemsLocked } returns true
        coEvery { collectionService.getById(collectionId) } returns collection
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT) } returns Unit
        every { groupEvaluator.hasEditorGroup(authentication) } returns false
        assertFailsExc<IllegalStateException> { controller.addGuide(authentication, collectionId, UUID.random(), 1, null) }
    }

    @Test
    fun `addGuide template not found`() = runTest {
        val templateId = UUID.random()
        coEvery { metadataService.getById(templateId, 1) } returns null
        assertFailsExc<NoSuchElementException> { controller.addGuide(authentication, null, templateId, 1, null) }
    }

    @Test
    fun `addData with parent collection and setReady`() = runTest {
        val principal = principalAuth()
        val collectionId = UUID.random()
        val templateId = UUID.random()
        val collection = mockk<bosca.content.collection.model.Collection>()
        every { collection.itemsLocked } returns false
        val template = metadata(id = templateId)
        val created = metadata()
        coEvery { collectionService.getById(collectionId) } returns collection
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.getById(templateId, 1) } returns template
        coEvery { metadataService.addData(collectionId, template) } returns created
        coEvery { collectionService.addMetadataItem(collectionId, created.id, any()) } returns Unit
        coEvery { metadataService.setReady(created, principal) } returns created
        assertEquals(created, controller.addData(authentication, collectionId, templateId, 1, true))
    }

    @Test
    fun `addData without parent collection`() = runTest {
        val templateId = UUID.random()
        val template = metadata(id = templateId)
        val created = metadata()
        coEvery { metadataService.getById(templateId, 1) } returns template
        coEvery { metadataService.addData(null, template) } returns created
        assertEquals(created, controller.addData(authentication, null, templateId, 1, null))
    }

    @Test
    fun `addData collection not found`() = runTest {
        val collectionId = UUID.random()
        coEvery { collectionService.getById(collectionId) } returns null
        assertFailsExc<NoSuchElementException> { controller.addData(authentication, collectionId, UUID.random(), 1, null) }
    }

    @Test
    fun `addData items locked non editor`() = runTest {
        val collectionId = UUID.random()
        val collection = mockk<bosca.content.collection.model.Collection>()
        every { collection.itemsLocked } returns true
        coEvery { collectionService.getById(collectionId) } returns collection
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT) } returns Unit
        every { groupEvaluator.hasEditorGroup(authentication) } returns false
        assertFailsExc<IllegalStateException> { controller.addData(authentication, collectionId, UUID.random(), 1, null) }
    }

    @Test
    fun `addData template not found`() = runTest {
        val templateId = UUID.random()
        coEvery { metadataService.getById(templateId, 1) } returns null
        assertFailsExc<NoSuchElementException> { controller.addData(authentication, null, templateId, 1, null) }
    }

    // ---- setGuideStartDate ----

    @Test
    fun `setGuideStartDate inserts DTSTART when absent`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        val guide = mockk<Guide>()
        every { guide.rrule } returns "RRULE:FREQ=DAILY"
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { guideService.getGuide(id, 1) } returns guide
        coEvery { guideService.setGuideRrule(md.id, md.version, any()) } returns Unit
        val date = OffsetDateTime.now()
        val result = controller.setGuideStartDate(authentication, id, 1, date)
        assertEquals(md, result)
        coVerify { guideService.setGuideRrule(md.id, md.version, match { it.startsWith("DTSTART:") && it.contains("RRULE:FREQ=DAILY") }) }
    }

    @Test
    fun `setGuideStartDate replaces existing DTSTART`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        val guide = mockk<Guide>()
        every { guide.rrule } returns "DTSTART:20200101T000000Z\nRRULE:FREQ=DAILY"
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { guideService.getGuide(id, 1) } returns guide
        coEvery { guideService.setGuideRrule(md.id, md.version, any()) } returns Unit
        controller.setGuideStartDate(authentication, id, 1, OffsetDateTime.now())
        coVerify { guideService.setGuideRrule(md.id, md.version, match { !it.substringAfter("\n").contains("DTSTART") }) }
    }

    @Test
    fun `setGuideStartDate not found`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id, 1) } returns null
        assertFailsExc<NoSuchElementException> { controller.setGuideStartDate(authentication, id, 1, OffsetDateTime.now()) }
    }

    @Test
    fun `setGuideStartDate locked non sa`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id, 1) } returns metadata(id = id, locked = true)
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        assertFailsExc<SecurityException> { controller.setGuideStartDate(authentication, id, 1, OffsetDateTime.now()) }
    }

    @Test
    fun `setGuideStartDate guide not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { guideService.getGuide(id, 1) } returns null
        assertFailsExc<NoSuchElementException> { controller.setGuideStartDate(authentication, id, 1, OffsetDateTime.now()) }
    }

    @Test
    fun `setGuideStartDate guide missing rrule`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        val guide = mockk<Guide>()
        every { guide.rrule } returns null
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { guideService.getGuide(id, 1) } returns guide
        assertFailsExc<IllegalStateException> { controller.setGuideStartDate(authentication, id, 1, OffsetDateTime.now()) }
    }

    // ---- addGuideStep / addGuideStepModule ----

    @Test
    fun `addGuideStep happy path`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        val guide = mockk<Guide>()
        val step = GuideStep(id = 5, metadataId = id, version = 1, stepMetadataId = null, stepMetadataVersion = null, sort = 0)
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { guideService.getGuide(id, 1) } returns guide
        coEvery { metadataService.addGuideStep(guide, 10L, 0) } returns step
        every { guide.metadataId } returns id
        every { guide.version } returns 1
        every { guide.getRecurrenceDates(1) } returns listOf(OffsetDateTime.now())

        val result = controller.addGuideStep(authentication, id, 1, 0, 10L)
        assertEquals(step, result.guideStep)
        assertEquals(guide, result.guide)
    }

    @Test
    fun `addGuideStep not found`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id, 1) } returns null
        assertFailsExc<NoSuchElementException> { controller.addGuideStep(authentication, id, 1, 0, 10L) }
    }

    @Test
    fun `addGuideStep locked non sa`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id, 1) } returns metadata(id = id, locked = true)
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        assertFailsExc<SecurityException> { controller.addGuideStep(authentication, id, 1, 0, 10L) }
    }

    @Test
    fun `addGuideStep guide not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { guideService.getGuide(id, 1) } returns null
        assertFailsExc<NoSuchElementException> { controller.addGuideStep(authentication, id, 1, 0, 10L) }
    }

    @Test
    fun `addGuideStepModule happy path`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        val guide = mockk<Guide>()
        val module = GuideStepModule(id = 1, metadataId = id, version = 1, step = 2, moduleMetadataId = null, moduleMetadataVersion = null, sort = 0)
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { guideService.getGuide(id, 1) } returns guide
        coEvery { metadataService.addGuideStepModule(guide, 2L, 3L, 0) } returns module
        assertEquals(module, controller.addGuideStepModule(authentication, id, 1, 0, 2L, 3L))
    }

    @Test
    fun `addGuideStepModule not found`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id, 1) } returns null
        assertFailsExc<NoSuchElementException> { controller.addGuideStepModule(authentication, id, 1, 0, 2L, 3L) }
    }

    @Test
    fun `addGuideStepModule locked non sa`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id, 1) } returns metadata(id = id, locked = true)
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        assertFailsExc<SecurityException> { controller.addGuideStepModule(authentication, id, 1, 0, 2L, 3L) }
    }

    @Test
    fun `addGuideStepModule guide not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { guideService.getGuide(id, 1) } returns null
        assertFailsExc<NoSuchElementException> { controller.addGuideStepModule(authentication, id, 1, 0, 2L, 3L) }
    }

    // ---- deleteGuide / deleteGuideStep / deleteGuideStepModule ----

    @Test
    fun `deleteGuide happy locked notfound`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.deleteGuide(md) } returns Unit
        assertTrue(controller.deleteGuide(authentication, id, 1))

        val lockedId = UUID.random()
        coEvery { metadataService.getById(lockedId, 1) } returns metadata(id = lockedId, locked = true)
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        assertFailsExc<SecurityException> { controller.deleteGuide(authentication, lockedId, 1) }

        val missing = UUID.random()
        coEvery { metadataService.getById(missing, 1) } returns null
        assertFailsExc<NoSuchElementException> { controller.deleteGuide(authentication, missing, 1) }
    }

    @Test
    fun `deleteGuideStep happy locked notfound`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.deleteGuideStep(md, 7L) } returns Unit
        assertTrue(controller.deleteGuideStep(authentication, id, 1, 7L))

        val lockedId = UUID.random()
        coEvery { metadataService.getById(lockedId, 1) } returns metadata(id = lockedId, locked = true)
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        assertFailsExc<SecurityException> { controller.deleteGuideStep(authentication, lockedId, 1, 7L) }

        val missing = UUID.random()
        coEvery { metadataService.getById(missing, 1) } returns null
        assertFailsExc<NoSuchElementException> { controller.deleteGuideStep(authentication, missing, 1, 7L) }
    }

    @Test
    fun `deleteGuideStepModule happy locked notfound`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.deleteGuideStepModule(md, 7L, 8L) } returns Unit
        assertTrue(controller.deleteGuideStepModule(authentication, id, 1, 7L, 8L))

        val lockedId = UUID.random()
        coEvery { metadataService.getById(lockedId, 1) } returns metadata(id = lockedId, locked = true)
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        assertFailsExc<SecurityException> { controller.deleteGuideStepModule(authentication, lockedId, 1, 7L, 8L) }

        val missing = UUID.random()
        coEvery { metadataService.getById(missing, 1) } returns null
        assertFailsExc<NoSuchElementException> { controller.deleteGuideStepModule(authentication, missing, 1, 7L, 8L) }
    }

    // ---- edit ----

    @Test
    fun `edit happy and not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        val input = MetadataInput(name = "n", languageTag = "en", contentType = "text/plain")
        val edited = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.edit(id, input) } returns edited
        assertEquals(edited, controller.edit(authentication, id, input))

        val missing = UUID.random()
        coEvery { metadataService.getById(missing) } returns null
        assertFailsExc<NoSuchElementException> { controller.edit(authentication, missing, input) }
    }

    // ---- setTemplate ----

    @Test
    fun `setTemplate document`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id, contentType = "bosca/v-document")
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        val templateId = UUID.random()
        coEvery { metadataService.setDocumentTemplate(md, templateId, 2) } returns Unit
        assertEquals(md, controller.setTemplate(authentication, id, 1, templateId, 2))
    }

    @Test
    fun `setTemplate guide-step`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id, contentType = "bosca/v-guide-step")
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        val templateId = UUID.random()
        coEvery { metadataService.setDocumentTemplate(md, templateId, 2) } returns Unit
        assertEquals(md, controller.setTemplate(authentication, id, 1, templateId, 2))
    }

    @Test
    fun `setTemplate data`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id, contentType = "bosca/v-data")
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        val templateId = UUID.random()
        coEvery { metadataService.setDataTemplate(md, templateId, 2) } returns Unit
        assertEquals(md, controller.setTemplate(authentication, id, 1, templateId, 2))
    }

    @Test
    fun `setTemplate invalid content type`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id, contentType = "text/plain")
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        assertFailsExc<IllegalArgumentException> { controller.setTemplate(authentication, id, 1, UUID.random(), 2) }
    }

    @Test
    fun `setTemplate not found`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id, 1) } returns null
        assertFailsExc<NoSuchElementException> { controller.setTemplate(authentication, id, 1, UUID.random(), 2) }
    }

    // ---- delete / deleteAll ----

    @Test
    fun `delete happy and not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.DELETE) } returns Unit
        coEvery { metadataService.markDeleted(id) } returns Unit
        assertTrue(controller.delete(authentication, id))

        val missing = UUID.random()
        coEvery { metadataService.getById(missing) } returns null
        assertFailsExc<NoSuchElementException> { controller.delete(authentication, missing) }
    }

    @Test
    fun `deleteAll processes allowed`() = runTest {
        val a = metadata()
        val b = metadata()
        val ids = listOf(a.id, b.id)
        coEvery { metadataService.getByIds(ids) } returns listOf(a, b)
        coEvery { metadataPermissionEvaluator.filterAllowed(authentication, listOf(a, b), PermissionAction.DELETE) } returns listOf(a)
        coEvery { metadataService.markDeleted(a.id) } returns Unit
        assertEquals(1, controller.deleteAll(authentication, ids))
    }

    @Test
    fun `deleteAll rejects too many`() = runTest {
        val ids = (0..500).map { UUID.random() }
        assertFailsExc<IllegalArgumentException> { controller.deleteAll(authentication, ids) }
    }

    // ---- setMetadataSystemAttributes ----

    @Test
    fun `setMetadataSystemAttributes happy and not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        val attrs = JsonObject(mapOf("a" to JsonPrimitive(1)))
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setSystemAttributes(md, attrs) } returns Unit
        assertTrue(controller.setMetadataSystemAttributes(authentication, id, attrs))

        val missing = UUID.random()
        coEvery { metadataService.getById(missing) } returns null
        assertFailsExc<NoSuchElementException> { controller.setMetadataSystemAttributes(authentication, missing, attrs) }
    }

    // ---- importUrl ----

    @Test
    fun `importUrl with parent and metadata source and headers`() = runTest {
        principalAuth()
        val parentCollectionId = UUID.random()
        val parentMetadataId = UUID.random()
        val input = MetadataInput(
            name = "n",
            languageTag = "en",
            contentType = "video/mp4",
            parentCollectionId = parentCollectionId,
            parentId = parentMetadataId,
        )
        val parent = mockk<bosca.content.collection.model.Collection>()
        val parentMetadata = metadata(id = parentMetadataId)
        val created = metadata()
        val source = Source(id = UUID.random(), name = "s", description = "d", configuration = JsonNull)

        coEvery { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { collectionService.getById(parentCollectionId) } returns parent
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, parent, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.getById(parentMetadataId) } returns parentMetadata
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, parentMetadata, PermissionAction.EDIT) } returns Unit
        coEvery { sourceService.getOrCreateForUrl("http://x/y.mp4") } returns source
        coEvery { metadataService.add(parent, null, any()) } returns created
        coEvery { metadataService.setSourceStatus(created.id, SourceStatus.PENDING) } returns Unit

        val headers = JsonObject(mapOf("Authorization" to JsonPrimitive("Bearer x")))
        val result = controller.importUrl(authentication, input, null, "http://x/y.mp4", headers, true)
        assertEquals(created, result)
    }

    @Test
    fun `importUrl without parent null headers`() = runTest {
        every { authentication.principal() } returns null
        val input = MetadataInput(name = "n", languageTag = "en", contentType = "video/mp4")
        val created = metadata()
        val source = Source(id = UUID.random(), name = "s", description = "d", configuration = JsonNull)
        coEvery { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { sourceService.getOrCreateForUrl("http://x/y.mp4") } returns source
        coEvery { metadataService.add(null, null, any()) } returns created
        coEvery { metadataService.setSourceStatus(created.id, SourceStatus.PENDING) } returns Unit

        val result = controller.importUrl(authentication, input, null, "http://x/y.mp4", JsonNull, null)
        assertEquals(created, result)
    }

    @Test
    fun `importUrl parent collection not found`() = runTest {
        val parentCollectionId = UUID.random()
        val input = MetadataInput(name = "n", languageTag = "en", contentType = "video/mp4", parentCollectionId = parentCollectionId)
        coEvery { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { collectionService.getById(parentCollectionId) } returns null
        assertFailsExc<NoSuchElementException> { controller.importUrl(authentication, input, null, "http://x", null, null) }
    }

    @Test
    fun `importUrl parent metadata not found`() = runTest {
        val parentMetadataId = UUID.random()
        val input = MetadataInput(name = "n", languageTag = "en", contentType = "video/mp4", parentId = parentMetadataId)
        coEvery { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { metadataService.getById(parentMetadataId) } returns null
        assertFailsExc<NoSuchElementException> { controller.importUrl(authentication, input, null, "http://x", null, null) }
    }

    // ---- content upload mutations ----

    private fun uploadedFile(bytes: ByteArray = "content".toByteArray()) =
        UploadedFile(name = "f.bin", contentType = "application/octet-stream", inputStream = ByteArrayInputStream(bytes))

    @Test
    fun `setMetadataContents uploads and marks uploaded`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        val path = mockk<ObjectPath>()
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { storage.getPath(md, null) } returns path
        coEvery { storage.setInputStream(eq(path), any(), any()) } returns 7L
        coEvery { metadataService.setUploaded(id, "image/png", 7L) } returns Unit
        assertTrue(controller.setMetadataContents(authentication, "image/png", uploadedFile(), id))
    }

    @Test
    fun `setMetadataContents not found`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns null
        assertFailsExc<NoSuchElementException> { controller.setMetadataContents(authentication, "image/png", uploadedFile(), id) }
    }

    @Test
    fun `setMetadataJsonContents uploads`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        val path = mockk<ObjectPath>()
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { storage.getPath(md, null) } returns path
        coEvery { storage.setInputStream(eq(path), any(), any()) } returns 3L
        coEvery { metadataService.setUploaded(id, "application/json", 3L) } returns Unit
        assertTrue(controller.setMetadataJsonContents(authentication, id, "application/json", JsonObject(mapOf("a" to JsonPrimitive(1)))))
    }

    @Test
    fun `setMetadataJsonContents not found`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns null
        assertFailsExc<NoSuchElementException> { controller.setMetadataJsonContents(authentication, id, null, JsonNull) }
    }

    @Test
    fun `setMetadataTextContents uploads`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        val path = mockk<ObjectPath>()
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { storage.getPath(md, null) } returns path
        coEvery { storage.setInputStream(eq(path), any(), any()) } returns 4L
        coEvery { metadataService.setUploaded(id, "text/plain", 4L) } returns Unit
        assertTrue(controller.setMetadataTextContents(authentication, id, "text/plain", "text"))
    }

    @Test
    fun `setMetadataTextContents not found`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns null
        assertFailsExc<NoSuchElementException> { controller.setMetadataTextContents(authentication, id, null, "text") }
    }

    // ---- collaboration clears ----

    @Test
    fun `clearDocumentCollaboration happy and not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { documentService.removeCollaboration(id, 1) } returns Unit
        assertTrue(controller.clearDocumentCollaboration(authentication, id, 1))

        val missing = UUID.random()
        coEvery { metadataService.getById(missing, 1) } returns null
        assertFailsExc<NoSuchElementException> { controller.clearDocumentCollaboration(authentication, missing, 1) }
    }

    @Test
    fun `clearDataCollaboration happy and not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { dataService.removeCollaboration(id, 1) } returns Unit
        assertTrue(controller.clearDataCollaboration(authentication, id, 1))

        val missing = UUID.random()
        coEvery { metadataService.getById(missing, 1) } returns null
        assertFailsExc<NoSuchElementException> { controller.clearDataCollaboration(authentication, missing, 1) }
    }

    // ---- deleteContent ----

    @Test
    fun `deleteContent happy and not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        val path = mockk<ObjectPath>()
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { storage.getPath(md, null) } returns path
        coEvery { storage.delete(path) } returns Unit
        coEvery { metadataService.clearUploaded(id) } returns Unit
        assertTrue(controller.deleteContent(authentication, id))

        val missing = UUID.random()
        coEvery { metadataService.getById(missing) } returns null
        assertFailsExc<NoSuchElementException> { controller.deleteContent(authentication, missing) }
    }

    // ---- setMetadataUploaded ----

    @Test
    fun `setMetadataUploaded with explicit content type and ready`() = runTest {
        val principal = principalAuth()
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setUploaded(id, "image/png", 10L) } returns Unit
        coEvery { metadataService.setReady(md, principal) } returns md
        assertTrue(controller.setMetadataUploaded(authentication, id, "image/png", 10, true))
    }

    @Test
    fun `setMetadataUploaded falls back to metadata content type without ready`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id, contentType = "text/plain")
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setUploaded(id, "text/plain", 5L) } returns Unit
        assertTrue(controller.setMetadataUploaded(authentication, id, null, 5, false))
        coVerify(exactly = 0) { metadataService.setReady(any(), any()) }
    }

    @Test
    fun `setMetadataUploaded not found`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns null
        assertFailsExc<NoSuchElementException> { controller.setMetadataUploaded(authentication, id, null, 5, false) }
    }

    // ---- setMetadataParentCollections ----

    @Test
    fun `setMetadataParentCollections removes and adds`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        val oldParentId = UUID.random()
        val newParentId = UUID.random()
        val oldParent = mockk<bosca.content.collection.model.Collection>()
        every { oldParent.id } returns oldParentId
        val oldCol = mockk<bosca.content.collection.model.Collection>()
        val newCol = mockk<bosca.content.collection.model.Collection>()
        val newInput = MetadataParentCollection(newParentId, JsonObject(mapOf("k" to JsonPrimitive(1))))

        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.getParents(id) } returns listOf(oldParent)
        coEvery { collectionService.getById(oldParentId) } returns oldCol
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, oldCol, PermissionAction.EDIT) } returns Unit
        coEvery { collectionService.removeMetadataItem(oldParentId, id) } returns Unit
        coEvery { collectionService.getById(newParentId) } returns newCol
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, newCol, PermissionAction.EDIT) } returns Unit
        coEvery { collectionService.addMetadataItem(newParentId, id, newInput.attributes) } returns Unit

        assertTrue(controller.setMetadataParentCollections(authentication, id, listOf(newInput)))
        coVerify { collectionService.removeMetadataItem(oldParentId, id) }
        coVerify { collectionService.addMetadataItem(newParentId, id, newInput.attributes) }
    }

    @Test
    fun `setMetadataParentCollections not found`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns null
        assertFailsExc<NoSuchElementException> { controller.setMetadataParentCollections(authentication, id, emptyList()) }
    }

    @Test
    fun `setMetadataParentCollections removed collection missing throws`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        val oldParentId = UUID.random()
        val oldParent = mockk<bosca.content.collection.model.Collection>()
        every { oldParent.id } returns oldParentId
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.getParents(id) } returns listOf(oldParent)
        coEvery { collectionService.getById(oldParentId) } returns null
        assertFailsExc<NoSuchElementException> { controller.setMetadataParentCollections(authentication, id, emptyList()) }
    }

    @Test
    fun `setMetadataParentCollections existing parent missing throws`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        val parentId = UUID.random()
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.getParents(id) } returns emptyList()
        coEvery { collectionService.getById(parentId) } returns null
        assertFailsExc<NoSuchElementException> {
            controller.setMetadataParentCollections(authentication, id, listOf(MetadataParentCollection(parentId, null)))
        }
    }

    @Test
    fun `setMetadataParentCollections merges existing with null attributes skips merge`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        val parentId = UUID.random()
        val parent = mockk<bosca.content.collection.model.Collection>()
        every { parent.id } returns parentId
        val col = mockk<bosca.content.collection.model.Collection>()
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.getParents(id) } returns listOf(parent)
        coEvery { collectionService.getById(parentId) } returns col
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit

        assertTrue(controller.setMetadataParentCollections(authentication, id, listOf(MetadataParentCollection(parentId, null))))
        coVerify(exactly = 0) { collectionService.mergeMetadataItemAttributes(any(), any(), any()) }
        coVerify(exactly = 0) { collectionService.removeMetadataItem(any(), any()) }
    }

    // ---- setMetadataRelationships ----

    @Test
    fun `setMetadataRelationships removes and adds new`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        val other = UUID.random()
        val existing = MetadataRelationship(id, other, "old", null)
        val newInput = MetadataRelationshipInput(id, UUID.random(), "new", null)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.getRelationships(id) } returns listOf(existing)
        coEvery { metadataService.removeRelationship(id, other, "old") } returns Unit
        coEvery { metadataService.addRelationship(newInput) } returns MetadataRelationship(newInput.id1, newInput.id2, newInput.relationship, null)
        assertTrue(controller.setMetadataRelationships(authentication, id, listOf(newInput)))
        coVerify { metadataService.removeRelationship(id, other, "old") }
        coVerify { metadataService.addRelationship(newInput) }
    }

    @Test
    fun `setMetadataRelationships rejects mismatched id1`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.getRelationships(id) } returns emptyList()
        val bad = MetadataRelationshipInput(UUID.random(), UUID.random(), "r", null)
        assertFailsExc<IllegalArgumentException> { controller.setMetadataRelationships(authentication, id, listOf(bad)) }
    }

    @Test
    fun `setMetadataRelationships existing with null attributes skips merge`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        val other = UUID.random()
        val existing = MetadataRelationship(id, other, "rel", null)
        val input = MetadataRelationshipInput(id, other, "rel", null)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.getRelationships(id) } returns listOf(existing)
        assertTrue(controller.setMetadataRelationships(authentication, id, listOf(input)))
        coVerify(exactly = 0) { metadataService.mergeAttributes(any(), any(), any<String>(), any()) }
        coVerify(exactly = 0) { metadataService.removeRelationship(any(), any(), any()) }
    }

    @Test
    fun `setMetadataRelationships not found`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns null
        assertFailsExc<NoSuchElementException> { controller.setMetadataRelationships(authentication, id, emptyList()) }
    }

    // ---- setLocked ----

    @Test
    fun `setLocked happy and not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setLocked(id, 1, true) } returns Unit
        assertTrue(controller.setLocked(authentication, id, 1, true).locked)

        val missing = UUID.random()
        coEvery { metadataService.getById(missing, 1) } returns null
        assertFailsExc<NoSuchElementException> { controller.setLocked(authentication, missing, 1, true) }
    }

    // ---- categories & traits ----

    @Test
    fun `addCategory happy and not found`() = runTest {
        val id = UUID.random()
        val cat = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.addCategory(id, cat) } returns Unit
        assertTrue(controller.addCategory(authentication, id, cat))

        val missing = UUID.random()
        coEvery { metadataService.getById(missing) } returns null
        assertFailsExc<NoSuchElementException> { controller.addCategory(authentication, missing, cat) }
    }

    @Test
    fun `deleteCategory happy and not found`() = runTest {
        val id = UUID.random()
        val cat = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.deleteCategory(id, cat) } returns Unit
        assertTrue(controller.deleteCategory(authentication, cat, id))

        val missing = UUID.random()
        coEvery { metadataService.getById(missing) } returns null
        assertFailsExc<NoSuchElementException> { controller.deleteCategory(authentication, cat, missing) }
    }

    @Test
    fun `setCategories happy and not found`() = runTest {
        val id = UUID.random()
        val cats = listOf(UUID.random())
        val md = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setCategories(id, cats) } returns Unit
        assertTrue(controller.setCategories(authentication, cats, id))

        val missing = UUID.random()
        coEvery { metadataService.getById(missing) } returns null
        assertFailsExc<NoSuchElementException> { controller.setCategories(authentication, cats, missing) }
    }

    @Test
    fun `addTrait happy and not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.addTrait(id, "trait") } returns Unit
        controller.addTrait(authentication, id, "trait")
        coVerify { metadataService.addTrait(id, "trait") }

        val missing = UUID.random()
        coEvery { metadataService.getById(missing) } returns null
        assertFailsExc<NoSuchElementException> { controller.addTrait(authentication, missing, "trait") }
    }

    @Test
    fun `deleteTrait happy and not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.deleteTrait(id, "trait") } returns Unit
        controller.deleteTrait(authentication, id, "trait")
        coVerify { metadataService.deleteTrait(id, "trait") }

        val missing = UUID.random()
        coEvery { metadataService.getById(missing) } returns null
        assertFailsExc<NoSuchElementException> { controller.deleteTrait(authentication, missing, "trait") }
    }

    // ---- relationships single ops ----

    @Test
    fun `addRelationship happy`() = runTest {
        val id1 = UUID.random()
        val id2 = UUID.random()
        val m1 = metadata(id = id1)
        val m2 = metadata(id = id2)
        val input = MetadataRelationshipInput(id1, id2, "rel", null)
        val rel = MetadataRelationship(id1, id2, "rel", null)
        coEvery { metadataService.getById(id1) } returns m1
        coEvery { metadataService.getById(id2) } returns m2
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, m1, PermissionAction.EDIT) } returns Unit
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, m2, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.addRelationship(input) } returns rel
        assertEquals(rel, controller.addRelationship(authentication, input))
    }

    @Test
    fun `addRelationship metadata1 not found`() = runTest {
        val input = MetadataRelationshipInput(UUID.random(), UUID.random(), "rel", null)
        coEvery { metadataService.getById(input.id1) } returns null
        assertFailsExc<NoSuchElementException> { controller.addRelationship(authentication, input) }
    }

    @Test
    fun `addRelationship metadata2 not found`() = runTest {
        val id1 = UUID.random()
        val input = MetadataRelationshipInput(id1, UUID.random(), "rel", null)
        coEvery { metadataService.getById(id1) } returns metadata(id = id1)
        coEvery { metadataService.getById(input.id2) } returns null
        assertFailsExc<NoSuchElementException> { controller.addRelationship(authentication, input) }
    }

    @Test
    fun `editRelationship happy and not found`() = runTest {
        val id1 = UUID.random()
        val id2 = UUID.random()
        val m1 = metadata(id = id1)
        val m2 = metadata(id = id2)
        val input = MetadataRelationshipInput(id1, id2, "rel", null)
        coEvery { metadataService.getById(id1) } returns m1
        coEvery { metadataService.getById(id2) } returns m2
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, m1, PermissionAction.EDIT) } returns Unit
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, m2, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.removeRelationship(id1, id2, "rel") } returns Unit
        coEvery { metadataService.addRelationship(input) } returns MetadataRelationship(id1, id2, "rel", null)
        assertTrue(controller.editRelationship(authentication, input))

        coEvery { metadataService.getById(id1) } returns null
        assertFailsExc<NoSuchElementException> { controller.editRelationship(authentication, input) }
    }

    @Test
    fun `editRelationship metadata2 not found`() = runTest {
        val id1 = UUID.random()
        val id2 = UUID.random()
        val input = MetadataRelationshipInput(id1, id2, "rel", null)
        coEvery { metadataService.getById(id1) } returns metadata(id = id1)
        coEvery { metadataService.getById(id2) } returns null
        assertFailsExc<NoSuchElementException> { controller.editRelationship(authentication, input) }
    }

    @Test
    fun `deleteRelationship happy and not found`() = runTest {
        val id1 = UUID.random()
        val id2 = UUID.random()
        val m1 = metadata(id = id1)
        val m2 = metadata(id = id2)
        coEvery { metadataService.getById(id1) } returns m1
        coEvery { metadataService.getById(id2) } returns m2
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, m1, PermissionAction.EDIT) } returns Unit
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, m2, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.removeRelationship(id1, id2, "rel") } returns Unit
        assertTrue(controller.deleteRelationship(authentication, id1, id2, "rel"))

        coEvery { metadataService.getById(id1) } returns null
        assertFailsExc<NoSuchElementException> { controller.deleteRelationship(authentication, id1, id2, "rel") }
    }

    @Test
    fun `deleteRelationship metadata2 not found`() = runTest {
        val id1 = UUID.random()
        val id2 = UUID.random()
        coEvery { metadataService.getById(id1) } returns metadata(id = id1)
        coEvery { metadataService.getById(id2) } returns null
        assertFailsExc<NoSuchElementException> { controller.deleteRelationship(authentication, id1, id2, "rel") }
    }

    @Test
    fun `mergeMetadataRelationshipAttributes happy and not found`() = runTest {
        val id1 = UUID.random()
        val id2 = UUID.random()
        val m1 = metadata(id = id1)
        val m2 = metadata(id = id2)
        val attrs = JsonObject(mapOf("a" to JsonPrimitive(1)))
        coEvery { metadataService.getById(id1) } returns m1
        coEvery { metadataService.getById(id2) } returns m2
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, m1, PermissionAction.EDIT) } returns Unit
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, m2, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.mergeAttributes(id1, id2, "rel", attrs) } returns Unit
        assertTrue(controller.mergeMetadataRelationshipAttributes(authentication, id1, id2, "rel", attrs))

        coEvery { metadataService.getById(id1) } returns null
        assertFailsExc<NoSuchElementException> { controller.mergeMetadataRelationshipAttributes(authentication, id1, id2, "rel", attrs) }
    }

    @Test
    fun `mergeMetadataRelationshipAttributes metadata2 not found`() = runTest {
        val id1 = UUID.random()
        val id2 = UUID.random()
        coEvery { metadataService.getById(id1) } returns metadata(id = id1)
        coEvery { metadataService.getById(id2) } returns null
        assertFailsExc<NoSuchElementException> { controller.mergeMetadataRelationshipAttributes(authentication, id1, id2, "rel", JsonNull) }
    }

    // ---- ready / not ready ----

    @Test
    fun `setMetadataNotReady happy and not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setNotReady(md) } returns Unit
        assertTrue(controller.setMetadataNotReady(authentication, id))

        val missing = UUID.random()
        coEvery { metadataService.getById(missing) } returns null
        assertFailsExc<NoSuchElementException> { controller.setMetadataNotReady(authentication, missing) }
    }

    @Test
    fun `setMetadataReady happy and not found and missing principal`() = runTest {
        val principal = principalAuth()
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setReady(md, principal) } returns md
        assertTrue(controller.setMetadataReady(authentication, id))

        val missing = UUID.random()
        coEvery { metadataService.getById(missing) } returns null
        assertFailsExc<NoSuchElementException> { controller.setMetadataReady(authentication, missing) }

        val id2 = UUID.random()
        val md2 = metadata(id = id2)
        coEvery { metadataService.getById(id2) } returns md2
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md2, PermissionAction.EDIT) } returns Unit
        every { authentication.principal() } returns null
        assertFailsExc<IllegalStateException> { controller.setMetadataReady(authentication, id2) }
    }

    // ---- setMetadataReadyAll ----

    @Test
    fun `setMetadataReadyAll succeeds and swallows failures as sa`() = runTest {
        val principal = principalAuth()
        val a = metadata()
        val b = metadata()
        val ids = listOf(a.id, b.id)
        every { groupEvaluator.hasSaGroup(authentication) } returns true
        coEvery { metadataService.getByIds(ids) } returns listOf(a, b)
        coEvery { metadataPermissionEvaluator.filterAllowed(authentication, listOf(a, b), PermissionAction.EDIT) } returns listOf(a, b)
        coEvery { metadataService.setReady(a, principal) } returns a
        coEvery { metadataService.setReady(b, principal) } throws RuntimeException("boom")
        assertEquals(1, controller.setMetadataReadyAll(authentication, ids))
    }

    @Test
    fun `setMetadataReadyAll filters locked when not sa`() = runTest {
        val principal = principalAuth()
        val a = metadata()
        val b = metadata(locked = true)
        val ids = listOf(a.id, b.id)
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        coEvery { metadataService.getByIds(ids) } returns listOf(a, b)
        coEvery { metadataPermissionEvaluator.filterAllowed(authentication, listOf(a), PermissionAction.EDIT) } returns listOf(a)
        coEvery { metadataService.setReady(a, principal) } returns a
        assertEquals(1, controller.setMetadataReadyAll(authentication, ids))
    }

    @Test
    fun `setMetadataReadyAll rejects too many`() = runTest {
        val ids = (0..500).map { UUID.random() }
        assertFailsExc<IllegalArgumentException> { controller.setMetadataReadyAll(authentication, ids) }
    }

    @Test
    fun `setMetadataReadyAll missing principal`() = runTest {
        every { groupEvaluator.hasSaGroup(authentication) } returns true
        every { authentication.principal() } returns null
        assertFailsExc<IllegalStateException> { controller.setMetadataReadyAll(authentication, listOf(UUID.random())) }
    }

    // ---- supplementary ----

    private fun supplementary(metadataId: UUID) = MetadataSupplementary(
        id = UUID.random(),
        metadataId = metadataId,
        key = "k",
        name = "n",
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
        contentType = "text/plain",
    )

    @Test
    fun `addSupplementary allowed`() = runTest {
        val metadataId = UUID.random()
        val md = metadata(id = metadataId)
        val input = MetadataSupplementaryInput(metadataId = metadataId, key = "k", name = "n", contentType = "text/plain")
        val supp = supplementary(metadataId)
        coEvery { metadataService.getById(metadataId) } returns md
        coEvery { metadataPermissionEvaluator.isAllowed(authentication, md, PermissionAction.EDIT) } returns true
        coEvery { metadataService.addSupplementary(input) } returns supp
        val result = controller.addSupplementary(authentication, input)
        assertEquals(supp, result.supplementary)
        assertEquals(md, result.metadata)
    }

    @Test
    fun `addSupplementary falls back to sa group when not allowed`() = runTest {
        val metadataId = UUID.random()
        val md = metadata(id = metadataId)
        val input = MetadataSupplementaryInput(metadataId = metadataId, key = "k", name = "n", contentType = "text/plain")
        val supp = supplementary(metadataId)
        coEvery { metadataService.getById(metadataId) } returns md
        coEvery { metadataPermissionEvaluator.isAllowed(authentication, md, PermissionAction.EDIT) } returns false
        coEvery { groupEvaluator.verifyHasSaGroup(authentication) } returns Unit
        coEvery { metadataService.addSupplementary(input) } returns supp
        assertEquals(supp, controller.addSupplementary(authentication, input).supplementary)
    }

    @Test
    fun `addSupplementary not found`() = runTest {
        val metadataId = UUID.random()
        val input = MetadataSupplementaryInput(metadataId = metadataId, key = "k", name = "n", contentType = "text/plain")
        coEvery { metadataService.getById(metadataId) } returns null
        assertFailsExc<NoSuchElementException> { controller.addSupplementary(authentication, input) }
    }

    @Test
    fun `setSupplementaryTextContents happy`() = runTest {
        val suppId = UUID.random()
        val metadataId = UUID.random()
        val supp = supplementary(metadataId)
        val md = metadata(id = metadataId)
        coEvery { metadataService.getSupplementaryById(suppId) } returns supp
        coEvery { metadataService.getById(metadataId) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.updateSupplementaryContent(md, suppId, "text", "text/plain") } returns Unit
        assertTrue(controller.setSupplementaryTextContents(authentication, suppId, "text", "text/plain"))
    }

    @Test
    fun `setSupplementaryTextContents supplementary not found`() = runTest {
        val suppId = UUID.random()
        coEvery { metadataService.getSupplementaryById(suppId) } returns null
        assertFailsExc<NoSuchElementException> { controller.setSupplementaryTextContents(authentication, suppId, "t", "text/plain") }
    }

    @Test
    fun `setSupplementaryTextContents metadata not found`() = runTest {
        val suppId = UUID.random()
        val metadataId = UUID.random()
        coEvery { metadataService.getSupplementaryById(suppId) } returns supplementary(metadataId)
        coEvery { metadataService.getById(metadataId) } returns null
        assertFailsExc<NoSuchElementException> { controller.setSupplementaryTextContents(authentication, suppId, "t", "text/plain") }
    }

    @Test
    fun `setSupplementaryContents allowed and sa fallback`() = runTest {
        val suppId = UUID.random()
        val metadataId = UUID.random()
        val supp = supplementary(metadataId)
        val md = metadata(id = metadataId)
        coEvery { metadataService.getSupplementaryById(suppId) } returns supp
        coEvery { metadataService.getById(metadataId) } returns md
        coEvery { metadataPermissionEvaluator.isAllowed(authentication, md, PermissionAction.EDIT) } returns true
        coEvery { metadataService.updateSupplementaryContent(eq(md), eq(suppId), any<bosca.server.content.PartData.FileItem>(), eq("text/plain")) } returns Unit
        assertTrue(controller.setSupplementaryContents(authentication, suppId, uploadedFile(), "text/plain"))

        coEvery { metadataPermissionEvaluator.isAllowed(authentication, md, PermissionAction.EDIT) } returns false
        coEvery { groupEvaluator.verifyHasSaGroup(authentication) } returns Unit
        assertTrue(controller.setSupplementaryContents(authentication, suppId, uploadedFile(), "text/plain"))
    }

    @Test
    fun `setSupplementaryContents supplementary not found`() = runTest {
        val suppId = UUID.random()
        coEvery { metadataService.getSupplementaryById(suppId) } returns null
        assertFailsExc<NoSuchElementException> { controller.setSupplementaryContents(authentication, suppId, uploadedFile(), "text/plain") }
    }

    @Test
    fun `setSupplementaryContents metadata not found`() = runTest {
        val suppId = UUID.random()
        val metadataId = UUID.random()
        coEvery { metadataService.getSupplementaryById(suppId) } returns supplementary(metadataId)
        coEvery { metadataService.getById(metadataId) } returns null
        assertFailsExc<NoSuchElementException> { controller.setSupplementaryContents(authentication, suppId, uploadedFile(), "text/plain") }
    }

    @Test
    fun `setSupplementaryUploaded allowed and sa fallback and not found`() = runTest {
        val suppId = UUID.random()
        val metadataId = UUID.random()
        val supp = supplementary(metadataId)
        val md = metadata(id = metadataId)
        coEvery { metadataService.getSupplementaryById(suppId) } returns supp
        coEvery { metadataService.getById(metadataId) } returns md
        coEvery { metadataPermissionEvaluator.isAllowed(authentication, md, PermissionAction.EDIT) } returns true
        coEvery { metadataService.setSupplementaryUploaded(md, suppId, 9L, "text/plain") } returns Unit
        assertTrue(controller.setSupplementaryUploaded(authentication, suppId, 9L, "text/plain"))

        coEvery { metadataPermissionEvaluator.isAllowed(authentication, md, PermissionAction.EDIT) } returns false
        coEvery { groupEvaluator.verifyHasSaGroup(authentication) } returns Unit
        assertTrue(controller.setSupplementaryUploaded(authentication, suppId, 9L, "text/plain"))

        coEvery { metadataService.getSupplementaryById(suppId) } returns null
        assertFailsExc<NoSuchElementException> { controller.setSupplementaryUploaded(authentication, suppId, 9L, "text/plain") }
    }

    @Test
    fun `setSupplementaryUploaded metadata not found`() = runTest {
        val suppId = UUID.random()
        val metadataId = UUID.random()
        coEvery { metadataService.getSupplementaryById(suppId) } returns supplementary(metadataId)
        coEvery { metadataService.getById(metadataId) } returns null
        assertFailsExc<NoSuchElementException> { controller.setSupplementaryUploaded(authentication, suppId, 9L, "text/plain") }
    }

    @Test
    fun `deleteSupplementary happy and short-circuits`() = runTest {
        val id = UUID.random()
        val metadataId = UUID.random()
        val supp = supplementary(metadataId)
        val md = metadata(id = metadataId)
        coEvery { metadataService.getSupplementaryById(id) } returns supp
        coEvery { metadataService.getById(metadataId) } returns md
        coEvery { metadataPermissionEvaluator.isAllowed(authentication, md, PermissionAction.EDIT) } returns true
        coEvery { metadataService.deleteSupplementary(md, id) } returns Unit
        assertTrue(controller.deleteSupplementary(authentication, id))

        coEvery { metadataService.getSupplementaryById(id) } returns null
        assertFalse(controller.deleteSupplementary(authentication, id))

        coEvery { metadataService.getSupplementaryById(id) } returns supp
        coEvery { metadataService.getById(metadataId) } returns null
        assertFalse(controller.deleteSupplementary(authentication, id))
    }

    @Test
    fun `deleteSupplementary sa fallback`() = runTest {
        val id = UUID.random()
        val metadataId = UUID.random()
        val supp = supplementary(metadataId)
        val md = metadata(id = metadataId)
        coEvery { metadataService.getSupplementaryById(id) } returns supp
        coEvery { metadataService.getById(metadataId) } returns md
        coEvery { metadataPermissionEvaluator.isAllowed(authentication, md, PermissionAction.EDIT) } returns false
        coEvery { groupEvaluator.verifyHasSaGroup(authentication) } returns Unit
        coEvery { metadataService.deleteSupplementary(md, id) } returns Unit
        assertTrue(controller.deleteSupplementary(authentication, id))
    }

    @Test
    fun `detachSupplementary happy and short-circuits and sa fallback`() = runTest {
        val id = UUID.random()
        val metadataId = UUID.random()
        val supp = supplementary(metadataId)
        val md = metadata(id = metadataId)
        coEvery { metadataService.getSupplementaryById(id) } returns supp
        coEvery { metadataService.getById(metadataId) } returns md
        coEvery { metadataPermissionEvaluator.isAllowed(authentication, md, PermissionAction.EDIT) } returns true
        coEvery { metadataService.detachSupplementary(md, id) } returns Unit
        assertTrue(controller.detachSupplementary(authentication, id))

        coEvery { metadataService.getSupplementaryById(id) } returns null
        assertFalse(controller.detachSupplementary(authentication, id))

        coEvery { metadataService.getSupplementaryById(id) } returns supp
        coEvery { metadataService.getById(metadataId) } returns null
        assertFalse(controller.detachSupplementary(authentication, id))

        coEvery { metadataService.getById(metadataId) } returns md
        coEvery { metadataPermissionEvaluator.isAllowed(authentication, md, PermissionAction.EDIT) } returns false
        coEvery { groupEvaluator.verifyHasSaGroup(authentication) } returns Unit
        assertTrue(controller.detachSupplementary(authentication, id))
    }

    // ---- workflow state ----

    @Test
    fun `setWorkflowState immediate`() = runTest {
        val principal = principalAuth()
        val metadataId = UUID.random()
        val md = metadata(id = metadataId)
        val state = MetadataWorkflowState(metadataId = metadataId, stateId = "published", status = "ok", immediate = true)
        coEvery { metadataService.getById(metadataId) } returns md
        coEvery { groupEvaluator.verifyHasSaGroup(authentication) } returns Unit
        coEvery { metadataService.setState(item = md, toStateId = "published", status = "ok", principal = principal) } returns md
        assertTrue(controller.setWorkflowState(authentication, state))
    }

    @Test
    fun `setWorkflowState pending`() = runTest {
        val principal = principalAuth()
        val metadataId = UUID.random()
        val md = metadata(id = metadataId)
        val state = MetadataWorkflowState(metadataId = metadataId, stateId = "published", status = "ok", immediate = false)
        coEvery { metadataService.getById(metadataId) } returns md
        coEvery { groupEvaluator.verifyHasSaGroup(authentication) } returns Unit
        coEvery { metadataService.setPendingState(item = md, toStateId = "published", status = "ok", principal = principal) } returns md
        assertTrue(controller.setWorkflowState(authentication, state))
    }

    @Test
    fun `setWorkflowState no principal returns false`() = runTest {
        every { authentication.principal() } returns null
        val state = MetadataWorkflowState(metadataId = UUID.random(), stateId = "x", status = "s", immediate = true)
        assertFalse(controller.setWorkflowState(authentication, state))
    }

    @Test
    fun `setWorkflowState metadata not found returns false`() = runTest {
        principalAuth()
        val metadataId = UUID.random()
        val state = MetadataWorkflowState(metadataId = metadataId, stateId = "x", status = "s", immediate = true)
        coEvery { metadataService.getById(metadataId) } returns null
        assertFalse(controller.setWorkflowState(authentication, state))
    }

    @Test
    fun `setWorkflowStateComplete happy no-principal not-found`() = runTest {
        val principal = principalAuth()
        val metadataId = UUID.random()
        val md = metadata(id = metadataId)
        val state = MetadataWorkflowCompleteState(metadataId = metadataId, status = "done")
        coEvery { metadataService.getById(metadataId) } returns md
        coEvery { groupEvaluator.verifyHasSaGroup(authentication) } returns Unit
        coEvery { metadataService.setPendingStateComplete(item = md, status = "done", principal = principal) } returns md
        assertTrue(controller.setWorkflowStateComplete(authentication, state))

        coEvery { metadataService.getById(metadataId) } returns null
        assertFalse(controller.setWorkflowStateComplete(authentication, state))

        every { authentication.principal() } returns null
        assertFalse(controller.setWorkflowStateComplete(authentication, state))
    }

    // ---- bible ----

    @Test
    fun `setMetadataBible happy and not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        val bible = mockk<BibleInput>()
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setBible(md, bible) } returns Unit
        assertTrue(controller.setMetadataBible(authentication, id, 1, bible))

        val missing = UUID.random()
        coEvery { metadataService.getById(missing, 1) } returns null
        assertFailsExc<NoSuchElementException> { controller.setMetadataBible(authentication, missing, 1, bible) }
    }

    @Test
    fun `setMetadataBibleVariantEnabled verifies edit access and updates the variant`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setBibleVariantEnabled(md, "study", false) } returns mockk()

        assertTrue(controller.setMetadataBibleVariantEnabled(authentication, id, 1, "study", false))
        coVerify { metadataService.setBibleVariantEnabled(md, "study", false) }
    }

    @Test
    fun `setMetadataBibleVariantEnabled reports missing metadata`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id, 1) } returns null

        assertFailsExc<NoSuchElementException> {
            controller.setMetadataBibleVariantEnabled(authentication, id, 1, "study", false)
        }
    }

    @Test
    fun `setMetadataBibleDefaultVariant verifies edit access and updates the default`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.setDefaultBibleVariant(md, "reader") } returns mockk()

        assertTrue(controller.setMetadataBibleDefaultVariant(authentication, id, 1, "reader"))
        coVerify { metadataService.setDefaultBibleVariant(md, "reader") }
    }

    @Test
    fun `setMetadataBibleDefaultVariant reports missing metadata`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id, 1) } returns null

        assertFailsExc<NoSuchElementException> {
            controller.setMetadataBibleDefaultVariant(authentication, id, 1, "reader")
        }
    }

    @Test
    fun `reprocessBible queues runner processing`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit

        assertTrue(controller.reprocessBible(authentication, id, 1))

        coVerify(exactly = 1) { BibleProcessJob(id, 1).enqueue() }
        coVerify(exactly = 0) { storage.getInputStream(any()) }
        coVerify(exactly = 0) { metadataService.setBible(any(), any()) }
    }

    @Test
    fun `reprocessBible not found`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id, 1) } returns null
        assertFailsExc<NoSuchElementException> { controller.reprocessBible(authentication, id, 1) }
    }

    // ---- media ----

    @Test
    fun `processMedia happy and not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        val options = MediaProcessingOptions(videoQuality = "plus")
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.MANAGE) } returns Unit
        coEvery { videoService.process(md, options) } returns Unit
        assertTrue(controller.processMedia(authentication, id, options))

        val missing = UUID.random()
        coEvery { metadataService.getById(missing) } returns null
        assertFailsExc<IllegalStateException> { controller.processMedia(authentication, missing, null) }
    }

    @Test
    fun `deleteMedia happy and not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.MANAGE) } returns Unit
        coEvery { videoService.delete(md) } returns Unit
        assertTrue(controller.deleteMedia(authentication, id))

        val missing = UUID.random()
        coEvery { metadataService.getById(missing) } returns null
        assertFailsExc<IllegalStateException> { controller.deleteMedia(authentication, missing) }
    }

    @Test
    fun `setMediaThumbnailOffset happy and not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.MANAGE) } returns Unit
        coEvery { videoService.setThumbnailTimeOffset(md, 1.5) } returns Unit
        assertTrue(controller.setMediaThumbnailOffset(authentication, id, 1.5))

        val missing = UUID.random()
        coEvery { metadataService.getById(missing) } returns null
        assertFailsExc<IllegalStateException> { controller.setMediaThumbnailOffset(authentication, missing, 1.5) }
    }

    @Test
    fun `updateMediaSettings happy and not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        val options = MediaProcessingOptions(maxResolutionTier = "1080p")
        coEvery { metadataService.getById(id) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.MANAGE) } returns Unit
        coEvery { videoService.updateMediaSettings(md, options) } returns Unit
        assertTrue(controller.updateMediaSettings(authentication, id, options))

        val missing = UUID.random()
        coEvery { metadataService.getById(missing) } returns null
        assertFailsExc<IllegalStateException> { controller.updateMediaSettings(authentication, missing, options) }
    }

    @Test
    fun `processMediaAll succeeds and swallows failures`() = runTest {
        val a = metadata()
        val b = metadata()
        val ids = listOf(a.id, b.id)
        coEvery { metadataService.getByIds(ids) } returns listOf(a, b)
        coEvery { metadataPermissionEvaluator.filterAllowed(authentication, listOf(a, b), PermissionAction.MANAGE) } returns listOf(a, b)
        coEvery { videoService.process(a) } returns Unit
        coEvery { videoService.process(b) } throws RuntimeException("boom")
        assertEquals(1, controller.processMediaAll(authentication, ids))
    }

    @Test
    fun `processMediaAll rejects too many`() = runTest {
        val ids = (0..500).map { UUID.random() }
        assertFailsExc<IllegalArgumentException> { controller.processMediaAll(authentication, ids) }
    }

    @Test
    fun `deleteMediaAll succeeds and swallows failures`() = runTest {
        val a = metadata()
        val b = metadata()
        val ids = listOf(a.id, b.id)
        coEvery { metadataService.getByIds(ids) } returns listOf(a, b)
        coEvery { metadataPermissionEvaluator.filterAllowed(authentication, listOf(a, b), PermissionAction.MANAGE) } returns listOf(a, b)
        coEvery { videoService.delete(a) } returns Unit
        coEvery { videoService.delete(b) } throws RuntimeException("boom")
        assertEquals(1, controller.deleteMediaAll(authentication, ids))
    }

    @Test
    fun `deleteMediaAll rejects too many`() = runTest {
        val ids = (0..500).map { UUID.random() }
        assertFailsExc<IllegalArgumentException> { controller.deleteMediaAll(authentication, ids) }
    }

    // ---- permanentlyDelete ----

    @Test
    fun `permanentlyDelete happy and not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id) } returns md
        coEvery { groupEvaluator.verifyHasSaGroup(authentication) } returns Unit
        coEvery { metadataService.delete(md) } returns Unit
        assertTrue(controller.permanentlyDelete(authentication, id))

        val missing = UUID.random()
        coEvery { metadataService.getById(missing) } returns null
        assertFalse(controller.permanentlyDelete(authentication, missing))
    }

    // ---- guide / template mutation entry points ----

    @Test
    fun `guideTemplate happy and not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        assertEquals(md, controller.guideTemplate(authentication, id, 1).metadata)

        val missing = UUID.random()
        coEvery { metadataService.getById(missing, 1) } returns null
        assertFailsExc<NoSuchElementException> { controller.guideTemplate(authentication, missing, 1) }
    }

    @Test
    fun `documentTemplate happy and not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        assertEquals(md, controller.documentTemplate(authentication, MetadataMutation, id, 1).metadata)

        val missing = UUID.random()
        coEvery { metadataService.getById(missing, 1) } returns null
        assertFailsExc<NoSuchElementException> { controller.documentTemplate(authentication, MetadataMutation, missing, 1) }
    }

    @Test
    fun `dataTemplate happy and not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        assertEquals(md, controller.dataTemplate(authentication, MetadataMutation, id, 1).metadata)

        val missing = UUID.random()
        coEvery { metadataService.getById(missing, 1) } returns null
        assertFailsExc<NoSuchElementException> { controller.dataTemplate(authentication, MetadataMutation, missing, 1) }
    }

    @Test
    fun `collectionTemplate happy and not found`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id)
        coEvery { metadataService.getById(id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        assertEquals(md, controller.collectionTemplate(authentication, MetadataMutation, id, 1).metadata)

        val missing = UUID.random()
        coEvery { metadataService.getById(missing, 1) } returns null
        assertFailsExc<NoSuchElementException> { controller.collectionTemplate(authentication, MetadataMutation, missing, 1) }
    }

    // ---- helper ----

    private suspend inline fun <reified T : Throwable> assertFailsExc(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.simpleName}")
        } catch (e: Throwable) {
            if (e !is T) throw e
        }
    }
}
