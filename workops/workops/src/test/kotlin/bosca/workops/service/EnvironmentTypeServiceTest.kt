package bosca.workops.service

import bosca.serialization.UUID
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.environment.EnvironmentType
import bosca.workops.repository.EnvironmentTypeRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EnvironmentTypeServiceTest {

    @Test
    fun `blank names are rejected before create or update reaches the repository`() = runTest {
        val repository = mockk<EnvironmentTypeRepository>()
        val service = EnvironmentTypeServiceImpl(repository)

        assertFailsWith<IllegalArgumentException> {
            service.create("   ", null, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            service.update(UUID.random(), "   ", null, 0, 1)
        }
        coVerify(exactly = 0) { repository.add(any(), any(), any()) }
        coVerify(exactly = 0) { repository.update(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `update trims a name already owned by the same type`() = runTest {
        val repository = mockk<EnvironmentTypeRepository>()
        val id = UUID.random()
        val existing = EnvironmentType(id = id, name = "qa", version = 3)
        val updated = existing.copy(description = "Verification", displayOrder = 7, version = 4)
        coEvery { repository.getByName("qa") } returns existing
        coEvery { repository.update(id, "qa", "Verification", 7, 3) } returns updated

        assertEquals(
            updated,
            EnvironmentTypeServiceImpl(repository).update(id, "  qa  ", "Verification", 7, 3),
        )
        coVerify(exactly = 1) { repository.update(id, "qa", "Verification", 7, 3) }
    }

    @Test
    fun `update rejects a name owned by another type`() = runTest {
        val repository = mockk<EnvironmentTypeRepository>()
        val duplicate = EnvironmentType(id = UUID.random(), name = "qa")
        coEvery { repository.getByName("qa") } returns duplicate

        val failure = assertFailsWith<IllegalStateException> {
            EnvironmentTypeServiceImpl(repository).update(UUID.random(), "qa", null, 0, 1)
        }

        assertEquals("An environment type named 'qa' already exists", failure.message)
        coVerify(exactly = 0) { repository.update(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `update reports optimistic lock failure when the repository does not update`() = runTest {
        val repository = mockk<EnvironmentTypeRepository>()
        val id = UUID.random()
        coEvery { repository.getByName("qa") } returns null
        coEvery { repository.list() } returns emptyList()
        coEvery { repository.update(id, "qa", null, 0, 8) } returns null

        val failure = assertFailsWith<OptimisticLockFailedException> {
            EnvironmentTypeServiceImpl(repository).update(id, "qa", null, 0, 8)
        }

        assertEquals("EnvironmentType", failure.type)
        assertEquals(id, failure.id)
    }
}
