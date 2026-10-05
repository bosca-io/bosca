package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.spec.Spec
import bosca.workops.model.task.Task
import bosca.workops.model.workflow.Workflow
import bosca.workops.model.workflow.WorkflowScheme
import bosca.workops.model.workflow.WorkflowState
import bosca.workops.model.workflow.WorkflowTransition

/**
 * Read surface for workflow definitions. Phase 3 keeps the runtime
 * read-only — the seeded "Default" workflow + scheme cover every
 * Phase 3 use case. Phase 7 (R11) wires the admin CRUD that lets
 * organizations define their own workflows behind the
 * `MANAGE_WORKFLOWS` permission.
 *
 * The [resolveWorkflowForTask] helper is the workflow engine's
 * entry point: it reads the project's scheme and the per-task-type
 * override map to land on the single [Workflow] that runs a given
 * task. Returning `Workflow` (not just an id) lets callers list
 * available transitions without re-fetching.
 */
interface WorkflowService : Service {

    suspend fun listWorkflows(): List<Workflow>

    suspend fun getWorkflow(id: UUID): Workflow?

    suspend fun listSchemes(): List<WorkflowScheme>

    suspend fun getScheme(id: UUID): WorkflowScheme?

    suspend fun listStates(workflowId: UUID): List<WorkflowState>

    suspend fun getState(id: UUID): WorkflowState?

    suspend fun listTransitions(workflowId: UUID): List<WorkflowTransition>

    /** Returns the number of unresolved direct subtasks that currently block task transitions. */
    suspend fun countUnresolvedSubtasks(taskId: UUID): Int

    /**
     * Returns the [Workflow] (and its current state for the task) that
     * the engine should evaluate for [task]. Combines:
     *  - project's `default_workflow_scheme_id`
     *  - the scheme's `per_task_type_workflow_ids` override map
     *  - the task's current `status_id` resolved into a `WorkflowState`
     */
    suspend fun resolveWorkflowForTask(task: Task): WorkflowResolution

    /**
     * Returns the [Workflow] (and its current state) that governs [spec].
     * Unlike tasks, specs carry their `workflowId` directly, so no
     * scheme/override resolution is needed — just the state lookup.
     */
    suspend fun resolveWorkflowForSpec(spec: Spec): WorkflowResolution
}

/** Result of [WorkflowService.resolveWorkflowForTask]. */
data class WorkflowResolution(
    val workflow: Workflow,
    val currentState: WorkflowState,
    val transitions: List<WorkflowTransition>,
)
