package bosca.git.service

import bosca.git.model.Repository
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Manages repository storage lifecycle: garbage collection to compact packfiles,
 * soft-delete purging after the retention period, backup/restore operations,
 * external repository import, and mirror fetching.
 */
interface RepositoryLifecycleService : Service {

    /**
     * Runs garbage collection on a repository, compacting loose objects into
     * packfiles and removing unreachable objects. Updates disk-size metrics
     * after completion.
     *
     * @return `true` if GC ran to completion; `false` if the cycle was skipped
     *   because the repository write lock was held by another writer. Callers
     *   must surface a skip (retry, requeue, or report an error) — GC that never
     *   runs lets replaced packs and garbage accumulate without bound.
     */
    suspend fun runGc(repositoryId: UUID): Boolean

    /**
     * Repairs a repository by running GC with bitmap generation disabled and
     * pack index v2. Used to recover from corrupt bitmap state or thin-pack
     * mismatches without the cost/risk of bitmap rebuilds.
     *
     * @return `true` if the repair ran to completion; `false` if it was skipped
     *   because the repository write lock was held by another writer.
     */
    suspend fun repair(repositoryId: UUID): Boolean

    /**
     * Permanently deletes repositories that were soft-deleted more than 30 days ago.
     * Removes all DFS packs from ObjectStorage, all refs and metadata from PostgreSQL,
     * and finally the repository row itself.
     */
    suspend fun purgeExpiredRepositories()

    /**
     * Physically removes the ObjectStorage files for packs that GC or thin-pack
     * compaction soft-deleted (replaced), once each pack's grace window has
     * elapsed. Run on a schedule; the grace window keeps replaced packs readable
     * long enough for any in-flight clone/fetch to complete before their storage
     * is reclaimed.
     */
    suspend fun reapDeletedPacks()

    /**
     * Creates a bundle file containing all refs and objects from the repository,
     * writes it to object storage, and returns the storage path for download.
     */
    suspend fun backup(repositoryId: UUID): String

    /**
     * Restores a repository from a backup bundle stored in object storage.
     * Creates a new repository record and fetches from the bundle into the DFS store.
     */
    suspend fun restore(
        backupPath: String,
        ownerId: UUID,
        slug: String,
        name: String
    ): Repository

    /**
     * Imports an external git repository by cloning it into a temporary in-memory
     * repository then copying all refs and objects into the Bosca DFS store.
     */
    suspend fun importRepository(
        cloneUrl: String,
        ownerId: UUID,
        slug: String,
        name: String
    ): Repository

    /**
     * Fetches new refs from the upstream mirror URL and updates the DFS repository.
     * Used by the periodic mirror fetch job to keep mirrored repositories in sync.
     */
    suspend fun mirrorFetch(repositoryId: UUID, upstreamUrl: String)
}
