package bosca.artifacts.repository

import bosca.artifacts.model.ArtifactTag
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

/**
 * Database operations for Docker image tags in the artifacts schema.
 */
@Repository
interface TagRepository {

    @Query("SELECT * FROM artifacts.tags WHERE repository_id = :repositoryId AND name = :name")
    suspend fun findByRepositoryAndName(repositoryId: UUID, name: String): ArtifactTag?

    /** Any tag starting with [prefix] —prefix constraints (`6.0.*`). Newest wins. */
    @Query(
        """
        SELECT * FROM artifacts.tags
        WHERE repository_id = :repositoryId AND starts_with(name, :prefix)
        ORDER BY modified DESC LIMIT 1
        """
    )
    suspend fun findByRepositoryAndNamePrefix(repositoryId: UUID, prefix: String): ArtifactTag?

    @Query("INSERT INTO artifacts.tags (repository_id, name, manifest_digest) VALUES (:repositoryId, :name, :manifestDigest) ON CONFLICT (repository_id, name) DO UPDATE SET manifest_digest = :manifestDigest, modified = now() RETURNING *")
    suspend fun upsert(repositoryId: UUID, name: String, manifestDigest: String): ArtifactTag

    @Query("SELECT * FROM artifacts.tags WHERE repository_id = :repositoryId AND name > :after ORDER BY name LIMIT :limit")
    suspend fun listByRepositoryAfter(repositoryId: UUID, after: String, limit: Int): List<ArtifactTag>

    @Query("SELECT * FROM artifacts.tags WHERE repository_id = :repositoryId ORDER BY name LIMIT :limit")
    suspend fun listByRepository(repositoryId: UUID, limit: Int): List<ArtifactTag>

    @Query("SELECT * FROM artifacts.tags WHERE repository_id = :repositoryId ORDER BY name LIMIT :limit OFFSET :offset")
    suspend fun listByRepositoryPaged(repositoryId: UUID, limit: Int, offset: Long): List<ArtifactTag>

    @Query("SELECT COUNT(*) FROM artifacts.tags WHERE repository_id = :repositoryId")
    suspend fun countByRepository(repositoryId: UUID): Long

    @Query("DELETE FROM artifacts.tags WHERE repository_id = :repositoryId AND name = :name")
    suspend fun delete(repositoryId: UUID, name: String)

    @Query("DELETE FROM artifacts.tags WHERE repository_id = :repositoryId AND manifest_digest = :manifestDigest")
    suspend fun deleteByManifestDigest(repositoryId: UUID, manifestDigest: String)
}
