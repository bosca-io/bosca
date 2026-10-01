package bosca.artifacts.repository

import bosca.artifacts.model.ArtifactRepository
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

/**
 * Database operations for artifact repositories in the artifacts schema.
 * Named `ArtifactRepoRepository` to avoid collision with the model class [ArtifactRepository].
 */
@Repository
interface ArtifactRepoRepository {

    @Query("SELECT * FROM artifacts.repositories WHERE id = :id")
    suspend fun findById(id: UUID): ArtifactRepository?

    @Query("SELECT * FROM artifacts.repositories WHERE namespace_id = :namespaceId AND name = :name AND type = :type")
    suspend fun findByCoordinates(namespaceId: UUID, name: String, type: String): ArtifactRepository?

    @Query("SELECT * FROM artifacts.repositories WHERE namespace_id = :namespaceId ORDER BY name")
    suspend fun listByNamespace(namespaceId: UUID): List<ArtifactRepository>

    @Query("SELECT * FROM artifacts.repositories WHERE namespace_id = :namespaceId AND type = :type ORDER BY name")
    suspend fun listByNamespaceAndType(namespaceId: UUID, type: String): List<ArtifactRepository>

    @Query("SELECT * FROM artifacts.repositories WHERE namespace_id = :namespaceId ORDER BY name LIMIT :limit OFFSET :offset")
    suspend fun listByNamespacePaged(namespaceId: UUID, limit: Int, offset: Long): List<ArtifactRepository>

    @Query("SELECT * FROM artifacts.repositories WHERE namespace_id = :namespaceId AND type = :type ORDER BY name LIMIT :limit OFFSET :offset")
    suspend fun listByNamespaceAndTypePaged(namespaceId: UUID, type: String, limit: Int, offset: Long): List<ArtifactRepository>

    @Query("SELECT COUNT(*) FROM artifacts.repositories WHERE namespace_id = :namespaceId")
    suspend fun countByNamespace(namespaceId: UUID): Long

    @Query("SELECT COUNT(*) FROM artifacts.repositories WHERE namespace_id = :namespaceId AND type = :type")
    suspend fun countByNamespaceAndType(namespaceId: UUID, type: String): Long

    @Query("SELECT * FROM artifacts.repositories WHERE type = :type ORDER BY name LIMIT :limit OFFSET :offset")
    suspend fun listByType(type: String, limit: Int, offset: Long): List<ArtifactRepository>

    @Query("INSERT INTO artifacts.repositories (namespace_id, name, type) VALUES (:namespaceId, :name, :type) RETURNING *")
    suspend fun create(namespaceId: UUID, name: String, type: String): ArtifactRepository

    @Query("DELETE FROM artifacts.repositories WHERE id = :id")
    suspend fun delete(id: UUID)

    @Query("DELETE FROM artifacts.repositories WHERE namespace_id = :namespaceId")
    suspend fun deleteByNamespace(namespaceId: UUID)
}
