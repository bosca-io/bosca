package bosca.workops.service

import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.service.MetadataService
import bosca.db.transaction
import bosca.di.ObjectProvider
import bosca.di.provide
import bosca.graphql.Batch
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.PendingPhaseImplementationException
import bosca.workops.model.WorkOpsArchivedException
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.WorkflowTransitionNotAvailableException
import bosca.workops.model.audit.FieldChange
import bosca.workops.model.audit.TaskHistoryEntry
import bosca.workops.model.project.Project
import bosca.workops.model.notification.NotificationDelivery
import bosca.workops.model.notification.NotificationDeliveryRequested
import bosca.workops.model.notification.NotificationEvent
import bosca.workops.model.notification.dispatch as dispatchNotification
import bosca.workops.model.task.CreateTaskInput
import bosca.workops.model.task.Task
import bosca.workops.model.task.TaskCreated
import bosca.workops.model.task.TaskDeleted
import bosca.workops.model.task.TaskTransitioned
import bosca.workops.model.task.TaskUpdated
import bosca.workops.model.task.UpdateTaskInput
import bosca.workops.model.task.dispatch
import bosca.workops.model.workflow.Condition
import bosca.workops.model.workflow.PostFunction
import bosca.workops.model.workflow.Validator
import bosca.workops.repository.TaskHistoryRepository
import bosca.workops.repository.TaskPermissionRepository
import bosca.workops.repository.TaskRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

