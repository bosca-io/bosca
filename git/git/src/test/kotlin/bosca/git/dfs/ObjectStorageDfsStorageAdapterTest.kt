@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.dfs

import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.repository.DfsPackRepository
import bosca.serialization.UUID
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.ByteArrayInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ObjectStorageDfsStorageAdapterTest {

    private val objectStorage = mockk<ObjectStorageService>(relaxed = true)
    private val packRepository = mockk<DfsPackRepository>(relaxed = true)
    private val adapter = ObjectStorageDfsStorageAdapter(objectStorage, packRepository)
    private val repositoryId = UUID.random()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        // commitPacks/rollbackPacks run inside withConnectionManager { transaction { } },
        // which resolves a ConnectionPool from DI. A relaxed mock makes begin/commit no-ops.
        provides<ConnectionPool>(singleton = true) { mockk(relaxed = true) }
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    @Test
    fun `listPacks queries committed packs and their extensions`() {
        val packId = UUID.random()
        coEvery { packRepository.findCommitted(repositoryId) } returns listOf(
            DfsPack(id = packId, repositoryId = repositoryId, packName = "test-pack", packSource = "INSERT", committed = true)
        )
        coEvery { packRepository.findExtensionsByRepository(repositoryId) } returns listOf(
            DfsPackExtension(packId = packId, extension = "pack", fileSize = 1024, storagePath = "git/$repositoryId/packs/test-pack.pack"),
            DfsPackExtension(packId = packId, extension = "idx", fileSize = 256, storagePath = "git/$repositoryId/packs/test-pack.idx")
        )

        val packs = adapter.listPacks(repositoryId)
        assertEquals(1, packs.size)
        assertEquals("test-pack", packs[0].packName)
        assertEquals(2, packs[0].extensions.size)
        assertEquals(1024L, packs[0].extensions["pack"]?.fileSize)
        assertEquals(256L, packs[0].extensions["idx"]?.fileSize)
    }

    @Test
    fun `createPack inserts a pack record and returns its info`() {
        val created = DfsPack(id = UUID.random(), repositoryId = repositoryId, packName = "new-pack", packSource = "GC")
        coEvery { packRepository.create(any()) } returns created

        val info = adapter.createPack(repositoryId, "new-pack", "GC")

        assertEquals(created.id, info.id)
        assertEquals("new-pack", info.packName)
        assertEquals("GC", info.packSource)
    }

    @Test
    fun `listPacks returns empty when there are no committed packs`() {
        coEvery { packRepository.findCommitted(repositoryId) } returns emptyList()

        assertEquals(0, adapter.listPacks(repositoryId).size)
    }

    @Test
    fun `listPacks skips packs that have no extensions`() {
        val packId = UUID.random()
        coEvery { packRepository.findCommitted(repositoryId) } returns listOf(
            DfsPack(id = packId, repositoryId = repositoryId, packName = "orphan", packSource = "INSERT", committed = true)
        )
        coEvery { packRepository.findExtensionsByRepository(repositoryId) } returns emptyList()

        assertEquals(0, adapter.listPacks(repositoryId).size)
    }

    @Test
    fun `openFileRange reads byte range from ObjectStorage`() {
        val content = "0123456789".toByteArray()
        coEvery { objectStorage.getInputStreamRange(any(), 2L..5L) } returns ByteArrayInputStream(content, 2, 4)

        val stream = adapter.openFileRange(repositoryId, "git/$repositoryId/packs/test.pack", 2L..5L)
        val read = stream.readAllBytes()
        assertEquals("2345", String(read))
    }

    @Test
    fun `writeFile stores data in ObjectStorage and records extension`() {
        val packInfo = DfsPackInfo(
            id = UUID.random(),
            repositoryId = repositoryId,
            packName = "pack-write-test",
            packSource = "INSERT"
        )
        val data = "test-data".toByteArray()
        coEvery { objectStorage.setInputStream(any(), any(), any()) } returns data.size.toLong()
        coEvery { packRepository.upsertExtension(any()) } answers { firstArg() }

        adapter.writeFile(packInfo, "pack", data)

        coVerify {
            objectStorage.setInputStream(
                match<StringObjectPath> { it.toString() == "git/$repositoryId/packs/pack-write-test.pack" },
                any(),
                data.size.toLong()
            )
        }
        coVerify {
            packRepository.upsertExtension(match {
                it.extension == "pack" && it.storagePath == "git/$repositoryId/packs/pack-write-test.pack"
            })
        }
    }

    @Test
    fun `openFile reads from ObjectStorage`() {
        val content = "pack-content".toByteArray()
        coEvery { objectStorage.getInputStream(any()) } returns ByteArrayInputStream(content)

        val stream = adapter.openFile(repositoryId, "git/$repositoryId/packs/test.pack")
        val read = stream.readAllBytes()
        assertEquals(content.size, read.size)
    }

    @Test
    fun `rollbackPacks soft-deletes without touching storage inline`() {
        val packId = UUID.random()
        val info = DfsPackInfo(id = packId, repositoryId = repositoryId, packName = "rollback-pack", packSource = "INSERT")

        adapter.rollbackPacks(listOf(info))

        // Storage removal is deferred to the reaper; rollback only tombstones the row.
        coVerify { packRepository.markDeleted(packId) }
        coVerify(exactly = 0) { objectStorage.delete(any()) }
        coVerify(exactly = 0) { packRepository.delete(any()) }
    }

    @Test
    fun `commitPacks commits new packs and soft-deletes replaced packs`() {
        val newId = UUID.random()
        val oldId = UUID.random()
        val newPack = DfsPackInfo(id = newId, repositoryId = repositoryId, packName = "gc-pack", packSource = "GC")
        val oldPack = DfsPackInfo(id = oldId, repositoryId = repositoryId, packName = "old-pack", packSource = "INSERT")

        adapter.commitPacks(listOf(newPack), listOf(oldPack))

        coVerify { packRepository.commit(match { it.id == newId }) }
        coVerify { packRepository.markDeleted(oldId) }
        // The replaced pack's storage is NOT removed inline — the reaper does that later.
        coVerify(exactly = 0) { objectStorage.delete(any()) }
    }
}
