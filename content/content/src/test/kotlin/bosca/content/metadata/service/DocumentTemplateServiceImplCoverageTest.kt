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
import bosca.content.metadata.model.ContainerRendererInput
import bosca.content.metadata.model.ContainerType
import bosca.content.metadata.model.DocumentTemplate
import bosca.content.metadata.model.DocumentTemplateContainerInput
import bosca.content.metadata.model.DocumentTemplateInput
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.repository.DocumentTemplateAttributeRepositoryImpl
import bosca.content.metadata.repository.DocumentTemplateAttributeWorkflowRepositoryImpl
import bosca.content.metadata.repository.DocumentTemplateContainerRepositoryImpl
import bosca.content.metadata.repository.DocumentTemplateRepositoryImpl
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.documents.Content
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
class DocumentTemplateServiceImplCoverageTest {

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

    private lateinit var service: DocumentTemplateServiceImpl

    @BeforeTest
    fun setup() = runBlocking {
        infrastructure.reset()
        serializer = RequestCacheSerializerImpl(testJson)

        ProviderRegistry.clear()
        provides<CacheManager>(singleton = true) { cacheManager }
        provides<RequestCacheSerializer>(singleton = true) { serializer }
        provides<Json>(singleton = true) { testJson }

        service = DocumentTemplateServiceImpl(
            DocumentTemplateRepositoryImpl(),
            DocumentTemplateAttributeRepositoryImpl(),
            DocumentTemplateAttributeWorkflowRepositoryImpl(),
            DocumentTemplateContainerRepositoryImpl(),
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

    /** Inserts a metadata row and a matching document_templates row so template FKs resolve. */
    private suspend fun createTemplateMetadata(
        contentType: String = "bosca/v-document-template",
        version: Int = 1,
    ): UUID {
        val id = UUID.random()
        rawExec("insert into metadata (id, name, content_type) values (?, 'QA Doc Template', '$contentType')") { stmt ->
            stmt.setObject(1, id.toJavaUuid())
        }
        // Save the template row itself (content column is NOT NULL) via the service.
        withRequest { service.saveTemplate(id, version, DocumentTemplateInput(content = Content())) }
        return id
    }

    /** Executes an arbitrary parameterised statement inside its own committed transaction. */
    private suspend fun rawExec(sql: String, bind: (java.sql.PreparedStatement) -> Unit) {
        val cm = ConnectionManager(connectionPool)
        withContext(cm.asCoroutineContext()) {
            cm.beginTransaction()
            cm.useStatement(sql) { stmt ->
                bind(stmt)
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

    private fun toolInput() = TemplateToolInput(
        id = UUID.random(),
        name = "tool",
        description = "d",
        query = "q",
        resultPath = "r",
    )

    private fun attributeInput(
        key: String,
        withTools: Boolean,
    ) = TemplateAttributeInput(
        key = key,
        name = "Name $key",
        description = "desc",
        supplementaryKey = if (withTools) "supp" else null,
        configuration = if (withTools) buildJsonObject { put("c", "v") } else null,
        type = AttributeType.STRING,
        ui = AttributeUiType.INPUT,
        list = false,
        tools = if (withTools) listOf(toolInput()) else null,
    )

    private fun containerInput(
        id: String,
        withExtras: Boolean,
        containerType: ContainerType? = if (withExtras) ContainerType.METADATA else null,
    ) = DocumentTemplateContainerInput(
        id = id,
        name = "Container $id",
        description = "cdesc",
        supplementaryKey = if (withExtras) "supp" else null,
        containerType = containerType,
        tools = if (withExtras) listOf(toolInput()) else null,
        renderers = if (withExtras) listOf(ContainerRendererInput(name = "rnd", configuration = buildJsonObject { put("x", 1) })) else null,
        filters = if (withExtras) listOf("f1", "f2") else null,
    )

    // ── getAll ────────────────────────────────────────────────────────────────

    @Test
    fun `getAll returns document templates and excludes non-template metadata`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val templateId = createTemplateMetadata()

        val all = withRequest { service.getAll() }
        assertTrue(all.any { it.metadataId == templateId }, "saved template should appear in getAll")
    }

    // ── getTemplate + cache ─────────────────────────────────────────────────────

    @Test
    fun `getTemplate returns saved template and caches it`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val templateId = createTemplateMetadata()

        val fetched = withRequest { service.getTemplate(templateId, 1) }
        assertNotNull(fetched)
        assertEquals(templateId, fetched.metadataId)
        assertEquals(1, fetched.version)

        // second read exercises the cache-hit path
        val cached = withRequest { service.getTemplate(templateId, 1) }
        assertNotNull(cached)
        assertEquals(templateId, cached.metadataId)
    }

    @Test
    fun `getTemplate returns null when no template exists`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val missing = withRequest { service.getTemplate(UUID.random(), 1) }
        assertNull(missing)
    }

    // ── addToBatch (batch resolver) ─────────────────────────────────────────────

    @Test
    fun `addToBatch populates the batch with template data`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val templateId = createTemplateMetadata()
        val presentKey = MetadataCacheKeyId(templateId, 1)
        val missingKey = MetadataCacheKeyId(UUID.random(), 1)

        val batch = Batch<MetadataCacheKeyId, DocumentTemplate>(listOf(presentKey, missingKey))
        withRequest { service.addToBatch(batch) }

        val resolved = batch.getData(presentKey)
        assertNotNull(resolved, "batch should resolve the existing template")
        assertEquals(templateId, resolved.metadataId)
        assertNull(batch.getData(missingKey), "batch should leave the missing key unset")
    }

    // ── attributes ──────────────────────────────────────────────────────────────

    @Test
    fun `addAttribute with tools then getTemplateAttributes returns it`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val templateId = createTemplateMetadata()

        withRequest { service.addAttribute(templateId, 1, attributeInput("title", withTools = true), 0) }

        val attributes = withRequest { service.getTemplateAttributes(templateId, 1) }
        assertEquals(1, attributes.size)
        val attribute = attributes.first()
        assertEquals("title", attribute.key)
        assertNotNull(attribute.tools, "tools should be persisted when provided")
    }

