package bosca.git.dfs

import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription
import org.eclipse.jgit.internal.storage.dfs.DfsReaderOptions
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.ReadableChannel
import org.eclipse.jgit.internal.storage.file.PackIndex
import org.eclipse.jgit.internal.storage.pack.PackExt
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * [DfsObjDatabase] implementation that stores packfiles in Bosca's ObjectStorage
 * and tracks pack metadata in PostgreSQL. Each pack extension (`.pack`, `.idx`,
 * `.bitmap`) is a separate object in storage, addressed by a path like
 * `git/{repositoryId}/packs/{packName}.{ext}`.
 */
class BoscaDfsObjDatabase(
    private val repo: BoscaDfsRepository,
    private val storageAdapter: DfsStorageAdapter,
    readerOptions: DfsReaderOptions
) : DfsObjDatabase(repo, readerOptions) {

    // packInfoByName maps JGit's pack name to our DB-side metadata (notably the
    // pack's UUID, which we need for write/commit/rollback). Because the enclosing
    // BoscaDfsRepository is shared across requests via the cache in
    // BoscaDfsRepositoryManager, this map outlives a single request — so:
    //   - listPacks() rebuilds the committed entries from the DB and evicts any
    //     committed-name entries that no longer exist there (e.g. compacted away
    //     by GC). Uncommitted (in-flight) entries are preserved across listPacks.
    //   - newPack() / writeFile() add or update entries that are not yet committed;
    //     these names are tracked in uncommittedPackNames so listPacks won't drop
    //     them while a push is still in progress.
    //   - commitPackImpl / rollbackPack remove finalized entries from both maps.
    private val packInfoByName = ConcurrentHashMap<String, DfsPackInfo>()
    private val uncommittedPackNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Optional check invoked at the start of [commitPackImpl], before any pack
     * row is committed or replaced. Repository write-lock holders install
     * [bosca.git.service.RepositoryWriteLockHandle.ensureHeld] here so a pack
     * swap computed under exclusivity can never commit after the lock is lost —
     * JGit runs the swap inside one blocking call that coroutine cancellation
     * cannot interrupt, so the lock-lost abort alone cannot stop it. A thrown
     * exception aborts the commit with nothing written.
     */
    @Volatile
    var commitPacksFence: (() -> Unit)? = null

    override fun listPacks(): List<DfsPackDescription> {
        val packs = storageAdapter.listPacks(repo.repositoryId)
        val freshNames = HashSet<String>(packs.size)
        val descriptions = packs.map { pack ->
            val info = pack.withObjectCount()
            packInfoByName[info.packName] = info
            freshNames.add(info.packName)
            info.toDescription()
        }
        packInfoByName.keys.removeIf { name -> name !in freshNames && name !in uncommittedPackNames }
        return descriptions
    }

    override fun newPack(source: PackSource): DfsPackDescription {
        val packName = "pack-${System.nanoTime()}-${packCounter.incrementAndGet()}-${source.name}"
        val info = storageAdapter.createPack(repo.repositoryId, packName, source.name)
        packInfoByName[info.packName] = info
        uncommittedPackNames.add(info.packName)
        return info.toDescription()
    }

    override fun commitPackImpl(
        desc: Collection<DfsPackDescription>,
        replace: Collection<DfsPackDescription>?
    ) {
        commitPacksFence?.invoke()
        val toCommit = desc.map { description ->
            description.packInfo().copy(
                fileSize = description.getFileSize(PackExt.PACK),
                objectCount = description.objectCount,
                deltaCount = description.deltaCount,
            )
        }
        val toReplace = replace?.map { it.packInfo() } ?: emptyList()
        storageAdapter.commitPacks(toCommit, toReplace)
        for (info in toCommit) {
            packInfoByName[info.packName] = info
            uncommittedPackNames.remove(info.packName)
        }
        for (info in toReplace) {
            packInfoByName.remove(info.packName)
            uncommittedPackNames.remove(info.packName)
        }
        clearCache()
    }

    override fun rollbackPack(desc: Collection<DfsPackDescription>) {
        val infos = desc.map { it.packInfo() }
        storageAdapter.rollbackPacks(infos)
        for (info in infos) {
            packInfoByName.remove(info.packName)
            uncommittedPackNames.remove(info.packName)
        }
    }

    override fun openFile(desc: DfsPackDescription, ext: PackExt): ReadableChannel {
        val storagePath = "git/${repo.repositoryId}/packs/${desc.packName}.${ext.getExtension()}"
        val size = desc.getFileSize(ext)
            .takeIf { it > 0 }
            ?: packInfoByName[desc.packName]?.extensions?.get(ext.getExtension())?.fileSize
            ?: throw IllegalStateException("Unknown size for pack ${desc.packName}.${ext.getExtension()}")
        return RangedObjectReadableChannel(storageAdapter, repo.repositoryId, storagePath, size).also { channel ->
            if (ext != PackExt.PACK) channel.setReadAheadBytes(INDEX_READ_AHEAD)
        }
    }

    override fun writeFile(desc: DfsPackDescription, ext: PackExt): DfsOutputStream {
        val packName = desc.packName
        return object : DfsOutputStream() {
            private val buffer = ByteArrayOutputStream()

            override fun write(buf: ByteArray, off: Int, len: Int) {
                buffer.write(buf, off, len)
            }

            override fun read(position: Long, buf: ByteBuffer): Int {
                val data = buffer.toByteArray()
                val pos = position.toInt()
                if (pos >= data.size) return -1
                val len = minOf(buf.remaining(), data.size - pos)
                buf.put(data, pos, len)
                return len
            }

            override fun blockSize(): Int = 0

            override fun flush() {
            }

            override fun close() {
                val currentInfo = packInfoByName[packName]
                    ?: throw IllegalStateException("No DfsPackInfo found for pack: $packName")
                val data = buffer.toByteArray()
                if (data.isEmpty()) return
                storageAdapter.writeFile(currentInfo, ext.getExtension(), data)
                val storagePath = "git/${currentInfo.repositoryId}/packs/${packName}.${ext.getExtension()}"
                packInfoByName[packName] = currentInfo.copy(
                    extensions = currentInfo.extensions + (ext.getExtension() to DfsPackExtensionInfo(
                        extension = ext.getExtension(),
                        fileSize = data.size.toLong(),
                        storagePath = storagePath
                    ))
                )
            }
        }
    }

    override fun getApproximateObjectCount(): Long {
        // Use JGit's cached pack list rather than re-querying the DB on every call.
        return getPacks().sumOf { it.packDescription.objectCount }
    }

    /**
     * Returns true when the `.pack` file's header object count (4 bytes at offset 8)
     * does not match the `.idx` object count. JGit's [DfsPackParser] deliberately
     * leaves the header count unchanged when it thickens a thin pack on receive
     * (see its `onEndThinPack`), so this is the signature of a stored pack that
     * needs compaction to become self-contained.
     *
     * Reads only 4 bytes from object storage via a single range request, so the
     * detection cost is dominated by the round-trip — typically tens of ms in
     * production, microseconds in tests.
     */
    fun isThinPack(packName: String): Boolean {
        val info = packInfoByName[packName] ?: return false
        val packExt = info.extensions[PackExt.PACK.getExtension()] ?: return false
        val idxCount = info.objectCount
        if (idxCount <= 0L) return false
        val headerCount = readPackHeaderObjectCount(packExt.storagePath) ?: return false
        return headerCount != idxCount
    }

    private fun readPackHeaderObjectCount(storagePath: String): Long? {
        val countRange = PACK_HEADER_COUNT_OFFSET.toLong()..(PACK_HEADER_COUNT_OFFSET + 3L)
        val stream = try {
            storageAdapter.openFileRange(repo.repositoryId, storagePath, countRange)
        } catch (_: Exception) {
            return null
        }
        val bytes = stream.use { it.readNBytes(4) }
        if (bytes.size != 4) return null
        return ByteBuffer.wrap(bytes).int.toLong() and 0xFFFFFFFFL
    }

    private fun DfsPackInfo.withObjectCount(): DfsPackInfo {
        if (objectCount != 0L) return this
        val index = extensions[PackExt.INDEX.extension] ?: return this
        // Cached-pack reuse writes the description's count into the outgoing
        // pack header. The index is authoritative when that metadata is missing;
        // a received thin pack's own header can exclude appended base objects.
        val count = storageAdapter.openFile(repositoryId, index.storagePath).use {
            PackIndex.read(it).objectCount
        }
        return copy(objectCount = count)
    }

    private fun DfsPackInfo.toDescription(): DfsPackDescription {
        val desc = DfsPackDescription(
            DfsRepositoryDescription(repo.repositoryId.toString()),
            packName,
            PackSource.valueOf(packSource)
        )
        desc.objectCount = objectCount
        desc.deltaCount = deltaCount
        for ((ext, extInfo) in extensions) {
            val packExt = resolvePackExt(ext) ?: continue
            desc.addFileExt(packExt)
            desc.setFileSize(packExt, extInfo.fileSize)
        }
        return desc
    }

    private fun DfsPackDescription.packInfo(): DfsPackInfo {
        return packInfoByName[packName]
            ?: throw IllegalStateException("No DfsPackInfo found for pack: $packName")
    }

    companion object {
        private val packCounter = AtomicLong(0)
        private val PACK_EXT_BY_NAME = PackExt.entries.associateBy { it.getExtension() }
        private const val INDEX_READ_AHEAD = 16 * 1024 * 1024

        private fun resolvePackExt(extension: String): PackExt? = PACK_EXT_BY_NAME[extension]

        // Offset of the 4-byte big-endian object count in a v2 pack file header
        // ("PACK" + 4-byte version + 4-byte object count).
        private const val PACK_HEADER_COUNT_OFFSET = 8
    }
}

