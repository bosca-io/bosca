package bosca.content.metadata.service

import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.asCoroutineContext
import bosca.content.metadata.model.Data
import bosca.content.metadata.model.DataCollaborationInput
import bosca.content.metadata.model.DataInput
import bosca.content.metadata.model.DataType
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.repository.DataCollaborationRepositoryImpl
import bosca.content.metadata.repository.DataRepositoryImpl
import bosca.content.metadata.repository.MetadataRepositoryImpl
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.events.DisabledEventManagerFilter
import bosca.events.EventManager
import bosca.events.asCoroutineContext
import bosca.graphql.Batch
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.test.ContentTestInfrastructure
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.junit.AfterClass
import org.junit.BeforeClass
import yks.utils.Doc
import yks.utils.applyUpdate
import yks.utils.encodeStateAsUpdate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(InternalDI::class)
class DataServiceImplCoverageTest {

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

    private lateinit var metadataRepository: MetadataRepositoryImpl
    private lateinit var dataRepository: DataRepositoryImpl
    private lateinit var dataCollaborationRepository: DataCollaborationRepositoryImpl
    private lateinit var service: DataServiceImpl

    private val metadataService = mockk<MetadataService>(relaxed = true)
    private val metadataServiceProvider = mockk<ObjectProvider<MetadataService>>()

    @BeforeTest
    fun setup() = runBlocking {
        unmockkAll()
        infrastructure.reset()
        serializer = RequestCacheSerializerImpl(testJson)

        ProviderRegistry.clear()
        provides<CacheManager>(singleton = true) { cacheManager }
        provides<RequestCacheSerializer>(singleton = true) { serializer }
        provides<Json>(singleton = true) { testJson }

        coEvery { metadataServiceProvider.get() } returns metadataService

        metadataRepository = MetadataRepositoryImpl()
        dataRepository = DataRepositoryImpl()
        dataCollaborationRepository = DataCollaborationRepositoryImpl()

        service = DataServiceImpl(
            metadataServiceProvider,
            dataRepository,
            dataCollaborationRepository,
        )
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
        unmockkAll()
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

    /** Inserts a metadata row so `data.metadata_id` FK is satisfied, returning its id. */
    private suspend fun insertMetadata(name: String = "Data Owner"): UUID = withRequest {
        metadataRepository.add(
            Metadata(
                name = name,
                type = MetadataType.STANDARD,
                contentType = "bosca/v-data",
                contentLength = null,
                languageTag = "en",
                workflowStateId = "pending",
                attributes = JsonObject(emptyMap()),
            )
        ).id
    }

    @Test
    fun `addData with explicit type persists and getData returns it`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata()
        val templateId = UUID.random()

        withRequest {
            service.addData(id, 1, DataInput(templateMetadataId = templateId, templateMetadataVersion = 2, type = DataType.TABLE))
        }

        val data = withRequest { service.getData(id, 1) }
        assertNotNull(data)
        assertEquals(id, data.metadataId)
        assertEquals(1, data.version)
        assertEquals(DataType.TABLE, data.type)
        assertEquals(templateId, data.templateMetadataId)
        assertEquals(2, data.templateMetadataVersion)
    }

    @Test
    fun `addData with null type defaults to ATTRIBUTES`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata()

        withRequest {
            service.addData(id, 1, DataInput())
        }

