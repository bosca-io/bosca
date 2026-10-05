package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.attachment.Attachment
import bosca.workops.model.attachment.PresignedUpload
import bosca.workops.service.AttachmentService
import bosca.workops.service.TaskPermissionEvaluator
import bosca.workops.service.TaskService

@TypeController(type = "WorkOpsAttachment")
class AttachmentTypeController : GraphQLController<Attachment> {
    @Field
    fun id(a: Attachment) = a.id
    @Field
    fun taskId(a: Attachment) = a.taskId
    @Field
    fun storageObjectId(a: Attachment) = a.storageObjectId
    @Field
    fun filename(a: Attachment) = a.filename
    @Field
    fun contentType(a: Attachment) = a.contentType
    @Field
    fun sizeBytes(a: Attachment) = a.sizeBytes
    @Field
    fun thumbnailStorageObjectId(a: Attachment) = a.thumbnailStorageObjectId
    @Field
    fun uploadedByProfileId(a: Attachment) = a.uploadedByProfileId
    @Field
    fun uploadedAt(a: Attachment) = a.uploadedAt
    @Field
    fun description(a: Attachment) = a.description
}

@TypeController(type = "WorkOpsPresignedUpload")
class PresignedUploadTypeController : GraphQLController<PresignedUpload> {
    @Field
    fun uploadUrl(u: PresignedUpload) = u.uploadUrl
    @Field
    fun storageObjectId(u: PresignedUpload) = u.storageObjectId
    @Field
    fun confirmationToken(u: PresignedUpload) = u.confirmationToken
    @Field
    fun expiresAt(u: PresignedUpload) = u.expiresAt
}

object WorkOpsAttachmentQuery

@TypeController
class AttachmentQueryController(
    private val service: AttachmentService,
    private val taskService: TaskService,
    private val permissionEvaluator: TaskPermissionEvaluator,
) : GraphQLController<WorkOpsAttachmentQuery> {

    @Field
    suspend fun forTask(authentication: AuthenticationContext, taskId: UUID): List<Attachment> {
        val task = taskService.getById(taskId) ?: return emptyList()
        if (!permissionEvaluator.isAllowed(authentication, task, PermissionAction.VIEW)) return emptyList()
        return service.listForTask(taskId)
    }

    @Field
    suspend fun attachment(authentication: AuthenticationContext, id: UUID): Attachment? {
        val attachment = service.getById(id) ?: return null
        val task = taskService.getById(attachment.taskId) ?: return null
        if (!permissionEvaluator.isAllowed(authentication, task, PermissionAction.VIEW)) return null
        return attachment
    }
}

object WorkOpsAttachmentMutation

@TypeController
class AttachmentMutationController(
    private val service: AttachmentService,
    private val taskService: TaskService,
    private val permissionEvaluator: TaskPermissionEvaluator,
    private val profileService: bosca.profile.profile.service.ProfileService,
) : GraphQLController<WorkOpsAttachmentMutation> {

    @Field
    suspend fun requestUpload(
        authentication: AuthenticationContext,
        taskId: UUID,
        filename: String,
        contentType: String,
        sizeBytes: Long,
    ): PresignedUpload {
        val task = taskService.getById(taskId) ?: error("requestUpload: task $taskId not found")
        permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.EDIT)
        val authenticated = authentication.principal()
            ?: error("requestUpload requires a profile")
        val principal = authenticated.asPrincipal()
        val profileId = principal.primaryProfileId
            ?: profileService.getByPrincipal(principal.id).firstOrNull()?.id
            ?: error("requestUpload requires a profile")
        return service.requestUpload(taskId, profileId, filename, contentType, sizeBytes)
    }

    @Field
    suspend fun confirmUpload(
        authentication: AuthenticationContext,
        token: String,
        description: String?,
    ): Attachment {
        return service.confirmUpload(token, description)
    }

    @Field
    suspend fun deleteAttachment(authentication: AuthenticationContext, id: UUID): Boolean {
        val attachment = service.getById(id) ?: error("Attachment $id not found")
        val task = taskService.getById(attachment.taskId) ?: error("Task not found")
        permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.EDIT)
        return service.delete(id)
    }
}
