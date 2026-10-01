package bosca.workops.service

import bosca.db.transaction
import bosca.graphql.Batch
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.audit.FieldChange
import bosca.workops.model.audit.RequirementHistoryEntry
import bosca.workops.model.requirement.CreateRequirementInput
import bosca.workops.model.requirement.Requirement
import bosca.workops.model.requirement.RequirementParent
import bosca.workops.model.requirement.UpdateRequirementInput
import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.documents.Content
import bosca.documents.MarkdownConverter
import bosca.workops.model.task.CreateTaskInput
import bosca.workops.repository.PriorityRepository
import bosca.workops.repository.ProjectRepository
import bosca.workops.repository.RequirementPermissionRepository
import bosca.workops.repository.RequirementRepository
import bosca.workops.repository.SpecHistoryRepository
import bosca.workops.repository.RequirementKeyCounterRepository
import bosca.workops.repository.SpecRepository
import bosca.workops.repository.StatusRepository
import bosca.workops.repository.TaskRepository
import bosca.workops.repository.WorkflowRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

@ServiceImplementation
class RequirementServiceImpl(
    private val requirementRepository: RequirementRepository,
    private val requirementHistoryRepository: bosca.workops.repository.RequirementHistoryRepository,
    private val keyCounterRepository: RequirementKeyCounterRepository,
    private val projectRepository: ProjectRepository,
    private val specRepository: SpecRepository,
    private val taskRepository: TaskRepository,
    private val taskService: TaskService,
    private val metadataService: MetadataService,
    private val documentService: DocumentService,
    private val statusRepository: StatusRepository,
    private val priorityRepository: PriorityRepository,
    private val workflowRepository: WorkflowRepository,
    private val requirementPermissionRepository: RequirementPermissionRepository,
    private val specPermissionEvaluator: SpecPermissionEvaluator,
    private val taskPermissionEvaluator: TaskPermissionEvaluator,
    private val json: Json,
) : RequirementService {

    override suspend fun getPermissions(entity: Requirement): List<EntityPermission> =
        requirementPermissionRepository.getByRequirementId(entity.id)

    override suspend fun addPermissionsToBatch(batch: Batch<UUID, List<EntityPermission>>) {
        val permissions = requirementPermissionRepository.getByRequirementIds(batch.keys)
        val grouped = permissions.groupBy { it.requirementId }
        for (key in batch.keys) {
            batch.setData(key, grouped[key]?.let { ArrayList(it) } ?: emptyList())
        }
    }

    override suspend fun isParentAllowed(
        authentication: AuthenticationContext?,
        entity: Requirement,
        action: PermissionAction,
    ): Boolean {
        return when (entity.parentType) {
            RequirementParent.SPEC -> {
                val spec = specRepository.getActiveById(entity.parentId) ?: return false
                specPermissionEvaluator.isAllowed(authentication, spec, action)
            }
            RequirementParent.TASK -> {
                val task = taskRepository.getActiveById(entity.parentId) ?: return false
                taskPermissionEvaluator.isAllowed(authentication, task, action)
            }
        }
    }

    override suspend fun getById(id: UUID): Requirement? = requirementRepository.getActiveById(id)

    override suspend fun getByIdIncludingDeleted(id: UUID): Requirement? = requirementRepository.getById(id)

    override suspend fun getByKey(key: String): Requirement? = requirementRepository.getActiveByKey(key)

    override suspend fun getByIds(ids: List<UUID>): List<Requirement> =
        if (ids.isEmpty()) emptyList() else requirementRepository.getByIds(ids)

    override suspend fun getByTaskId(taskId: UUID): Requirement? = requirementRepository.getByTaskId(taskId)

    override suspend fun listByParent(
        parentType: RequirementParent,
        parentId: UUID,
        offset: Long,
        limit: Int,
    ): List<Requirement> =
        requirementRepository.listByParent(parentType, parentId, offset.coerceAtLeast(0), limit.coerceIn(1, MAX_PAGE))

    override suspend fun countByParent(parentType: RequirementParent, parentId: UUID): Long =
        requirementRepository.countByParent(parentType, parentId)

    override suspend fun create(
        input: CreateRequirementInput,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ): Requirement {
        validateParentExists(input.parentType, input.parentId)

        val projectId = resolveProjectId(input.parentType, input.parentId)
        val project = projectRepository.getById(projectId)
            ?: throw WorkOpsNotFoundException("Project", projectId.toString())

        // Reserve the key sequence outside the transaction so the counter
        // increment auto-commits and cannot be rolled back by a later failure.
        val seq = keyCounterRepository.reserveNext(projectId)
            ?: run {
                keyCounterRepository.initialize(projectId)
                keyCounterRepository.reserveNext(projectId)
                    ?: error("requirement_key_counter initialization failed for $projectId")
            }
        val key = "${project.key}-REQ-$seq"

        return transaction {
            val workflowId = input.workflowId ?: resolveDefaultWorkflowId()
            val statusId = defaultStatusId()
            val priorityId = input.priorityId ?: defaultPriorityId()

            val metadata = input.metadataId?.let {
                metadataService.getById(it) ?: throw WorkOpsNotFoundException("Metadata", it.toString())
            } ?: createMetadata(input.name ?: key)
            // Bind the requirement document to the Requirement template: sets the
            // template link and merges the template's default attributes (notably
            // `type`) onto the metadata, for both freshly-created and caller-supplied
            // metadata.
            metadataService.setDocumentTemplate(metadata, REQUIREMENT_TEMPLATE_ID, REQUIREMENT_TEMPLATE_VERSION)
            val metadataId = metadata.id

            val reporterProfileId = actingProfileId ?: resolveReporterProfileId(input.parentType, input.parentId)
            val task = createLinkedTask(
                metadataId = metadataId,
                projectId = projectId,
                requirementKey = key,
                priorityId = priorityId,
                assigneeProfileId = input.assigneeProfileId,
                actingPrincipalId = actingPrincipalId,
                actingProfileId = actingProfileId,
                reporterProfileId = reporterProfileId,
            )

            val saved = requirementRepository.add(
                Requirement(
                    key = key,
                    metadataId = metadataId,
                    parentType = input.parentType,
                    parentId = input.parentId,
                    statusId = statusId,
                    workflowId = workflowId,
                    priorityId = priorityId,
                    assigneeProfileId = input.assigneeProfileId,
                    taskId = task.id,
                    sortOrder = input.sortOrder,
                    createdByPrincipalId = actingPrincipalId,
                    modifiedByPrincipalId = actingPrincipalId,
                )
            )
            writeHistory(
                requirementId = saved.id,
                actingPrincipalId = actingPrincipalId,
                actingProfileId = actingProfileId,
                changes = listOf(
                    FieldChange(fieldKey = "created", toValue = JsonPrimitive(saved.key)),
                    FieldChange(fieldKey = "task_id", toValue = JsonPrimitive(task.id.toString())),
                ),
            )
            saved
        }
    }

    override suspend fun update(
        id: UUID,
        input: UpdateRequirementInput,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ): Requirement = transaction {
        val existing = requirementRepository.getActiveById(id)
            ?: throw WorkOpsNotFoundException("Requirement", id.toString())

        val newPriorityId = input.priorityId ?: existing.priorityId
        val newAssigneeProfileId = when {
            input.clearAssignee -> null
            input.assigneeProfileId != null -> input.assigneeProfileId
            else -> existing.assigneeProfileId
        }
        val newSortOrder = input.sortOrder ?: existing.sortOrder
        val newExternalReferences = when {
            input.clearExternalReferences -> null
            input.externalReferences != null -> input.externalReferences
            else -> existing.externalReferences
        }

        val updated = requirementRepository.updateCore(
            id = id,
            priorityId = newPriorityId,
            assigneeProfileId = newAssigneeProfileId,
            sortOrder = newSortOrder,
            externalReferences = newExternalReferences,
            modifiedByPrincipalId = actingPrincipalId,
            expectedVersion = input.expectedVersion,
        ) ?: throw OptimisticLockFailedException("Requirement", id)

        val changes = buildList {
            addIfChangedUuid("priority_id", existing.priorityId, updated.priorityId)
            addIfChangedUuid("assignee_profile_id", existing.assigneeProfileId, updated.assigneeProfileId)
            addIfChangedInt("sort_order", existing.sortOrder, updated.sortOrder)
        }
        if (changes.isNotEmpty()) {
            writeHistory(
                requirementId = updated.id,
                actingPrincipalId = actingPrincipalId,
                actingProfileId = actingProfileId,
                changes = changes,
            )
        }
        updated
    }

    override suspend fun softDelete(
        id: UUID,
        expectedVersion: Long,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ): Requirement = transaction {
        val updated = requirementRepository.softDelete(id, actingPrincipalId, expectedVersion)
            ?: run {
                val current = requirementRepository.getById(id)
                    ?: throw WorkOpsNotFoundException("Requirement", id.toString())
                if (current.deletedAt != null) {
                    return@run current
                }
                throw OptimisticLockFailedException("Requirement", id)
            }
        writeHistory(
            requirementId = updated.id,
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
        updated
    }

    override suspend fun restore(
        id: UUID,
        expectedVersion: Long,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ): Requirement = transaction {
        val updated = requirementRepository.restore(id, actingPrincipalId, expectedVersion)
            ?: run {
                val current = requirementRepository.getById(id)
                    ?: throw WorkOpsNotFoundException("Requirement", id.toString())
                if (current.deletedAt == null) {
                    return@run current
                }
                throw OptimisticLockFailedException("Requirement", id)
            }
        writeHistory(
            requirementId = updated.id,
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

    override suspend fun syncStatusFromTask(
        taskId: UUID,
        statusId: UUID,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ) = transaction {
        val linkedReq = requirementRepository.syncStatusByTaskId(taskId, statusId, actingPrincipalId)
        if (linkedReq != null) {
            writeHistory(linkedReq.id, actingPrincipalId, actingProfileId,
                listOf(FieldChange(fieldKey = "status_id", toValue = JsonPrimitive(statusId.toString()))))
        }

        val childReqs = requirementRepository.listByParent(RequirementParent.TASK, taskId, 0, MAX_PAGE)
        if (childReqs.isNotEmpty()) {
            requirementRepository.syncStatusByParentTask(taskId, statusId, actingPrincipalId)
            for (req in childReqs) {
                writeHistory(req.id, actingPrincipalId, actingProfileId,
                    listOf(FieldChange(fieldKey = "status_id", toValue = JsonPrimitive(statusId.toString()))))
            }
        }
    }

    override suspend fun listHistory(requirementId: UUID, offset: Long, limit: Int): List<RequirementHistoryEntry> =
        requirementHistoryRepository.listByRequirement(requirementId, offset.coerceAtLeast(0), limit.coerceIn(1, MAX_PAGE))

    override suspend fun moveToParent(
        id: UUID,
        newParentType: RequirementParent,
        newParentId: UUID,
        expectedVersion: Long,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ): Requirement = transaction {
        val existing = requirementRepository.getActiveById(id)
            ?: throw WorkOpsNotFoundException("Requirement", id.toString())
        validateParentExists(newParentType, newParentId)

        val updated = requirementRepository.moveToParent(
            id = id,
            parentType = newParentType,
            parentId = newParentId,
            modifiedByPrincipalId = actingPrincipalId,
            expectedVersion = expectedVersion,
        ) ?: throw OptimisticLockFailedException("Requirement", id)

        val changes = buildList {
            if (existing.parentType != updated.parentType) {
                add(FieldChange("parent_type", JsonPrimitive(existing.parentType.name), JsonPrimitive(updated.parentType.name)))
            }
            addIfChangedUuid("parent_id", existing.parentId, updated.parentId)
        }
        if (changes.isNotEmpty()) {
            writeHistory(id, actingPrincipalId, actingProfileId, changes)
        }
        updated
    }

    private suspend fun createMetadata(name: String): bosca.content.metadata.model.Metadata {
        return metadataService.add(
            parent = null,
            collectionItemAttributes = null,
            input = MetadataInput(
                name = name,
                contentType = "bosca/v-document",
                languageTag = "en",
                document = DocumentInput(
                    title = name,
                    content = Content(),
                    templateMetadataId = REQUIREMENT_TEMPLATE_ID,
                    templateMetadataVersion = REQUIREMENT_TEMPLATE_VERSION,
                ),
            ),
        )
    }

    private suspend fun createLinkedTask(
        metadataId: UUID,
        projectId: UUID,
        requirementKey: String,
        priorityId: UUID,
        assigneeProfileId: UUID?,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
        reporterProfileId: UUID,
    ): bosca.workops.model.task.Task {
        val doc = try {
            documentService.getDocument(metadataId, 1)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val summary = doc?.title?.takeIf { it.isNotBlank() } ?: "REQ $requirementKey"
        val description = doc?.content?.let { MarkdownConverter.toMarkdown(it) }
        return taskService.create(
            input = CreateTaskInput(
                projectId = projectId,
                summary = summary,
                descriptionMarkdown = description,
                priorityId = priorityId,
                assigneeProfileId = assigneeProfileId,
            ),
            actingPrincipalId = actingPrincipalId,
            actingProfileId = actingProfileId,
            reporterProfileId = reporterProfileId,
        )
    }

    private suspend fun resolveReporterProfileId(parentType: RequirementParent, parentId: UUID): UUID {
        return when (parentType) {
            RequirementParent.SPEC -> {
                val spec = specRepository.getActiveById(parentId)
                    ?: throw WorkOpsNotFoundException("Spec", parentId.toString())
                spec.ownerProfileId
            }
            RequirementParent.TASK -> {
                val task = taskRepository.getActiveById(parentId)
                    ?: throw WorkOpsNotFoundException("Task", parentId.toString())
                task.reporterProfileId
            }
        }
    }

    private suspend fun validateParentExists(parentType: RequirementParent, parentId: UUID) {
        when (parentType) {
            RequirementParent.SPEC -> {
                specRepository.getActiveById(parentId)
                    ?: throw WorkOpsNotFoundException("Spec", parentId.toString())
            }
            RequirementParent.TASK -> {
                taskRepository.getActiveById(parentId)
                    ?: throw WorkOpsNotFoundException("Task", parentId.toString())
            }
        }
    }

    private suspend fun resolveProjectId(parentType: RequirementParent, parentId: UUID): UUID {
        return when (parentType) {
            RequirementParent.SPEC -> {
                val spec = specRepository.getActiveById(parentId)
                    ?: throw WorkOpsNotFoundException("Spec", parentId.toString())
                spec.projectId ?: error("spec $parentId has no project — cannot mint requirement key")
            }
            RequirementParent.TASK -> {
                val task = taskRepository.getActiveById(parentId)
                    ?: throw WorkOpsNotFoundException("Task", parentId.toString())
                task.projectId
            }
        }
    }

    private suspend fun resolveDefaultWorkflowId(): UUID {
        val workflows = workflowRepository.listWorkflows()
        val workflow = workflows.firstOrNull()
            ?: error("no workflows defined — seed data missing")
        return workflow.id
    }

    private suspend fun defaultStatusId(): UUID {
        val status = statusRepository.getAll().firstOrNull { it.name == "To Do" }
            ?: error("seed status 'To Do' missing — V2 seeds drifted")
        return status.id
    }

    private suspend fun defaultPriorityId(): UUID {
        val priority = priorityRepository.getAll().firstOrNull { it.name == "Medium" }
            ?: error("seed priority 'Medium' missing — V2 seeds drifted")
        return priority.id
    }

    private suspend fun writeHistory(
        requirementId: UUID,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
        changes: List<FieldChange>,
    ) {
        requirementHistoryRepository.add(
            requirementId = requirementId,
            changedAt = OffsetDateTime.now(),
            changedByPrincipalId = actingPrincipalId,
            changedByProfileId = actingProfileId,
            changes = json.encodeToJsonElement(ListSerializer(FieldChange.serializer()), changes),
        )
    }

    private fun MutableList<FieldChange>.addIfChangedUuid(key: String, before: UUID?, after: UUID?) {
        if (before != after) {
            add(FieldChange(key, historyText(before), historyText(after)))
        }
    }

    private fun historyText(value: Any?): kotlinx.serialization.json.JsonElement =
        if (value == null) JsonNull else JsonPrimitive(value.toString())

    private fun MutableList<FieldChange>.addIfChangedInt(key: String, before: Int, after: Int) {
        if (before != after) {
            add(FieldChange(key, JsonPrimitive(before), JsonPrimitive(after)))
        }
    }

    companion object {
        private const val MAX_PAGE = 200
        // Well-known seeded Requirement document template — see V26__spec_document_templates.sql.
        val REQUIREMENT_TEMPLATE_ID: UUID = UUID.parse("a0000000-0000-0000-0000-000000000002")
        const val REQUIREMENT_TEMPLATE_VERSION: Int = 1
    }
}
