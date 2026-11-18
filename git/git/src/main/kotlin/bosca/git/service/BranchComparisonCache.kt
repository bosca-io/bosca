package bosca.git.service

import bosca.serialization.UUID
import com.github.benmanes.caffeine.cache.Caffeine
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import java.time.Duration

/** Shares parsed history across branch heads and coalesces reads of unchanged comparisons. */
internal class BranchComparisonCache {
    private data class Key(
        val repositoryId: UUID,
        val defaultId: ObjectId,
        val heads: Set<ObjectId>,
        val shallow: Set<ObjectId>,
    )

    // The budget accounts for head IDs, count pairs, map entries and shallow boundaries.
    private val comparisons = Caffeine.newBuilder()
        .maximumWeight(16L * 1024 * 1024)
        .weigher<Key, Map<ObjectId, Pair<Int, Int>>> { key, _ ->
            (256L + key.heads.size * 128L + key.shallow.size * 32L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        .expireAfterAccess(Duration.ofMinutes(30))
        .build<Key, Map<ObjectId, Pair<Int, Int>>>()

    /** Counts each head's reachable commits outside the default history, and vice versa. */
    fun counts(
        repositoryId: UUID,
        repository: Repository,
        defaultId: ObjectId,
        heads: Set<ObjectId>,
        checkActive: () -> Unit,
    ): Map<ObjectId, Pair<Int, Int>> {
        checkActive()
        val selected = heads - defaultId
        if (selected.isEmpty()) return emptyMap()
        val key = Key(repositoryId, defaultId, selected, repository.objectDatabase.shallowCommits.toSet())
        return requireNotNull(comparisons.get(key) { count(repository, key, checkActive) })
    }

    private fun count(repository: Repository, key: Key, checkActive: () -> Unit): Map<ObjectId, Pair<Int, Int>> {
        return RevWalk(repository).use { walk ->
            walk.setRetainBody(false)
            val defaultCommit = walk.parseCommit(key.defaultId)
            fun difference(from: RevCommit, excluded: RevCommit): Int {
                // Reset traversal flags while retaining parsed headers shared by all comparisons.
                walk.reset()
                walk.markStart(from)
                walk.markUninteresting(excluded)
                var count = 0
                for (commit in walk) {
                    checkActive()
                    count++
                }
                return count
            }
            key.heads.associateWith { head ->
                checkActive()
                val branchCommit = walk.parseCommit(head)
                difference(branchCommit, defaultCommit) to difference(defaultCommit, branchCommit)
            }
        }
    }
}
