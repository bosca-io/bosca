package bosca.workops.service

import bosca.db.transaction
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.repository.CrossProjectTaskRepository
import bosca.workops.repository.MoveTaskParams
import bosca.workops.repository.ProjectKeyCounterRepository
import bosca.workops.repository.ProjectRepository
import bosca.workops.repository.StatusRepository
import bosca.workops.repository.TaskKeyAliasRepository
import bosca.workops.repository.TaskMoveAuditParams
import bosca.workops.repository.TaskMoveAuditRepository
import bosca.workops.repository.TaskRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@ServiceImplementation
class CrossProjectMoveServiceImpl(
    private val taskRepository: TaskRepository,
    private val crossProjectRepo: CrossProjectTaskRepository,
    private val keyCounterRepository: ProjectKeyCounterRepository,
    private val taskKeyAliasRepository: TaskKeyAliasRepository,
    private val auditRepository: TaskMoveAuditRepository,
    private val projectRepository: ProjectRepository,
    private val statusRepository: StatusRepository,
    private val json: Json,
) : CrossProjectMoveService {

    override suspend fun moveTaskToProject(
        taskId: UUID,
        input: MoveWorkOpsTaskInput,
        actingPrincipalId: UUID,
    ): bosca.workops.model.task.Task = transaction {
        val task = taskRepository.getActiveById(taskId)
            ?: throw WorkOpsNotFoundException("Task", taskId.toString())
        if (task.projectId == input.targetProjectId) {
            throw WorkOpsValidationException("targetProjectId", "task is already in the target project")
        }
        val targetProject = projectRepository.getById(input.targetProjectId)
            ?: throw WorkOpsNotFoundException("Project", input.targetProjectId.toString())
        val targetStatusId = input.statusMapping[task.statusId]
            ?: throw WorkOpsValidationException(
                "statusMapping",
                "no mapping provided for source status ${task.statusId}",
            )
        // Verify the target status exists.
        statusRepository.getById(targetStatusId)
            ?: throw WorkOpsNotFoundException("Status", targetStatusId.toString())
        // TODO: implement custom-field remapping (R26). Until then, reject
        //  non-empty fieldMapping so callers don't silently lose field data.
        if (input.fieldMapping.isNotEmpty()) {
            throw UnsupportedOperationException(
                "fieldMapping is accepted for forward-compatibility but is not yet implemented; " +
                    "pass an empty map or omit the parameter"
            )
        }
        // Atomically bump the target project's key counter. If
        // the project hasn't minted a key yet (newly seeded), the
        // counter row may not exist — initialize on miss.
        val nextSeq = keyCounterRepository.reserveNext(input.targetProjectId)
            ?: run {
                keyCounterRepository.initialize(input.targetProjectId)
                keyCounterRepository.reserveNext(input.targetProjectId)
                    ?: throw IllegalStateException("failed to reserve key for project ${input.targetProjectId}")
            }
        val newKey = "${targetProject.key}-$nextSeq"
        val legacyKey = task.key

        crossProjectRepo.moveTask(
            MoveTaskParams(
                taskId = task.id,
                targetProjectId = input.targetProjectId,
                newKey = newKey,
                targetStatusId = targetStatusId,
                movedByPrincipalId = actingPrincipalId,
                expectedVersion = task.version,
            )
        )
        taskKeyAliasRepository.add(legacyKey, task.id)
        val statusMappingJson = buildJsonObject {
            for ((source, target) in input.statusMapping) {
                put(source.toString(), JsonPrimitive(target.toString()))
            }
        }
        val fieldMappingJson = buildJsonObject {
            for ((source, target) in input.fieldMapping) put(source, JsonPrimitive(target))
        }
        auditRepository.audit(
            TaskMoveAuditParams(
                taskId = task.id,
                fromProjectId = task.projectId,
                toProjectId = input.targetProjectId,
                legacyKey = legacyKey,
                newKey = newKey,
                movedByPrincipalId = actingPrincipalId,
                statusMapping = json.encodeToString(JsonObject.serializer(), statusMappingJson),
                fieldMapping = json.encodeToString(JsonObject.serializer(), fieldMappingJson),
            )
        )
        // Reload to return the new shape.
        taskRepository.getById(task.id)
            ?: throw WorkOpsNotFoundException("Task", task.id.toString())
    }
}
