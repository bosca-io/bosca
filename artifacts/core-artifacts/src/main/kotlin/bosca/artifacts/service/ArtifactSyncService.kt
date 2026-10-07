package bosca.artifacts.service

import bosca.artifacts.model.ArtifactSync
import bosca.artifacts.model.ArtifactSyncDestination
import bosca.artifacts.model.ArtifactSyncDestinationInput
import bosca.artifacts.model.ArtifactSyncTarget
import bosca.serialization.UUID
import bosca.service.Service

/** Copies published Docker images to configured GHCR repositories through pipelines. */
interface ArtifactSyncService : Service {
    /** Creates a destination with an immutable remote image path and a secret reference. */
    suspend fun createDestination(input: ArtifactSyncDestinationInput): ArtifactSyncDestination

    /** Lists destinations for a repository. The API boundary authorizes configuration access. */
    suspend fun destinations(repositoryId: UUID): List<ArtifactSyncDestination>

    /** Changes activation or credentials with optimistic locking. */
    suspend fun updateDestination(id: UUID, expectedVersion: Long, enabled: Boolean, username: String?, tokenSecretName: String?): ArtifactSyncDestination

    /** Prepares one selected pipeline target; obsolete tags, deleted sources and disabled destinations are skipped. */
    suspend fun prepare(target: ArtifactSyncTarget): ArtifactSync?

    /** Lists current desired tags and their sync results using offset pagination. */
    suspend fun syncs(repositoryId: UUID, limit: Int = 100, offset: Long = 0): List<ArtifactSync>

    /** Performs the selected copy from a pipeline action, serializing concurrent copies of the same tag. */
    suspend fun sync(id: UUID)

    /** Clears the result and dispatches the current tag's publication event for pipeline retry. */
    suspend fun retry(id: UUID): ArtifactSync
}
