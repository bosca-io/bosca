package bosca.artifacts.repository

import bosca.artifacts.model.ArtifactPublication
import bosca.artifacts.model.ArtifactPublicationDestination
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

/** Durable publication manifests, target identities and independent attempt results. */
@Repository
interface ArtifactPublicationRepository {
    /** Returns the configured destination and its secret reference. */
    @Query("SELECT * FROM artifacts.publication_destinations WHERE id = :id")
    suspend fun findDestination(id: UUID): ArtifactPublicationDestination?

    /** Lists an artifact repository's destinations in key order. */
    @Query("SELECT * FROM artifacts.publication_destinations WHERE repository_id = :repositoryId ORDER BY key")
    suspend fun destinations(repositoryId: UUID): List<ArtifactPublicationDestination>

    /** Stores a new target with immutable repository identity and tag prefix. */
    @Query("""INSERT INTO artifacts.publication_destinations
        (id, repository_id, key, github_repository_id, owner, github_repository, tag_prefix, enabled, token_secret_name)
        VALUES (:id, :repositoryId, :key, :githubRepositoryId,
        :owner, :githubRepository, :tagPrefix, :enabled,
        :tokenSecretName) RETURNING *""")
    suspend fun createDestination(destination: ArtifactPublicationDestination): ArtifactPublicationDestination

    /** Updates activation and the secret reference only when the destination version matches. */
    @Query("""UPDATE artifacts.publication_destinations SET enabled = :enabled, token_secret_name = :tokenSecretName,
        version = version + 1, modified = now()
        WHERE id = :id AND version = :expectedVersion RETURNING *""")
    suspend fun updateDestination(id: UUID, expectedVersion: Long, enabled: Boolean, tokenSecretName: String): ArtifactPublicationDestination?

    /** Finds one durable publication by its identity. */
    @Query("SELECT * FROM artifacts.publications WHERE id = :id")
    suspend fun find(id: UUID): ArtifactPublication?

    /** Locks publication state until the active transaction completes. */
    @Query("SELECT * FROM artifacts.publications WHERE id = :id FOR UPDATE")
    suspend fun lock(id: UUID): ArtifactPublication?

    /** Finds the original publication for a destination and version. */
    @Query("SELECT * FROM artifacts.publications WHERE destination_id = :destinationId AND version_id = :versionId")
    suspend fun find(destinationId: UUID, versionId: UUID): ArtifactPublication?

    /** Lists a version's publication records using offset pagination. */
    @Query("SELECT * FROM artifacts.publications WHERE version_id = :versionId ORDER BY created, id LIMIT :limit OFFSET :offset")
    suspend fun publications(versionId: UUID, limit: Int, offset: Long): List<ArtifactPublication>

    /** Stores a fixed manifest; destination/version and destination/tag pairs are unique. */
    @Query("""INSERT INTO artifacts.publications (id, destination_id, version_id, tag_name, commit_sha, prerelease, files)
        VALUES (:id, :destinationId, :versionId, :tagName,
        :commitSha, :prerelease, :files::jsonb) RETURNING *""")
    suspend fun create(publication: ArtifactPublication): ArtifactPublication

    /** Starts an attempt and clears its previous error. */
    @Query("""UPDATE artifacts.publications SET attempts = attempts + 1, error = null, modified = now()
        WHERE id = :id RETURNING *""")
    suspend fun attempt(id: UUID): ArtifactPublication?

    /** Records provider success while retaining the first publication timestamp. */
    @Query("""UPDATE artifacts.publications SET release_id = :releaseId, published = coalesce(published, now()),
        modified = now() WHERE id = :id RETURNING *""")
    suspend fun published(id: UUID, releaseId: Long): ArtifactPublication?

    /** Records successful verification independently of publication. */
    @Query("UPDATE artifacts.publications SET verified = now(), error = null, modified = now() WHERE id = :id RETURNING *")
    suspend fun verified(id: UUID): ArtifactPublication?

    /** Records an unverified failure, optionally accounting for a rolled-back attempt. */
    @Query("""UPDATE artifacts.publications SET error = :error, attempts = attempts + CASE WHEN :incrementAttempt THEN 1 ELSE 0 END,
        modified = now() WHERE id = :id AND verified IS NULL""", returnUpdateCount = true)
    suspend fun failed(id: UUID, error: String, incrementAttempt: Boolean): Int
}
