package bosca.git.service

import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.dfs.GitWorkDispatcher
import bosca.lock.DistributedLockFactory
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.coroutines.withContext
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.TagBuilder
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk

@ServiceImplementation
class RepositoryWriteServiceImpl(
    private val dfsManager: BoscaDfsRepositoryManager,
    private val refUpdateNotifier: RefUpdateNotifier,
    private val lockFactory: DistributedLockFactory,
) : RepositoryWriteService {

    // Both mutations below insert objects and advance a ref, so they must hold
    // the per-repository write lock like a push: a concurrent GC computing
    // reachability without it could drop the just-written objects (see
    // RepositoryWriteLock).
    override suspend fun commitFile(input: CommitFileInput): CommitFileResult =
        commitFileInternal(input, initiatingPrincipalId = null)

    override suspend fun commitFile(
        input: CommitFileInput,
        initiatingPrincipalId: UUID,
    ): CommitFileResult = commitFileInternal(input, initiatingPrincipalId)

    private suspend fun commitFileInternal(
        input: CommitFileInput,
        initiatingPrincipalId: UUID?,
    ): CommitFileResult = withContext(GitWorkDispatcher) {
        lockFactory.withRepositoryWriteLock(
            input.repositoryId, RepositoryWriteLock.API_WRITE_WAIT_MILLIS
        ) { lockHandle ->
            val repo = dfsManager.open(input.repositoryId)
            lockHandle.fence(repo)
            repo.use {
                val inserter = it.objectDatabase.newInserter()

                val parentId: ObjectId? = it.resolve("refs/heads/${input.branch}")
                val revWalk = RevWalk(it)
                val parentCommit = parentId?.let { id -> revWalk.parseCommit(id) }

                val blobId = inserter.insert(Constants.OBJ_BLOB, input.content.toByteArray(Charsets.UTF_8))

                val dc = DirCache.newInCore()
                val builder = dc.builder()
                var replaced = false

                if (parentCommit != null) {
                    val treeWalk = TreeWalk(it)
                    treeWalk.addTree(parentCommit.tree)
                    treeWalk.isRecursive = true
                    while (treeWalk.next()) {
                        val entry = DirCacheEntry(treeWalk.pathString)
                        if (treeWalk.pathString == input.path) {
                            entry.fileMode = FileMode.REGULAR_FILE
                            entry.setObjectId(blobId)
                            replaced = true
                        } else {
                            entry.fileMode = treeWalk.getFileMode(0)
                            entry.setObjectId(treeWalk.getObjectId(0))
                        }
                        builder.add(entry)
                    }
                    treeWalk.close()
                }

                if (!replaced) {
                    val newEntry = DirCacheEntry(input.path)
                    newEntry.fileMode = FileMode.REGULAR_FILE
                    newEntry.setObjectId(blobId)
                    builder.add(newEntry)
                }

                builder.finish()
                val treeId = dc.writeTree(inserter)

                val commit = CommitBuilder()
                commit.setTreeId(treeId)
                if (parentId != null) commit.setParentId(parentId)
                commit.author = PersonIdent(input.authorName, input.authorEmail)
                commit.committer = PersonIdent(input.authorName, input.authorEmail)
                commit.message = input.message
                val commitId = inserter.insert(commit)
                inserter.flush()

                val refUpdate = it.refDatabase.newUpdate("refs/heads/${input.branch}", false)
                refUpdate.setNewObjectId(commitId)
                refUpdate.setExpectedOldObjectId(parentId ?: ObjectId.zeroId())
                val updateResult = refUpdate.update()

                revWalk.dispose()
                verifyRefUpdated(input.branch, updateResult)

                refUpdateNotifier.notifyRefsUpdated(
                    repository = it,
                    repositoryId = input.repositoryId,
                    updates = listOf(RefChange("refs/heads/${input.branch}", parentId ?: ObjectId.zeroId(), commitId)),
                    pusherPrincipalId = initiatingPrincipalId,
                )

                CommitFileResult(
                    commitSha = commitId.name(),
                    branch = input.branch,
                    path = input.path,
                )
            }
        } ?: throw RepositoryWriteBusyException(input.repositoryId)
    }

    override suspend fun commitFiles(input: CommitFilesInput): CommitFileResult =
        commitFilesInternal(input, initiatingPrincipalId = null)

    override suspend fun commitFiles(
        input: CommitFilesInput,
        initiatingPrincipalId: UUID,
    ): CommitFileResult = commitFilesInternal(input, initiatingPrincipalId)

    private suspend fun commitFilesInternal(
        input: CommitFilesInput,
        initiatingPrincipalId: UUID?,
    ): CommitFileResult = withContext(GitWorkDispatcher) {
        lockFactory.withRepositoryWriteLock(
            input.repositoryId, RepositoryWriteLock.API_WRITE_WAIT_MILLIS
        ) { lockHandle ->
            require(input.files.isNotEmpty()) { "commitFiles needs at least one file" }
            val repo = dfsManager.open(input.repositoryId)
            lockHandle.fence(repo)
            repo.use {
                val inserter = it.objectDatabase.newInserter()

                val parentId: ObjectId? = it.resolve("refs/heads/${input.branch}")
                val revWalk = RevWalk(it)
                val parentCommit = parentId?.let { id -> revWalk.parseCommit(id) }

                val blobs = input.files.mapValues { (_, content) ->
                    inserter.insert(Constants.OBJ_BLOB, content.toByteArray(Charsets.UTF_8))
                }

                val dc = DirCache.newInCore()
                val builder = dc.builder()
                val replaced = mutableSetOf<String>()

                if (parentCommit != null) {
                    val treeWalk = TreeWalk(it)
                    treeWalk.addTree(parentCommit.tree)
                    treeWalk.isRecursive = true
                    while (treeWalk.next()) {
                        val entry = DirCacheEntry(treeWalk.pathString)
                        val blob = blobs[treeWalk.pathString]
                        if (blob != null) {
                            entry.fileMode = FileMode.REGULAR_FILE
                            entry.setObjectId(blob)
                            replaced.add(treeWalk.pathString)
                        } else {
                            entry.fileMode = treeWalk.getFileMode(0)
                            entry.setObjectId(treeWalk.getObjectId(0))
                        }
                        builder.add(entry)
                    }
                    treeWalk.close()
                }
                for ((path, blob) in blobs) {
                    if (path in replaced) continue
                    val newEntry = DirCacheEntry(path)
                    newEntry.fileMode = FileMode.REGULAR_FILE
                    newEntry.setObjectId(blob)
                    builder.add(newEntry)
                }

                builder.finish()
                val treeId = dc.writeTree(inserter)

                val commit = CommitBuilder()
                commit.setTreeId(treeId)
                if (parentId != null) commit.setParentId(parentId)
                commit.author = PersonIdent(input.authorName, input.authorEmail)
                commit.committer = PersonIdent(input.authorName, input.authorEmail)
                commit.message = input.message
                val commitId = inserter.insert(commit)
                inserter.flush()

                val refUpdate = it.refDatabase.newUpdate("refs/heads/${input.branch}", false)
                refUpdate.setNewObjectId(commitId)
                refUpdate.setExpectedOldObjectId(parentId ?: ObjectId.zeroId())
                val updateResult = refUpdate.update()

                revWalk.dispose()
                verifyRefUpdated(input.branch, updateResult)

                refUpdateNotifier.notifyRefsUpdated(
                    repository = it,
                    repositoryId = input.repositoryId,
                    updates = listOf(RefChange("refs/heads/${input.branch}", parentId ?: ObjectId.zeroId(), commitId)),
                    pusherPrincipalId = initiatingPrincipalId,
                )

                CommitFileResult(
                    commitSha = commitId.name(),
                    branch = input.branch,
                    path = input.files.keys.sorted().joinToString(", "),
                )
            }
        } ?: throw RepositoryWriteBusyException(input.repositoryId)
    }

    override suspend fun deleteFile(input: DeleteFileInput): CommitFileResult =
        deleteFileInternal(input, initiatingPrincipalId = null)

    override suspend fun deleteFile(
        input: DeleteFileInput,
        initiatingPrincipalId: UUID,
    ): CommitFileResult = deleteFileInternal(input, initiatingPrincipalId)

    private suspend fun deleteFileInternal(
        input: DeleteFileInput,
        initiatingPrincipalId: UUID?,
    ): CommitFileResult = withContext(GitWorkDispatcher) {
        lockFactory.withRepositoryWriteLock(
            input.repositoryId, RepositoryWriteLock.API_WRITE_WAIT_MILLIS
        ) { lockHandle ->
            val repo = dfsManager.open(input.repositoryId)
            lockHandle.fence(repo)
            repo.use {
                val parentId = it.resolve("refs/heads/${input.branch}")
                    ?: throw IllegalArgumentException("Branch '${input.branch}' not found")
                val inserter = it.objectDatabase.newInserter()
                val revWalk = RevWalk(it)
                val parentCommit = revWalk.parseCommit(parentId)

                val dc = DirCache.newInCore()
                val builder = dc.builder()
                val treeWalk = TreeWalk(it)
                treeWalk.addTree(parentCommit.tree)
                treeWalk.isRecursive = true
                while (treeWalk.next()) {
                    if (treeWalk.pathString == input.path) continue
                    val entry = DirCacheEntry(treeWalk.pathString)
                    entry.fileMode = treeWalk.getFileMode(0)
                    entry.setObjectId(treeWalk.getObjectId(0))
                    builder.add(entry)
                }
                treeWalk.close()
                builder.finish()
                val treeId = dc.writeTree(inserter)

                val commit = CommitBuilder()
                commit.setTreeId(treeId)
                commit.setParentId(parentId)
                commit.author = PersonIdent(input.authorName, input.authorEmail)
                commit.committer = PersonIdent(input.authorName, input.authorEmail)
                commit.message = input.message
                val commitId = inserter.insert(commit)
                inserter.flush()

                val refUpdate = it.refDatabase.newUpdate("refs/heads/${input.branch}", false)
                refUpdate.setNewObjectId(commitId)
                refUpdate.setExpectedOldObjectId(parentId)
                val updateResult = refUpdate.update()
                revWalk.dispose()
                verifyRefUpdated(input.branch, updateResult)

                refUpdateNotifier.notifyRefsUpdated(
                    repository = it,
                    repositoryId = input.repositoryId,
                    updates = listOf(RefChange("refs/heads/${input.branch}", parentId, commitId)),
                    pusherPrincipalId = initiatingPrincipalId,
                )

                CommitFileResult(commitSha = commitId.name(), branch = input.branch, path = input.path)
            }
        } ?: throw RepositoryWriteBusyException(input.repositoryId)
    }

    override suspend fun createTag(input: CreateTagInput): CreateTagResult = withContext(GitWorkDispatcher) {
        lockFactory.withRepositoryWriteLock(
            input.repositoryId, RepositoryWriteLock.API_WRITE_WAIT_MILLIS
        ) { lockHandle ->
            val repo = dfsManager.open(input.repositoryId)
            lockHandle.fence(repo)
            repo.use {
                val tagRefName = "refs/tags/${input.tag}"
                val targetId = it.resolve(input.targetRef)
                    ?: throw IllegalArgumentException("Target ref '${input.targetRef}' not found")
                val revWalk = RevWalk(it)
                val targetCommit = revWalk.parseCommit(targetId)

                // An existing tag is an ERROR unless the caller opted into re-run safety (the
                // `uses: tag` action): then a tag on the same commit is a no-op — which must NOT re-fire the
                // notifier, or the whole downstream wave re-triggers — and a tag sitting on a DIFFERENT
                // commit still fails: tags are never silently moved.
                val existingId = it.resolve(tagRefName)
                if (existingId != null) {
                    val existingCommit = revWalk.parseCommit(revWalk.peel(revWalk.parseAny(existingId)))
                    revWalk.dispose()
                    if (!input.allowExisting) {
                        throw IllegalArgumentException("Tag '${input.tag}' already exists")
                    }
                    if (existingCommit.name() == targetCommit.name()) {
                        return@use CreateTagResult(
                            tag = input.tag,
                            ref = tagRefName,
                            commitSha = existingCommit.name(),
                            tagSha = existingId.name(),
                            created = false,
                        )
                    }
                    throw IllegalStateException(
                        "Tag '${input.tag}' already exists at ${existingCommit.name()}, " +
                                "which is not ${targetCommit.name()} — refusing to move it"
                    )
                }

                val inserter = it.objectDatabase.newInserter()
                val tagBuilder = TagBuilder()
                tagBuilder.tag = input.tag
                tagBuilder.setObjectId(targetCommit)
                tagBuilder.tagger = PersonIdent(input.taggerName, input.taggerEmail)
                tagBuilder.message = input.message
                val tagId = inserter.insert(tagBuilder)
                inserter.flush()

                val refUpdate = it.refDatabase.newUpdate(tagRefName, false)
                refUpdate.setNewObjectId(tagId)
                refUpdate.setExpectedOldObjectId(ObjectId.zeroId())
                val updateResult = refUpdate.update()
                revWalk.dispose()
                verifyRefUpdated(input.tag, updateResult)

                refUpdateNotifier.notifyRefsUpdated(
                    repository = it,
                    repositoryId = input.repositoryId,
                    updates = listOf(RefChange(tagRefName, ObjectId.zeroId(), tagId)),
                    pusherPrincipalId = input.pusherPrincipalId,
                )

                CreateTagResult(
                    tag = input.tag,
                    ref = tagRefName,
                    commitSha = targetCommit.name(),
                    tagSha = tagId.name(),
                )
            }
        } ?: throw RepositoryWriteBusyException(input.repositoryId)
    }

    override suspend fun deleteTag(repositoryId: UUID, tag: String) =
        deleteRef(repositoryId, "refs/tags/$tag", "Tag '$tag'")

    override suspend fun deleteBranch(repositoryId: UUID, branch: String) =
        deleteRef(repositoryId, "refs/heads/$branch", "Branch '$branch'")

    /**
     * Deletes [refName] and fires the ref-update notifier — the same fan-out a pushed deletion takes
     * (TAG_DELETED / BRANCH_DELETED webhooks); a zero newId is skipped by the CI trigger path by design.
     */
    private suspend fun deleteRef(repositoryId: UUID, refName: String, what: String) {
        withContext(GitWorkDispatcher) {
            lockFactory.withRepositoryWriteLock(repositoryId, RepositoryWriteLock.API_WRITE_WAIT_MILLIS) { lockHandle ->
                val repo = dfsManager.open(repositoryId)
                lockHandle.fence(repo)
                repo.use {
                    val oldId = it.resolve(refName)
                        ?: throw IllegalArgumentException("$what not found")
                    val refUpdate = it.refDatabase.newUpdate(refName, false)
                    refUpdate.setForceUpdate(true)
                    refUpdate.setExpectedOldObjectId(oldId)
                    when (val result = refUpdate.delete()) {
                        RefUpdate.Result.FORCED, RefUpdate.Result.FAST_FORWARD, RefUpdate.Result.NO_CHANGE -> {}
                        else -> throw IllegalStateException("Failed to delete $what: $result")
                    }
                    refUpdateNotifier.notifyRefsUpdated(
                        repository = it,
                        repositoryId = repositoryId,
                        updates = listOf(RefChange(refName, oldId, ObjectId.zeroId())),
                    )
                }
            } ?: throw RepositoryWriteBusyException(repositoryId)
        }
    }

    /**
     * A ref write that didn't actually land (e.g. a lost optimistic-lock race)
     * must not be reported as a successful commit, and must not fire downstream
     * notifications for a commit that isn't the branch tip.
     */
    private fun verifyRefUpdated(branch: String, result: RefUpdate.Result) {
        when (result) {
            RefUpdate.Result.NEW,
            RefUpdate.Result.FORCED,
            RefUpdate.Result.FAST_FORWARD,
            RefUpdate.Result.NO_CHANGE -> {
            }

            else -> throw IllegalStateException("Failed to update branch '$branch': $result")
        }
    }

    override suspend fun readFile(repositoryId: UUID, ref: String, path: String): String? = withContext(GitWorkDispatcher) {
        val repo = dfsManager.open(repositoryId)
        repo.use {
            val commitId = it.resolve(ref) ?: return@use null
            val objectId = RevWalk(it).use { revWalk ->
                val commit = revWalk.parseCommit(commitId)
                TreeWalk.forPath(it, path, commit.tree)?.use { treeWalk -> treeWalk.getObjectId(0) }
            } ?: return@use null
            String(it.objectDatabase.open(objectId).cachedBytes, Charsets.UTF_8)
        }
    }
}
