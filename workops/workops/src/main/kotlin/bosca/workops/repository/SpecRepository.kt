package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.spec.Spec
import kotlinx.serialization.json.JsonElement

@Repository
interface SpecRepository {

    @Query("select * from workops.spec where id = :id")
    suspend fun getById(id: UUID): Spec?

    @Query("select * from workops.spec where id = :id and deleted_at is null")
    suspend fun getActiveById(id: UUID): Spec?

    @Query("select * from workops.spec where key = :key and deleted_at is null")
    suspend fun getActiveByKey(key: String): Spec?

    @Query("select * from workops.spec where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<Spec>

    @Query("select * from workops.spec where metadata_id = :metadataId and deleted_at is null")
    suspend fun getByMetadataId(metadataId: UUID): Spec?

    @Query(
        """
        select * from workops.spec
        where project_id = :projectId and deleted_at is null
        order by modified_at desc
        limit :limit offset :offset
        """
    )
    suspend fun listByProject(projectId: UUID, offset: Long, limit: Int): List<Spec>

    @Query(
        """
        select * from workops.spec
        where program_id = :programId and deleted_at is null
        order by modified_at desc
        limit :limit offset :offset
        """
    )
    suspend fun listByProgram(programId: UUID, offset: Long, limit: Int): List<Spec>

    @Query(
        """
        select * from workops.spec
        where owner_profile_id = :ownerProfileId and deleted_at is null
        order by modified_at desc
        limit :limit offset :offset
        """
    )
    suspend fun listByOwner(ownerProfileId: UUID, offset: Long, limit: Int): List<Spec>

    @Query(
        """
        select * from workops.spec
        where status_id = :statusId and deleted_at is null
        order by modified_at desc
        limit :limit offset :offset
        """
    )
    suspend fun listByStatus(statusId: UUID, offset: Long, limit: Int): List<Spec>

    @Query(
        """
        select * from workops.spec
        where git_repository_id = :gitRepositoryId and deleted_at is null
        """
    )
    suspend fun listByGitRepository(gitRepositoryId: UUID): List<Spec>

    @Query(
        """
        select * from workops.spec
        where parent_spec_id = :parentSpecId and deleted_at is null
        order by sort_order, modified_at desc
        limit :limit offset :offset
        """
    )
    suspend fun listByParentSpec(parentSpecId: UUID, offset: Long, limit: Int): List<Spec>

    @Query(
        """
        select count(*) from workops.spec
        where parent_spec_id = :parentSpecId and deleted_at is null
        """
    )
    suspend fun countByParentSpec(parentSpecId: UUID): Long

    @Query(
        """
        insert into workops.spec (
            key, metadata_id, program_id, project_id,
            parent_spec_id, sort_order,
            status_id, workflow_id, owner_profile_id,
            git_repository_id, git_path,
            watcher_profile_ids, label_ids, external_references,
            created_by_principal_id, modified_by_principal_id
        ) values (
            :key, :metadataId, :programId, :projectId,
            :parentSpecId, :sortOrder,
            :statusId, :workflowId, :ownerProfileId,
            :gitRepositoryId, :gitPath,
            :watcherProfileIds, :labelIds, :externalReferences::jsonb,
            :createdByPrincipalId, :modifiedByPrincipalId
        )
        returning *
        """
    )
    suspend fun add(spec: Spec): Spec

    @Query(
        """
        update workops.spec
        set program_id = :programId,
            project_id = :projectId,
            parent_spec_id = :parentSpecId,
            sort_order = :sortOrder,
            owner_profile_id = :ownerProfileId,
            git_repository_id = :gitRepositoryId,
            git_path = :gitPath,
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
        programId: UUID?,
        projectId: UUID?,
        parentSpecId: UUID?,
        sortOrder: Int,
        ownerProfileId: UUID,
        gitRepositoryId: UUID?,
        gitPath: String?,
        externalReferences: JsonElement?,
        modifiedByPrincipalId: UUID,
        expectedVersion: Long,
    ): Spec?

    @Query(
        """
        update workops.spec
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
    ): Spec?

    @Query(
        """
        update workops.spec
        set deleted_at = now(),
            modified_at = now(),
            modified_by_principal_id = :modifiedByPrincipalId,
            version = version + 1
        where id = :id and version = :expectedVersion and deleted_at is null
        returning *
        """
    )
    suspend fun softDelete(id: UUID, modifiedByPrincipalId: UUID, expectedVersion: Long): Spec?

    @Query(
        """
        update workops.spec
        set deleted_at = null,
            modified_at = now(),
            modified_by_principal_id = :modifiedByPrincipalId,
            version = version + 1
        where id = :id and version = :expectedVersion and deleted_at is not null
        returning *
        """
    )
    suspend fun restore(id: UUID, modifiedByPrincipalId: UUID, expectedVersion: Long): Spec?

    @Query("delete from workops.spec where id = :id and deleted_at is not null")
    suspend fun hardDelete(id: UUID)

    @Query("update workops.spec set child_count = child_count + 1, modified_at = now() where id = :id and deleted_at is null")
    suspend fun incrementChildCount(id: UUID)

    @Query("update workops.spec set child_count = greatest(child_count - 1, 0), modified_at = now() where id = :id and deleted_at is null")
    suspend fun decrementChildCount(id: UUID)

    @Query("update workops.spec set child_done_count = child_done_count + 1, modified_at = now() where id = :id and deleted_at is null")
    suspend fun incrementChildDoneCount(id: UUID)

    @Query("update workops.spec set child_done_count = greatest(child_done_count - 1, 0), modified_at = now() where id = :id and deleted_at is null")
    suspend fun decrementChildDoneCount(id: UUID)
}
