package bosca.git.dfs

import bosca.db.transaction
import bosca.db.withConnectionManager
import bosca.git.repository.DfsPackRepository
import bosca.serialization.UUID
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Concrete [DfsStorageAdapter] that persists pack files to Bosca's [ObjectStorageService]
 * and tracks metadata in the `git.dfs_packs` / `git.dfs_pack_extensions` tables via
 * [DfsPackRepository].
 *
 * Methods are blocking because JGit invokes them synchronously. Callers run JGit
 * on [GitBlockingDispatcher]; [runBlocking] bridges each callback to the suspending
 * storage and repository services.
 */
class ObjectStorageDfsStorageAdapter(
    private val objectStorage: ObjectStorageService,
    private val packRepository: DfsPackRepository
) : DfsStorageAdapter {

    override fun listPacks(repositoryId: UUID): List<DfsPackInfo> = runBlocking {
        withConnectionManager {
            val packs = packRepository.findCommitted(repositoryId)
            if (packs.isEmpty()) return@withConnectionManager emptyList()
            val extensionsByPackId = packRepository.findExtensionsByRepository(repositoryId)
                .groupBy { it.packId }
            packs.mapNotNull { pack ->
                val extensions = extensionsByPackId[pack.id].orEmpty()
                if (extensions.isEmpty()) return@mapNotNull null
                DfsPackInfo(
                    id = pack.id,
                    repositoryId = pack.repositoryId,
                    packName = pack.packName,
                    packSource = pack.packSource,
                    fileSize = pack.fileSize,
                    objectCount = pack.objectCount,
                    deltaCount = pack.deltaCount,
                    extensions = extensions.associate { ext ->
                        ext.extension to DfsPackExtensionInfo(
                            extension = ext.extension,
                            fileSize = ext.fileSize,
                            storagePath = ext.storagePath
                        )
                    }
                )
            }
        }
    }

    override fun createPack(repositoryId: UUID, packName: String, source: String): DfsPackInfo = runBlocking {
        withConnectionManager {
            val pack = packRepository.create(
                DfsPack(repositoryId = repositoryId, packName = packName, packSource = source)
            )
            DfsPackInfo(
                id = pack.id,
                repositoryId = pack.repositoryId,
                packName = pack.packName,
                packSource = pack.packSource
            )
        }
    }

    override fun commitPacks(toCommit: List<DfsPackInfo>, toReplace: List<DfsPackInfo>) = runBlocking {
        // The commit of the new pack(s) and the retirement of the packs they
        // replace must be atomic: a partial swap can drop objects or leave a
        // committed row pointing at storage that is about to disappear. Replaced
        // packs are only SOFT-deleted here (deleted_at); their object-storage
        // files are removed later by the reaper, once no in-flight read can still
        // reference them. That keeps this an all-in-the-database transaction with
        // no irreversible side effects.
        withConnectionManager {
            transaction {
                for (info in toCommit) {
                    packRepository.commit(
                        DfsPack(
                            id = info.id,
                            repositoryId = info.repositoryId,
                            packName = info.packName,
                            packSource = info.packSource,
                            fileSize = info.fileSize,
                            objectCount = info.objectCount,
                            deltaCount = info.deltaCount
                        )
                    )
                }
                for (info in toReplace) {
                    packRepository.markDeleted(info.id)
                }
            }
        }
    }

    override fun rollbackPacks(packs: List<DfsPackInfo>) = runBlocking {
        // Soft-delete rather than deleting object-storage files inline. These
        // packs were never committed, so no reader can see them; the reaper
        // cleans up their metadata rows and any storage they wrote.
        withConnectionManager {
            transaction {
                for (info in packs) {
                    packRepository.markDeleted(info.id)
                }
            }
        }
    }

    override fun openFile(repositoryId: UUID, storagePath: String): InputStream = runBlocking {
        objectStorage.getInputStream(StringObjectPath(storagePath))
    }

    override fun openFileRange(repositoryId: UUID, storagePath: String, range: LongRange): InputStream = runBlocking {
        objectStorage.getInputStreamRange(StringObjectPath(storagePath), range)
    }

    override fun writeFile(packInfo: DfsPackInfo, extension: String, data: ByteArray): Unit = runBlocking {
        withConnectionManager {
            val storagePath = "git/${packInfo.repositoryId}/packs/${packInfo.packName}.$extension"
            objectStorage.setInputStream(
                StringObjectPath(storagePath),
                ByteArrayInputStream(data),
                data.size.toLong()
            )
            packRepository.upsertExtension(
                DfsPackExtension(
                    packId = packInfo.id,
                    extension = extension,
                    fileSize = data.size.toLong(),
                    storagePath = storagePath
                )
            )
            Unit
        }
    }
}
