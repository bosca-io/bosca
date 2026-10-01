package bosca.workops.service

import bosca.comments.model.CommentStatus
import bosca.db.transaction
import bosca.profile.profile.service.ProfileService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.audit.FieldChange
import bosca.workops.model.comment.TaskComment
import bosca.workops.model.comment.TaskCommentInput
import bosca.workops.model.notification.NotificationDelivery
import bosca.workops.model.notification.NotificationDeliveryRequested
import bosca.workops.model.notification.NotificationEvent
import bosca.workops.model.task.Task
import bosca.workops.model.task.TaskCommented
import bosca.workops.model.task.dispatch
import bosca.workops.repository.TaskCommentRepository
import bosca.workops.repository.TaskHistoryRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import bosca.workops.model.notification.dispatch as dispatchNotification

private val profileMentionRegex = Regex(
    pattern = "(?<![A-Za-z0-9_@])@([A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?)(?![A-Za-z0-9._-])",
    option = RegexOption.IGNORE_CASE,
)

internal fun parseProfileMentions(content: String): Set<String> = profileMentionRegex.findAll(content)
    .map { it.groupValues[1].lowercase() }
    .toSet()

internal suspend fun resolveProfileMentions(content: String, profileService: ProfileService): Set<UUID> = buildSet {
    for (slug in parseProfileMentions(content)) {
        profileService.getBySlug(slug)?.let { profile -> add(profile.id) }
    }
}

