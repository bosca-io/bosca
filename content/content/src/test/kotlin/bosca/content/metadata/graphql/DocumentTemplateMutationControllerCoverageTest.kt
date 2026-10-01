package bosca.content.metadata.graphql

import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.attributes.TemplateAttributeInput
import bosca.content.metadata.model.ContainerType
import bosca.content.metadata.model.DocumentTemplateContainerInput
import bosca.content.metadata.model.DocumentTemplateMutation
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.DocumentTemplateService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocumentTemplateMutationControllerCoverageTest {

    private val templateService = mockk<DocumentTemplateService>()
    private val permissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val controller = DocumentTemplateMutationController(templateService, permissionEvaluator)
    private val authentication = mockk<AuthenticationContext>(relaxed = true)

    private val metadata = Metadata(
        id = UUID.random(),
        name = "test-template",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "draft",
        version = 2
    )
    private val mutation = DocumentTemplateMutation(metadata)

    private val attribute = TemplateAttributeInput(
        key = "field-a",
        name = "Field A",
        description = "A field",
        type = AttributeType.STRING,
        ui = AttributeUiType.INPUT
    )

    private val container = DocumentTemplateContainerInput(
        id = "container-a",
        name = "Container A",
        description = "A container",
        containerType = ContainerType.STANDARD
    )

    init {
        coJustRun { permissionEvaluator.verifyAllowed(any(), any<Metadata>(), any()) }
    }

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    // ----- addAttribute -----

    @Test
    fun `addAttribute verifies edit permission then delegates and returns metadata`() = runTest {
        coJustRun { templateService.addAttribute(metadata.id, metadata.version, attribute, 5) }

        val result = controller.addAttribute(authentication, mutation, attribute, 5)

        assertEquals(metadata, result)
        coVerify { permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT) }
        coVerify { templateService.addAttribute(metadata.id, metadata.version, attribute, 5) }
    }

    @Test
    fun `addAttribute propagates permission denial and skips service`() = runTest {
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller.addAttribute(authentication, mutation, attribute, 5)
        }
        coVerify(exactly = 0) { templateService.addAttribute(any(), any(), any(), any()) }
    }

    // ----- deleteAttribute -----

    @Test
    fun `deleteAttribute verifies edit permission then delegates and returns metadata`() = runTest {
        coJustRun { templateService.deleteAttribute(metadata.id, metadata.version, "field-a") }

        val result = controller.deleteAttribute(authentication, mutation, "field-a")

        assertEquals(metadata, result)
        coVerify { permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT) }
        coVerify { templateService.deleteAttribute(metadata.id, metadata.version, "field-a") }
    }

    @Test
    fun `deleteAttribute propagates permission denial and skips service`() = runTest {
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller.deleteAttribute(authentication, mutation, "field-a")
        }
        coVerify(exactly = 0) { templateService.deleteAttribute(any(), any(), any()) }
    }

    // ----- setAttributes -----

    @Test
    fun `setAttributes verifies edit permission then delegates and returns metadata`() = runTest {
        val attributes = listOf(attribute)
        coJustRun { templateService.setAttributes(metadata.id, metadata.version, attributes) }

        val result = controller.setAttributes(authentication, mutation, attributes)

        assertEquals(metadata, result)
        coVerify { permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT) }
        coVerify { templateService.setAttributes(metadata.id, metadata.version, attributes) }
    }

    @Test
    fun `setAttributes with empty list delegates to service`() = runTest {
        val attributes = emptyList<TemplateAttributeInput>()
        coJustRun { templateService.setAttributes(metadata.id, metadata.version, attributes) }

        val result = controller.setAttributes(authentication, mutation, attributes)

        assertEquals(metadata, result)
        coVerify { templateService.setAttributes(metadata.id, metadata.version, attributes) }
    }

    @Test
    fun `setAttributes propagates permission denial and skips service`() = runTest {
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller.setAttributes(authentication, mutation, listOf(attribute))
        }
        coVerify(exactly = 0) { templateService.setAttributes(any(), any(), any()) }
    }

    // ----- setConfiguration -----

    @Test
    fun `setConfiguration verifies edit permission then delegates and returns metadata`() = runTest {
        val configuration = buildJsonObject { put("k", "v") }
        coJustRun { templateService.setConfiguration(metadata.id, metadata.version, configuration) }

        val result = controller.setConfiguration(authentication, mutation, configuration)

        assertEquals(metadata, result)
        coVerify { permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT) }
        coVerify { templateService.setConfiguration(metadata.id, metadata.version, configuration) }
    }

    @Test
    fun `setConfiguration propagates permission denial and skips service`() = runTest {
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller.setConfiguration(authentication, mutation, JsonPrimitive("v"))
        }
        coVerify(exactly = 0) { templateService.setConfiguration(any(), any(), any()) }
    }

    // ----- setDefaultAttributes -----

    @Test
    fun `setDefaultAttributes verifies edit permission then delegates and returns metadata`() = runTest {
        val attributes = buildJsonObject { put("a", 1) }
        coJustRun { templateService.setDefaultAttributes(metadata.id, metadata.version, attributes) }

        val result = controller.setDefaultAttributes(authentication, mutation, attributes)

        assertEquals(metadata, result)
        coVerify { permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT) }
        coVerify { templateService.setDefaultAttributes(metadata.id, metadata.version, attributes) }
    }

    @Test
    fun `setDefaultAttributes propagates permission denial and skips service`() = runTest {
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller.setDefaultAttributes(authentication, mutation, JsonPrimitive("v"))
        }
        coVerify(exactly = 0) { templateService.setDefaultAttributes(any(), any(), any()) }
    }

    // ----- setSchema -----

    @Test
    fun `setSchema verifies edit permission then delegates and returns metadata`() = runTest {
        val schema = buildJsonObject { put("type", "object") }
        coJustRun { templateService.setSchema(metadata.id, metadata.version, schema) }

        val result = controller.setSchema(authentication, mutation, schema)

        assertEquals(metadata, result)
        coVerify { permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT) }
        coVerify { templateService.setSchema(metadata.id, metadata.version, schema) }
    }

    @Test
    fun `setSchema propagates permission denial and skips service`() = runTest {
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller.setSchema(authentication, mutation, JsonPrimitive("v"))
        }
        coVerify(exactly = 0) { templateService.setSchema(any(), any(), any()) }
    }

    // ----- addContainer -----

    @Test
    fun `addContainer verifies edit permission then delegates and returns metadata`() = runTest {
        coJustRun { templateService.addContainer(metadata.id, metadata.version, container, 3) }

        val result = controller.addContainer(authentication, mutation, container, 3)

        assertEquals(metadata, result)
        coVerify { permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT) }
        coVerify { templateService.addContainer(metadata.id, metadata.version, container, 3) }
    }

    @Test
    fun `addContainer propagates permission denial and skips service`() = runTest {
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller.addContainer(authentication, mutation, container, 3)
        }
        coVerify(exactly = 0) { templateService.addContainer(any(), any(), any(), any()) }
    }

    // ----- deleteContainer -----

    @Test
    fun `deleteContainer verifies edit permission then delegates and returns metadata`() = runTest {
        coJustRun { templateService.deleteContainer(metadata.id, metadata.version, "container-a") }

        val result = controller.deleteContainer(authentication, mutation, "container-a")

        assertEquals(metadata, result)
        coVerify { permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT) }
        coVerify { templateService.deleteContainer(metadata.id, metadata.version, "container-a") }
    }

    @Test
    fun `deleteContainer propagates permission denial and skips service`() = runTest {
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller.deleteContainer(authentication, mutation, "container-a")
        }
        coVerify(exactly = 0) { templateService.deleteContainer(any(), any(), any()) }
    }

    // ----- setContainers -----

    @Test
    fun `setContainers verifies edit permission then delegates and returns metadata`() = runTest {
        val containers = listOf(container)
        coJustRun { templateService.setContainers(metadata.id, metadata.version, containers) }

        val result = controller.setContainers(authentication, mutation, containers)

        assertEquals(metadata, result)
        coVerify { permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT) }
        coVerify { templateService.setContainers(metadata.id, metadata.version, containers) }
    }

    @Test
    fun `setContainers with empty list delegates to service`() = runTest {
        val containers = emptyList<DocumentTemplateContainerInput>()
        coJustRun { templateService.setContainers(metadata.id, metadata.version, containers) }

        val result = controller.setContainers(authentication, mutation, containers)

        assertEquals(metadata, result)
        coVerify { templateService.setContainers(metadata.id, metadata.version, containers) }
    }

    @Test
    fun `setContainers propagates permission denial and skips service`() = runTest {
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller.setContainers(authentication, mutation, listOf(container))
        }
        coVerify(exactly = 0) { templateService.setContainers(any(), any(), any()) }
    }

    // ----- setContent -----

    @Test
    fun `setContent verifies edit permission then delegates and returns metadata`() = runTest {
        val content = buildJsonObject { put("body", "hello") }
        coJustRun { templateService.setContent(metadata.id, metadata.version, content) }

        val result = controller.setContent(authentication, mutation, content)

        assertEquals(metadata, result)
        coVerify { permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT) }
        coVerify { templateService.setContent(metadata.id, metadata.version, content) }
    }

    @Test
    fun `setContent propagates permission denial and skips service`() = runTest {
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller.setContent(authentication, mutation, JsonPrimitive("v"))
        }
        coVerify(exactly = 0) { templateService.setContent(any(), any(), any()) }
    }
}
