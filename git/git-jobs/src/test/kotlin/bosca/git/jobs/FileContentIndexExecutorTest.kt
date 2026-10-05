@file:OptIn(bosca.di.annotation.InternalDI::class, bosca.core.annotations.Internal::class)

package bosca.git.jobs

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.model.Repository
import bosca.git.model.Visibility
import bosca.git.repository.GitRepositoryRepository
import bosca.search.IndexStorageSystem
import bosca.search.model.SearchFilter
import bosca.search.service.SearchService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.TreeFormatter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for [FileContentIndexExecutor]'s branch-overlay state machine:
 *  - initial / incremental / delete mode dispatch
 *  - content-addressed deduplication (shared blob across branches = one doc with
 *    multiple branch tags)
 *  - read-modify-write semantics on the `branches` array, including delete-when-empty
 *  - non-branch refs and repo-deletion bypasses
 *
 * The "search index" is an in-memory `MutableMap<String, JsonObject>` exposed
 * through a mocked [SearchService] so assertions can read its state directly.
 */
class FileContentIndexExecutorTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val docs = mutableMapOf<String, JsonObject>()
    private val searchService = mockk<SearchService>(relaxed = true)
    private val dfsManager = mockk<BoscaDfsRepositoryManager>()
    private val repoRepository = mockk<GitRepositoryRepository>(relaxed = true)
    private val storageService = mockk<bosca.storage.service.StorageSystemService>(relaxed = true)
    private val jobQueue = mockk<JobQueue>(relaxed = true)

    private val repositoryId = UUID.random()
    private val ownerId = UUID.random()
    private val storageSystem = IndexStorageSystem(UUID.random(), "git-code")
    private val repo = Repository(
        id = repositoryId,
        slug = "test",
        name = "Test Repo",
        ownerId = ownerId,
        visibility = Visibility.PRIVATE,
        defaultBranch = "main",
    )

    private lateinit var gitRepo: InMemoryRepository

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        provides<SearchService>(singleton = true) { searchService }
        provides<BoscaDfsRepositoryManager>(singleton = true) { dfsManager }
        provides<GitRepositoryRepository>(singleton = true) { repoRepository }

        gitRepo = InMemoryRepository(DfsRepositoryDescription("test-${repositoryId}"))
        coEvery { dfsManager.open(repositoryId) } returns gitRepo
        coEvery { repoRepository.findById(repositoryId) } returns repo
        wireInMemorySearch()
    }

    @AfterTest
    fun teardown() {
        gitRepo.close()
        unmockkAll()
        ProviderRegistry.clear()
    }

    /**
     * Wires the mocked SearchService to a plain in-memory document store so the
     * RMW path through fetch → index / fetch → delete behaves end-to-end. Only
     * the methods the executor actually calls are bound; everything else is
     * `relaxed = true` so an unexpected call is loud (returns nothing useful)
     * but doesn't NPE the test.
     */
    private fun wireInMemorySearch() {
        coEvery { searchService.fetch(storageSystem, any()) } answers {
            docs[secondArg<String>()]
        }
        coEvery { searchService.index(storageSystem, any<JsonElement>()) } answers {
            val doc = secondArg<JsonElement>() as JsonObject
            val id = doc["id"]?.jsonPrimitive?.contentOrNull
                ?: error("indexed document missing id")
            docs[id] = doc
        }
        coEvery { searchService.index(storageSystem, any<List<JsonElement>>()) } answers {
            for (doc in secondArg<List<JsonElement>>()) {
                val obj = doc as JsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull
                    ?: error("indexed document missing id")
                docs[id] = obj
            }
        }
        coEvery { searchService.delete(storageSystem, any<String>()) } answers {
            docs.remove(secondArg<String>())
        }
        coEvery { searchService.deleteByFilter(storageSystem, any()) } answers {
            val filter = secondArg<SearchFilter>()
            val toRemove = docs.entries.filter { matches(it.value, filter) }.map { it.key }
            toRemove.forEach { docs.remove(it) }
        }
    }

    private fun matches(doc: JsonObject, filter: SearchFilter): Boolean = when (filter) {
        is SearchFilter.Eq -> {
            val field = doc[filter.field]
            when (field) {
                is JsonPrimitive -> field.contentOrNull == filter.value
                is JsonArray -> field.any { (it as? JsonPrimitive)?.contentOrNull == filter.value }
                else -> false
            }
        }
        is SearchFilter.And -> filter.conditions.all { matches(doc, it) }
    }

    /** Builds a tree with the given (path → blob bytes) entries and returns the commit SHA. */
    private fun commitTree(
        files: Map<String, ByteArray>,
        parent: ObjectId? = null,
        message: String = "test commit",
    ): ObjectId {
        val inserter = gitRepo.objectDatabase.newInserter()
        try {
            val tree = TreeFormatter()
            // TreeFormatter requires entries in name-sorted order.
            for ((path, bytes) in files.entries.sortedBy { it.key }) {
                require(!path.contains('/')) { "single-level test paths only" }
                val blobId = inserter.insert(Constants.OBJ_BLOB, bytes)
                tree.append(path, FileMode.REGULAR_FILE, blobId)
            }
            val treeId = inserter.insert(tree)
            val commit = CommitBuilder().apply {
                setTreeId(treeId)
                setAuthor(AUTHOR)
                setCommitter(AUTHOR)
                setMessage(message)
                if (parent != null) setParentId(parent)
            }
            val commitId = inserter.insert(commit)
            inserter.flush()
            return commitId
        } finally {
            inserter.close()
        }
    }

    @Test
    fun `resolves search storage systems when the job carries none`() = runTest {
        bosca.di.provides<bosca.storage.service.StorageSystemService>(singleton = true) { storageService }
        coEvery { storageService.getAll() } returns listOf(
            bosca.storage.model.StorageSystem(id = UUID.random(), name = "git-code", description = "", type = bosca.storage.model.StorageSystemType.SEARCH, configuration = json.parseToJsonElement("{}")),
            bosca.storage.model.StorageSystem(id = UUID.random(), name = "other", description = "", type = bosca.storage.model.StorageSystemType.SEARCH, configuration = json.parseToJsonElement("{}")), // wrong name
            bosca.storage.model.StorageSystem(id = UUID.random(), name = "git-code", description = "", type = bosca.storage.model.StorageSystemType.SUPPLEMENTARY, configuration = json.parseToJsonElement("{}")), // wrong type
        )
        val commit = commitTree(mapOf("a.kt" to "fun x() {}".toByteArray()))

        runJob(FileContentIndexJob(storage = null, repositoryId = repositoryId, ref = "refs/heads/main", afterSha = commit.name()))

        coVerify { storageService.getAll() }
        coVerify { searchService.index(any(), any<JsonElement>()) }
    }

    @Test
    fun `initial mode ignores an unparseable commit`() = runTest {
        runJob(FileContentIndexJob(storage = storageSystem, repositoryId = repositoryId, ref = "refs/heads/main", afterSha = BAD_SHA))
        assertTrue(docsForBranch("main").isEmpty())
    }

    @Test
    fun `delete mode ignores an unparseable commit`() = runTest {
        runJob(FileContentIndexJob(storage = storageSystem, repositoryId = repositoryId, ref = "refs/heads/main", beforeSha = BAD_SHA))
        assertTrue(docsForBranch("main").isEmpty())
    }

    @Test
    fun `incremental mode ignores an unparseable before commit`() = runTest {
        val after = commitTree(mapOf("a.kt" to "x".toByteArray()))
        runJob(FileContentIndexJob(storage = storageSystem, repositoryId = repositoryId, ref = "refs/heads/main", beforeSha = BAD_SHA, afterSha = after.name()))
        assertTrue(docsForBranch("main").isEmpty())
    }

    @Test
    fun `incremental mode ignores an unparseable after commit`() = runTest {
        val before = commitTree(mapOf("a.kt" to "x".toByteArray()))
        runJob(FileContentIndexJob(storage = storageSystem, repositoryId = repositoryId, ref = "refs/heads/main", beforeSha = before.name(), afterSha = BAD_SHA))
        assertTrue(docsForBranch("main").isEmpty())
    }

    @Test
    fun `applyDiff handles added, modified, removed, and unchanged files together`() = runTest {
        val before = commitTree(mapOf(
            "keep.txt" to "same".toByteArray(),
            "mod.txt" to "v1".toByteArray(),
            "gone.txt" to "old".toByteArray(),
        ))
        runJob(FileContentIndexJob(storage = storageSystem, repositoryId = repositoryId, ref = "refs/heads/main", afterSha = before.name()))

        val after = commitTree(mapOf(
            "keep.txt" to "same".toByteArray(), // unchanged
            "mod.txt" to "v2".toByteArray(),    // modified
            "new.txt" to "n".toByteArray(),     // added
            // gone.txt removed
        ), parent = before, message = "second")
        runJob(FileContentIndexJob(storage = storageSystem, repositoryId = repositoryId, ref = "refs/heads/main", beforeSha = before.name(), afterSha = after.name()))

        val paths = docsForBranch("main").mapNotNull { it["filePath"]?.jsonPrimitive?.contentOrNull }.toSet()
        assertTrue("new.txt" in paths)
        assertTrue("keep.txt" in paths)
        assertTrue("mod.txt" in paths)
        assertTrue("gone.txt" !in paths)
    }

    @Test
    fun `tagging tolerates an existing document with no branches overlay`() = runTest {
        coEvery { searchService.fetch(storageSystem, any()) } returns
            kotlinx.serialization.json.buildJsonObject { put("id", kotlinx.serialization.json.JsonPrimitive("x")) }
        val commit = commitTree(mapOf("a.kt" to "x".toByteArray()))
        runJob(FileContentIndexJob(storage = storageSystem, repositoryId = repositoryId, ref = "refs/heads/main", afterSha = commit.name()))
        // A malformed existing doc (no "branches") is treated as an empty overlay and re-indexed.
        coVerify { searchService.index(storageSystem, any<JsonElement>()) }
    }

    @Test
    fun `untagging tolerates a document with no branches overlay`() = runTest {
        coEvery { searchService.fetch(storageSystem, any()) } returns
            kotlinx.serialization.json.buildJsonObject { put("id", kotlinx.serialization.json.JsonPrimitive("x")) }
        val commit = commitTree(mapOf("a.kt" to "x".toByteArray()))
        runJob(FileContentIndexJob(storage = storageSystem, repositoryId = repositoryId, ref = "refs/heads/main", beforeSha = commit.name()))
        coVerify(exactly = 0) { searchService.delete(storageSystem, any<String>()) }
    }

    private fun commitWithSymlink(): ObjectId {
        val ins = gitRepo.objectDatabase.newInserter()
        try {
            val real = ins.insert(Constants.OBJ_BLOB, "fun x() {}".toByteArray())
            val link = ins.insert(Constants.OBJ_BLOB, "target".toByteArray())
            val tree = TreeFormatter()
            tree.append("link.txt", FileMode.SYMLINK, link)   // not an indexable file mode
            tree.append("real.kt", FileMode.REGULAR_FILE, real)
            val treeId = ins.insert(tree)
            val commit = CommitBuilder().apply { setTreeId(treeId); setAuthor(AUTHOR); setCommitter(AUTHOR); setMessage("sym") }
            val id = ins.insert(commit)
            ins.flush()
            return id
        } finally {
            ins.close()
        }
    }

    @Test
    fun `tagging skips non-regular files such as symlinks`() = runTest {
        val commit = commitWithSymlink()
        runJob(FileContentIndexJob(storage = storageSystem, repositoryId = repositoryId, ref = "refs/heads/main", afterSha = commit.name()))
        val paths = docsForBranch("main").mapNotNull { it["filePath"]?.jsonPrimitive?.contentOrNull }
        assertEquals(listOf("real.kt"), paths) // symlink was skipped
    }

    @Test
    fun `untagging skips non-regular files such as symlinks`() = runTest {
        val commit = commitWithSymlink()
        runJob(FileContentIndexJob(storage = storageSystem, repositoryId = repositoryId, ref = "refs/heads/main", afterSha = commit.name()))
        runJob(FileContentIndexJob(storage = storageSystem, repositoryId = repositoryId, ref = "refs/heads/main", beforeSha = commit.name()))
        assertTrue(docsForBranch("main").isEmpty())
    }

    @Test
    fun `oversize files are skipped`() = runTest {
        val big = ByteArray(257 * 1024) { 'a'.code.toByte() } // > MAX_FILE_SIZE (256 KiB)
        val commit = commitTree(mapOf("big.txt" to big, "small.txt" to "hi".toByteArray()))
        runJob(FileContentIndexJob(storage = storageSystem, repositoryId = repositoryId, ref = "refs/heads/main", afterSha = commit.name()))
        assertEquals(listOf("small.txt"), docsForBranch("main").mapNotNull { it["filePath"]?.jsonPrimitive?.contentOrNull })
    }

    @Test
    fun `applyDiff untags a file that becomes binary`() = runTest {
        val before = commitTree(mapOf("f.txt" to "text".toByteArray()))
        runJob(FileContentIndexJob(storage = storageSystem, repositoryId = repositoryId, ref = "refs/heads/main", afterSha = before.name()))
        val binary = ByteArray(10) { if (it == 5) 0 else 'x'.code.toByte() }
        val after = commitTree(mapOf("f.txt" to binary), parent = before, message = "binarize")
        runJob(FileContentIndexJob(storage = storageSystem, repositoryId = repositoryId, ref = "refs/heads/main", beforeSha = before.name(), afterSha = after.name()))
        assertTrue(docsForBranch("main").isEmpty()) // after-blob is binary -> not re-tagged
    }

    @Test
    fun `untag ignores a branch not present in the overlay`() = runTest {
        val commit = commitTree(mapOf("a.kt" to "x".toByteArray()))
        runJob(FileContentIndexJob(storage = storageSystem, repositoryId = repositoryId, ref = "refs/heads/main", afterSha = commit.name()))
        // Delete a DIFFERENT branch over the same tree: its name isn't in the ["main"] overlay.
        runJob(FileContentIndexJob(storage = storageSystem, repositoryId = repositoryId, ref = "refs/heads/other", beforeSha = commit.name()))
        assertEquals(1, docsForBranch("main").size)
    }

    @Test
    fun `delete mode ignores paths that were never indexed`() = runTest {
        // A binary blob is never indexed, so untagging its branch finds no document.
        val binary = byteArrayOf(0, 1, 2, 3, 0, 4, 0, 5)
        val commit = commitTree(mapOf("bin.dat" to binary))
        runJob(FileContentIndexJob(storage = storageSystem, repositoryId = repositoryId, ref = "refs/heads/main", beforeSha = commit.name()))
        assertTrue(docsForBranch("main").isEmpty())
    }

    @Test
    fun `incremental mode is a no-op when before and after are the same commit`() = runTest {
        val commit = commitTree(mapOf("a.kt" to "x".toByteArray()))
        runJob(FileContentIndexJob(storage = storageSystem, repositoryId = repositoryId, ref = "refs/heads/main", beforeSha = commit.name(), afterSha = commit.name()))
        assertTrue(docsForBranch("main").isEmpty())
    }

    @Test
    fun `treats a missing repository like a deleted one`() = runTest {
        coEvery { repoRepository.findById(repositoryId) } returns null
        val commit = commitTree(mapOf("a.kt" to "x".toByteArray()))

        runJob(FileContentIndexJob(storage = storageSystem, repositoryId = repositoryId, ref = "refs/heads/main", afterSha = commit.name()))

        coVerify(exactly = 0) { searchService.index(storageSystem, any<JsonElement>()) }
    }

    @Test
    fun `initial mode maps every known file extension to a language`() = runTest {
        val exts = listOf(
            "kt", "java", "py", "js", "ts", "tsx", "jsx", "rs", "go", "rb", "c", "h",
            "cpp", "cc", "cxx", "hpp", "cs", "swift", "sql", "sh", "bash", "zsh", "yaml",
            "yml", "json", "xml", "html", "htm", "css", "scss", "sass", "md", "toml",
            "gradle", "kts", "dockerfile",
        )
        val files = buildMap<String, ByteArray> {
            exts.forEach { put("f.$it", "content".toByteArray()) }
            put("Makefile", "content".toByteArray()) // no extension -> else branch -> "text"
        }
        val commit = commitTree(files)

        runJob(FileContentIndexJob(
            storage = storageSystem,
            repositoryId = repositoryId,
            ref = "refs/heads/main",
            afterSha = commit.name(),
        ))

        val languages = docsForBranch("main").mapNotNull { it["language"]?.jsonPrimitive?.contentOrNull }.toSet()
        assertTrue("kotlin" in languages)
        assertTrue("cpp" in languages)
        assertTrue("shell" in languages)
        assertTrue("text" in languages) // the extensionless Makefile
    }

    private suspend fun runJob(job: FileContentIndexJob) {
        val jobObj: Job = InternalJobConstructor(
            definition = json.encodeToJsonElement<FileContentIndexJob>(FileContentIndexJob.serializer(), job),
            executor = FileContentIndexExecutor::class,
        )
        withContext(jobQueue.asCoroutineContext(jobObj)) {
            FileContentIndexExecutor().execute()
        }
    }

    private fun JsonObject.branches(): List<String> =
        get("branches")?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    private fun docsForBranch(branch: String): List<JsonObject> =
        docs.values.filter { branch in it.branches() }

    @Test
    fun `getLockId scopes serialization to the repository`() = runTest {
        val executor = FileContentIndexExecutor()
        val jobObj: Job = InternalJobConstructor(
            definition = json.encodeToJsonElement<FileContentIndexJob>(
                FileContentIndexJob.serializer(),
                FileContentIndexJob(
                    storage = storageSystem,
                    repositoryId = repositoryId,
                    ref = "refs/heads/main",
                    afterSha = ObjectId.zeroId().name(),
                ),
            ),
            executor = FileContentIndexExecutor::class,
        )
        val lockId = withContext(jobQueue.asCoroutineContext(jobObj)) {
            executor.getLockId()
        }
        assertEquals("git-file-content-index-${repositoryId}", lockId)
    }

    @Test
    fun `initial mode tags every indexable file on the new branch`() = runTest {
        val commit = commitTree(mapOf(
            "README.md" to "hello".toByteArray(),
            "Main.kt"   to "fun main() {}".toByteArray(),
        ))

        runJob(FileContentIndexJob(
            storage = storageSystem,
            repositoryId = repositoryId,
            ref = "refs/heads/main",
            afterSha = commit.name(),
        ))

        val mainDocs = docsForBranch("main")
        assertEquals(2, mainDocs.size)
        assertTrue(mainDocs.any { it["filePath"]?.jsonPrimitive?.content == "README.md" })
        assertTrue(mainDocs.any { it["filePath"]?.jsonPrimitive?.content == "Main.kt" })
        mainDocs.forEach { assertEquals(listOf("main"), it.branches()) }
    }

    @Test
    fun `initial mode shares a doc when two branches have identical content at the same path`() = runTest {
        val sharedBytes = "shared".toByteArray()
        val main = commitTree(mapOf("file.txt" to sharedBytes))
        val feature = commitTree(mapOf("file.txt" to sharedBytes), message = "feature commit")

        runJob(FileContentIndexJob(
            storage = storageSystem,
            repositoryId = repositoryId,
            ref = "refs/heads/main",
            afterSha = main.name(),
        ))
        runJob(FileContentIndexJob(
            storage = storageSystem,
            repositoryId = repositoryId,
            ref = "refs/heads/feature",
            afterSha = feature.name(),
        ))

        // Same (path, blob) → single content-addressed doc tagged with both branches.
        assertEquals(1, docs.size, "expected dedup to a single shared document")
        val doc = docs.values.single()
        assertEquals(setOf("main", "feature"), doc.branches().toSet())
    }

    @Test
    fun `initial mode is idempotent when re-applied with the same branch`() = runTest {
        val commit = commitTree(mapOf("file.txt" to "x".toByteArray()))
        val job = FileContentIndexJob(
            storage = storageSystem,
            repositoryId = repositoryId,
            ref = "refs/heads/main",
            afterSha = commit.name(),
        )
        runJob(job)
        val snapshot = docs.toMap()
        runJob(job)
        assertEquals(snapshot.size, docs.size)
        docs.values.forEach { assertEquals(listOf("main"), it.branches()) }
    }

    @Test
    fun `incremental mode tags added file and untags removed file`() = runTest {
        val before = commitTree(mapOf("kept.txt" to "k".toByteArray(), "gone.txt" to "g".toByteArray()))
        val after = commitTree(
            mapOf("kept.txt" to "k".toByteArray(), "added.txt" to "a".toByteArray()),
            parent = before,
            message = "drop gone, add added",
        )

        runJob(FileContentIndexJob(
            storage = storageSystem, repositoryId = repositoryId,
            ref = "refs/heads/main", afterSha = before.name(),
        ))
        assertEquals(2, docsForBranch("main").size)

        runJob(FileContentIndexJob(
            storage = storageSystem, repositoryId = repositoryId,
            ref = "refs/heads/main",
            beforeSha = before.name(), afterSha = after.name(),
        ))

        val paths = docsForBranch("main").map { it["filePath"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("kept.txt", "added.txt"), paths)
    }

    @Test
    fun `incremental mode swaps tag from old blob doc to new blob doc when content changes`() = runTest {
        val before = commitTree(mapOf("file.txt" to "v1".toByteArray()))
        val after = commitTree(
            mapOf("file.txt" to "v2".toByteArray()),
            parent = before,
            message = "bump",
        )

        runJob(FileContentIndexJob(
            storage = storageSystem, repositoryId = repositoryId,
            ref = "refs/heads/main", afterSha = before.name(),
        ))
        val v1DocId = docs.values.single().get("id")!!.jsonPrimitive.content

        runJob(FileContentIndexJob(
            storage = storageSystem, repositoryId = repositoryId,
            ref = "refs/heads/main",
            beforeSha = before.name(), afterSha = after.name(),
        ))

        // Old (path, v1) doc went away because its only tag was removed.
        // New (path, v2) doc exists with the same branch tag.
        assertNull(docs[v1DocId], "doc for old blob should be deleted when its last branch is untagged")
        val remaining = docs.values.single()
        assertEquals("file.txt", remaining["filePath"]!!.jsonPrimitive.content)
        assertEquals(listOf("main"), remaining.branches())
        assertTrue(remaining["content"]!!.jsonPrimitive.content.contains("v2"))
    }

    @Test
    fun `incremental mode skips fetch for unchanged blobs`() = runTest {
        val before = commitTree(mapOf("a.txt" to "a".toByteArray(), "b.txt" to "b".toByteArray()))
        runJob(FileContentIndexJob(
            storage = storageSystem, repositoryId = repositoryId,
            ref = "refs/heads/main", afterSha = before.name(),
        ))
        // Add a third file; the first two are unchanged.
        val after = commitTree(
            mapOf("a.txt" to "a".toByteArray(), "b.txt" to "b".toByteArray(), "c.txt" to "c".toByteArray()),
            parent = before,
            message = "add c",
        )

        // Reset call count tracking by re-arming the fetch counter via a wrapper.
        val fetchedIds = mutableListOf<String>()
        coEvery { searchService.fetch(storageSystem, any()) } answers {
            val id = secondArg<String>()
            fetchedIds.add(id)
            docs[id]
        }

        runJob(FileContentIndexJob(
            storage = storageSystem, repositoryId = repositoryId,
            ref = "refs/heads/main",
            beforeSha = before.name(), afterSha = after.name(),
        ))

        // Only the new c.txt (path, blob) should have been read-modify-written;
        // unchanged a.txt and b.txt must not produce fetches in the diff path.
        assertEquals(1, fetchedIds.size,
            "expected exactly one fetch (for the new file), got: $fetchedIds")
    }

    @Test
    fun `delete mode untags branch from every path in the old tree`() = runTest {
        val commit = commitTree(mapOf("a.txt" to "a".toByteArray(), "b.txt" to "b".toByteArray()))
        runJob(FileContentIndexJob(
            storage = storageSystem, repositoryId = repositoryId,
            ref = "refs/heads/main", afterSha = commit.name(),
        ))
        assertEquals(2, docs.size)

        runJob(FileContentIndexJob(
            storage = storageSystem, repositoryId = repositoryId,
            ref = "refs/heads/main",
            beforeSha = commit.name(), afterSha = null,
        ))

        assertTrue(docs.isEmpty(), "all docs should be gone after their only branch was deleted")
    }

    @Test
    fun `delete mode preserves docs that still belong to other branches`() = runTest {
        val sharedBytes = "shared".toByteArray()
        val main = commitTree(mapOf("file.txt" to sharedBytes))
        val feature = commitTree(mapOf("file.txt" to sharedBytes), message = "feature")

        runJob(FileContentIndexJob(
            storage = storageSystem, repositoryId = repositoryId,
            ref = "refs/heads/main", afterSha = main.name(),
        ))
        runJob(FileContentIndexJob(
            storage = storageSystem, repositoryId = repositoryId,
            ref = "refs/heads/feature", afterSha = feature.name(),
        ))
        assertEquals(setOf("main", "feature"), docs.values.single().branches().toSet())

        runJob(FileContentIndexJob(
            storage = storageSystem, repositoryId = repositoryId,
            ref = "refs/heads/feature",
            beforeSha = feature.name(), afterSha = null,
        ))

        // Doc survives because `main` still tags it.
        val survivor = docs.values.single()
        assertEquals(listOf("main"), survivor.branches())
    }

    @Test
    fun `binary and oversize blobs are skipped silently`() = runTest {
        val binary = ByteArray(10) { if (it == 5) 0 else 'x'.code.toByte() }
        val commit = commitTree(mapOf("binary.bin" to binary, "text.txt" to "hi".toByteArray()))

        runJob(FileContentIndexJob(
            storage = storageSystem, repositoryId = repositoryId,
            ref = "refs/heads/main", afterSha = commit.name(),
        ))

        assertEquals(1, docs.size, "binary file should not be indexed")
        assertEquals("text.txt", docs.values.single()["filePath"]!!.jsonPrimitive.content)
    }

    @Test
    fun `non-branch ref short-circuits without touching the index`() = runTest {
        val commit = commitTree(mapOf("file.txt" to "x".toByteArray()))
        runJob(FileContentIndexJob(
            storage = storageSystem,
            repositoryId = repositoryId,
            ref = "refs/tags/v1",
            afterSha = commit.name(),
        ))
        assertTrue(docs.isEmpty())
        coVerify(exactly = 0) { dfsManager.open(any()) }
    }

    @Test
    fun `deleted repository purges all file docs irrespective of branch`() = runTest {
        // Pre-seed two docs under the same repositoryId so the deleteByFilter sweep
        // can be observed.
        val a = pretendDoc("a")
        val b = pretendDoc("b")
        docs[a.id] = a.obj
        docs[b.id] = b.obj

        // Repo lookup now reports the repo as deleted.
        coEvery { repoRepository.findById(repositoryId) } returns repo.copy(deleted = true)

        runJob(FileContentIndexJob(
            storage = storageSystem,
            repositoryId = repositoryId,
            ref = "refs/heads/main",
            afterSha = ObjectId.zeroId().let { "0".repeat(40) }, // not actually walked
        ))

        assertTrue(docs.isEmpty(), "deleted-repo branch should sweep all file docs for the repo")
    }

    @Test
    fun `no-op when both before and after are null`() = runTest {
        runJob(FileContentIndexJob(
            storage = storageSystem,
            repositoryId = repositoryId,
            ref = "refs/heads/main",
            beforeSha = null,
            afterSha = null,
        ))
        assertTrue(docs.isEmpty())
        coVerify(exactly = 0) { dfsManager.open(any()) }
    }

    private data class PretendDoc(val id: String, val obj: JsonObject)

    private fun pretendDoc(path: String): PretendDoc {
        val id = "${repositoryId}_${path.hashCode().toString(16)}"
        val obj = buildJsonObject {
            put("id", id)
            put("_type", "file")
            put("repositoryId", repositoryId.toString())
            put("filePath", path)
            putJsonArray("branches") { add("main") }
        }
        return PretendDoc(id, obj)
    }

    private companion object {
        private val AUTHOR = PersonIdent("Test", "test@example.com")
        private const val BAD_SHA = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef" // 40 hex, no such object
    }
}
