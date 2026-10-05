package bosca.workops.service

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.attachment.Attachment
import bosca.workops.model.task.Task
import bosca.workops.repository.AttachmentInsertParams
import bosca.workops.repository.AttachmentRepository
import bosca.workops.repository.ProjectAttachmentLimits
import bosca.workops.repository.ProjectAttachmentLimitsRepository
import bosca.workops.repository.TaskRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AttachmentServiceTest {

    private val repository = mockk<AttachmentRepository>()
    private val taskRepository = mockk<TaskRepository>()
    private val limitsRepository = mockk<ProjectAttachmentLimitsRepository>()
    private val signingSecret = mockk<AttachmentSigningSecret>()
    private val service = AttachmentServiceImpl(repository, taskRepository, limitsRepository, signingSecret)

    private val taskId = UUID.random()
    private val projectId = UUID.random()
    private val profileId = UUID.random()

    private fun task() = Task(
        id = taskId,
        key = "ATT-1",
        projectId = projectId,
        taskTypeId = UUID.random(),
        statusId = UUID.random(),
        priorityId = UUID.random(),
        summary = "Attachment task",
        reporterProfileId = profileId,
        createdByPrincipalId = UUID.random(),
        modifiedByPrincipalId = UUID.random(),
    )

    private fun limits() = ProjectAttachmentLimits(
        maxAttachmentBytes = 100,
        maxTotalAttachmentBytes = 250,
        denylist = listOf("application/project-denied"),
    )

    @Test
    fun `request upload signs and remembers the exact attachment descriptor`() = runTest {
        coEvery { taskRepository.getActiveById(taskId) } returns task()
        coEvery { limitsRepository.get(projectId) } returns limits()
        coEvery { repository.totalForTask(taskId) } returns 25
        every { signingSecret.sign(any()) } returns "signature"
        every { signingSecret.remember(any(), any()) } returns Unit
        val token = slot<String>()
        val descriptor = slot<AttachmentDescriptor>()

        val upload = service.requestUpload(taskId, profileId, "photo.png", "image/png", 50)

        verify { signingSecret.remember(capture(token), capture(descriptor)) }
        assertEquals(upload.confirmationToken, token.captured)
        assertTrue(upload.uploadUrl.startsWith("workops://upload/${upload.storageObjectId}?expires="))
        assertEquals(taskId, descriptor.captured.taskId)
        assertEquals(profileId, descriptor.captured.profileId)
        assertEquals("photo.png", descriptor.captured.filename)
        assertEquals("image/png", descriptor.captured.contentType)
        assertEquals(50, descriptor.captured.sizeBytes)
        assertEquals(upload.storageObjectId, descriptor.captured.storageObjectId)
        assertEquals("${upload.storageObjectId}:${upload.expiresAt.toEpochSecond()}:signature", token.captured)
        verify {
            signingSecret.sign(
                "$taskId|$profileId|photo.png|image/png|50|${upload.storageObjectId}|${upload.expiresAt.toEpochSecond()}",
            )
        }
    }

    @Test
    fun `request upload rejects missing ownership limits invalid sizes denied media and aggregate overflow`() = runTest {
        coEvery { taskRepository.getActiveById(taskId) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.requestUpload(taskId, profileId, "a", "text/plain", 1)
        }

        coEvery { taskRepository.getActiveById(taskId) } returns task()
        coEvery { limitsRepository.get(projectId) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.requestUpload(taskId, profileId, "a", "text/plain", 1)
        }

        coEvery { limitsRepository.get(projectId) } returns limits()
        for (size in listOf(0L, -1L, 101L)) {
            assertFailsWith<WorkOpsValidationException> {
                service.requestUpload(taskId, profileId, "a", "text/plain", size)
            }
        }
        for (contentType in listOf("application/x-sh", "application/project-denied")) {
            assertFailsWith<WorkOpsValidationException> {
                service.requestUpload(taskId, profileId, "a", contentType, 1)
            }
        }

        coEvery { repository.totalForTask(taskId) } returns 225
        assertFailsWith<WorkOpsValidationException> {
            service.requestUpload(taskId, profileId, "a", "text/plain", 26)
        }
        verify(exactly = 0) { signingSecret.remember(any(), any()) }
    }

    @Test
    fun `confirm upload validates every token envelope field`() = runTest {
        val validId = UUID.random()
        val future = OffsetDateTime.now().plusMinutes(5).toEpochSecond()
        val invalidTokens = listOf(
            "missing-parts",
            "not-a-uuid:$future:signature",
            "$validId:not-a-number:signature",
            "$validId:${OffsetDateTime.now().minusMinutes(5).toEpochSecond()}:signature",
        )
        invalidTokens.forEach { token ->
            assertFailsWith<WorkOpsValidationException> { service.confirmUpload(token, null) }
        }

        val validToken = "$validId:$future:signature"
        every { signingSecret.verify(validToken) } returns null
        assertFailsWith<WorkOpsValidationException> { service.confirmUpload(validToken, null) }
        coVerify(exactly = 0) { repository.add(any()) }
    }

    @Test
    fun `confirm upload persists verified descriptor and token storage id`() = runTest {
        val tokenStorageId = UUID.random()
        val descriptor = AttachmentDescriptor(
            taskId = taskId,
            profileId = profileId,
            filename = "report.pdf",
            contentType = "application/pdf",
            sizeBytes = 88,
            storageObjectId = UUID.random(),
            expiresAt = OffsetDateTime.now().plusMinutes(5),
        )
        val token = "$tokenStorageId:${descriptor.expiresAt.toEpochSecond()}:signature"
        val input = slot<AttachmentInsertParams>()
        val attachment = attachment(tokenStorageId)
        every { signingSecret.verify(token) } returns descriptor
        coEvery { repository.add(capture(input)) } returns attachment

        assertEquals(attachment, service.confirmUpload(token, "Evidence"))
        assertEquals(taskId, input.captured.taskId)
        assertEquals(tokenStorageId, input.captured.storageObjectId)
        assertEquals("report.pdf", input.captured.filename)
        assertEquals("application/pdf", input.captured.contentType)
        assertEquals(88, input.captured.sizeBytes)
        assertEquals(profileId, input.captured.uploadedByProfileId)
        assertEquals("Evidence", input.captured.description)
    }

    @Test
    fun `attachment reads and deletion results delegate without changing repository semantics`() = runTest {
        val attachment = attachment(UUID.random())
        coEvery { repository.listForTask(taskId) } returns listOf(attachment)
        coEvery { repository.getById(attachment.id) } returns attachment
        coEvery { repository.softDelete(attachment.id) } returns attachment
        val missingId = UUID.random()
        coEvery { repository.softDelete(missingId) } returns null

        assertEquals(listOf(attachment), service.listForTask(taskId))
        assertEquals(attachment, service.getById(attachment.id))
        assertTrue(service.delete(attachment.id))
        assertFalse(service.delete(missingId))
    }

    @Test
    fun `HMAC signing is deterministic and remembered descriptors are single use and bounded`() {
        val random = HmacSecret.random()
        assertEquals(32, random.bytes.size)
        val secret = AttachmentSigningSecretImpl(HmacSecret(ByteArray(32) { it.toByte() }))
        assertEquals(secret.sign("payload"), secret.sign("payload"))
        assertNotEquals(secret.sign("payload"), secret.sign("different"))

        val descriptor = AttachmentDescriptor(
            taskId, profileId, "file", "text/plain", 1, UUID.random(), OffsetDateTime.now().plusMinutes(1),
        )
        secret.remember("single", descriptor)
        assertEquals(descriptor, secret.verify("single"))
        assertEquals(null, secret.verify("single"))

        repeat(4_097) { secret.remember("token-$it", descriptor) }
        assertEquals(null, secret.verify("token-0"))
        assertEquals(descriptor, secret.verify("token-4096"))
    }

    private fun attachment(storageObjectId: UUID) = Attachment(
        id = UUID.random(),
        taskId = taskId,
        storageObjectId = storageObjectId,
        filename = "report.pdf",
        contentType = "application/pdf",
        sizeBytes = 88,
        uploadedByProfileId = profileId,
    )
}
