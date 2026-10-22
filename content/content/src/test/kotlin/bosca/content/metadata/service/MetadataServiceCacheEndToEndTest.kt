package bosca.content.metadata.service

import bosca.cache.CacheManager
import bosca.content.model.ContentStorageReferenceKind
import bosca.content.metadata.model.MetadataSupplementary
import bosca.db.connection
import kotlin.test.assertFailsWith
import java.time.OffsetDateTime
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.asCoroutineContext
import bosca.cache.nats.NatsCacheManager
import bosca.category.service.CategoryService
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.events.MetadataSetReady
import bosca.content.metadata.events.dispatch
import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.content.attributes.model.TemplateAttribute
import bosca.content.metadata.model.Data
import bosca.content.metadata.model.DataCollaboration
import bosca.content.metadata.model.DataCollaborationInput
import bosca.content.metadata.model.DataTemplateAttribute
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.model.MetadataType
import bosca.events.DisabledEventManagerFilter
import bosca.events.EventManager
import bosca.events.asCoroutineContext
import bosca.content.metadata.repository.MetadataCategoryRepositoryImpl
import bosca.content.metadata.repository.MetadataPermissionRepositoryImpl
import bosca.content.metadata.repository.MetadataProfileRepositoryImpl
import bosca.content.metadata.repository.MetadataRelationshipRepositoryImpl
import bosca.content.metadata.repository.MetadataRepositoryImpl
import bosca.content.metadata.repository.MetadataSupplementaryRepositoryImpl
import bosca.content.metadata.repository.MetadataTraitRepositoryImpl
import bosca.content.metadata.repository.MetadataWorkflowPlanRepositoryImpl
import bosca.content.video.service.VideoService
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
import bosca.nats.NatsConnectionPool
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.slug.service.SlugService
import bosca.storage.service.ObjectStorageService
import bosca.trait.service.TraitService
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.unmockkAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.toJavaUuid

@OptIn(InternalDI::class)
class MetadataServiceCacheEndToEndTest {

    private lateinit var natsContainer: SharedNatsContainer
    private lateinit var postgresContainer: SharedPostgreSQLContainer
    private lateinit var connectionPool: ConnectionPool

