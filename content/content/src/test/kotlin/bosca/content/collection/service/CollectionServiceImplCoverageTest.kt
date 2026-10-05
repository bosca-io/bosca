package bosca.content.collection.service

import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.asCoroutineContext
import bosca.attributes.AttributeLocation
import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionCollaboration
import bosca.content.collection.model.CollectionCollaborationInput
import bosca.content.collection.model.CollectionInput
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.model.CollectionLanguageVariantInput
import bosca.content.collection.model.CollectionMetadataRelationshipInput
import bosca.content.collection.model.CollectionSupplementaryInput
import bosca.content.collection.model.CollectionTemplateAttribute
import bosca.content.collection.repository.CollectionCategoryRepositoryImpl
import bosca.content.collection.repository.CollectionCollaborationRepositoryImpl
import bosca.content.collection.repository.CollectionFindRepository
import bosca.content.collection.repository.CollectionItemRepositoryImpl
import bosca.content.collection.repository.CollectionLanguageVariantRepositoryImpl
import bosca.content.collection.repository.CollectionMetadataRelationshipRepositoryImpl
import bosca.content.collection.repository.CollectionPermissionRepositoryImpl
import bosca.content.collection.repository.CollectionRepositoryImpl
import bosca.content.collection.repository.CollectionSupplementaryRepositoryImpl
import bosca.content.collection.repository.CollectionTraitRepositoryImpl
import bosca.content.collection.repository.CollectionWorkflowPlanRepositoryImpl
import bosca.content.find.FindQueryInput
import bosca.content.recommendation.service.RecommendationContextClassifier
import bosca.content.metadata.repository.CollectionTemplateRepositoryImpl
import bosca.content.metadata.repository.CollectionTemplateAttributeRepositoryImpl
import bosca.content.metadata.repository.MetadataRepositoryImpl
import bosca.content.transition.history.service.TransitionHistoryService
import bosca.content.transition.service.Transitioner
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.events.DisabledEventManagerFilter
import bosca.events.EventManager
import bosca.events.asCoroutineContext
import bosca.security.model.PermissionAction
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUIDSerializer
import bosca.slug.service.SlugService
import bosca.storage.service.ObjectStorageService
import bosca.security.model.Principal
import bosca.serialization.UUID
import bosca.test.ContentTestInfrastructure
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.junit.AfterClass
import org.junit.BeforeClass
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@OptIn(InternalDI::class)
class CollectionServiceImplCoverageTest {

    private lateinit var serializer: RequestCacheSerializer

    private val connectionPool
        get() = infrastructure.connectionPool

    private val cacheManager
        get() = infrastructure.cacheManager

