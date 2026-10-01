package bosca.git.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.git.model.QuerySourceRef
import bosca.serialization.UUID

/**
 * Database access layer for analytics-query-to-git-file linkages. Used by the
 * sync flow to find which queries need updating when a push changes files in
 * a repository.
 */
@Repository
interface QuerySourceRefRepository {

    @Query("select * from git.query_source_refs where query_id = :queryId")
    suspend fun findByQueryId(queryId: UUID): QuerySourceRef?

    @Query("select * from git.query_source_refs where repository_id = :repositoryId")
    suspend fun findByRepository(repositoryId: UUID): List<QuerySourceRef>

    @Query("""
        insert into git.query_source_refs (query_id, repository_id, path, ref)
        values (:queryId, :repositoryId, :path, :ref)
        on conflict (query_id) do update set repository_id = :repositoryId, path = :path, ref = :ref
        returning *
    """)
    suspend fun upsert(ref: QuerySourceRef): QuerySourceRef

    @Query("""
        update git.query_source_refs set resolved_commit = :resolvedCommit where query_id = :queryId
    """)
    suspend fun updateResolvedCommit(queryId: UUID, resolvedCommit: String)

    @Query("delete from git.query_source_refs where query_id = :queryId")
    suspend fun delete(queryId: UUID)
}
