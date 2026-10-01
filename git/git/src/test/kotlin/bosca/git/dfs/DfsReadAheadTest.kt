package bosca.git.dfs

import bosca.git.TestDfsRefAdapter
import bosca.git.TestDfsStorageAdapter
import bosca.serialization.UUID
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.internal.storage.pack.PackExt
import org.eclipse.jgit.internal.storage.pack.PackWriter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ObjectId
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DfsReadAheadTest {

    @Test
    fun `cold object reads do not repeatedly read the rest of the pack`() {
        val random = Random(42)
        val contents = List(64) { random.nextBytes(128 * 1024) }
        withPackedObjects(contents) { repo, ids, ranges, packSize ->
            repo.newObjectReader().use { reader ->
                for (i in ids.indices.shuffled(Random(24))) {
                    assertContentEquals(contents[i], reader.open(ids[i]).bytes)
                }
            }
            val packRanges = ranges.getValue("pack")
            val bytesRead = packRanges.sumOf { it.last - it.first + 1 }
            println("Cold pack reads: $bytesRead storage bytes for $packSize pack bytes in ${packRanges.size} requests")
            assertTrue(packRanges.isNotEmpty(), "The test must exercise cold storage reads")
            assertTrue(bytesRead <= 2L * packSize, "Read $bytesRead bytes from a $packSize-byte pack")
        }
    }

    @Test
    fun `loading an index larger than a cache block uses one range request`() {
        val contents = List(20_000) { ByteBuffer.allocate(4).putInt(it).array() }
        withPackedObjects(contents) { repo, ids, ranges, _ ->
            repo.newObjectReader().use { reader ->
                assertTrue(reader.has(ids.first()))
                assertTrue(reader.has(ids.last()))
            }
            val indexSize = repo.objectDatabase.packs.single().packDescription
                .getFileSize(PackExt.INDEX)
            assertTrue(indexSize > 512 * 1024, "The index must span multiple cache blocks")
            assertEquals(listOf(0L until indexSize), ranges.getValue("idx"))
        }
    }

    private fun withPackedObjects(
        contents: List<ByteArray>,
        block: (BoscaDfsRepository, List<ObjectId>, Map<String, List<LongRange>>, Int) -> Unit,
    ) {
        val repositoryId = UUID.random()
        val storage = TestDfsStorageAdapter()
        val ranges = mutableMapOf<String, MutableList<LongRange>>()
        val countedStorage = object : DfsStorageAdapter by storage {
            override fun openFileRange(repositoryId: UUID, storagePath: String, range: LongRange): InputStream {
                ranges.getOrPut(storagePath.substringAfterLast('.')) { mutableListOf() }.add(range)
                return storage.openFileRange(repositoryId, storagePath, range)
            }
        }
        InMemoryRepository(DfsRepositoryDescription("source-$repositoryId")).use { source ->
            val ids = source.newObjectInserter().use { inserter ->
                contents.map { inserter.insert(Constants.OBJ_BLOB, it) }.also { inserter.flush() }
            }
            val info = storage.createPack(repositoryId, "pack-read-ahead", "RECEIVE")
            PackWriter(source).use { writer ->
                writer.preparePack(NullProgressMonitor.INSTANCE, ids.toSet(), emptySet())
                val pack = ByteArrayOutputStream().also {
                    writer.writePack(NullProgressMonitor.INSTANCE, NullProgressMonitor.INSTANCE, it)
                }.toByteArray()
                val index = ByteArrayOutputStream().also { writer.writeIndex(it) }.toByteArray()
                storage.writeFile(info, "pack", pack)
                storage.writeFile(info, "idx", index)
                storage.commitPacks(listOf(info.copy(objectCount = ids.size.toLong(), fileSize = pack.size.toLong())), emptyList())

                BoscaDfsRepositoryBuilder().apply {
                    this.repositoryId = repositoryId
                    storageAdapter = countedStorage
                    refAdapter = TestDfsRefAdapter()
                    repositoryDescription = DfsRepositoryDescription(repositoryId.toString())
                }.build().use { repo ->
                    block(repo, ids, ranges, pack.size)
                }
            }
        }
    }

}
