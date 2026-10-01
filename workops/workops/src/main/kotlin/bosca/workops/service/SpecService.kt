package bosca.workops.service

import bosca.db.transaction

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.SpecCycleException
import bosca.workops.model.WorkflowTransitionNotAvailableException
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.graphql.Batch
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.workops.model.audit.FieldChange
import bosca.workops.model.audit.SpecHistoryEntry
import bosca.workops.model.spec.CreateSpecInput
import bosca.workops.model.spec.SpecCreated
import bosca.workops.model.spec.SpecDeleted
import bosca.workops.model.spec.SpecTasksGenerated
import bosca.workops.model.spec.SpecTransitioned
import bosca.workops.model.spec.SpecUpdated
import bosca.workops.model.spec.dispatch
import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.documents.Content
import bosca.documents.MarkdownConverter
import bosca.workops.model.spec.GenerationSource
import bosca.workops.model.spec.Spec
import bosca.workops.model.spec.SpecTaskGeneration
import bosca.workops.model.spec.UpdateSpecInput
import bosca.workops.model.task.CreateTaskInput
import bosca.workops.repository.ProgramRepository
import bosca.workops.repository.ProjectRepository
import bosca.workops.repository.SpecPermissionRepository
import bosca.workops.model.requirement.RequirementParent
import bosca.workops.repository.RequirementRepository
import bosca.workops.repository.SpecHistoryRepository
import bosca.workops.repository.SpecKeyCounterRepository
import bosca.workops.repository.SpecRepository
import bosca.workops.repository.SpecTaskGenerationRepository
import bosca.workops.repository.StatusRepository
import bosca.workops.repository.WorkflowRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

