package bosca.artifacts.repository

import bosca.artifacts.model.ArtifactSync
import bosca.artifacts.model.ArtifactSyncDestination
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

/** GHCR destinations and the latest desired digest of each synchronized tag. */
@Repository
interface ArtifactSyncRepository {
    /** Finds a remote target and its current credentials. */
    @Query("SELECT * FROM artifacts.sync_destinations WHERE id = :id")
    suspend fun destination(id: UUID): ArtifactSyncDestination?

    /** Serializes target preparation before checking the current local tag. */
    @Query("SELECT * FROM artifacts.sync_destinations WHERE id = :id FOR UPDATE")
    suspend fun lockDestination(id: UUID): ArtifactSyncDestination?

    /** Lists repository destinations in key order. */
    @Query("SELECT * FROM artifacts.sync_destinations WHERE repository_id = :repositoryId ORDER BY key")
    suspend fun destinations(repositoryId: UUID): List<ArtifactSyncDestination>

    /** Creates a target. */
    @Query(
        """INSERT INTO artifacts.sync_destinations
        (id, repository_id, key, remote_repository, username, token_secret_name, enabled)
        VALUES (:id, :repositoryId, :key, :remoteRepository, :username, :tokenSecretName, :enabled) RETURNING *"""
    )
    suspend fun createDestination(destination: ArtifactSyncDestination): ArtifactSyncDestination

    /** Updates configuration when the version matches. */
    @Query(
        """UPDATE artifacts.sync_destinations SET enabled = :enabled, username = :username,
        token_secret_name = :tokenSecretName, key = :key, remote_repository = :remoteRepository,
        version = version + 1, modified = now()
        WHERE id = :id AND version = :expectedVersion RETURNING *"""
    )
    suspend fun updateDestination(id: UUID, expectedVersion: Long, enabled: Boolean, username: String, tokenSecretName: String, key: String, remoteRepository: String): ArtifactSyncDestination?

    /** Removes sync results and pending copies for a previous remote image path. */
    @Query("DELETE FROM artifacts.syncs WHERE destination_id = :destinationId", returnUpdateCount = true)
    suspend fun clearSyncs(destinationId: UUID): Int

    /** Removes an obsolete pending digest without deleting a completed or newer request. */
    @Query(
        """DELETE FROM artifacts.syncs WHERE destination_id = :destinationId AND tag_name = :tagName
        AND manifest_digest = :digest AND synced IS NULL""", returnUpdateCount = true
    )
    suspend fun discardPending(destinationId: UUID, tagName: String, digest: String): Int

    /** Deletes the matching destination; its sync records are removed by the foreign key cascade. */
    @Query("DELETE FROM artifacts.sync_destinations WHERE id = :id AND version = :expectedVersion", returnUpdateCount = true)
    suspend fun deleteDestination(id: UUID, expectedVersion: Long): Int

    /** Stores the latest desired tag, resetting results only when the digest changes. */
    @Query(
        """INSERT INTO artifacts.syncs (id, destination_id, version_id, tag_name, manifest_digest)
        VALUES (:id, :destinationId, :versionId, :tagName, :manifestDigest)
        ON CONFLICT (destination_id, tag_name) DO UPDATE SET
        version_id = excluded.version_id, manifest_digest = excluded.manifest_digest,
        attempts = CASE WHEN syncs.manifest_digest = excluded.manifest_digest THEN syncs.attempts ELSE 0 END,
        synced = CASE WHEN syncs.manifest_digest = excluded.manifest_digest THEN syncs.synced ELSE NULL END,
        error = CASE WHEN syncs.manifest_digest = excluded.manifest_digest THEN syncs.error ELSE NULL END,
        modified = now() RETURNING *"""
    )
    suspend fun request(sync: ArtifactSync): ArtifactSync

    /** Finds the latest desired image. */
    @Query("SELECT * FROM artifacts.syncs WHERE id = :id")
    suspend fun find(id: UUID): ArtifactSync?

    /** Serializes remote tag writes across pipeline runs and redelivery. */
    @Query("SELECT * FROM artifacts.syncs WHERE id = :id FOR UPDATE")
    suspend fun lock(id: UUID): ArtifactSync?

    /** Lists current results for all of a repository's destinations. */
    @Query(
        """SELECT s.* FROM artifacts.syncs s JOIN artifacts.sync_destinations d ON d.id = s.destination_id
        WHERE d.repository_id = :repositoryId ORDER BY s.created, s.id LIMIT :limit OFFSET :offset"""
    )
    suspend fun syncs(repositoryId: UUID, limit: Int, offset: Long): List<ArtifactSync>

    /** Starts an attempt without modifying a newer desired image. */
    @Query(
        """UPDATE artifacts.syncs SET attempts = attempts + 1, error = NULL, modified = now()
        WHERE id = :id AND manifest_digest = :digest""", returnUpdateCount = true
    )
    suspend fun attempt(id: UUID, digest: String): Int

    /** Records success only for the copied digest. */
    @Query(
        """UPDATE artifacts.syncs SET synced = now(), error = NULL, modified = now()
        WHERE id = :id AND manifest_digest = :digest""", returnUpdateCount = true
    )
    suspend fun synced(id: UUID, digest: String): Int

    /** Records a safe error without overwriting a newer request. */
    @Query(
        """UPDATE artifacts.syncs SET error = :error, modified = now()
        WHERE id = :id AND manifest_digest = :digest""", returnUpdateCount = true
    )
    suspend fun failed(id: UUID, digest: String, error: String): Int

    /** Clears success when an administrator explicitly asks to verify and copy again. */
    @Query("UPDATE artifacts.syncs SET synced = NULL, error = NULL, modified = now() WHERE id = :id RETURNING *")
    suspend fun retry(id: UUID): ArtifactSync?
}