@ServiceImplementation
class TaskServiceImpl(
    private val taskRepository: TaskRepository,
    private val taskHistoryRepository: TaskHistoryRepository,
    private val projectService: ProjectService,
    private val programService: ProgramService,
    private val taskTypeService: TaskTypeService,
    private val taskTypeSchemeService: TaskTypeSchemeService,
    private val statusService: StatusService,
    private val priorityService: PriorityService,
    private val workflowService: WorkflowService,
    private val workflowEvaluator: WorkflowEvaluator,
    private val customFieldService: TaskCustomFieldService,
    private val taskPermissionRepository: TaskPermissionRepository,
    private val affectedProjectService: TaskAffectedProjectService,
    private val requirementServiceProvider: ObjectProvider<RequirementService>,
    private val metadataService: MetadataService,
    private val sprintService: SprintService,
    private val projectPermissionEvaluator: ProjectPermissionEvaluator,
    private val json: Json,
) : TaskService {

    override suspend fun getPermissions(entity: Task): List<EntityPermission> =
        taskPermissionRepository.getByTaskId(entity.id)

    override suspend fun addPermissionsToBatch(batch: Batch<UUID, List<EntityPermission>>) {
        val permissions = taskPermissionRepository.getByTaskIds(batch.keys)
        val grouped = permissions.groupBy { it.taskId }
        for (key in batch.keys) {
            batch.setData(key, grouped[key]?.let { ArrayList(it) } ?: emptyList())
        }
    }

    override suspend fun isParentAllowed(
        authentication: AuthenticationContext?,
        entity: Task,
        action: PermissionAction,
    ): Boolean {
        val project = projectService.getById(entity.projectId) ?: return false
        return projectPermissionEvaluator.isAllowed(authentication, project, action)
    }

    override suspend fun listByProject(projectId: UUID, offset: Long, limit: Int): List<Task> =
        taskRepository.listByProject(projectId, offset.coerceAtLeast(0), limit.coerceIn(1, MAX_PAGE))

    override suspend fun listByAffectedProject(projectId: UUID, offset: Long, limit: Int): List<Task> =
        taskRepository.listByAffectedProject(projectId, offset.coerceAtLeast(0), limit.coerceIn(1, MAX_PAGE))

    override suspend fun getById(id: UUID): Task? = taskRepository.getActiveById(id)

    override suspend fun getByIdIncludingDeleted(id: UUID): Task? = taskRepository.getById(id)

    override suspend fun getByKey(key: String): Task? = taskRepository.getActiveByKey(key)

    override suspend fun getByIds(ids: List<UUID>): List<Task> =
        if (ids.isEmpty()) emptyList() else taskRepository.getByIds(ids)

    override suspend fun dispatchDueNotifications(limit: Int): Int = transaction {
        val due = taskRepository.findDueForNotification(limit.coerceIn(1, 1_000))
        due.forEach { task ->
            check(taskRepository.markDueNotified(task.id, requireNotNull(task.dueDate)) == 1) {
                "Task ${task.id} disappeared while claiming its due notification"
            }
            NotificationDeliveryRequested(
                NotificationDelivery(
                    event = NotificationEvent.TASK_DUE,
                    taskId = task.id,
                    projectId = task.projectId,
                ),
            ).dispatchNotification()
        }
        due.size
    }

    override suspend fun create(
        input: CreateTaskInput,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
        reporterProfileId: UUID,
    ): Task {
        val saved = transaction {
            if (input.summary.isBlank() || input.summary.length > 255) {
                throw WorkOpsValidationException("summary", "must be 1–255 non-blank chars")
            }
            val project = projectService.getById(input.projectId)
                ?: throw WorkOpsNotFoundException("Project", input.projectId.toString())
            if (project.archivedAt != null) {
                throw WorkOpsArchivedException("Project", project.id)
            }

            val taskTypeId = resolveTaskTypeId(project, input.taskTypeId)
            val statusId = input.statusId ?: defaultStatusId()
            val priorityId = input.priorityId ?: defaultPriorityId()

            // R3 acceptance criterion: type must belong to the project's
            // active task-type scheme.
            validateTypeAgainstScheme(project, taskTypeId)

            // R5: validate the customFields against the project's
            // field-configuration scheme. Required-but-missing rejects;
            // defaults from the scheme are applied; "unknown" keys pass
            // through (Phase 7 / strict-mode tightens this).
            val composedCustomFields = customFieldService.composeForCreate(
                project = project,
                taskTypeId = taskTypeId,
                provided = input.customFields,
            )

            // Mint the next per-project sequence number atomically.
            val seq = projectService.reserveNextTaskSequence(project.id)
            val key = "${project.key}-$seq"

            val saved = taskRepository.add(
                Task(
                    key = key,
                    projectId = project.id,
                    taskTypeId = taskTypeId,
                    statusId = statusId,
                    priorityId = priorityId,
                    summary = input.summary,
                    descriptionMarkdown = input.descriptionMarkdown,
                    descriptionHtml = null,
                    reporterProfileId = reporterProfileId,
                    assigneeProfileId = input.assigneeProfileId,
                    parentTaskId = input.parentTaskId,
                    sprintId = input.sprintId,
                    dueDate = input.dueDate,
                    startDate = input.startDate,
                    customFieldValues = composedCustomFields,
                    createdByPrincipalId = actingPrincipalId,
                    modifiedByPrincipalId = actingPrincipalId,
                )
            )
            writeHistory(
                taskId = saved.id,
                actingPrincipalId = actingPrincipalId,
                actingProfileId = actingProfileId,
                changes = listOf(
                    FieldChange(fieldKey = "created", toValue = JsonPrimitive(saved.key)),
                ),
            )
            val createdSprintId = saved.sprintId
            if (createdSprintId != null) {
                sprintService.addTask(createdSprintId, saved.id)
            }
            for (affectedProjectId in input.affectedProjectIds) {
                affectedProjectService.add(saved.id, affectedProjectId)
            }
            TaskCreated(taskId = saved.id, projectId = saved.projectId, reporterProfileId = saved.reporterProfileId, assigneeProfileId = saved.assigneeProfileId).dispatch()
            saved
        }
        fireTaskAutomation(saved) { dispatcher, programId, portfolioId ->
            dispatcher.fireTaskCreated(saved, saved.projectId, programId, portfolioId)
        }
        return saved
    }

    override suspend fun update(
        id: UUID,
        input: UpdateTaskInput,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ): Task {
        val result = transaction {
            val existing = taskRepository.getActiveById(id)
                ?: throw WorkOpsNotFoundException("Task", id.toString())
            if (existing.version != input.expectedVersion) {
                throw OptimisticLockFailedException("Task", id)
            }

            val newSummary = input.summary ?: existing.summary
            if (newSummary.isBlank() || newSummary.length > 255) {
                throw WorkOpsValidationException("summary", "must be 1–255 non-blank chars")
            }
            val newDescriptionMarkdown = input.descriptionMarkdown ?: existing.descriptionMarkdown
            val newTaskTypeId = input.taskTypeId ?: existing.taskTypeId
            if (input.taskTypeId != null && input.taskTypeId != existing.taskTypeId) {
                val project = projectService.getById(existing.projectId)
                    ?: throw WorkOpsNotFoundException("Project", existing.projectId.toString())
                validateTypeAgainstScheme(project, newTaskTypeId)
            }
            val newAssigneeProfileId = when {
                input.clearAssignee -> null
                input.assigneeProfileId != null -> input.assigneeProfileId
                else -> existing.assigneeProfileId
            }
            val newPriorityId = input.priorityId ?: existing.priorityId
            val newSprintId = when {
                input.clearSprintId -> null
                input.sprintId != null -> input.sprintId
                else -> existing.sprintId
            }
            val newStatusId = existing.statusId
            val newDueDate = when {
                input.clearDueDate -> null
                input.dueDate != null -> input.dueDate
                else -> existing.dueDate
            }
            val newStartDate = when {
                input.clearStartDate -> null
                input.startDate != null -> input.startDate
                else -> existing.startDate
            }
            val newOriginalEstimate = when {
                input.clearOriginalEstimate -> null
                input.originalEstimateSeconds != null -> input.originalEstimateSeconds
                else -> existing.originalEstimateSeconds
            }
            val newRemainingEstimate = when {
                input.clearRemainingEstimate -> null
                input.remainingEstimateSeconds != null -> input.remainingEstimateSeconds
                else -> existing.remainingEstimateSeconds
            }
            val newParentTaskId = when {
                input.clearParentTask -> null
                input.parentTaskId != null -> input.parentTaskId
                else -> existing.parentTaskId
            }

            val automationChangedKeys = buildSet {
                if (existing.summary != newSummary) add("summary")
                if (existing.descriptionMarkdown != newDescriptionMarkdown) add("descriptionMarkdown")
                if (existing.taskTypeId != newTaskTypeId) add("taskTypeId")
                if (existing.assigneeProfileId != newAssigneeProfileId) add("assigneeProfileId")
                if (existing.priorityId != newPriorityId) add("priorityId")
                if (existing.sprintId != newSprintId) add("sprintId")
                if (existing.dueDate != newDueDate) add("dueDate")
                if (existing.startDate != newStartDate) add("startDate")
                if (existing.originalEstimateSeconds != newOriginalEstimate) add("originalEstimateSeconds")
                if (existing.remainingEstimateSeconds != newRemainingEstimate) add("remainingEstimateSeconds")
                if (existing.parentTaskId != newParentTaskId) add("parentTaskId")
            }
            if (automationChangedKeys.isEmpty()) {
                return@transaction TaskUpdateResult(existing, emptySet())
            }

            val updated = taskRepository.updateCore(
                id = id,
                summary = newSummary,
                descriptionMarkdown = newDescriptionMarkdown,
                descriptionHtml = existing.descriptionHtml,
                taskTypeId = newTaskTypeId,
                assigneeProfileId = newAssigneeProfileId,
                priorityId = newPriorityId,
                statusId = newStatusId,
                sprintId = newSprintId,
                dueDate = newDueDate,
                startDate = newStartDate,
                originalEstimateSeconds = newOriginalEstimate,
                remainingEstimateSeconds = newRemainingEstimate,
                parentTaskId = newParentTaskId,
                modifiedByPrincipalId = actingPrincipalId,
                expectedVersion = input.expectedVersion,
            ) ?: throw OptimisticLockFailedException("Task", id)

            val oldSprintId = existing.sprintId
            val updatedSprintId = updated.sprintId
            if (oldSprintId != updatedSprintId) {
                if (oldSprintId != null) {
                    sprintService.removeTask(oldSprintId, updated.id)
                }
                if (updatedSprintId != null) {
                    sprintService.addTask(updatedSprintId, updated.id)
                }
            }

            val changes = buildList {
                addIfChanged("summary", existing.summary, updated.summary)
                addIfChanged("description_markdown", existing.descriptionMarkdown, updated.descriptionMarkdown)
                addIfChangedUuid("task_type_id", existing.taskTypeId, updated.taskTypeId)
                addIfChangedUuid("assignee_profile_id", existing.assigneeProfileId, updated.assigneeProfileId)
                addIfChangedUuid("priority_id", existing.priorityId, updated.priorityId)
                addIfChangedUuid("sprint_id", existing.sprintId, updated.sprintId)
                addIfChangedDate("due_date", existing.dueDate, updated.dueDate)
                addIfChangedDate("start_date", existing.startDate, updated.startDate)
                addIfChangedLong("original_estimate_seconds", existing.originalEstimateSeconds, updated.originalEstimateSeconds)
                addIfChangedLong("remaining_estimate_seconds", existing.remainingEstimateSeconds, updated.remainingEstimateSeconds)
                addIfChangedUuid("parent_task_id", existing.parentTaskId, updated.parentTaskId)
            }
            if (changes.isNotEmpty()) {
                writeHistory(
                    taskId = updated.id,
                    actingPrincipalId = actingPrincipalId,
                    actingProfileId = actingProfileId,
                    changes = changes,
                )
            }
            TaskUpdated(taskId = updated.id, projectId = updated.projectId, assigneeProfileId = updated.assigneeProfileId, assigneeChanged = existing.assigneeProfileId != updated.assigneeProfileId).dispatch()
            TaskUpdateResult(updated, automationChangedKeys)
        }
        if (result.changedKeys.isEmpty()) return result.task
        fireTaskAutomation(result.task) { dispatcher, programId, portfolioId ->
            dispatcher.fireTaskUpdated(result.task, result.task.projectId, programId, portfolioId, result.changedKeys)
        }
        return result.task
    }

    override suspend fun setCustomFieldValues(
        id: UUID,
        customFieldValues: JsonObject,
        expectedVersion: Long,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ): Task {
        val result = transaction {
            val existing = taskRepository.getActiveById(id)
                ?: throw WorkOpsNotFoundException("Task", id.toString())
            if (existing.version != expectedVersion) {
                throw OptimisticLockFailedException("Task", id)
            }
            val project = projectService.getById(existing.projectId)
                ?: throw WorkOpsNotFoundException("Project", existing.projectId.toString())
            val validatedValues = customFieldService.composeForCreate(
                project = project,
                taskTypeId = existing.taskTypeId,
                provided = customFieldValues,
            )
            val changedKeys = (existing.customFieldValues.keys + validatedValues.keys)
                .filterTo(linkedSetOf()) { key ->
                    existing.customFieldValues[key] != validatedValues[key]
                }
            if (changedKeys.isEmpty()) {
                return@transaction CustomFieldUpdateResult(existing, emptySet())
            }
            val saved = taskRepository.setCustomFieldValues(
                id = id,
                customFieldValues = validatedValues,
                modifiedByPrincipalId = actingPrincipalId,
                expectedVersion = expectedVersion,
            ) ?: throw OptimisticLockFailedException("Task", id)
            writeHistory(
                taskId = saved.id,
                actingPrincipalId = actingPrincipalId,
                actingProfileId = actingProfileId,
                changes = changedKeys.map { key ->
                    FieldChange(key, existing.customFieldValues[key], saved.customFieldValues[key])
                },
            )
            TaskUpdated(
                taskId = saved.id,
                projectId = saved.projectId,
                assigneeProfileId = saved.assigneeProfileId,
                assigneeChanged = false,
            ).dispatch()
            CustomFieldUpdateResult(saved, changedKeys)
        }
        if (result.changedKeys.isEmpty()) return result.task
        fireTaskAutomation(result.task) { dispatcher, programId, portfolioId ->
            dispatcher.fireTaskUpdated(
                result.task,
                result.task.projectId,
                programId,
                portfolioId,
                result.changedKeys,
            )
        }
        return result.task
    }

    override suspend fun softDelete(
        id: UUID,
        expectedVersion: Long,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ): Task {
        val deleted = transaction {
            val updated = taskRepository.softDelete(id, actingPrincipalId, expectedVersion)
                ?: run {
                    val current = taskRepository.getById(id)
                        ?: throw WorkOpsNotFoundException("Task", id.toString())
                    if (current.deletedAt != null) {
                        return@run current
                    }
                    throw OptimisticLockFailedException("Task", id)
                }
            writeHistory(
                taskId = updated.id,
                actingPrincipalId = actingPrincipalId,
                actingProfileId = actingProfileId,
                changes = listOf(
                    FieldChange(
                        fieldKey = "deleted_at",
                        fromValue = JsonNull,
                        toValue = JsonPrimitive(updated.deletedAt?.toString()),
                    ),
                ),
            )
            val deletedSprintId = updated.sprintId
            if (deletedSprintId != null) {
                sprintService.removeTask(deletedSprintId, updated.id)
            }
            TaskDeleted(taskId = updated.id, projectId = updated.projectId).dispatch()
            updated
        }
        fireTaskAutomation(deleted) { dispatcher, programId, portfolioId ->
            dispatcher.fireTaskDeleted(deleted, deleted.projectId, programId, portfolioId)
        }
        return deleted
    }

    override suspend fun restore(
        id: UUID,
        expectedVersion: Long,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ): Task = transaction {
        val updated = taskRepository.restore(id, actingPrincipalId, expectedVersion)
            ?: run {
                val current = taskRepository.getById(id)
                    ?: throw WorkOpsNotFoundException("Task", id.toString())
                if (current.deletedAt == null) {
                    return@run current
                }
                throw OptimisticLockFailedException("Task", id)
            }
        writeHistory(
            taskId = updated.id,
            actingPrincipalId = actingPrincipalId,
            actingProfileId = actingProfileId,
            changes = listOf(
                FieldChange(
                    fieldKey = "deleted_at",
                    fromValue = JsonPrimitive("set"),
                    toValue = JsonNull,
                ),
            ),
        )
        updated
    }

    override suspend fun listHistory(taskId: UUID, offset: Long, limit: Int): List<TaskHistoryEntry> =
        taskHistoryRepository.listByTask(taskId, offset.coerceAtLeast(0), limit.coerceIn(1, MAX_PAGE))

    override suspend fun transition(
        id: UUID,
        transitionId: UUID,
        expectedVersion: Long,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
        principalPermissions: Set<PermissionAction>,
        resolutionId: UUID?,
        comment: String?,
    ): Task {
        val (task, updated) = transaction {
            val task = taskRepository.getActiveById(id)
                ?: throw WorkOpsNotFoundException("Task", id.toString())

            val resolution = workflowService.resolveWorkflowForTask(task)
            val transition = resolution.transitions.firstOrNull { it.id == transitionId }
                ?: throw WorkflowTransitionNotAvailableException(id, transitionId)
            // The transition must be reachable from the current state.
            if (resolution.transitions.matching(resolution.currentState.id).none { it.id == transition.id }) {
                throw WorkflowTransitionNotAvailableException(id, transitionId)
            }

            val unresolvedSubtasks = workflowService.countUnresolvedSubtasks(id)
            val context = WorkflowContext(
                task = task,
                actingPrincipalId = actingPrincipalId,
                actingProfileId = actingProfileId,
                comment = comment,
                resolutionId = resolutionId,
                unresolvedSubtaskCount = unresolvedSubtasks,
                principalPermissions = principalPermissions,
            )
            val decodedConditions = json.decodeFromJsonElement(
                ListSerializer(Condition.serializer()),
                transition.conditions,
            )
            val decodedValidators = json.decodeFromJsonElement(
                ListSerializer(Validator.serializer()),
                transition.validators,
            )
            val decodedPostFunctions = json.decodeFromJsonElement(
                ListSerializer(PostFunction.serializer()),
                transition.postFunctions,
            )
            val plan = workflowEvaluator.evaluate(
                transition = transition,
                conditions = decodedConditions,
                validators = decodedValidators,
                postFunctions = decodedPostFunctions,
                context = context,
            ).requireApprovedOrThrow()

            // Resolve the target state to a status id.
            val resolvedTransition = resolution.transitions.firstOrNull { it.id == transition.id }
                ?: error("transition ${transition.id} resolved without a to_state_id")
            val targetState = resolvedTransition.toStateId
            val targetStateRow = resolution.transitions
                // The full WorkflowState shape isn't on the transition; load it.
                .let { workflowService.listStates(resolution.workflow.id) }
                .firstOrNull { it.id == targetState }
                ?: error("workflow state $targetState missing for workflow ${resolution.workflow.id}")
            val newStatusId = targetStateRow.statusId

            // Apply post-functions onto an in-memory copy of the task. The
            // sync-safe variants flip fields here; async variants (Phase 8
            // webhooks, Phase 11 scripts) raise PendingPhase and abort the
            // transition.
            var assigneeProfileId: UUID? = task.assigneeProfileId
            var newResolutionId: UUID? = task.resolutionId
            var newResolutionAt: OffsetDateTime? = task.resolutionAt
            var summary: String = task.summary
            var descriptionMarkdown: String? = task.descriptionMarkdown
            var priorityId: UUID = task.priorityId
            var dueDate: OffsetDateTime? = task.dueDate
            var startDate: OffsetDateTime? = task.startDate

            // If the workflow declared no SetResolution post-function but
            // the caller supplied a resolutionId on the transition screen,
            // honor it — that's the canonical "Resolve" UX.
            if (resolutionId != null) {
                newResolutionId = resolutionId
                newResolutionAt = OffsetDateTime.now()
            }

            for (postFn in plan.postFunctions) {
                when (postFn) {
                    is PostFunction.SetField -> when (postFn.fieldKey) {
                        "summary" -> summary = postFn.value.toString().trim('"')
                        "description_markdown" -> descriptionMarkdown = postFn.value.toString().trim('"')
                        "priority_id" -> priorityId = UUID.parse(postFn.value.toString().trim('"'))
                        "assignee_profile_id" -> assigneeProfileId =
                            postFn.value.toString().trim('"').takeIf { it != "null" }?.let(UUID::parse)

                        "due_date" -> dueDate = postFn.value.toString().trim('"').takeIf { it != "null" }?.let(OffsetDateTime::parse)
                        "start_date" -> startDate = postFn.value.toString().trim('"').takeIf { it != "null" }?.let(OffsetDateTime::parse)
                        else -> throw PendingPhaseImplementationException(
                            variant = "PostFunction.SetField('${postFn.fieldKey}')",
                            owningPhase = 4,
                        )
                    }

                    is PostFunction.SetResolution -> {
                        newResolutionId = postFn.resolutionId
                        newResolutionAt = OffsetDateTime.now()
                    }

                    is PostFunction.ClearResolution -> {
                        newResolutionId = null
                        newResolutionAt = null
                    }

                    is PostFunction.AssignToReporter -> assigneeProfileId = task.reporterProfileId
                    is PostFunction.AssignToCurrentUser -> assigneeProfileId = actingProfileId
                    is PostFunction.Unassign -> assigneeProfileId = null
                    is PostFunction.AddComment ->
                        throw PendingPhaseImplementationException("PostFunction.AddComment", owningPhase = 4)

                    is PostFunction.EmitWebhook ->
                        throw PendingPhaseImplementationException("PostFunction.EmitWebhook", owningPhase = 8)

                    is PostFunction.RunScript ->
                        throw PendingPhaseImplementationException("PostFunction.RunScript", owningPhase = 11)
                }
            }

            val updated = taskRepository.applyTransition(
                id = id,
                statusId = newStatusId,
                assigneeProfileId = assigneeProfileId,
                resolutionId = newResolutionId,
                resolutionAt = newResolutionAt,
                summary = summary,
                descriptionMarkdown = descriptionMarkdown,
                priorityId = priorityId,
                dueDate = dueDate,
                startDate = startDate,
                parentTaskId = task.parentTaskId,
                epicTaskId = task.epicTaskId,
                modifiedByPrincipalId = actingPrincipalId,
                expectedVersion = expectedVersion,
            ) ?: throw OptimisticLockFailedException("Task", id)

            // Single history entry covering the bundle (R17). The
            // `transitionId` is recorded on the entry so the audit log
            // reads "Alice transitioned X via 'Resolve' and the workflow
            // set Reviewer to Bob" as one record.
            val changes = buildList {
                addIfChangedUuid("status_id", task.statusId, updated.statusId)
                addIfChangedUuid("resolution_id", task.resolutionId, updated.resolutionId)
                addIfChangedUuid("assignee_profile_id", task.assigneeProfileId, updated.assigneeProfileId)
                addIfChanged("summary", task.summary, updated.summary)
                addIfChanged("description_markdown", task.descriptionMarkdown, updated.descriptionMarkdown)
                addIfChangedUuid("priority_id", task.priorityId, updated.priorityId)
                addIfChangedDate("due_date", task.dueDate, updated.dueDate)
                addIfChangedDate("start_date", task.startDate, updated.startDate)
                add(FieldChange(fieldKey = "transition_id", toValue = JsonPrimitive(transition.id.toString())))
            }
            writeHistory(
                taskId = updated.id,
                actingPrincipalId = actingPrincipalId,
                actingProfileId = actingProfileId,
                changes = changes,
            )
            TaskTransitioned(taskId = updated.id, projectId = updated.projectId, fromStatusId = task.statusId, toStatusId = updated.statusId, transitionId = transition.id, resolutionId = resolutionId).dispatch()
            Pair(task, updated)
        }
        if (task.statusId != updated.statusId) {
            requirementServiceProvider.get().syncStatusFromTask(updated.id, updated.statusId, actingPrincipalId, actingProfileId)
        }
        fireTaskAutomation(updated) { dispatcher, programId, portfolioId ->
            dispatcher.fireTaskTransitioned(updated, updated.projectId, programId, portfolioId, task.statusId, updated.statusId)
        }
        return updated
    }

    private suspend fun resolveTaskTypeId(project: Project, requested: UUID?): UUID {
        if (requested != null) return requested
        val schemeId = project.defaultTaskTypeSchemeId
            ?: return DEFAULT_TASK_TYPE_ID_TASK
        val scheme = taskTypeSchemeService.getById(schemeId)
            ?: return DEFAULT_TASK_TYPE_ID_TASK
        return scheme.defaultTaskTypeId
    }

    private suspend fun validateTypeAgainstScheme(
        project: Project,
        taskTypeId: UUID,
    ) {
        val schemeId = project.defaultTaskTypeSchemeId ?: return
        val scheme = taskTypeSchemeService.getById(schemeId) ?: return
        if (taskTypeId !in scheme.taskTypeIds) {
            throw WorkOpsValidationException(
                "taskTypeId",
                "type $taskTypeId is not a member of project ${project.key}'s scheme",
            )
        }
        // Also confirm the type row itself exists so we surface a
        // clean error on stale references rather than an opaque FK
        // violation from the database.
        taskTypeService.getById(taskTypeId)
            ?: throw WorkOpsNotFoundException("TaskType", taskTypeId.toString())
    }

    private suspend fun defaultStatusId(): UUID {
        // Phase 2 uses the seeded "To Do" status as the default
        // landing state. Phase 3's workflow engine replaces this with
        // a per-project initial state.
        val status = statusService.list().firstOrNull { it.name == "To Do" }
            ?: error("seed status 'To Do' missing — V2 seeds drifted")
        return status.id
    }

    private suspend fun defaultPriorityId(): UUID {
        val priority = priorityService.list().firstOrNull { it.name == "Medium" }
            ?: error("seed priority 'Medium' missing — V2 seeds drifted")
        return priority.id
    }

    private suspend fun writeHistory(
        taskId: UUID,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
        changes: List<FieldChange>,
    ) {
        // Serialize the change list to a JSON array on the way to the
        // jsonb column. Bosca's repository binder can't bind a
        // List<@Serializable> directly through its Postgres array
        // adapter (R17 would need a per-element check constraint
        // anyway), so the service owns the encode step.
        taskHistoryRepository.add(
            taskId = taskId,
            changedAt = OffsetDateTime.now(),
            changedByPrincipalId = actingPrincipalId,
            changedByProfileId = actingProfileId,
            changes = json.encodeToJsonElement(ListSerializer(FieldChange.serializer()), changes),
        )
    }

    private fun MutableList<FieldChange>.addIfChanged(key: String, before: String?, after: String?) {
        if (before != after) {
            add(FieldChange(key, historyText(before), historyText(after)))
        }
    }

    private fun MutableList<FieldChange>.addIfChangedUuid(key: String, before: UUID?, after: UUID?) {
        if (before != after) {
            add(FieldChange(key, historyText(before), historyText(after)))
        }
    }

    private fun MutableList<FieldChange>.addIfChangedDate(key: String, before: OffsetDateTime?, after: OffsetDateTime?) {
        if (before != after) {
            add(FieldChange(key, historyText(before), historyText(after)))
        }
    }

    private fun MutableList<FieldChange>.addIfChangedLong(key: String, before: Long?, after: Long?) {
        if (before != after) {
            add(FieldChange(key, historyLong(before), historyLong(after)))
        }
    }

    private fun historyText(value: Any?): kotlinx.serialization.json.JsonElement =
        if (value == null) JsonNull else JsonPrimitive(value.toString())

    private fun historyLong(value: Long?): kotlinx.serialization.json.JsonElement =
        if (value == null) JsonNull else JsonPrimitive(value)

    override suspend fun createDocument(
        id: UUID,
        expectedVersion: Long,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ): Task = transaction {
        val existing = taskRepository.getActiveById(id)
            ?: throw WorkOpsNotFoundException("Task", id.toString())
        if (existing.metadataId != null) {
            throw WorkOpsValidationException("metadataId", "task already has a document")
        }
        val metadata = metadataService.add(
            parent = null,
            collectionItemAttributes = null,
            input = MetadataInput(
                name = existing.summary,
                contentType = "bosca/v-document",
                languageTag = "en",
                document = DocumentInput(title = existing.summary, content = null),
            ),
        )
        val updated = taskRepository.setMetadataId(
            id = id,
            metadataId = metadata.id,
            modifiedByPrincipalId = actingPrincipalId,
            expectedVersion = expectedVersion,
        ) ?: throw OptimisticLockFailedException("Task", id)
        writeHistory(
            taskId = updated.id,
            actingPrincipalId = actingPrincipalId,
            actingProfileId = actingProfileId,
            changes = listOf(
                FieldChange("metadata_id", JsonNull, JsonPrimitive(metadata.id.toString())),
            ),
        )
        updated
    }

    /**
     * Fires WorkOps automation rules for a task event AFTER the triggering transaction has
     * committed. Running post-commit (not inside the mutation's transaction) keeps the executor's
     * own task mutations out of this transaction and lets the per-task loop guard observe committed
     * fire counts between cascading rules. The dispatcher is resolved lazily via [provide] to break
     * the construction cycle TaskService → AutomationDispatcher → AutomationExecutor → TaskService.
     * Automation failures are logged, never propagated — they must not fail the user's mutation.
     */
    private suspend fun fireTaskAutomation(
        task: Task,
        fire: suspend (dispatcher: AutomationDispatcher, programId: UUID?, portfolioId: UUID?) -> Unit,
    ) {
        try {
            val project = projectService.getById(task.projectId) ?: return
            val programId = project.programId
            val program = programService.getById(programId)
            val portfolioId = if (program == null) null else program.portfolioId
            fire(provide<AutomationDispatcher>(), programId, portfolioId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Automation dispatch failed for task {}: {}", task.id, e.message, e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(TaskServiceImpl::class.java)
        private const val MAX_PAGE = 200

        /** Seeded "Task" type id — the conventional default. */
        val DEFAULT_TASK_TYPE_ID_TASK: UUID =
            UUID.parse("00000000-0000-0000-0000-000000000003")
    }

    private data class CustomFieldUpdateResult(
        val task: Task,
        val changedKeys: Set<String>,
    )

    private data class TaskUpdateResult(
        val task: Task,
        val changedKeys: Set<String>,
    )
}
