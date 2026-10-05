package bosca.content.metadata.graphql

import bosca.content.attributes.model.TemplateTool
import bosca.content.metadata.model.ContainerRenderer
import bosca.content.metadata.model.ContainerType
import bosca.content.metadata.model.DocumentTemplateAttributeWorkflow
import bosca.content.metadata.model.DocumentTemplateContainer
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
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DocumentTemplateContainerControllerCoverageTest {

    private val service = mockk<DocumentTemplateService>()
    private val templateAttributeToolService = mockk<TemplateAttributeToolService>()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
        serializersModule = SerializersModule {
            contextual(UUIDSerializer())
        }
    }

    private val controller = DocumentTemplateContainerController(service, templateAttributeToolService, json)

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun container(
        tools: JsonElement? = null,
        renderers: JsonElement? = null,
        filters: JsonElement? = null,
    ) = DocumentTemplateContainer(
        metadataId = UUID.random(),
        version = 1,
        id = "container-id",
        name = "Container Name",
        description = "Container Description",
        supplementaryKey = "supp-key",
        sort = 0,
        type = ContainerType.STANDARD,
        tools = tools,
        renderers = renderers,
        filters = filters,
    )

    @Test
    fun `id returns container id`() {
        val template = container()
        assertEquals("container-id", controller.id(template))
    }

    @Test
    fun `name returns container name`() {
        val template = container()
        assertEquals("Container Name", controller.name(template))
    }

    @Test
    fun `description returns container description`() {
        val template = container()
        assertEquals("Container Description", controller.description(template))
    }

    @Test
    fun `supplementaryKey returns container supplementary key`() {
        val template = container()
        assertEquals("supp-key", controller.supplementaryKey(template))
    }

    @Test
    fun `supplementaryKey returns null when absent`() {
        val template = DocumentTemplateContainer(
            metadataId = UUID.random(),
            version = 1,
            id = "id",
            name = "n",
            description = "d",
            supplementaryKey = null,
            type = ContainerType.BIBLE,
        )
        assertNull(controller.supplementaryKey(template))
    }

    @Test
    fun `type returns container type`() {
        val template = container()
        assertEquals(ContainerType.STANDARD, controller.type(template))
    }

    @Test
    fun `tools returns null when tools element is null`() = runTest {
        val template = container(tools = null)
        assertNull(controller.tools(template))
    }

    @Test
    fun `tools returns original tool when id is null`() = runTest {
        val tool = TemplateTool(id = null, name = "raw", description = "d", query = "q", resultPath = "r")
        val element = json.encodeToJsonElement(ListSerializer(TemplateTool.serializer()), listOf(tool))
        val template = container(tools = element)

        val result = controller.tools(template)

        assertEquals(listOf(tool), result)
    }

    @Test
    fun `tools resolves defined tool when id present and found`() = runTest {
        val toolId = UUID.random()
        val tool = TemplateTool(id = toolId, name = "old", description = "old-desc", query = "old-q", resultPath = "old-r")
        val element = json.encodeToJsonElement(ListSerializer(TemplateTool.serializer()), listOf(tool))
        val template = container(tools = element)

        val defined = TemplateAttributeTool(
            id = toolId,
            key = "key",
            name = "resolved-name",
            description = "resolved-desc",
            query = "resolved-q",
            resultPath = "resolved-r",
        )
        coEvery { templateAttributeToolService.get(toolId) } returns defined

        val result = controller.tools(template)

        assertEquals(
            listOf(
                tool.copy(
                    name = "resolved-name",
                    description = "resolved-desc",
                    query = "resolved-q",
                    resultPath = "resolved-r",
                )
            ),
            result
        )
    }

    @Test
    fun `tools keeps original tool when id present but not found`() = runTest {
        val toolId = UUID.random()
        val tool = TemplateTool(id = toolId, name = "old", description = "old-desc", query = "old-q", resultPath = "old-r")
        val element = json.encodeToJsonElement(ListSerializer(TemplateTool.serializer()), listOf(tool))
        val template = container(tools = element)

        coEvery { templateAttributeToolService.get(toolId) } returns null

        val result = controller.tools(template)

        assertEquals(listOf(tool), result)
    }

    @Test
    fun `renderers returns null when renderers element is null`() {
        val template = container(renderers = null)
        assertNull(controller.renderers(template))
    }

    @Test
    fun `renderers decodes list when present`() {
        val renderer = ContainerRenderer(
            name = "markdown",
            configuration = JsonObject(mapOf("theme" to JsonPrimitive("dark")))
        )
        val element = json.encodeToJsonElement(ListSerializer(ContainerRenderer.serializer()), listOf(renderer))
        val template = container(renderers = element)

        assertEquals(listOf(renderer), controller.renderers(template))
    }

    @Test
    fun `filters returns null when filters element is null`() {
        val template = container(filters = null)
        assertNull(controller.filters(template))
    }

    @Test
    fun `filters decodes list when present`() {
        val element = json.encodeToJsonElement(ListSerializer(String.serializer()), listOf("a", "b"))
        val template = container(filters = element)

        assertEquals(listOf("a", "b"), controller.filters(template))
    }

    @Test
    fun `workflows maps service results to template workflows`() = runTest {
        val metadataId = UUID.random()
        val template = DocumentTemplateContainer(
            metadataId = metadataId,
            version = 3,
            id = "wf-container",
            name = "n",
            description = "d",
            type = ContainerType.METADATA,
        )

        val attributeWorkflows = listOf(
            DocumentTemplateAttributeWorkflow(
                metadataId = metadataId,
                version = 3,
                key = "wf-container",
                workflowId = "workflow-1",
                autoRun = true,
            ),
            DocumentTemplateAttributeWorkflow(
                metadataId = metadataId,
                version = 3,
                key = "wf-container",
                workflowId = "workflow-2",
                autoRun = false,
            ),
        )
        coEvery { service.getTemplateAttributeWorkflows(metadataId, 3, "wf-container") } returns attributeWorkflows

        val result = controller.workflows(template)

        assertEquals(2, result.size)
        assertEquals("workflow-1", result[0].workflowId)
        assertEquals(true, result[0].autoRun)
        assertEquals("workflow-2", result[1].workflowId)
        assertEquals(false, result[1].autoRun)
    }

    @Test
    fun `workflows returns empty list when service returns empty`() = runTest {
        val metadataId = UUID.random()
        val template = DocumentTemplateContainer(
            metadataId = metadataId,
            version = 1,
            id = "empty",
            name = "n",
            description = "d",
            type = ContainerType.STANDARD,
        )
        coEvery { service.getTemplateAttributeWorkflows(metadataId, 1, "empty") } returns emptyList()

        assertEquals(emptyList(), controller.workflows(template))
    }
}
