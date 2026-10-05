package bosca.workops.service

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.release.Release
import bosca.workops.model.release.ReleaseProjectVersion
import bosca.workops.repository.ReleaseRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReleaseServiceTest {

    @Test
    fun `release service delegates lifecycle and reports optimistic failures`() = runTest {
        val repository = mockk<ReleaseRepository>()
        val programId = UUID.random()
        val projectId = UUID.random()
        val versionId = UUID.random()
        val ownerProfileId = UUID.random()
        val releaseDate = OffsetDateTime.now()
        val release = Release(UUID.random(), programId, "July", "monthly", releaseDate, ownerProfileId = ownerProfileId)
        val component = ReleaseProjectVersion(release.id, projectId, versionId)
        val released = release.copy(releasedAt = OffsetDateTime.now(), version = 1)
        val updated = release.copy(name = "July Stable", description = "stable", version = 1)
        val missingId = UUID.random()
        coEvery { repository.listForProgram(programId) } returns listOf(release)
        coEvery { repository.getById(release.id) } returns release
        coEvery { repository.add(programId, "July", "monthly", releaseDate, ownerProfileId) } returns release
        coEvery { repository.bundle(release.id, projectId, versionId) } returns component
        coEvery { repository.unbundle(release.id, versionId) } just Runs
        coEvery { repository.listVersions(release.id) } returns listOf(component)
        coEvery { repository.resetDeployments(release.id) } returns 2
        coEvery { repository.markReleased(release.id, 0) } returns released
        coEvery { repository.markReleased(missingId, 0) } returns null
        coEvery { repository.update(release.id, "July Stable", "stable", releaseDate, 0) } returns updated
        coEvery { repository.update(missingId, "July Stable", "stable", releaseDate, 0) } returns null
        coEvery { repository.softDelete(release.id) } just Runs
        val service = ReleaseServiceImpl(repository)

        assertEquals(listOf(release), service.list(programId))
        assertEquals(release, service.getById(release.id))
        assertEquals(release, service.create(programId, "July", "monthly", releaseDate, ownerProfileId))
        assertEquals(component, service.bundle(release.id, projectId, versionId))
        service.unbundle(release.id, versionId)
        assertEquals(listOf(component), service.listVersions(release.id))
        assertEquals(2, service.resetDeployments(release.id))
        assertEquals(released, service.release(release.id, 0))
        assertFailsWith<WorkOpsValidationException> { service.release(missingId, 0) }
        assertEquals(updated, service.update(release.id, "July Stable", "stable", releaseDate, 0))
        assertFailsWith<WorkOpsValidationException> {
            service.update(missingId, "July Stable", "stable", releaseDate, 0)
        }
        service.delete(release.id)

        coVerify(exactly = 1) { repository.unbundle(release.id, versionId) }
        coVerify(exactly = 1) { repository.softDelete(release.id) }
    }
}
