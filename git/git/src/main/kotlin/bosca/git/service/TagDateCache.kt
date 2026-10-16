package bosca.git.service

import bosca.serialization.UUID
import com.github.benmanes.caffeine.cache.Caffeine
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevTag
import org.eclipse.jgit.revwalk.RevWalk
import java.time.Duration

/**
 * Caches immutable Git object dates in stable hash buckets, independently of ref names and order.
 * Repository-scoped buckets share a 64 MiB weight budget per cache instance, accounting for
 * 28 bytes per object plus estimated bucket overhead. Temporary request allocations are separate.
 * Buckets expire after 30 minutes without access, allowing active repositories to reuse dates.
 * Ref membership always comes from the caller's current JGit refs. Used on the repository work dispatcher.
 */
internal class TagDateCache {
    private data class Key(val repositoryId: UUID, val bucket: Int)

    private val buckets = Caffeine.newBuilder()
        .maximumWeight(64L * 1024 * 1024)
        .weigher<Key, Dates> { _, dates -> dates.weight }
        .expireAfterAccess(Duration.ofMinutes(30))
        .build<Key, Dates>()

    /** Visits the supplied refs with their Git dates, parsing only objects absent from the cache. */
    fun forEach(repositoryId: UUID, repository: Repository, refs: Sequence<Ref>, visit: (Ref, Long) -> Unit) {
        val groups = refs.groupBy { it.objectId.hashCode() and 0xfff }
        var walk: RevWalk? = null
        var parsed = 0
        fun readDate(id: ObjectId): Long {
            if (parsed == 256) {
                walk?.close()
                walk = null
                parsed = 0
            }
            val reader = walk ?: RevWalk(repository).also { walk = it }
            parsed++
            return when (val target = reader.parseAny(id)) {
                is RevTag -> target.taggerIdent?.whenAsInstant?.epochSecond
                is RevCommit -> target.committerIdent.whenAsInstant.epochSecond
                else -> null
            } ?: Long.MIN_VALUE
        }
        try {
            for ((bucket, members) in groups) {
                // Atomic updates preserve additions from concurrent searches in the same bucket.
                val dates = requireNotNull(buckets.asMap().compute(Key(repositoryId, bucket)) { _, previous ->
                    val additions = members.asSequence().map { it.objectId }
                        .filter { previous == null || previous.indexOf(it) < 0 }.toSortedSet().toList()
                    if (additions.isEmpty()) previous
                    else {
                        val created = LongArray(additions.size) { readDate(additions[it]) }
                        (previous ?: Dates(IntArray(0), LongArray(0))).withDates(additions, created)
                    }
                })
                for (ref in members) visit(ref, dates[ref.objectId])
            }
        } finally {
            walk?.close()
        }
    }

    private class Dates(private val ids: IntArray, private val created: LongArray) {
        val weight: Int get() = ids.size * Int.SIZE_BYTES + created.size * Long.SIZE_BYTES + 160

        fun indexOf(id: ObjectId): Int {
            var low = 0
            var high = created.lastIndex
            while (low <= high) {
                val middle = (low + high) ushr 1
                val compared = id.compareTo(ids, middle * 5)
                when {
                    compared < 0 -> high = middle - 1
                    compared > 0 -> low = middle + 1
                    else -> return middle
                }
            }
            return -1
        }

        operator fun get(id: ObjectId): Long = created[indexOf(id)]

        fun withDates(additions: List<ObjectId>, dates: LongArray): Dates {
            val mergedIds = IntArray((created.size + additions.size) * 5)
            val mergedDates = LongArray(created.size + additions.size)
            var old = 0
            var added = 0
            for (index in mergedDates.indices) {
                if (added < additions.size && (old == created.size || additions[added].compareTo(ids, old * 5) < 0)) {
                    additions[added].copyRawTo(mergedIds, index * 5)
                    mergedDates[index] = dates[added++]
                } else {
                    ids.copyInto(mergedIds, index * 5, old * 5, (old + 1) * 5)
                    mergedDates[index] = created[old++]
                }
            }
            return Dates(mergedIds, mergedDates)
        }
    }
}
