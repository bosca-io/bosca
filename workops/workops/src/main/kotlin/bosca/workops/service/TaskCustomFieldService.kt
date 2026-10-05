package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.fields.TaskFieldConfiguration
import bosca.workops.model.project.Project
import bosca.workops.model.task.Task
import bosca.workops.repository.TaskFieldConfigurationRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@ServiceImplementation
class TaskCustomFieldServiceImpl(
    private val configurationRepository: TaskFieldConfigurationRepository,
) : TaskCustomFieldService {

    override suspend fun configFor(project: Project, taskTypeId: UUID): Map<String, TaskFieldConfiguration> {
        val schemeId = project.defaultFieldConfigurationSchemeId
            ?: return emptyMap()
        val rows = configurationRepository.listConfigurationsForTaskType(schemeId, taskTypeId)
        // Task-type-specific row wins over the scheme-wide default
        // for the same fieldKey. Iterate in two passes so the
        // override is deterministic regardless of the SQL order.
        val defaults = rows.filter { it.taskTypeId == null }.associateBy { it.fieldKey }
        val overrides = rows.filter { it.taskTypeId == taskTypeId }.associateBy { it.fieldKey }
        return defaults + overrides
    }

    override suspend fun composeForCreate(
        project: Project,
        taskTypeId: UUID,
        provided: Map<String, JsonElement>,
    ): JsonObject {
        val config = configFor(project, taskTypeId)
        val merged = mutableMapOf<String, JsonElement>()
        // Walk the configured keys first so defaults / hidden rules
        // apply uniformly. Values the caller supplied take precedence;
        // missing required fields raise.
        for ((key, cfg) in config) {
            val value = provided[key] ?: cfg.defaultValueExpression
            if (value != null) {
                merged[key] = value
            } else if (cfg.required) {
                throw WorkOpsValidationException(
                    "customFields[$key]",
                    "field '$key' is required by the project's field-configuration scheme",
                )
            }
        }
        // Caller-supplied keys not in the scheme pass through —
        // Phase 4 doesn't reject "unknown" keys because the
        // core-forms field catalog isn't wired yet. Phase 7's
        // permission layer can tighten this when admins want
        // strict-mode schemas.
        for ((key, value) in provided) {
            if (key !in merged) merged[key] = value
        }
        return JsonObject(merged)
    }

    override suspend fun filterForRead(
        project: Project,
        task: Task,
        manager: Boolean,
    ): JsonObject {
        if (manager) return task.customFieldValues
        val config = configFor(project, task.taskTypeId)
        val visible = task.customFieldValues.filterKeys { key ->
            config[key]?.hidden != true
        }
        return JsonObject(visible)
    }
}
