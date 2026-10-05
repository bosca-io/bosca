package bosca.git.dfs

import bosca.git.service.fence
import bosca.serialization.UUID
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.TreeFormatter
import org.eclipse.jgit.transport.ReceivePack
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * In-memory [DfsStorageAdapter] that stores pack files and metadata in hash maps,
 * providing a functional adapter without database or object storage dependencies.
 */
private class InMemoryDfsStorageAdapter : DfsStorageAdapter {
    data class PackRecord(
        val id: UUID,
        val repositoryId: UUID,
        val packName: String,
        val packSource: String,
        var committed: Boolean = false,
        var fileSize: Long = 0L,
        var objectCount: Long = 0L,
        var deltaCount: Long = 0L
    )

    data class ExtensionRecord(
        val packId: UUID,
        val extension: String,
        val fileSize: Long,
        val storagePath: String
    )

    val packs = ConcurrentHashMap<UUID, PackRecord>()
    val extensions = ConcurrentHashMap<String, ExtensionRecord>()
    val files = ConcurrentHashMap<String, ByteArray>()

    override fun listPacks(repositoryId: UUID): List<DfsPackInfo> {
        val committed = packs.values.filter { it.repositoryId == repositoryId && it.committed }
        return committed.mapNotNull { pack ->
            val exts = extensions.values
                .filter { it.packId == pack.id }
                .associate { ext ->
                    ext.extension to DfsPackExtensionInfo(
                        extension = ext.extension,
                        fileSize = ext.fileSize,
                        storagePath = ext.storagePath
                    )
                }
            if (exts.isEmpty()) return@mapNotNull null
            DfsPackInfo(
                id = pack.id,
                repositoryId = pack.repositoryId,
                packName = pack.packName,
                packSource = pack.packSource,
                fileSize = pack.fileSize,
                objectCount = pack.objectCount,
                deltaCount = pack.deltaCount,
                extensions = exts
            )
        }
    }

    override fun createPack(repositoryId: UUID, packName: String, source: String): DfsPackInfo {
        val id = UUID.random()
        packs[id] = PackRecord(id, repositoryId, packName, source)
        return DfsPackInfo(id = id, repositoryId = repositoryId, packName = packName, packSource = source)
    }

    override fun commitPacks(toCommit: List<DfsPackInfo>, toReplace: List<DfsPackInfo>) {
        for (info in toCommit) {
            packs[info.id]?.apply {
                committed = true
                fileSize = info.fileSize
                objectCount = info.objectCount
                deltaCount = info.deltaCount
            }
        }
        for (info in toReplace) {
            extensions.values.removeIf { it.packId == info.id }
            packs.remove(info.id)
        }
    }

    override fun rollbackPacks(packs: List<DfsPackInfo>) {
        for (info in packs) {
            extensions.values.removeIf { it.packId == info.id }
            this.packs.remove(info.id)
        }
    }

    override fun openFile(repositoryId: UUID, storagePath: String): InputStream {
        val data = files[storagePath]
            ?: throw java.io.FileNotFoundException("No file at $storagePath")
        return ByteArrayInputStream(data)
    }

    override fun openFileRange(repositoryId: UUID, storagePath: String, range: LongRange): InputStream {
        val data = files[storagePath]
            ?: throw java.io.FileNotFoundException("No file at $storagePath")
        val from = range.first.toInt().coerceIn(0, data.size)
        val toExclusive = (range.last.toInt() + 1).coerceIn(from, data.size)
        return ByteArrayInputStream(data, from, toExclusive - from)
    }

    override fun writeFile(packInfo: DfsPackInfo, extension: String, data: ByteArray) {
        val storagePath = "git/${packInfo.repositoryId}/packs/${packInfo.packName}.$extension"
        files[storagePath] = data
        val key = "${packInfo.id}:$extension"
        extensions[key] = ExtensionRecord(
            packId = packInfo.id,
            extension = extension,
            fileSize = data.size.toLong(),
            storagePath = storagePath
        )
    }
}

/**
 * In-memory [DfsRefAdapter] for testing.
 */
private class InMemoryDfsRefAdapter : DfsRefAdapter {
    data class RefRecord(val name: String, val objectId: String, val peeledId: String?, val symbolicTarget: String?)

