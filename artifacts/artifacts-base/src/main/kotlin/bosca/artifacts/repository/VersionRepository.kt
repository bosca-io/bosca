package bosca.artifacts.repository

import bosca.artifacts.model.ArtifactVersion
import bosca.artifacts.model.ArtifactVersionBlob
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

/**
 * Database operations for artifact versions and their blob associations.
 */
@Repository
interface VersionRepository {

    @Query("SELECT * FROM artifacts.versions WHERE id = :id")
    suspend fun findById(id: UUID): ArtifactVersion?

    /** Serializes blob manifest edits, publication finalization and version deletion. */
    @Query("SELECT * FROM artifacts.versions WHERE id = :id FOR UPDATE")
    suspend fun lockById(id: UUID): ArtifactVersion?

    /** Fixes the version's file manifest while its row lock is held. */
    @Query("UPDATE artifacts.versions SET finalized = true WHERE id = :id RETURNING *")
    suspend fun finalizeVersion(id: UUID): ArtifactVersion?

    @Query("SELECT * FROM artifacts.versions WHERE repository_id = :repositoryId AND version = :version")
    suspend fun findByRepositoryAndVersion(repositoryId: UUID, version: String): ArtifactVersion?

    /** Any version starting with [prefix] —prefix constraints (`6.0.*`). Newest wins. */
    @Query(
        """
        SELECT * FROM artifacts.versions
        WHERE repository_id = :repositoryId AND starts_with(version, :prefix)
        ORDER BY created DESC LIMIT 1
        """
    )
    suspend fun findByRepositoryAndVersionPrefix(repositoryId: UUID, prefix: String): ArtifactVersion?

    @Query("INSERT INTO artifacts.versions (repository_id, version, metadata) VALUES (:repositoryId, :version, :metadata::jsonb) RETURNING *")
    suspend fun create(repositoryId: UUID, version: String, metadata: String?): ArtifactVersion

    @Query("SELECT * FROM artifacts.versions WHERE repository_id = :repositoryId ORDER BY created DESC")
    suspend fun listByRepository(repositoryId: UUID): List<ArtifactVersion>

    @Query("SELECT * FROM artifacts.versions WHERE repository_id = :repositoryId ORDER BY created DESC LIMIT :limit OFFSET :offset")
    suspend fun listByRepositoryPaged(repositoryId: UUID, limit: Int, offset: Long): List<ArtifactVersion>

    @Query("SELECT COUNT(*) FROM artifacts.versions WHERE repository_id = :repositoryId")
    suspend fun countByRepository(repositoryId: UUID): Long

    @Query("DELETE FROM artifacts.versions WHERE id = :id")
    suspend fun delete(id: UUID)

    // -- Version Blobs --

    @Query("INSERT INTO artifacts.version_blobs (version_id, digest, role, filename, media_type) VALUES (:versionId, :digest, :role, :filename, :mediaType) ON CONFLICT (version_id, digest, role) DO NOTHING RETURNING *")
    suspend fun addVersionBlob(versionId: UUID, digest: String, role: String, filename: String?, mediaType: String?): ArtifactVersionBlob?

    @Query("SELECT * FROM artifacts.version_blobs WHERE version_id = :versionId")
    suspend fun getVersionBlobs(versionId: UUID): List<ArtifactVersionBlob>

    @Query("SELECT * FROM artifacts.version_blobs WHERE version_id = :versionId AND digest = :digest AND role = :role")
    suspend fun findVersionBlob(versionId: UUID, digest: String, role: String): ArtifactVersionBlob?

    @Query("DELETE FROM artifacts.version_blobs WHERE version_id = :versionId")
    suspend fun deleteVersionBlobs(versionId: UUID)

    @Query("DELETE FROM artifacts.version_blobs WHERE version_id = :versionId AND filename = :filename AND role = :role")
    suspend fun deleteVersionBlobsByFilenameAndRole(versionId: UUID, filename: String, role: String)

    @Query("SELECT v.* FROM artifacts.versions v JOIN artifacts.version_blobs vb ON v.id = vb.version_id WHERE v.repository_id = :repositoryId AND vb.digest = :digest AND vb.role = :role")
    suspend fun findVersionsByBlobDigestAndRole(repositoryId: UUID, digest: String, role: String): List<ArtifactVersion>

    @Query("SELECT vb.* FROM artifacts.version_blobs vb JOIN artifacts.versions v ON vb.version_id = v.id WHERE v.repository_id = :repositoryId AND vb.filename = :filename AND vb.role = :role LIMIT 1")
    suspend fun findVersionBlobByRepositoryFilenameAndRole(repositoryId: UUID, filename: String, role: String): ArtifactVersionBlob?
}
