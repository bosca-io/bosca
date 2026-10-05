package bosca.content.metadata.service

import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.attributes.TemplateAttributeInput
import bosca.attributes.TemplateToolInput
import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.asCoroutineContext
import bosca.content.metadata.model.DataTemplate
import bosca.content.metadata.model.DataTemplateInput
import bosca.content.metadata.model.DataType
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.repository.DataTemplateAttributeRepositoryImpl
import bosca.content.metadata.repository.DataTemplateAttributeWorkflowRepositoryImpl
import bosca.content.metadata.repository.DataTemplateRepositoryImpl
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
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
import io.mockk.unmockkAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.junit.AfterClass
import org.junit.BeforeClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.toJavaUuid

@OptIn(InternalDI::class)
class DataTemplateServiceImplCoverageTest {

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

    private lateinit var service: DataTemplateServiceImpl

    @BeforeTest
    fun setup() = runBlocking {
        unmockkAll()
        infrastructure.reset()
        serializer = RequestCacheSerializerImpl(testJson)

        ProviderRegistry.clear()
        provides<CacheManager>(singleton = true) { cacheManager }
        provides<RequestCacheSerializer>(singleton = true) { serializer }
        provides<Json>(singleton = true) { testJson }

        service = DataTemplateServiceImpl(
            DataTemplateRepositoryImpl(),
            DataTemplateAttributeRepositoryImpl(),
            DataTemplateAttributeWorkflowRepositoryImpl(),
            testJson,
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

    /**
     * data_templates.metadata_id FKs to metadata(id); getAll() filters on
     * metadata.content_type = 'bosca/v-data-template'. Insert a template
     * metadata row so both constraints are satisfied.
     */
    private suspend fun insertTemplateMetadata(id: UUID) {
        val cm = ConnectionManager(connectionPool)
        withContext(cm.asCoroutineContext()) {
            cm.beginTransaction()
            cm.useStatement(
                "insert into metadata (id, name, content_type) values (?, 'Data Template', 'bosca/v-data-template')"
            ) { stmt ->
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

    private fun attributeInput(
        key: String,
        withTools: Boolean = false,
    ) = TemplateAttributeInput(
        key = key,
        name = "Name $key",
        description = "Description $key",
        supplementaryKey = null,
        configuration = null,
        type = AttributeType.STRING,
        ui = AttributeUiType.INPUT,
        list = false,
        tools = if (withTools) {
            listOf(
                TemplateToolInput(
                    id = UUID.random(),
                    name = "tool",
                    description = "a tool",
                    query = "query",
                    resultPath = "$.path",
                )
            )
        } else {
            null
        },
    )

    @Test
    fun `saveTemplate with explicit type and tools then getTemplate and getTemplateAttributes read back`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = UUID.random()
            insertTemplateMetadata(id)

            withRequest {
                service.saveTemplate(
                    id,
                    1,
                    DataTemplateInput(
                        type = DataType.TABLE,
                        defaultAttributes = buildJsonObject { put("default", "value") },
                        attributes = listOf(
                            attributeInput("first", withTools = true),
                            attributeInput("second", withTools = false),
                        ),
                    ),
                )
            }

            val template = withRequest { service.getTemplate(id, 1) }
            assertNotNull(template)
            assertEquals(id, template.metadataId)
            assertEquals(1, template.version)
            assertEquals(DataType.TABLE, template.type)
            assertNotNull(template.defaultAttributes)

            val attributes = withRequest { service.getTemplateAttributes(id, 1) }
            assertEquals(2, attributes.size)
            assertEquals("first", attributes[0].key)
            assertEquals("second", attributes[1].key)
            // First attribute carried tools; second did not.
            assertNotNull(attributes[0].tools)
            assertNull(attributes[1].tools)
        }

    @Test
    fun `saveTemplate with null type defaults to ATTRIBUTES`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = UUID.random()
            insertTemplateMetadata(id)

            withRequest {
                service.saveTemplate(
                    id,
                    1,
                    DataTemplateInput(
                        type = null,
                        defaultAttributes = null,
                        attributes = emptyList(),
                    ),
                )
            }

            val template = withRequest { service.getTemplate(id, 1) }
            assertNotNull(template)
            assertEquals(DataType.ATTRIBUTES, template.type)
            assertNull(template.defaultAttributes)

            val attributes = withRequest { service.getTemplateAttributes(id, 1) }
            assertTrue(attributes.isEmpty())
        }

    @Test
    fun `getTemplate returns null for missing template`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val missing = withRequest { service.getTemplate(UUID.random(), 1) }
            assertNull(missing)
        }

    @Test
    fun `getTemplateAttributes returns empty list when no attributes exist`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = UUID.random()
            insertTemplateMetadata(id)
            withRequest {
                service.saveTemplate(id, 1, DataTemplateInput(type = DataType.ATTRIBUTES, attributes = emptyList()))
            }

            val attributes = withRequest { service.getTemplateAttributes(id, 1) }
            assertTrue(attributes.isEmpty())
        }

    @Test
    fun `getTemplateAttributeWorkflows returns empty list when none configured`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = UUID.random()
            insertTemplateMetadata(id)
            withRequest {
                service.saveTemplate(id, 1, DataTemplateInput(attributes = listOf(attributeInput("k"))))
            }

            val workflows = withRequest { service.getTemplateAttributeWorkflows(id, 1, "k") }
            assertTrue(workflows.isEmpty())
        }