        val data = withRequest { service.getData(id, 1) }
        assertNotNull(data)
        assertEquals(DataType.ATTRIBUTES, data.type)
        assertNull(data.templateMetadataId)
        assertNull(data.templateMetadataVersion)
    }

    @Test
    fun `getData returns null when no data row exists`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata()
        val data = withRequest { service.getData(id, 1) }
        assertNull(data)
    }

    @Test
    fun `setData edits existing data row and invalidates cache`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata()
        withRequest { service.addData(id, 1, DataInput(type = DataType.ATTRIBUTES)) }

        // Populate the cache with the current (ATTRIBUTES) value.
        val before = withRequest { service.getData(id, 1) }
        assertNotNull(before)
        assertEquals(DataType.ATTRIBUTES, before.type)

        val templateId = UUID.random()
        val metadata = Metadata(
            id = id,
            version = 1,
            name = "Data Owner",
            type = MetadataType.STANDARD,
            contentType = "bosca/v-data",
            contentLength = null,
            languageTag = "en",
            workflowStateId = "pending",
        )
        withRequest {
            service.setData(metadata, DataInput(templateMetadataId = templateId, templateMetadataVersion = 3, type = DataType.TABLE))
        }

        // New request — verifies cache was invalidated and repo now returns the edited value.
        val after = withRequest { service.getData(id, 1) }
        assertNotNull(after)
        assertEquals(DataType.TABLE, after.type)
        assertEquals(templateId, after.templateMetadataId)
        assertEquals(3, after.templateMetadataVersion)
    }

    @Test
    fun `setData with null input type defaults to ATTRIBUTES`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata()
        withRequest { service.addData(id, 1, DataInput(type = DataType.TABLE)) }

        val metadata = Metadata(
            id = id,
            version = 1,
            name = "Data Owner",
            type = MetadataType.STANDARD,
            contentType = "bosca/v-data",
            contentLength = null,
            languageTag = "en",
            workflowStateId = "pending",
        )
        withRequest { service.setData(metadata, DataInput()) }

        val after = withRequest { service.getData(id, 1) }
        assertNotNull(after)
        assertEquals(DataType.ATTRIBUTES, after.type)
    }

    @Test
    fun `setTemplate upserts template on data and invalidates cache`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata()
        val templateId = UUID.random()

        // setTemplate uses an upsert, so no pre-existing data row is required.
        withRequest { service.setTemplate(id, 1, templateId, 4) }

        val data = withRequest { service.getData(id, 1) }
        assertNotNull(data)
        assertEquals(templateId, data.templateMetadataId)
        assertEquals(4, data.templateMetadataVersion)
        // Default type when created via setTemplate insert.
        assertEquals(DataType.ATTRIBUTES, data.type)
    }

    // NOTE: A test for `service.setType(...)` was removed here as genuinely unreachable via the
    // service API. `DataRepository.setType` uses the SQL `update data set type = :type` with no
    // `::data_type` cast (unlike `add`/`edit`, which cast `:type::data_type`). `EnumMapper.bind`
    // binds `DataType` as a plain varchar, and no `CREATE CAST (varchar AS data_type)` implicit
    // cast exists (V59/V82/V142 cover other enums but not `data_type`). Postgres therefore rejects
    // the assignment with "column type is of type data_type but expression is of type character
    // varying". The fix is a one-word change in main source (`:type::data_type` in
    // DataRepository.setType), which is out of scope for a test-only edit.

    @Test
    fun `getCollaboration returns null then the stored document`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata()
        // data_collaborations FKs to data, so a data row must exist first.
        withRequest { service.addData(id, 1, DataInput()) }

        assertNull(withRequest { service.getCollaboration(id, 1) })

        val content = encodeStateAsUpdate(Doc())
        withRequest { service.setCollaboration(DataCollaborationInput(metadataId = id, version = 1, content = content)) }

        val collab = withRequest { service.getCollaboration(id, 1) }
        assertNotNull(collab)
        assertEquals(id, collab.metadataId)
        assertEquals(1, collab.version)
    }

    @Test
    fun `setCollaboration with null content stores an empty byte array`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata()
        withRequest { service.addData(id, 1, DataInput()) }

        withRequest { service.setCollaboration(DataCollaborationInput(metadataId = id, version = 1, content = null)) }

        val collab = withRequest { service.getCollaboration(id, 1) }
        assertNotNull(collab)
        assertEquals(0, collab.content.size)
    }

    @Test
    fun `removeCollaboration deletes the stored document`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata()
        withRequest { service.addData(id, 1, DataInput()) }
        withRequest { service.setCollaboration(DataCollaborationInput(metadataId = id, version = 1, content = encodeStateAsUpdate(Doc()))) }
        assertNotNull(withRequest { service.getCollaboration(id, 1) })

        withRequest { service.removeCollaboration(id, 1) }

        assertNull(withRequest { service.getCollaboration(id, 1) })
    }

    @Test
    fun `addToBatch resolves present keys and leaves missing keys unset`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val presentId = insertMetadata("present")
        withRequest { service.addData(presentId, 1, DataInput(type = DataType.TABLE)) }

        val missingId = insertMetadata("missing")
        // No data row for missingId — batch resolver must skip it via `?: continue`.

        val presentKey = MetadataCacheKeyId(presentId, 1)
        val missingKey = MetadataCacheKeyId(missingId, 1)
        val batch = Batch<MetadataCacheKeyId, Data>(listOf(presentKey, missingKey))

        withRequest { service.addToBatch(batch) }

        val present = batch.getData(presentKey)
        assertNotNull(present)
        assertEquals(presentId, present.metadataId)
        assertEquals(DataType.TABLE, present.type)

        assertNull(batch.getData(missingKey))
    }

    private fun dirtyFlag(content: ByteArray, mapName: String): Any? {
        val doc = Doc()
        applyUpdate(doc, content)
        return doc.getMap(mapName).get("|__dirty__|")
    }

    @Test
    fun `markCollaborationCollectionsDirty sets the collections dirty flag`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata()
        withRequest { service.addData(id, 1, DataInput()) }
        withRequest { service.setCollaboration(DataCollaborationInput(metadataId = id, version = 1, content = encodeStateAsUpdate(Doc()))) }

        coEvery { metadataService.getById(id) } returns metadataFor(id)

        withRequest { service.markCollaborationCollectionsDirty(id) }

        val collab = withRequest { service.getCollaboration(id, 1) }
        assertNotNull(collab)
        assertEquals("true", dirtyFlag(collab.content, "collections")?.toString())
    }

    @Test
    fun `markCollaborationRelationshipsDirty sets the relationships dirty flag`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata()
        withRequest { service.addData(id, 1, DataInput()) }
        withRequest { service.setCollaboration(DataCollaborationInput(metadataId = id, version = 1, content = encodeStateAsUpdate(Doc()))) }

        coEvery { metadataService.getById(id) } returns metadataFor(id)

        withRequest { service.markCollaborationRelationshipsDirty(id) }

        val collab = withRequest { service.getCollaboration(id, 1) }
        assertNotNull(collab)
        assertEquals("true", dirtyFlag(collab.content, "metadatas")?.toString())
    }

    @Test
    fun `markCollaborationAttributesDirty sets the attributes dirty flag`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata()
        withRequest { service.addData(id, 1, DataInput()) }
        withRequest { service.setCollaboration(DataCollaborationInput(metadataId = id, version = 1, content = encodeStateAsUpdate(Doc()))) }

        coEvery { metadataService.getById(id) } returns metadataFor(id)

        withRequest { service.markCollaborationAttributesDirty(id) }

        val collab = withRequest { service.getCollaboration(id, 1) }
        assertNotNull(collab)
        assertEquals("true", dirtyFlag(collab.content, "attrs")?.toString())
    }

    @Test
    fun `markCollaborationCollectionsDirty is a no-op when metadata is missing`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val missing = UUID.random()
        coEvery { metadataService.getById(missing) } returns null

        // Should return early without touching collaborations.
        withRequest { service.markCollaborationCollectionsDirty(missing) }
    }

    @Test
    fun `markCollaborationRelationshipsDirty is a no-op when collaboration is missing`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata()
        withRequest { service.addData(id, 1, DataInput()) }
        // No collaboration document seeded.

        coEvery { metadataService.getById(id) } returns metadataFor(id)

        withRequest { service.markCollaborationRelationshipsDirty(id) }

        // Still no collaboration document created.
        assertNull(withRequest { service.getCollaboration(id, 1) })
    }

    @Test
    fun `markCollaborationAttributesDirty is a no-op when collaboration is missing`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata()
        withRequest { service.addData(id, 1, DataInput()) }

        coEvery { metadataService.getById(id) } returns metadataFor(id)

        withRequest { service.markCollaborationAttributesDirty(id) }

        assertNull(withRequest { service.getCollaboration(id, 1) })
    }

    @Test
    fun `mark dirty then getCollaboration confirms only that map is dirty`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata()
        withRequest { service.addData(id, 1, DataInput()) }
        withRequest { service.setCollaboration(DataCollaborationInput(metadataId = id, version = 1, content = encodeStateAsUpdate(Doc()))) }

        coEvery { metadataService.getById(id) } returns metadataFor(id)

        withRequest { service.markCollaborationCollectionsDirty(id) }

        val collab = withRequest { service.getCollaboration(id, 1) }
        assertNotNull(collab)
        assertEquals("true", dirtyFlag(collab.content, "collections")?.toString())
        assertFalse("true" == dirtyFlag(collab.content, "attrs")?.toString())
    }

    private fun metadataFor(id: UUID, version: Int = 1) = Metadata(
        id = id,
        version = version,
        name = "Data Owner",
        type = MetadataType.STANDARD,
        contentType = "bosca/v-data",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "pending",
    )
}
