package bosca.content.metadata.graphql

import bosca.content.metadata.model.GuideType
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.GuideService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GuideMutationControllerTest {

    private val guideService = mockk<GuideService>()
    private val permissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val controller = GuideMutationController(guideService = guideService, permissionEvaluator = permissionEvaluator)
    private val auth = mockk<AuthenticationContext>(relaxed = true)

    init {
        coJustRun { permissionEvaluator.verifyAllowed(any(), any<Metadata>(), any()) }
    }

    private val metadata = Metadata(
        id = UUID.random(),
        name = "test-guide",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "draft",
        version = 1
    )

    @Test
    fun `setType delegates to guide service and returns true`() = runTest {
        coEvery { guideService.setGuideType(metadata.id, metadata.version, GuideType.CALENDAR_PROGRESS) } returns Unit

        val mutation = GuideMutation(metadata)
        val result = controller.setType(auth, mutation, GuideType.CALENDAR_PROGRESS)

        assertEquals(true, result)
        coVerify { permissionEvaluator.verifyAllowed(auth, metadata, PermissionAction.EDIT) }
        coVerify { guideService.setGuideType(metadata.id, metadata.version, GuideType.CALENDAR_PROGRESS) }
    }

    @Test
    fun `setRrule delegates to guide service and returns true`() = runTest {
        val rrule = "RRULE:FREQ=DAILY;INTERVAL=1;COUNT=30"
        coEvery { guideService.setGuideRrule(metadata.id, metadata.version, rrule) } returns Unit

        val mutation = GuideMutation(metadata)
        val result = controller.setRrule(auth, mutation, rrule)

        assertEquals(true, result)
        coVerify { permissionEvaluator.verifyAllowed(auth, metadata, PermissionAction.EDIT) }
        coVerify { guideService.setGuideRrule(metadata.id, metadata.version, rrule) }
    }

    @Test
    fun `reorderSteps delegates to guide service and returns true`() = runTest {
        val stepIds = listOf(3L, 1L, 2L)
        coEvery { guideService.reorderSteps(metadata.id, metadata.version, stepIds) } returns Unit

        val mutation = GuideMutation(metadata)
        val result = controller.reorderSteps(auth, mutation, stepIds)

        assertEquals(true, result)
        coVerify { guideService.reorderSteps(metadata.id, metadata.version, stepIds) }
    }

    @Test
    fun `reorderSteps with empty list delegates to guide service`() = runTest {
        val stepIds = emptyList<Long>()
        coEvery { guideService.reorderSteps(metadata.id, metadata.version, stepIds) } returns Unit

        val mutation = GuideMutation(metadata)
        val result = controller.reorderSteps(auth, mutation, stepIds)

        assertEquals(true, result)
        coVerify { guideService.reorderSteps(metadata.id, metadata.version, stepIds) }
    }

    @Test
    fun `reorderModules delegates to guide service and returns true`() = runTest {
        val stepId = 5L
        val moduleIds = listOf(2L, 3L, 1L)
        coEvery { guideService.reorderModules(metadata.id, metadata.version, stepId, moduleIds) } returns Unit

        val mutation = GuideMutation(metadata)
        val result = controller.reorderModules(auth, mutation, stepId, moduleIds)

        assertEquals(true, result)
        coVerify { guideService.reorderModules(metadata.id, metadata.version, stepId, moduleIds) }
    }

    @Test
    fun `reorderModules with empty list delegates to guide service`() = runTest {
        val stepId = 1L
        val moduleIds = emptyList<Long>()
        coEvery { guideService.reorderModules(metadata.id, metadata.version, stepId, moduleIds) } returns Unit

        val mutation = GuideMutation(metadata)
        val result = controller.reorderModules(auth, mutation, stepId, moduleIds)

        assertEquals(true, result)
        coVerify { guideService.reorderModules(metadata.id, metadata.version, stepId, moduleIds) }
    }

    @Test
    fun `reorderSteps uses metadata id and version from mutation`() = runTest {
        val specificMetadata = Metadata(
            id = UUID.random(),
            name = "specific-guide",
            type = MetadataType.STANDARD,
            contentType = "text/plain",
            contentLength = null,
            languageTag = "en",
            workflowStateId = "draft",
            version = 3
        )
        val stepIds = listOf(1L, 2L)
        coEvery { guideService.reorderSteps(specificMetadata.id, specificMetadata.version, stepIds) } returns Unit

        val mutation = GuideMutation(specificMetadata)
        controller.reorderSteps(auth, mutation, stepIds)

        coVerify { guideService.reorderSteps(specificMetadata.id, 3, stepIds) }
    }
}