/**
 * A [ReadableChannel] that serves pack data from ObjectStorage via range reads.
 *
 * JGit opens a short-lived channel for each cache miss, so the default buffer
 * holds one cache block. Sequential pack copies and index files request a larger
 * reusable buffer through [setReadAheadBytes].
 */
private class RangedObjectReadableChannel(
    private val storageAdapter: DfsStorageAdapter,
    private val repositoryId: bosca.serialization.UUID,
    private val storagePath: String,
    private val totalSize: Long
) : ReadableChannel {

    private var position: Long = 0
    private var open = true
    private var readAheadBytes: Int = DEFAULT_READ_AHEAD

    private var bufferStart: Long = -1L
    private var bufferData: ByteArray = ByteArray(0)

    @Synchronized
    override fun read(dst: ByteBuffer): Int {
        if (!open) throw ClosedChannelException()
        if (position >= totalSize) return -1

        val bufferEnd = if (bufferStart >= 0) bufferStart + bufferData.size else -1L
        if (bufferStart < 0 || position < bufferStart || position >= bufferEnd) {
            fillBuffer()
        }

        val bufOffset = (position - bufferStart).toInt()
        val available = bufferData.size - bufOffset
        if (available <= 0) return -1
        val toRead = minOf(available, dst.remaining())
        dst.put(bufferData, bufOffset, toRead)
        position += toRead
        return toRead
    }

    private fun fillBuffer() {
        val rangeSize = readAheadBytes.coerceAtLeast(MIN_RANGE_SIZE).toLong()
        val endInclusive = minOf(position + rangeSize - 1, totalSize - 1)
        val length = (endInclusive - position + 1).toInt()
        val data = ByteArray(length)
        storageAdapter.openFileRange(repositoryId, storagePath, position..endInclusive).use { stream ->
            var off = 0
            while (off < length) {
                val n = stream.read(data, off, length - off)
                if (n == -1) break
                off += n
            }
            bufferData = if (off == length) data else data.copyOf(off)
        }
        bufferStart = position
    }

    @Synchronized
    override fun isOpen(): Boolean = open

    @Synchronized
    override fun close() {
        open = false
        bufferData = ByteArray(0)
        bufferStart = -1L
    }

    @Synchronized
    override fun position(): Long = position

    @Synchronized
    override fun position(newPosition: Long) {
        position = newPosition.coerceAtLeast(0)
    }

    override fun size(): Long = totalSize

    override fun blockSize(): Int = BLOCK_SIZE

    @Synchronized
    override fun setReadAheadBytes(bufferSize: Int) {
        if (bufferSize > 0) readAheadBytes = bufferSize
    }

    companion object {
        private const val BLOCK_SIZE = 512 * 1024
        private const val MIN_RANGE_SIZE = BLOCK_SIZE
        private const val DEFAULT_READ_AHEAD = MIN_RANGE_SIZE
    }
}
