package bosca.content.collection.graphql

import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.attributes.TemplateAttributeInput
import bosca.content.attributes.model.TemplateAttribute
import bosca.content.collection.model.CollectionTemplateMutation
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.CollectionTemplateService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CollectionTemplateMutationControllerTest {

    private val templateService = mockk<CollectionTemplateService>()
    private val permissionEvaluator = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val authContext = mockk<AuthenticationContext>(relaxed = true)
    private val controller = CollectionTemplateMutationController(templateService, permissionEvaluator)

    private val metadata = Metadata(
        id = UUID.random(),
        name = "Template",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "draft",
        version = 2
    )
    private val mutation = CollectionTemplateMutation(metadata)

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `addAttribute delegates to service and returns metadata`() = runTest {
        val attribute = TemplateAttributeInput(
            key = "title",
            name = "Title",
            description = "The title attribute",
            type = AttributeType.STRING,
            ui = AttributeUiType.INPUT
        )
        coEvery { templateService.addAttribute(metadata.id, metadata.version, attribute, 0) } returns TemplateAttribute()

        val result = controller.addAttribute(authContext, mutation, attribute, 0)

        assertEquals(metadata, result)
        coVerify { templateService.addAttribute(metadata.id, metadata.version, attribute, 0) }
    }

    @Test
    fun `deleteAttribute delegates to service and returns metadata`() = runTest {
        coEvery { templateService.deleteAttribute(metadata.id, metadata.version, "title") } returns Unit

        val result = controller.deleteAttribute(authContext, mutation, "title")

        assertEquals(metadata, result)
        coVerify { templateService.deleteAttribute(metadata.id, metadata.version, "title") }
    }

    @Test
    fun `setAttributes delegates to service and returns metadata`() = runTest {
        val attributes = listOf(
            TemplateAttributeInput(key = "a", name = "A", description = "Attribute A", type = AttributeType.STRING, ui = AttributeUiType.INPUT),
            TemplateAttributeInput(key = "b", name = "B", description = "Attribute B", type = AttributeType.INT, ui = AttributeUiType.INPUT)
        )
        coEvery { templateService.setAttributes(metadata.id, metadata.version, attributes) } returns Unit

        val result = controller.setAttributes(authContext, mutation, attributes)

        assertEquals(metadata, result)
        coVerify { templateService.setAttributes(metadata.id, metadata.version, attributes) }
    }

    @Test
    fun `setConfiguration delegates to service and returns metadata`() = runTest {
        val config = JsonObject(mapOf("key" to JsonPrimitive("value")))
        coEvery { templateService.setConfiguration(metadata.id, metadata.version, config) } returns Unit

        val result = controller.setConfiguration(authContext, mutation, config)

        assertEquals(metadata, result)
        coVerify { templateService.setConfiguration(metadata.id, metadata.version, config) }
    }

    @Test
    fun `setDefaultAttributes delegates to service and returns metadata`() = runTest {
        val defaults = JsonObject(mapOf("color" to JsonPrimitive("red")))
        coEvery { templateService.setDefaultAttributes(metadata.id, metadata.version, defaults) } returns Unit

        val result = controller.setDefaultAttributes(authContext, mutation, defaults)

        assertEquals(metadata, result)
        coVerify { templateService.setDefaultAttributes(metadata.id, metadata.version, defaults) }
    }
}