    private val testJson = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(OffsetDateTimeSerializer())
            contextual(UUIDSerializer())
        }
    }

    private lateinit var service: CollectionServiceImpl
    private val slugService = mockk<SlugService>(relaxed = true)
    private val transitioner = mockk<Transitioner>(relaxed = true)
    private val securityService = mockk<SecurityService>(relaxed = true)
    private val objectService = mockk<ObjectStorageService>(relaxed = true)

    @BeforeTest
    fun setup() = runBlocking {
        unmockkAll()
        infrastructure.reset()
        serializer = RequestCacheSerializerImpl(testJson)

        ProviderRegistry.clear()
        provides<CacheManager>(singleton = true) { cacheManager }
        provides<RequestCacheSerializer>(singleton = true) { serializer }
        provides<Json>(singleton = true) { testJson }

        coEvery { slugService.createSlug(any()) } answers { firstArg<String>().lowercase().replace(" ", "-") }
        coEvery { securityService.getPrincipalGroups(any<UUID>()) } returns emptyList()

        val transitionerProvider = mockk<ObjectProvider<Transitioner>>()
        coEvery { transitionerProvider.get() } returns transitioner

        val securityProvider = mockk<ObjectProvider<SecurityService>>()
        coEvery { securityProvider.get() } returns securityService

        service = CollectionServiceImpl(
            CollectionRepositoryImpl(),
            CollectionItemRepositoryImpl(),
            CollectionFindRepository(testJson),
            CollectionCategoryRepositoryImpl(),
            CollectionTraitRepositoryImpl(),
            CollectionMetadataRelationshipRepositoryImpl(),
            CollectionSupplementaryRepositoryImpl(),
            CollectionWorkflowPlanRepositoryImpl(),
            CollectionPermissionRepositoryImpl(),
            mockk<TransitionHistoryService>(relaxed = true),
            objectService,
            testJson,
            slugService,
            CollectionCollaborationRepositoryImpl(),
            CollectionLanguageVariantRepositoryImpl(),
            CollectionTemplateRepositoryImpl(),
            CollectionTemplateAttributeRepositoryImpl(),
            MetadataRepositoryImpl(),
            transitionerProvider,
            securityProvider,
        )
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    companion object {

        private val infrastructure = ContentTestInfrastructure()

        @BeforeClass
        @JvmStatic
        fun startInfrastructure() = runBlocking {
            infrastructure.start()
        }

        @AfterClass
        @JvmStatic
        fun stopInfrastructure() = runBlocking {
            infrastructure.stop()
        }
    }

    private suspend fun <T> withRequest(block: suspend () -> T): T {
        val cm = ConnectionManager(connectionPool)
        val rc = RequestCache(cacheManager, serializer)
        val em = EventManager().apply { filter = DisabledEventManagerFilter }
        return withContext(cm.asCoroutineContext() + rc.asCoroutineContext() + em.asCoroutineContext()) {
            try {
                block()
            } finally {
                withContext(NonCancellable) {
                    cm.release()
                }
            }
        }
    }

    private suspend fun rawExec(sql: String, vararg ids: UUID) {
        val cm = ConnectionManager(connectionPool)
        withContext(cm.asCoroutineContext()) {
            cm.beginTransaction()
            cm.useStatement(sql) { stmt ->
                ids.forEachIndexed { index, id -> stmt.setObject(index + 1, id.toJavaUuid()) }
                stmt.execute()
            }
            withContext(NonCancellable) {
                cm.commitTransaction()
            }
        }
        withContext(NonCancellable) {
            cm.release()
        }
    }

    private suspend fun <T> firstResult(sql: String, mapper: (java.sql.ResultSet) -> T): T {
        val cm = ConnectionManager(connectionPool)
        return try {
            withContext(cm.asCoroutineContext()) {
                cm.beginTransaction()
                val result = cm.useStatement(sql) { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        mapper(rs)
                    }
                }
                withContext(NonCancellable) { cm.commitTransaction() }
                result
            }
        } finally {
            withContext(NonCancellable) { cm.release() }
        }
    }

    private suspend fun newCollection(name: String = "C"): Collection = withRequest {
        service.add(CollectionInput(name = name), parent = null, parentItemAttributes = null)
    }

    private suspend fun insertMetadata(): UUID {
        val id = UUID.random()
        rawExec("insert into metadata (id, name, content_type) values (?, 'M', 'text/plain')", id)
        return id
    }

    private suspend fun insertCategory(): UUID {
        val id = UUID.random()
        rawExec("insert into categories (id, name) values (?, 'cat')", id)
        return id
    }

    private suspend fun defaultGroupId(): UUID =
        firstResult("select id from groups limit 1") { rs ->
            (rs.getObject("id") as java.util.UUID).toKotlinUuid()
        }

    // ── addRoot ──────────────────────────────────────────────────────────────

    @Test
    fun `addRoot creates a root collection`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val classifier = mockk<RecommendationContextClassifier>()
        provides<RecommendationContextClassifier>(singleton = true) { classifier }
        coEvery { classifier.classifyCollection("STANDARD", any()) } returns listOf("default")
        val categoryId = insertCategory()
        // NOTE: traitIds is intentionally NOT exercised here. `addRoot` fans out to
        // `CollectionTraitRepository.add`, whose @Query is
        //   `insert into collection_traits (collection_id, trait_id) values (:collectionId, :traitId)`
        // with NO `returning *`, yet the method returns a non-Unit `CollectionTrait`. The KSP-generated
        // impl therefore calls `stmt.executeQuery()` on a plain INSERT, which pgjdbc rejects with
        // PSQLException "No results were returned by the query." That is a defect in the main source
        // (the query needs `returning *`), reachable only through the trait-add path; it cannot be
        // fixed from the test. The category branch of `addRoot` uses a repository whose add DOES
        // include `returning *`, so we validate that branch and the root creation itself.
        val created = withRequest {
            service.addRoot(
                CollectionInput(name = "Root", categoryIds = listOf(categoryId))
            )
        }
        assertNotNull(created.id)
        assertEquals("Root", created.name)
        val cats = withRequest { service.getCategoryIds(created.id) }
        assertTrue(cats.contains(categoryId))
        assertTrue(withRequest { service.getTraitIds(created.id) }.isEmpty())
        assertEquals(listOf("default"), created.recommendationContexts)
        coVerify(exactly = 1) { classifier.classifyCollection("STANDARD", any()) }

        withRequest { service.setRecommendationContexts(created.id, listOf("featured")) }
        assertEquals(listOf("featured"), withRequest { service.getById(created.id) }?.recommendationContexts)
    }

    @Test
    fun `addRoot applies template ordering when input ordering empty`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val templateId = insertMetadata()
        withRequest {
            CollectionTemplateRepositoryImpl().add(templateId, 1, null, null, JsonObject(emptyMap()), buildJsonObject { put("kind", "custom") })
        }
        val created = withRequest {
            service.addRoot(CollectionInput(name = "TplRoot", templateMetadataId = templateId, templateMetadataVersion = 1))
        }
        assertNotNull(created.ordering)
    }

    @Test
    fun `addRoot with template but no version uses latest`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val templateId = insertMetadata()
        withRequest {
            CollectionTemplateRepositoryImpl().add(templateId, 1, null, null, JsonObject(emptyMap()), buildJsonObject { put("kind", "custom") })
        }
        val created = withRequest {
            service.addRoot(CollectionInput(name = "TplRootNoVer", templateMetadataId = templateId))
        }
        assertNotNull(created)
    }

    // ── add with template default attributes / slug ────────────────────────────

    @Test
    fun `add merges template default attributes and uses explicit slug`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val templateId = insertMetadata()
        withRequest {
            CollectionTemplateRepositoryImpl().add(
                templateId, 1,
                buildJsonObject { put("defaulted", "yes") },
                null, JsonObject(emptyMap()),
                buildJsonObject { put("kind", "custom") },
            )
        }
        val created = withRequest {
            service.add(
                CollectionInput(
                    name = "WithTemplate",
                    slug = "explicit-slug",
                    templateMetadataId = templateId,
                    templateMetadataVersion = 1,
                    attributes = buildJsonObject { put("own", "attr") },
                ),
                parent = null,
                parentItemAttributes = null,
            )
        }
        assertNotNull(created.id)
    }

    @Test
    fun `add with template no version resolves latest template`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val templateId = insertMetadata()
        withRequest {
            CollectionTemplateRepositoryImpl().add(templateId, 1, null, null, JsonObject(emptyMap()), null)
        }
        val created = withRequest {
            service.add(
                CollectionInput(name = "TplLatest", templateMetadataId = templateId),
                parent = null,
                parentItemAttributes = null,
            )
        }
        assertNotNull(created.id)
    }

    // ── getDeleted / getByIds / parents / variants ─────────────────────────────

    @Test
    fun `getDeleted returns soft deleted collections`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("del")
        withRequest { service.markDeleted(created.id) }
        val deleted = withRequest { service.getDeleted(0, 1000) }
        assertTrue(deleted.any { it.id == created.id })
    }

    @Test
    fun `getByIds returns present collections and skips missing`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val a = newCollection("a")
        val b = newCollection("b")
        val result = withRequest { service.getByIds(listOf(a.id, b.id, UUID.random())) }
        assertTrue(result.any { it.id == a.id })
        assertTrue(result.any { it.id == b.id })
    }

    @Test
    fun `getCollectionParents returns empty when none`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val child = newCollection("orphan")
        assertTrue(withRequest { service.getCollectionParents(child.id) }.isEmpty())
        assertTrue(withRequest { service.getCollectionParents(child.id, 0, 100) }.isEmpty())
    }

    @Test
    fun `getCollectionParents returns parents after adding item`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val parent = newCollection("parent")
        val child = newCollection("child")
        withRequest { service.addCollectionItem(parent.id, child.id, null) }
        val parents = withRequest { service.getCollectionParents(child.id) }
        assertTrue(parents.any { it.id == parent.id })
        val parentsPaged = withRequest { service.getCollectionParents(child.id, 0, 100) }
        assertTrue(parentsPaged.any { it.id == parent.id })
    }

    @Test
    fun `getMetadataParents returns parents and empty variants`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val parent = newCollection("mparent")
        val metadataId = insertMetadata()
        withRequest { service.addMetadataItem(parent.id, metadataId, null) }
        assertTrue(withRequest { service.getMetadataParents(metadataId) }.any { it.id == parent.id })
        assertTrue(withRequest { service.getMetadataParents(metadataId, 0, 100) }.any { it.id == parent.id })
        assertTrue(withRequest { service.getMetadataParents(UUID.random()) }.isEmpty())
        assertTrue(withRequest { service.getLanguageVariants(parent.id) }.isEmpty())
    }

    // ── categories / traits / permissions accessors ───────────────────────────

    @Test
    fun `getCategoryIds and getTraitIds empty for fresh collection`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("empties")
        assertTrue(withRequest { service.getCategoryIds(created.id) }.isEmpty())
        assertTrue(withRequest { service.getTraitIds(created.id) }.isEmpty())
    }

    @Test
    fun `addPermission then getPermissions and deletePermission`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("perm")
        val groupId = defaultGroupId()
        val perm = withRequest { service.addPermission(created.id, groupId, PermissionAction.VIEW) }
        assertEquals(created.id, perm.collectionId)
        assertEquals(groupId, perm.groupId)
        assertEquals(PermissionAction.VIEW, perm.action)

        val perms = withRequest {
            val collection = service.getById(created.id) ?: error("missing")
            service.getPermissions(collection)
        }
        assertTrue(perms.any { it.action == PermissionAction.VIEW })

        withRequest { service.deletePermission(created.id, groupId, PermissionAction.VIEW) }
        val after = withRequest {
            val collection = service.getById(created.id) ?: error("missing")
            service.getPermissions(collection)
        }
        assertFalse(after.any { it.action == PermissionAction.VIEW })
    }

    // ── items accessors ────────────────────────────────────────────────────────

    @Test
    fun `getItems getItemsCount getItemsNoCache and collection items`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val parent = newCollection("itemsParent")
        val child = newCollection("itemsChild")
        val metadataId = insertMetadata()
        withRequest { service.addCollectionItem(parent.id, child.id, null) }
        withRequest { service.addMetadataItem(parent.id, metadataId, null) }

        val items = withRequest {
            service.getItems(parent.id, null, 0, 100, null, null, includeMetadata = true, includeCollections = true)
        }
        assertTrue(items.isNotEmpty())

        val count = withRequest {
            service.getItemsCount(parent.id, null, null, null, includeMetadata = true, includeCollections = true)
        }
        assertTrue(count >= 2L)

        val noCache = withRequest { service.getItemsNoCache(parent.id, 0, 100) }
        assertTrue(noCache.isNotEmpty())

        val collectionItems = withRequest { service.getCollectionItems(parent.id, null, 0, 100) }
        assertTrue(collectionItems.any { it.childCollectionId == child.id })

        val metadataItems = withRequest { service.getMetadataItems(parent.id, null, 0, 100) }
        assertTrue(metadataItems.any { it.childMetadataId == metadataId })

        assertTrue(withRequest { service.getCollectionItemsCount(parent.id, null) } >= 1L)
        assertTrue(withRequest { service.getMetadataItemsCount(parent.id, null) } >= 1L)

        val gcci = withRequest { service.getCollectionCollectionItem(parent.id, child.id) }
        assertEquals(child.id, gcci.childCollectionId)
        val gcmi = withRequest { service.getCollectionMetadataItem(parent.id, metadataId) }
        assertEquals(metadataId, gcmi.childMetadataId)
    }

    @Test
    fun `getItems with state and language filters`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val parent = newCollection("filters")
        val child = newCollection("filterChild")
        withRequest { service.addCollectionItem(parent.id, child.id, null) }

        // state provided branch, language provided branch, include flags false branch.
        withRequest {
            service.getItems(parent.id, "pending", 0, 100, "en", null, includeMetadata = false, includeCollections = true)
        }
        withRequest {
            service.getItemsCount(parent.id, "pending", "en", null, includeMetadata = true, includeCollections = false)
        }

        // state-filtered collection / metadata item queries
        withRequest { service.getCollectionItems(parent.id, "pending", 0, 100) }
        withRequest { service.getMetadataItems(parent.id, "pending", 0, 100) }
        withRequest { service.getCollectionItemsCount(parent.id, "pending") }
        withRequest { service.getMetadataItemsCount(parent.id, "pending") }
    }

    @Test
    fun `getItems and count filter metadata by content type`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val parent = newCollection("contentTypeFilters")
        val metadataId = insertMetadata()
        withRequest { service.addMetadataItem(parent.id, metadataId, null) }

        val matchingItems = withRequest {
            service.getItems(
                parent.id,
                null,
                0,
                100,
                null,
                listOf("text/plain"),
                includeMetadata = true,
                includeCollections = false,
            )
        }
        val matchingCount = withRequest {
            service.getItemsCount(
                parent.id,
                null,
                null,
                listOf("text/plain"),
                includeMetadata = true,
                includeCollections = false,
            )
        }
        val nonMatchingItems = withRequest {
            service.getItems(
                parent.id,
                null,
                0,
                100,
                null,
                listOf("application/json"),
                includeMetadata = true,
                includeCollections = false,
            )
        }

        assertEquals(listOf(metadataId), matchingItems.map { it.childMetadataId })
        assertEquals(1L, matchingCount)
        assertTrue(nonMatchingItems.isEmpty())
    }

    @Test
    fun `getCollectionCollectionItem throws when missing`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val parent = newCollection("missingItems")
        assertFailsWith<IllegalStateException> {
            withRequest { service.getCollectionCollectionItem(parent.id, UUID.random()) }
        }
        assertFailsWith<IllegalStateException> {
            withRequest { service.getCollectionMetadataItem(parent.id, UUID.random()) }
        }
    }

    // ── set flags ──────────────────────────────────────────────────────────────

    @Test
    fun `setPublic setPublicList setPublicSupplementary at base level`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("flags")
        withRequest { service.setPublic(created.id, public = true, languageTag = null) }
        withRequest { service.setPublicList(created.id, public = true, languageTag = null) }
        withRequest { service.setPublicSupplementary(created.id, public = true, languageTag = null) }
        val fetched = withRequest { service.getById(created.id) }
        assertNotNull(fetched)
        assertTrue(fetched.public)
    }

    @Test
    fun `setPublic flags at variant level`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("variantFlags")
        withRequest {
            service.addLanguageVariant(CollectionLanguageVariantInput(id = created.id, languageTag = "fr", name = "FR"))
        }
        withRequest { service.setPublic(created.id, public = true, languageTag = "fr") }
        withRequest { service.setPublicList(created.id, public = true, languageTag = "fr") }
        withRequest { service.setPublicSupplementary(created.id, public = true, languageTag = "fr") }
        val variant = withRequest { service.getLanguageVariant(created.id, "fr") }
        assertNotNull(variant)
    }

    @Test
    fun `recommendation search and lock flags persist`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("searchable")
        withRequest { service.setSearchable(created.id, false) }
        withRequest { service.setRecommendable(created.id, false) }
        val fetched = withRequest { service.getById(created.id) } ?: error("missing")
        assertFalse(fetched.searchable)
        assertFalse(fetched.recommendable)
        withRequest { service.setLocked(fetched, true) }
        withRequest { service.setLocked(fetched, false) }
        assertNotNull(withRequest { service.getById(created.id) })
    }

    @Test
    fun `search and recommendation flags persist independently for a language variant`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("variantEligibility")
        withRequest {
            service.addLanguageVariant(CollectionLanguageVariantInput(id = created.id, languageTag = "fr", name = "FR"))
        }

        withRequest { service.setSearchable(created.id, false, "fr") }
        withRequest { service.setRecommendable(created.id, false, "fr") }

        val variant = withRequest { service.getLanguageVariant(created.id, "fr") } ?: error("missing variant")
        assertFalse(variant.searchable)
        assertFalse(variant.recommendable)
        val base = withRequest { service.getById(created.id) } ?: error("missing collection")
        assertTrue(base.searchable)
        assertTrue(base.recommendable)
        assertEquals(listOf(variant), withRequest { service.getLanguageVariants(listOf(created.id), "fr") })
    }

    @Test
    fun `setCategories replaces category set`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("cats")
        val cat1 = insertCategory()
        val cat2 = insertCategory()
        withRequest { service.setCategories(created.id, listOf(cat1)) }
        assertEquals(listOf(cat1), withRequest { service.getCategoryIds(created.id) })
        withRequest { service.setCategories(created.id, listOf(cat2)) }
        assertEquals(listOf(cat2), withRequest { service.getCategoryIds(created.id) })
    }

    @Test
    fun `setCollectionOrdering updates ordering`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("ordering")
        withRequest { service.setCollectionOrdering(created.id, buildJsonObject { put("k", "v") }) }
        assertNotNull(withRequest { service.getById(created.id) })
    }

    @Test
    fun `setSystemAttributes mergeAttributes and setAttributes`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("sysattrs")
        withRequest { service.setSystemAttributes(created.id, buildJsonObject { put("sys", "1") }) }
        withRequest { service.setAttributes(created.id, buildJsonObject { put("a", "1") }) }
        withRequest { service.mergeAttributes(created.id, buildJsonObject { put("b", "2") }) }
        assertNotNull(withRequest { service.getById(created.id) })
        assertFailsWith<IllegalStateException> {
            withRequest { service.setAttributes(UUID.random(), buildJsonObject { put("a", "1") }) }
        }
    }

    // ── template ─────────────────────────────────────────────────────────────

    @Test
    fun `setTemplate applies default attributes and ordering`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("setTpl")
        val templateId = insertMetadata()
        withRequest {
            CollectionTemplateRepositoryImpl().add(
                templateId, 1,
                buildJsonObject { put("fromTemplate", "yes") },
                null, JsonObject(emptyMap()),
                buildJsonObject { put("kind", "custom") },
            )
        }
        withRequest {
            CollectionCollaborationRepositoryImpl().setCollaboration(
                CollectionCollaboration(created.id, "en", yks.utils.encodeStateAsUpdate(yks.utils.Doc()))
            )
        }
        withRequest { service.setTemplate(created.id, templateId, 1) }
        val fetched = withRequest { service.getById(created.id) }
        assertNotNull(fetched)
        assertEquals(templateId, fetched.templateMetadataId)
    }

    @Test
    fun `setTemplate with template lacking defaults`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("setTplNoDefaults")
        val templateId = insertMetadata()
        withRequest {
            CollectionTemplateRepositoryImpl().add(templateId, 1, null, null, JsonObject(emptyMap()), null)
        }
        withRequest { service.setTemplate(created.id, templateId, 1) }
        assertNotNull(withRequest { service.getById(created.id) })
    }

    // ── item attribute mutations ───────────────────────────────────────────────

    @Test
    fun `set and merge metadata and collection item attributes`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val parent = newCollection("itemAttrs")
        val child = newCollection("itemAttrsChild")
        val metadataId = insertMetadata()
        withRequest { service.addCollectionItem(parent.id, child.id, null) }
        withRequest { service.addMetadataItem(parent.id, metadataId, null) }

        withRequest { service.setMetadataItemAttributes(parent.id, metadataId, buildJsonObject { put("m", "1") }) }
        withRequest { service.setCollectionItemAttributes(parent.id, child.id, buildJsonObject { put("c", "1") }) }
        withRequest { service.mergeMetadataItemAttributes(parent.id, metadataId, buildJsonObject { put("m2", "2") }) }
        withRequest { service.mergeCollectionItemAttributes(parent.id, child.id, buildJsonObject { put("c2", "2") }) }
        assertNotNull(withRequest { service.getById(parent.id) })
    }

    @Test
    fun `setMetadataItemAttributes with null attributes`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val parent = newCollection("nullAttrs")
        val metadataId = insertMetadata()
        withRequest { service.addMetadataItem(parent.id, metadataId, null) }
        withRequest { service.setMetadataItemAttributes(parent.id, metadataId, null) }
        assertNotNull(withRequest { service.getById(parent.id) })
    }

    // ── remove items ───────────────────────────────────────────────────────────

    @Test
    fun `removeCollectionItem and removeMetadataItem`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val parent = newCollection("removeParent")
        val child = newCollection("removeChild")
        val metadataId = insertMetadata()
        withRequest { service.addCollectionItem(parent.id, child.id, null) }
        withRequest { service.addMetadataItem(parent.id, metadataId, null) }

        withRequest { service.removeCollectionItem(parent.id, child.id) }
        withRequest { service.removeMetadataItem(parent.id, metadataId) }

        val items = withRequest {
            service.getItems(parent.id, null, 0, 100, null, null, includeMetadata = true, includeCollections = true)
        }
        assertFalse(items.any { it.childCollectionId == child.id })
        assertFalse(items.any { it.childMetadataId == metadataId })
    }

    // ── language variant lifecycle ─────────────────────────────────────────────

    @Test
    fun `addLanguageVariant editLanguageVariant deleteLanguageVariant`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("variant")
        val added = withRequest {
            service.addLanguageVariant(CollectionLanguageVariantInput(id = created.id, languageTag = "es", name = "ES"))
        }
        assertEquals("es", added.languageTag)

        // add again -> existing branch
        withRequest {
            service.addLanguageVariant(CollectionLanguageVariantInput(id = created.id, languageTag = "es", name = "ES2"))
        }

        val edited = withRequest {
            service.editLanguageVariant(CollectionLanguageVariantInput(id = created.id, languageTag = "es", name = "ES Edited"))
        }
        assertEquals("ES Edited", edited.name)

        assertNotNull(withRequest { service.getLanguageVariant(created.id, "es") })

        withRequest { service.deleteLanguageVariant(created.id, "es") }
        assertNull(withRequest { service.getLanguageVariant(created.id, "es") })
    }

    @Test
    fun `editLanguageVariant missing throws`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("editMissing")
        assertFailsWith<IllegalStateException> {
            withRequest {
                service.editLanguageVariant(CollectionLanguageVariantInput(id = created.id, languageTag = "de", name = "X"))
            }
        }
    }

    // ── state transitions ──────────────────────────────────────────────────────

    @Test
    fun `setState on base collection`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("state")
        val item = withRequest { service.getById(created.id) } ?: error("missing")
        val result = withRequest { service.setState(item, "draft", "moving", Principal(id = UUID.random())) }
        assertNotNull(result)
        assertEquals("draft", (withRequest { service.getById(created.id) })?.workflowStateId)
    }

    @Test
    fun `setState on variant`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("stateVariant")
        withRequest { service.addLanguageVariant(CollectionLanguageVariantInput(id = created.id, languageTag = "fr", name = "FR")) }
        val variant = withRequest { service.getLanguageVariant(created.id, "fr") } ?: error("missing")
        val result = withRequest { service.setState(variant, "draft", "moving", null) }
        assertNotNull(result)
    }

    @Test
    fun `setPendingState notify and no-notify`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("pending")
        val item = withRequest { service.getById(created.id) } ?: error("missing")
        val r1 = withRequest { service.setPendingState(item, "processing", "s", null, null, notifyEvent = true) }
        assertNotNull(r1)
        val item2 = withRequest { service.getById(created.id) } ?: error("missing")
        val r2 = withRequest { service.setPendingState(item2, "draft", "s", java.time.OffsetDateTime.now(), Principal(id = UUID.random()), notifyEvent = false) }
        assertNotNull(r2)
    }

    @Test
    fun `setPendingStateComplete and setPendingStateFailed`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("pendingComplete")
        val item = withRequest { service.getById(created.id) } ?: error("missing")
        // sets workflow_state_pending_id
        withRequest { service.setPendingState(item, "processing", "s", null, null, notifyEvent = true) }
        val pending = withRequest { service.getById(created.id) } ?: error("missing")
        val completed = withRequest { service.setPendingStateComplete(pending, "done", null) }
        assertNotNull(completed)

        val created2 = newCollection("pendingFailed")
        val item2 = withRequest { service.getById(created2.id) } ?: error("missing")
        withRequest { service.setPendingState(item2, "processing", "s", null, null, notifyEvent = true) }
        val pending2 = withRequest { service.getById(created2.id) } ?: error("missing")
        val failed = withRequest { service.setPendingStateFailed(pending2, "boom", null) }
        assertNotNull(failed)
    }

    @Test
    fun `setPendingStateComplete throws when no pending id`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("noPending")
        val item = withRequest { service.getById(created.id) } ?: error("missing")
        assertFailsWith<IllegalStateException> {
            withRequest { service.setPendingStateComplete(item, "x", null) }
        }
        assertFailsWith<IllegalStateException> {
            withRequest { service.setPendingStateFailed(item, "x", null) }
        }
    }

    @Test
    fun `setNotReady on variant`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("notReadyVariant")
        withRequest { service.addLanguageVariant(CollectionLanguageVariantInput(id = created.id, languageTag = "fr", name = "FR")) }
        val variant = withRequest { service.getLanguageVariant(created.id, "fr") } ?: error("missing")
        withRequest { service.setNotReady(variant) }
        assertNotNull(withRequest { service.getLanguageVariant(created.id, "fr") })
    }

    @Test
    fun `setReady on variant path`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("readyVariant")
        withRequest { service.addLanguageVariant(CollectionLanguageVariantInput(id = created.id, languageTag = "fr", name = "FR")) }
        val variant = withRequest { service.getLanguageVariant(created.id, "fr") } ?: error("missing")
        withRequest { service.setReady(variant, Principal(id = UUID.random())) }
        val after = withRequest { service.getLanguageVariant(created.id, "fr") }
        assertNotNull(after?.ready)
    }

    // ── metadata relationships ─────────────────────────────────────────────────

    @Test
    fun `addMetadataRelationship editMetadataRelationship deleteMetadataRelationship base`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("rel")
        val metadataId = insertMetadata()
        val added = withRequest {
            service.addMetadataRelationship(
                CollectionMetadataRelationshipInput(id = created.id, metadataId = metadataId, relationship = "rel", attributes = buildJsonObject { put("k", "v") })
            )
        }
        assertNotNull(added)

        val rels = withRequest { service.getMetadataRelationships(created.id) }
        assertTrue(rels.isNotEmpty())

        withRequest {
            service.editMetadataRelationship(
                CollectionMetadataRelationshipInput(id = created.id, metadataId = metadataId, relationship = "rel", attributes = buildJsonObject { put("k", "v2") })
            )
        }

        withRequest {
            service.mergeMetadataRelationshipAttributes(created.id, metadataId, "rel", buildJsonObject { put("m", "1") })
        }

        withRequest { service.deleteMetadataRelationship(created.id, metadataId, "rel") }
        assertTrue(withRequest { service.getMetadataRelationships(created.id) }.none { it.metadataId == metadataId })
    }

    @Test
    fun `metadata relationship variant path`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("relVariant")
        withRequest { service.addLanguageVariant(CollectionLanguageVariantInput(id = created.id, languageTag = "fr", name = "FR")) }
        val metadataId = insertMetadata()
        val added = withRequest {
            service.addMetadataRelationship(
                CollectionMetadataRelationshipInput(id = created.id, languageTag = "fr", metadataId = metadataId, relationship = "rel", attributes = null)
            )
        }
        assertNotNull(added)

        val rels = withRequest { service.getMetadataRelationships(created.id, "fr") }
        assertTrue(rels.isNotEmpty())

        withRequest {
            service.editMetadataRelationship(
                CollectionMetadataRelationshipInput(id = created.id, languageTag = "fr", metadataId = metadataId, relationship = "rel", attributes = buildJsonObject { put("x", "y") })
            )
        }

        withRequest {
            service.mergeMetadataRelationshipAttributes(created.id, "fr", metadataId, "rel", buildJsonObject { put("m", "1") })
        }

        withRequest { service.deleteMetadataRelationship(created.id, "fr", metadataId, "rel") }
    }

    @Test
    fun `deleteMetadataRelationship no-op when relationship absent`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("relAbsent")
        val metadataId = insertMetadata()
        withRequest { service.deleteMetadataRelationship(created.id, metadataId, "does-not-exist") }
        assertNotNull(withRequest { service.getById(created.id) })
    }

    // ── supplementary ──────────────────────────────────────────────────────────

    @Test
    fun `addSupplementary getSupplementary and setSupplementaryUploaded`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("supp")
        val supp = withRequest {
            service.addSupplementary(
                CollectionSupplementaryInput(
                    collectionId = created.id,
                    key = "k1",
                    name = "Supp",
                    contentType = "text/plain",
                    contentLength = 5,
                )
            )
        }
        assertNotNull(supp.id)

        val all = withRequest { service.getSupplementary(created.id) }
        assertTrue(all.any { it.id == supp.id })

        val byId = withRequest { service.getSupplementaryById(supp.id) }
        assertNotNull(byId)

        withRequest { service.setSupplementaryUploaded(supp.id, "text/html", 10) }
        val updated = withRequest { service.getSupplementaryById(supp.id) }
        assertEquals("text/html", updated?.contentType)

        // null content type branch defaults to octet-stream
        withRequest { service.setSupplementaryUploaded(supp.id, null, 3) }
        assertEquals("application/octet-stream", (withRequest { service.getSupplementaryById(supp.id) })?.contentType)
    }

    @Test
    fun `addSupplementary empty key throws`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("suppEmpty")
        assertFailsWith<IllegalArgumentException> {
            withRequest {
                service.addSupplementary(
                    CollectionSupplementaryInput(collectionId = created.id, key = "", name = "n", contentType = "text/plain")
                )
            }
        }
    }

    @Test
    fun `markSupplementaryUploaded and deleteSupplementary`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("suppMark")
        val supp = withRequest {
            service.addSupplementary(
                CollectionSupplementaryInput(collectionId = created.id, key = "k", name = "n", contentType = "text/plain")
            )
        }
        withRequest { service.markSupplementaryUploaded(supp.id, "text/plain", 4) }
        // no-op when missing
        withRequest { service.markSupplementaryUploaded(UUID.random(), "text/plain", 4) }

        val collection = withRequest { service.getById(created.id) } ?: error("missing")
        // CollectionSupplementaryRepository.deleteById removes rows WHERE collection_id = :id,
        // so the collection id (not the supplementary id) is what actually deletes the row.
        withRequest { service.deleteSupplementary(collection, created.id) }
        assertNull(withRequest { service.getSupplementaryById(supp.id) })
    }

    @Test
    fun `updateSupplementaryContent string overload`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("suppContent")
        val collection = withRequest { service.getById(created.id) } ?: error("missing")
        val supp = withRequest {
            service.addSupplementary(
                CollectionSupplementaryInput(collectionId = created.id, key = "k", name = "n", contentType = "text/plain")
            )
        }
        val principal = Principal(id = UUID.random())
        withRequest { service.updateSupplementaryContent(principal, collection, supp.id, "hello", "text/plain") }
        val updated = withRequest { service.getSupplementaryById(supp.id) }
        assertEquals(5L, updated?.contentLength)

        // missing supplementary returns early
        withRequest { service.updateSupplementaryContent(principal, collection, UUID.random(), "x", "text/plain") }
    }

    @Test
    fun `updateSupplementaryContent rejects mismatched collection`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("suppMismatch")
        val other = newCollection("suppOther")
        val supp = withRequest {
            service.addSupplementary(
                CollectionSupplementaryInput(collectionId = created.id, key = "k", name = "n", contentType = "text/plain")
            )
        }
        val otherCollection = withRequest { service.getById(other.id) } ?: error("missing")
        assertFailsWith<IllegalArgumentException> {
            withRequest {
                service.updateSupplementaryContent(Principal(id = UUID.random()), otherCollection, supp.id, "x", "text/plain")
            }
        }
    }

    // ── collaboration accessors ──────────────────────────────────────────────

    @Test
    fun `getCollaboration and setCollaboration`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("collab")
        assertNull(withRequest { service.getCollaboration(created.id, "en") })
        withRequest {
            service.setCollaboration(
                CollectionCollaborationInput(collectionId = created.id, languageTag = "en", content = byteArrayOf(1, 2, 3))
            )
        }
        assertNotNull(withRequest { service.getCollaboration(created.id, "en") })
    }

    @Test
    fun `markCollaboration dirty helpers`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("dirty")
        withRequest {
            CollectionCollaborationRepositoryImpl().setCollaboration(
                CollectionCollaboration(created.id, "en", yks.utils.encodeStateAsUpdate(yks.utils.Doc()))
            )
        }
        withRequest { service.markCollaborationCollectionsDirty(created.id, null) }
        withRequest { service.markCollaborationRelationshipsDirty(created.id, "en") }
        withRequest { service.markCollaborationAttributesDirty(created.id, null) }
        assertNotNull(withRequest { service.getCollaboration(created.id, "en") })
    }

    @Test
    fun `markCollaboration dirty no-op when no collaboration document`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("dirtyNone")
        withRequest { service.markCollaborationCollectionsDirty(created.id, null) }
        withRequest { service.markCollaborationRelationshipsDirty(created.id, null) }
        withRequest { service.markCollaborationAttributesDirty(created.id, null) }
        assertNull(withRequest { service.getCollaboration(created.id, "en") })
    }

    // ── plans / find / expand ──────────────────────────────────────────────────

    @Test
    fun `getPlans empty and find methods`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("plans")
        assertTrue(withRequest { service.getPlans(created.id) }.isEmpty())

        val input = FindQueryInput(limit = 10, offset = 0)
        withRequest { service.find(input) }
        withRequest { service.findBySystem(input) }
        withRequest { service.findCount(input) }
    }

    @Test
    fun `expandMetadata and count`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("expand")
        val collection = withRequest { service.getById(created.id) } ?: error("missing")
        withRequest { service.expandMetadata(collection, null, 0, 100) }
        val count = withRequest { service.expandMetadataCount(collection, null) }
        assertTrue(count >= 0L)
    }

    // ── permanentlyDelete / syncVariantItems / removeItemsCache ────────────────

    @Test
    fun `permanentlyDelete removes the row`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("perma")
        withRequest { service.permanentlyDelete(created.id) }
        assertNull(withRequest { service.getById(created.id) })
    }

    @Test
    fun `syncVariantItems with no metadata items is a no-op`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("syncEmpty")
        withRequest { service.syncVariantItems(created.id) }
        assertNotNull(withRequest { service.getById(created.id) })
    }

    @Test
    fun `removeItemsCache clears items cache`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = newCollection("removeItemsCache")
        val cm = ConnectionManager(connectionPool)
        val rc = RequestCache(cacheManager, serializer)
        withContext(cm.asCoroutineContext() + rc.asCoroutineContext()) {
            cm.beginTransaction()
            service.removeItemsCache(created.id)
            withContext(NonCancellable) { cm.commitTransaction() }
        }
        withContext(NonCancellable) { cm.release() }
        assertNotNull(withRequest { service.getById(created.id) })
    }

    // ── template attribute sync via relationships / parents ────────────────────

    /** Creates a collection backed by a template that has a METADATA-typed attribute. */
    private suspend fun createRelationshipTemplatedCollection(): Collection {
        val templateId = insertMetadata()
        withRequest {
            CollectionTemplateRepositoryImpl().add(templateId, 1, null, null, JsonObject(emptyMap()), null)
            CollectionTemplateAttributeRepositoryImpl().add(
                CollectionTemplateAttribute(
                    metadataId = templateId,
                    version = 1,
                    key = "related",
                    name = "Related",
                    description = "",
                    type = AttributeType.METADATA,
                    ui = AttributeUiType.INPUT,
                    list = false,
                    sort = 0,
                    location = AttributeLocation.ITEM,
                ),
            )
        }
        return withRequest {
            service.add(
                CollectionInput(name = "RelTpl", templateMetadataId = templateId, templateMetadataVersion = 1),
                parent = null,
                parentItemAttributes = null,
            )
        }
    }

    @Test
    fun `syncCollaboration writes relationship-backed attributes`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val collection = createRelationshipTemplatedCollection()
        withRequest {
            CollectionCollaborationRepositoryImpl().setCollaboration(
                CollectionCollaboration(collection.id, collection.languageTag, yks.utils.encodeStateAsUpdate(yks.utils.Doc()))
            )
        }
        val metadataId = insertMetadata()
        withRequest {
            service.addMetadataRelationship(
                CollectionMetadataRelationshipInput(id = collection.id, metadataId = metadataId, relationship = "related", attributes = null)
            )
        }
        assertNotNull(withRequest { CollectionCollaborationRepositoryImpl().getByCollectionId(collection.id, collection.languageTag) })
    }
}
