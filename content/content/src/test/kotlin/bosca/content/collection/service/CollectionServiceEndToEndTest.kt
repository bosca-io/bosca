package bosca.content.collection.service

import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.asCoroutineContext
import bosca.cache.nats.NatsCacheManager
import bosca.attributes.AttributeLocation
import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionCollaboration
import bosca.content.collection.model.CollectionInput
import bosca.content.collection.model.CollectionItem
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
import bosca.content.metadata.repository.CollectionTemplateRepositoryImpl
import bosca.content.metadata.repository.CollectionTemplateAttributeRepositoryImpl
import bosca.content.metadata.repository.MetadataRepositoryImpl
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.transition.history.service.TransitionHistoryService
import bosca.content.transition.service.Transitioner
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.migrations.CoreMigration
import bosca.db.migrations.FlywayMigration
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.events.DisabledEventManagerFilter
import bosca.events.EventManager
import bosca.events.asCoroutineContext
import bosca.nats.NatsConnectionPool
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUIDSerializer
import bosca.slug.model.Slug
import bosca.slug.service.SlugService
import bosca.storage.service.ObjectStorageService
import bosca.security.model.Principal
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import yks.utils.Doc
import yks.utils.applyUpdate
import yks.utils.encodeStateAsUpdate
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import bosca.test.resources.SharedNatsContainer
import bosca.test.resources.SharedPostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.lifecycle.Startables
import kotlin.uuid.toJavaUuid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(InternalDI::class)
class CollectionServiceEndToEndTest {

    private lateinit var natsContainer: SharedNatsContainer
    private lateinit var postgresContainer: SharedPostgreSQLContainer
    private lateinit var connectionPool: ConnectionPool
    private lateinit var cacheManager: CacheManager
    private lateinit var serializer: RequestCacheSerializer

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

    @BeforeTest
    fun setup() = runBlocking {
        unmockkAll()

        natsContainer = SharedNatsContainer()
            .withExposedPorts(4222)
            .withCommand("-js")
            .withReuse(true)
            .waitingFor(Wait.forListeningPort())

        postgresContainer = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withUsername("test")
            withPassword("test")
            withDatabaseName("test")
            withReuse(true)
        }

        Startables.deepStart(natsContainer, postgresContainer).join()

        val factory = ConnectionFactoryImpl(
            ConnectionConfig(
                url = postgresContainer.jdbcUrl,
                user = postgresContainer.username,
                password = postgresContainer.password,
            ),
            key = "test"
        )
        connectionPool = ConnectionPool(factory)

        FlywayMigration(connectionPool).migrate(listOf(CoreMigration()))

        val natsUrl = "nats://${natsContainer.host}:${natsContainer.getMappedPort(4222)}"
        val natsPool = natsContainer.newConnectionPool(1)
        cacheManager = NatsCacheManager(natsPool)
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
            mockk<ObjectStorageService>(relaxed = true),
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
    fun teardown() = runBlocking {
        if (::connectionPool.isInitialized) connectionPool.close()
        if (::natsContainer.isInitialized) natsContainer.stop()
        if (::postgresContainer.isInitialized) postgresContainer.stop()
        ProviderRegistry.clear()
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

    @Test
    fun `add creates collection and getById retrieves it`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = withRequest {
            service.add(
                CollectionInput(name = "Test Collection"),
                parent = null,
                parentItemAttributes = null
            )
        }

        assertNotNull(created.id)
        assertEquals("Test Collection", created.name)
        assertEquals("pending", created.workflowStateId)

        val fetched = withRequest { service.getById(created.id) }
        assertNotNull(fetched)
        assertEquals(created.id, fetched.id)
        assertEquals("Test Collection", fetched.name)
    }

    @Test
    fun `edit updates collection and cache is invalidated`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = withRequest {
            service.add(
                CollectionInput(name = "Original Name"),
                parent = null,
                parentItemAttributes = null
            )
        }

        val edited = withRequest {
            service.edit(
                created.id,
                CollectionInput(name = "Updated Name", description = "new description", attributes = JsonObject(emptyMap()))
            )
        }

        assertEquals("Updated Name", edited.name)
        assertEquals("new description", edited.description)

