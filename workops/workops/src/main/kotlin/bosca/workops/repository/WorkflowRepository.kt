package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.workflow.Workflow
import bosca.workops.model.workflow.WorkflowState
import bosca.workops.model.workflow.WorkflowTransition

/**
 * Reads and writes [Workflow], [WorkflowState], and
 * [WorkflowTransition] rows. The state and transition reads are
 * exposed as separate methods rather than a graph load so the engine
 * can choose what to fetch — when answering "what transitions are
 * available?" the engine wants only the transitions matching the
 * current state, not the entire workflow.
 */
@Repository
interface WorkflowRepository {

    @Query("select * from workops.workflow order by name")
    suspend fun listWorkflows(): List<Workflow>

    @Query("select * from workops.workflow where id = :id")
    suspend fun getWorkflowById(id: UUID): Workflow?

    @Query("select * from workops.workflow where id = any(:ids)")
    suspend fun getWorkflowsByIds(ids: List<UUID>): List<Workflow>

    @Query("select * from workops.workflow_state where workflow_id = :workflowId order by display_order")
    suspend fun listStates(workflowId: UUID): List<WorkflowState>

    @Query("select * from workops.workflow_state where id = :id")
    suspend fun getStateById(id: UUID): WorkflowState?

    @Query("select * from workops.workflow_state where id = any(:ids)")
    suspend fun getStatesByIds(ids: List<UUID>): List<WorkflowState>

    /**
     * Fetch the state row that maps a (workflow, status) pair. The
     * engine uses this to convert "the task's current statusId" into
     * "the workflow_state row" so transitions can be matched by their
     * `from_state_ids` column.
     */
    @Query("select * from workops.workflow_state where workflow_id = :workflowId and status_id = :statusId")
    suspend fun getStateByStatus(workflowId: UUID, statusId: UUID): WorkflowState?

    @Query("select * from workops.workflow_transition where workflow_id = :workflowId")
    suspend fun listTransitions(workflowId: UUID): List<WorkflowTransition>

    @Query("select * from workops.workflow_transition where id = :id")
    suspend fun getTransitionById(id: UUID): WorkflowTransition?

    @Query(
        """
        insert into workops.workflow (name, description, initial_state_id)
        values (:name, :description, :initialStateId)
        returning *
        """
    )
    suspend fun addWorkflow(
        name: String,
        description: String?,
        initialStateId: UUID?,
    ): Workflow

    /** Header edits retain the initial state and the separately stored graph. */
    @Query("""
        update workops.workflow
        set name = :name, description = :description, version = version + 1
        where id = :id and version = :expectedVersion
        returning *
    """)
    suspend fun updateWorkflow(id: UUID, name: String, description: String?, expectedVersion: Long): Workflow?

    @Query("delete from workops.workflow where id = :id")
    suspend fun deleteWorkflow(id: UUID)
}