@ServiceImplementation
class SpecServiceImpl(
    private val specRepository: SpecRepository,
    private val specHistoryRepository: SpecHistoryRepository,
    private val keyCounterRepository: SpecKeyCounterRepository,
    private val projectRepository: ProjectRepository,
    private val programRepository: ProgramRepository,
    private val statusRepository: StatusRepository,
    private val workflowRepository: WorkflowRepository,
    private val specPermissionRepository: SpecPermissionRepository,
    private val requirementRepository: RequirementRepository,
    private val taskService: TaskService,
    private val metadataService: MetadataService,
    private val documentService: DocumentService,
    private val specTaskGenerationRepository: SpecTaskGenerationRepository,
    private val projectPermissionEvaluator: ProjectPermissionEvaluator,
    private val programPermissionEvaluator: ProgramPermissionEvaluator,
    private val json: Json,
) : SpecService {

    override suspend fun getPermissions(entity: Spec): List<EntityPermission> =
        specPermissionRepository.getBySpecId(entity.id)

    override suspend fun addPermissionsToBatch(batch: Batch<UUID, List<EntityPermission>>) {
        val permissions = specPermissionRepository.getBySpecIds(batch.keys)
        val grouped = permissions.groupBy { it.specId }
        for (key in batch.keys) {
            batch.setData(key, grouped[key]?.let { ArrayList(it) } ?: emptyList())
        }
    }

    override suspend fun isParentAllowed(
        authentication: AuthenticationContext?,
        entity: Spec,
        action: PermissionAction,
    ): Boolean {
        entity.projectId?.let {
            val project = projectRepository.getById(it) ?: return false
            return projectPermissionEvaluator.isAllowed(authentication, project, action)
        }
        entity.programId?.let {
            val program = programRepository.getById(it) ?: return false
            return programPermissionEvaluator.isAllowed(authentication, program, action)
        }
        return false
    }

    override suspend fun getById(id: UUID): Spec? = specRepository.getActiveById(id)

    override suspend fun getByIdIncludingDeleted(id: UUID): Spec? = specRepository.getById(id)

    override suspend fun getByKey(key: String): Spec? = specRepository.getActiveByKey(key)

    override suspend fun getByIds(ids: List<UUID>): List<Spec> =
        if (ids.isEmpty()) emptyList() else specRepository.getByIds(ids)

    override suspend fun listByProject(projectId: UUID, offset: Long, limit: Int): List<Spec> =
        specRepository.listByProject(projectId, offset.coerceAtLeast(0), limit.coerceIn(1, MAX_PAGE))

    override suspend fun listByProgram(programId: UUID, offset: Long, limit: Int): List<Spec> =
        specRepository.listByProgram(programId, offset.coerceAtLeast(0), limit.coerceIn(1, MAX_PAGE))

    override suspend fun listByOwner(ownerProfileId: UUID, offset: Long, limit: Int): List<Spec> =
        specRepository.listByOwner(ownerProfileId, offset.coerceAtLeast(0), limit.coerceIn(1, MAX_PAGE))

    override suspend fun create(
        input: CreateSpecInput,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
        ownerProfileId: UUID,
    ): Spec {
        val projectId = input.projectId
            ?: throw WorkOpsValidationException("projectId", "required for key minting")
        val project = projectRepository.getById(projectId)
            ?: throw WorkOpsNotFoundException("Project", projectId.toString())

        // Reserve the key sequence outside the transaction so the counter
        // increment auto-commits and cannot be rolled back by a later failure
        // in the spec insert / history write.
        val seq = keyCounterRepository.reserveNext(projectId)
            ?: run {
                keyCounterRepository.initialize(projectId)
                keyCounterRepository.reserveNext(projectId)
                    ?: error("spec_key_counter initialization failed for $projectId")
            }
        val key = "${project.key}-SPEC-$seq"

        return transaction {
            val workflowId = input.workflowId ?: resolveDefaultWorkflowId(project.id)
            val statusId = defaultStatusId()

            input.parentSpecId?.let { checkNoCycle(UUID.NIL, it) }

            val metadata = input.metadataId?.let {
                metadataService.getById(it) ?: throw WorkOpsNotFoundException("Metadata", it.toString())
            } ?: createMetadata(input.name ?: key)
            // Bind the spec document to the Spec template: sets the template link and
            // merges the template's default attributes (notably `type`) onto the
            // metadata, for both freshly-created and caller-supplied metadata.
            metadataService.setDocumentTemplate(metadata, SPEC_TEMPLATE_ID, SPEC_TEMPLATE_VERSION)
            val metadataId = metadata.id

            val saved = specRepository.add(
                Spec(
                    key = key,
                    metadataId = metadataId,
                    programId = input.programId,
                    projectId = input.projectId,
                    parentSpecId = input.parentSpecId,
                    sortOrder = input.sortOrder,
                    statusId = statusId,
                    workflowId = workflowId,
                    ownerProfileId = ownerProfileId,
                    gitRepositoryId = input.gitRepositoryId,
                    gitPath = input.gitPath,
                    createdByPrincipalId = actingPrincipalId,
                    modifiedByPrincipalId = actingPrincipalId,
                )
            )
            writeHistory(
                specId = saved.id,
                actingPrincipalId = actingPrincipalId,
                actingProfileId = actingProfileId,
                changes = listOf(
                    FieldChange(fieldKey = "created", toValue = JsonPrimitive(saved.key)),
                ),
            )
            saved.parentSpecId?.let { specRepository.incrementChildCount(it) }
            SpecCreated(specId = saved.id, projectId = saved.projectId, ownerProfileId = saved.ownerProfileId).dispatch()
            saved
        }
    }

    override suspend fun update(
        id: UUID,
        input: UpdateSpecInput,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ): Spec = transaction {
        val existing = specRepository.getActiveById(id)
            ?: throw WorkOpsNotFoundException("Spec", id.toString())

        val newProgramId = when {
            input.clearProgramId -> null
            input.programId != null -> input.programId
            else -> existing.programId
        }
        val newProjectId = when {
            input.clearProjectId -> null
            input.projectId != null -> input.projectId
            else -> existing.projectId
        }
        val newOwnerProfileId = input.ownerProfileId ?: existing.ownerProfileId
        val newParentSpecId = when {
            input.clearParentSpecId -> null
            input.parentSpecId != null -> input.parentSpecId
            else -> existing.parentSpecId
        }
        if (newParentSpecId != null && newParentSpecId != existing.parentSpecId) {
            checkNoCycle(id, newParentSpecId)
        }
        val newSortOrder = input.sortOrder ?: existing.sortOrder
        val newGitRepositoryId = when {
            input.clearGitRepositoryId -> null
            input.gitRepositoryId != null -> input.gitRepositoryId
            else -> existing.gitRepositoryId
        }
        val newGitPath = when {
            input.clearGitPath -> null
            input.gitPath != null -> input.gitPath
            else -> existing.gitPath
        }
        val newExternalReferences = when {
            input.clearExternalReferences -> null
            input.externalReferences != null -> input.externalReferences
            else -> existing.externalReferences
        }

        val updated = specRepository.updateCore(
            id = id,
            programId = newProgramId,
            projectId = newProjectId,
            parentSpecId = newParentSpecId,
            sortOrder = newSortOrder,
            ownerProfileId = newOwnerProfileId,
            gitRepositoryId = newGitRepositoryId,
            gitPath = newGitPath,
            externalReferences = newExternalReferences,
            modifiedByPrincipalId = actingPrincipalId,
            expectedVersion = input.expectedVersion,
        ) ?: throw OptimisticLockFailedException("Spec", id)

        val changes = buildList {
            addIfChangedUuid("program_id", existing.programId, updated.programId)
            addIfChangedUuid("project_id", existing.projectId, updated.projectId)
            addIfChangedUuid("parent_spec_id", existing.parentSpecId, updated.parentSpecId)
            addIfChangedInt("sort_order", existing.sortOrder, updated.sortOrder)
            addIfChangedUuid("owner_profile_id", existing.ownerProfileId, updated.ownerProfileId)
            addIfChangedUuid("git_repository_id", existing.gitRepositoryId, updated.gitRepositoryId)
            addIfChanged("git_path", existing.gitPath, updated.gitPath)
        }
        if (changes.isNotEmpty()) {
            writeHistory(
                specId = updated.id,
                actingPrincipalId = actingPrincipalId,
                actingProfileId = actingProfileId,
                changes = changes,
            )
        }
        if (existing.parentSpecId != updated.parentSpecId) {
            existing.parentSpecId?.let { specRepository.decrementChildCount(it) }
            updated.parentSpecId?.let { specRepository.incrementChildCount(it) }
        }
        SpecUpdated(specId = updated.id).dispatch()
        updated
    }

    override suspend fun softDelete(
        id: UUID,
        expectedVersion: Long,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ): Spec = transaction {
        val updated = specRepository.softDelete(id, actingPrincipalId, expectedVersion)
            ?: run {
                val current = specRepository.getById(id)
                    ?: throw WorkOpsNotFoundException("Spec", id.toString())
                if (current.deletedAt != null) {
                    return@run current
                }
                throw OptimisticLockFailedException("Spec", id)
            }
        writeHistory(
            specId = updated.id,
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
        updated.parentSpecId?.let { specRepository.decrementChildCount(it) }
        SpecDeleted(specId = updated.id).dispatch()
        updated
    }

    override suspend fun restore(
        id: UUID,
        expectedVersion: Long,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ): Spec = transaction {
        val updated = specRepository.restore(id, actingPrincipalId, expectedVersion)
            ?: run {
                val current = specRepository.getById(id)
                    ?: throw WorkOpsNotFoundException("Spec", id.toString())
                if (current.deletedAt == null) {
                    return@run current
                }
                throw OptimisticLockFailedException("Spec", id)
            }
        writeHistory(
            specId = updated.id,
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
        updated.parentSpecId?.let { specRepository.incrementChildCount(it) }
        updated
    }

    override suspend fun transition(
        id: UUID,
        transitionId: UUID,
        expectedVersion: Long,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
        resolutionId: UUID?,
    ): Spec = transaction {
        val spec = specRepository.getActiveById(id)
            ?: throw WorkOpsNotFoundException("Spec", id.toString())

        val workflow = workflowRepository.getWorkflowById(spec.workflowId)
            ?: error("Workflow ${spec.workflowId} missing for spec ${spec.key}")
        val states = workflowRepository.listStates(workflow.id)
        val currentState = states.firstOrNull { it.statusId == spec.statusId }
            ?: error("No workflow state for status ${spec.statusId} in workflow ${workflow.id}")

        val transitions = workflowRepository.listTransitions(workflow.id)
        val transition = transitions.firstOrNull { it.id == transitionId }
            ?: throw WorkflowTransitionNotAvailableException(id, transitionId)
        val reachable = transition.fromStateIds.contains("*") ||
            transition.fromStateIds.contains(currentState.id.toString())
        if (!reachable) {
            throw WorkflowTransitionNotAvailableException(id, transitionId)
        }

        val targetState = states.firstOrNull { it.id == transition.toStateId }
            ?: error("Target state ${transition.toStateId} missing in workflow ${workflow.id}")
        val newStatusId = targetState.statusId

        val updated = specRepository.applyTransition(
            id = id,
            statusId = newStatusId,
            modifiedByPrincipalId = actingPrincipalId,
            expectedVersion = expectedVersion,
        ) ?: throw OptimisticLockFailedException("Spec", id)

        val changes = buildList {
            addIfChangedUuid("status_id", spec.statusId, updated.statusId)
            add(FieldChange(fieldKey = "transition_id", toValue = JsonPrimitive(transition.id.toString())))
        }
        writeHistory(
            specId = updated.id,
            actingPrincipalId = actingPrincipalId,
            actingProfileId = actingProfileId,
            changes = changes,
        )

        val parentId = spec.parentSpecId
        if (parentId != null) {
            val allStatuses = statusRepository.getAll()
            val oldStatus = allStatuses.firstOrNull { it.id == spec.statusId }
            val newStatus = allStatuses.firstOrNull { it.id == updated.statusId }
            if (oldStatus != null && newStatus != null) {
                val wasDone = oldStatus.category == bosca.workops.model.workflow.StatusCategory.DONE
                val isDone = newStatus.category == bosca.workops.model.workflow.StatusCategory.DONE
                if (!wasDone && isDone) {
                    specRepository.incrementChildDoneCount(parentId)
                } else if (wasDone && !isDone) {
                    specRepository.decrementChildDoneCount(parentId)
                }
            }
        }

        SpecTransitioned(specId = updated.id, fromStatusId = spec.statusId, toStatusId = updated.statusId, transitionId = transitionId).dispatch()
        updated
    }

    override suspend fun listHistory(specId: UUID, offset: Long, limit: Int): List<SpecHistoryEntry> =
        specHistoryRepository.listBySpec(specId, offset.coerceAtLeast(0), limit.coerceIn(1, MAX_PAGE))

    override suspend fun listChildren(parentSpecId: UUID, offset: Long, limit: Int): List<Spec> =
        specRepository.listByParentSpec(parentSpecId, offset.coerceAtLeast(0), limit.coerceIn(1, MAX_PAGE))

    override suspend fun countChildren(parentSpecId: UUID): Long =
        specRepository.countByParentSpec(parentSpecId)

    override suspend fun generateTasks(
        specId: UUID,
        metadataVersion: Int,
        source: GenerationSource,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
        agentSessionId: UUID?,
    ): SpecTaskGeneration = transaction {
        val spec = specRepository.getActiveById(specId)
            ?: throw WorkOpsNotFoundException("Spec", specId.toString())
        val projectId = spec.projectId
            ?: throw WorkOpsValidationException("projectId", "spec must have a project to generate tasks")

        val requirements = requirementRepository.listByParent(
            RequirementParent.SPEC, specId, 0, MAX_PAGE,
        )

        val reporterProfileId = actingProfileId ?: spec.ownerProfileId
        val generatedTaskIds = mutableListOf<UUID>()
        for (req in requirements) {
            val doc = documentService.getDocument(req.metadataId, 1)
            val summary = doc?.title?.takeIf { it.isNotBlank() } ?: "REQ ${req.key}"
            val description = doc?.content?.let { MarkdownConverter.toMarkdown(it) }
            val task = taskService.create(
                input = CreateTaskInput(
                    projectId = projectId,
                    summary = summary,
                    descriptionMarkdown = description,
                    priorityId = req.priorityId,
                    assigneeProfileId = req.assigneeProfileId,
                ),
                actingPrincipalId = actingPrincipalId,
                actingProfileId = actingProfileId,
                reporterProfileId = reporterProfileId,
            )
            generatedTaskIds.add(task.id)
        }

        val generation = specTaskGenerationRepository.add(
            SpecTaskGeneration(
                specId = specId,
                metadataVersion = metadataVersion,
                source = source,
                agentSessionId = agentSessionId,
                generatedTaskIds = generatedTaskIds,
                createdByPrincipalId = actingPrincipalId,
            )
        )

        writeHistory(
            specId = specId,
            actingPrincipalId = actingPrincipalId,
            actingProfileId = actingProfileId,
            changes = listOf(
                FieldChange(
                    fieldKey = "task_generation",
                    toValue = JsonPrimitive("${generation.id}:${generatedTaskIds.size} tasks"),
                ),
            ),
        )

        SpecTasksGenerated(specId = specId, source = source, taskCount = generatedTaskIds.size).dispatch()
        generation
    }

    private suspend fun checkNoCycle(specId: UUID, proposedParentId: UUID) {
        var currentId: UUID? = proposedParentId
        var depth = 0
        while (currentId != null && depth < MAX_HIERARCHY_DEPTH) {
            if (currentId == specId) {
                throw SpecCycleException(specId, proposedParentId)
            }
            val parent = specRepository.getActiveById(currentId) ?: break
            currentId = parent.parentSpecId
            depth++
        }
        if (depth >= MAX_HIERARCHY_DEPTH) {
            throw SpecCycleException(specId, proposedParentId)
        }
    }

    private suspend fun resolveDefaultWorkflowId(projectId: UUID): UUID {
        val workflows = workflowRepository.listWorkflows()
        val workflow = workflows.firstOrNull()
            ?: error("no workflows defined — seed data missing")
        return workflow.id
    }

    private suspend fun createMetadata(name: String): Metadata {
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
                    templateMetadataId = SPEC_TEMPLATE_ID,
                    templateMetadataVersion = SPEC_TEMPLATE_VERSION,
                ),
            ),
        )
    }


    private suspend fun defaultStatusId(): UUID {
        val status = statusRepository.getAll().firstOrNull { it.name == "To Do" }
            ?: error("seed status 'To Do' missing — V2 seeds drifted")
        return status.id
    }

    private suspend fun writeHistory(
        specId: UUID,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
        changes: List<FieldChange>,
    ) {
        specHistoryRepository.add(
            specId = specId,
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

    private fun historyText(value: Any?): JsonElement =
        if (value == null) JsonNull else JsonPrimitive(value.toString())

    private fun MutableList<FieldChange>.addIfChangedInt(key: String, before: Int, after: Int) {
        if (before != after) {
            add(FieldChange(key, JsonPrimitive(before), JsonPrimitive(after)))
        }
    }

    companion object {
        private const val MAX_PAGE = 200
        private const val MAX_HIERARCHY_DEPTH = 256
        // Well-known seeded Spec document template — see V26__spec_document_templates.sql.
        val SPEC_TEMPLATE_ID: UUID = UUID.parse("a0000000-0000-0000-0000-000000000001")
        const val SPEC_TEMPLATE_VERSION: Int = 1
        val DEFAULT_TASK_TYPE_ID: UUID = UUID.parse("00000000-0000-0000-0000-000000000003")
    }
}