        // New request — verifies cache was properly invalidated by edit
        val fetched = withRequest { service.getById(created.id) }
        assertNotNull(fetched)
        assertEquals("Updated Name", fetched.name)
        assertEquals("new description", fetched.description)
    }

    @Test
    fun `markDeleted soft-deletes and getAll excludes it`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = withRequest {
            service.add(
                CollectionInput(name = "To Be Deleted"),
                parent = null,
                parentItemAttributes = null
            )
        }

        // Verify it appears in getAll
        val allBefore = withRequest { service.getAll(0, 1000) }
        assertTrue(allBefore.any { it.id == created.id }, "Collection should appear in getAll before deletion")

        // Soft delete
        withRequest { service.markDeleted(created.id) }

        // getAll should exclude it (query filters deleted = false)
        val allAfter = withRequest { service.getAll(0, 1000) }
        assertFalse(allAfter.any { it.id == created.id }, "Collection should not appear in getAll after deletion")

        // getById still returns it (soft delete, no filter)
        val fetched = withRequest { service.getById(created.id) }
        assertNotNull(fetched, "getById should still return soft-deleted collection")
        assertTrue(fetched.deleted)
    }

    @Test
    fun `add with parent creates parent-child relationship`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val parent = withRequest {
            service.add(
                CollectionInput(name = "Parent Collection"),
                parent = null,
                parentItemAttributes = null
            )
        }

        val child = withRequest {
            service.add(
                CollectionInput(name = "Child Collection"),
                parent = parent,
                parentItemAttributes = JsonObject(emptyMap())
            )
        }

        assertNotNull(child.id)
        assertEquals("Child Collection", child.name)

        // Verify child appears in parent's items
        val items = withRequest {
            service.getItems(
                parent.id,
                state = null,
                offset = 0,
                limit = 100,
                languageTag = null,
                contentTypes = null,
                includeMetadata = false,
                includeCollections = true
            )
        }
        assertTrue(items.any { it.childCollectionId == child.id }, "Child should appear in parent's items")
    }

    private suspend fun rawUpdate(sql: String, id: UUID) {
        val cm = ConnectionManager(connectionPool)
        withContext(cm.asCoroutineContext()) {
            cm.beginTransaction()
            cm.useStatement(sql) { stmt ->
                stmt.setObject(1, id.toJavaUuid())
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

    private suspend fun rawExecute(sql: String) {
        val cm = ConnectionManager(connectionPool)
        withContext(cm.asCoroutineContext()) {
            cm.beginTransaction()
            cm.useStatement(sql) { statement -> statement.execute() }
            withContext(NonCancellable) {
                cm.commitTransaction()
            }
        }
        withContext(NonCancellable) {
            cm.release()
        }
    }

    @Test
    fun `Bible language resolution maps Bosca locales to ISO 639-3 metadata tags`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val collection = withRequest {
            service.add(
                CollectionInput(name = "Bibles"),
                parent = null,
                parentItemAttributes = null,
            )
        }
        val englishIso3 = withRequest {
            MetadataRepositoryImpl().add(
                Metadata(
                    name = "ISO 639-3 Bible",
                    type = MetadataType.STANDARD,
                    contentType = "bosca/v-bible",
                    contentLength = 1,
                    languageTag = "eng",
                    attributes = JsonObject(emptyMap()),
                    workflowStateId = "published",
                ),
            )
        }
        val englishCanonical = withRequest {
            MetadataRepositoryImpl().add(
                Metadata(
                    name = "Legacy Canonical Bible",
                    type = MetadataType.STANDARD,
                    contentType = "bosca/v-bible",
                    contentLength = 1,
                    languageTag = "en",
                    attributes = JsonObject(emptyMap()),
                    workflowStateId = "published",
                ),
            )
        }
        withRequest {
            CollectionItemRepositoryImpl().add(CollectionItem(collectionId = collection.id, childMetadataId = englishIso3.id))
            CollectionItemRepositoryImpl().add(CollectionItem(collectionId = collection.id, childMetadataId = englishCanonical.id))
        }
        rawExecute("insert into languages (tag, name, localName) values ('en', 'English', 'English') on conflict do nothing")
        rawExecute(
            "insert into language_resolution_contexts (key, name, fallback_language_tag, protected) values ('bibles', 'Bibles', 'eng', true) on conflict (key) do update set fallback_language_tag = excluded.fallback_language_tag",
        )
        rawExecute(
            """insert into language_tag_mappings (context_id, source_language_tag, resolved_language_tag)
                select lrc.id, mapping.source_language_tag, mapping.resolved_language_tag
                from language_resolution_contexts lrc
                cross join (values ('en', 'eng')) mapping(source_language_tag, resolved_language_tag)
                where lrc.key = 'bibles'
                on conflict (context_id, source_language_tag) do update set resolved_language_tag = excluded.resolved_language_tag""".trimIndent(),
        )

        val exactItems = withRequest {
            service.getItems(
                collection.id,
                state = null,
                offset = 0,
                limit = 10,
                languageTag = "en",
                contentTypes = listOf("bosca/v-bible"),
                includeMetadata = true,
                includeCollections = false,
                languageResolutionContext = null,
            )
        }
        val resolvedItems = withRequest {
            service.getItems(
                collection.id,
                state = null,
                offset = 0,
                limit = 10,
                languageTag = "en",
                contentTypes = listOf("bosca/v-bible"),
                includeMetadata = true,
                includeCollections = false,
                languageResolutionContext = "bibles",
            )
        }
        val resolvedCount = withRequest {
            service.getItemsCount(
                collection.id,
                state = null,
                languageTag = "en",
                contentTypes = listOf("bosca/v-bible"),
                includeMetadata = true,
                includeCollections = false,
                languageResolutionContext = "bibles",
            )
        }
        val regionalItems = withRequest {
            service.getItems(
                collection.id,
                state = null,
                offset = 0,
                limit = 10,
                languageTag = "en-US",
                contentTypes = listOf("bosca/v-bible"),
                includeMetadata = true,
                includeCollections = false,
                languageResolutionContext = "bibles",
            )
        }

        assertEquals(listOf(englishCanonical.id), exactItems.mapNotNull { it.childMetadataId })
        assertEquals(listOf(englishIso3.id), resolvedItems.mapNotNull { it.childMetadataId })
        assertEquals(1L, resolvedCount)
        assertEquals(listOf(englishIso3.id), regionalItems.mapNotNull { it.childMetadataId })
    }

    @Test
    fun `setReady sets ready timestamp and setNotReady clears it`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = withRequest {
            service.add(
                CollectionInput(name = "Ready Test"),
                parent = null,
                parentItemAttributes = null
            )
        }

        // New collections start with workflowStateId = "pending", which triggers transitioner.
        // Set to "draft" to test the ready/notReady cycle without transitioner side effects.
        rawUpdate("UPDATE collections SET workflow_state_id = 'draft' WHERE id = ?", created.id)

        val principal = Principal(id = UUID.random())

        // Verify initially not ready
        val beforeReady = withRequest { service.getById(created.id) }
        assertNotNull(beforeReady)
        assertNull(beforeReady.ready, "Collection should not be ready initially")

        // Set ready
        withRequest { service.setReady(created.id, principal, null) }

        // Verify ready timestamp is set
        val afterReady = withRequest { service.getById(created.id) }
        assertNotNull(afterReady)
        assertNotNull(afterReady.ready, "Collection should have ready timestamp after setReady")

        // Set not ready
        withRequest { service.setNotReady(afterReady) }

        // Verify ready is cleared
        val afterNotReady = withRequest { service.getById(created.id) }
        assertNotNull(afterNotReady)
        assertNull(afterNotReady.ready, "Collection should not be ready after setNotReady")
    }

    @Test
    fun `setReady with pending state triggers transitioner`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = withRequest {
            service.add(
                CollectionInput(name = "Pending Ready Test"),
                parent = null,
                parentItemAttributes = null
            )
        }

        // Collection starts in "pending" state — setReady should trigger transitioner
        assertEquals("pending", created.workflowStateId)

        val principal = Principal(id = UUID.random())

        withRequest { service.setReady(created.id, principal, null) }

        val afterReady = withRequest { service.getById(created.id) }
        assertNotNull(afterReady)
        assertNotNull(afterReady.ready, "Collection should have ready timestamp")

        // Verify transitioner was invoked for the pending → processing transition
        coVerify { transitioner.beginTransition(any(), any(), any()) }
    }

    // ── Collaboration sync ───────────────────────────────────────────────────

    /** Creates a collection backed by a template that has one STRING attribute, "title". */
    private suspend fun createTemplatedCollection(): Collection {
        val templateId = UUID.random()
        // collection_templates.metadata_id FKs to metadata(id), so a template metadata row is required.
        rawUpdate(
            "insert into metadata (id, name, content_type) values (?, 'QA Template', 'bosca/v-collection-template')",
            templateId,
        )
        withRequest {
            CollectionTemplateRepositoryImpl().add(templateId, 1, null, null, JsonObject(emptyMap()), null)
            CollectionTemplateAttributeRepositoryImpl().add(
                CollectionTemplateAttribute(
                    metadataId = templateId,
                    version = 1,
                    key = "title",
                    name = "Title",
                    description = "",
                    type = AttributeType.STRING,
                    ui = AttributeUiType.INPUT,
                    list = false,
                    sort = 0,
                    location = AttributeLocation.ITEM,
                ),
            )
        }
        return withRequest {
            service.add(
                CollectionInput(name = "Synced", templateMetadataId = templateId, templateMetadataVersion = 1),
                parent = null,
                parentItemAttributes = null,
            )
        }
    }

    private fun textAttribute(content: ByteArray, key: String): Any? {
        val doc = Doc()
        applyUpdate(doc, content)
        return doc.getMap("textAttributes").get(key)
    }

    private suspend fun seedCollaboration(collection: Collection) = withRequest {
        CollectionCollaborationRepositoryImpl().setCollaboration(
            CollectionCollaboration(collection.id, collection.languageTag, encodeStateAsUpdate(Doc())),
        )
    }

    @Test
    fun `edit writes the attribute value into the collaboration document`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val collection = createTemplatedCollection()
        seedCollaboration(collection)

        withRequest {
            service.edit(collection.id, CollectionInput(name = "Synced", attributes = buildJsonObject { put("title", "Hello Sync") }))
        }

        val collab = withRequest { CollectionCollaborationRepositoryImpl().getByCollectionId(collection.id, collection.languageTag) }
        assertNotNull(collab, "collaboration document should exist")
        assertEquals("Hello Sync", textAttribute(collab.content, "title"))
    }

    @Test
    fun `setAttributes writes the attribute value into the collaboration document`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val collection = createTemplatedCollection()
        seedCollaboration(collection)

        withRequest { service.setAttributes(collection.id, buildJsonObject { put("title", "Via setAttributes") }) }

        val collab = withRequest { CollectionCollaborationRepositoryImpl().getByCollectionId(collection.id, collection.languageTag) }
        assertNotNull(collab)
        assertEquals("Via setAttributes", textAttribute(collab.content, "title"))
    }

    @Test
    fun `editing a removed attribute clears it from the collaboration document`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val collection = createTemplatedCollection()
        seedCollaboration(collection)

        withRequest { service.edit(collection.id, CollectionInput(name = "Synced", attributes = buildJsonObject { put("title", "present") })) }
        assertEquals("present", textAttribute(withRequest { CollectionCollaborationRepositoryImpl().getByCollectionId(collection.id, collection.languageTag) }!!.content, "title"))

        withRequest { service.edit(collection.id, CollectionInput(name = "Synced", attributes = JsonObject(emptyMap()))) }
        val doc = Doc()
        applyUpdate(doc, withRequest { CollectionCollaborationRepositoryImpl().getByCollectionId(collection.id, collection.languageTag) }!!.content)
        assertFalse(doc.getMap("textAttributes").has("title"), "removed attribute should be cleared from the collaboration document")
    }

    @Test
    fun `edit without an existing collaboration document does not create one`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val collection = createTemplatedCollection()
        // No collaboration document seeded — the client seeds one on first open.

        withRequest { service.edit(collection.id, CollectionInput(name = "Synced", attributes = buildJsonObject { put("title", "x") })) }

        val collab = withRequest { CollectionCollaborationRepositoryImpl().getByCollectionId(collection.id, collection.languageTag) }
        assertNull(collab, "sync must be a no-op when no collaboration document exists yet")
    }
}
