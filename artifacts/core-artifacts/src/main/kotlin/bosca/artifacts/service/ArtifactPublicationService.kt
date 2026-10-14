package bosca.artifacts.service

import bosca.artifacts.model.ArtifactPublication
import bosca.artifacts.model.ArtifactPublicationDestination
import bosca.artifacts.model.ArtifactPublicationDestinationInput
import bosca.serialization.UUID
import bosca.security.service.AuthenticationContext
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

    /**
     * Repeats verification when [reverify] is true. The attempt number is committed before provider work;
     * failures retain prior success timestamps, and only the current attempt can record its result.
     * Verification is tied to its attempt number. An unfinished attempt remains eligible for retry
     * even when an earlier attempt was verified, regardless of transaction timestamp ordering.
     */
    suspend fun publish(id: UUID, reverify: Boolean)

    /**
     * Queues a completed raw version for one enabled destination through a durable pipeline
     * under the caller's identity. [commitSha] is the exact build commit and must match the
     * destination's existing GitHub tag. The publication action fixes the stored file manifest;
     * repeated requests retain that manifest and publication result while verifying the release again.
     */
    suspend fun publish(authentication: AuthenticationContext, destinationId: UUID, versionId: UUID, commitSha: String): UUID

    companion object {
        /** Default editable pipeline for publishing one raw version to GitHub. */
        const val PUBLICATION_PIPELINE_NAME = "Publish Raw Artifact to GitHub"
    }
}