    @Test
    fun `getAll returns saved templates`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = UUID.random()
            insertTemplateMetadata(id)
            withRequest {
                service.saveTemplate(id, 1, DataTemplateInput(type = DataType.ATTRIBUTES, attributes = emptyList()))
            }

            val all = withRequest { service.getAll() }
            assertTrue(all.any { it.metadataId == id && it.version == 1 })
        }

    @Test
    fun `addAttribute with tools and without tools both persist and invalidate cache`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = UUID.random()
            insertTemplateMetadata(id)
            withRequest {
                service.saveTemplate(id, 1, DataTemplateInput(type = DataType.ATTRIBUTES, attributes = emptyList()))
            }
            // Prime the attribute cache with the empty result.
            assertTrue(withRequest { service.getTemplateAttributes(id, 1) }.isEmpty())

            withRequest { service.addAttribute(id, 1, attributeInput("withTools", withTools = true), sort = 0) }
            withRequest { service.addAttribute(id, 1, attributeInput("noTools", withTools = false), sort = 1) }

            val attributes = withRequest { service.getTemplateAttributes(id, 1) }
            assertEquals(2, attributes.size)
            assertEquals("withTools", attributes[0].key)
            assertNotNull(attributes[0].tools)
            assertEquals("noTools", attributes[1].key)
            assertNull(attributes[1].tools)
        }

    @Test
    fun `deleteAttribute removes a single attribute by key and invalidates cache`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = UUID.random()
            insertTemplateMetadata(id)
            withRequest {
                service.saveTemplate(
                    id,
                    1,
                    DataTemplateInput(attributes = listOf(attributeInput("keep"), attributeInput("drop"))),
                )
            }
            assertEquals(2, withRequest { service.getTemplateAttributes(id, 1) }.size)

            withRequest { service.deleteAttribute(id, 1, "drop") }

            val attributes = withRequest { service.getTemplateAttributes(id, 1) }
            assertEquals(1, attributes.size)
            assertEquals("keep", attributes[0].key)
        }

    @Test
    fun `setDefaultAttributes with value then with null updates the template`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = UUID.random()
            insertTemplateMetadata(id)
            withRequest {
                service.saveTemplate(id, 1, DataTemplateInput(type = DataType.ATTRIBUTES, attributes = emptyList()))
            }

            withRequest { service.setDefaultAttributes(id, 1, buildJsonObject { put("k", "v") }) }
            val withDefaults = withRequest { service.getTemplate(id, 1) }
            assertNotNull(withDefaults)
            assertNotNull(withDefaults.defaultAttributes)

            withRequest { service.setDefaultAttributes(id, 1, null) }
            val cleared = withRequest { service.getTemplate(id, 1) }
            assertNotNull(cleared)
            assertNull(cleared.defaultAttributes)
        }

    @Test
    fun `setAttributes replaces the full attribute set and invalidates cache`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = UUID.random()
            insertTemplateMetadata(id)
            withRequest {
                service.saveTemplate(
                    id,
                    1,
                    DataTemplateInput(attributes = listOf(attributeInput("original"))),
                )
            }
            assertEquals(listOf("original"), withRequest { service.getTemplateAttributes(id, 1) }.map { it.key })

            withRequest {
                service.setAttributes(
                    id,
                    1,
                    listOf(attributeInput("alpha", withTools = true), attributeInput("beta")),
                )
            }

            val attributes = withRequest { service.getTemplateAttributes(id, 1) }
            assertEquals(listOf("alpha", "beta"), attributes.map { it.key })
            assertNotNull(attributes[0].tools)
            assertNull(attributes[1].tools)
        }

    @Test
    fun `setAttributes with empty list clears all attributes`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = UUID.random()
            insertTemplateMetadata(id)
            withRequest {
                service.saveTemplate(
                    id,
                    1,
                    DataTemplateInput(attributes = listOf(attributeInput("a"), attributeInput("b"))),
                )
            }
            assertEquals(2, withRequest { service.getTemplateAttributes(id, 1) }.size)

            withRequest { service.setAttributes(id, 1, emptyList()) }

            assertTrue(withRequest { service.getTemplateAttributes(id, 1) }.isEmpty())
        }

    @Test
    fun `addToBatch populates found keys and leaves missing keys null`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val presentId = UUID.random()
            val absentId = UUID.random()
            insertTemplateMetadata(presentId)
            withRequest {
                service.saveTemplate(
                    presentId,
                    1,
                    DataTemplateInput(type = DataType.TABLE, attributes = emptyList()),
                )
            }

            val presentKey = MetadataCacheKeyId(presentId, 1)
            val absentKey = MetadataCacheKeyId(absentId, 1)
            val batch = Batch<MetadataCacheKeyId, DataTemplate>(listOf(presentKey, absentKey))

            withRequest { service.addToBatch(batch) }

            val results = batch.getResults()
            assertEquals(2, results.size)
            val present = batch.getData(presentKey)
            assertNotNull(present)
            assertEquals(presentId, present.metadataId)
            assertEquals(DataType.TABLE, present.type)
            assertNull(batch.getData(absentKey))
        }
}
