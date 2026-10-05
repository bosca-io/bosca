package bosca.git.ci.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.git.model.Pipeline
import bosca.serialization.UUID

@Repository
interface PipelineRepository {

    /** Serializes catalog reconciliation and identity creation for a repository until transaction commit. */
    @Query("select true from pg_advisory_xact_lock(hashtextextended(cast(:repositoryId as text), 0))")
    suspend fun lockForSync(repositoryId: UUID): Boolean

    @Query("select * from git.pipelines where id = :id")
    suspend fun findById(id: UUID): Pipeline?

    @Query("select * from git.pipelines where repository_id = :repositoryId and deleted_at is null order by name")
    suspend fun findByRepository(repositoryId: UUID): List<Pipeline>

    @Query("select * from git.pipelines where repository_id = :repositoryId and file_path = :filePath")
    suspend fun findByRepositoryAndFilePath(repositoryId: UUID, filePath: String): Pipeline?

    /** Resolves a pipeline by its YAML `name:` — how pipeline requirements reference upstreams. */
    @Query("select * from git.pipelines where repository_id = :repositoryId and name = :name and deleted_at is null limit 1")
    suspend fun findByRepositoryAndName(repositoryId: UUID, name: String): Pipeline?

    @Query("""
        insert into git.pipelines (repository_id, file_path, name, triggers, concurrency, config_hash, deleted_at)
        values (:repositoryId, :filePath, :name, :triggers::jsonb, :concurrency::jsonb, :configHash, :deletedAt)
        returning *
    """)
    suspend fun create(pipeline: Pipeline): Pipeline

    @Query("""
        update git.pipelines
        set name = :name, triggers = :triggers::jsonb, concurrency = :concurrency::jsonb,
            config_hash = :configHash, deleted_at = :deletedAt, updated = now()
        where id = :id
        returning *
    """)
    suspend fun update(pipeline: Pipeline): Pipeline?

    @Query("select * from git.pipelines where deleted_at is null")
    suspend fun findAll(): List<Pipeline>

    /** Removes an entry from the live catalog while preserving its identity and dependent history. */
    @Query("update git.pipelines set deleted_at = now(), updated = now() where id = :id and deleted_at is null")
    suspend fun archive(id: UUID)

    @Query("delete from git.pipelines where id = :id")
    suspend fun delete(id: UUID)
}
