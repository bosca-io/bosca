package bosca.communications.repository

import bosca.communications.model.BmlMessageProject
import bosca.db.annotation.Query
import bosca.serialization.UUID
import bosca.db.annotation.Repository

@Repository
interface BmlMessageProjectRepository {

    @Query("select * from communications.bml_message_projects order by key")
    suspend fun getAll(): List<BmlMessageProject>

    @Query("select * from communications.bml_message_projects where key = :key")
    suspend fun get(key: String): BmlMessageProject?

    @Query("""
        insert into communications.bml_message_projects (key, description, repository_id)
        values (:key, :description, :repositoryId)
        on conflict (key) do update set
            description = excluded.description,
            repository_id = excluded.repository_id,
            modified = now()
        returning *
    """)
    suspend fun upsert(key: String, description: String?, repositoryId: UUID?): BmlMessageProject

    @Query("""
        update communications.bml_message_projects
        set pinned_version = :version, modified = now()
        where key = :key
        returning *
    """)
    suspend fun setPinnedVersion(key: String, version: String?): BmlMessageProject?

    @Query("delete from communications.bml_message_projects where key = :key", returnUpdateCount = true)
    suspend fun delete(key: String): Int
}
