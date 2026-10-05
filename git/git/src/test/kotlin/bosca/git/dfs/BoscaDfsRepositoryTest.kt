package bosca.git.dfs

import bosca.serialization.UUID
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.jgit.lib.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BoscaDfsRepositoryTest {

    private val repositoryId = UUID.random()

    private fun createRepository(
        storageAdapter: DfsStorageAdapter = mockk<DfsStorageAdapter>(relaxed = true).also {
            every { it.listPacks(any()) } returns emptyList()
        },
        refAdapter: DfsRefAdapter = mockk<DfsRefAdapter>(relaxed = true).also {
            every { it.scanRefs(any()) } returns emptyList()
        }
    ): BoscaDfsRepository {
        return BoscaDfsRepositoryBuilder().apply {
            this.repositoryId = this@BoscaDfsRepositoryTest.repositoryId
            this.storageAdapter = storageAdapter
            this.refAdapter = refAdapter
            repositoryDescription = org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription(
                this@BoscaDfsRepositoryTest.repositoryId.toString()
            )
        }.build()
    }

    @Test
    fun `repository exposes object and ref databases`() {
        val repo = createRepository()
        assertNotNull(repo.objectDatabase)
        assertNotNull(repo.refDatabase)
    }

    @Test
    fun `repository carries correct repository ID`() {
        val repo = createRepository()
        assertEquals(repositoryId, repo.repositoryId)
    }

    @Test
    fun `scanRefs returns refs from adapter`() {
        val refAdapter = mockk<DfsRefAdapter>(relaxed = true)
        every { refAdapter.scanRefs(repositoryId) } returns listOf(
            DfsRefInfo(name = "refs/heads/main", objectId = ObjectId.zeroId().name())
        )

        val repo = createRepository(refAdapter = refAdapter)
        val refs = repo.refDatabase.refs
        assertTrue(refs.any { it.name == "refs/heads/main" })
    }

    @Test
    fun `compareAndPut creates new ref via adapter`() {
        val refAdapter = mockk<DfsRefAdapter>(relaxed = true)
        every { refAdapter.scanRefs(repositoryId) } returns emptyList()
        every { refAdapter.compareAndPut(any(), any(), any(), any(), any(), any()) } returns true

        val repo = createRepository(refAdapter = refAdapter)
        val db = repo.refDatabase

        val update = db.newUpdate("refs/heads/main", false)
        update.setNewObjectId(ObjectId.zeroId())
        update.setExpectedOldObjectId(ObjectId.zeroId())

        verify(exactly = 0) { refAdapter.compareAndRemove(any(), any(), any()) }
    }

    @Test
    fun `getApproximateObjectCount sums pack object counts`() {
        val storageAdapter = mockk<DfsStorageAdapter>(relaxed = true)
        every { storageAdapter.listPacks(repositoryId) } returns listOf(
            DfsPackInfo(
                UUID.random(), repositoryId, "p1", "INSERT",
                objectCount = 10,
                extensions = mapOf("pack" to DfsPackExtensionInfo("pack", 100, "git/$repositoryId/packs/p1.pack"))
            ),
            DfsPackInfo(
                UUID.random(), repositoryId, "p2", "INSERT",
                objectCount = 25,
                extensions = mapOf("pack" to DfsPackExtensionInfo("pack", 200, "git/$repositoryId/packs/p2.pack"))
            )
        )

        val repo = createRepository(storageAdapter = storageAdapter)
        val objDb = repo.objectDatabase as BoscaDfsObjDatabase
        assertEquals(35, objDb.approximateObjectCount)
    }
}
