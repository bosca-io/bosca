package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.project.Project

/**
 * Persists [Project] rows in `workops.project`. Project keys are
 * globally unique (they form the prefix of every task key) so the
 * `getByKey` lookup does not need a parent-id qualifier.
 *
 * The per-project task-key counter row in `workops.project_key_counter`
 * is owned by [ProjectKeyCounterRepository] — keeping the two repos
 * separate lets the counter be locked / updated independently of the
 * project row, which matters once cross-project task moves (R30)
 * land in Phase 16.
 */
@Repository
interface ProjectRepository {

    @Query("select * from workops.project order by key")
    suspend fun getAll(): List<Project>

    @Query("select * from workops.project where program_id = :programId order by key limit :limit offset :offset")
    suspend fun getByProgram(programId: UUID, offset: Long, limit: Int): List<Project>

    @Query("select * from workops.project where id = :id")
    suspend fun getById(id: UUID): Project?

    @Query("select * from workops.project where key = :key")
    suspend fun getByKey(key: String): Project?

    @Query("select * from workops.project where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<Project>

    @Query(
        """
        insert into workops.project
            (program_id, key, name, description, owner_profile_id,
             default_task_type_scheme_id, default_workflow_scheme_id, default_field_configuration_scheme_id)
        values
            (:programId, :key, :name, :description, :ownerProfileId,
             :defaultTaskTypeSchemeId, :defaultWorkflowSchemeId, :defaultFieldConfigurationSchemeId)
        returning *
        """
    )
    suspend fun add(project: Project): Project

    @Query(
        """
        update workops.project
        set name = :name,
            description = :description,
            owner_profile_id = :ownerProfileId,
            default_task_type_scheme_id = :defaultTaskTypeSchemeId,
            task_creation_form_schema_key = :taskCreationFormSchemaKey,
            modified_at = now(),
            version = version + 1
        where id = :id and version = :expectedVersion
        returning *
        """
    )
    suspend fun update(
        id: UUID,
        name: String,
        description: String?,
        ownerProfileId: UUID,
        defaultTaskTypeSchemeId: UUID?,
        taskCreationFormSchemaKey: String,
        expectedVersion: Long,
    ): Project?

    @Query(
        """
        update workops.project
        set program_id = :programId,
            modified_at = now(),
            version = version + 1
        where id = :id and version = :expectedVersion
        returning *
        """
    )
    suspend fun move(id: UUID, programId: UUID, expectedVersion: Long): Project?

    @Query(
        """
        update workops.project
        set archived_at = now(), modified_at = now(), version = version + 1
        where id = :id and archived_at is null and version = :expectedVersion
        returning *
        """
    )
    suspend fun archive(id: UUID, expectedVersion: Long): Project?

    @Query(
        """
        update workops.project
        set archived_at = null, modified_at = now(), version = version + 1
        where id = :id and archived_at is not null and version = :expectedVersion
        returning *
        """
    )
    suspend fun unarchive(id: UUID, expectedVersion: Long): Project?
}
