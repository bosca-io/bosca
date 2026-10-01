package bosca.git.dfs

import bosca.serialization.UUID
import java.io.InputStream

/**
 * Bridges JGit's synchronous DFS object database callbacks to Bosca's
 * ObjectStorageService. Callers must run JGit, and so these blocking callbacks, on
 * [GitBlockingDispatcher]: never on a Netty event loop, and never on `Dispatchers.IO`, whose
 * threads the callbacks' `runBlocking` bridges may need.
 *
 * All methods are non-suspend because JGit invokes them synchronously.
 */
interface DfsStorageAdapter {

    /** Lists all committed packs for the given repository. */
    fun listPacks(repositoryId: UUID): List<DfsPackInfo>

    /** Creates a new pack record and returns its assigned ID. */
    fun createPack(repositoryId: UUID, packName: String, source: String): DfsPackInfo

    /** Marks packs as committed and removes replaced packs. */
    fun commitPacks(toCommit: List<DfsPackInfo>, toReplace: List<DfsPackInfo>)

    /** Deletes uncommitted pack records (rollback on failure). */
    fun rollbackPacks(packs: List<DfsPackInfo>)

    /** Opens a readable stream for a pack extension file from ObjectStorage. */
    fun openFile(repositoryId: UUID, storagePath: String): InputStream

    /**
     * Opens a readable stream for a byte range of a pack extension file from ObjectStorage.
     * The range is inclusive on both ends.
     */
    fun openFileRange(repositoryId: UUID, storagePath: String, range: LongRange): InputStream

    /** Writes data to a pack extension file in ObjectStorage and records it in the database. */
    fun writeFile(packInfo: DfsPackInfo, extension: String, data: ByteArray)
}

/**
 * Carries pack metadata between the DFS adapter and JGit's [DfsPackDescription].
 * Represents a single pack in the `git.dfs_packs` table.
 */
data class DfsPackInfo(
    val id: UUID,
    val repositoryId: UUID,
    val packName: String,
    val packSource: String,
    val fileSize: Long = 0L,
    val objectCount: Long = 0L,
    val deltaCount: Long = 0L,
    val extensions: Map<String, DfsPackExtensionInfo> = emptyMap()
)

/**
 * Metadata for a single pack extension file (`.pack`, `.idx`, `.bitmap`).
 */
data class DfsPackExtensionInfo(
    val extension: String,
    val fileSize: Long,
    val storagePath: String
)
