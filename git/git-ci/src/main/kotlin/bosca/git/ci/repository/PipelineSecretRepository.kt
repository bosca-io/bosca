package bosca.git.ci.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.git.model.PipelineSecret
import bosca.serialization.UUID

@Repository
interface PipelineSecretRepository {

    @Query("select * from git.pipeline_secrets where repository_id = :repositoryId order by name")
    suspend fun findByRepository(repositoryId: UUID): List<PipelineSecret>

    @Query("select * from git.pipeline_secrets where repository_id = :repositoryId and name = :name")
    suspend fun findByName(repositoryId: UUID, name: String): PipelineSecret?

    @Query("select * from git.pipeline_secrets where repository_id = :repositoryId")
    suspend fun findAllByRepository(repositoryId: UUID): List<PipelineSecret>

    @Query("""
        insert into git.pipeline_secrets (repository_id, name, encrypted_value, environment_key)
        values (:repositoryId, :name, :encryptedValue, :environmentKey)
        on conflict (repository_id, name)
        do update set encrypted_value = excluded.encrypted_value,
                      environment_key = excluded.environment_key,
                      updated = now()
        returning *
    """)
    suspend fun upsert(secret: PipelineSecret): PipelineSecret

    @Query("delete from git.pipeline_secrets where repository_id = :repositoryId and name = :name")
    suspend fun delete(repositoryId: UUID, name: String)
}

@Repository
interface PipelineSecretPermissionRepository {

    @Query("insert into git.pipeline_secret_permissions (secret_id, group_id, action) values (:secretId, :groupId, (:action)::permission_action) on conflict do nothing")
    suspend fun add(secretId: UUID, groupId: UUID, action: bosca.security.model.PermissionAction)

    @Query("select * from git.pipeline_secret_permissions where secret_id = :id")
    suspend fun findBySecretId(id: UUID): List<bosca.git.model.PipelineSecretPermission>

    @Query("select * from git.pipeline_secret_permissions where secret_id = any(:ids)")
    suspend fun findBySecretIds(ids: List<UUID>): List<bosca.git.model.PipelineSecretPermission>

    @Query("delete from git.pipeline_secret_permissions where secret_id = :id and group_id = :groupId and action = (:action)::permission_action")
    suspend fun delete(id: UUID, groupId: UUID, action: bosca.security.model.PermissionAction)
}
