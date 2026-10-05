package bosca.content.metadata.service

import bosca.attributes.AttributeLocation
import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.attributes.TemplateAttributeInput
import bosca.attributes.TemplateToolInput
import bosca.attributes.TemplateWorkflowInput
import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.asCoroutineContext
import bosca.content.collection.model.CollectionTemplate
import bosca.content.metadata.model.CollectionTemplateFilterInput
import bosca.content.metadata.model.CollectionTemplateFilters
import bosca.content.metadata.model.CollectionTemplateFiltersInput
import bosca.content.metadata.model.CollectionTemplateInput
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.repository.CollectionTemplateAttributeRepositoryImpl
import bosca.content.metadata.repository.CollectionTemplateAttributeWorkflowRepositoryImpl
import bosca.content.metadata.repository.CollectionTemplateRepositoryImpl
import bosca.content.ordering.OrderingInput
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.toJavaUuid

@OptIn(InternalDI::class)
class CollectionTemplateServiceImplCoverageTest {

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

    private lateinit var service: CollectionTemplateServiceImpl

    @BeforeTest
    fun setup() = runBlocking {
        infrastructure.reset()
        serializer = RequestCacheSerializerImpl(testJson)

        ProviderRegistry.clear()
        provides<CacheManager>(singleton = true) { cacheManager }
        provides<RequestCacheSerializer>(singleton = true) { serializer }
        provides<Json>(singleton = true) { testJson }

        service = CollectionTemplateServiceImpl(
            CollectionTemplateRepositoryImpl(),
            CollectionTemplateAttributeRepositoryImpl(),
            CollectionTemplateAttributeWorkflowRepositoryImpl(),
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

    /** Executes a parameter-free statement in its own committed transaction. */
    private suspend fun rawExec(sql: String) {
        val cm = ConnectionManager(connectionPool)
        withContext(cm.asCoroutineContext()) {
            cm.beginTransaction()
            cm.useStatement(sql) { stmt -> stmt.execute() }
            withContext(NonCancellable) {
                cm.commitTransaction()
            }
        }
        withContext(NonCancellable) {
            cm.release()
        }
    }

    /** Creates the metadata row that a collection_templates row FKs to; returns its id. */
    private suspend fun seedTemplateMetadata(): UUID {
        val id = UUID.random()
        rawExec("insert into metadata (id, name, content_type) values ('${id.toJavaUuid()}', 'Tmpl', 'bosca/v-collection-template')")
        return id
    }

    /** Seeds a workflows row so template-attribute-workflow inserts satisfy their FK. */
    private suspend fun seedWorkflow(id: String) {
        rawExec("insert into workflows (id, name, description, queue) values ('$id', '$id', 'desc', 'q')")
    }

    @Test
    fun `saveTemplate persists template attributes filters and ordering then getAll and getCollectionTemplate return it`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = seedTemplateMetadata()

            withRequest {
                service.saveTemplate(
                    id,
                    1,
                    CollectionTemplateInput(
                        attributes = listOf(
                            TemplateAttributeInput(
                                key = "title",
                                name = "Title",
                                description = "the title",
                                type = AttributeType.STRING,
                                ui = AttributeUiType.INPUT,
                                location = AttributeLocation.ITEM,
                                tools = listOf(
                                    TemplateToolInput(
                                        id = UUID.random(),
                                        name = "tool",
                                        description = "d",
                                        query = "q",
                                        resultPath = "$",
                                    ),
                                ),
                            ),
                            TemplateAttributeInput(
                                key = "subtitle",
                                name = "Subtitle",
                                description = "the subtitle",
                                type = AttributeType.STRING,
                                ui = AttributeUiType.INPUT,
                            ),
                        ),
                        defaultAttributes = buildJsonObject { put("title", "Untitled") },
                        filters = CollectionTemplateFilters(
                            listOf(bosca.content.metadata.model.CollectionTemplateFilter("f", "n")),
                        ),
                        ordering = listOf(OrderingInput(field = "title")),
                        configuration = buildJsonObject { put("layout", "grid") },
                    ),
                )
            }

            val all = withRequest { service.getAll() }
            assertTrue(all.any { it.metadataId == id }, "template should appear in getAll")

            val template = withRequest { service.getCollectionTemplate(id, 1) }
            assertNotNull(template, "getCollectionTemplate should return the saved template")
            assertEquals(id, template.metadataId)
            assertEquals(1, template.version)

            val attributes = withRequest { service.getCollectionTemplateAttributes(id, 1) }
            assertEquals(2, attributes.size)
            assertEquals("title", attributes[0].key)
            assertEquals("subtitle", attributes[1].key)
            assertNotNull(attributes[0].tools, "tools should be serialized for the first attribute")
            assertNull(attributes[1].tools, "second attribute has no tools")
        }

    @Test
    fun `getCollectionTemplate returns null for unknown id`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val template = withRequest { service.getCollectionTemplate(UUID.random(), 1) }
            assertNull(template, "unknown template id should return null")
        }