    private val refs = ConcurrentHashMap<String, RefRecord>()

    private fun key(repositoryId: UUID, name: String) = "$repositoryId:$name"

    override fun scanRefs(repositoryId: UUID): List<DfsRefInfo> {
        return refs.entries
            .filter { it.key.startsWith("$repositoryId:") }
            .map { DfsRefInfo(it.value.name, it.value.objectId, it.value.peeledId, it.value.symbolicTarget) }
    }

    override fun compareAndPut(
        repositoryId: UUID, name: String, expectedOldId: String?,
        newId: String, peeledId: String?, symbolicTarget: String?
    ): Boolean {
        val k = key(repositoryId, name)
        val existing = refs[k]
        if (expectedOldId == null) {
            if (existing != null) return false
            refs[k] = RefRecord(name, newId, peeledId, symbolicTarget)
            return true
        }
        if (existing == null || existing.objectId != expectedOldId) return false
        refs[k] = RefRecord(name, newId, peeledId, symbolicTarget)
        return true
    }

    override fun compareAndRemove(repositoryId: UUID, name: String, expectedOldId: String): Boolean {
        val k = key(repositoryId, name)
        val existing = refs[k] ?: return false
        if (existing.objectId != expectedOldId) return false
        refs.remove(k)
        return true
    }
}

/**
 * Tests [BoscaDfsObjDatabase] through JGit's public API, exercising the
 * real code paths used by `DfsInserter`, `DfsPackParser`, and `ReceivePack`.
 */
class BoscaDfsObjDatabaseTest {

    private val repositoryId = UUID.random()
    private lateinit var storageAdapter: InMemoryDfsStorageAdapter
    private lateinit var refAdapter: InMemoryDfsRefAdapter
    private lateinit var repo: BoscaDfsRepository

    @BeforeTest
    fun setup() {
        storageAdapter = InMemoryDfsStorageAdapter()
        refAdapter = InMemoryDfsRefAdapter()
        repo = BoscaDfsRepositoryBuilder().apply {
            this.repositoryId = this@BoscaDfsObjDatabaseTest.repositoryId
            this.storageAdapter = this@BoscaDfsObjDatabaseTest.storageAdapter
            this.refAdapter = this@BoscaDfsObjDatabaseTest.refAdapter
            repositoryDescription = DfsRepositoryDescription(this@BoscaDfsObjDatabaseTest.repositoryId.toString())
        }.build()
    }

    @Test
    fun `inserter persists pack files to storage on flush`() {
        val inserter = repo.objectDatabase.newInserter()
        inserter.insert(Constants.OBJ_BLOB, "hello\n".toByteArray())
        inserter.flush()
        inserter.close()

        assertTrue(storageAdapter.files.isNotEmpty(), "Pack files should be persisted to storage")
        assertTrue(storageAdapter.extensions.isNotEmpty(), "Extensions should be recorded in metadata")
        assertTrue(
            storageAdapter.packs.values.any { it.committed },
            "Pack should be committed"
        )
        val pack = storageAdapter.listPacks(repositoryId).single()
        assertEquals(1L, pack.objectCount)
        assertEquals(pack.extensions.getValue("pack").fileSize, pack.fileSize)
    }

    @Test
    fun `commitPacksFence aborts the pack commit before anything lands`() {
        val objDb = repo.objectDatabase as BoscaDfsObjDatabase
        objDb.commitPacksFence = { throw IllegalStateException("write lock lost") }

        val inserter = repo.objectDatabase.newInserter()
        inserter.insert(Constants.OBJ_BLOB, "fenced\n".toByteArray())
        try {
            inserter.flush()
            fail("flush must fail when the fence rejects the commit")
        } catch (_: IllegalStateException) {
            // expected: the fence fired at the commit point
        }
        inserter.close()

        assertTrue(
            storageAdapter.packs.values.none { it.committed },
            "no pack may be committed once the fence rejects the swap"
        )

        // Without the fence, the same write commits normally.
        objDb.commitPacksFence = null
        val retry = repo.objectDatabase.newInserter()
        retry.insert(Constants.OBJ_BLOB, "fenced\n".toByteArray())
        retry.flush()
        retry.close()
        assertTrue(storageAdapter.packs.values.any { it.committed })
    }

