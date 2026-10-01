package bosca.git

import bosca.git.dfs.DfsPackExtensionInfo
import bosca.git.dfs.DfsPackInfo
import bosca.git.dfs.DfsRefAdapter
import bosca.git.dfs.DfsRefInfo
import bosca.git.dfs.DfsStorageAdapter
import bosca.serialization.UUID
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory [DfsStorageAdapter] for integration testing.
 * Simulates the real ObjectStorage + PostgreSQL backing with [ConcurrentHashMap]
 * collections and `@Synchronized` methods, providing the same consistency
 * guarantees that the production Postgres-backed adapter delivers via
 * row-level locking and transactional commits.
 */
class TestDfsStorageAdapter : DfsStorageAdapter {
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

    val packs = ConcurrentHashMap<UUID, PackRecord>()
    val extensions = ConcurrentHashMap<String, DfsPackExtensionInfo>()
    private val extensionPackIds = ConcurrentHashMap<String, UUID>()
    val files = ConcurrentHashMap<String, ByteArray>()

    fun countCommittedPacks(repositoryId: UUID): Int {
        return packs.values.count { it.repositoryId == repositoryId && it.committed }
    }

    @Synchronized
    override fun listPacks(repositoryId: UUID): List<DfsPackInfo> {
        val committed = packs.values.filter { it.repositoryId == repositoryId && it.committed }
        val result = mutableListOf<DfsPackInfo>()
        for (pack in committed) {
            val exts = extensions.entries
                .filter { extensionPackIds[it.key] == pack.id }
                .associate { it.value.extension to it.value }
            if (exts.isEmpty()) {
                packs.remove(pack.id)
                continue
            }
            result.add(
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
            )
        }
        return result
    }

    @Synchronized
    override fun createPack(repositoryId: UUID, packName: String, source: String): DfsPackInfo {
        val id = UUID.random()
        packs[id] = PackRecord(id, repositoryId, packName, source)
        return DfsPackInfo(id = id, repositoryId = repositoryId, packName = packName, packSource = source)
    }

    @Synchronized
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
            extensions.entries.removeIf { extensionPackIds[it.key] == info.id }
            extensionPackIds.entries.removeIf { it.value == info.id }
            packs.remove(info.id)
        }
    }

    @Synchronized
    override fun rollbackPacks(packs: List<DfsPackInfo>) {
        for (info in packs) {
            extensions.entries.removeIf { extensionPackIds[it.key] == info.id }
            extensionPackIds.entries.removeIf { it.value == info.id }
            this.packs.remove(info.id)
        }
    }

    @Synchronized
    override fun openFile(repositoryId: UUID, storagePath: String): InputStream {
        val data = files[storagePath]
            ?: throw java.io.FileNotFoundException("No file at $storagePath")
        return ByteArrayInputStream(data)
    }

    @Synchronized
    override fun openFileRange(repositoryId: UUID, storagePath: String, range: LongRange): InputStream {
        val data = files[storagePath]
            ?: throw java.io.FileNotFoundException("No file at $storagePath")
        val from = range.first.toInt().coerceIn(0, data.size)
        val toExclusive = (range.last.toInt() + 1).coerceIn(from, data.size)
        return ByteArrayInputStream(data, from, toExclusive - from)
    }

    @Synchronized
    override fun writeFile(packInfo: DfsPackInfo, extension: String, data: ByteArray) {
        val storagePath = "git/${packInfo.repositoryId}/packs/${packInfo.packName}.$extension"
        files[storagePath] = data
        val key = "${packInfo.id}:$extension"
        val extInfo = DfsPackExtensionInfo(
            extension = extension,
            fileSize = data.size.toLong(),
            storagePath = storagePath
        )
        extensions[key] = extInfo
        extensionPackIds[key] = packInfo.id
    }
}

/**
 * Thread-safe in-memory [DfsRefAdapter] for integration testing.
 * Uses `@Synchronized` to simulate Postgres row-level locking and
 * provide the same compare-and-swap atomicity guarantees that prevent
 * concurrent pushes from overwriting each other's ref updates.
 */
class TestDfsRefAdapter : DfsRefAdapter {
    data class RefRecord(val name: String, val objectId: String, val peeledId: String?, val symbolicTarget: String?)

    private val refs = ConcurrentHashMap<String, RefRecord>()

    private fun key(repositoryId: UUID, name: String) = "$repositoryId:$name"

    override fun scanRefs(repositoryId: UUID): List<DfsRefInfo> {
        return refs.entries
            .filter { it.key.startsWith("$repositoryId:") }
            .map { DfsRefInfo(it.value.name, it.value.objectId, it.value.peeledId, it.value.symbolicTarget) }
    }

    @Synchronized
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

    @Synchronized
    override fun compareAndRemove(repositoryId: UUID, name: String, expectedOldId: String): Boolean {
        val k = key(repositoryId, name)
        val existing = refs[k] ?: return false
        if (existing.objectId != expectedOldId) return false
        refs.remove(k)
        return true
    }
}
