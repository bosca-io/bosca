package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.links.LinkCategory
import bosca.workops.model.links.TaskLink
import bosca.workops.model.links.TaskLinkType

/** Reads and writes [TaskLink] and [TaskLinkType] rows. */
@Repository
interface TaskLinkRepository {

    @Query("select * from workops.task_link_type order by name")
    suspend fun listLinkTypes(): List<TaskLinkType>

    @Query("select * from workops.task_link_type where id = :id")
    suspend fun getLinkTypeById(id: UUID): TaskLinkType?

    @Query("select * from workops.task_link_type where id = any(:ids)")
    suspend fun getLinkTypesByIds(ids: List<UUID>): List<TaskLinkType>

    /**
     * The set of task ids that the given task BLOCKS (forward edges
     * for cycle detection). The link engine traverses by category to
     * keep the BFS bounded to the relevant graph.
     */
    @Query(
        """
        select tl.target_task_id from workops.task_link tl
        join workops.task_link_type tlt on tlt.id = tl.link_type_id
        where tl.source_task_id = :sourceTaskId
          and tlt.category = (:category)::workops.link_category
        """
    )
    suspend fun blockedTargets(sourceTaskId: UUID, category: LinkCategory): List<UUID>

    @Query("select * from workops.task_link where id = :id")
    suspend fun getById(id: UUID): TaskLink?

    @Query(
        """
        select * from workops.task_link
        where source_task_id = :taskId or target_task_id = :taskId
        order by created_at desc
        """
    )
    suspend fun listByTask(taskId: UUID): List<TaskLink>

    @Query(
        """
        insert into workops.task_link (link_type_id, source_task_id, target_task_id, created_by_principal_id)
        values (:linkTypeId, :sourceTaskId, :targetTaskId, :createdByPrincipalId)
        on conflict (source_task_id, target_task_id, link_type_id) do nothing
        returning *
        """
    )
    suspend fun add(
        linkTypeId: UUID,
        sourceTaskId: UUID,
        targetTaskId: UUID,
        createdByPrincipalId: UUID,
    ): TaskLink?

    /**
     * Fetch the row matching the unique triple — used by `linkTasks`
     * after `add`'s `on conflict do nothing` returns null so the
     * caller can return the pre-existing link rather than crashing
     * on a duplicate.
     */
    @Query(
        """
        select * from workops.task_link
        where source_task_id = :sourceTaskId
          and target_task_id = :targetTaskId
          and link_type_id = :linkTypeId
        """
    )
    suspend fun getByTriple(
        sourceTaskId: UUID,
        targetTaskId: UUID,
        linkTypeId: UUID,
    ): TaskLink?

    @Query("delete from workops.task_link where id = :id")
    suspend fun deleteById(id: UUID)

    @Query(
        """
        insert into workops.task_link_type (name, inward_label, outward_label, category)
        values (:name, :inwardLabel, :outwardLabel, (:category)::workops.link_category)
        returning *
        """
    )
    suspend fun addLinkType(
        name: String,
        inwardLabel: String,
        outwardLabel: String,
        category: LinkCategory,
    ): TaskLinkType

    @Query(
        """
        update workops.task_link_type
        set name = :name,
            inward_label = :inwardLabel,
            outward_label = :outwardLabel,
            category = (:category)::workops.link_category,
            version = version + 1
        where id = :id and version = :expectedVersion
        returning *
        """
    )
    suspend fun updateLinkType(
        id: UUID,
        name: String,
        inwardLabel: String,
        outwardLabel: String,
        category: LinkCategory,
        expectedVersion: Long,
    ): TaskLinkType?

    @Query("delete from workops.task_link_type where id = :id")
    suspend fun deleteLinkTypeById(id: UUID)
}
