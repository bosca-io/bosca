package bosca.content.metadata.graphql

import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.content.attributes.model.TemplateAttribute
import bosca.content.collection.model.CollectionTemplate
import bosca.content.collection.model.CollectionTemplateAttribute
import bosca.content.metadata.model.CollectionTemplateFilter
import bosca.content.metadata.model.CollectionTemplateFilters
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.CollectionTemplateService
import bosca.content.metadata.service.MetadataService
import bosca.content.ordering.Ordering
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectionTemplateControllerCoverageTest {

    private val service = mockk<CollectionTemplateService>()
    private val metadataService = mockk<MetadataService>()
    private val permissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val json = Json

    private val controller = CollectionTemplateController(service, metadataService, permissionEvaluator, json)
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
        configuration: kotlinx.serialization.json.JsonElement? = null,
        defaultAttributes: kotlinx.serialization.json.JsonElement? = null,
        filters: kotlinx.serialization.json.JsonElement = JsonObject(emptyMap()),
        ordering: kotlinx.serialization.json.JsonElement? = null,
    ) = CollectionTemplate(
        metadataId = metadataId,
        version = version,
        configuration = configuration,
        defaultAttributes = defaultAttributes,
        filters = filters,
        ordering = ordering,
    )

    @Test
    fun `configuration returns template configuration`() {
        val config = JsonPrimitive("config-value")
        val t = template(configuration = config)

        assertEquals(config, controller.configuration(t))
    }

    @Test
    fun `configuration returns null when absent`() {
        assertNull(controller.configuration(template(configuration = null)))
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
    fun `filters decodes filters json element`() {
        val filters = CollectionTemplateFilters(
            filters = listOf(CollectionTemplateFilter(filter = "type = 'x'", name = "byType"))
        )
        val encoded = json.encodeToJsonElement(CollectionTemplateFilters.serializer(), filters)
        val t = template(filters = encoded)

        val result = controller.filters(t)

        assertEquals(1, result.filters.size)
        assertEquals("type = 'x'", result.filters[0].filter)
        assertEquals("byType", result.filters[0].name)
    }

    @Test
    fun `ordering returns decoded list when present`() {
        val orderings = listOf(Ordering(field = "title"), Ordering(field = "created"))
        val encoded = json.encodeToJsonElement(ListSerializer(Ordering.serializer()), orderings)
        val t = template(ordering = encoded)

        val result = controller.ordering(t)

        assertEquals(orderings, result)
    }

    @Test
    fun `ordering returns null when absent`() {
        assertNull(controller.ordering(template(ordering = null)))
    }

    @Test
    fun `metadata returns null when metadata not found`() = runTest {
        val id = UUID.random()
        val t = template(metadataId = id, version = 2)

        coEvery { metadataService.getById(id, 2) } returns null

        assertNull(controller.metadata(authentication, t))
    }

    @Test
    fun `metadata returns metadata when allowed`() = runTest {
        val id = UUID.random()
        val t = template(metadataId = id, version = 4)
        val md = metadata(id)

        coEvery { metadataService.getById(id, 4) } returns md
        coEvery { permissionEvaluator.verifyAllowed(authentication, md, PermissionAction.VIEW) } returns Unit

        assertEquals(md, controller.metadata(authentication, t))
    }

    @Test
    fun `metadata throws when not allowed`() = runTest {
        val id = UUID.random()
        val t = template(metadataId = id, version = 4)
        val md = metadata(id)

        coEvery { metadataService.getById(id, 4) } returns md
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, md, PermissionAction.VIEW)
        } throws IllegalStateException("unauthorized")

        assertFailsWith<IllegalStateException> {
            controller.metadata(authentication, t)
        }
    }

    @Test
    fun `attributes maps service results to template attributes`() = runTest {
        val id = UUID.random()
        val t = template(metadataId = id, version = 7)
        val attribute = CollectionTemplateAttribute(
            metadataId = id,
            version = 7,
            key = "color",
            name = "Color",
            description = "A color attribute",
            type = AttributeType.STRING,
            ui = AttributeUiType.INPUT,
        )

        coEvery { service.getCollectionTemplateAttributes(id, 7) } returns listOf(attribute)

        val result = controller.attributes(t)

        assertEquals(1, result.size)
        val wrapped: TemplateAttribute = result[0]
        assertEquals(attribute, wrapped.collectionAttribute)
        assertEquals("color", wrapped.key)
        assertTrue(wrapped.documentAttribute == null)
    }

    @Test
    fun `attributes returns empty list when service returns none`() = runTest {
        val id = UUID.random()
        val t = template(metadataId = id, version = 9)

        coEvery { service.getCollectionTemplateAttributes(id, 9) } returns emptyList()

        assertEquals(emptyList(), controller.attributes(t))
    }
}
