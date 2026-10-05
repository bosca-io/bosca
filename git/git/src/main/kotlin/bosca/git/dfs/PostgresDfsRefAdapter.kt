package bosca.git.dfs

import bosca.db.withConnectionManager
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.git.model.DfsRef
import bosca.git.repository.DfsRefRepository
import bosca.serialization.UUID
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Concrete [DfsRefAdapter] backed by the `git.dfs_refs` PostgreSQL table.
 * Compare-and-swap operations use Postgres row-level locking for atomicity,
 * making concurrent pushes from multiple git-server pods safe without
 * distributed lock coordination.
 */
class PostgresDfsRefAdapter(
    private val refRepository: DfsRefRepository,
    private val connectionManager: ConnectionManager? = null,
) : DfsRefAdapter {

    /** The caller owns a supplied transaction; ordinary Git callbacks use their own connection. */
    private fun <T> withConnection(block: suspend () -> T): T =
        runBlocking(connectionManager?.asCoroutineContext() ?: EmptyCoroutineContext) {
            if (connectionManager == null) withConnectionManager(block) else block()
        }

    override fun scanRefs(repositoryId: UUID): List<DfsRefInfo> = withConnection {
        refRepository.findAll(repositoryId).map { ref ->
            DfsRefInfo(
                name = ref.name,
                objectId = ref.objectId,
                peeledId = ref.peeledId,
                symbolicTarget = ref.symbolicTarget
            )
        }
    }

    override fun compareAndPut(
        repositoryId: UUID,
        name: String,
        expectedOldId: String?,
        newId: String,
        peeledId: String?,
        symbolicTarget: String?
    ): Boolean = withConnection {
        if (expectedOldId == null) {
            val existing = refRepository.findByName(repositoryId, name)
            if (existing != null) return@withConnection false
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

    override fun compareAndRemove(repositoryId: UUID, name: String, expectedOldId: String): Boolean = withConnection {
        val existing = refRepository.findByName(repositoryId, name)
        if (existing == null || existing.objectId != expectedOldId) return@withConnection false
        refRepository.delete(repositoryId, name)
        true
    }
}