@ServiceImplementation
class TaskCommentServiceImpl(
    private val commentRepository: TaskCommentRepository,
    private val taskService: TaskService,
    private val taskHistoryRepository: TaskHistoryRepository,
    private val projectService: ProjectService,
    private val programService: ProgramService,
    private val automationDispatcher: AutomationDispatcher,
    private val profileService: ProfileService,
    private val json: Json,
) : TaskCommentService {

    override suspend fun add(
        taskId: UUID,
        input: TaskCommentInput,
        actingPrincipalId: UUID,
        actingProfileId: UUID,
        impersonatorId: UUID?,
    ): TaskComment {
        var commentedTask: Task? = null
        val saved = transaction {
            if (input.content.isBlank()) {
                throw WorkOpsValidationException("content", "must be non-blank")
            }
            val task = taskService.getById(taskId)
                ?: throw WorkOpsNotFoundException("Task", taskId.toString())

            // Reply parent must belong to the same task — same rule as
            // metadata comments rejecting parents from a different
            // (metadata_id, version) pair.
            val parentId = input.parentId
            if (parentId != null) {
                val parent = commentRepository.getById(parentId)
                    ?: throw WorkOpsNotFoundException("TaskComment", parentId.toString())
                if (parent.taskId != taskId) {
                    throw WorkOpsValidationException("parentId", "parent comment is on a different task")
                }
            }

            val newId = commentRepository.add(
                parentId = parentId,
                taskId = taskId,
                profileId = actingProfileId,
                impersonatorId = impersonatorId,
                visibility = input.visibility,
                content = input.content,
                attributes = input.attributes,
                systemAttributes = input.systemAttributes,
            )
            val saved = commentRepository.getById(newId)
                ?: error("freshly inserted task comment $newId vanished")

            writeHistory(
                taskId = taskId,
                actingPrincipalId = actingPrincipalId,
                actingProfileId = actingProfileId,
                fieldKey = "comment",
                toValue = JsonPrimitive("added:$newId"),
            )
            TaskCommented(
                taskId = taskId,
                projectId = task.projectId,
                commentId = newId,
                profileId = actingProfileId,
                mentionedProfileIds = resolveProfileMentions(input.content, profileService),
            ).dispatch()
            commentedTask = task
            saved
        }
        commentedTask?.let { fireCommentedAutomation(it) }
        return saved
    }

    /**
     * Fires comment-triggered automation after the comment transaction commits (see
     * `TaskServiceImpl.fireTaskAutomation` for the post-commit / lazy-[provide] rationale).
     * Failures are logged, never propagated — automation must not fail the comment write.
     */
    private suspend fun fireCommentedAutomation(task: Task) {
        try {
            val project = projectService.getById(task.projectId) ?: return
            val programId = project.programId
            val portfolioId = programService.getById(programId)?.portfolioId
            automationDispatcher.fireTaskCommented(task, task.projectId, programId, portfolioId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Automation dispatch failed for comment on task {}: {}", task.id, e.message, e)
        }
    }

    override suspend fun get(
        taskId: UUID,
        commentId: Long,
        viewingProfileId: UUID?,
        manager: Boolean,
    ): TaskComment? = when {
        manager -> commentRepository.getManager(taskId, commentId)
        viewingProfileId != null -> commentRepository.getForProfile(taskId, commentId, viewingProfileId)
        else -> commentRepository.getPublic(taskId, commentId)
    }

    override suspend fun list(
        taskId: UUID,
        viewingProfileId: UUID?,
        manager: Boolean,
        offset: Long,
        limit: Long,
    ): List<TaskComment> = when {
        manager -> commentRepository.listManager(taskId, offset.coerceAtLeast(0), limit.coerceIn(1, MAX_PAGE))
        viewingProfileId != null ->
            commentRepository.listForProfile(taskId, viewingProfileId, offset.coerceAtLeast(0), limit.coerceIn(1, MAX_PAGE))

        else -> commentRepository.listPublic(taskId, offset.coerceAtLeast(0), limit.coerceIn(1, MAX_PAGE))
    }

    override suspend fun count(taskId: UUID, viewingProfileId: UUID?, manager: Boolean): Long = when {
        manager -> commentRepository.countManager(taskId)
        viewingProfileId != null -> commentRepository.countForProfile(taskId, viewingProfileId)
        else -> commentRepository.countPublic(taskId)
    }

    override suspend fun listReplies(
        taskId: UUID,
        parentId: Long,
        viewingProfileId: UUID?,
        manager: Boolean,
        offset: Long,
        limit: Long,
    ): List<TaskComment> = when {
        manager ->
            commentRepository.listRepliesManager(taskId, parentId, offset.coerceAtLeast(0), limit.coerceIn(1, MAX_PAGE))

        viewingProfileId != null -> commentRepository.listRepliesForProfile(
            taskId, viewingProfileId, parentId, offset.coerceAtLeast(0), limit.coerceIn(1, MAX_PAGE),
        )

        else -> commentRepository.listRepliesPublic(taskId, parentId, offset.coerceAtLeast(0), limit.coerceIn(1, MAX_PAGE))
    }

    override suspend fun like(taskId: UUID, commentId: Long, profileId: UUID): Int = transaction {
        val likes = commentRepository.incrementLikes(taskId, commentId) ?: -1
        if (likes > 0) commentRepository.addLikeRow(commentId, profileId)
        likes
    }

    override suspend fun unlike(taskId: UUID, commentId: Long, profileId: UUID): Int = transaction {
        val likes = commentRepository.decrementLikes(taskId, commentId) ?: -1
        if (likes >= 0) {
            val deleted = commentRepository.deleteLikeRow(commentId, profileId)
            if (deleted == null) {
                // The like row didn't exist for this profile —
                // throwing rolls the decrement back so the counter
                // stays consistent.
                error("Like row for profile $profileId on comment $commentId not found")
            }
        }
        likes
    }

    override suspend fun setStatus(
        taskId: UUID,
        commentId: Long,
        status: CommentStatus,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ) = transaction {
        val task = taskService.getById(taskId)
            ?: throw WorkOpsNotFoundException("Task", taskId.toString())
        val comment = commentRepository.getManager(taskId, commentId)
            ?: throw WorkOpsNotFoundException("TaskComment", commentId.toString())
        commentRepository.setStatus(taskId, commentId, status)
        writeHistory(
            taskId = taskId,
            actingPrincipalId = actingPrincipalId,
            actingProfileId = actingProfileId,
            fieldKey = "comment_status",
            toValue = JsonPrimitive("$commentId:$status"),
        )
        NotificationDeliveryRequested(
            NotificationDelivery(
                event = NotificationEvent.TASK_COMMENT_EDITED,
                taskId = task.id,
                projectId = task.projectId,
                actorProfileId = actingProfileId,
                mentionedProfileIds = resolveProfileMentions(comment.content, profileService),
                commentId = commentId,
            ),
        ).dispatchNotification()
    }

    override suspend fun delete(
        taskId: UUID,
        commentId: Long,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ) = transaction {
        val task = taskService.getById(taskId)
            ?: throw WorkOpsNotFoundException("Task", taskId.toString())
        commentRepository.softDelete(taskId, commentId)
        writeHistory(
            taskId = taskId,
            actingPrincipalId = actingPrincipalId,
            actingProfileId = actingProfileId,
            fieldKey = "comment",
            toValue = JsonPrimitive("deleted:$commentId"),
        )
        NotificationDeliveryRequested(
            NotificationDelivery(
                event = NotificationEvent.TASK_COMMENT_DELETED,
                taskId = task.id,
                projectId = task.projectId,
                actorProfileId = actingProfileId,
                commentId = commentId,
            ),
        ).dispatchNotification()
    }

    override suspend fun deleteByAuthor(
        taskId: UUID,
        commentId: Long,
        profileId: UUID,
        actingPrincipalId: UUID,
    ) = transaction {
        val task = taskService.getById(taskId)
            ?: throw WorkOpsNotFoundException("Task", taskId.toString())
        commentRepository.softDeleteByProfile(taskId, commentId, profileId)
        writeHistory(
            taskId = taskId,
            actingPrincipalId = actingPrincipalId,
            actingProfileId = profileId,
            fieldKey = "comment",
            toValue = JsonPrimitive("deleted_by_author:$commentId"),
        )
        NotificationDeliveryRequested(
            NotificationDelivery(
                event = NotificationEvent.TASK_COMMENT_DELETED,
                taskId = task.id,
                projectId = task.projectId,
                actorProfileId = profileId,
                commentId = commentId,
            ),
        ).dispatchNotification()
    }

    private suspend fun writeHistory(
        taskId: UUID,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
        fieldKey: String,
        toValue: kotlinx.serialization.json.JsonElement,
    ) {
        taskHistoryRepository.add(
            taskId = taskId,
            changedAt = OffsetDateTime.now(),
            changedByPrincipalId = actingPrincipalId,
            changedByProfileId = actingProfileId,
            changes = json.encodeToJsonElement(
                ListSerializer(FieldChange.serializer()),
                listOf(FieldChange(fieldKey = fieldKey, toValue = toValue)),
            ),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(TaskCommentServiceImpl::class.java)
        private const val MAX_PAGE = 100L
    }
}
