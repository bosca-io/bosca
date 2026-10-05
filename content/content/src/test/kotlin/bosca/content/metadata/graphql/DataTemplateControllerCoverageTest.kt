package bosca.content.metadata.graphql

import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.content.attributes.model.TemplateAttribute
import bosca.content.metadata.model.DataTemplate
import bosca.content.metadata.model.DataTemplateAttribute
import bosca.content.metadata.model.DataType
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.DataTemplateService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DataTemplateControllerCoverageTest {

    private val service = mockk<DataTemplateService>()
    private val metadataService = mockk<MetadataService>()
    private val permissionEvaluator = mockk<MetadataPermissionEvaluator>()

    private val controller = DataTemplateController(service, metadataService, permissionEvaluator)
    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun metadata(id: UUID) = Metadata(
        id = id,
        name = "Template",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "published"
    )

    private fun template(
        metadataId: UUID = UUID.random(),
        version: Int = 1,
        type: DataType = DataType.ATTRIBUTES,
        defaultAttributes: kotlinx.serialization.json.JsonElement? = null,
    ) = DataTemplate(
        metadataId = metadataId,
        version = version,
        type = type,
        defaultAttributes = defaultAttributes,
    )

    @Test
    fun `type returns template type`() {
        val t = template(type = DataType.ATTRIBUTES)

        assertEquals(DataType.ATTRIBUTES, controller.type(t))
    }

    @Test
    fun `defaultAttributes returns template default attributes`() {
        val defaults = JsonPrimitive("defaults-value")
        val t = template(defaultAttributes = defaults)

        assertEquals(defaults, controller.defaultAttributes(t))
    }

    @Test
    fun `defaultAttributes returns null when absent`() {
        assertNull(controller.defaultAttributes(template(defaultAttributes = null)))
    }

    @Test
    fun `attributes returns service template attributes`() = runTest {
        val id = UUID.random()
        val t = template(metadataId = id, version = 7)
        val attribute = DataTemplateAttribute(
            metadataId = id,
            version = 7,
            key = "color",
            name = "Color",
            description = "A color attribute",
            type = AttributeType.STRING,
            ui = AttributeUiType.INPUT,
        )
        val wrapped = TemplateAttribute(dataAttribute = attribute)

        coEvery { service.getTemplateAttributes(id, 7) } returns listOf(wrapped)

        val result = controller.attributes(t)

        assertEquals(1, result.size)
        assertEquals(wrapped, result[0])
    }

    @Test
    fun `attributes returns empty list when service returns none`() = runTest {
        val id = UUID.random()
        val t = template(metadataId = id, version = 9)

        coEvery { service.getTemplateAttributes(id, 9) } returns emptyList()

        assertEquals(emptyList(), controller.attributes(t))
    }

    @Test
    fun `metadata returns null when metadata not found`() = runTest {
        val id = UUID.random()
        val t = template(metadataId = id, version = 2)

        coEvery { metadataService.getById(id, 2) } returns null

        assertNull(controller.metadata(authentication, t))
    }

    @Test
    fun `metadata returns null when not allowed`() = runTest {
        val id = UUID.random()
        val t = template(metadataId = id, version = 4)
        val md = metadata(id)

        coEvery { metadataService.getById(id, 4) } returns md
        coEvery { permissionEvaluator.isAllowed(authentication, md, PermissionAction.VIEW) } returns false

        assertNull(controller.metadata(authentication, t))
    }

    @Test
    fun `metadata returns metadata when allowed`() = runTest {
        val id = UUID.random()
        val t = template(metadataId = id, version = 4)
        val md = metadata(id)

        coEvery { metadataService.getById(id, 4) } returns md
        coEvery { permissionEvaluator.isAllowed(authentication, md, PermissionAction.VIEW) } returns true

        assertEquals(md, controller.metadata(authentication, t))
    }

    @Test
    fun `metadata returns null with null authentication when not allowed`() = runTest {
        val id = UUID.random()
        val t = template(metadataId = id, version = 5)
        val md = metadata(id)

        coEvery { metadataService.getById(id, 5) } returns md
        coEvery { permissionEvaluator.isAllowed(null, md, PermissionAction.VIEW) } returns false

        assertNull(controller.metadata(null, t))
    }
}
