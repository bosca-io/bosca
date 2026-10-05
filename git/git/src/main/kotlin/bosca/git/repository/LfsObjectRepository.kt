package bosca.git.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.git.model.LfsObject
import bosca.serialization.UUID

/**
 * Database access layer for Git LFS objects stored in external object storage.
 * Tracks which large files belong to which repository for quota enforcement
 * and garbage collection.
 */
@Repository
interface LfsObjectRepository {

    @Query("select * from git.lfs_objects where repository_id = :repositoryId and oid = :oid")
    suspend fun findByOid(repositoryId: UUID, oid: String): LfsObject?

    @Query("select * from git.lfs_objects where repository_id = :repositoryId")
    suspend fun findByRepository(repositoryId: UUID): List<LfsObject>

    @Query("""
        insert into git.lfs_objects (repository_id, oid, size, storage_path)
        values (:repositoryId, :oid, :size, :storagePath)
        on conflict do nothing
        returning *
    """)
    suspend fun create(obj: LfsObject): LfsObject?

    @Query("delete from git.lfs_objects where repository_id = :repositoryId and oid = :oid")
    suspend fun delete(repositoryId: UUID, oid: String)

    @Query("select coalesce(sum(size), 0) from git.lfs_objects where repository_id = :repositoryId")
    suspend fun getTotalSize(repositoryId: UUID): Long?
}