    @Test
    fun `inserted blob is readable after flush`() {
        val inserter = repo.objectDatabase.newInserter()
        val blobId = inserter.insert(Constants.OBJ_BLOB, "hello world\n".toByteArray())
        inserter.flush()
        inserter.close()

        val reader = repo.objectDatabase.newReader()
        assertTrue(reader.has(blobId), "Blob should exist after flush")
        val loader = reader.open(blobId)
        assertEquals("hello world\n", String(loader.bytes))
        reader.close()
    }

    @Test
    fun `multiple objects inserted and all readable`() {
        val inserter = repo.objectDatabase.newInserter()
        val blob1 = inserter.insert(Constants.OBJ_BLOB, "file1".toByteArray())
        val blob2 = inserter.insert(Constants.OBJ_BLOB, "file2".toByteArray())
        val blob3 = inserter.insert(Constants.OBJ_BLOB, "file3".toByteArray())
        inserter.flush()
        inserter.close()

        val reader = repo.objectDatabase.newReader()
        assertTrue(reader.has(blob1))
        assertTrue(reader.has(blob2))
        assertTrue(reader.has(blob3))
        reader.close()
    }

    @Test
    fun `commit with tree and blob is readable`() {
        val inserter = repo.objectDatabase.newInserter()
        val blobId = inserter.insert(Constants.OBJ_BLOB, "content\n".toByteArray())
        val tree = TreeFormatter()
        tree.append("file.txt", FileMode.REGULAR_FILE, blobId)
        val treeId = inserter.insert(tree)
        val commit = CommitBuilder().apply {
            setTreeId(treeId)
            author = PersonIdent("Test", "test@test.com")
            committer = PersonIdent("Test", "test@test.com")
            message = "test commit"
        }
        val commitId = inserter.insert(commit)
        inserter.flush()
        inserter.close()

        val reader = repo.objectDatabase.newReader()
        assertTrue(reader.has(commitId), "Commit should be readable")
        assertTrue(reader.has(treeId), "Tree should be readable")
        assertTrue(reader.has(blobId), "Blob should be readable")
        assertEquals(Constants.OBJ_COMMIT, reader.open(commitId).type)
        reader.close()
    }

    @Test
    fun `objects readable after fresh listPacks from storage`() {
        val inserter = repo.objectDatabase.newInserter()
        val blobId = inserter.insert(Constants.OBJ_BLOB, "persistent\n".toByteArray())
        inserter.flush()
        inserter.close()

        val freshRepo = BoscaDfsRepositoryBuilder().apply {
            this.repositoryId = this@BoscaDfsObjDatabaseTest.repositoryId
            this.storageAdapter = this@BoscaDfsObjDatabaseTest.storageAdapter
            this.refAdapter = this@BoscaDfsObjDatabaseTest.refAdapter
            repositoryDescription = DfsRepositoryDescription(this@BoscaDfsObjDatabaseTest.repositoryId.toString())
        }.build()

        val reader = freshRepo.objectDatabase.newReader()
        assertTrue(reader.has(blobId), "Object should be readable from a fresh repository instance")
        assertEquals("persistent\n", String(reader.open(blobId).bytes))
        reader.close()
    }

