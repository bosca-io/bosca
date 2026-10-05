package bosca.content.attributes.graphql

import bosca.attributes.AttributeLocation
import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.content.attributes.model.TemplateAttribute
import bosca.content.attributes.model.TemplateTool
import bosca.content.collection.model.CollectionTemplateAttribute
import bosca.content.collection.model.CollectionTemplateAttributeWorkflow
import bosca.content.metadata.model.DataTemplateAttribute
import bosca.content.metadata.model.DataTemplateAttributeWorkflow
import bosca.content.metadata.model.DocumentTemplateAttribute
import bosca.content.metadata.model.DocumentTemplateAttributeWorkflow
import bosca.content.metadata.service.CollectionTemplateService
import bosca.content.metadata.service.DataTemplateService
import bosca.content.metadata.service.DocumentTemplateService
import bosca.content.tools.model.TemplateAttributeTool
import bosca.content.tools.service.TemplateAttributeToolService
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplateAttributeControllerCoverageTest {

    private val documentService = mockk<DocumentTemplateService>()
    private val collectionTemplateService = mockk<CollectionTemplateService>()
    private val dataTemplateService = mockk<DataTemplateService>()
    private val templateAttributeToolService = mockk<TemplateAttributeToolService>()

    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(UUIDSerializer())
        }
    }

    private val controller = TemplateAttributeController(
        documentService,
        collectionTemplateService,
        dataTemplateService,
        templateAttributeToolService,
        json
    )

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun documentAttribute(
        metadataId: UUID = UUID.random(),
        version: Int = 1,
        key: String = "doc-key",
        name: String = "Doc Name",
        description: String = "Doc Description",
        supplementaryKey: String? = "doc-supp",
        configuration: JsonElement? = null,
        type: AttributeType = AttributeType.STRING,
        ui: AttributeUiType = AttributeUiType.INPUT,
        list: Boolean = false,
        tools: JsonElement? = null,
    ) = DocumentTemplateAttribute(
        metadataId = metadataId,
        version = version,
        key = key,
        name = name,
        description = description,
        supplementaryKey = supplementaryKey,
        configuration = configuration,
        type = type,
        ui = ui,
        list = list,
        tools = tools,
    )

    private fun toolsElement(vararg tools: TemplateTool): JsonElement =
        json.encodeToJsonElement(ListSerializer(TemplateTool.serializer()), tools.toList())

    // --- Simple field resolvers -------------------------------------------

    @Test
    fun `name returns attribute name`() {
        val attribute = TemplateAttribute(documentAttribute = documentAttribute(name = "My Attr"))

        assertEquals("My Attr", controller.name(attribute))
    }

    @Test
    fun `key returns attribute key`() {
        val attribute = TemplateAttribute(documentAttribute = documentAttribute(key = "attr-key"))

        assertEquals("attr-key", controller.key(attribute))
    }

    @Test
    fun `description returns attribute description`() {
        val attribute = TemplateAttribute(documentAttribute = documentAttribute(description = "A description"))

        assertEquals("A description", controller.description(attribute))
    }

    @Test
    fun `supplementaryKey returns attribute supplementaryKey`() {
        val attribute = TemplateAttribute(documentAttribute = documentAttribute(supplementaryKey = "supp"))

        assertEquals("supp", controller.supplementaryKey(attribute))
    }

    @Test
    fun `supplementaryKey returns null when absent`() {
        val attribute = TemplateAttribute(documentAttribute = documentAttribute(supplementaryKey = null))

        assertNull(controller.supplementaryKey(attribute))
    }

    @Test
    fun `configuration returns attribute configuration`() {
        val config = json.parseToJsonElement("""{"foo":"bar"}""")
        val attribute = TemplateAttribute(documentAttribute = documentAttribute(configuration = config))

        assertEquals(config, controller.configuration(attribute))
    }

    @Test
    fun `configuration returns null when absent`() {
        val attribute = TemplateAttribute(documentAttribute = documentAttribute(configuration = null))

        assertNull(controller.configuration(attribute))
    }

    @Test
    fun `type returns attribute type`() {
        val attribute = TemplateAttribute(documentAttribute = documentAttribute(type = AttributeType.INT))

        assertEquals(AttributeType.INT, controller.type(attribute))
    }

    @Test
    fun `location returns ITEM for document attribute`() {
        val attribute = TemplateAttribute(documentAttribute = documentAttribute())

        assertEquals(AttributeLocation.ITEM, controller.location(attribute))
    }

    @Test
    fun `location returns collection attribute location`() {
        val collectionAttribute = CollectionTemplateAttribute(
            metadataId = UUID.random(),
            version = 1,
            key = "c-key",
            name = "C Name",
            description = "C Desc",
            type = AttributeType.STRING,
            ui = AttributeUiType.INPUT,
            location = AttributeLocation.RELATIONSHIP,
        )
        val attribute = TemplateAttribute(collectionAttribute = collectionAttribute)

        assertEquals(AttributeLocation.RELATIONSHIP, controller.location(attribute))
    }

    @Test
    fun `ui returns attribute ui type`() {
        val attribute = TemplateAttribute(documentAttribute = documentAttribute(ui = AttributeUiType.TEXTAREA))

        assertEquals(AttributeUiType.TEXTAREA, controller.ui(attribute))
    }

    @Test
    fun `list returns attribute list flag`() {
        val attribute = TemplateAttribute(documentAttribute = documentAttribute(list = true))

        assertTrue(controller.list(attribute))
    }

    // --- tools resolver ---------------------------------------------------

    @Test
    fun `tools returns null when attribute has no tools`() = runTest {
        val attribute = TemplateAttribute(documentAttribute = documentAttribute(tools = null))

        assertNull(controller.tools(attribute))
    }

    @Test
    fun `tools returns tool unchanged when id is null`() = runTest {
        val tool = TemplateTool(id = null, name = "Inline", query = "q", resultPath = "r")
        val attribute = TemplateAttribute(documentAttribute = documentAttribute(tools = toolsElement(tool)))

        val result = controller.tools(attribute)

        assertEquals(1, result?.size)
        assertEquals(tool, result?.get(0))
    }

    @Test
    fun `tools enriches tool from defined tool when id resolves`() = runTest {
        val toolId = UUID.random()
        val tool = TemplateTool(id = toolId, name = "Old", description = "Old Desc", query = "old-q", resultPath = "old-r")
        val defined = TemplateAttributeTool(
            id = toolId,
            key = "defined-key",
            name = "Defined Name",
            description = "Defined Desc",
            query = "defined-q",
            resultPath = "defined-r",
        )
        coEvery { templateAttributeToolService.get(toolId) } returns defined

        val attribute = TemplateAttribute(documentAttribute = documentAttribute(tools = toolsElement(tool)))
        val result = controller.tools(attribute)

        val enriched = result?.get(0)
        assertEquals(toolId, enriched?.id)
        assertEquals("Defined Name", enriched?.name)
        assertEquals("Defined Desc", enriched?.description)
        assertEquals("defined-q", enriched?.query)
        assertEquals("defined-r", enriched?.resultPath)
    }

    @Test
    fun `tools returns original tool when defined tool not found`() = runTest {
        val toolId = UUID.random()
        val tool = TemplateTool(id = toolId, name = "Original", query = "orig-q", resultPath = "orig-r")
        coEvery { templateAttributeToolService.get(toolId) } returns null

        val attribute = TemplateAttribute(documentAttribute = documentAttribute(tools = toolsElement(tool)))
        val result = controller.tools(attribute)

        assertEquals(tool, result?.get(0))
    }

    // --- workflows resolver ----------------------------------------------

    @Test
    fun `workflows resolves document attribute workflows`() = runTest {
        val metadataId = UUID.random()
        val attribute = TemplateAttribute(
            documentAttribute = documentAttribute(metadataId = metadataId, version = 2, key = "wf-key")
        )
        coEvery {
            documentService.getTemplateAttributeWorkflows(metadataId, 2, "wf-key")
        } returns listOf(
            DocumentTemplateAttributeWorkflow(
                metadataId = metadataId,
                version = 2,
                key = "wf-key",
                workflowId = "wf-doc",
                autoRun = true,
            )
        )

        val result = controller.workflows(attribute)

        assertEquals(1, result.size)
        assertEquals("wf-doc", result[0].workflowId)
        assertTrue(result[0].autoRun)
    }

    @Test
    fun `workflows resolves collection attribute workflows`() = runTest {
        val metadataId = UUID.random()
        val collectionAttribute = CollectionTemplateAttribute(
            metadataId = metadataId,
            version = 3,
            key = "col-key",
            name = "C",
            description = "C Desc",
            type = AttributeType.STRING,
            ui = AttributeUiType.INPUT,
        )
        val attribute = TemplateAttribute(collectionAttribute = collectionAttribute)
        coEvery {
            collectionTemplateService.getCollectionTemplateAttributeWorkflows(metadataId, 3, "col-key")
        } returns listOf(
            CollectionTemplateAttributeWorkflow(
                metadataId = metadataId,
                version = 3,
                key = "col-key",
                workflowId = "wf-col",
                autoRun = false,
            )
        )

        val result = controller.workflows(attribute)

        assertEquals(1, result.size)
        assertEquals("wf-col", result[0].workflowId)
        assertEquals(false, result[0].autoRun)
    }

    @Test
    fun `workflows resolves data attribute workflows`() = runTest {
        val metadataId = UUID.random()
        val dataAttribute = DataTemplateAttribute(
            metadataId = metadataId,
            version = 4,
            key = "data-key",
            name = "D",
            description = "D Desc",
        )
        val attribute = TemplateAttribute(dataAttribute = dataAttribute)
        coEvery {
            dataTemplateService.getTemplateAttributeWorkflows(metadataId, 4, "data-key")
        } returns listOf(
            DataTemplateAttributeWorkflow(
                metadataId = metadataId,
                version = 4,
                key = "data-key",
                workflowId = "wf-data",
                autoRun = true,
            )
        )

        val result = controller.workflows(attribute)

        assertEquals(1, result.size)
        assertEquals("wf-data", result[0].workflowId)
        assertTrue(result[0].autoRun)
    }

    @Test
    fun `workflows returns empty list when no backing attribute produces workflows`() = runTest {
        val attribute = TemplateAttribute()

        val result = controller.workflows(attribute)

        assertTrue(result.isEmpty())
    }
}
