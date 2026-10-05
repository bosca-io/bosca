package bosca.git.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.git.model.DfsRef
import bosca.serialization.UUID

/**
 * Database access layer for git refs stored in the `git.dfs_refs` table.
 * Provides atomic compare-and-swap for concurrent push safety and bulk
 * operations for fork initialization.
 */
@Repository
interface DfsRefRepository {

    @Query("select * from git.dfs_refs where repository_id = :repositoryId order by name")
    suspend fun findAll(repositoryId: UUID): List<DfsRef>

    @Query("select * from git.dfs_refs where repository_id = :repositoryId and name = :name")
    suspend fun findByName(repositoryId: UUID, name: String): DfsRef?

    @Query("""
        insert into git.dfs_refs (repository_id, name, object_id, peeled_id, symbolic_target)
        values (:repositoryId, :name, :objectId, :peeledId, :symbolicTarget)
        on conflict (repository_id, name) do update
        set object_id = excluded.object_id, peeled_id = excluded.peeled_id,
            symbolic_target = excluded.symbolic_target, updated = now()
        returning *
    """)
    suspend fun upsert(ref: DfsRef): DfsRef

    @Query("delete from git.dfs_refs where repository_id = :repositoryId and name = :name")
    suspend fun delete(repositoryId: UUID, name: String)

    @Query("delete from git.dfs_refs where repository_id = :repositoryId")
    suspend fun deleteAll(repositoryId: UUID)

    @Query("""
        update git.dfs_refs set object_id = :newObjectId, updated = now()
        where repository_id = :repositoryId and name = :name and object_id = :expectedObjectId
        returning *
    """)
    suspend fun compareAndSwap(repositoryId: UUID, name: String, expectedObjectId: String, newObjectId: String): DfsRef?
}
