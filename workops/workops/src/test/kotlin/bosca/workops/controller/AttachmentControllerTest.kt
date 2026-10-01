package bosca.workops.controller

import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.attachment.Attachment
import bosca.workops.model.attachment.PresignedUpload
import bosca.workops.model.task.Task
import bosca.workops.service.AttachmentService
import bosca.workops.service.TaskPermissionEvaluator
import bosca.workops.service.TaskService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AttachmentControllerTest {

    private val service = mockk<AttachmentService>(relaxed = true)
    private val taskService = mockk<TaskService>(relaxed = true)
    private val permissions = mockk<TaskPermissionEvaluator>(relaxed = true)
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val principalId = UUID.random()
    private val profileId = UUID.random()
    private val task = sampleTask()
    private val attachment = sampleAttachment(task.id)
    private val upload = PresignedUpload(
        uploadUrl = "https://storage.example/upload",
        storageObjectId = UUID.random(),
        confirmationToken = "confirmation-token",
        expiresAt = OffsetDateTime.now(),
    )

    private fun authentication(primaryProfileId: UUID? = profileId): AuthenticationContext =
        ImpersonatedAuthenticationContext(
            Principal(principalId, primaryProfileId = primaryProfileId),
            listOf(Group(UUID.random(), "users", "", GroupType.SYSTEM)),
        )

    private fun mutationController() = AttachmentMutationController(
        service,
        taskService,
        permissions,
        profileService,
    )

    @Test
    fun `attachment and presigned upload fields expose complete models`() {
        val attachmentController = AttachmentTypeController()
        assertEquals(attachment.id, attachmentController.id(attachment))
        assertEquals(attachment.taskId, attachmentController.taskId(attachment))
        assertEquals(attachment.storageObjectId, attachmentController.storageObjectId(attachment))
        assertEquals(attachment.filename, attachmentController.filename(attachment))
        assertEquals(attachment.contentType, attachmentController.contentType(attachment))
        assertEquals(attachment.sizeBytes, attachmentController.sizeBytes(attachment))
        assertEquals(attachment.thumbnailStorageObjectId, attachmentController.thumbnailStorageObjectId(attachment))
        assertEquals(attachment.uploadedByProfileId, attachmentController.uploadedByProfileId(attachment))
        assertEquals(attachment.uploadedAt, attachmentController.uploadedAt(attachment))
        assertEquals(attachment.description, attachmentController.description(attachment))

        val uploadController = PresignedUploadTypeController()
        assertEquals(upload.uploadUrl, uploadController.uploadUrl(upload))
        assertEquals(upload.storageObjectId, uploadController.storageObjectId(upload))
        assertEquals(upload.confirmationToken, uploadController.confirmationToken(upload))
        assertEquals(upload.expiresAt, uploadController.expiresAt(upload))
    }

    @Test
    fun `attachment queries return only records on visible tasks`() = runTest {
        val authentication = authentication()
        val controller = AttachmentQueryController(service, taskService, permissions)
        coEvery { taskService.getById(task.id) } returns null
        assertEquals(emptyList(), controller.forTask(authentication, task.id))

        coEvery { taskService.getById(task.id) } returns task
        coEvery { permissions.isAllowed(authentication, task, PermissionAction.VIEW) } returns false
        assertEquals(emptyList(), controller.forTask(authentication, task.id))

        coEvery { permissions.isAllowed(authentication, task, PermissionAction.VIEW) } returns true
        coEvery { service.listForTask(task.id) } returns listOf(attachment)
        assertEquals(listOf(attachment), controller.forTask(authentication, task.id))
    }

    @Test
    fun `attachment query checks attachment task and view permission`() = runTest {
        val authentication = authentication()
        val controller = AttachmentQueryController(service, taskService, permissions)
        coEvery { service.getById(attachment.id) } returns null
        assertNull(controller.attachment(authentication, attachment.id))

        coEvery { service.getById(attachment.id) } returns attachment
        coEvery { taskService.getById(task.id) } returns null
        assertNull(controller.attachment(authentication, attachment.id))

        coEvery { taskService.getById(task.id) } returns task
        coEvery { permissions.isAllowed(authentication, task, PermissionAction.VIEW) } returns false
        assertNull(controller.attachment(authentication, attachment.id))

        coEvery { permissions.isAllowed(authentication, task, PermissionAction.VIEW) } returns true
        assertSame(attachment, controller.attachment(authentication, attachment.id))
    }

    @Test
    fun `request upload uses the principal primary profile`() = runTest {
        val authentication = authentication()
        coEvery { taskService.getById(task.id) } returns task
        coEvery {
            service.requestUpload(task.id, profileId, "design.png", "image/png", 1234)
        } returns upload

        assertSame(
            upload,
            mutationController().requestUpload(authentication, task.id, "design.png", "image/png", 1234),
        )
        coVerify(exactly = 1) { permissions.verifyAllowed(authentication, task, PermissionAction.EDIT) }
        coVerify(exactly = 0) { profileService.getByPrincipal(any()) }
    }

    @Test
    fun `request upload falls back to the first principal profile`() = runTest {
        val authentication = authentication(primaryProfileId = null)
        coEvery { taskService.getById(task.id) } returns task
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(profile(profileId))
        coEvery {
            service.requestUpload(task.id, profileId, "design.png", "image/png", 1234)
        } returns upload

        assertSame(
            upload,
            mutationController().requestUpload(authentication, task.id, "design.png", "image/png", 1234),
        )
    }

    @Test
    fun `request upload rejects missing task principal and profile`() = runTest {
        val controller = mutationController()
        val authenticated = authentication()
        coEvery { taskService.getById(task.id) } returns null
        assertFailsWith<IllegalStateException> {
            controller.requestUpload(authenticated, task.id, "design.png", "image/png", 1234)
        }

        coEvery { taskService.getById(task.id) } returns task
        assertFailsWith<IllegalStateException> {
            controller.requestUpload(AuthenticationContext(null, null), task.id, "design.png", "image/png", 1234)
        }

        val noProfile = authentication(primaryProfileId = null)
        coEvery { profileService.getByPrincipal(principalId) } returns emptyList()
        assertFailsWith<IllegalStateException> {
            controller.requestUpload(noProfile, task.id, "design.png", "image/png", 1234)
        }
    }

    @Test
    fun `confirm and delete delegate after authorization`() = runTest {
        val authentication = authentication()
        val controller = mutationController()
        coEvery { service.confirmUpload(upload.confirmationToken, "Design") } returns attachment
        coEvery { service.getById(attachment.id) } returns attachment
        coEvery { taskService.getById(task.id) } returns task
        coEvery { service.delete(attachment.id) } returns true

        assertSame(attachment, controller.confirmUpload(authentication, upload.confirmationToken, "Design"))
        assertTrue(controller.deleteAttachment(authentication, attachment.id))
        coVerify(exactly = 1) { permissions.verifyAllowed(authentication, task, PermissionAction.EDIT) }
    }

    @Test
    fun `delete rejects missing attachments and tasks`() = runTest {
        val authentication = authentication()
        val controller = mutationController()
        coEvery { service.getById(attachment.id) } returns null
        assertFailsWith<IllegalStateException> { controller.deleteAttachment(authentication, attachment.id) }

        coEvery { service.getById(attachment.id) } returns attachment
        coEvery { taskService.getById(task.id) } returns null
        assertFailsWith<IllegalStateException> { controller.deleteAttachment(authentication, attachment.id) }
    }

    private fun sampleTask() = Task(
        id = UUID.random(),
        key = "GIT-1",
        projectId = UUID.random(),
        taskTypeId = UUID.random(),
        statusId = UUID.random(),
        priorityId = UUID.random(),
        summary = "Task",
        reporterProfileId = profileId,
        createdByPrincipalId = principalId,
        modifiedByPrincipalId = principalId,
    )

    private fun sampleAttachment(taskId: UUID) = Attachment(
        id = UUID.random(),
        taskId = taskId,
        storageObjectId = UUID.random(),
        filename = "design.png",
        contentType = "image/png",
        sizeBytes = 1234,
        thumbnailStorageObjectId = UUID.random(),
        uploadedByProfileId = profileId,
        description = "Design",
    )

    private fun profile(id: UUID) = Profile(
        id = id,
        type = ProfileType.GENERIC,
        name = "Profile",
        visibility = ProfileVisibility.USER,
    )
}