    @Test
    fun `ReceivePack accepts push to empty repository`() {
        val author = PersonIdent("Test", "test@test.com")
        val clientRepo = InMemoryRepository(DfsRepositoryDescription("client"))

        val clientInserter = clientRepo.objectDatabase.newInserter()
        val blobId = clientInserter.insert(Constants.OBJ_BLOB, "hello\n".toByteArray())
        val tree = TreeFormatter()
        tree.append("README.md", FileMode.REGULAR_FILE, blobId)
        val treeId = clientInserter.insert(tree)
        val commit = CommitBuilder().apply {
            setTreeId(treeId)
            this.author = author
            this.committer = author
            message = "initial commit"
        }
        val commitId = clientInserter.insert(commit)
        clientInserter.flush()
        val refUpdate = clientRepo.refDatabase.newUpdate("refs/heads/main", true)
        refUpdate.setNewObjectId(commitId)
        refUpdate.update()

        val clientPack = generatePack(clientRepo, commitId)
        val cmdLine = "${ObjectId.zeroId().name()} ${commitId.name()} refs/heads/main\u0000 report-status side-band-64k\n"
        val pktCmd = String.format("%04x%s", cmdLine.length + 4, cmdLine)
        val requestBytes = (pktCmd + "0000").toByteArray() + clientPack

        val receivePack = ReceivePack(repo)
        receivePack.setBiDirectionalPipe(false)

        val responseBytes = ByteArrayOutputStream()
        try {
            receivePack.receive(ByteArrayInputStream(requestBytes), responseBytes, null)
        } catch (e: Exception) {
            fail("ReceivePack.receive() threw: ${e.message}")
        }

        val responseStr = parseSidebandResponse(responseBytes.toByteArray())
        assertTrue(
            !responseStr.contains("ng refs/heads/main"),
            "Push should succeed. Response: $responseStr"
        )

        val reader = repo.objectDatabase.newReader()
        assertTrue(reader.has(commitId), "Pushed commit should be readable")
        assertTrue(reader.has(treeId), "Pushed tree should be readable")
        assertTrue(reader.has(blobId), "Pushed blob should be readable")
        reader.close()

        assertTrue(storageAdapter.files.isNotEmpty(), "Pack files should be persisted to storage")
        assertTrue(storageAdapter.extensions.isNotEmpty(), "Pack extensions should be recorded")
        assertTrue(
            storageAdapter.packs.values.any { it.committed },
            "Pack should be committed"
        )
    }

    @Test
    fun `ReceivePack objects readable from fresh repository instance`() {
        val author = PersonIdent("Test", "test@test.com")
        val clientRepo = InMemoryRepository(DfsRepositoryDescription("client"))

        val clientInserter = clientRepo.objectDatabase.newInserter()
        val blobId = clientInserter.insert(Constants.OBJ_BLOB, "persistent data\n".toByteArray())
        val tree = TreeFormatter()
        tree.append("file.txt", FileMode.REGULAR_FILE, blobId)
        val treeId = clientInserter.insert(tree)
        val commit = CommitBuilder().apply {
            setTreeId(treeId)
            this.author = author
            this.committer = author
            message = "test persistence"
        }
        val commitId = clientInserter.insert(commit)
        clientInserter.flush()

        val clientPack = generatePack(clientRepo, commitId)
        val cmdLine = "${ObjectId.zeroId().name()} ${commitId.name()} refs/heads/main\u0000 report-status side-band-64k\n"
        val pktCmd = String.format("%04x%s", cmdLine.length + 4, cmdLine)
        val requestBytes = (pktCmd + "0000").toByteArray() + clientPack

        val receivePack = ReceivePack(repo)
        receivePack.setBiDirectionalPipe(false)
        receivePack.receive(ByteArrayInputStream(requestBytes), ByteArrayOutputStream(), null)

        val freshRepo = BoscaDfsRepositoryBuilder().apply {
            this.repositoryId = this@BoscaDfsObjDatabaseTest.repositoryId
            this.storageAdapter = this@BoscaDfsObjDatabaseTest.storageAdapter
            this.refAdapter = this@BoscaDfsObjDatabaseTest.refAdapter
            repositoryDescription = DfsRepositoryDescription(this@BoscaDfsObjDatabaseTest.repositoryId.toString())
        }.build()

        val reader = freshRepo.objectDatabase.newReader()
        assertTrue(reader.has(commitId), "Object should be readable from fresh repo (proves storage persistence)")
        assertEquals("persistent data\n", String(reader.open(blobId).bytes))
        reader.close()
    }

