package bosca.git.dfs

import bosca.db.ConnectionManager
import bosca.git.repository.DfsPackRepository
import bosca.git.repository.DfsRefRepository
import bosca.serialization.UUID
import bosca.storage.service.ObjectStorageService
import org.eclipse.jgit.internal.storage.dfs.DfsReaderOptions
import org.eclipse.jgit.internal.storage.dfs.DfsRepository
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription

/**
 * Factory for creating [DfsRepository] instances bound to a specific
 * repository ID. Each instance shares the same underlying ObjectStorage and
 * database connections but is scoped to a single git repository's namespace.
 *
 * Instances are lightweight and should not be cached long-term — create one
 * per request or operation, then close it when done.
 */
open class BoscaDfsRepositoryManager(
    private val objectStorage: ObjectStorageService,
    private val packRepository: DfsPackRepository,
    private val refRepository: DfsRefRepository
) {

    private val readerOptions = DfsReaderOptions().apply {
        setStreamFileThreshold(STREAM_FILE_THRESHOLD)
        setStreamPackBufferSize(STREAM_PACK_BUFFER_SIZE)
    }

    /**
     * Opens a [DfsRepository] for the given repository ID. The returned
     * repository reads/writes packs from ObjectStorage at path
     * `git/{repositoryId}/packs/` and refs from the `git.dfs_refs` table.
     */
    open fun open(repositoryId: UUID): DfsRepository {
        return open(repositoryId, null)
    }

    /**
     * Opens refs on the caller's connection so ref changes can commit with domain state and events.
     * Pack storage retains its ordinary independent lifecycle; the caller owns the connection.
     */
    open fun open(repositoryId: UUID, refConnectionManager: ConnectionManager?): DfsRepository {
        val storageAdapter = ObjectStorageDfsStorageAdapter(objectStorage, packRepository)
        val refAdapter = PostgresDfsRefAdapter(refRepository, refConnectionManager)

        return BoscaDfsRepositoryBuilder().apply {
            this.repositoryId = repositoryId
            this.storageAdapter = storageAdapter
            this.refAdapter = refAdapter
            this.readerOptions = this@BoscaDfsRepositoryManager.readerOptions
            repositoryDescription = DfsRepositoryDescription(repositoryId.toString())
        }.build()
    }

    companion object {
        private const val STREAM_FILE_THRESHOLD = 16 * 1024 * 1024
        private const val STREAM_PACK_BUFFER_SIZE = 16 * 1024 * 1024
    }
}
