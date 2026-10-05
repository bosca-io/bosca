package bosca.artifacts.service

import bosca.artifacts.model.ArtifactPublication
import bosca.artifacts.model.ArtifactPublicationDestination
import bosca.artifacts.model.ArtifactPublicationDestinationInput
import bosca.serialization.UUID
import bosca.service.Service

/** Publishes completed raw artifact versions to configured GitHub releases using their stored bytes. */
interface ArtifactPublicationService : Service {
    /** Creates a destination referencing a configured Pipeline secret. The GitHub target and tag prefix are immutable. */
    suspend fun createDestination(input: ArtifactPublicationDestinationInput): ArtifactPublicationDestination

    /** Lists destinations for an artifact repository; callers must authorize access to configuration metadata. */
    suspend fun destinations(repositoryId: UUID): List<ArtifactPublicationDestination>

    /** Enables/disables a destination or changes its secret reference, guarded by [expectedVersion]. */
    suspend fun updateDestination(id: UUID, expectedVersion: Long, enabled: Boolean, tokenSecretName: String? = null): ArtifactPublicationDestination

    /**
     * Finalizes a completed version and records its publication to one enabled destination.
     * The caller publishes the returned identity; pipeline execution owns retries.
     * Repetition keeps the original manifest, commit and prerelease choice; incompatible reuse fails.
     * [commitSha] must match each existing GitHub release tag. No Git tag is created by publication.
     */
    suspend fun prepare(destinationId: UUID, versionId: UUID, commitSha: String, prerelease: Boolean = false): ArtifactPublication

    /** Lists a version's publication records using ordinary offset pagination. */
    suspend fun publications(versionId: UUID, limit: Int = 100, offset: Long = 0): List<ArtifactPublication>

    /** Performs one publication/verification attempt using the fixed input. Deleted local versions cancel their work. */
    suspend fun publish(id: UUID)
}
