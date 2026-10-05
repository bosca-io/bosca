package bosca.git.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.git.model.ScriptSourceRef
import bosca.serialization.UUID

/**
 * Database access layer for script-to-git-file linkages. Used by the sync
 * flow to find which scripts need updating when a push changes files in a
 * repository.
 */
@Repository
interface ScriptSourceRefRepository {

    @Query("select * from git.script_source_refs where script_id = :scriptId")
    suspend fun findByScriptId(scriptId: UUID): ScriptSourceRef?

    @Query("select * from git.script_source_refs where repository_id = :repositoryId")
    suspend fun findByRepository(repositoryId: UUID): List<ScriptSourceRef>

    @Query("""
        insert into git.script_source_refs (script_id, repository_id, path, ref)
        values (:scriptId, :repositoryId, :path, :ref)
        on conflict (script_id) do update set repository_id = :repositoryId, path = :path, ref = :ref
        returning *
    """)
    suspend fun upsert(ref: ScriptSourceRef): ScriptSourceRef

    @Query("""
        update git.script_source_refs set resolved_commit = :resolvedCommit where script_id = :scriptId
    """)
    suspend fun updateResolvedCommit(scriptId: UUID, resolvedCommit: String)

    @Query("delete from git.script_source_refs where script_id = :scriptId")
    suspend fun delete(scriptId: UUID)
}
