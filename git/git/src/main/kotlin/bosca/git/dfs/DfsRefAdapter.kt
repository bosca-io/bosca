package bosca.git.dfs

import bosca.serialization.UUID

/**
 * Bridges JGit's synchronous ref database callbacks to Bosca's PostgreSQL-backed
 * `git.dfs_refs` table. Provides atomic compare-and-swap for safe concurrent
 * ref updates from multiple git-server pods.
 *
 * All methods are non-suspend — implementations use blocking coroutine bridges
 * internally since JGit invokes them synchronously.
 */
interface DfsRefAdapter {

    /** Returns all refs for the given repository. */
    fun scanRefs(repositoryId: UUID): List<DfsRefInfo>

    /**
     * Atomically updates a ref if its current value matches [expectedOldId].
     * Returns `true` if the swap succeeded, `false` if the ref was concurrently modified.
     * A null [expectedOldId] means the ref must not exist (create case).
     */
    fun compareAndPut(repositoryId: UUID, name: String, expectedOldId: String?, newId: String, peeledId: String?, symbolicTarget: String?): Boolean

    /**
     * Atomically removes a ref if its current value matches [expectedOldId].
     * Returns `true` if the removal succeeded.
     */
    fun compareAndRemove(repositoryId: UUID, name: String, expectedOldId: String): Boolean
}

/**
 * A ref as stored in the `git.dfs_refs` table.
 */
data class DfsRefInfo(
    val name: String,
    val objectId: String,
    val peeledId: String? = null,
    val symbolicTarget: String? = null
)
