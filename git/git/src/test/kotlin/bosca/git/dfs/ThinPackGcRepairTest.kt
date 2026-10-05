package bosca.git.dfs

import bosca.git.TestDfsRefAdapter
import bosca.git.TestDfsStorageAdapter
import bosca.git.transport.compactNewlyReceivedPacks
import bosca.serialization.UUID
import org.eclipse.jgit.internal.storage.dfs.DfsGarbageCollector
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.file.PackIndex
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ObjectId
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that the production GC code path (DfsGarbageCollector, as invoked by
 * RepositoryLifecycleServiceImpl.runGc) can read from real thin packs whose
 * .pack header object count disagrees with their .idx (the corruption pattern
 * produced by JGit's DfsPackParser when ReceivePack accepts a thin pack), and
 * produces a self-contained replacement pack in which the header count matches
 * the actual object count.
 *
 * Requires pack files at ~/Desktop/test:
 *   pack-3713509222185-2-RECEIVE.{pack,idx}      (thin, 2/3 mismatch)
 *   pack-3759955280469-4-RECEIVE.{pack,idx}      (thin, 2/3 mismatch)
 *   pack-75958630788205-40-GC.{pack,idx,bitmap}  (thick)
 *   pack-76249237210393-28-RECEIVE.{pack,idx}    (thin, mismatch)
 *
 * Skipped silently if those files aren't present (so CI doesn't fail on a
 * dev-only fixture path).
 */
class ThinPackGcRepairTest {

    @Test
    fun `GC repairs real thin packs to self-contained packs`() {
        val packDir = Path.of(System.getProperty("user.home"), "Desktop/test")
        if (!packDir.exists()) {
            println("[ThinPackGcRepairTest] SKIP: fixture pack files not available at $packDir")
            return
        }

        val repositoryId = UUID.random()
        val storageAdapter = TestDfsStorageAdapter()
        val refAdapter = TestDfsRefAdapter()

        loadPacksFromDisk(packDir, repositoryId, storageAdapter)

        val repo = BoscaDfsRepositoryBuilder().apply {
            this.repositoryId = repositoryId
            this.storageAdapter = storageAdapter
            this.refAdapter = refAdapter
            repositoryDescription = DfsRepositoryDescription(repositoryId.toString())
        }.build()

        val allCommits = findAllCommits(repo)
        check(allCommits.isNotEmpty()) { "No commit objects found across the loaded packs — fixture is broken" }
        println("[ThinPackGcRepairTest] discovered ${allCommits.size} commits across all packs")
        // Create a ref per commit so reachability covers every object reachable from
        // any commit in the packs. This over-approximates the real ref set but is
        // the safest way to distinguish "test setup didn't include enough refs" from
        // "this repo legitimately has unreachable garbage".
        allCommits.forEachIndexed { i, oid ->
            repo.refDatabase.newUpdate("refs/heads/commit-$i", true).apply {
                setNewObjectId(oid)
                update()
            }
        }

        val (preThin, preTotal) = countThinPacks(storageAdapter, repositoryId)
        println("[ThinPackGcRepairTest] before GC: $preThin of $preTotal packs are thin")
        assertTrue(preThin > 0, "Fixture must contain at least one thin pack — got $preThin/$preTotal")

        DfsGarbageCollector(repo).pack(NullProgressMonitor.INSTANCE)

        val (postThin, postTotal) = countThinPacks(storageAdapter, repositoryId)
        println("[ThinPackGcRepairTest] after GC:  $postThin of $postTotal packs are thin")
        assertTrue(postTotal > 0, "GC should have left at least one pack behind")
        assertEquals(
            0, postThin,
            "After GC every stored pack should be self-contained, but $postThin/$postTotal are still thin"
        )
    }

    private fun loadPacksFromDisk(
        packDir: Path,
        repositoryId: UUID,
        storageAdapter: TestDfsStorageAdapter
    ) {
        Files.list(packDir).use { stream ->
            stream
                .filter { it.fileName.toString().endsWith(".pack") }
                .sorted()
                .forEach { packPath ->
                    val baseName = packPath.fileName.toString().removeSuffix(".pack")
                    val source = when {
                        baseName.endsWith("-GC") -> "GC"
                        baseName.endsWith("-RECEIVE") -> "RECEIVE"
                        else -> "INSERT"
                    }
                    val packBytes = Files.readAllBytes(packPath)
                    val idxPath = packPath.resolveSibling("$baseName.idx")
                    val idxBytes = Files.readAllBytes(idxPath)

                    val info = storageAdapter.createPack(repositoryId, baseName, source)
                    storageAdapter.writeFile(info, "pack", packBytes)
                    storageAdapter.writeFile(info, "idx", idxBytes)

                    val bitmapPath = packPath.resolveSibling("$baseName.bitmap")
                    if (Files.exists(bitmapPath)) {
                        storageAdapter.writeFile(info, "bitmap", Files.readAllBytes(bitmapPath))
                    }

                    val objectCount = PackIndex.read(ByteArrayInputStream(idxBytes)).objectCount

                    storageAdapter.commitPacks(
                        listOf(info.copy(objectCount = objectCount, fileSize = packBytes.size.toLong())),
                        emptyList(),
                    )
                }
        }
    }

