package bosca.workops.service

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.release.Release
import bosca.workops.model.release.ReleaseProjectVersion

interface ReleaseService : Service {
    suspend fun list(programId: UUID): List<Release>
    suspend fun getById(id: UUID): Release?
    suspend fun create(
        programId: UUID, name: String, description: String?,
        releaseDate: OffsetDateTime?, ownerProfileId: UUID?,
    ): Release
    suspend fun bundle(releaseId: UUID, projectId: UUID, versionId: UUID): ReleaseProjectVersion?
    suspend fun unbundle(releaseId: UUID, versionId: UUID)
    suspend fun listVersions(releaseId: UUID): List<ReleaseProjectVersion>

    /** Resets every bundled project back to PENDING — a release-attempt rollback's bookkeeping. */
    suspend fun resetDeployments(releaseId: UUID): Int
    suspend fun release(releaseId: UUID, expectedVersion: Long): Release

    /** Update a release's editable details (name, description, planned date), optimistic-locked. */
    suspend fun update(
        id: UUID,
        name: String,
        description: String?,
        releaseDate: OffsetDateTime?,
        expectedVersion: Long,
    ): Release

    /** Soft-delete a release (idempotent) — hidden from reads, its row + bundle retained. */
    suspend fun delete(id: UUID)
}
