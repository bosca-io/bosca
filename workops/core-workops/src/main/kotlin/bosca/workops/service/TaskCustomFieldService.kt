package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.fields.TaskFieldConfiguration
import bosca.workops.model.project.Project
import bosca.workops.model.task.Task
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Custom-field configuration enforcement (R5). Phase 4 ships:
 *
 *  - The merged-effective-config resolver: collapses scheme-wide
 *    defaults (`task_type_id is null`) with task-type-specific
 *    rows, so a (project, taskType) pair has one row per fieldKey.
 *  - Required-field enforcement on createTask / updateTask.
 *  - Hidden-field filtering on read paths.
 *  - Default-value application from the configuration's literal
 *    JSON expression when the caller omits the key.
 *
 * Phase 4 deliberately scopes JSON Schema-driven type validation
 * out of the service: core-forms does not yet expose a per-field
 * validator API. The hook is reserved for the deeper core-forms
 * port (the workops side rejects required-but-missing today; the
 * type check lands when core-forms gains the field-binding model
 * R5 calls for).
 */
interface TaskCustomFieldService : Service {

    /**
     * Resolved configurations for a task: keyed by `fieldKey`, with
     * task-type-specific rows overriding scheme-wide defaults.
     */
    suspend fun configFor(project: Project, taskTypeId: UUID): Map<String, TaskFieldConfiguration>

    /**
     * Compose the customFieldValues map that should land on the
     * task row at create / update time. Throws
     * [WorkOpsValidationException] when a required field is missing
     * after defaults are applied.
     */
    suspend fun composeForCreate(
        project: Project,
        taskTypeId: UUID,
        provided: Map<String, JsonElement>,
    ): JsonObject

    /**
     * Filter the persisted customFieldValues map for read paths,
     * removing any fields the configuration marks `hidden = true`.
     * Manager-level reads bypass the filter.
     */
    suspend fun filterForRead(
        project: Project,
        task: Task,
        manager: Boolean,
    ): JsonObject
}