    /**
     * Returns (thinPackCount, totalPackCount). A pack is "thin" in this sense
     * when its 4-byte header object count at offset 8 disagrees with the count
     * its .idx advertises — the precise signature `git fsck` reports.
     */
    private fun countThinPacks(
        adapter: TestDfsStorageAdapter,
        repositoryId: UUID
    ): Pair<Int, Int> {
        val packs = adapter.listPacks(repositoryId)
        var thin = 0
        for (pack in packs) {
            val packExt = pack.extensions["pack"] ?: continue
            val idxExt = pack.extensions["idx"] ?: continue
            val packBytes = adapter.files.getValue(packExt.storagePath)
            val idxBytes = adapter.files.getValue(idxExt.storagePath)
            val headerCount = ByteBuffer.wrap(packBytes, 8, 4).int.toLong() and 0xFFFFFFFFL
            val idxCount = PackIndex.read(ByteArrayInputStream(idxBytes)).objectCount
            if (headerCount != idxCount) {
                println("[ThinPackGcRepairTest]   THIN: ${pack.packName} header=$headerCount idx=$idxCount")
                thin++
            } else {
                println("[ThinPackGcRepairTest]   OK:   ${pack.packName} count=$headerCount")
            }
        }
        return thin to packs.size
    }

    @Test
    fun `compactNewlyReceivedPacks replaces thin packs with self-contained packs`() {
        val packDir = Path.of(System.getProperty("user.home"), "Desktop/test")
        if (!packDir.exists()) {
            println("[compactNewlyReceived] SKIP: fixture pack files not available at $packDir")
            return
        }

        val repositoryId = UUID.random()
        val storageAdapter = TestDfsStorageAdapter()
        val refAdapter = TestDfsRefAdapter()
        loadPacksFromDisk(packDir, repositoryId, storageAdapter)

        val repo = BoscaDfsRepositoryBuilder().apply {
            this.repositoryId = repositoryId
            this.storageAdapter = storageAdapter
            this.refAdapter = refAdapter
            repositoryDescription = DfsRepositoryDescription(repositoryId.toString())
        }.build()

        // Refs are needed so the compactor's PackWriter has something to walk from.
        val allCommits = findAllCommits(repo)
        check(allCommits.isNotEmpty())
        allCommits.forEachIndexed { i, oid ->
            repo.refDatabase.newUpdate("refs/heads/commit-$i", true).apply {
                setNewObjectId(oid)
                update()
            }
        }

        val (preThin, preTotal) = countThinPacks(storageAdapter, repositoryId)
        println("[compactNewlyReceived] before: $preThin of $preTotal packs are thin")
        assertTrue(preThin > 0, "fixture must contain thin packs to exercise the compactor")

        // Empty namesBefore = treat every existing pack as "newly received". Only the
        // ones that are actually thin will be compacted (per the isThinPack filter).
        compactNewlyReceivedPacks(repo, namesBefore = emptySet())

        val (postThin, postTotal) = countThinPacks(storageAdapter, repositoryId)
        println("[compactNewlyReceived] after:  $postThin of $postTotal packs are thin")
        assertEquals(0, postThin, "all thin packs should have been replaced after compaction")
    }

    @Test
    fun `compactNewlyReceivedPacks leaves self-contained packs untouched`() {
        // Build a fresh repo with one thick pack, then run the compactor over it.
        // Since nothing is thin, the compactor should be a no-op — the pack should
        // still be there with the same name.
        val repositoryId = UUID.random()
        val storageAdapter = TestDfsStorageAdapter()
        val refAdapter = TestDfsRefAdapter()
        val repo = BoscaDfsRepositoryBuilder().apply {
            this.repositoryId = repositoryId
            this.storageAdapter = storageAdapter
            this.refAdapter = refAdapter
            repositoryDescription = DfsRepositoryDescription(repositoryId.toString())
        }.build()

        val inserter = repo.objectDatabase.newInserter()
        val blob = inserter.insert(Constants.OBJ_BLOB, "hello\n".toByteArray())
        inserter.flush()
        inserter.close()

        val packNamesBefore = storageAdapter.packs.values.map { it.packName }.toSet()
        assertTrue(packNamesBefore.size == 1, "expected exactly one pack from the inserter")

        compactNewlyReceivedPacks(repo, namesBefore = emptySet())

        val packNamesAfter = storageAdapter.packs.values.filter { it.committed }.map { it.packName }.toSet()
        assertEquals(packNamesBefore, packNamesAfter, "thick pack should not be compacted")
        // and the inserted blob is still readable
        val reader = repo.objectDatabase.newReader()
        assertTrue(reader.has(blob))
        reader.close()
    }

