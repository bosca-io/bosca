package bosca.content.metadata.service

import bosca.cache.CacheManager
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
