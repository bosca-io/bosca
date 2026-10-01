package bosca.artifacts.repository

import bosca.artifacts.model.ArtifactNamespace
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

/**
 * Database operations for artifact namespaces in the artifacts schema.
 */
@Repository
interface NamespaceRepository {

    @Query("SELECT * FROM artifacts.namespaces ORDER BY name")
    suspend fun getAll(): List<ArtifactNamespace>

    @Query("SELECT * FROM artifacts.namespaces WHERE id = :id")
    suspend fun findById(id: UUID): ArtifactNamespace?

    @Query("SELECT * FROM artifacts.namespaces WHERE name = :name")
    suspend fun findByName(name: String): ArtifactNamespace?

    @Query("INSERT INTO artifacts.namespaces (name, public) VALUES (:name, :public) RETURNING *")
    suspend fun create(name: String, public: Boolean): ArtifactNamespace

    @Query("UPDATE artifacts.namespaces SET public = :public WHERE id = :id RETURNING *")
    suspend fun updatePublic(id: UUID, public: Boolean): ArtifactNamespace?

    @Query("DELETE FROM artifacts.namespaces WHERE id = :id")
    suspend fun delete(id: UUID)
}