    @Test
    fun `getCollectionTemplateAttributes returns empty list for template with no attributes`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = seedTemplateMetadata()
            withRequest { service.saveTemplate(id, 1, CollectionTemplateInput()) }

            val attributes = withRequest { service.getCollectionTemplateAttributes(id, 1) }
            assertTrue(attributes.isEmpty(), "template with no attributes returns empty list")
        }

    @Test
    fun `addCollectionTemplatesBatch loads templates by metadata ids`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = seedTemplateMetadata()
            withRequest {
                service.saveTemplate(id, 1, CollectionTemplateInput(configuration = buildJsonObject { put("k", "v") }))
            }

            val loaded = withRequest {
                val batch = Batch<MetadataCacheKeyId, CollectionTemplate>(listOf(MetadataCacheKeyId(id, 1)))
                service.addCollectionTemplatesBatch(batch)
                batch.getData(MetadataCacheKeyId(id, 1))
            }
            assertNotNull(loaded, "batch loader should populate the template")
            assertEquals(id, loaded.metadataId)
        }

    @Test
    fun `addAttribute with tools inserts attribute and returns wrapper`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = seedTemplateMetadata()
            withRequest { service.saveTemplate(id, 1, CollectionTemplateInput()) }

            val result = withRequest {
                service.addAttribute(
                    metadataId = id,
                    version = 1,
                    attribute = TemplateAttributeInput(
                        key = "hero",
                        name = "Hero",
                        description = "hero image",
                        supplementaryKey = "hero-sup",
                        configuration = buildJsonObject { put("c", 1) },
                        type = AttributeType.STRING,
                        ui = AttributeUiType.INPUT,
                        list = true,
                        location = AttributeLocation.ITEM,
                        tools = listOf(TemplateToolInput(name = "t", query = "q")),
                    ),
                    sort = 5,
                )
            }

            assertNotNull(result.collectionAttribute)
            assertEquals("hero", result.key)
            assertEquals(5, result.sort)
            assertNotNull(result.tools, "tools should be persisted")

            val attributes = withRequest { service.getCollectionTemplateAttributes(id, 1) }
            assertTrue(attributes.any { it.key == "hero" })
        }

    @Test
    fun `addAttribute without tools leaves tools null`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = seedTemplateMetadata()
            withRequest { service.saveTemplate(id, 1, CollectionTemplateInput()) }

            val result = withRequest {
                service.addAttribute(
                    metadataId = id,
                    version = 1,
                    attribute = TemplateAttributeInput(
                        key = "plain",
                        name = "Plain",
                        description = "",
                        type = AttributeType.STRING,
                        ui = AttributeUiType.INPUT,
                    ),
                    sort = 0,
                )
            }
            assertNull(result.tools, "no tools provided means null tools")
            assertEquals("plain", result.key)
        }

    @Test
    fun `deleteAttribute removes the attribute`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = seedTemplateMetadata()
            withRequest {
                service.saveTemplate(
                    id,
                    1,
                    CollectionTemplateInput(
                        attributes = listOf(
                            TemplateAttributeInput(
                                key = "removable",
                                name = "Removable",
                                description = "",
                                type = AttributeType.STRING,
                                ui = AttributeUiType.INPUT,
                            ),
                        ),
                    ),
                )
            }

            assertTrue(withRequest { service.getCollectionTemplateAttributes(id, 1) }.any { it.key == "removable" })

            withRequest { service.deleteAttribute(id, 1, "removable") }

            assertTrue(
                withRequest { service.getCollectionTemplateAttributes(id, 1) }.none { it.key == "removable" },
                "attribute should be gone after deleteAttribute",
            )
        }

    @Test
    fun `setDefaultAttributes updates and clears defaults`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = seedTemplateMetadata()
            withRequest { service.saveTemplate(id, 1, CollectionTemplateInput()) }

            withRequest { service.setDefaultAttributes(id, 1, buildJsonObject { put("x", 1) }) }
            val withDefaults = withRequest { service.getCollectionTemplate(id, 1) }
            assertNotNull(withDefaults?.defaultAttributes, "defaults should be set")

            withRequest { service.setDefaultAttributes(id, 1, null) }
            val cleared = withRequest { service.getCollectionTemplate(id, 1) }
            assertNotNull(cleared)
            assertNull(cleared.defaultAttributes, "defaults should be cleared")
        }

    @Test
    fun `setConfiguration updates and clears configuration`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = seedTemplateMetadata()
            withRequest { service.saveTemplate(id, 1, CollectionTemplateInput()) }

            withRequest { service.setConfiguration(id, 1, buildJsonObject { put("layout", "grid") }) }
            val withConfig = withRequest { service.getCollectionTemplate(id, 1) }
            assertNotNull(withConfig?.configuration, "configuration should be set")

            withRequest { service.setConfiguration(id, 1, null) }
            val cleared = withRequest { service.getCollectionTemplate(id, 1) }
            assertNotNull(cleared)
            assertNull(cleared.configuration, "configuration should be cleared")
        }

    @Test
    fun `setFilters with value then null`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = seedTemplateMetadata()
            withRequest { service.saveTemplate(id, 1, CollectionTemplateInput()) }

            withRequest {
                service.setFilters(
                    id,
                    1,
                    CollectionTemplateFiltersInput(listOf(CollectionTemplateFilterInput("f", "n"))),
                )
            }
            val afterSet = withRequest { service.getCollectionTemplate(id, 1) }
            assertNotNull(afterSet, "template should still exist after setFilters")

            // null filters path — encodeToJsonElement(null) serializes to JSON null
            withRequest { service.setFilters(id, 1, null) }
            val afterClear = withRequest { service.getCollectionTemplate(id, 1) }
            assertNotNull(afterClear)
        }

    @Test
    fun `setOrdering with value then null`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = seedTemplateMetadata()
            withRequest { service.saveTemplate(id, 1, CollectionTemplateInput()) }

            withRequest { service.setOrdering(id, 1, listOf(OrderingInput(field = "title"))) }
            val afterSet = withRequest { service.getCollectionTemplate(id, 1) }
            assertNotNull(afterSet?.ordering, "ordering should be set")

            withRequest { service.setOrdering(id, 1, null) }
            val afterClear = withRequest { service.getCollectionTemplate(id, 1) }
            assertNotNull(afterClear)
        }

    @Test
    fun `setAttributes replaces attributes and seeds workflows`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = seedTemplateMetadata()
            seedWorkflow("wf-auto")
            withRequest {
                service.saveTemplate(
                    id,
                    1,
                    CollectionTemplateInput(
                        attributes = listOf(
                            TemplateAttributeInput(
                                key = "old",
                                name = "Old",
                                description = "",
                                type = AttributeType.STRING,
                                ui = AttributeUiType.INPUT,
                            ),
                        ),
                    ),
                )
            }

            withRequest {
                service.setAttributes(
                    id,
                    1,
                    listOf(
                        // has workflows and tools
                        TemplateAttributeInput(
                            key = "withWorkflow",
                            name = "With Workflow",
                            description = "",
                            type = AttributeType.STRING,
                            ui = AttributeUiType.INPUT,
                            tools = listOf(TemplateToolInput(name = "t")),
                            workflows = listOf(TemplateWorkflowInput(workflowId = "wf-auto", autoRun = true)),
                        ),
                        // no workflows (exercises the `?: emptyList()` flatMap branch)
                        TemplateAttributeInput(
                            key = "noWorkflow",
                            name = "No Workflow",
                            description = "",
                            type = AttributeType.STRING,
                            ui = AttributeUiType.INPUT,
                        ),
                    ),
                )
            }

            val attributes = withRequest { service.getCollectionTemplateAttributes(id, 1) }
            assertEquals(2, attributes.size)
            assertTrue(attributes.any { it.key == "withWorkflow" })
            assertTrue(attributes.any { it.key == "noWorkflow" })
            assertTrue(attributes.none { it.key == "old" }, "old attribute should be replaced")

            val workflows = withRequest { service.getCollectionTemplateAttributeWorkflows(id, 1, "withWorkflow") }
            assertEquals(1, workflows.size)
            assertEquals("wf-auto", workflows[0].workflowId)
            assertTrue(workflows[0].autoRun)
        }

    @Test
    fun `getCollectionTemplateAttributeWorkflows returns empty for key with no workflows`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = seedTemplateMetadata()
            withRequest {
                service.saveTemplate(
                    id,
                    1,
                    CollectionTemplateInput(
                        attributes = listOf(
                            TemplateAttributeInput(
                                key = "lonely",
                                name = "Lonely",
                                description = "",
                                type = AttributeType.STRING,
                                ui = AttributeUiType.INPUT,
                            ),
                        ),
                    ),
                )
            }

            val workflows = withRequest { service.getCollectionTemplateAttributeWorkflows(id, 1, "lonely") }
            assertTrue(workflows.isEmpty(), "attribute with no workflows returns empty list")
        }

    @Test
    fun `setAttributes with empty list clears all attributes`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = seedTemplateMetadata()
            withRequest {
                service.saveTemplate(
                    id,
                    1,
                    CollectionTemplateInput(
                        attributes = listOf(
                            TemplateAttributeInput(
                                key = "gone",
                                name = "Gone",
                                description = "",
                                type = AttributeType.STRING,
                                ui = AttributeUiType.INPUT,
                            ),
                        ),
                    ),
                )
            }

            withRequest { service.setAttributes(id, 1, emptyList()) }

            val attributes = withRequest { service.getCollectionTemplateAttributes(id, 1) }
            assertTrue(attributes.isEmpty(), "empty setAttributes clears everything")
        }
}
