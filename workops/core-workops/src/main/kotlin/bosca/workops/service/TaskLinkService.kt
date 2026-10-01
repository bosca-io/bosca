package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.LinkGraphTooDeepException
import bosca.workops.model.links.CreateTaskLinkTypeInput
import bosca.workops.model.links.TaskLink
import bosca.workops.model.links.TaskLinkInput
import bosca.workops.model.links.TaskLinkType
import bosca.workops.model.links.UpdateTaskLinkTypeInput

/**
 * Manages [TaskLink] rows and enforces R7's link-graph invariants:
 *
 *  - Self-links are rejected (`source == target`).
 *  - `BLOCKS`-category links may not close a cycle. The proposed
 *    link is checked by BFS-ing forward from `target` along
 *    `BLOCKS` edges; if `source` is reachable, inserting would
 *    close a cycle and the operation is rejected.
 *  - The BFS is bounded at 256 visited nodes (R7). Deeper graphs
 *    raise [LinkGraphTooDeepException] rather than paying for an
 *    unbounded traversal.
 *  - Other categories permit cycles — a `RELATES_TO` ring among a
 *    cluster of related tasks is legitimate planning shorthand.
 *
 *  Idempotency: the database has a unique constraint on
 *  `(source, target, link_type)`. The service treats a duplicate
 *  insert as success and returns the pre-existing row.
 */
interface TaskLinkService : Service {

    suspend fun listLinkTypes(): List<TaskLinkType>

    suspend fun getLinkType(id: UUID): TaskLinkType?

    /** Retrieves a single task link by its primary key. */
    suspend fun getById(id: UUID): TaskLink?

    suspend fun listForTask(taskId: UUID): List<TaskLink>

    suspend fun link(input: TaskLinkInput, actingPrincipalId: UUID): TaskLink

    suspend fun unlink(linkId: UUID)

    /** Creates a new [TaskLinkType] definition for admin CRUD (R7). */
    suspend fun createLinkType(input: CreateTaskLinkTypeInput): TaskLinkType

    /** Updates an existing [TaskLinkType] with optimistic-concurrency control. */
    suspend fun updateLinkType(id: UUID, input: UpdateTaskLinkTypeInput): TaskLinkType

    /** Deletes a [TaskLinkType] by primary key. */
    suspend fun deleteLinkType(id: UUID)
}
