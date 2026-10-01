package bosca.content.metadata.graphql

import bosca.attributes.AttributeLocation
import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.attributes.TemplateAttributeInput
import bosca.content.metadata.model.GuideType
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.GuideTemplateService
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GuideTemplateMutationControllerCoverageTest {

    private val templateService = mockk<GuideTemplateService>()
    private val permissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val controller = GuideTemplateMutationController(
        templateService = templateService,
        permissionEvaluator = permissionEvaluator
    )
    private val auth = mockk<AuthenticationContext>(relaxed = true)

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

    private val mutation = GuideTemplateMutation(metadata)

    private val attribute = TemplateAttributeInput(
        key = "attr-key",
        name = "Attr Name",
        description = "Attr Description",
        type = AttributeType.STRING,
        ui = AttributeUiType.INPUT,
        list = false,
        location = AttributeLocation.ITEM
    )

    private fun allowEdit() {
        coJustRun { permissionEvaluator.verifyAllowed(auth, metadata, PermissionAction.EDIT) }
    }

    private fun denyEdit() {
        coEvery {
            permissionEvaluator.verifyAllowed(auth, metadata, PermissionAction.EDIT)
        } throws SecurityException("unauthorized")
    }

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    // GuideTemplateMutation holder

    @Test
    fun `mutation holds the metadata`() {
        assertEquals(metadata, mutation.metadata)
    }

    // addAttribute

    @Test
    fun `addAttribute verifies edit permission and delegates`() = runTest {
        allowEdit()
        coJustRun { templateService.addAttribute(metadata.id, metadata.version, attribute, 7) }

        val result = controller.addAttribute(auth, mutation, attribute, 7)

        assertEquals(metadata, result)
        coVerify { permissionEvaluator.verifyAllowed(auth, metadata, PermissionAction.EDIT) }
        coVerify { templateService.addAttribute(metadata.id, metadata.version, attribute, 7) }
    }

    @Test
    fun `addAttribute denied does not call service`() = runTest {
        denyEdit()

        assertFailsWith<SecurityException> {
            controller.addAttribute(auth, mutation, attribute, 0)
        }
        coVerify(exactly = 0) { templateService.addAttribute(any(), any(), any(), any()) }
    }

    // deleteAttribute

    @Test
    fun `deleteAttribute verifies edit permission and delegates`() = runTest {
        allowEdit()
        coJustRun { templateService.deleteAttribute(metadata.id, metadata.version, "the-key") }

        val result = controller.deleteAttribute(auth, mutation, "the-key")

        assertEquals(metadata, result)
        coVerify { templateService.deleteAttribute(metadata.id, metadata.version, "the-key") }
    }

    @Test
    fun `deleteAttribute denied does not call service`() = runTest {
        denyEdit()

        assertFailsWith<SecurityException> {
            controller.deleteAttribute(auth, mutation, "the-key")
        }
        coVerify(exactly = 0) { templateService.deleteAttribute(any(), any(), any()) }
    }

    // setAttributes

    @Test
    fun `setAttributes verifies edit permission and delegates`() = runTest {
        allowEdit()
        val attributes = listOf(attribute)
        coJustRun { templateService.setAttributes(metadata.id, metadata.version, attributes) }

        val result = controller.setAttributes(auth, mutation, attributes)

        assertEquals(metadata, result)
        coVerify { templateService.setAttributes(metadata.id, metadata.version, attributes) }
    }

    @Test
    fun `setAttributes with empty list delegates`() = runTest {
        allowEdit()
        val attributes = emptyList<TemplateAttributeInput>()
        coJustRun { templateService.setAttributes(metadata.id, metadata.version, attributes) }

        val result = controller.setAttributes(auth, mutation, attributes)

        assertEquals(metadata, result)
        coVerify { templateService.setAttributes(metadata.id, metadata.version, attributes) }
    }

    @Test
    fun `setAttributes denied does not call service`() = runTest {
        denyEdit()

        assertFailsWith<SecurityException> {
            controller.setAttributes(auth, mutation, listOf(attribute))
        }
        coVerify(exactly = 0) { templateService.setAttributes(any(), any(), any()) }
    }

    // setConfiguration

    @Test
    fun `setConfiguration verifies edit permission and delegates`() = runTest {
        allowEdit()
        val configuration = JsonPrimitive("cfg")
        coJustRun { templateService.setConfiguration(metadata.id, metadata.version, configuration) }

        val result = controller.setConfiguration(auth, mutation, configuration)

        assertEquals(metadata, result)
        coVerify { templateService.setConfiguration(metadata.id, metadata.version, configuration) }
    }

    @Test
    fun `setConfiguration denied does not call service`() = runTest {
        denyEdit()

        assertFailsWith<SecurityException> {
            controller.setConfiguration(auth, mutation, JsonPrimitive("cfg"))
        }
        coVerify(exactly = 0) { templateService.setConfiguration(any(), any(), any()) }
    }

    // setDefaultAttributes

    @Test
    fun `setDefaultAttributes verifies edit permission and delegates`() = runTest {
        allowEdit()
        val attributes = JsonPrimitive("defaults")
        coJustRun { templateService.setDefaultAttributes(metadata.id, metadata.version, attributes) }

        val result = controller.setDefaultAttributes(auth, mutation, attributes)

        assertEquals(metadata, result)
        coVerify { templateService.setDefaultAttributes(metadata.id, metadata.version, attributes) }
    }

    @Test
    fun `setDefaultAttributes denied does not call service`() = runTest {
        denyEdit()

        assertFailsWith<SecurityException> {
            controller.setDefaultAttributes(auth, mutation, JsonPrimitive("defaults"))
        }
        coVerify(exactly = 0) { templateService.setDefaultAttributes(any(), any(), any()) }
    }

    // setRrule

    @Test
    fun `setRrule verifies edit permission and delegates`() = runTest {
        allowEdit()
        coJustRun { templateService.setRrule(metadata.id, metadata.version, "FREQ=DAILY") }

        val result = controller.setRrule(auth, mutation, "FREQ=DAILY")

        assertEquals(metadata, result)
        coVerify { templateService.setRrule(metadata.id, metadata.version, "FREQ=DAILY") }
    }

    @Test
    fun `setRrule denied does not call service`() = runTest {
        denyEdit()

        assertFailsWith<SecurityException> {
            controller.setRrule(auth, mutation, "FREQ=DAILY")
        }
        coVerify(exactly = 0) { templateService.setRrule(any(), any(), any()) }
    }

    // setType

    @Test
    fun `setType verifies edit permission and delegates`() = runTest {
        allowEdit()
        coJustRun { templateService.setType(metadata.id, metadata.version, GuideType.CALENDAR) }

        val result = controller.setType(auth, mutation, GuideType.CALENDAR)

        assertEquals(metadata, result)
        coVerify { templateService.setType(metadata.id, metadata.version, GuideType.CALENDAR) }
    }

    @Test
    fun `setType denied does not call service`() = runTest {
        denyEdit()

        assertFailsWith<SecurityException> {
            controller.setType(auth, mutation, GuideType.LINEAR)
        }
        coVerify(exactly = 0) { templateService.setType(any(), any(), any()) }
    }

    // addStep

    @Test
    fun `addStep verifies edit permission delegates and returns true`() = runTest {
        allowEdit()
        val stepMetadataId = UUID.random()
        coJustRun { templateService.addStep(metadata, stepMetadataId, 4) }

        val result = controller.addStep(auth, mutation, stepMetadataId, 4)

        assertTrue(result)
        coVerify { templateService.addStep(metadata, stepMetadataId, 4) }
    }

    @Test
    fun `addStep denied does not call service`() = runTest {
        denyEdit()

        assertFailsWith<SecurityException> {
            controller.addStep(auth, mutation, UUID.random(), 1)
        }
        coVerify(exactly = 0) { templateService.addStep(any(), any(), any()) }
    }

    // addModule

    @Test
    fun `addModule verifies edit permission delegates and returns true`() = runTest {
        allowEdit()
        val moduleMetadataId = UUID.random()
        coJustRun { templateService.addModule(metadata, 9L, moduleMetadataId, 3) }

        val result = controller.addModule(auth, mutation, moduleMetadataId, 3, 9L)

        assertTrue(result)
        coVerify { templateService.addModule(metadata, 9L, moduleMetadataId, 3) }
    }

    @Test
    fun `addModule denied does not call service`() = runTest {
        denyEdit()

        assertFailsWith<SecurityException> {
            controller.addModule(auth, mutation, UUID.random(), 1, 2L)
        }
        coVerify(exactly = 0) { templateService.addModule(any(), any(), any(), any()) }
    }

    // removeModule

    @Test
    fun `removeModule verifies edit permission delegates and returns true`() = runTest {
        allowEdit()
        coJustRun { templateService.removeModule(metadata, 5L, 6L) }

        val result = controller.removeModule(auth, mutation, 5L, 6L)

        assertTrue(result)
        coVerify { templateService.removeModule(metadata, 5L, 6L) }
    }

    @Test
    fun `removeModule denied does not call service`() = runTest {
        denyEdit()

        assertFailsWith<SecurityException> {
            controller.removeModule(auth, mutation, 5L, 6L)
        }
        coVerify(exactly = 0) { templateService.removeModule(any(), any(), any()) }
    }

    // removeStep

    @Test
    fun `removeStep verifies edit permission delegates and returns true`() = runTest {
        allowEdit()
        coJustRun { templateService.removeStep(metadata, 8L) }

        val result = controller.removeStep(auth, mutation, 8L)

        assertTrue(result)
        coVerify { templateService.removeStep(metadata, 8L) }
    }

    @Test
    fun `removeStep denied does not call service`() = runTest {
        denyEdit()

        assertFailsWith<SecurityException> {
            controller.removeStep(auth, mutation, 8L)
        }
        coVerify(exactly = 0) { templateService.removeStep(any(), any()) }
    }

    // reorderModules

    @Test
    fun `reorderModules verifies edit permission delegates and returns true`() = runTest {
        allowEdit()
        val moduleIds = listOf(3L, 1L, 2L)
        coJustRun { templateService.reorderModules(metadata, 4L, moduleIds) }

        val result = controller.reorderModules(auth, mutation, 4L, moduleIds)

        assertTrue(result)
        coVerify { templateService.reorderModules(metadata, 4L, moduleIds) }
    }

    @Test
    fun `reorderModules with empty list delegates`() = runTest {
        allowEdit()
        val moduleIds = emptyList<Long>()
        coJustRun { templateService.reorderModules(metadata, 4L, moduleIds) }

        val result = controller.reorderModules(auth, mutation, 4L, moduleIds)

        assertTrue(result)
        coVerify { templateService.reorderModules(metadata, 4L, moduleIds) }
    }

    @Test
    fun `reorderModules denied does not call service`() = runTest {
        denyEdit()

        assertFailsWith<SecurityException> {
            controller.reorderModules(auth, mutation, 4L, listOf(1L))
        }
        coVerify(exactly = 0) { templateService.reorderModules(any(), any(), any()) }
    }

    // reorderSteps

    @Test
    fun `reorderSteps verifies edit permission delegates and returns true`() = runTest {
        allowEdit()
        val stepIds = listOf(2L, 3L, 1L)
        coJustRun { templateService.reorderSteps(metadata, stepIds) }

        val result = controller.reorderSteps(auth, mutation, stepIds)

        assertTrue(result)
        coVerify { templateService.reorderSteps(metadata, stepIds) }
    }

    @Test
    fun `reorderSteps with empty list delegates`() = runTest {
        allowEdit()
        val stepIds = emptyList<Long>()
        coJustRun { templateService.reorderSteps(metadata, stepIds) }

        val result = controller.reorderSteps(auth, mutation, stepIds)

        assertTrue(result)
        coVerify { templateService.reorderSteps(metadata, stepIds) }
    }

    @Test
    fun `reorderSteps denied does not call service`() = runTest {
        denyEdit()

        assertFailsWith<SecurityException> {
            controller.reorderSteps(auth, mutation, listOf(1L))
        }
        coVerify(exactly = 0) { templateService.reorderSteps(any(), any()) }
    }
}
