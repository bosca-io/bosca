package bosca.artifacts.service

import bosca.artifacts.model.ArtifactSync
import bosca.artifacts.model.ArtifactSyncDestination
import bosca.artifacts.model.ArtifactSyncDestinationInput
import bosca.artifacts.model.ArtifactSyncTarget
import bosca.serialization.UUID
import bosca.security.service.AuthenticationContext
import bosca.service.Service

/** Copies published Docker images to configured GHCR repositories through pipelines. */
interface ArtifactSyncService : Service {
    /** Creates a destination with a remote image path and a secret reference. */
    suspend fun createDestination(input: ArtifactSyncDestinationInput): ArtifactSyncDestination

    /** Lists destinations for a repository. The API boundary authorizes configuration access. */
    suspend fun destinations(repositoryId: UUID): List<ArtifactSyncDestination>

    /** Updates configuration with optimistic locking; changing the image path removes the previous path's sync records. */
    suspend fun updateDestination(id: UUID, expectedVersion: Long, enabled: Boolean, username: String?, tokenSecretName: String?, key: String?, remoteRepository: String?): ArtifactSyncDestination

    /** Deletes a destination and its sync records with optimistic locking, preserving source and remote images. */
    suspend fun deleteDestination(id: UUID, expectedVersion: Long)

    /**
     * Prepares a target for its selected image path, discarding obsolete pending sync records.
     * Changed paths, deleted sources and disabled destinations are skipped.
     */
    suspend fun prepare(target: ArtifactSyncTarget): ArtifactSync?

    /** Lists current desired tags and their sync results using offset pagination. */
    suspend fun syncs(repositoryId: UUID, limit: Int = 100, offset: Long = 0): List<ArtifactSync>

    /** Performs the selected copy from a pipeline action, serializing concurrent copies of the same tag. */
    suspend fun sync(id: UUID)

    /** Clears the result and dispatches the current tag's publication event for pipeline retry. */
    suspend fun retry(id: UUID): ArtifactSync

    /** Queues a selected current tag for an enabled destination through a durable pipeline under the caller's identity. */
    suspend fun push(authentication: AuthenticationContext, destinationId: UUID, tagName: String): UUID

    companion object {
        /** Default editable pipeline used to push one selected Docker tag to a GHCR destination. */
        const val PUSH_PIPELINE_NAME = "Sync Docker Image to GHCR"
    }
}
