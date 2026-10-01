package bosca.git.dfs

import bosca.git.repository.DfsPackRepository
import bosca.git.repository.DfsRefRepository
import bosca.serialization.UUID
import bosca.storage.service.ObjectStorageService
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Covers the DFS repository factory: wiring, description, and per-open isolation. */
class BoscaDfsRepositoryManagerTest {

    private val manager = BoscaDfsRepositoryManager(
        mockk<ObjectStorageService>(relaxed = true),
        mockk<DfsPackRepository>(relaxed = true),
        mockk<DfsRefRepository>(relaxed = true),
    )

    @Test
    fun `open builds a repository scoped to the id`() {
        val id = UUID.random()
        manager.open(id).use { repo ->
            assertEquals(id.toString(), repo.description.repositoryName)
            assertTrue(repo.objectDatabase is BoscaDfsObjDatabase)
        }
    }

    @Test
    fun `pack streaming opts into large sequential read ahead`() {
        manager.open(UUID.random()).use { repo ->
            assertEquals(16 * 1024 * 1024, repo.objectDatabase.readerOptions.streamPackBufferSize)
        }
    }

    @Test
    fun `each open returns an independent instance`() {
        val id = UUID.random()
        val a = manager.open(id)
        val b = manager.open(id)
        try {
            assertTrue(a !== b)
        } finally {
            a.close(); b.close()
        }
    }
}