    private val testJson = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(OffsetDateTimeSerializer())
            contextual(UUIDSerializer())
        }
    }

    private lateinit var cacheManager: CacheManager
    private lateinit var serializer: RequestCacheSerializer
    private lateinit var repository: MetadataRepositoryImpl
    private lateinit var service: MetadataServiceImpl

    private val collectionService = mockk<CollectionService>(relaxed = true)
    private val collectionTemplateService = mockk<CollectionTemplateService>(relaxed = true)
    private val find = mockk<ObjectProvider<bosca.content.metadata.repository.MetadataFindRepository>>(relaxed = true)
    private val transitionHistory = mockk<TransitionHistoryService>(relaxed = true)
    private val documentService = mockk<DocumentService>(relaxed = true)
    private val documentTemplateService = mockk<DocumentTemplateService>(relaxed = true)
    private val dataTemplateService = mockk<DataTemplateService>(relaxed = true)
    private val guideService = mockk<GuideService>(relaxed = true)
    private val guideTemplateService = mockk<GuideTemplateService>(relaxed = true)
    private val dataService = mockk<DataService>(relaxed = true)
    private val bibleService = mockk<BibleService>(relaxed = true)
    private val objectService = mockk<ObjectStorageService>(relaxed = true)
    private val slugService = mockk<SlugService>(relaxed = true)
    private val traitService = mockk<TraitService>(relaxed = true)
    private val categoriesService = mockk<CategoryService>(relaxed = true)
    private val securityService = mockk<ObjectProvider<SecurityService>>(relaxed = true)
    private val transitioner = mockk<ObjectProvider<Transitioner>>(relaxed = true)

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

        repository = MetadataRepositoryImpl()

        service = MetadataServiceImpl(
            repository,
            collectionService,
            collectionTemplateService,
            find,
            MetadataPermissionRepositoryImpl(),
            MetadataTraitRepositoryImpl(),
            MetadataCategoryRepositoryImpl(),
            MetadataWorkflowPlanRepositoryImpl(),
            MetadataProfileRepositoryImpl(),
            MetadataSupplementaryRepositoryImpl(),
            MetadataRelationshipRepositoryImpl(),
            transitionHistory,
            documentService,
            documentTemplateService,
            dataTemplateService,
            guideService,
            guideTemplateService,
            dataService,
            bibleService,
            objectService,
            slugService,
            traitService,
            categoriesService,
            securityService,
            transitioner,
            mockk<VideoService>(relaxed = true),
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

    @Test
    fun `storage references include current deleted and legacy records without using cached uploads`(): Unit = runBlocking {
        val uploaded = OffsetDateTime.parse("2026-10-20T10:00:00Z")
        val ids = List(2) { UUID.random() }
        ids.forEach { id ->
            rawUpdate("""
                insert into metadata (id, version, name, content_type)
                values (?, 8, 'Storage inventory', 'image/png')
            """.trimIndent(), id)
        }
        val owners = withRequest { ids.map { assertNotNull(repository.getById(it)) } }
        val current = owners[0]
        val deleted = owners[1]
        try {
            val attachment = withRequest {
                MetadataSupplementaryRepositoryImpl().add(
                    MetadataSupplementary(
                        metadataId = current.id,
                        key = "thumbnail",
                        name = "Thumbnail",
                        created = uploaded,
                        modified = uploaded,
                        uploaded = uploaded,
                        contentType = "image/png",
                        contentLength = 12,
                    ),
                ).also { stored ->
                    MetadataSupplementaryRepositoryImpl().add(
                        stored.copy(
                            id = UUID.NIL,
                            planId = UUID.random(),
                            uploaded = null,
                            contentLength = null,
                        ),
                    )
                }
            }
            withRequest {
                repository.markDeleted(deleted.id)
                service.getById(current.id)
                service.getSupplementary(current.id)
            }
            rawUpdate("update metadata set uploaded = '2026-10-20T10:00:00Z', content_length = 42 where id = ?", current.id)
            rawUpdate("update metadata_supplementary set content_length = 24 where metadata_id = ? and uploaded is not null", current.id)
            rawUpdate("""
                insert into metadata_versions (id, version, name, content_type, content_length)
                select id, 7, name, content_type, 17 from metadata where id = ?
            """.trimIndent(), current.id)
            rawUpdate("""
                insert into metadata_versions_supplementary
                    (metadata_id, version, key, name, content_type, content_length, uploaded)
                select id, 7, 'thumbnail', name, content_type, 18, '2026-10-20T10:00:00Z'
                from metadata where id = ?
            """.trimIndent(), current.id)

            withRequest {
                val all = service.getStorageReferences(0, Int.MAX_VALUE)
                val paged = buildList {
                    var offset = 0L
                    while (true) {
                        val page = service.getStorageReferences(offset, 1)
                        if (page.isEmpty()) break
                        addAll(page)
                        offset += page.size
                    }
                }
                assertEquals(all, paged)
                assertEquals(emptyList(), service.getStorageReferences(all.size.toLong(), 1))
                val refs = all.filter { it.contentId in owners.map { owner -> owner.id } }
                assertEquals(6, refs.size)
                val primary = refs.single { it.contentId == current.id && it.kind == ContentStorageReferenceKind.METADATA }
                assertEquals(8, primary.version)
                assertEquals(42L, primary.contentLength)
                assertEquals(uploaded.toInstant(), primary.uploaded?.toInstant())
                assertNull(primary.supplementaryId)
                assertNull(primary.supplementaryKey)
                assertEquals(false, primary.deleted)
                val supplementary = refs.single { it.supplementaryId == attachment.id }
                val pendingAttachment = refs.single {
                    it.kind == ContentStorageReferenceKind.METADATA_SUPPLEMENTARY && it.supplementaryId != attachment.id
                }
                assertEquals("thumbnail", pendingAttachment.supplementaryKey)
                assertNull(pendingAttachment.uploaded)
                assertNull(pendingAttachment.contentLength)
                assertEquals(attachment.id, supplementary.supplementaryId)
                assertEquals("thumbnail", supplementary.supplementaryKey)
                assertEquals(8, supplementary.version)
                assertEquals(24L, supplementary.contentLength)
                assertEquals(uploaded.toInstant(), supplementary.uploaded?.toInstant())
                val history = refs.single { it.kind == ContentStorageReferenceKind.METADATA_VERSION }
                assertEquals(7, history.version)
                assertEquals(17L, history.contentLength)
                assertNull(history.uploaded)
                val legacy = refs.single { it.kind == ContentStorageReferenceKind.METADATA_VERSION_SUPPLEMENTARY }
                assertEquals(7, legacy.version)
                assertEquals("thumbnail", legacy.supplementaryKey)
                assertEquals(18L, legacy.contentLength)
                assertEquals(uploaded.toInstant(), legacy.uploaded?.toInstant())
                assertNull(legacy.supplementaryId, "The current thumbnail UUID cannot address the historical record")
                val pending = refs.single { it.contentId == deleted.id }
                assertEquals(true, pending.deleted)
                assertNull(pending.uploaded)
                assertNull(pending.contentLength)
                assertFailsWith<IllegalArgumentException> { service.getStorageReferences(-1, 1) }
                assertFailsWith<IllegalArgumentException> { service.getStorageReferences(0, 0) }
                assertFailsWith<IllegalArgumentException> { service.getStorageReferences(0, -1) }
                repository.markDeleted(current.id)
                val deletedRefs = service.getStorageReferences(0, Int.MAX_VALUE).filter { it.contentId == current.id }
                assertEquals(5, deletedRefs.size)
                assertTrue(deletedRefs.all { it.deleted }, "Soft deletion must include history and attachments")
            }
        } finally {
            withRequest { owners.forEach { repository.deleteById(it.id) } }
        }
    }

    @Test
    fun `storage references exclude confirmed database-only documents while preserving possible assets`(): Unit = runBlocking {
        val owners = mutableListOf<UUID>()
        val expectations = mutableListOf<Triple<UUID, Int, Boolean>>()
        suspend fun owner(
            type: String,
            documentVersion: Int? = 2,
            length: Long? = null,
            uploaded: Boolean = false,
            historical: Boolean = false,
            asset: Boolean,
        ): UUID {
            val id = UUID.random().also { owners.add(it) }
            val mime = "'$type'"
            rawUpdate("""
                insert into metadata (id, version, name, content_type, content_length, uploaded)
                values (?, 2, 'Document storage evidence', $mime, ${length ?: "null"}, ${if (uploaded) "now()" else "null"})
            """.trimIndent(), id)
            documentVersion?.let { version ->
                rawUpdate("""insert into documents (metadata_id, version, title, content)
                    values (?, $version, 'Stored in PostgreSQL', '{"document":{"type":"doc"}}')""", id)
            }
            if (historical) {
                rawUpdate("""
                    insert into metadata_versions (id, version, name, content_type, content_length)
                    values (?, 1, 'Earlier document', $mime, ${length ?: "null"})
                """.trimIndent(), id)
            }
            expectations.add(Triple(id, if (historical) 1 else 2, asset))
            return id
        }
        try {
            for (type in listOf("bosca/v-document", "bosca/v-guide-step", "bosca/v-guide-step-module")) {
                owner(type, asset = false)
                owner(type, documentVersion = 1, historical = true, asset = false)
            }
            owner("bosca/v-document", documentVersion = null, asset = true)
            owner("bosca/v-document", documentVersion = 1, asset = true)
            owner("bosca/v-document", uploaded = true, asset = true)
            owner("bosca/v-document", length = 17, asset = true)
            owner("image/png", asset = true)
            owner("bosca/custom", asset = true)
            owner("bosca/v-document", documentVersion = 1, length = 17, historical = true, asset = true)
            owner("bosca/v-document", documentVersion = null, historical = true, asset = true)
            val attached = owner("bosca/v-document", asset = false)
            rawUpdate("""
                insert into metadata_supplementary (metadata_id, key, name, content_type, uploaded, content_length)
                values (?, 'thumbnail', 'Document thumbnail', 'image/png', now(), 9)
            """.trimIndent(), attached)
            withRequest {
                val all = service.getStorageReferences(0, Int.MAX_VALUE)
                for ((id, version, expected) in expectations) {
                    assertEquals(expected, all.any {
                        it.contentId == id && it.version == version &&
                            it.kind in setOf(ContentStorageReferenceKind.METADATA, ContentStorageReferenceKind.METADATA_VERSION)
                    }, "Binary reference selection for $id version $version")
                }
                assertEquals(ContentStorageReferenceKind.METADATA_SUPPLEMENTARY, all.single { it.contentId == attached }.kind)
                val paged = buildList {
                    var offset = 0L
                    while (true) {
                        val page = service.getStorageReferences(offset, 2)
                        if (page.isEmpty()) break
                        addAll(page)
                        offset += page.size
                    }
                }
                assertEquals(all, paged)
            }
        } finally {
            withRequest { owners.forEach { repository.deleteById(it) } }
        }
    }

    @Test
    fun `storage references keep the caller snapshot across pages while source rows change`(): Unit = runBlocking {
        val owner = withRequest {
            repository.add(
                Metadata(
                    name = "Storage snapshot",
                    type = MetadataType.STANDARD,
                    contentType = "image/png",
                    contentLength = null,
                    languageTag = "en",
                    workflowStateId = "pending",
                    attributes = JsonObject(emptyMap()),
                ),
            )
        }
        try {
            rawUpdate("""
                insert into metadata_supplementary (metadata_id, key, name, content_type, uploaded)
                select id, 'old', 'Original upload', content_type, now() from metadata where id = ?
            """.trimIndent(), owner.id)
            withRequest {
                val cm = connection()
                cm.beginTransaction()
                try {
                    cm.useStatement("set transaction isolation level repeatable read, read only") { it.execute() }
                    val before = service.getStorageReferences(0, Int.MAX_VALUE)
                    rawUpdate("update metadata set content_length = 99, uploaded = now() where id = ?", owner.id)
                    rawUpdate("delete from metadata_supplementary where metadata_id = ?", owner.id)
                    rawUpdate("""
                        insert into metadata_supplementary (metadata_id, key, name, content_type, uploaded)
                        select id, 'new', 'New upload', content_type, now() from metadata where id = ?
                    """.trimIndent(), owner.id)
                    val after = buildList {
                        var offset = 0L
                        while (true) {
                            val page = service.getStorageReferences(offset, 2)
                            if (page.isEmpty()) break
                            addAll(page)
                            offset += page.size
                        }
                    }
                    assertEquals(before, after)
                    assertEquals(2, after.count { it.contentId == owner.id })
                    assertEquals("old", after.single {
                        it.contentId == owner.id && it.kind == ContentStorageReferenceKind.METADATA_SUPPLEMENTARY
                    }.supplementaryKey)
                } finally {
                    withContext(NonCancellable) { cm.rollbackTransaction() }
                }
            }
            val fresh = withRequest { service.getStorageReferences(0, Int.MAX_VALUE).filter { it.contentId == owner.id } }
            assertEquals(2, fresh.size)
            assertEquals("new", fresh.single { it.kind == ContentStorageReferenceKind.METADATA_SUPPLEMENTARY }.supplementaryKey)
            assertEquals(99L, fresh.single { it.kind == ContentStorageReferenceKind.METADATA }.contentLength)
        } finally {
            withRequest { repository.deleteById(owner.id) }
        }
    }

    @Test
    fun `getById caches in NATS and removeFromCache clears it`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val inserted = withRequest {
            repository.add(
                Metadata(
                    name = "Test Metadata",
                    type = MetadataType.STANDARD,
                    contentType = "text/plain",
                    contentLength = null,
                    languageTag = "en",
                    workflowStateId = "pending",
                    attributes = JsonObject(emptyMap()),
                )
            )
        }
        val testId = inserted.id

        // Request 1: getById populates cache
        val result1 = withRequest { service.getById(testId) }
        assertNotNull(result1)
        assertEquals("Test Metadata", result1.name)

        // Modify DB directly behind cache's back
        rawUpdate("UPDATE metadata SET name = 'Updated Metadata' WHERE id = ?", testId)

        // Request 2: getById should return STALE cached data (proves caching works)
        val result2 = withRequest { service.getById(testId) }
        assertNotNull(result2)
        assertEquals("Test Metadata", result2.name, "Should return stale cached data")

        // Request 3: removeFromCache inside a transaction, commit triggers flush to NATS
        val cm3 = ConnectionManager(connectionPool)
        val rc3 = RequestCache(cacheManager, serializer)
        withContext(cm3.asCoroutineContext() + rc3.asCoroutineContext()) {
            cm3.beginTransaction()
            service.removeFromCache(testId)
            withContext(NonCancellable) {
                cm3.commitTransaction()
            }
        }
        withContext(NonCancellable) {
            cm3.release()
        }

        // Request 4: getById should miss NATS and call repo, returning updated name
        val result4 = withRequest { service.getById(testId) }
        assertNotNull(result4)
        assertEquals("Updated Metadata", result4.name, "Should return fresh data after cache invalidation")
    }

    @Test
    fun `setReady invalidates cache so next request sees fresh data`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val inserted = withRequest {
            repository.add(
                Metadata(
                    name = "Test Metadata",
                    type = MetadataType.STANDARD,
                    contentType = "text/plain",
                    contentLength = null,
                    languageTag = "en",
                    workflowStateId = "pending",
                    attributes = JsonObject(emptyMap()),
                )
            )
        }
        val testId = inserted.id

        // Set workflow_state_id to 'draft' to avoid transitioner check in setReady
        rawUpdate("UPDATE metadata SET workflow_state_id = 'draft' WHERE id = ?", testId)

        val testPrincipal = Principal(id = UUID.random())

        mockkStatic("bosca.content.metadata.events.MetadataSetReadyExtKt")
        coEvery { any<MetadataSetReady>().dispatch() } just Runs

        // Request 1: getById populates cache with ready = null
        val result1 = withRequest { service.getById(testId) }
        assertNotNull(result1)
        assertNull(result1.ready)
        assertEquals("draft", result1.workflowStateId)

        // Request 2: setReady updates ready timestamp, invalidates cache
        withRequest {
            service.setReady(result1, testPrincipal)
        }

        // Request 3: getById should see fresh data with ready != null
        val result3 = withRequest { service.getById(testId) }
        assertNotNull(result3)
        assertNotNull(result3.ready, "ready should be set after setReady call")
    }

    // ── Data collaboration sync ──────────────────────────────────────────────

    @Test
    fun `edit writes the attribute value into the data collaboration document`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val inserted = withRequest {
            repository.add(
                Metadata(
                    name = "QA Data",
                    type = MetadataType.STANDARD,
                    contentType = "bosca/v-data",
                    contentLength = null,
                    languageTag = "en",
                    workflowStateId = "pending",
                    attributes = JsonObject(emptyMap()),
                )
            )
        }
        val id = inserted.id
        val templateId = UUID.random()

        // The data side is mocked: a data collaboration document exists, backed by a
        // template with one STRING attribute "note", and no relationships/parents.
        coEvery { dataService.getCollaboration(id, any()) } returns
            DataCollaboration(metadataId = id, version = 1, content = encodeStateAsUpdate(Doc()))
        coEvery { dataService.getData(id, any()) } returns
            Data(metadataId = id, version = 1, templateMetadataId = templateId, templateMetadataVersion = 1)
        coEvery { dataTemplateService.getTemplateAttributes(templateId, any()) } returns listOf(
            TemplateAttribute(
                dataAttribute = DataTemplateAttribute(
                    metadataId = templateId,
                    version = 1,
                    key = "note",
                    name = "Note",
                    description = "",
                    type = AttributeType.STRING,
                    ui = AttributeUiType.INPUT,
                    list = false,
                    sort = 0,
                ),
            ),
        )
        coEvery { collectionService.getMetadataParents(id) } returns emptyList()
        val captured = slot<DataCollaborationInput>()
        coEvery { dataService.setCollaboration(capture(captured)) } just Runs

        withRequest {
            service.edit(
                id,
                MetadataInput(
                    name = "QA Data",
                    languageTag = "en",
                    contentType = "bosca/v-data",
                    attributes = buildJsonObject { put("note", "Hello Data") },
                ),
            )
        }

        assertTrue(captured.isCaptured, "data collaboration document should be written on edit")
        val doc = Doc()
        applyUpdate(doc, captured.captured.content!!)
        assertEquals("Hello Data", doc.getMap("textAttributes").get("note"))
    }
}
