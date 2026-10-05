package bosca.workops.service

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.VersionInUseException
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.version.CreateVersionInput
import bosca.workops.model.version.Version
import bosca.workops.repository.VersionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VersionServiceTest {

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
    }

    @AfterTest
    fun teardown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    @Test
    fun `lookups delegate while an empty batch avoids the repository`() = runTest {
        val repository = mockk<VersionRepository>()
        val service = VersionServiceImpl(repository)
        val version = sampleVersion()
        coEvery { repository.getById(version.id) } returns version
        coEvery { repository.getByIds(listOf(version.id)) } returns listOf(version)
        coEvery { repository.listByProject(version.projectId) } returns listOf(version)

        assertEquals(version, service.getById(version.id))
        assertTrue(service.getByIds(emptyList()).isEmpty())
        assertEquals(listOf(version), service.getByIds(listOf(version.id)))
        assertEquals(listOf(version), service.listByProject(version.projectId))
        coVerify(exactly = 0) { repository.getByIds(emptyList()) }
    }

    @Test
    fun `create rejects a blank name before reading the sequence`() = runTest {
        val repository = mockk<VersionRepository>()

        val failure = assertFailsWith<WorkOpsValidationException> {
            VersionServiceImpl(repository).create(CreateVersionInput(UUID.random(), "   "))
        }

        assertEquals("name", failure.field)
        coVerify(exactly = 0) { repository.maxSequenceNumber(any()) }
    }

    @Test
    fun `release preserves an existing release date`() = runTest {
        val repository = mockk<VersionRepository>()
        val releaseDate = OffsetDateTime.parse("2026-07-21T10:00:00Z")
        val existing = sampleVersion(releaseDate = releaseDate)
        val released = existing.copy(released = true, version = 4)
        coEvery { repository.getById(existing.id) } returns existing
        coEvery {
            repository.update(
                existing.id,
                existing.name,
                existing.description,
                existing.startDate,
                releaseDate,
                true,
                existing.archived,
                3,
            )
        } returns released

        assertEquals(released, VersionServiceImpl(repository).release(existing.id, 3))
    }

    @Test
    fun `release assigns a date when one is missing`() = runTest {
        val repository = mockk<VersionRepository>()
        val existing = sampleVersion()
        val released = existing.copy(released = true, version = 2)
        coEvery { repository.getById(existing.id) } returns existing
        coEvery {
            repository.update(
                existing.id,
                existing.name,
                existing.description,
                existing.startDate,
                any(),
                true,
                existing.archived,
                1,
            )
        } coAnswers {
            val assignedReleaseDate = arg<OffsetDateTime?>(4) ?: error("missing release date")
            released.copy(releaseDate = assignedReleaseDate)
        }

        assertTrue(VersionServiceImpl(repository).release(existing.id, 1).releaseDate != null)
    }

    @Test
    fun `release reports missing and stale versions with typed failures`() = runTest {
        val repository = mockk<VersionRepository>()
        val missingId = UUID.random()
        val stale = sampleVersion()
        coEvery { repository.getById(missingId) } returns null
        coEvery { repository.getById(stale.id) } returns stale
        coEvery {
            repository.update(
                stale.id,
                stale.name,
                stale.description,
                stale.startDate,
                any(),
                true,
                stale.archived,
                7,
            )
        } returns null
        val service = VersionServiceImpl(repository)

        val missing = assertFailsWith<WorkOpsNotFoundException> {
            service.release(missingId, 1)
        }
        val optimisticLock = assertFailsWith<OptimisticLockFailedException> {
            service.release(stale.id, 7)
        }

        assertEquals("Version", missing.type)
        assertEquals(missingId.toString(), missing.handle)
        assertEquals("Version", optimisticLock.type)
        assertEquals(stale.id, optimisticLock.id)
    }

    @Test
    fun `delete rejects referenced versions and deletes unreferenced versions`() = runTest {
        val repository = mockk<VersionRepository>()
        val referencedId = UUID.random()
        val deletableId = UUID.random()
        coEvery { repository.isReferencedByTasks(referencedId) } returns true
        coEvery { repository.isReferencedByTasks(deletableId) } returns false
        coEvery { repository.deleteById(deletableId) } returns Unit
        val service = VersionServiceImpl(repository)

        val failure = assertFailsWith<VersionInUseException> {
            service.delete(referencedId)
        }
        service.delete(deletableId)

        assertEquals(referencedId, failure.versionId)
        coVerify(exactly = 0) { repository.deleteById(referencedId) }
        coVerify(exactly = 1) { repository.deleteById(deletableId) }
    }

    private fun sampleVersion(releaseDate: OffsetDateTime? = null) = Version(
        id = UUID.random(),
        projectId = UUID.random(),
        name = "1.2.3",
        description = "Release",
        releaseDate = releaseDate,
        archived = false,
        sequenceNumber = 3,
        version = 3,
    )
}
