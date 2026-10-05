package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.release.Release
import bosca.workops.model.release.ReleaseProjectVersion
import bosca.workops.model.release.TaskAffectedProject

@Repository
interface ReleaseRepository {

    @Query("select * from workops.release where id = :id and deleted_at is null")
    suspend fun getById(id: UUID): Release?

    @Query("select * from workops.release where program_id = :programId and deleted_at is null order by created desc, name desc")
    suspend fun listForProgram(programId: UUID): List<Release>

    @Query(
        """
        insert into workops.release
            (program_id, name, description, release_date, owner_profile_id)
        values
            (:programId, :name, :description, :releaseDate, :ownerProfileId)
        returning *
        """
    )
    suspend fun add(
        programId: UUID,
        name: String,
        description: String?,
        releaseDate: OffsetDateTime?,
        ownerProfileId: UUID?,
    ): Release

    @Query(
        """
        update workops.release
        set released_at = now(), version = version + 1
        where id = :id and version = :expectedVersion and released_at is null
        returning *
        """
    )
    suspend fun markReleased(id: UUID, expectedVersion: Long): Release?

    @Query(
        """
        select * from workops.release_component_version
        where release_id = :releaseId
        order by deployment_order nulls last
        """
    )
    suspend fun listVersions(releaseId: UUID): List<ReleaseProjectVersion>

    @Query(
        """
        update workops.release_component_version
        set deployment_status = 'PENDING', deployed_at = null, deployed_by_principal_id = null
        where release_id = :releaseId
        """,
        returnUpdateCount = true
    )
    suspend fun resetDeployments(releaseId: UUID): Int

    @Query(
        """
        insert into workops.release_component_version (release_id, project_id, version_id)
        values (:releaseId, :projectId, :versionId)
        on conflict (release_id, project_id, version_id) do nothing
        returning *
        """
    )
    suspend fun bundle(releaseId: UUID, projectId: UUID, versionId: UUID): ReleaseProjectVersion?

    @Query(
        """
        delete from workops.release_component_version
        where release_id = :releaseId and version_id = :versionId
        """
    )
    suspend fun unbundle(releaseId: UUID, versionId: UUID)

    @Query(
        """
        update workops.release
        set name = :name, description = :description, release_date = :releaseDate, version = version + 1
        where id = :id and version = :expectedVersion and deleted_at is null
        returning *
        """
    )
    suspend fun update(
        id: UUID,
        name: String,
        description: String?,
        releaseDate: OffsetDateTime?,
        expectedVersion: Long,
    ): Release?

    @Query(
        """
        update workops.release set deleted_at = now()
        where id = :id and deleted_at is null
        """
    )
    suspend fun softDelete(id: UUID)
}

@Repository
interface TaskAffectedProjectRepository {

    @Query("select * from workops.task_affected_project where task_id = :taskId")
    suspend fun listForTask(taskId: UUID): List<TaskAffectedProject>

    @Query("select * from workops.task_affected_project where project_id = :projectId")
    suspend fun listForProject(projectId: UUID): List<TaskAffectedProject>

    @Query(
        """
        insert into workops.task_affected_project (task_id, project_id)
        values (:taskId, :projectId)
        on conflict (task_id, project_id) do nothing
        returning *
        """
    )
    suspend fun add(taskId: UUID, projectId: UUID): TaskAffectedProject?

    @Query(
        """
        delete from workops.task_affected_project
        where task_id = :taskId and project_id = :projectId
        """
    )
    suspend fun remove(taskId: UUID, projectId: UUID)
}

@Repository
interface TaskMoveAuditRepository {

    @Query(
        """
        insert into workops.task_move_audit
            (task_id, from_project_id, to_project_id, legacy_key, new_key,
             moved_by_principal_id, status_mapping, field_mapping)
        values (:taskId, :fromProjectId, :toProjectId, :legacyKey, :newKey,
                :movedByPrincipalId, cast(:statusMapping as jsonb),
                cast(:fieldMapping as jsonb))
        """
    )
    suspend fun audit(input: TaskMoveAuditParams)
}

data class TaskMoveAuditParams(
    val taskId: UUID,
    val fromProjectId: UUID,
    val toProjectId: UUID,
    val legacyKey: String,
    val newKey: String,
    val movedByPrincipalId: UUID,
    val statusMapping: String,
    val fieldMapping: String,
)

@Repository
interface TaskKeyAliasRepository {

    @Query(
        """
        insert into workops.task_key_alias (legacy_key, task_id)
        values (:legacyKey, :taskId)
        on conflict (legacy_key) do nothing
        """
    )
    suspend fun add(legacyKey: String, taskId: UUID)

    @Query("select task_id from workops.task_key_alias where legacy_key = :legacyKey")
    suspend fun resolve(legacyKey: String): UUID?
}

@Repository
interface CrossProjectTaskRepository {

    @Query(
        """
        update workops.task
        set project_id = :targetProjectId,
            key = :newKey,
            status_id = :targetStatusId,
            modified_at = now(),
            modified_by_principal_id = :movedByPrincipalId,
            version = version + 1
        where id = :taskId and version = :expectedVersion
        """
    )
    suspend fun moveTask(input: MoveTaskParams)
}

data class MoveTaskParams(
    val taskId: UUID,
    val targetProjectId: UUID,
    val newKey: String,
    val targetStatusId: UUID,
    val movedByPrincipalId: UUID,
    val expectedVersion: Long,
)
