package bosca.content.metadata.graphql

import bosca.calendar.model.Calendar
import bosca.calendar.service.CalendarService
import bosca.category.model.Category
import bosca.comments.graphql.Comments
import bosca.comments.service.CommentService
import bosca.content.attributes.model.AttributesFilterInput
import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionTemplate
import bosca.content.metadata.model.Data
import bosca.content.metadata.model.DataCollaboration
import bosca.content.metadata.model.DataTemplate
import bosca.content.metadata.model.Document
import bosca.content.metadata.model.DocumentCollaboration
import bosca.content.metadata.model.DocumentTemplate
import bosca.content.metadata.model.Guide
import bosca.content.metadata.model.GuideTemplate
import bosca.content.metadata.model.Media
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.model.MetadataContent
import bosca.content.metadata.model.MetadataProfile
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.model.MetadataSupplementary
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.CollectionTemplateService
import bosca.content.metadata.service.DataService
import bosca.content.metadata.service.DataTemplateService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.DocumentTemplateService
import bosca.content.metadata.service.GuideService
import bosca.content.metadata.service.GuideTemplateService
import bosca.content.metadata.service.MediaService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.content.timeevent.model.TimeEvent
import bosca.content.timeevent.model.TimeEventFilter
import bosca.content.timeevent.service.TimeEventService
import bosca.content.metadata.model.Bible
import bosca.graphql.Batch
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.slug.service.SlugService
import bosca.trait.model.Trait
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MetadataControllerCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val collectionTemplateService = mockk<CollectionTemplateService>()
    private val collectionPermissionEvaluator = mockk<CollectionPermissionEvaluator>()
    private val profilePermissions = mockk<ProfilePermissionEvaluator>()
    private val slugService = mockk<SlugService>()
    private val documentService = mockk<DocumentService>()
    private val documentTemplateService = mockk<DocumentTemplateService>()
    private val dataTemplateService = mockk<DataTemplateService>()
    private val guideService = mockk<GuideService>()
    private val guideTemplateService = mockk<GuideTemplateService>()
    private val profileService = mockk<ProfileService>()
    private val dataService = mockk<DataService>()
    private val timeEventService = mockk<TimeEventService>()
    private val mediaService = mockk<MediaService>()
    private val calendarService = mockk<CalendarService>()
    private val commentService = mockk<CommentService>()

    private val controller = MetadataController(
        metadataService,
        metadataPermissionEvaluator,
        collectionTemplateService,
        collectionPermissionEvaluator,
        profilePermissions,
        slugService,
        documentService,
        documentTemplateService,
        dataTemplateService,
        guideService,
        guideTemplateService,
        profileService,
        dataService,
        timeEventService,
        mediaService,
        calendarService,
        commentService,
    )

    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun metadata(
        id: UUID = UUID.random(),
        version: Int = 1,
        name: String = "Test",
        type: MetadataType = MetadataType.STANDARD,
        workflowStateId: String = "published",
        attributes: kotlinx.serialization.json.JsonElement? = null,
        systemAttributes: kotlinx.serialization.json.JsonElement? = null,
        etag: String? = "etag-1",
        parentId: UUID? = null,
        modified: OffsetDateTime? = null,
        commentsEnabled: Boolean = false,
    ) = Metadata(
        id = id,
        version = version,
        parentId = parentId,
        name = name,
        type = type,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        attributes = attributes,
        systemAttributes = systemAttributes,
        workflowStateId = workflowStateId,
        etag = etag,
        modified = modified,
        commentsEnabled = commentsEnabled,
    )

    // ---- Simple property resolvers ----

    @Test
    fun `id version name type resolvers`() {
        val id = UUID.random()
        val md = metadata(id = id, version = 7, name = "Doc", type = MetadataType.VARIANT)
        assertEquals(id, controller.id(md))
        assertEquals(7, controller.version(md))
        assertEquals("Doc", controller.name(md))
        assertEquals(MetadataType.VARIANT, controller.type(md))
    }

    @Test
    fun `etag languageTag source workflow wrappers`() {
        val md = metadata(etag = "abc")
        assertEquals("abc", controller.etag(md, addHeader = true))
        assertEquals("en", controller.languageTag(md))
        assertSame(md, controller.source(md).metadata)
        assertSame(md, controller.workflow(md).metadata)
    }

    @Test
    fun `itemAttributes and systemAttributes resolvers`() {
        val sys = JsonObject(mapOf("k" to JsonPrimitive("v")))
        val md = metadata(systemAttributes = sys)
        md.itemAttributes = JsonObject(mapOf("i" to JsonPrimitive("j")))
        assertEquals(md.itemAttributes, controller.itemAttributes(md))
        assertEquals(sys, controller.systemAttributes(md))
    }

    @Test
    fun `boolean and flag resolvers`() {
        val md = metadata(commentsEnabled = true)
        assertEquals(md.deleted, controller.deleted(md))
        assertEquals(md.locked, controller.locked(md))
        assertEquals(md.public, controller.public(md))
        assertEquals(md.syncVariantCollections, controller.syncVariantCollections(md))
        assertEquals(md.syncVariantRelationships, controller.syncVariantRelationships(md))
        assertEquals(md.searchable, controller.searchable(md))
        assertEquals(md.recommendable, controller.recommendable(md))
        assertEquals(true, controller.commentsEnabled(md))
        assertEquals(md.commentRepliesEnabled, controller.commentRepliesEnabled(md))
        assertEquals(md.publicContent, controller.publicContent(md))
        assertEquals(md.publicSupplementary, controller.publicSupplementary(md))
    }

    @Test
    fun `parentId created uploaded ready labels resolvers`() {
        val parent = UUID.random()
        val md = metadata(parentId = parent)
        assertEquals(parent, controller.parentId(md))
        assertEquals(md.created, controller.created(md))
        assertEquals(md.uploaded, controller.uploaded(md))
        assertEquals(md.ready, controller.ready(md))
        assertEquals(md.labels, controller.labels(md))
    }

    @Test
    fun `modified returns modified when present`() {
        val when1 = OffsetDateTime.now().plusDays(1)
        val md = metadata(modified = when1)
        assertEquals(when1, controller.modified(md))
    }

    @Test
    fun `modified falls back to created when null`() {
        val md = metadata(modified = null)
        assertEquals(md.created, controller.modified(md))
    }

    @Test
    fun `ai wrapper wraps metadata`() {
        val md = metadata()
        assertSame(md, controller.ai(md).metadata)
    }

    // ---- variants ----

    @Test
    fun `variants returns permission-filtered variants`() = runTest {
        val md = metadata()
        val variants = listOf(metadata(), metadata())
        val filtered = listOf(variants.first())
        coEvery { metadataService.getByParentId(md.id) } returns variants
        coEvery {
            metadataPermissionEvaluator.filterAllowed(authentication, variants, PermissionAction.VIEW)
        } returns filtered

        assertEquals(filtered, controller.variants(authentication, md))
    }

    @Test
    fun `variants tolerates null authentication`() = runTest {
        val md = metadata()
        val variants = listOf(metadata())
        coEvery { metadataService.getByParentId(md.id) } returns variants
        coEvery {
            metadataPermissionEvaluator.filterAllowed(null, variants, PermissionAction.VIEW)
        } returns variants

        assertEquals(variants, controller.variants(null, md))
    }

    // ---- profiles ----

    @Test
    fun `profiles filters out profiles the caller cannot view`() = runTest {
        val md = metadata()
        val allowedProfileId = UUID.random()
        val deniedProfileId = UUID.random()
        val allowedMp = MetadataProfile(md.id, allowedProfileId, "author", 0)
        val deniedMp = MetadataProfile(md.id, deniedProfileId, "editor", 1)
        val allowedProfile = mockk<Profile>()
        val deniedProfile = mockk<Profile>()

        coEvery { metadataService.getProfiles(md.id) } returns listOf(allowedMp, deniedMp)
        coEvery { profileService.getById(allowedProfileId) } returns allowedProfile
        coEvery { profileService.getById(deniedProfileId) } returns deniedProfile
        coEvery { profilePermissions.isAllowed(authentication, allowedProfile, PermissionAction.VIEW) } returns true
        coEvery { profilePermissions.isAllowed(authentication, deniedProfile, PermissionAction.VIEW) } returns false

        assertEquals(listOf(allowedMp), controller.profiles(authentication, md))
    }

    // ---- attributes ----

    @Test
    fun `attributes returns raw attributes when filter is null and not advertised`() {
        val attrs = JsonObject(mapOf("a" to JsonPrimitive("1")))
        val md = metadata(attributes = attrs, workflowStateId = "published")
        assertEquals(attrs, controller.attributes(md, filter = null))
    }

    @Test
    fun `attributes applies advertised filter over map`() {
        val attrs = JsonObject(
            mapOf(
                "type" to JsonPrimitive("book"),
                "secret" to JsonPrimitive("hidden"),
            )
        )
        val md = metadata(attributes = attrs, workflowStateId = "advertised")
        val result = controller.attributes(md, filter = null)
        assertTrue(result is Map<*, *>)
        val map = result as Map<*, *>
        assertTrue(map.containsKey("type"))
        assertTrue(!map.containsKey("secret"))
    }

    @Test
    fun `attributes returns empty map when attributes not a map`() {
        val md = metadata(attributes = JsonPrimitive("scalar"), workflowStateId = "published")
        val filter = AttributesFilterInput(attributes = listOf("a"))
        assertEquals(emptyMap<String, Any>(), controller.attributes(md, filter))
    }

    @Test
    fun `attributes filters provided filter over map`() {
        val attrs = JsonObject(
            mapOf(
                "keep" to JsonPrimitive("yes"),
                "drop" to JsonPrimitive("no"),
            )
        )
        val md = metadata(attributes = attrs, workflowStateId = "published")
        val filter = AttributesFilterInput(attributes = listOf("keep"))
        val result = controller.attributes(md, filter)
        assertTrue(result is Map<*, *>)
        val map = result as Map<*, *>
        assertTrue(map.containsKey("keep"))
        assertTrue(!map.containsKey("drop"))
    }

    // ---- collaboration / calendar ----

    @Test
    fun `documentCollaboration delegates to documentService`() = runTest {
        val md = metadata()
        val collab = mockk<DocumentCollaboration>()
        coEvery { documentService.getCollaboration(md.id, md.version) } returns collab
        assertSame(collab, controller.documentCollaboration(md))
    }

    @Test
    fun `documentCollaboration returns null when absent`() = runTest {
        val md = metadata()
        coEvery { documentService.getCollaboration(md.id, md.version) } returns null
        assertNull(controller.documentCollaboration(md))
    }

    @Test
    fun `dataCollaboration delegates to dataService`() = runTest {
        val md = metadata()
        val collab = mockk<DataCollaboration>()
        coEvery { dataService.getCollaboration(md.id, md.version) } returns collab
        assertSame(collab, controller.dataCollaboration(md))
    }

    @Test
    fun `calendar delegates to calendarService`() = runTest {
        val md = metadata()
        val cal = mockk<Calendar>()
        coEvery { calendarService.getCalendar(authentication, md.id, md.version) } returns cal
        assertSame(cal, controller.calendar(authentication, md))
    }

    @Test
    fun `calendar returns null when absent`() = runTest {
        val md = metadata()
        coEvery { calendarService.getCalendar(null, md.id, md.version) } returns null
        assertNull(controller.calendar(null, md))
    }

    // ---- bible ----

    @Test
    fun `bible delegates to metadataService`() = runTest {
        val md = metadata()
        val bible = mockk<Bible>()
        coEvery { metadataService.getBible(md.id, md.version, "kjv") } returns bible
        assertSame(bible, controller.bible(md, "kjv"))
    }

    @Test
    fun `bible tolerates null variant`() = runTest {
        val md = metadata()
        coEvery { metadataService.getBible(md.id, md.version, null) } returns null
        assertNull(controller.bible(md, null))
    }

    @Test
    fun `bibles returns only enabled variants by default`() = runTest {
        val md = metadata()
        val enabled = mockk<Bible>()
        val disabled = mockk<Bible>()
        every { enabled.enabled } returns true
        every { disabled.enabled } returns false
        coEvery { metadataService.getBibles(md.id, md.version) } returns listOf(enabled, disabled)

        assertEquals(listOf(enabled), controller.bibles(null, md, null))
    }

    @Test
    fun `bibles returns disabled variants to metadata editors when requested`() = runTest {
        val md = metadata()
        val enabled = mockk<Bible>()
        val disabled = mockk<Bible>()
        every { enabled.enabled } returns true
        every { disabled.enabled } returns false
        val variants = listOf(enabled, disabled)
        coEvery { metadataService.getBibles(md.id, md.version) } returns variants
        coEvery {
            metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT)
        } returns Unit

        assertEquals(variants, controller.bibles(authentication, md, true))
        coVerify { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) }
    }

    // ---- permissions batch ----

    @Test
    fun `permissions builds sub-batch and ensures non-null defaults`() = runTest {
        val id1 = UUID.random()
        val id2 = UUID.random()
        val batch = Batch<MetadataCacheKeyId, List<EntityPermission>>(
            keys = listOf(MetadataCacheKeyId(id1), MetadataCacheKeyId(id2))
        )
        val perm = mockk<EntityPermission>()
        coEvery { metadataService.addPermissionsToBatch(any<Batch<UUID, List<EntityPermission>>>()) } coAnswers {
            val inner = firstArg<Batch<UUID, List<EntityPermission>>>()
            inner.setData(id1, listOf(perm))
        }

        controller.permissions(batch)

        assertEquals(listOf(perm), batch.getData(MetadataCacheKeyId(id1)))
        assertEquals(emptyList(), batch.getData(MetadataCacheKeyId(id2)))
    }

    // ---- slug batch ----

    @Test
    fun `slug delegates to slugService`() = runTest {
        val batch = Batch<MetadataCacheKeyId, String>(keys = listOf(MetadataCacheKeyId(UUID.random())))
        coEvery { slugService.addMetadataSlugsToBatch(batch) } returns Unit
        controller.slug(batch)
        coVerify { slugService.addMetadataSlugsToBatch(batch) }
    }

    // ---- traits / traitIds / categoryIds / categories batches ----

    @Test
    fun `traits ensures non-null empty defaults`() = runTest {
        val key = MetadataCacheKeyId(UUID.random())
        val batch = Batch<MetadataCacheKeyId, List<Trait>>(keys = listOf(key))
        coEvery { metadataService.addTraitsToBatch(batch) } returns Unit
        controller.traits(batch)
        assertEquals(emptyList(), batch.getData(key))
    }

    @Test
    fun `traitIds ensures non-null empty defaults`() = runTest {
        val key = MetadataCacheKeyId(UUID.random())
        val batch = Batch<MetadataCacheKeyId, List<String>>(keys = listOf(key))
        coEvery { metadataService.addTraitIdsToBatch(batch) } returns Unit
        controller.traitIds(batch)
        assertEquals(emptyList(), batch.getData(key))
    }

    @Test
    fun `categoryIds ensures non-null empty defaults`() = runTest {
        val key = MetadataCacheKeyId(UUID.random())
        val batch = Batch<MetadataCacheKeyId, List<UUID>>(keys = listOf(key))
        coEvery { metadataService.addCategoryIdsToBatch(batch) } returns Unit
        controller.categoryIds(batch)
        assertEquals(emptyList(), batch.getData(key))
    }

    @Test
    fun `categories ensures non-null empty defaults`() = runTest {
        val key = MetadataCacheKeyId(UUID.random())
        val batch = Batch<MetadataCacheKeyId, List<Category>>(keys = listOf(key))
        coEvery { metadataService.addCategoriesToBatch(batch) } returns Unit
        controller.categories(batch)
        assertEquals(emptyList(), batch.getData(key))
    }

    // ---- content batch (filter body) ----

    @Test
    fun `content sets filter that copies allowed flags and delegates to service`() = runTest {
        val md1 = metadata()
        val md2 = metadata()
        val key1 = MetadataCacheKeyId(md1)
        val key2 = MetadataCacheKeyId(md2)
        val key3 = MetadataCacheKeyId(UUID.random())
        val batch = Batch<MetadataCacheKeyId, MetadataContent>(keys = listOf(key1, key2, key3))
        coEvery { metadataService.getByIdBatched(any()) } returns Unit
        coEvery {
            metadataPermissionEvaluator.isContentAllowed(authentication, any<List<Metadata>>(), PermissionAction.VIEW)
        } returns listOf(true, false, false)

        controller.content(authentication, batch)
        batch.setData(0, MetadataContent(md1))
        batch.setData(1, MetadataContent(md2))
        // key3 intentionally left without data -> exercises the EmptyMetadata fallback
        val results = batch.getResults()

        assertEquals(true, results[0]?.allowed)
        assertEquals(false, results[1]?.allowed)
        assertNull(results[2])
    }

    // ---- supplementary batch (filter body) ----

    @Test
    fun `supplementary filter keeps allowed and drops denied`() = runTest {
        val now = OffsetDateTime.now()
        val mdA = metadata()
        val mdB = metadata()
        val suppA = MetadataSupplementary(metadataId = mdA.id, key = "k", name = "A", created = now, modified = now)
        val suppB = MetadataSupplementary(metadataId = mdB.id, key = "k", name = "B", created = now, modified = now)
        val ctxA = MetadataSupplementaryContext(mdA, suppA)
        val ctxB = MetadataSupplementaryContext(mdB, suppB)
        val key1 = MetadataCacheKeyId(mdA)
        val key2 = MetadataCacheKeyId(mdB)
        val emptyKey = MetadataCacheKeyId(UUID.random())
        val batch = Batch<MetadataCacheKeyId, List<MetadataSupplementaryContext>>(keys = listOf(key1, key2, emptyKey))

        coEvery { metadataService.addSupplementaryToBatch(any()) } returns Unit
        coEvery { metadataService.getByIdBatched(any<Batch<MetadataCacheKeyId, Metadata>>()) } coAnswers {
            val inner = firstArg<Batch<MetadataCacheKeyId, Metadata>>()
            inner.setData(MetadataCacheKeyId(mdA.id), mdA)
            inner.setData(MetadataCacheKeyId(mdB.id), mdB)
        }
        coEvery {
            metadataPermissionEvaluator.isSupplementaryAllowed(authentication, any<List<Metadata>>(), PermissionAction.VIEW)
        } returns listOf(true, false)

        controller.supplementary(authentication, batch)
        batch.setData(0, listOf(ctxA))
        batch.setData(1, listOf(ctxB))
        // emptyKey intentionally left without data -> exercises the null-data branches
        val results = batch.getResults()

        assertEquals(listOf(ctxA), results[0])
        assertEquals(emptyList(), results[1])
        assertEquals(emptyList(), results[2])
    }

    // ---- media batch ----

    @Test
    fun `media delegates to mediaService`() = runTest {
        val batch = Batch<MetadataCacheKeyId, Media>(keys = listOf(MetadataCacheKeyId(UUID.random())))
        coEvery { mediaService.addToBatch(batch) } returns Unit
        controller.media(batch)
        coVerify { mediaService.addToBatch(batch) }
    }

    // ---- relationships batch (filter body) ----

    @Test
    fun `relationships filter drops relationships to disallowed metadata`() = runTest {
        val target1 = metadata()
        val target2 = metadata()
        val rel1 = MetadataRelationship(metadataId1 = UUID.random(), metadataId2 = target1.id, relationship = "r1")
        val rel2 = MetadataRelationship(metadataId1 = UUID.random(), metadataId2 = target2.id, relationship = "r2")
        val key = MetadataCacheKeyId(UUID.random())
        val emptyKey = MetadataCacheKeyId(UUID.random())
        val batch = Batch<MetadataCacheKeyId, List<MetadataRelationship>>(keys = listOf(key, emptyKey))

        coEvery { metadataService.addRelationshipsToBatch(batch) } returns Unit
        coEvery { metadataService.getByIdBatched(any<Batch<MetadataCacheKeyId, Metadata>>()) } coAnswers {
            val inner = firstArg<Batch<MetadataCacheKeyId, Metadata>>()
            inner.setData(MetadataCacheKeyId(target1.id), target1)
            inner.setData(MetadataCacheKeyId(target2.id), target2)
        }
        coEvery {
            metadataPermissionEvaluator.isContentAllowed(authentication, any<List<Metadata>>(), PermissionAction.VIEW)
        } returns listOf(true, false)

        controller.relationships(authentication, batch)
        batch.setData(0, listOf(rel1, rel2))
        // emptyKey intentionally left without data -> exercises the `it.data ?: emptyList()` null branch
        val results = batch.getResults()

        assertEquals(listOf(rel1), results[0])
        assertEquals(emptyList(), results[1])
    }

    // ---- parentCollections ----

    @Test
    fun `parentCollections uses paginated overload when offset and limit present`() = runTest {
        val md = metadata()
        val allowed = Collection(name = "P1", languageTag = "en", workflowStateId = "published")
        val denied = Collection(name = "P2", languageTag = "en", workflowStateId = "published")
        coEvery { metadataService.getParents(md.id, 5L, 10) } returns listOf(allowed, denied)
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, allowed, PermissionAction.VIEW)
        } returns true
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, denied, PermissionAction.VIEW)
        } returns false

        val result = controller.parentCollections(authentication, md, offset = 5L, limit = 10)

        assertEquals(listOf(allowed), result)
        assertEquals(allowed.attributes, allowed.itemAttributes)
    }

    @Test
    fun `parentCollections uses unpaginated overload when offset null`() = runTest {
        val md = metadata()
        val col = Collection(name = "P", languageTag = "en", workflowStateId = "published")
        coEvery { metadataService.getParents(md.id) } returns listOf(col)
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, col, PermissionAction.VIEW)
        } returns true

        val result = controller.parentCollections(authentication, md, offset = null, limit = 10)
        assertEquals(listOf(col), result)
    }

    @Test
    fun `parentCollections uses unpaginated overload when limit null`() = runTest {
        val md = metadata()
        val col = Collection(name = "P", languageTag = "en", workflowStateId = "published")
        coEvery { metadataService.getParents(md.id) } returns listOf(col)
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, col, PermissionAction.VIEW)
        } returns false

        val result = controller.parentCollections(authentication, md, offset = 5L, limit = null)
        assertEquals(emptyList(), result)
    }

    // ---- template / data / document / guide batch resolvers ----

    @Test
    fun `collectionTemplate delegates`() = runTest {
        val batch = Batch<MetadataCacheKeyId, CollectionTemplate>(keys = listOf(MetadataCacheKeyId(UUID.random())))
        coEvery { collectionTemplateService.addCollectionTemplatesBatch(batch) } returns Unit
        controller.collectionTemplate(batch)
        coVerify { collectionTemplateService.addCollectionTemplatesBatch(batch) }
    }

    @Test
    fun `documentTemplate delegates`() = runTest {
        val batch = Batch<MetadataCacheKeyId, DocumentTemplate>(keys = listOf(MetadataCacheKeyId(UUID.random())))
        coEvery { documentTemplateService.addToBatch(batch) } returns Unit
        controller.documentTemplate(batch)
        coVerify { documentTemplateService.addToBatch(batch) }
    }

    @Test
    fun `dataTemplate delegates`() = runTest {
        val batch = Batch<MetadataCacheKeyId, DataTemplate>(keys = listOf(MetadataCacheKeyId(UUID.random())))
        coEvery { dataTemplateService.addToBatch(batch) } returns Unit
        controller.dataTemplate(batch)
        coVerify { dataTemplateService.addToBatch(batch) }
    }

    @Test
    fun `guideTemplate delegates`() = runTest {
        val batch = Batch<MetadataCacheKeyId, GuideTemplate>(keys = listOf(MetadataCacheKeyId(UUID.random())))
        coEvery { guideTemplateService.addTemplatesToBatch(batch) } returns Unit
        controller.guideTemplate(batch)
        coVerify { guideTemplateService.addTemplatesToBatch(batch) }
    }

    @Test
    fun `data delegates`() = runTest {
        val batch = Batch<MetadataCacheKeyId, Data>(keys = listOf(MetadataCacheKeyId(UUID.random())))
        coEvery { dataService.addToBatch(batch) } returns Unit
        controller.data(batch)
        coVerify { dataService.addToBatch(batch) }
    }

    @Test
    fun `document delegates`() = runTest {
        val batch = Batch<MetadataCacheKeyId, Document>(keys = listOf(MetadataCacheKeyId(UUID.random())))
        coEvery { documentService.getDocumentsBatch(batch) } returns Unit
        controller.document(batch)
        coVerify { documentService.getDocumentsBatch(batch) }
    }

    @Test
    fun `guide delegates`() = runTest {
        val batch = Batch<MetadataCacheKeyId, Guide>(keys = listOf(MetadataCacheKeyId(UUID.random())))
        coEvery { guideService.addGuidesToBatch(batch) } returns Unit
        controller.guide(batch)
        coVerify { guideService.addGuidesToBatch(batch) }
    }

    // ---- timeEvents ----

    private fun timeEvent(
        metadataId: UUID,
        type: String = "chapter",
        start: Long = 0,
        end: Long? = null,
    ) = TimeEvent(
        metadataId = metadataId,
        metadataVersion = 1,
        type = type,
        startOffsetMs = start,
        endOffsetMs = end,
    )

    @Test
    fun `timeEvents at offset branch`() = runTest {
        val md = metadata()
        val event = timeEvent(md.id, start = 100)
        coEvery { timeEventService.getTimeEventsAtOffset(md.id, md.version, 500L) } returns listOf(event)

        val filter = TimeEventFilter(atOffsetMs = 500L)
        assertEquals(listOf(event), controller.timeEvents(md, filter))
    }

    @Test
    fun `timeEvents single-type branch`() = runTest {
        val md = metadata()
        val event = timeEvent(md.id, type = "verse")
        coEvery { timeEventService.getTimeEventsByType(md.id, md.version, "verse") } returns listOf(event)

        val filter = TimeEventFilter(types = listOf("verse"))
        assertEquals(listOf(event), controller.timeEvents(md, filter))
    }

    @Test
    fun `timeEvents multi-type branch merges per-type`() = runTest {
        val md = metadata()
        val e1 = timeEvent(md.id, type = "verse")
        val e2 = timeEvent(md.id, type = "chapter")
        coEvery { timeEventService.getTimeEventsByType(md.id, md.version, "verse") } returns listOf(e1)
        coEvery { timeEventService.getTimeEventsByType(md.id, md.version, "chapter") } returns listOf(e2)

        val filter = TimeEventFilter(types = listOf("verse", "chapter"))
        assertEquals(listOf(e1, e2), controller.timeEvents(md, filter))
    }

    @Test
    fun `timeEvents default branch when filter null`() = runTest {
        val md = metadata()
        val event = timeEvent(md.id)
        coEvery { timeEventService.getTimeEvents(md.id, md.version) } returns listOf(event)

        assertEquals(listOf(event), controller.timeEvents(md, filter = null))
    }

    @Test
    fun `timeEvents default branch when types empty`() = runTest {
        val md = metadata()
        val event = timeEvent(md.id)
        coEvery { timeEventService.getTimeEvents(md.id, md.version) } returns listOf(event)

        val filter = TimeEventFilter(types = emptyList())
        assertEquals(listOf(event), controller.timeEvents(md, filter))
    }

    @Test
    fun `timeEvents applies startAfter and endBefore filters`() = runTest {
        val md = metadata()
        val keep = timeEvent(md.id, start = 200, end = 400)
        val tooEarly = timeEvent(md.id, start = 50, end = 400)
        val tooLate = timeEvent(md.id, start = 200, end = 9000)
        val nullEnd = timeEvent(md.id, start = 200, end = null)
        coEvery { timeEventService.getTimeEvents(md.id, md.version) } returns
            listOf(keep, tooEarly, tooLate, nullEnd)

        val filter = TimeEventFilter(startAfterMs = 100L, endBeforeMs = 500L)
        assertEquals(listOf(keep), controller.timeEvents(md, filter))
    }

    // ---- timeEventsAtOffset ----

    @Test
    fun `timeEventsAtOffset filters by type when provided`() = runTest {
        val md = metadata()
        val a = timeEvent(md.id, type = "verse")
        val b = timeEvent(md.id, type = "chapter")
        coEvery { timeEventService.getTimeEventsAtOffset(md.id, md.version, 300L) } returns listOf(a, b)

        val result = controller.timeEventsAtOffset(md, 300L, types = listOf("verse"))
        assertEquals(listOf(a), result)
    }

    @Test
    fun `timeEventsAtOffset returns all when types null`() = runTest {
        val md = metadata()
        val a = timeEvent(md.id, type = "verse")
        val b = timeEvent(md.id, type = "chapter")
        coEvery { timeEventService.getTimeEventsAtOffset(md.id, md.version, 300L) } returns listOf(a, b)

        val result = controller.timeEventsAtOffset(md, 300L, types = null)
        assertEquals(listOf(a, b), result)
    }

    // ---- comments ----

    @Test
    fun `comments as manager resolves profile and returns page`() = runTest {
        val md = metadata()
        val principalId = UUID.random()
        val profileId = UUID.random()
        val principal = mockk<AuthenticatedPrincipal>()
        val profile = mockk<Profile>()
        every { authentication.principal() } returns principal
        every { principal.id } returns principalId
        every { profile.id } returns profileId

        coEvery { metadataPermissionEvaluator.isAllowed(authentication, md, PermissionAction.MANAGE) } returns true
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(profile)
        coEvery {
            commentService.getMetadataComments(profileId, md.id, md.version, true, 0L, 20L, false)
        } returns emptyList()
        coEvery {
            commentService.getMetadataCommentsCount(profileId, md.id, md.version, true, false)
        } returns 3L

        val result = controller.comments(authentication, md, limit = 20, offset = 0, pinned = null)
        assertEquals(Comments(emptyList(), 3), result)
    }

    @Test
    fun `comments non-manager with null authentication and pinnedOnly`() = runTest {
        val md = metadata()
        coEvery { metadataPermissionEvaluator.isAllowed(null, md, PermissionAction.MANAGE) } returns false
        coEvery {
            commentService.getMetadataComments(null, md.id, md.version, false, 5L, 10L, true)
        } returns emptyList()
        coEvery {
            commentService.getMetadataCommentsCount(null, md.id, md.version, false, true)
        } returns 0L

        val result = controller.comments(null, md, limit = 10, offset = 5, pinned = true)
        assertEquals(Comments(emptyList(), 0), result)
    }

    @Test
    fun `comments manager with no matching profile yields null profileId`() = runTest {
        val md = metadata()
        val principalId = UUID.random()
        val principal = mockk<AuthenticatedPrincipal>()
        every { authentication.principal() } returns principal
        every { principal.id } returns principalId

        coEvery { metadataPermissionEvaluator.isAllowed(authentication, md, PermissionAction.MANAGE) } returns true
        coEvery { profileService.getByPrincipal(principalId) } returns emptyList()
        coEvery {
            commentService.getMetadataComments(null, md.id, md.version, true, 0L, 20L, false)
        } returns emptyList()
        coEvery {
            commentService.getMetadataCommentsCount(null, md.id, md.version, true, false)
        } returns 1L

        val result = controller.comments(authentication, md, limit = 20, offset = 0, pinned = false)
        assertEquals(Comments(emptyList(), 1), result)
    }

    // ---- hasUnmoderatedComments ----

    @Test
    fun `hasUnmoderatedComments returns false for non-manager`() = runTest {
        val md = metadata()
        coEvery { metadataPermissionEvaluator.isAllowed(authentication, md, PermissionAction.MANAGE) } returns false
        assertEquals(false, controller.hasUnmoderatedComments(authentication, md))
    }

    @Test
    fun `hasUnmoderatedComments delegates for manager`() = runTest {
        val md = metadata()
        coEvery { metadataPermissionEvaluator.isAllowed(authentication, md, PermissionAction.MANAGE) } returns true
        coEvery { commentService.hasUnmoderatedComments(md.id, md.version) } returns true
        assertEquals(true, controller.hasUnmoderatedComments(authentication, md))
    }

    // ---- lastCommentAt ----

    @Test
    fun `lastCommentAt returns null when caller cannot view`() = runTest {
        val md = metadata()
        coEvery { metadataPermissionEvaluator.isAllowed(null, md, PermissionAction.VIEW) } returns false
        assertNull(controller.lastCommentAt(null, md))
    }

    @Test
    fun `lastCommentAt delegates when viewable`() = runTest {
        val md = metadata()
        val when1 = OffsetDateTime.now()
        coEvery { metadataPermissionEvaluator.isAllowed(authentication, md, PermissionAction.VIEW) } returns true
        coEvery { commentService.getLastCommentAt(md.id, md.version) } returns when1
        assertEquals(when1, controller.lastCommentAt(authentication, md))
    }
}
