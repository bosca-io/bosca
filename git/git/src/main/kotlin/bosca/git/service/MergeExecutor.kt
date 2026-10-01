package bosca.git.service

import org.eclipse.jgit.internal.storage.dfs.DfsRepository
import bosca.git.model.MergeResult
import bosca.git.model.MergeStrategy
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.merge.MergeStrategy as JGitMergeStrategy
import org.eclipse.jgit.merge.ResolveMerger
import org.eclipse.jgit.merge.ThreeWayMerger
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.slf4j.LoggerFactory

/**
 * Executes server-side merges within a [DfsRepository] using JGit's in-memory
 * merge infrastructure (DirCache, no working tree). Supports all four merge strategies.
 */
object MergeExecutor {

    private val log = LoggerFactory.getLogger(MergeExecutor::class.java)

    fun merge(
        repo: DfsRepository,
        sourceId: ObjectId,
        targetId: ObjectId,
        strategy: MergeStrategy,
        message: String,
        author: PersonIdent
    ): MergeResult {
        return when (strategy) {
            MergeStrategy.MERGE_COMMIT -> mergeCommit(repo, sourceId, targetId, message, author)
            MergeStrategy.SQUASH -> squash(repo, sourceId, targetId, message, author)
            MergeStrategy.REBASE -> rebase(repo, sourceId, targetId, message, author)
            MergeStrategy.FAST_FORWARD -> fastForward(repo, sourceId, targetId)
        }
    }

    fun checkMergeability(
        repo: DfsRepository,
        sourceId: ObjectId,
        targetId: ObjectId
    ): MergeResult {
        val merger = JGitMergeStrategy.RECURSIVE.newMerger(repo, true)
        val canMerge = merger.merge(targetId, sourceId)
        return if (canMerge) {
            MergeResult(success = true)
        } else {
            MergeResult(success = false, conflictingFiles = extractConflicts(merger))
        }
    }

    private fun mergeCommit(
        repo: DfsRepository,
        sourceId: ObjectId,
        targetId: ObjectId,
        message: String,
        author: PersonIdent
    ): MergeResult {
        val merger = JGitMergeStrategy.RECURSIVE.newMerger(repo, true)
        if (!merger.merge(targetId, sourceId)) {
            return MergeResult(success = false, conflictingFiles = extractConflicts(merger))
        }

        val inserter = repo.objectDatabase.newInserter()
        val mergedTreeId = merger.resultTreeId

        val commit = CommitBuilder()
        commit.setTreeId(mergedTreeId)
        commit.setParentIds(targetId, sourceId)
        commit.author = author
        commit.committer = author
        commit.message = message

        val commitId = inserter.insert(commit)
        inserter.flush()

        return MergeResult(success = true, mergeSha = commitId.name())
    }

    private fun squash(
        repo: DfsRepository,
        sourceId: ObjectId,
        targetId: ObjectId,
        message: String,
        author: PersonIdent
    ): MergeResult {
        val merger = JGitMergeStrategy.RECURSIVE.newMerger(repo, true)
        if (!merger.merge(targetId, sourceId)) {
            return MergeResult(success = false, conflictingFiles = extractConflicts(merger))
        }

        val inserter = repo.objectDatabase.newInserter()
        val mergedTreeId = merger.resultTreeId

        val commit = CommitBuilder()
        commit.setTreeId(mergedTreeId)
        commit.setParentId(targetId)
        commit.author = author
        commit.committer = author
        commit.message = message

        val commitId = inserter.insert(commit)
        inserter.flush()

        return MergeResult(success = true, mergeSha = commitId.name())
    }

    private fun rebase(
        repo: DfsRepository,
        sourceId: ObjectId,
        targetId: ObjectId,
        message: String,
        author: PersonIdent
    ): MergeResult {
        val mergeBaseId: ObjectId
        RevWalk(repo).use { baseWalk ->
            val sourceCommit = baseWalk.parseCommit(sourceId)
            val targetCommit = baseWalk.parseCommit(targetId)
            baseWalk.revFilter = RevFilter.MERGE_BASE
            baseWalk.markStart(sourceCommit)
            baseWalk.markStart(targetCommit)
            mergeBaseId = baseWalk.next()?.id
                ?: return MergeResult(success = false)
        }

        val commits = mutableListOf<RevCommit>()
        RevWalk(repo).use { commitWalk ->
            commitWalk.markStart(commitWalk.parseCommit(sourceId))
            commitWalk.markUninteresting(commitWalk.parseCommit(mergeBaseId))
            for (c in commitWalk) {
                commits.add(c)
            }
        }
        commits.reverse()

        var currentHead = targetId
        val inserter = repo.objectDatabase.newInserter()

        for (c in commits) {
            val merger = JGitMergeStrategy.RECURSIVE.newMerger(repo, true)
            if (!merger.merge(currentHead, c)) {
                return MergeResult(success = false, conflictingFiles = extractConflicts(merger))
            }

            val rebasedCommit = CommitBuilder()
            rebasedCommit.setTreeId(merger.resultTreeId)
            rebasedCommit.setParentId(currentHead)
            rebasedCommit.author = c.authorIdent
            rebasedCommit.committer = author
            rebasedCommit.message = c.fullMessage

            currentHead = inserter.insert(rebasedCommit)
        }

        inserter.flush()
        return MergeResult(success = true, mergeSha = currentHead.name())
    }

    private fun fastForward(
        repo: DfsRepository,
        sourceId: ObjectId,
        targetId: ObjectId
    ): MergeResult {
        val revWalk = RevWalk(repo)
        try {
            val sourceCommit = revWalk.parseCommit(sourceId)
            val targetCommit = revWalk.parseCommit(targetId)

            if (revWalk.isMergedInto(targetCommit, sourceCommit)) {
                return MergeResult(success = true, mergeSha = sourceId.name())
            }
            return MergeResult(success = false)
        } finally {
            revWalk.dispose()
        }
    }

    private fun extractConflicts(merger: org.eclipse.jgit.merge.Merger): List<String> {
        return if (merger is ResolveMerger) {
            merger.unmergedPaths ?: emptyList()
        } else {
            emptyList()
        }
    }
}
