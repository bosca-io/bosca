package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.spec.Spec
import bosca.workops.model.task.Task
import bosca.workops.model.workflow.Workflow
import bosca.workops.model.workflow.WorkflowScheme
import bosca.workops.model.workflow.WorkflowState
import bosca.workops.model.workflow.WorkflowTransition
import bosca.workops.model.workflow.CreateWorkflowInput
import bosca.workops.model.workflow.UpdateWorkflowInput
import bosca.workops.model.workflow.CreateWorkflowSchemeInput
import bosca.workops.model.workflow.UpdateWorkflowSchemeInput

/**
 * Workflow definitions, scheme configuration and runtime resolution.
 * Header mutations retain the existing state/transition graph; scheme
 * header/default edits retain per-task-type overrides.
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

    /** Creates an empty definition; states and transitions are configured independently. */
    suspend fun createWorkflow(input: CreateWorkflowInput): Workflow

    /** Updates name/description at the inspected version without changing its graph. */
    suspend fun updateWorkflow(id: UUID, input: UpdateWorkflowInput): Workflow

    /** Deletes an unreferenced definition, rejecting schemes that still reference it. */
    suspend fun deleteWorkflow(id: UUID)

    /** Creates a scheme with the selected default and an empty override map. */
    suspend fun createScheme(input: CreateWorkflowSchemeInput): WorkflowScheme

    /** Updates header/default fields at the inspected version, retaining stored overrides. */
    suspend fun updateScheme(id: UUID, input: UpdateWorkflowSchemeInput): WorkflowScheme

    /** Deletes a scheme using the owning persistence constraints for existing references. */
    suspend fun deleteScheme(id: UUID)

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
