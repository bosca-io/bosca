package bosca.git.dfs

import bosca.db.withConnectionManager
import bosca.git.model.DfsRef
import bosca.git.repository.DfsRefRepository
import bosca.serialization.UUID
import kotlinx.coroutines.runBlocking

/**
 * Concrete [DfsRefAdapter] backed by the `git.dfs_refs` PostgreSQL table.
 * Compare-and-swap operations use Postgres row-level locking for atomicity,
 * making concurrent pushes from multiple git-server pods safe without
 * distributed lock coordination.
 */
class PostgresDfsRefAdapter(
    private val refRepository: DfsRefRepository
) : DfsRefAdapter {

    override fun scanRefs(repositoryId: UUID): List<DfsRefInfo> = runBlocking {
        withConnectionManager {
            refRepository.findAll(repositoryId).map { ref ->
                DfsRefInfo(
                    name = ref.name,
                    objectId = ref.objectId,
                    peeledId = ref.peeledId,
                    symbolicTarget = ref.symbolicTarget
                )
            }
        }
    }

    override fun compareAndPut(
        repositoryId: UUID,
        name: String,
        expectedOldId: String?,
        newId: String,
        peeledId: String?,
        symbolicTarget: String?
    ): Boolean = runBlocking {
        withConnectionManager {
            if (expectedOldId == null) {
                val existing = refRepository.findByName(repositoryId, name)
                if (existing != null) return@withConnectionManager false
                refRepository.upsert(
                    DfsRef(
                        repositoryId = repositoryId,
                        name = name,
                        objectId = newId,
                        peeledId = peeledId,
                        symbolicTarget = symbolicTarget
                    )
                )
                true
            } else {
                val updated = refRepository.compareAndSwap(repositoryId, name, expectedOldId, newId)
                updated != null
            }
        }
    }

    override fun compareAndRemove(repositoryId: UUID, name: String, expectedOldId: String): Boolean = runBlocking {
        withConnectionManager {
            val existing = refRepository.findByName(repositoryId, name)
            if (existing == null || existing.objectId != expectedOldId) return@withConnectionManager false
            refRepository.delete(repositoryId, name)
            true
        }
    }
}
