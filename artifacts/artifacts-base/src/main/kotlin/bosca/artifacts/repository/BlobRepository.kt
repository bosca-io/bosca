package bosca.artifacts.repository

import bosca.artifacts.model.ArtifactBlob
import bosca.db.annotation.Query
import bosca.db.annotation.Repository

/**
 * Database operations for content-addressable blob metadata in the artifacts schema.
 */
@Repository
interface BlobRepository {

    @Query("SELECT * FROM artifacts.blobs WHERE digest = :digest")
    suspend fun findByDigest(digest: String): ArtifactBlob?

    @Query("INSERT INTO artifacts.blobs (digest, size, storage_path) VALUES (:digest, :size, :storagePath) ON CONFLICT (digest) DO NOTHING RETURNING *")
    suspend fun insert(digest: String, size: Long, storagePath: String? = null): ArtifactBlob?

    @Query("SELECT * FROM artifacts.blobs WHERE digest = :digest")
    suspend fun getByDigest(digest: String): ArtifactBlob

    @Query("UPDATE artifacts.blobs SET ref_count = ref_count + 1 WHERE digest = :digest RETURNING *")
    suspend fun incrementRefCount(digest: String): ArtifactBlob?

    @Query("UPDATE artifacts.blobs SET ref_count = ref_count - 1 WHERE digest = :digest RETURNING *")
    suspend fun decrementRefCount(digest: String): ArtifactBlob?

    @Query("DELETE FROM artifacts.blobs WHERE digest = :digest")
    suspend fun delete(digest: String)

    @Query("DELETE FROM artifacts.blobs WHERE digest = :digest AND ref_count <= 0 RETURNING *")
    suspend fun deleteIfUnreferenced(digest: String): ArtifactBlob?

    @Query("SELECT * FROM artifacts.blobs WHERE ref_count <= 0")
    suspend fun findUnreferenced(): List<ArtifactBlob>
}