    @Test
    fun `push succeeds even when orphaned packs exist in storage`() {
        val orphanedPackId = UUID.random()
        storageAdapter.packs[orphanedPackId] = InMemoryDfsStorageAdapter.PackRecord(
            id = orphanedPackId,
            repositoryId = repositoryId,
            packName = "pack-orphaned-old",
            packSource = "RECEIVE",
            committed = true
        )

        val author = PersonIdent("Test", "test@test.com")
        val clientRepo = InMemoryRepository(DfsRepositoryDescription("client"))
        val clientInserter = clientRepo.objectDatabase.newInserter()
        val blobId = clientInserter.insert(Constants.OBJ_BLOB, "data\n".toByteArray())
        val tree = TreeFormatter()
        tree.append("file.txt", FileMode.REGULAR_FILE, blobId)
        val treeId = clientInserter.insert(tree)
        val commit = CommitBuilder().apply {
            setTreeId(treeId)
            this.author = author
            this.committer = author
            message = "push with orphaned packs present"
        }
        val commitId = clientInserter.insert(commit)
        clientInserter.flush()

        val clientPack = generatePack(clientRepo, commitId)
        val cmdLine = "${ObjectId.zeroId().name()} ${commitId.name()} refs/heads/main\u0000 report-status side-band-64k\n"
        val pktCmd = String.format("%04x%s", cmdLine.length + 4, cmdLine)
        val requestBytes = (pktCmd + "0000").toByteArray() + clientPack

        val receivePack = ReceivePack(repo)
        receivePack.setBiDirectionalPipe(false)

        val responseBytes = ByteArrayOutputStream()
        try {
            receivePack.receive(ByteArrayInputStream(requestBytes), responseBytes, null)
        } catch (e: Exception) {
            fail("ReceivePack.receive() threw with orphaned packs present: ${e.message}")
        }

        val responseStr = parseSidebandResponse(responseBytes.toByteArray())
        assertTrue(
            !responseStr.contains("ng refs/heads/main"),
            "Push should succeed despite orphaned packs. Response: $responseStr"
        )

        val reader = repo.objectDatabase.newReader()
        assertTrue(reader.has(commitId), "Object should be readable despite prior orphaned packs")
        reader.close()
    }

    @Test
    fun `orphaned packs are skipped by listPacks without mutating storage`() {
        val orphanId = UUID.random()
        storageAdapter.packs[orphanId] = InMemoryDfsStorageAdapter.PackRecord(
            id = orphanId,
            repositoryId = repositoryId,
            packName = "pack-orphan-cleanup",
            packSource = "RECEIVE",
            committed = true
        )

        val listed = storageAdapter.listPacks(repositoryId)
        assertEquals(0, listed.size, "Orphaned pack should not appear in listPacks")
        assertTrue(
            storageAdapter.packs.containsKey(orphanId),
            "listPacks must not delete orphaned packs; cleanup belongs to GC/maintenance"
        )
    }

    @Test
    fun `isThinPack returns true when header count disagrees with idx count`() {
        // Seed storage with a fake pack whose .pack header claims fewer objects
        // than its .idx says — the signature DfsPackParser leaves behind for thin
        // packs. We don't need real pack content; isThinPack only reads the
        // 4-byte count at offset 8 and compares to the recorded objectCount.
        val packInfo = storageAdapter.createPack(repositoryId, "pack-thin-test", "RECEIVE")
        val packBytes = byteArrayOf(
            'P'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte(), 'K'.code.toByte(),
            0, 0, 0, 2,           // pack version 2
            0, 0, 0, 2,           // header claims 2 objects
            // body + trailer omitted — isThinPack only inspects bytes 8-11
        )
        storageAdapter.writeFile(packInfo, "pack", packBytes)
        // Idx isn't read by isThinPack; the count comes from the PG record below.
        storageAdapter.writeFile(packInfo, "idx", ByteArray(0))
        storageAdapter.commitPacks(listOf(packInfo.copy(objectCount = 3L)), emptyList())

        val objdb = repo.objectDatabase as BoscaDfsObjDatabase
        objdb.packs  // public accessor; populates packInfoByName as a side effect
        assertTrue(objdb.isThinPack(packInfo.packName), "header=2 vs idx=3 should be detected as thin")
    }

