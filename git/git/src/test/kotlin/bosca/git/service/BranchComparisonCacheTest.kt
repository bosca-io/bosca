package bosca.git.service

import bosca.serialization.UUID
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.lib.AnyObjectId
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.TreeFormatter
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BranchComparisonCacheTest {
    private val repository = InMemoryRepository(DfsRepositoryDescription("branch-comparison"))
    private val repositoryId = UUID.random()
    private val cache = BranchComparisonCache()
    private var sequence = 0
    private var increasingCommitTimes = false

    @AfterTest
    fun close() = repository.close()

    private fun commit(vararg parents: ObjectId): ObjectId = repository.newObjectInserter().use { inserter ->
        val timestamp = Instant.ofEpochSecond(1_700_000_000L + if (increasingCommitTimes) sequence else 0)
        val author = PersonIdent("Test", "test@example.com", timestamp, ZoneOffset.UTC)
        val commit = CommitBuilder().apply {
            setTreeId(inserter.insert(TreeFormatter()))
            setAuthor(author)
            setCommitter(author)
            setMessage("commit-${sequence++}")
            setParentIds(*parents)
        }
        inserter.insert(commit).also { inserter.flush() }
    }

    @Test
    fun `shared walk counts divergent merged stale and unrelated histories`() {
        val root = commit()
        val shared = commit(root)
        val main = commit(shared)
        val feature = commit(shared)
        val merged = commit(main, feature)
        val unrelated = commit()
        val counts = cache.counts(repositoryId, repository, main, setOf(main, root, feature, merged, unrelated)) {}

        assertEquals(0 to 2, counts[root])
        assertEquals(1 to 1, counts[feature])
        assertEquals(2 to 0, counts[merged])
        assertEquals(1 to 3, counts[unrelated])
    }

    @Test
    fun `shared history is read once across many heads and reused by concurrent requests`() = assertSharedHistoryReads()

    @Test
    fun `shared history reads remain coalesced with increasing commit times`() {
        increasingCommitTimes = true
        assertSharedHistoryReads()
    }

    private fun assertSharedHistoryReads() {
        var shared = commit()
        repeat(100) { shared = commit(shared) }
        val main = commit(shared)
        val heads = List(41) { commit(shared) }.toSet()
        val reads = ConcurrentHashMap<ObjectId, AtomicInteger>()
        val observed = spyk(repository)
        every { observed.newObjectReader() } answers {
            spyk(repository.newObjectReader()).also { reader ->
                every { reader.open(any(), any()) } answers {
                    reads.computeIfAbsent(firstArg<AnyObjectId>().copy()) { AtomicInteger() }.incrementAndGet()
                    callOriginal()
                }
            }
        }

        val requests = List(4) {
            CompletableFuture.supplyAsync { cache.counts(repositoryId, observed, main, heads) {} }
        }
        requests.forEach { request -> assertTrue(request.join().values.all { it == (1 to 1) }) }
        assertTrue((heads + main).all(reads::containsKey), "Every compared head must be read")
        assertTrue(reads.values.all { it.get() == 1 }, "Commit objects must not be read repeatedly across branches or requests")
        val firstReadCounts = reads.mapValues { it.value.get() }
        cache.counts(repositoryId, observed, main, heads.reversed().toSet()) {}
        assertEquals(firstReadCounts, reads.mapValues { it.value.get() }, "Ref order must not invalidate immutable comparison results")
    }

    @Test
    fun `moving either head changes the comparison`() {
        val root = commit()
        val main = commit(root)
        val feature = commit(root)
        assertEquals(1 to 1, cache.counts(repositoryId, repository, main, setOf(feature)) {}[feature])
        val advanced = commit(main, feature)
        assertEquals(0 to 2, cache.counts(repositoryId, repository, advanced, setOf(feature)) {}[feature])
        assertEquals(2 to 0, cache.counts(repositoryId, repository, main, setOf(advanced)) {}[advanced])
    }

    @Test
    fun `shallow boundary changes invalidate cached comparisons`() {
        val root = commit()
        val shared = commit(root)
        val main = commit(shared)
        val observed = spyk(repository)
        every { observed.newObjectReader() } answers {
            spyk(repository.newObjectReader()).also { reader ->
                every { reader.shallowCommits } answers { repository.objectDatabase.shallowCommits }
            }
        }
        assertEquals(0 to 2, cache.counts(repositoryId, observed, main, setOf(root)) {}[root])
        repository.objectDatabase.setShallowCommits(setOf(shared))
        assertEquals(1 to 2, cache.counts(repositoryId, observed, main, setOf(root)) {}[root])
    }

    @Test
    fun `cancelled traversal stops and does not cache incomplete counts`() {
        val root = commit()
        val main = commit(root)
        val feature = commit(root)
        var checks = 0
        assertFailsWith<CancellationException> {
            cache.counts(repositoryId, repository, main, setOf(feature)) {
                if (++checks == 3) throw CancellationException("cancelled")
            }
        }
        assertEquals(1 to 1, cache.counts(repositoryId, repository, main, setOf(feature)) {}[feature])
    }

    @Test
    fun `missing commit data fails instead of reporting zero counts`() {
        val main = commit()
        assertFailsWith<org.eclipse.jgit.errors.MissingObjectException> {
            cache.counts(repositoryId, repository, main, setOf(ObjectId.fromString("1234567890123456789012345678901234567890"))) {}
        }
    }
}
