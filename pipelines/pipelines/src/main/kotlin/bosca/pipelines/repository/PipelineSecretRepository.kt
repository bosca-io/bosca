package bosca.pipelines.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.pipelines.model.PipelineSecret

/** Encrypted node-secret store (`pipelines.pipeline_secret`) — keyed by name. */
@Repository
interface PipelineSecretRepository {

    @Query("select * from pipelines.pipeline_secret order by name")
    suspend fun findAll(): List<PipelineSecret>

    @Query("select * from pipelines.pipeline_secret where name = :name")
    suspend fun findByName(name: String): PipelineSecret?

    @Query(
        """
        insert into pipelines.pipeline_secret (name, encrypted_value)
        values (:name, :encryptedValue)
        on conflict (name) do update set encrypted_value = excluded.encrypted_value, modified_at = now()
        returning *
        """
    )
    suspend fun upsert(secret: PipelineSecret): PipelineSecret

    @Query("delete from pipelines.pipeline_secret where name = :name")
    suspend fun delete(name: String)
}