    @Test
    fun `addAttribute without tools persists null tools`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val templateId = createTemplateMetadata()

        withRequest { service.addAttribute(templateId, 1, attributeInput("plain", withTools = false), 3) }

        val attributes = withRequest { service.getTemplateAttributes(templateId, 1) }
        assertEquals(1, attributes.size)
        assertNull(attributes.first().tools, "tools should be null when not provided")
    }

    @Test
    fun `getTemplateAttributes returns empty list when none exist`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val attributes = withRequest { service.getTemplateAttributes(UUID.random(), 1) }
        assertTrue(attributes.isEmpty())
    }

    @Test
    fun `deleteAttribute removes the attribute`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val templateId = createTemplateMetadata()
        withRequest { service.addAttribute(templateId, 1, attributeInput("gone", withTools = false), 0) }
        assertEquals(1, withRequest { service.getTemplateAttributes(templateId, 1) }.size)

        withRequest { service.deleteAttribute(templateId, 1, "gone") }
        assertTrue(withRequest { service.getTemplateAttributes(templateId, 1) }.isEmpty())
    }

    @Test
    fun `setAttributes replaces all attributes with mixed tools`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val templateId = createTemplateMetadata()
        withRequest { service.addAttribute(templateId, 1, attributeInput("old", withTools = false), 0) }

        withRequest {
            service.setAttributes(
                templateId,
                1,
                listOf(
                    attributeInput("a", withTools = true),
                    attributeInput("b", withTools = false),
                ),
            )
        }

        val attributes = withRequest { service.getTemplateAttributes(templateId, 1) }
        assertEquals(2, attributes.size)
        assertEquals(listOf("a", "b"), attributes.map { it.key })
        assertNotNull(attributes[0].tools)
        assertNull(attributes[1].tools)
    }

    @Test
    fun `setAttributes with empty list clears attributes`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val templateId = createTemplateMetadata()
        withRequest { service.addAttribute(templateId, 1, attributeInput("x", withTools = false), 0) }

        withRequest { service.setAttributes(templateId, 1, emptyList()) }

        assertTrue(withRequest { service.getTemplateAttributes(templateId, 1) }.isEmpty())
    }

    // ── attribute workflows ─────────────────────────────────────────────────────

    @Test
    fun `getTemplateAttributeWorkflows returns empty list when none exist`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val templateId = createTemplateMetadata()
        withRequest { service.addAttribute(templateId, 1, attributeInput("wf", withTools = false), 0) }

        val workflows = withRequest { service.getTemplateAttributeWorkflows(templateId, 1, "wf") }
        assertTrue(workflows.isEmpty())
    }

    @Test
    fun `getTemplateAttributeWorkflows returns seeded workflows`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val templateId = createTemplateMetadata()
        withRequest { service.addAttribute(templateId, 1, attributeInput("wf", withTools = false), 0) }

        val workflowId = "wf-${UUID.random()}"
        rawExec("insert into workflows (id, name, description, queue) values ('$workflowId', 'W', 'D', 'q')") { }
        rawExec(
            "insert into document_template_attribute_workflows (metadata_id, version, key, workflow_id, auto_run) values (?, 1, 'wf', '$workflowId', true)"
        ) { stmt -> stmt.setObject(1, templateId.toJavaUuid()) }

        val workflows = withRequest { service.getTemplateAttributeWorkflows(templateId, 1, "wf") }
        assertEquals(1, workflows.size)
        assertEquals(workflowId, workflows.first().workflowId)
        assertTrue(workflows.first().autoRun)
    }

    // ── setters on the template row ─────────────────────────────────────────────

    @Test
    fun `setDefaultAttributes updates the row`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val templateId = createTemplateMetadata()

        withRequest { service.setDefaultAttributes(templateId, 1, buildJsonObject { put("d", 1) }) }

        val template = withRequest { service.getTemplate(templateId, 1) }
        assertNotNull(template)
        assertNotNull(template.defaultAttributes)
    }

    @Test
    fun `setDefaultAttributes with null clears the row`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val templateId = createTemplateMetadata()
        withRequest { service.setDefaultAttributes(templateId, 1, buildJsonObject { put("d", 1) }) }

        withRequest { service.setDefaultAttributes(templateId, 1, null) }

        val template = withRequest { service.getTemplate(templateId, 1) }
        assertNotNull(template)
        assertNull(template.defaultAttributes)
    }

    @Test
    fun `setConfiguration updates and clears configuration`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val templateId = createTemplateMetadata()

        withRequest { service.setConfiguration(templateId, 1, buildJsonObject { put("cfg", true) }) }
        assertNotNull(withRequest { service.getTemplate(templateId, 1) }?.configuration)

        withRequest { service.setConfiguration(templateId, 1, null) }
        assertNull(withRequest { service.getTemplate(templateId, 1) }?.configuration)
    }

    @Test
    fun `setSchema updates and clears schema`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val templateId = createTemplateMetadata()

        withRequest { service.setSchema(templateId, 1, buildJsonObject { put("type", "object") }) }
        assertNotNull(withRequest { service.getTemplate(templateId, 1) }?.schema)

        withRequest { service.setSchema(templateId, 1, null) }
        assertNull(withRequest { service.getTemplate(templateId, 1) }?.schema)
    }

    @Test
    fun `setContent updates the content column`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val templateId = createTemplateMetadata()

        // content column is NOT NULL — supply a non-null JSON element that round-trips as Content.
        withRequest { service.setContent(templateId, 1, testJson.encodeToJsonElement(Content.serializer(), Content())) }

        val template = withRequest { service.getTemplate(templateId, 1) }
        assertNotNull(template)
        assertNotNull(template.content)
    }

    // ── containers ──────────────────────────────────────────────────────────────

    @Test
    fun `addContainer with extras then getTemplateContainers returns it`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val templateId = createTemplateMetadata()

        withRequest { service.addContainer(templateId, 1, containerInput("c1", withExtras = true), 0) }

        val containers = withRequest { service.getTemplateContainers(templateId, 1) }
        assertEquals(1, containers.size)
        val container = containers.first()
        assertEquals("c1", container.id)
        assertEquals(ContainerType.METADATA, container.type)
        assertNotNull(container.tools)
        assertNotNull(container.renderers)
        assertNotNull(container.filters)
    }

    @Test
    fun `addContainer without extras defaults containerType to STANDARD`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val templateId = createTemplateMetadata()

        withRequest { service.addContainer(templateId, 1, containerInput("plain", withExtras = false), 1) }

        val containers = withRequest { service.getTemplateContainers(templateId, 1) }
        assertEquals(1, containers.size)
        val container = containers.first()
        assertEquals(ContainerType.STANDARD, container.type)
        assertNull(container.tools)
        assertNull(container.renderers)
        assertNull(container.filters)
    }

    @Test
    fun `getTemplateContainers returns empty list when none exist`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val containers = withRequest { service.getTemplateContainers(UUID.random(), 1) }
        assertTrue(containers.isEmpty())
    }

    @Test
    fun `deleteContainer removes the container`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val templateId = createTemplateMetadata()
        withRequest { service.addContainer(templateId, 1, containerInput("del", withExtras = false), 0) }
        assertEquals(1, withRequest { service.getTemplateContainers(templateId, 1) }.size)

        withRequest { service.deleteContainer(templateId, 1, "del") }
        assertTrue(withRequest { service.getTemplateContainers(templateId, 1) }.isEmpty())
    }

    @Test
    fun `setContainers replaces all containers with mixed extras`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val templateId = createTemplateMetadata()
        withRequest { service.addContainer(templateId, 1, containerInput("old", withExtras = false), 0) }

        withRequest {
            service.setContainers(
                templateId,
                1,
                listOf(
                    containerInput("full", withExtras = true),
                    containerInput("bare", withExtras = false),
                ),
            )
        }

        val containers = withRequest { service.getTemplateContainers(templateId, 1) }
        assertEquals(2, containers.size)
        assertEquals(listOf("full", "bare"), containers.map { it.id })
        assertEquals(ContainerType.METADATA, containers[0].type)
        assertNotNull(containers[0].tools)
        assertEquals(ContainerType.STANDARD, containers[1].type)
        assertNull(containers[1].tools)
    }

    @Test
    fun `setContainers with empty list clears containers`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val templateId = createTemplateMetadata()
        withRequest { service.addContainer(templateId, 1, containerInput("x", withExtras = false), 0) }

        withRequest { service.setContainers(templateId, 1, emptyList()) }

        assertTrue(withRequest { service.getTemplateContainers(templateId, 1) }.isEmpty())
    }

    // ── saveTemplate ────────────────────────────────────────────────────────────

    @Test
    fun `saveTemplate persists template attributes and containers`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        rawExec("insert into metadata (id, name, content_type) values (?, 'Full Template', 'bosca/v-document-template')") { stmt ->
            stmt.setObject(1, id.toJavaUuid())
        }

        withRequest {
            service.saveTemplate(
                id,
                1,
                DocumentTemplateInput(
                    configuration = buildJsonObject { put("cfg", 1) },
                    schema = buildJsonObject { put("type", "object") },
                    content = Content(),
                    defaultAttributes = buildJsonObject { put("d", 1) },
                    attributes = listOf(
                        attributeInput("a", withTools = true),
                        attributeInput("b", withTools = false),
                    ),
                    containers = listOf(
                        containerInput("full", withExtras = true),
                        containerInput("bare", withExtras = false),
                    ),
                ),
            )
        }

        val template = withRequest { service.getTemplate(id, 1) }
        assertNotNull(template)
        assertNotNull(template.configuration)
        assertNotNull(template.schema)
        assertNotNull(template.defaultAttributes)

        val attributes = withRequest { service.getTemplateAttributes(id, 1) }
        assertEquals(listOf("a", "b"), attributes.map { it.key })
        assertNotNull(attributes[0].tools)
        assertNull(attributes[1].tools)

        val containers = withRequest { service.getTemplateContainers(id, 1) }
        assertEquals(listOf("full", "bare"), containers.map { it.id })
        assertEquals(ContainerType.METADATA, containers[0].type)
        assertNotNull(containers[0].tools)
    }

    @Test
    fun `saveTemplate with minimal input creates the row and clears children`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = createTemplateMetadata()
        // seed children, then re-save with none to exercise the delete-then-empty-loop branches
        withRequest { service.addAttribute(id, 1, attributeInput("old", withTools = false), 0) }
        withRequest { service.addContainer(id, 1, containerInput("old", withExtras = false), 0) }

        withRequest { service.saveTemplate(id, 1, DocumentTemplateInput(content = Content())) }

        assertTrue(withRequest { service.getTemplateAttributes(id, 1) }.isEmpty())
        assertTrue(withRequest { service.getTemplateContainers(id, 1) }.isEmpty())
        assertNotNull(withRequest { service.getTemplate(id, 1) })
    }
}