    @Test
    fun `isThinPack returns false when header count matches idx count`() {
        val packInfo = storageAdapter.createPack(repositoryId, "pack-thick-test", "RECEIVE")
        val packBytes = byteArrayOf(
            'P'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte(), 'K'.code.toByte(),
            0, 0, 0, 2,
            0, 0, 0, 5,           // header says 5
        )
        storageAdapter.writeFile(packInfo, "pack", packBytes)
        storageAdapter.writeFile(packInfo, "idx", ByteArray(0))
        storageAdapter.commitPacks(listOf(packInfo.copy(objectCount = 5L)), emptyList())

        val objdb = repo.objectDatabase as BoscaDfsObjDatabase
        objdb.packs
        assertTrue(!objdb.isThinPack(packInfo.packName), "header=5 vs idx=5 should NOT be detected as thin")
    }

    @Test
    fun `isThinPack returns false for an unknown pack name`() {
        val objdb = repo.objectDatabase as BoscaDfsObjDatabase
        assertTrue(!objdb.isThinPack("no-such-pack"), "unknown packs return false rather than throwing")
    }

    private fun generatePack(sourceRepo: InMemoryRepository, vararg objects: ObjectId): ByteArray {
        val buf = ByteArrayOutputStream()
        val pw = org.eclipse.jgit.internal.storage.pack.PackWriter(sourceRepo)
        pw.preparePack(NullProgressMonitor.INSTANCE, objects.toSet(), emptySet())
        pw.writePack(NullProgressMonitor.INSTANCE, NullProgressMonitor.INSTANCE, buf)
        pw.close()
        return buf.toByteArray()
    }

    private fun parseSidebandResponse(response: ByteArray): String {
        val sb = StringBuilder()
        var pos = 0
        while (pos + 4 <= response.size) {
            val lenStr = String(response, pos, 4)
            if (lenStr == "0000") break
            val len = try { lenStr.toInt(16) } catch (_: NumberFormatException) { break }
            if (len <= 4 || pos + len > response.size) break
            val band = response[pos + 4].toInt()
            if (band == 1) {
                sb.append(String(response, pos + 5, len - 5))
            } else if (band == 3) {
                sb.append("[ERROR] ")
                sb.append(String(response, pos + 5, len - 5))
            }
            pos += len
        }
        if (sb.isEmpty() && response.isNotEmpty()) {
            sb.append(String(response))
        }
        return sb.toString()
    }
    @Test
    fun `missing object count is recovered from the index instead of the pack header`() {
        repo.objectDatabase.newInserter().use { inserter ->
            inserter.insert(Constants.OBJ_BLOB, "x".toByteArray())
            inserter.flush()
        }
        val info = storageAdapter.listPacks(repositoryId).single()
        storageAdapter.packs.getValue(info.id).objectCount = 0L
        val data = storageAdapter.files.getValue(info.extensions.getValue("pack").storagePath)
        data.fill(0, 8, 12)

        BoscaDfsRepositoryBuilder().apply {
            repositoryId = this@BoscaDfsObjDatabaseTest.repositoryId
            storageAdapter = this@BoscaDfsObjDatabaseTest.storageAdapter
            refAdapter = this@BoscaDfsObjDatabaseTest.refAdapter
            repositoryDescription = DfsRepositoryDescription(repositoryId.toString())
        }.build().use { freshRepo ->
            val objdb = freshRepo.objectDatabase as BoscaDfsObjDatabase
            assertEquals(1L, objdb.packs.single().packDescription.objectCount)
            assertTrue(objdb.isThinPack(info.packName), "index includes an object absent from the header count")
        }
        assertEquals(0L, storageAdapter.packs.getValue(info.id).objectCount, "reads must not mutate stored metadata")
    }

    @Test
    fun `a lock handle's fence aborts pack commits once its lock is lost`() {
        val lock = io.mockk.mockk<bosca.lock.DistributedLock>()
        io.mockk.coEvery { lock.renew(any()) } returns false
        bosca.git.service.RepositoryWriteLockHandle("git-repo-write-test", lock).fence(repo)

        val inserter = repo.objectDatabase.newInserter()
        inserter.insert(Constants.OBJ_BLOB, "written after the lock was lost\n".toByteArray())
        try {
            inserter.flush()
            fail("flush must fail once the lock is no longer held")
        } catch (_: bosca.git.service.RepositoryWriteLockLostException) {
            // expected: the fence confirmed the lock at the commit point
        }
        inserter.close()

        assertTrue(storageAdapter.packs.values.none { it.committed }, "nothing may be committed without the lock")
    }
}
