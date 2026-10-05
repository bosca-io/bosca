package bosca.git.service

import bosca.git.dfs.BoscaDfsObjDatabase
import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.dfs.GitBlockingDispatcher
import bosca.git.dfs.GitWorkDispatcher
import bosca.git.model.Repository
import bosca.git.model.Visibility
import bosca.git.repository.DfsPackRepository
import bosca.git.repository.DfsRefRepository
import bosca.git.repository.GitRepositoryRepository
import bosca.lock.DistributedLockFactory
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.dfs.DfsGarbageCollector
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.pack.PackConfig
import org.eclipse.jgit.transport.BundleWriter
import org.eclipse.jgit.transport.RefSpec
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Handles repository storage lifecycle operations including GC compaction,
 * soft-delete purging, bundle backup/restore, external import, and mirror sync.
 */
@ServiceImplementation
class RepositoryLifecycleServiceImpl(
    private val repositoryRepository: GitRepositoryRepository,
    private val packRepository: DfsPackRepository,
    private val refRepository: DfsRefRepository,
    private val objectStorage: ObjectStorageService,
    private val dfsManager: BoscaDfsRepositoryManager,
    private val lockFactory: DistributedLockFactory
) : RepositoryLifecycleService {

    private val log = LoggerFactory.getLogger(RepositoryLifecycleServiceImpl::class.java)

    override suspend fun runGc(repositoryId: UUID): Boolean {
        // Hold the per-repository write lock across the whole GC so its
        // reachability computation cannot race a concurrent writer (which could
        // otherwise make GC drop a still-reachable object). Reads are unaffected
        // — they run lock-free and stay safe via deferred pack reaping.
        val ran = lockFactory.withRepositoryWriteLock(repositoryId, RepositoryWriteLock.MAINTENANCE_WAIT_MILLIS) { lockHandle ->
            withContext(GitWorkDispatcher) {
                val repo = dfsManager.open(repositoryId)
                repo.use {
                    // gc.pack() commits its pack swap inside one blocking JGit call
                    // that coroutine cancellation cannot interrupt; the fence
                    // re-verifies lock ownership at the swap point so a swap computed
                    // from a stale ref snapshot aborts instead of committing.
                    (it.objectDatabase as? BoscaDfsObjDatabase)?.commitPacksFence = lockHandle::ensureHeld
                    val gc = DfsGarbageCollector(it)
                    gc.pack(NullProgressMonitor.INSTANCE)
                    val sizeBytes = packRepository.sumPackSizeBytes(repositoryId)
                    repositoryRepository.updateDiskSize(repositoryId, sizeBytes)
                    log.info("GC completed for repository {}, size: {} bytes", repositoryId, sizeBytes)
                }
            }
        } != null
        if (!ran) {
            log.warn(
                "Skipped GC for repository {}: write lock held by another writer after waiting {}ms",
                repositoryId, RepositoryWriteLock.MAINTENANCE_WAIT_MILLIS
            )
        }
        return ran
    }

    override suspend fun repair(repositoryId: UUID): Boolean {
        val ran = lockFactory.withRepositoryWriteLock(repositoryId, RepositoryWriteLock.MAINTENANCE_WAIT_MILLIS) { lockHandle ->
            withContext(GitWorkDispatcher) {
                val repo = dfsManager.open(repositoryId)
                repo.use {
                    (it.objectDatabase as? BoscaDfsObjDatabase)?.commitPacksFence = lockHandle::ensureHeld
                    val gc = DfsGarbageCollector(it)
                    val cfg = PackConfig(it)
                    cfg.indexVersion = 2
                    cfg.setBuildBitmaps(false)
                    gc.setPackConfig(cfg)
                    gc.pack(NullProgressMonitor.INSTANCE)
                    val sizeBytes = packRepository.sumPackSizeBytes(repositoryId)
                    repositoryRepository.updateDiskSize(repositoryId, sizeBytes)
                    log.info("Repair completed for repository {}, size: {} bytes", repositoryId, sizeBytes)
                }
            }
        } != null
        if (!ran) {
            log.warn(
                "Skipped repair for repository {}: write lock held by another writer after waiting {}ms",
                repositoryId, RepositoryWriteLock.MAINTENANCE_WAIT_MILLIS
            )
        }
        return ran
    }

    override suspend fun reapDeletedPacks() {
        val batch = packRepository.findReapable(PACK_REAP_GRACE_SECONDS, PACK_REAP_BATCH_SIZE)
        if (batch.isEmpty()) return
        var reaped = 0
        for (pack in batch) {
            try {
                // Delete the object-storage files first; only drop the metadata
                // row once storage is gone. If a delete fails, the row survives
                // and the pack is retried on the next run — never a row that
                // points at storage we failed to remove.
                val extensions = packRepository.findExtensions(pack.id)
                withContext(GitWorkDispatcher) {
                    for (ext in extensions) {
                        objectStorage.delete(StringObjectPath(ext.storagePath))
                    }
                }
                packRepository.delete(pack.id)
                reaped++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to reap soft-deleted pack {} for repository {}", pack.id, pack.repositoryId, e)
            }
        }
        log.info("Reaped {} of {} soft-deleted pack(s) past the {}s grace window", reaped, batch.size, PACK_REAP_GRACE_SECONDS)
        if (batch.size == PACK_REAP_BATCH_SIZE) {
            log.info("Pack reap batch was full ({}); remaining packs will be reaped on the next scheduled run", PACK_REAP_BATCH_SIZE)
        }
    }

    override suspend fun purgeExpiredRepositories() {
        val expired = repositoryRepository.findExpiredSoftDeletes()
        for (repository in expired) {
            try {
                purgeRepository(repository.id)
                log.info("Purged expired repository: {}", repository.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to purge repository {}", repository.id, e)
            }
        }
        if (expired.isNotEmpty()) {
            log.info("Purged {} expired repositories", expired.size)
        }
    }

    override suspend fun backup(repositoryId: UUID): String {
        val bundleBytes = withContext(GitWorkDispatcher) {
            dfsManager.open(repositoryId).use { repo ->
                val out = ByteArrayOutputStream()
                val writer = BundleWriter(repo)
                val allRefs = repo.refDatabase.refs
                for (ref in allRefs) {
                    writer.include(ref)
                }
                writer.writeBundle(NullProgressMonitor.INSTANCE, out)
                out.toByteArray()
            }
        }

        val path = StringObjectPath("git-backups/$repositoryId/backup.bundle")
        withContext(GitWorkDispatcher) {
            objectStorage.setInputStream(path, ByteArrayInputStream(bundleBytes), bundleBytes.size.toLong())
        }
        log.info("Backup created for repository {} ({} bytes)", repositoryId, bundleBytes.size)
        return path.toString()
    }

    override suspend fun restore(
        backupPath: String,
        ownerId: UUID,
        slug: String,
        name: String
    ): Repository = withContext(GitWorkDispatcher) {
        val storagePath = StringObjectPath(backupPath)
        val bundleBytes = objectStorage.getInputStream(storagePath).use { it.readBytes() }

        val repository = repositoryRepository.create(
            Repository(
                slug = slug,
                name = name,
                ownerId = ownerId,
                visibility = Visibility.PRIVATE,
                defaultBranch = "main"
            )
        )

        val tempFile = File.createTempFile("git-restore-", ".bundle")
        val tempDir = File.createTempFile("git-restore-repo-", ".git")
        tempDir.delete()
        try {
            tempFile.writeBytes(bundleBytes)
            val tempRepo = org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(tempDir).call().repository
            try {
                val git = Git.wrap(tempRepo)
                git.fetch()
                    .setRemote(tempFile.toURI().toString())
                    .setRefSpecs(RefSpec("+refs/*:refs/*"))
                    .call()

                // The new repository row is already visible to scheduled GC, so
                // the DFS writes must hold the write lock like every other writer.
                lockFactory.withRepositoryWriteLock(repository.id, RepositoryWriteLock.MAINTENANCE_WAIT_MILLIS) { lockHandle ->
                    val dfsRepo = dfsManager.open(repository.id)
                    lockHandle.fence(dfsRepo)
                    dfsRepo.use { target ->
                        for (ref in tempRepo.refDatabase.refs) {
                            val refUpdate = target.refDatabase.newUpdate(ref.name, true)
                            refUpdate.setNewObjectId(ref.objectId)
                            refUpdate.setExpectedOldObjectId(ObjectId.zeroId())
                            refUpdate.update()
                        }
                        val sizeBytes = packRepository.sumPackSizeBytes(repository.id)
                        repositoryRepository.updateDiskSize(repository.id, sizeBytes)
                    }
                } ?: throw RepositoryWriteBusyException(repository.id)
            } finally {
                tempRepo.close()
            }
        } catch (e: Throwable) {
            // A cancelled restore leaves the same partial record as a failed one, so both clean up;
            // the cleanup must not be cancelled with it, and the cause is always rethrown.
            if (e is CancellationException) {
                log.warn("Restore of repository from {} was cancelled, cleaning up record {}", backupPath, repository.id)
            } else {
                log.error("Failed to restore repository from {}, cleaning up record {}", backupPath, repository.id, e)
            }
            withContext(NonCancellable) {
                try {
                    purgeRepository(repository.id)
                } catch (cleanupError: Exception) {
                    log.error("Failed to clean up partially restored repository {}", repository.id, cleanupError)
                    e.addSuppressed(cleanupError)
                }
            }
            throw e
        } finally {
            tempFile.delete()
            tempDir.deleteRecursively()
        }

        log.info("Restored repository from {} as {}/{}", backupPath, ownerId, slug)
        repository
    }

    override suspend fun importRepository(
        cloneUrl: String,
        ownerId: UUID,
        slug: String,
        name: String
    ): Repository = withContext(GitWorkDispatcher) {
        val tempDir = File.createTempFile("git-import-", ".git")
        tempDir.delete()
        val tempRepo = org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(tempDir).call().repository
        try {
            val git = Git.wrap(tempRepo)
            // Network I/O into a plain local repository: off the bounded GitWorkDispatcher, so a slow or
            // hung upstream holds no work slot, and bounded by a read timeout.
            withContext(GitBlockingDispatcher) {
                git.fetch()
                    .setRemote(cloneUrl)
                    .setRefSpecs(RefSpec("+refs/*:refs/*"))
                    .setTimeout(UPSTREAM_FETCH_TIMEOUT_SECONDS)
                    .call()
            }

            val repository = repositoryRepository.create(
                Repository(
                    slug = slug,
                    name = name,
                    ownerId = ownerId,
                    visibility = Visibility.PRIVATE,
                    defaultBranch = "main"
                )
            )

            // The new repository row is already visible to scheduled GC, so the
            // DFS writes must hold the write lock like every other writer.
            lockFactory.withRepositoryWriteLock(repository.id, RepositoryWriteLock.MAINTENANCE_WAIT_MILLIS) { lockHandle ->
                val dfsRepo = dfsManager.open(repository.id)
                lockHandle.fence(dfsRepo)
                dfsRepo.use { target ->
                    val inserter = target.objectDatabase.newInserter()
                    val copied = mutableSetOf<ObjectId>()
                    val refsToUpdate = mutableListOf<org.eclipse.jgit.lib.Ref>()

                    for (ref in tempRepo.refDatabase.refs) {
                        try {
                            val revWalk = RevWalk(tempRepo)
                            val commit = revWalk.parseCommit(ref.objectId)
                            copyReachableObjects(tempRepo, commit.id, inserter, copied)
                            revWalk.dispose()
                            refsToUpdate.add(ref)
                        } catch (e: Exception) {
                            log.debug("Skipping ref {} during import: {}", ref.name, e.message)
                        }
                    }
                    inserter.flush()

                    for (ref in refsToUpdate) {
                        val refUpdate = target.refDatabase.newUpdate(ref.name, true)
                        refUpdate.setNewObjectId(ref.objectId)
                        refUpdate.setExpectedOldObjectId(ObjectId.zeroId())
                        refUpdate.update()
                    }

                    val sizeBytes = packRepository.sumPackSizeBytes(repository.id)
                    repositoryRepository.updateDiskSize(repository.id, sizeBytes)
                }
            } ?: throw RepositoryWriteBusyException(repository.id)

            log.info("Imported repository from {} as {}/{}", cloneUrl, ownerId, slug)
            repository
        } finally {
            tempRepo.close()
            tempDir.deleteRecursively()
        }
    }

    private fun copyReachableObjects(
        source: org.eclipse.jgit.lib.Repository,
        objectId: ObjectId,
        inserter: org.eclipse.jgit.lib.ObjectInserter,
        copied: MutableSet<ObjectId>
    ) {
        if (!copied.add(objectId)) return
        val loader = source.objectDatabase.open(objectId)
        loader.openStream().use { inserter.insert(loader.type, loader.size, it) }

        when (loader.type) {
            Constants.OBJ_COMMIT -> {
                val revWalk = RevWalk(source)
                val commit = revWalk.parseCommit(objectId)
                copyReachableObjects(source, commit.tree.id, inserter, copied)
                for (parent in commit.parents) {
                    copyReachableObjects(source, parent.id, inserter, copied)
                }
                revWalk.dispose()
            }

            Constants.OBJ_TREE -> {
                val treeWalk = org.eclipse.jgit.treewalk.TreeWalk(source)
                treeWalk.addTree(objectId)
                while (treeWalk.next()) {
                    if (treeWalk.isSubtree) {
                        val subtreeId = treeWalk.getObjectId(0)
                        copyReachableObjects(source, subtreeId, inserter, copied)
                        treeWalk.enterSubtree()
                    } else {
                        copyReachableObjects(source, treeWalk.getObjectId(0), inserter, copied)
                    }
                }
                treeWalk.close()
            }
        }
    }

    override suspend fun mirrorFetch(repositoryId: UUID, upstreamUrl: String) = withContext(GitWorkDispatcher) {
        val tempDir = File.createTempFile("git-mirror-", ".git")
        tempDir.delete()
        val tempRepo = org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(tempDir).call().repository
        try {
            val git = Git.wrap(tempRepo)
            // Network I/O into a plain local repository: off the bounded GitWorkDispatcher, so a slow or
            // hung upstream holds no work slot, and bounded by a read timeout.
            withContext(GitBlockingDispatcher) {
                git.fetch()
                    .setRemote(upstreamUrl)
                    .setRefSpecs(RefSpec("+refs/*:refs/*"))
                    .setTimeout(UPSTREAM_FETCH_TIMEOUT_SECONDS)
                    .call()
            }

            // Mirror sync writes objects and moves refs on an existing repository,
            // so it must be serialized against GC like every other writer.
            lockFactory.withRepositoryWriteLock(repositoryId, RepositoryWriteLock.MAINTENANCE_WAIT_MILLIS) { lockHandle ->
                val dfsRepo = dfsManager.open(repositoryId)
                lockHandle.fence(dfsRepo)
                dfsRepo.use { target ->
                    val inserter = target.objectDatabase.newInserter()
                    val copied = mutableSetOf<ObjectId>()
                    val refsToUpdate = mutableListOf<org.eclipse.jgit.lib.Ref>()

                    for (ref in tempRepo.refDatabase.refs) {
                        try {
                            val revWalk = RevWalk(tempRepo)
                            val commit = revWalk.parseCommit(ref.objectId)
                            copyReachableObjects(tempRepo, commit.id, inserter, copied)
                            revWalk.dispose()
                            refsToUpdate.add(ref)
                        } catch (e: Exception) {
                            log.debug("Skipping ref {} during mirror: {}", ref.name, e.message)
                        }
                    }
                    inserter.flush()

                    for (ref in refsToUpdate) {
                        val refUpdate = target.refDatabase.newUpdate(ref.name, false)
                        refUpdate.setNewObjectId(ref.objectId)
                        refUpdate.setForceUpdate(true)
                        refUpdate.update()
                    }

                    val sizeBytes = packRepository.sumPackSizeBytes(repositoryId)
                    repositoryRepository.updateDiskSize(repositoryId, sizeBytes)
                }
            } ?: throw RepositoryWriteBusyException(repositoryId)

            log.info("Mirror fetch completed for repository {} from {}", repositoryId, upstreamUrl)
        } finally {
            tempRepo.close()
            tempDir.deleteRecursively()
        }
    }

    private suspend fun purgeRepository(repositoryId: UUID) {
        // Delete storage for every pack — committed, uncommitted, and
        // soft-deleted-but-not-yet-reaped — so a permanent purge leaves nothing
        // behind in object storage.
        val packs = packRepository.findAll(repositoryId)
        for (pack in packs) {
            val extensions = packRepository.findExtensions(pack.id)
            for (ext in extensions) {
                objectStorage.delete(StringObjectPath(ext.storagePath))
            }
        }
        refRepository.deleteAll(repositoryId)
        repositoryRepository.hardDelete(repositoryId)
    }

    companion object {
        /** Longest an import or mirror fetch waits on its upstream for data before failing. */
        private const val UPSTREAM_FETCH_TIMEOUT_SECONDS = 300

        /**
         * Grace period a replaced pack's object-storage files must age past
         * their soft-delete before the reaper removes them. Must comfortably
         * exceed the longest realistic in-flight clone/fetch so a reader is never
         * left referencing storage that has been reclaimed.
         *
         * A full day, because clone duration is bounded by the CLIENT's bandwidth
         * — a large repository over a slow or intermittent link can legitimately
         * stream for hours, and no server-side tuning can shorten that. The only
         * cost of the wide window is that replaced packs occupy object storage a
         * day longer before reclamation; reaping them out from under a live
         * reader would abort the clone mid-stream.
         */
        internal const val PACK_REAP_GRACE_SECONDS = 24 * 60 * 60L

        /** Upper bound on packs reaped per run; overflow rolls to the next run. */
        internal const val PACK_REAP_BATCH_SIZE = 500
    }
}
