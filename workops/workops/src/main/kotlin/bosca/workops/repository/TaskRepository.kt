package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.task.Task

/**
 * Persists [Task] rows in `workops.task`. Phase 2 ships the core
 * CRUD surface; Phase 3+ extends with workflow-aware mutators
 * (`transitionTask`), Phase 5 with sprint membership, Phase 7 with
 * watcher / vote mutators, and so on.
 *
 * Optimistic locking is mandatory on every mutator. The [updateCore],
 * [softDelete], and [restore] methods return `null` on no-row-matched
 * to surface stale-version writes; the calling service translates
 * that into the typed `OPTIMISTIC_LOCK_FAILED` error.
 *
 * `getActiveById` / `getActiveByKey` filter `deleted_at is null`. The
 * raw `getById` is reserved for the audit log and the restore path
 * which legitimately needs to see soft-deleted rows.
 */
@Repository
interface TaskRepository {

    @Query("select * from workops.task where id = :id")
    suspend fun getById(id: UUID): Task?

    @Query("select * from workops.task where id = :id and deleted_at is null")
    suspend fun getActiveById(id: UUID): Task?

    @Query("select * from workops.task where key = :key and deleted_at is null")
    suspend fun getActiveByKey(key: String): Task?

    @Query("select * from workops.task where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<Task>

    @Query(
        """
        select * from workops.task
        where project_id = :projectId and deleted_at is null
        order by modified_at desc
        limit :limit offset :offset
        """
    )
    suspend fun listByProject(projectId: UUID, offset: Long, limit: Int): List<Task>

    @Query(
        """
        select t.* from workops.task t
        join workops.task_affected_project ap on ap.task_id = t.id
        where ap.project_id = :projectId and t.deleted_at is null
        order by t.modified_at desc
        limit :limit offset :offset
        """
    )
    suspend fun listByAffectedProject(projectId: UUID, offset: Long, limit: Int): List<Task>

    @Query(
        """
        select *
        from workops.task
        where deleted_at is null
          and resolution_at is null
          and due_date is not null
          and due_date <= now()
          and due_notified_for is distinct from due_date
        order by due_date, id
        for update skip locked
        limit :limit
        """
    )
    suspend fun findDueForNotification(limit: Int): List<Task>

    @Query(
        "update workops.task set due_notified_for = :dueDate where id = :id",
        returnUpdateCount = true,
    )
    suspend fun markDueNotified(id: UUID, dueDate: OffsetDateTime): Int

    @Query(
        """
        insert into workops.task (
            key, project_id, task_type_id, status_id, priority_id,
            summary, description_markdown, description_html,
            reporter_profile_id, assignee_profile_id,
            parent_task_id, epic_task_id, sprint_id,
            affects_version_ids, fix_version_ids, component_ids, label_ids,
            original_estimate_seconds, remaining_estimate_seconds,
            time_spent_seconds, due_date, start_date,
            resolution_id, resolution_at, sla_due_at,
            content_item_id, collection_id, custom_field_values,
            watcher_profile_ids, vote_count, external_references,
            created_by_principal_id, modified_by_principal_id
        ) values (
            :key, :projectId, :taskTypeId, :statusId, :priorityId,
            :summary, :descriptionMarkdown, :descriptionHtml,
            :reporterProfileId, :assigneeProfileId,
            :parentTaskId, :epicTaskId, :sprintId,
            :affectsVersionIds, :fixVersionIds, :componentIds, :labelIds,
            :originalEstimateSeconds, :remainingEstimateSeconds,
            :timeSpentSeconds, :dueDate, :startDate,
            :resolutionId, :resolutionAt, :slaDueAt,
            :contentItemId, :collectionId, :customFieldValues::jsonb,
            :watcherProfileIds, :voteCount, :externalReferences::jsonb,
            :createdByPrincipalId, :modifiedByPrincipalId
        )
        returning *
        """
    )
    suspend fun add(task: Task): Task

    /**
     * Mutates the everyday-edit fields of a task. Phase 3 introduces
     * the workflow-aware `transitionTask` path which is the only
     * permitted writer of `status_id` / `resolution_id` once it lands;
     * until then, [updateCore] accepts a status change directly so a
     * minimum-viable Phase 2 task tracker functions.
     */
    @Query(
        """
        update workops.task
        set summary = :summary,
            description_markdown = :descriptionMarkdown,
            description_html = :descriptionHtml,
            task_type_id = :taskTypeId,
            assignee_profile_id = :assigneeProfileId,
            priority_id = :priorityId,
            status_id = :statusId,
            sprint_id = :sprintId,
            due_date = :dueDate,
            start_date = :startDate,
            original_estimate_seconds = :originalEstimateSeconds,
            remaining_estimate_seconds = :remainingEstimateSeconds,
            parent_task_id = :parentTaskId,
            modified_by_principal_id = :modifiedByPrincipalId,
            modified_at = now(),
            version = version + 1
        where id = :id and version = :expectedVersion and deleted_at is null
        returning *
        """
    )
    suspend fun updateCore(
        id: UUID,
        summary: String,
        descriptionMarkdown: String?,
        descriptionHtml: String?,
        taskTypeId: UUID,
        assigneeProfileId: UUID?,
        priorityId: UUID,
        statusId: UUID,
        sprintId: UUID?,
        dueDate: OffsetDateTime?,
        startDate: OffsetDateTime?,
        originalEstimateSeconds: Long?,
        remainingEstimateSeconds: Long?,
        parentTaskId: UUID?,
        modifiedByPrincipalId: UUID,
        expectedVersion: Long,
    ): Task?

    @Query(
        """
        update workops.task
        set deleted_at = now(),
            modified_at = now(),
            modified_by_principal_id = :modifiedByPrincipalId,
            version = version + 1
        where id = :id and version = :expectedVersion and deleted_at is null
        returning *
        """
    )
    suspend fun softDelete(id: UUID, modifiedByPrincipalId: UUID, expectedVersion: Long): Task?

    @Query(
        """
        update workops.task
        set deleted_at = null,
            modified_at = now(),
            modified_by_principal_id = :modifiedByPrincipalId,
            version = version + 1
        where id = :id and version = :expectedVersion and deleted_at is not null
        returning *
        """
    )
    suspend fun restore(id: UUID, modifiedByPrincipalId: UUID, expectedVersion: Long): Task?

    /**
     * Apply a workflow transition's full effect inside one optimistic-
     * locked UPDATE. Post-functions in Phase 3 can rewrite assignee,
     * resolution, due-date, start-date, summary, description, priority,
     * parent, and epic; the column list here covers every Phase 3
     * write path so the service composes the new row in memory and
     * fires one UPDATE instead of one per post-function.
     */
    @Query(
        """
        update workops.task
        set status_id = :statusId,
            assignee_profile_id = :assigneeProfileId,
            resolution_id = :resolutionId,
            resolution_at = :resolutionAt,
            summary = :summary,
            description_markdown = :descriptionMarkdown,
            priority_id = :priorityId,
            due_date = :dueDate,
            start_date = :startDate,
            parent_task_id = :parentTaskId,
            epic_task_id = :epicTaskId,
            modified_by_principal_id = :modifiedByPrincipalId,
            modified_at = now(),
            version = version + 1
        where id = :id and version = :expectedVersion and deleted_at is null
        returning *
        """
    )
    suspend fun applyTransition(
        id: UUID,
        statusId: UUID,
        assigneeProfileId: UUID?,
        resolutionId: UUID?,
        resolutionAt: OffsetDateTime?,
        summary: String,
        descriptionMarkdown: String?,
        priorityId: UUID,
        dueDate: OffsetDateTime?,
        startDate: OffsetDateTime?,
        parentTaskId: UUID?,
        epicTaskId: UUID?,
        modifiedByPrincipalId: UUID,
        expectedVersion: Long,
    ): Task?

    @Query("delete from workops.task where id = :id and deleted_at is not null")
    suspend fun hardDelete(id: UUID)

    /**
     * Set the entire `custom_field_values` jsonb in one atomic
     * optimistic-locked write. The service composes the merged
     * map (existing + delta) before calling this — Phase 4 doesn't
     * support partial-key updates at the repository layer.
     */
    @Query(
        """
        update workops.task
        set custom_field_values = :customFieldValues::jsonb,
            modified_by_principal_id = :modifiedByPrincipalId,
            modified_at = now(),
            version = version + 1
        where id = :id and version = :expectedVersion and deleted_at is null
        returning *
        """
    )
    suspend fun setCustomFieldValues(
        id: UUID,
        customFieldValues: kotlinx.serialization.json.JsonElement,
        modifiedByPrincipalId: UUID,
        expectedVersion: Long,
    ): Task?

    @Query(
        """
        update workops.task
        set metadata_id = :metadataId,
            modified_by_principal_id = :modifiedByPrincipalId,
            modified_at = now(),
            version = version + 1
        where id = :id and version = :expectedVersion and deleted_at is null
        returning *
        """
    )
    suspend fun setMetadataId(
        id: UUID,
        metadataId: UUID,
        modifiedByPrincipalId: UUID,
        expectedVersion: Long,
    ): Task?
}
