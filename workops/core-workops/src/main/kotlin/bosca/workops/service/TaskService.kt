package bosca.workops.service

import bosca.security.model.PermissionAction
import bosca.security.model.PermissionService
import bosca.serialization.UUID
import bosca.workops.model.audit.FieldChange
import bosca.workops.model.audit.TaskHistoryEntry
import bosca.workops.model.task.CreateTaskInput
import bosca.workops.model.task.Task
import bosca.workops.model.task.UpdateTaskInput
import kotlinx.serialization.json.JsonObject

/**
 * Service surface for [Task] (R2). The Phase 2 contract:
 *
 * - [create] mints a `{PROJECT_KEY}-{N}` handle by atomically bumping
 *   `workops.project_key_counter`, writes the task row, and writes a
 *   matching `TaskHistoryEntry` — all inside one transaction.
 * - [update] applies a partial edit subject to the optimistic-lock
 *   `expectedVersion`; the diff between old and new is captured as a
 *   single history entry carrying one [FieldChange] per changed field
 *   (R17: multi-field updates produce one entry, not one per field).
 * - [softDelete] / [restore] flip the `deletedAt` tombstone; both
 *   are audited.
 * - Phase 3 introduces `transitionTask` as the only path to a status
 *   change. Until that lands, [update] accepts a status-id swap
 *   directly so a minimum-viable Phase 2 task tracker functions.
 *
 * The createdBy / modifiedBy principal id is required input — Phase 2
 * predates the wire-up of `core-security`'s `AuthenticationContext`
 * inside service calls, so the caller (the GraphQL controller) reads
 * the current principal and passes the value down. Phase 7 (R11)
 * tightens this into the standard auth-context pattern.
 */
interface TaskService : PermissionService<Task, UUID> {

    suspend fun listByProject(projectId: UUID, offset: Long, limit: Int): List<Task>

    suspend fun listByAffectedProject(projectId: UUID, offset: Long, limit: Int): List<Task>

    suspend fun getById(id: UUID): Task?

    /**
     * Returns a task regardless of its soft-deletion state. This is reserved for manager-style
     * workflows such as audit and post-deletion notification processing; ordinary reads use
     * [getById].
     */
    suspend fun getByIdIncludingDeleted(id: UUID): Task?

    suspend fun getByKey(key: String): Task?

    suspend fun getByIds(ids: List<UUID>): List<Task>

    /** Claims tasks whose due dates have arrived and emits one notification per configured due date. */
    suspend fun dispatchDueNotifications(limit: Int = 200): Int

    suspend fun create(
        input: CreateTaskInput,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
        reporterProfileId: UUID,
    ): Task

    suspend fun update(
        id: UUID,
        input: UpdateTaskInput,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ): Task

    /**
     * Replaces a task's custom-field values after applying its project configuration.
     * The mutation uses optimistic locking and records per-field history and update events;
     * replacing the values with an identical object is a no-op.
     */
    suspend fun setCustomFieldValues(
        id: UUID,
        customFieldValues: JsonObject,
        expectedVersion: Long,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ): Task

    suspend fun softDelete(id: UUID, expectedVersion: Long, actingPrincipalId: UUID, actingProfileId: UUID?): Task

    suspend fun restore(id: UUID, expectedVersion: Long, actingPrincipalId: UUID, actingProfileId: UUID?): Task

    suspend fun listHistory(taskId: UUID, offset: Long, limit: Int): List<TaskHistoryEntry>

    /**
     * The single permitted path for a status change (R4 Excellence-bar
     * non-negotiable: "Workflow is the only status path"). Resolves
     * the project's effective workflow, evaluates the transition's
     * conditions and validators, applies post-functions and the
     * status flip atomically, and writes one [TaskHistoryEntry]
     * covering the full bundle.
     *
     * @param transitionId the [bosca.workops.model.workflow.WorkflowTransition.id]
     *                     to apply.
     * @param expectedVersion optimistic-lock token observed by the caller.
     * @param resolutionId optional resolution captured on the
     *                     transition screen — required when the
     *                     workflow's `RequireResolution` validator is
     *                     attached.
     * @param comment optional comment captured on the transition
     *                screen — required when the workflow's
     *                `RequireComment` validator is attached. Phase 4
     *                will additionally write the comment through
     *                `core-comments`; Phase 3 only uses it to satisfy
     *                the validator.
     */
    suspend fun transition(
        id: UUID,
        transitionId: UUID,
        expectedVersion: Long,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
        principalPermissions: Set<PermissionAction> = emptySet(),
        resolutionId: UUID? = null,
        comment: String? = null,
    ): Task

    suspend fun createDocument(
        id: UUID,
        expectedVersion: Long,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ): Task
}