    @Test
    fun `inspect what becomes unreachable when only one ref is set`() {
        val packDir = Path.of(System.getProperty("user.home"), "Desktop/test")
        if (!packDir.exists()) {
            println("[ThinPackGcRepairTest] SKIP: fixture pack files not available at $packDir")
            return
        }

        val repositoryId = UUID.random()
        val storageAdapter = TestDfsStorageAdapter()
        val refAdapter = TestDfsRefAdapter()
        loadPacksFromDisk(packDir, repositoryId, storageAdapter)

        val repo = BoscaDfsRepositoryBuilder().apply {
            this.repositoryId = repositoryId
            this.storageAdapter = storageAdapter
            this.refAdapter = refAdapter
            repositoryDescription = DfsRepositoryDescription(repositoryId.toString())
        }.build()

        // Set only ONE ref — pick the LAST commit found, which is most likely to be
        // a leaf in the DAG (a tag tip, not the main branch tip). This simulates the
        // original test setup where my single picked ref didn't cover everything.
        val allCommits = findAllCommits(repo)
        val pickedCommit = allCommits.last()
        repo.refDatabase.newUpdate("refs/heads/main", true).apply {
            setNewObjectId(pickedCommit)
            update()
        }
        println("[inspect-unreachable] set refs/heads/main → ${pickedCommit.name()} (last of ${allCommits.size})")

        DfsGarbageCollector(repo).pack(NullProgressMonitor.INSTANCE)

        // Show every pack the GC left behind, so we can see whether UNREACHABLE_GARBAGE exists.
        for (pack in repo.objectDatabase.packs) {
            val desc = pack.packDescription
            println("[inspect-unreachable] post-GC pack: ${desc.packName}  source=${desc.packSource.name}  objectCount=${desc.objectCount}")
        }

        val reader = repo.objectDatabase.newReader()
        try {
            for (pack in repo.objectDatabase.packs) {
                if (pack.packDescription.packSource.name != "UNREACHABLE_GARBAGE") continue
                println("[inspect-unreachable] examining ${pack.packDescription.packName}")
                for (entry in pack.getPackIndex(reader)) {
                    val oid = entry.toObjectId()
                    val loader = reader.open(oid)
                    val typeName = when (loader.type) {
                        Constants.OBJ_COMMIT -> "commit"
                        Constants.OBJ_TREE -> "tree"
                        Constants.OBJ_BLOB -> "blob"
                        Constants.OBJ_TAG -> "tag"
                        else -> "type-${loader.type}"
                    }
                    val bytes = loader.bytes
                    println("[inspect-unreachable] ${oid.name()}  type=$typeName  size=${bytes.size}")
                    when (loader.type) {
                        Constants.OBJ_COMMIT, Constants.OBJ_TAG -> {
                            // Commits and tags are short text — print the whole thing.
                            println(bytes.toString(Charsets.UTF_8).prependIndent("    | "))
                        }
                        Constants.OBJ_TREE -> {
                            // Trees list filenames + child SHAs. Render entries readably.
                            val tw = org.eclipse.jgit.treewalk.TreeWalk(reader)
                            tw.addTree(oid)
                            tw.isRecursive = false
                            while (tw.next()) {
                                println("    | ${tw.fileMode}  ${tw.getObjectId(0).name()}  ${tw.pathString}")
                            }
                            tw.close()
                        }
                        Constants.OBJ_BLOB -> {
                            // Blobs may be binary — show a short ASCII preview.
                            val preview = bytes.take(120).toByteArray()
                                .toString(Charsets.UTF_8)
                                .replace(Regex("[\\x00-\\x08\\x0E-\\x1F]"), "·")
                            println("    | preview: $preview")
                        }
                    }
                }
            }
        } finally {
            reader.close()
        }
    }

    private fun findAllCommits(repo: BoscaDfsRepository): List<ObjectId> {
        val commits = mutableListOf<ObjectId>()
        val seen = HashSet<String>()
        val reader = repo.objectDatabase.newReader()
        try {
            for (pack in repo.objectDatabase.packs) {
                for (entry in pack.getPackIndex(reader)) {
                    val oid = entry.toObjectId()
                    if (!seen.add(oid.name())) continue
                    val type = try {
                        reader.open(oid).type
                    } catch (_: Exception) {
                        continue
                    }
                    if (type == Constants.OBJ_COMMIT) commits.add(oid)
                }
            }
            return commits
        } finally {
            reader.close()
        }
    }
}
