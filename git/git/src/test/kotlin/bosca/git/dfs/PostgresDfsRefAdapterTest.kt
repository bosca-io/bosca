@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.dfs

import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.model.DfsRef
import bosca.git.repository.DfsRefRepository
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.eclipse.jgit.lib.ObjectId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostgresDfsRefAdapterTest {

    private val refRepository = mockk<DfsRefRepository>(relaxed = true)
    private val adapter = PostgresDfsRefAdapter(refRepository)
    private val repositoryId = UUID.random()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { mockk(relaxed = true) }
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    @Test
    fun `scanRefs returns all refs`() {
        coEvery { refRepository.findAll(repositoryId) } returns listOf(
            DfsRef(repositoryId, "refs/heads/main", "abc123"),
            DfsRef(repositoryId, "refs/tags/v1.0", "def456", peeledId = "111222")
        )

        val refs = adapter.scanRefs(repositoryId)
        assertEquals(2, refs.size)
        assertEquals("refs/heads/main", refs[0].name)
        assertEquals("abc123", refs[0].objectId)
        assertEquals("111222", refs[1].peeledId)
    }

    @Test
    fun `compareAndPut creates ref when expectedOldId is null`() {
        coEvery { refRepository.findByName(repositoryId, "refs/heads/new-branch") } returns null
        coEvery { refRepository.upsert(any()) } answers { firstArg() }

        val result = adapter.compareAndPut(
            repositoryId, "refs/heads/new-branch", null, "abc123", null, null
        )
        assertTrue(result)
        coVerify { refRepository.upsert(match { it.name == "refs/heads/new-branch" && it.objectId == "abc123" }) }
    }

    @Test
    fun `compareAndPut fails when ref already exists and expectedOldId is null`() {
        coEvery { refRepository.findByName(repositoryId, "refs/heads/main") } returns
            DfsRef(repositoryId, "refs/heads/main", "existing-id")

        val result = adapter.compareAndPut(
            repositoryId, "refs/heads/main", null, "new-id", null, null
        )
        assertFalse(result)
    }

    @Test
    fun `compareAndPut with expected old ID uses compareAndSwap`() {
        coEvery { refRepository.compareAndSwap(repositoryId, "refs/heads/main", "old-id", "new-id") } returns
            DfsRef(repositoryId, "refs/heads/main", "new-id")

        val result = adapter.compareAndPut(
            repositoryId, "refs/heads/main", "old-id", "new-id", null, null
        )
        assertTrue(result)
    }

    @Test
    fun `compareAndPut fails when old ID does not match`() {
        coEvery { refRepository.compareAndSwap(repositoryId, "refs/heads/main", "wrong-id", "new-id") } returns null

        val result = adapter.compareAndPut(
            repositoryId, "refs/heads/main", "wrong-id", "new-id", null, null
        )
        assertFalse(result)
    }

    @Test
    fun `compareAndRemove deletes ref when ID matches`() {
        coEvery { refRepository.findByName(repositoryId, "refs/heads/feature") } returns
            DfsRef(repositoryId, "refs/heads/feature", "abc123")

        val result = adapter.compareAndRemove(repositoryId, "refs/heads/feature", "abc123")
        assertTrue(result)
        coVerify { refRepository.delete(repositoryId, "refs/heads/feature") }
    }

    @Test
    fun `compareAndRemove fails when ID does not match`() {
        coEvery { refRepository.findByName(repositoryId, "refs/heads/feature") } returns
            DfsRef(repositoryId, "refs/heads/feature", "different-id")

        val result = adapter.compareAndRemove(repositoryId, "refs/heads/feature", "abc123")
        assertFalse(result)
    }
}
