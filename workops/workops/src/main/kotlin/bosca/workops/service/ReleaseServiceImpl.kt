package bosca.workops.service

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.release.Release
import bosca.workops.repository.ReleaseRepository

@ServiceImplementation
class ReleaseServiceImpl(
    private val repository: ReleaseRepository,
) : ReleaseService {
    override suspend fun list(programId: UUID) = repository.listForProgram(programId)
    override suspend fun getById(id: UUID) = repository.getById(id)
    override suspend fun create(
        programId: UUID, name: String, description: String?,
        releaseDate: OffsetDateTime?, ownerProfileId: UUID?,
    ) = repository.add(programId, name, description, releaseDate, ownerProfileId)
    override suspend fun bundle(releaseId: UUID, projectId: UUID, versionId: UUID) =
        repository.bundle(releaseId, projectId, versionId)
    override suspend fun unbundle(releaseId: UUID, versionId: UUID) =
        repository.unbundle(releaseId, versionId)
    override suspend fun listVersions(releaseId: UUID) = repository.listVersions(releaseId)
    override suspend fun resetDeployments(releaseId: UUID) = repository.resetDeployments(releaseId)
    override suspend fun release(releaseId: UUID, expectedVersion: Long): Release =
        // Readiness is the release's pipeline run reaching OK, read from the run — not a gate table.
        // A run-status guard here is future work, wired when release↔pipeline lands.
        repository.markReleased(releaseId, expectedVersion)
            ?: throw WorkOpsValidationException("release", "already released or version mismatch")

    override suspend fun update(
        id: UUID, name: String, description: String?,
        releaseDate: OffsetDateTime?, expectedVersion: Long,
    ): Release =
        repository.update(id, name, description, releaseDate, expectedVersion)
            ?: throw WorkOpsValidationException("release", "not found or version mismatch")

    override suspend fun delete(id: UUID) = repository.softDelete(id)
}
