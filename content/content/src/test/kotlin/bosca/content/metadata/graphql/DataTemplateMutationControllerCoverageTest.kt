package bosca.content.metadata.graphql

import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.attributes.TemplateAttributeInput
import bosca.content.metadata.model.DataTemplateMutation
import bosca.content.metadata.model.DataType
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.DataTemplateService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DataTemplateMutationControllerCoverageTest {

    private val templateService = mockk<DataTemplateService>()
    private val permissionEvaluator = mockk<MetadataPermissionEvaluator>()

    private val controller = DataTemplateMutationController(templateService, permissionEvaluator)
    private val authentication = mockk<AuthenticationContext>()

    private val metadata = Metadata(
        id = UUID.random(),
        name = "Template",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "published",
        version = 3
    )
    private val mutation = DataTemplateMutation(metadata = metadata)

    private val attribute = TemplateAttributeInput(
        key = "field",
        name = "Field",
        description = "A field",
        type = AttributeType.STRING,
        ui = AttributeUiType.INPUT
    )

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    // ---- addAttribute ----

    @Test
    fun `addAttribute verifies edit, calls service and returns metadata`() = runTest {
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        } just Runs
        coEvery { templateService.addAttribute(metadata.id, metadata.version, attribute, 5) } just Runs

        val result = controller.addAttribute(authentication, mutation, attribute, 5)

        assertEquals(metadata, result)
        coVerify(exactly = 1) {
            permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        }
        coVerify(exactly = 1) { templateService.addAttribute(metadata.id, metadata.version, attribute, 5) }
    }

    @Test
    fun `addAttribute propagates when permission denied`() = runTest {
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        } throws IllegalStateException("unauthorized")

        assertFailsWith<IllegalStateException> {
            controller.addAttribute(authentication, mutation, attribute, 0)
        }
        coVerify(exactly = 0) { templateService.addAttribute(any(), any(), any(), any()) }
    }

    // ---- deleteAttribute ----

    @Test
    fun `deleteAttribute verifies edit, calls service and returns metadata`() = runTest {
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        } just Runs
        coEvery { templateService.deleteAttribute(metadata.id, metadata.version, "field") } just Runs

        val result = controller.deleteAttribute(authentication, mutation, "field")

        assertEquals(metadata, result)
        coVerify(exactly = 1) { templateService.deleteAttribute(metadata.id, metadata.version, "field") }
    }

    @Test
    fun `deleteAttribute propagates when permission denied`() = runTest {
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        } throws IllegalStateException("unauthorized")

        assertFailsWith<IllegalStateException> {
            controller.deleteAttribute(authentication, mutation, "field")
        }
        coVerify(exactly = 0) { templateService.deleteAttribute(any(), any(), any()) }
    }

    // ---- setAttributes ----

    @Test
    fun `setAttributes verifies edit, calls service and returns metadata`() = runTest {
        val attributes = listOf(attribute)
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        } just Runs
        coEvery { templateService.setAttributes(metadata.id, metadata.version, attributes) } just Runs

        val result = controller.setAttributes(authentication, mutation, attributes)

        assertEquals(metadata, result)
        coVerify(exactly = 1) { templateService.setAttributes(metadata.id, metadata.version, attributes) }
    }

    @Test
    fun `setAttributes handles empty list`() = runTest {
        val attributes = emptyList<TemplateAttributeInput>()
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        } just Runs
        coEvery { templateService.setAttributes(metadata.id, metadata.version, attributes) } just Runs

        val result = controller.setAttributes(authentication, mutation, attributes)

        assertEquals(metadata, result)
        coVerify(exactly = 1) { templateService.setAttributes(metadata.id, metadata.version, attributes) }
    }

    @Test
    fun `setAttributes propagates when permission denied`() = runTest {
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        } throws IllegalStateException("unauthorized")

        assertFailsWith<IllegalStateException> {
            controller.setAttributes(authentication, mutation, listOf(attribute))
        }
        coVerify(exactly = 0) { templateService.setAttributes(any(), any(), any()) }
    }

    // ---- setDefaultAttributes ----

    @Test
    fun `setDefaultAttributes verifies edit, calls service and returns metadata`() = runTest {
        val attributes = JsonPrimitive("default")
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        } just Runs
        coEvery { templateService.setDefaultAttributes(metadata.id, metadata.version, attributes) } just Runs

        val result = controller.setDefaultAttributes(authentication, mutation, attributes)

        assertEquals(metadata, result)
        coVerify(exactly = 1) { templateService.setDefaultAttributes(metadata.id, metadata.version, attributes) }
    }

    @Test
    fun `setDefaultAttributes propagates when permission denied`() = runTest {
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        } throws IllegalStateException("unauthorized")

        assertFailsWith<IllegalStateException> {
            controller.setDefaultAttributes(authentication, mutation, JsonPrimitive("x"))
        }
        coVerify(exactly = 0) { templateService.setDefaultAttributes(any(), any(), any()) }
    }

    // ---- setType ----

    @Test
    fun `setType verifies edit, calls service and returns metadata`() = runTest {
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        } just Runs
        coEvery { templateService.setType(metadata.id, metadata.version, DataType.TABLE) } just Runs

        val result = controller.setType(authentication, mutation, DataType.TABLE)

        assertEquals(metadata, result)
        coVerify(exactly = 1) { templateService.setType(metadata.id, metadata.version, DataType.TABLE) }
    }

    @Test
    fun `setType handles ATTRIBUTES type`() = runTest {
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        } just Runs
        coEvery { templateService.setType(metadata.id, metadata.version, DataType.ATTRIBUTES) } just Runs

        val result = controller.setType(authentication, mutation, DataType.ATTRIBUTES)

        assertEquals(metadata, result)
        coVerify(exactly = 1) { templateService.setType(metadata.id, metadata.version, DataType.ATTRIBUTES) }
    }

    @Test
    fun `setType propagates when permission denied`() = runTest {
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        } throws IllegalStateException("unauthorized")

        assertFailsWith<IllegalStateException> {
            controller.setType(authentication, mutation, DataType.TABLE)
        }
        coVerify(exactly = 0) { templateService.setType(any(), any(), any()) }
    }
}
