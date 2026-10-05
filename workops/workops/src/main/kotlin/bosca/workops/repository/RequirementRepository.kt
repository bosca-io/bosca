package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.requirement.Requirement
import bosca.workops.model.requirement.RequirementParent
import kotlinx.serialization.json.JsonElement

@Repository
interface RequirementRepository {

    @Query("select * from workops.requirement where id = :id")
    suspend fun getById(id: UUID): Requirement?

    @Query("select * from workops.requirement where id = :id and deleted_at is null")
    suspend fun getActiveById(id: UUID): Requirement?

    @Query("select * from workops.requirement where key = :key and deleted_at is null")
    suspend fun getActiveByKey(key: String): Requirement?

    @Query("select * from workops.requirement where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<Requirement>

    @Query("select * from workops.requirement where task_id = :taskId and deleted_at is null")
    suspend fun getByTaskId(taskId: UUID): Requirement?

    @Query(
        """
        update workops.requirement
        set status_id = :statusId,
            modified_by_principal_id = :modifiedByPrincipalId,
            modified_at = now(),
            version = version + 1
        where task_id = :taskId and deleted_at is null
        returning *
        """
    )
    suspend fun syncStatusByTaskId(
        taskId: UUID,
        statusId: UUID,
        modifiedByPrincipalId: UUID,
    ): Requirement?

    @Query(
        """
        update workops.requirement
        set status_id = :statusId,
            modified_by_principal_id = :modifiedByPrincipalId,
            modified_at = now(),
            version = version + 1
        where parent_type = 'task' and parent_id = :parentTaskId and deleted_at is null
        """
    )
    suspend fun syncStatusByParentTask(
        parentTaskId: UUID,
        statusId: UUID,
        modifiedByPrincipalId: UUID,
    )

    @Query(
        """
        select * from workops.requirement
        where parent_type = (:parentType)::workops.requirement_parent
          and parent_id = :parentId
          and deleted_at is null
        order by sort_order, modified_at desc
        limit :limit offset :offset
        """
    )
    suspend fun listByParent(
        parentType: RequirementParent,
        parentId: UUID,
        offset: Long,
        limit: Int,
    ): List<Requirement>

    @Query(
        """
        select * from workops.requirement
        where status_id = :statusId and deleted_at is null
        order by modified_at desc
        limit :limit offset :offset
        """
    )
    suspend fun listByStatus(statusId: UUID, offset: Long, limit: Int): List<Requirement>

    @Query(
        """
        select count(*) from workops.requirement
        where parent_type = (:parentType)::workops.requirement_parent
          and parent_id = :parentId
          and deleted_at is null
        """
    )
    suspend fun countByParent(parentType: RequirementParent, parentId: UUID): Long

    @Query(
        """
        insert into workops.requirement (
            key, metadata_id, parent_type, parent_id,
            status_id, workflow_id, priority_id,
            assignee_profile_id, task_id, sort_order,
            label_ids, external_references,
            created_by_principal_id, modified_by_principal_id
        ) values (
            :key, :metadataId, (:parentType)::workops.requirement_parent, :parentId,
            :statusId, :workflowId, :priorityId,
            :assigneeProfileId, :taskId, :sortOrder,
            :labelIds, :externalReferences::jsonb,
            :createdByPrincipalId, :modifiedByPrincipalId
        )
        returning *
        """
    )
    suspend fun add(requirement: Requirement): Requirement

    @Query(
        """
        update workops.requirement
        set priority_id = :priorityId,
            assignee_profile_id = :assigneeProfileId,
            sort_order = :sortOrder,
            external_references = :externalReferences::jsonb,
            modified_by_principal_id = :modifiedByPrincipalId,
            modified_at = now(),
            version = version + 1
        where id = :id and version = :expectedVersion and deleted_at is null
        returning *
        """
    )
    suspend fun updateCore(
        id: UUID,
        priorityId: UUID,
        assigneeProfileId: UUID?,
        sortOrder: Int,
        externalReferences: JsonElement?,
        modifiedByPrincipalId: UUID,
        expectedVersion: Long,
    ): Requirement?

    @Query(
        """
        update workops.requirement
        set status_id = :statusId,
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
        modifiedByPrincipalId: UUID,
        expectedVersion: Long,
    ): Requirement?

    @Query(
        """
        update workops.requirement
        set deleted_at = now(),
            modified_at = now(),
            modified_by_principal_id = :modifiedByPrincipalId,
            version = version + 1
        where id = :id and version = :expectedVersion and deleted_at is null
        returning *
        """
    )
    suspend fun softDelete(id: UUID, modifiedByPrincipalId: UUID, expectedVersion: Long): Requirement?

    @Query(
        """
        update workops.requirement
        set deleted_at = null,
            modified_at = now(),
            modified_by_principal_id = :modifiedByPrincipalId,
            version = version + 1
        where id = :id and version = :expectedVersion and deleted_at is not null
        returning *
        """
    )
    suspend fun restore(id: UUID, modifiedByPrincipalId: UUID, expectedVersion: Long): Requirement?

    @Query("delete from workops.requirement where id = :id and deleted_at is not null")
    suspend fun hardDelete(id: UUID)

    @Query(
        """
        update workops.requirement
        set parent_type = (:parentType)::workops.requirement_parent,
            parent_id = :parentId,
            modified_by_principal_id = :modifiedByPrincipalId,
            modified_at = now(),
            version = version + 1
        where id = :id and version = :expectedVersion and deleted_at is null
        returning *
        """
    )
    suspend fun moveToParent(
        id: UUID,
        parentType: RequirementParent,
        parentId: UUID,
        modifiedByPrincipalId: UUID,
        expectedVersion: Long,
    ): Requirement?
}
