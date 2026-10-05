package bosca.git.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.git.dfs.DfsPack
import bosca.git.dfs.DfsPackExtension
import bosca.serialization.UUID

/**
 * Database access layer for DFS packfile metadata in `git.dfs_packs` and
 * `git.dfs_pack_extensions`. JGit's [BoscaDfsObjDatabase] uses these rows
 * to discover and open packs stored in ObjectStorage.
 */
@Repository
interface DfsPackRepository {

    @Query("select * from git.dfs_packs where repository_id = :repositoryId and committed = true and deleted_at is null order by created")
    suspend fun findCommitted(repositoryId: UUID): List<DfsPack>

    @Query("select * from git.dfs_packs where id = :id")
    suspend fun findById(id: UUID): DfsPack?

    /**
     * Every pack for a repository regardless of committed/soft-deleted state.
     * Used when permanently purging a repository so no object-storage file is
     * left behind, including packs still within their reap grace window.
     */
    @Query("select * from git.dfs_packs where repository_id = :repositoryId")
    suspend fun findAll(repositoryId: UUID): List<DfsPack>

    @Query("""
        insert into git.dfs_packs (repository_id, pack_name, pack_source, file_size, object_count, delta_count, min_update_idx, max_update_idx)
        values (:repositoryId, :packName, :packSource, :fileSize, :objectCount, :deltaCount, :minUpdateIdx, :maxUpdateIdx)
        returning *
    """)
    suspend fun create(pack: DfsPack): DfsPack

    @Query("""
        update git.dfs_packs set committed = true, file_size = :fileSize, object_count = :objectCount,
            delta_count = :deltaCount, min_update_idx = :minUpdateIdx, max_update_idx = :maxUpdateIdx
        where id = :id returning *
    """)
    suspend fun commit(pack: DfsPack): DfsPack?

    @Query("update git.dfs_packs set gc_retained = true where id = :id")
    suspend fun markGcRetained(id: UUID)

    /**
     * Soft-deletes a pack: marks it replaced so it drops out of committed-pack
     * lookups immediately, while its object-storage files linger until the pack
     * reaper removes them after the grace window. Called inside the same
     * transaction that commits the replacing pack (see commitPacks).
     */
    @Query("update git.dfs_packs set deleted_at = now() where id = :id")
    suspend fun markDeleted(id: UUID)

    /**
     * Soft-deleted packs whose grace window has fully elapsed, oldest first.
     * The cutoff is computed with the database clock ([now]) so it is immune to
     * app-server clock skew. Bounded by [limit] so a single reaper run can't
     * fan out unboundedly.
     */
    @Query("""
        select * from git.dfs_packs
        where deleted_at is not null and deleted_at < now() - make_interval(secs => :graceSeconds)
        order by deleted_at
        limit :limit
    """)
    suspend fun findReapable(graceSeconds: Long, limit: Int): List<DfsPack>

    @Query("delete from git.dfs_packs where id = :id")
    suspend fun delete(id: UUID)

    @Query("delete from git.dfs_packs where repository_id = :repositoryId and committed = false")
    suspend fun deleteUncommitted(repositoryId: UUID)

    @Query("""
        select coalesce(sum(e.file_size), 0)
        from git.dfs_pack_extensions e
        join git.dfs_packs p on e.pack_id = p.id
        where p.repository_id = :repositoryId and p.committed = true and p.deleted_at is null
    """)
    suspend fun sumPackSizeBytes(repositoryId: UUID): Long

    @Query("select * from git.dfs_pack_extensions where pack_id = :packId order by extension")
    suspend fun findExtensions(packId: UUID): List<DfsPackExtension>

    @Query("""
        select e.* from git.dfs_pack_extensions e
        join git.dfs_packs p on e.pack_id = p.id
        where p.repository_id = :repositoryId and p.committed = true and p.deleted_at is null
        order by e.pack_id, e.extension
    """)
    suspend fun findExtensionsByRepository(repositoryId: UUID): List<DfsPackExtension>

    @Query("""
        insert into git.dfs_pack_extensions (pack_id, extension, file_size, storage_path)
        values (:packId, :extension, :fileSize, :storagePath)
        on conflict (pack_id, extension) do update
        set file_size = excluded.file_size, storage_path = excluded.storage_path
        returning *
    """)
    suspend fun upsertExtension(ext: DfsPackExtension): DfsPackExtension
}
