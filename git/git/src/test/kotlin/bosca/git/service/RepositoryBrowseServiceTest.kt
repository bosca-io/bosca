@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.service

import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.asCoroutineContext
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.model.DiffLineType
import bosca.git.model.TreeEntryType
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.put
import kotlinx.coroutines.withContext
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

class RepositoryBrowseServiceTest {

    private val dfsManager = mockk<BoscaDfsRepositoryManager>()
    private val packRepository = mockk<bosca.git.repository.DfsPackRepository>(relaxed = true)
    private val searchService = mockk<bosca.search.service.SearchService>(relaxed = true)
    private val cacheManager = mockk<CacheManager>(relaxed = true)
    private val cacheSerializer = mockk<RequestCacheSerializer>(relaxed = true)
    private lateinit var service: RepositoryBrowseServiceImpl

    private val repositoryId = UUID.random()
    private lateinit var inMemoryRepo: InMemoryRepository

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<CacheManager>(singleton = true) { cacheManager }
        provides<RequestCacheSerializer>(singleton = true) { cacheSerializer }

        service = RepositoryBrowseServiceImpl(dfsManager, packRepository, searchService)

        inMemoryRepo = InMemoryRepository(DfsRepositoryDescription("test"))
        createTestContent()

        every { dfsManager.open(repositoryId) } returns inMemoryRepo
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    private suspend fun <T> withCache(block: suspend () -> T): T {
        val cache = RequestCache(cacheManager, cacheSerializer)
        return withContext(cache.asCoroutineContext()) { block() }
    }

    private var firstCommitId: ObjectId = ObjectId.zeroId()
    private var secondCommitId: ObjectId = ObjectId.zeroId()

    private fun createTestContent() {
        val inserter = inMemoryRepo.objectDatabase.newInserter()
        val author = PersonIdent("Test Author", "test@example.com")

        val readmeContent = "# Hello World\nThis is a test."
        val readmeId = inserter.insert(Constants.OBJ_BLOB, readmeContent.toByteArray())

        val codeContent = "fun main() { println(\"Hello\") }"
        val codeId = inserter.insert(Constants.OBJ_BLOB, codeContent.toByteArray())

        val helperContent = "fun helper() {}"
        val helperId = inserter.insert(Constants.OBJ_BLOB, helperContent.toByteArray())

        val utilTree = TreeFormatter()
        utilTree.append("Helper.kt", FileMode.REGULAR_FILE, helperId)
        val utilTreeId = inserter.insert(utilTree)

        val srcTree = TreeFormatter()
        srcTree.append("Main.kt", FileMode.REGULAR_FILE, codeId)
        srcTree.append("util", FileMode.TREE, utilTreeId)
        val srcTreeId = inserter.insert(srcTree)

        val rootTree = TreeFormatter()
        rootTree.append("README.md", FileMode.REGULAR_FILE, readmeId)
        rootTree.append("src", FileMode.TREE, srcTreeId)
        val rootTreeId = inserter.insert(rootTree)

        val commit = CommitBuilder()
        commit.setTreeId(rootTreeId)
        commit.setAuthor(author)
        commit.setCommitter(author)
        commit.setMessage("Initial commit")
        firstCommitId = inserter.insert(commit)
        inserter.flush()

        val refUpdate = inMemoryRepo.refDatabase.newUpdate("refs/heads/main", true)
        refUpdate.setNewObjectId(firstCommitId)
        refUpdate.update()

        val updatedReadme = "# Hello World\nThis is a test.\n\nUpdated content."
        val updatedReadmeId = inserter.insert(Constants.OBJ_BLOB, updatedReadme.toByteArray())

        val newFileContent = "new file content"
        val newFileId = inserter.insert(Constants.OBJ_BLOB, newFileContent.toByteArray())

        val rootTree2 = TreeFormatter()
        rootTree2.append("README.md", FileMode.REGULAR_FILE, updatedReadmeId)
        rootTree2.append("new-file.txt", FileMode.REGULAR_FILE, newFileId)
        rootTree2.append("src", FileMode.TREE, srcTreeId)
        val rootTreeId2 = inserter.insert(rootTree2)

        val commit2 = CommitBuilder()
        commit2.setTreeId(rootTreeId2)
        commit2.setAuthor(PersonIdent("Second Author", "second@example.com"))
        commit2.setCommitter(PersonIdent("Second Author", "second@example.com"))
        commit2.setMessage("Add new file and update README")
        commit2.setParentId(firstCommitId)
        secondCommitId = inserter.insert(commit2)
        inserter.flush()

        val featureUpdate = inMemoryRepo.refDatabase.newUpdate("refs/heads/feature", true)
        featureUpdate.setNewObjectId(secondCommitId)
        featureUpdate.update()

        val headUpdate = inMemoryRepo.refDatabase.newUpdate(Constants.HEAD, true)
        headUpdate.link("refs/heads/main")
    }

    @Test
    fun `listTree returns root entries`() = runTest {
        withCache {
            val entries = service.listTree(repositoryId, "refs/heads/main", null)
            assertEquals(2, entries.size)
            assertTrue(entries.any { it.name == "README.md" && it.type == TreeEntryType.BLOB })
            assertTrue(entries.any { it.name == "src" && it.type == TreeEntryType.TREE })
        }
    }

    @Test
    fun `listTree returns subdirectory entries including nested directories`() = runTest {
        withCache {
            val entries = service.listTree(repositoryId, "refs/heads/main", "src")
            assertEquals(2, entries.size)
            assertTrue(entries.any { it.name == "Main.kt" && it.type == TreeEntryType.BLOB })
            assertTrue(entries.any { it.name == "util" && it.type == TreeEntryType.TREE })
        }
    }

    @Test
    fun `listTree returns correct full paths for subdirectory entries`() = runTest {
        withCache {
            val entries = service.listTree(repositoryId, "refs/heads/main", "src")
            val mainKt = entries.find { it.name == "Main.kt" }
            assertNotNull(mainKt)
            assertEquals("src/Main.kt", mainKt.path)
            val util = entries.find { it.name == "util" }
            assertNotNull(util)
            assertEquals("src/util", util.path)
        }
    }

    @Test
    fun `listTree returns nested subdirectory contents`() = runTest {
        withCache {
            val entries = service.listTree(repositoryId, "refs/heads/main", "src/util")
            assertEquals(1, entries.size)
            assertEquals("Helper.kt", entries[0].name)
            assertEquals("src/util/Helper.kt", entries[0].path)
        }
    }

    @Test
    fun `listTree returns empty for non-existent path`() = runTest {
        withCache {
            val entries = service.listTree(repositoryId, "refs/heads/main", "nonexistent")
            assertTrue(entries.isEmpty())
        }
    }

    @Test
    fun `readBlob returns text content for text file`() = runTest {
        val blob = service.readBlob(repositoryId, "refs/heads/main", "README.md")
        assertNotNull(blob)
        assertFalse(blob.isBinary)
        assertTrue(blob.content!!.contains("Hello World"))
        assertEquals("text/markdown", blob.mimeType)
    }

    @Test
    fun `readBlob returns null for non-existent path`() = runTest {
        val blob = service.readBlob(repositoryId, "refs/heads/main", "does-not-exist.txt")
        assertNull(blob)
    }

    @Test
    fun `listCommits returns commit history`() = runTest {
        val commits = service.listCommits(repositoryId, "refs/heads/main", null, 10, 0)
        assertEquals(1, commits.size)
        assertEquals("Initial commit", commits[0].message)
        assertEquals("Test Author", commits[0].authorName)
        assertEquals("test@example.com", commits[0].authorEmail)
    }

    @Test
    fun `listCommits returns empty for non-existent ref`() = runTest {
        val commits = service.listCommits(repositoryId, "refs/heads/nonexistent", null, 10, 0)
        assertTrue(commits.isEmpty())
    }

    @Test
    fun `listBranches returns branch list`() = runTest {
        val branches = service.listBranches(repositoryId)
        assertEquals(2, branches.size)
        assertTrue(branches.any { it.name == "main" })
        assertTrue(branches.any { it.name == "feature" })
    }

    @Test
    fun `getStats returns correct counts`() = runTest {
        val stats = service.getStats(repositoryId)
        assertEquals(1, stats.commitCount)
        assertEquals(2, stats.branchCount)
        assertEquals(0, stats.tagCount)
        assertEquals(1, stats.contributorCount)
    }

    @Test
    fun `listTags returns empty when no tags exist`() = runTest {
        val tags = service.listTags(repositoryId)
        assertTrue(tags.isEmpty())
    }

    @Test
    fun `blame returns per-line attribution`() = runTest {
        val lines = service.blame(repositoryId, "refs/heads/main", "README.md")
        assertTrue(lines.isNotEmpty())
        assertEquals(1, lines[0].lineNumber)
        assertEquals("Test Author", lines[0].authorName)
        assertTrue(lines[0].content.contains("Hello World"))
    }

    @Test
    fun `compare returns diff between two refs`() = runTest {
        val diffService = DiffServiceImpl(dfsManager)
        val result = service.compare(repositoryId, "refs/heads/main", "refs/heads/feature", diffService)
        assertEquals("refs/heads/main", result.baseRef)
        assertEquals("refs/heads/feature", result.headRef)
        assertEquals(1, result.commits.size)
        assertTrue(result.filesChanged > 0)
        assertTrue(result.files.any { it.newPath == "new-file.txt" })
    }

    @Test
    fun `searchPaths finds files matching query`() = runTest {
        val results = service.searchPaths(repositoryId, "README", "refs/heads/main")
        assertTrue(results.isNotEmpty())
        assertTrue(results.any { it.name == "README.md" })
    }

    @Test
    fun `searchContent maps Meilisearch hits and computes line numbers from content`() = runTest {
        val capturedQuery = slot<bosca.search.model.SearchQuery>()
        coEvery { searchService.searchRaw(capture(capturedQuery)) } returns bosca.search.model.RawSearchResult(
            hits = listOf(
                kotlinx.serialization.json.buildJsonObject {
                    put("filePath", "README.md")
                    put("content", "# Title\nHello World\nThird line")
                },
                kotlinx.serialization.json.buildJsonObject {
                    put("filePath", "src/Main.kt")
                    put("content", "line 1\nline 2 with Hello World\nline 3")
                },
            ),
            facets = emptyList(),
            estimatedHits = 2L,
            system = bosca.search.IndexStorageSystem(UUID.random(), "git-code"),
        )

        val results = service.searchContent(repositoryId, "Hello World", "refs/heads/main")

        assertEquals(2, results.size)
        assertEquals("README.md", results[0].filePath)
        assertEquals(2, results[0].lineNumber)
        assertEquals("Hello World", results[0].snippet)
        assertEquals("src/Main.kt", results[1].filePath)
        assertEquals(2, results[1].lineNumber)

        // Verify the filter set the resolver constructed: scoped to file docs in this
        // repo on the resolved branch. Short branch derivation strips the refs/heads/
        // prefix so the index filter matches what FileContentIndexExecutor writes.
        val filters = capturedQuery.captured.filter ?: emptyList()
        assertTrue(filters.any { it == "_type = \"file\"" })
        assertTrue(filters.any { it.contains("repositoryId") && it.contains(repositoryId.toString()) })
        assertTrue(filters.any { it == "branches = \"main\"" })
    }

    @Test
    fun `searchContent accepts short branch names without refs prefix`() = runTest {
        val capturedQuery = slot<bosca.search.model.SearchQuery>()
        coEvery { searchService.searchRaw(capture(capturedQuery)) } returns bosca.search.model.RawSearchResult(
            hits = emptyList(),
            facets = emptyList(),
            estimatedHits = 0L,
            system = bosca.search.IndexStorageSystem(UUID.random(), "git-code"),
        )

        service.searchContent(repositoryId, "anything", "feature/abc")

        assertTrue((capturedQuery.captured.filter ?: emptyList()).any { it == "branches = \"feature/abc\"" })
    }

    @Test
    fun `searchContent returns empty when Meilisearch has no hits`() = runTest {
        coEvery { searchService.searchRaw(any()) } returns bosca.search.model.RawSearchResult(
            hits = emptyList(),
            facets = emptyList(),
            estimatedHits = 0L,
            system = bosca.search.IndexStorageSystem(UUID.random(), "git-code"),
        )

        val results = service.searchContent(repositoryId, "xyznonexistent", "refs/heads/main")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `searchContent skips Meilisearch entirely for blank query`() = runTest {
        val results = service.searchContent(repositoryId, "   ", "refs/heads/main")
        assertTrue(results.isEmpty())
        coVerify(exactly = 0) { searchService.searchRaw(any()) }
    }

    @Test
    fun `searchContent drops hits whose indexed content does not contain the query line`() = runTest {
        // Meilisearch can match on `filePath` or `fileName`, so a hit may carry content
        // that does not itself contain the query (e.g., binary stub or stripped fields).
        // The resolver should drop such hits rather than emit a result with line 0.
        coEvery { searchService.searchRaw(any()) } returns bosca.search.model.RawSearchResult(
            hits = listOf(
                kotlinx.serialization.json.buildJsonObject {
                    put("filePath", "matched-by-name.txt")
                    put("content", "no match here")
                },
            ),
            facets = emptyList(),
            estimatedHits = 1L,
            system = bosca.search.IndexStorageSystem(UUID.random(), "git-code"),
        )

        val results = service.searchContent(repositoryId, "Hello World", "refs/heads/main")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `listBranches with feature branch shows ahead-behind`() = runTest {
        val branches = service.listBranches(repositoryId)
        assertEquals(2, branches.size)
        val feature = branches.find { it.name == "feature" }
        assertNotNull(feature)
        assertEquals(1, feature.ahead)
    }

    @Test
    fun `listChangedPaths returns modified and added paths between two commits`() = runTest {
        val paths = service.listChangedPaths(repositoryId, firstCommitId.name(), secondCommitId.name())
        assertTrue(paths.contains("README.md"))
        assertTrue(paths.contains("new-file.txt"))
    }

    @Test
    fun `listChangedPaths returns empty set when beforeSha is the zero id`() = runTest {
        val paths = service.listChangedPaths(repositoryId, ObjectId.zeroId().name(), secondCommitId.name())
        assertTrue(paths.isEmpty())
    }

    @Test
    fun `listChangedPaths returns empty set when afterSha is the zero id`() = runTest {
        val paths = service.listChangedPaths(repositoryId, firstCommitId.name(), ObjectId.zeroId().name())
        assertTrue(paths.isEmpty())
    }

    @Test
    fun `listChangedPaths returns empty set when commits are identical`() = runTest {
        val paths = service.listChangedPaths(repositoryId, firstCommitId.name(), firstCommitId.name())
        assertTrue(paths.isEmpty())
    }

    @Test
    fun `resolveRef returns the commit SHA for an existing branch`() = runTest {
        val sha = service.resolveRef(repositoryId, "refs/heads/main")
        assertEquals(firstCommitId.name(), sha)
    }

    @Test
    fun `resolveRef returns null for a non-existent ref`() = runTest {
        val sha = service.resolveRef(repositoryId, "refs/heads/no-such-branch")
        assertNull(sha)
    }

    // ── appended coverage: getCommit, paging, search, blob edge cases ────

    @Test
    fun `getCommit returns commit info and null for an unknown sha`() = runTest {
        val info = service.getCommit(repositoryId, secondCommitId.name())
        assertEquals(secondCommitId.name(), info?.sha)
        assertEquals(listOf(firstCommitId.name()), info?.parentShas)

        assertNull(service.getCommit(repositoryId, "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"))
    }

    @Test
    fun `listCommits honors offset and a path filter`() = runTest {
        val all = service.listCommits(repositoryId, "refs/heads/feature", null, 10, 0)
        val skipped = service.listCommits(repositoryId, "refs/heads/feature", null, 10, 1)
        assertEquals(all.size - 1, skipped.size)
        assertEquals(all[1].sha, skipped[0].sha)

        // Path-filtered history only includes commits touching that path.
        val filtered = service.listCommits(repositoryId, "refs/heads/feature", "README.md", 10, 0)
        assertTrue(filtered.isNotEmpty())
    }

    @Test
    fun `searchCode maps hits, applies filters, and drops incomplete hits`() = runTest {
        val captured = slot<bosca.search.model.SearchQuery>()
        coEvery { searchService.searchRaw(capture(captured)) } returns bosca.search.model.RawSearchResult(
            hits = listOf(
                kotlinx.serialization.json.buildJsonObject {
                    put("repositoryId", repositoryId.toString())
                    put("name", "Repo"); put("slug", "repo")
                    put("filePath", "Main.kt"); put("language", "kotlin"); put("content", "fun main() {}")
                },
                kotlinx.serialization.json.buildJsonObject { put("filePath", "orphan.kt") }, // no repositoryId -> dropped
            ),
            facets = emptyList(), estimatedHits = 2L,
            system = bosca.search.IndexStorageSystem(UUID.random(), "git-code"),
        )

        val response = service.searchCode("main", repositoryId, "kotlin", 0, 20)

        assertEquals(1, response.results.size)
        assertEquals("Main.kt", response.results[0].filePath)
        assertEquals("kotlin", response.results[0].language)
        val filters = captured.captured.filter.orEmpty()
        assertTrue(filters.any { it.contains("_type") })
        assertTrue(filters.any { it.contains(repositoryId.toString()) })
        assertTrue(filters.any { it.contains("language") })
    }

    @Test
    fun `searchCode without optional filters only scopes to file docs`() = runTest {
        val captured = slot<bosca.search.model.SearchQuery>()
        coEvery { searchService.searchRaw(capture(captured)) } returns bosca.search.model.RawSearchResult(
            hits = emptyList(), facets = emptyList(), estimatedHits = 0L,
            system = bosca.search.IndexStorageSystem(UUID.random(), "git-code"),
        )
        service.searchCode("q", null, null, 0, 20)
        assertEquals(1, captured.captured.filter.orEmpty().size)
    }

    @Test
    fun `searchRepositories maps hits, applies filters, and drops incomplete hits`() = runTest {
        val ownerId = UUID.random()
        val hitId = UUID.random()
        val captured = slot<bosca.search.model.SearchQuery>()
        coEvery { searchService.searchRaw(capture(captured)) } returns bosca.search.model.RawSearchResult(
            hits = listOf(
                kotlinx.serialization.json.buildJsonObject {
                    put("id", hitId.toString()); put("name", "Repo"); put("slug", "repo")
                    put("description", "d"); put("ownerId", ownerId.toString())
                    put("visibility", "PRIVATE"); put("defaultBranch", "main")
                    put("archived", false)
                },
                kotlinx.serialization.json.buildJsonObject { put("name", "no-id") }, // dropped
            ),
            facets = emptyList(), estimatedHits = 2L,
            system = bosca.search.IndexStorageSystem(UUID.random(), "git-code"),
        )

        val response = service.searchRepositories("repo", "PRIVATE", false, 0, 20)

        assertEquals(1, response.results.size)
        assertEquals(hitId, response.results[0].id)
        val filters = captured.captured.filter.orEmpty()
        assertTrue(filters.any { it.contains("visibility") })
        assertTrue(filters.any { it.contains("archived = false") })
    }

    private fun commitExtraFiles(files: Map<String, ByteArray>): ObjectId {
        val inserter = inMemoryRepo.objectDatabase.newInserter()
        val tree = TreeFormatter()
        for ((name, bytes) in files.entries.sortedBy { it.key }) {
            tree.append(name, FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, bytes))
        }
        val treeId = inserter.insert(tree)
        val author = PersonIdent("Test", "t@x")
        val commit = org.eclipse.jgit.lib.CommitBuilder().apply {
            setTreeId(treeId); setAuthor(author); setCommitter(author); setMessage("extra")
        }
        val id = inserter.insert(commit)
        inserter.flush()
        inMemoryRepo.refDatabase.newUpdate("refs/heads/extra", true).apply { setNewObjectId(id); update() }
        return id
    }

    @Test
    fun `readBlob flags binary content and returns no inline text`() = runTest {
        commitExtraFiles(mapOf("bin.dat" to byteArrayOf(1, 0, 2, 0, 3)))
        val blob = service.readBlob(repositoryId, "refs/heads/extra", "bin.dat")
        assertEquals(true, blob?.isBinary)
        assertNull(blob?.content)
    }

    @Test
    fun `readBlob returns metadata only for oversized files`() = runTest {
        commitExtraFiles(mapOf("huge.txt" to ByteArray(1024 * 1024 + 1) { 'a'.code.toByte() }))
        val blob = service.readBlob(repositoryId, "refs/heads/extra", "huge.txt")
        assertEquals(true, blob?.isBinary)
        assertNull(blob?.content)
        assertEquals(1024L * 1024L + 1L, blob?.size)
    }

    @Test
    fun `readBlob returns null for an unknown ref`() = runTest {
        assertNull(service.readBlob(repositoryId, "refs/heads/nope", "README.md"))
    }

    @Test
    fun `readBlob infers mime types from extensions`() = runTest {
        val files = mapOf(
            "a.kt" to "text/plain", "a.md" to "text/markdown", "a.json" to "application/json",
            "a.yaml" to "text/yaml", "a.xml" to "application/xml", "a.html" to "text/html",
            "a.css" to "text/css", "a.png" to "image/png", "a.jpg" to "image/jpeg",
            "a.gif" to "image/gif", "a.svg" to "image/svg+xml", "a.pdf" to "application/pdf",
            "LICENSE" to null,
        )
        commitExtraFiles(files.keys.associateWith { "x".toByteArray() })
        for ((name, mime) in files) {
            assertEquals(mime, service.readBlob(repositoryId, "refs/heads/extra", name)?.mimeType, "for $name")
        }
    }

    @Test
    fun `listTree returns empty when the path is a file`() = runTest {
        val entries = withCache { service.listTree(repositoryId, "refs/heads/main", "README.md") }
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `listTags reports annotated and lightweight tags`() = runTest {
        // Lightweight tag: ref straight at the commit.
        inMemoryRepo.refDatabase.newUpdate("refs/tags/light", true).apply {
            setNewObjectId(firstCommitId); update()
        }
        // Annotated tag: a tag object pointing at the commit.
        val ins = inMemoryRepo.objectDatabase.newInserter()
        val tag = org.eclipse.jgit.lib.TagBuilder().apply {
            setObjectId(firstCommitId, Constants.OBJ_COMMIT)
            tag = "v1"
            tagger = PersonIdent("Tagger", "tag@x")
            message = "release v1"
        }
        val tagId = ins.insert(tag)
        ins.flush()
        inMemoryRepo.refDatabase.newUpdate("refs/tags/v1", true).apply {
            setNewObjectId(tagId); update()
        }

        val tags = service.listTags(repositoryId).sortedBy { it.name }

        assertEquals(listOf("light", "v1"), tags.map { it.name })
        val light = tags[0]
        assertEquals(false, light.isAnnotated)
        assertEquals(firstCommitId.name(), light.sha)
        val annotated = tags[1]
        assertEquals(true, annotated.isAnnotated)
        assertEquals(tagId.name(), annotated.sha)
        assertEquals(firstCommitId.name(), annotated.targetSha)
        assertEquals("Tagger", annotated.taggerName)
        assertEquals("release v1", annotated.message)
    }

    @Test
    fun `blame returns empty for an unknown ref`() = runTest {
        assertTrue(service.blame(repositoryId, "refs/heads/nope", "README.md").isEmpty())
    }

    @Test
    fun `searchCode falls back through repositoryName-name and fills blanks`() = runTest {
        coEvery { searchService.searchRaw(any()) } returns bosca.search.model.RawSearchResult(
            hits = listOf(
                // Uses the repositoryName/repositorySlug keys directly.
                kotlinx.serialization.json.buildJsonObject {
                    put("repositoryId", repositoryId.toString())
                    put("repositoryName", "RN"); put("repositorySlug", "rs")
                    put("filePath", "A.kt"); put("language", "kotlin"); put("content", "x".repeat(600))
                },
                // Falls back to name/slug, and to empty strings for the rest.
                kotlinx.serialization.json.buildJsonObject {
                    put("repositoryId", repositoryId.toString())
                    put("name", "N"); put("slug", "s")
                },
            ),
            facets = emptyList(), estimatedHits = 2L,
            system = bosca.search.IndexStorageSystem(UUID.random(), "git-code"),
        )

        val response = service.searchCode("q", null, null, 0, 20)

        assertEquals("RN", response.results[0].repositoryName)
        assertEquals("rs", response.results[0].repositorySlug)
        assertEquals(500, response.results[0].snippet.length) // content capped at 500
        assertEquals("N", response.results[1].repositoryName)
        assertEquals("s", response.results[1].repositorySlug)
        assertEquals("", response.results[1].filePath)
        assertEquals("", response.results[1].language)
        assertEquals("", response.results[1].snippet)
    }

    @Test
    fun `searchRepositories fills defaults and drops hits without an owner`() = runTest {
        val hitId = UUID.random()
        coEvery { searchService.searchRaw(any()) } returns bosca.search.model.RawSearchResult(
            hits = listOf(
                // Minimal hit: everything except ids falls back to defaults.
                kotlinx.serialization.json.buildJsonObject {
                    put("id", hitId.toString()); put("ownerId", UUID.random().toString())
                },
                // Has an id but no ownerId -> dropped.
                kotlinx.serialization.json.buildJsonObject { put("id", UUID.random().toString()) },
            ),
            facets = emptyList(), estimatedHits = 2L,
            system = bosca.search.IndexStorageSystem(UUID.random(), "git-code"),
        )

        val response = service.searchRepositories("q", null, null, 0, 20)

        assertEquals(1, response.results.size)
        val r = response.results[0]
        assertEquals(hitId, r.id)
        assertEquals("", r.name); assertEquals("", r.slug); assertEquals("", r.description)
        assertEquals(false, r.archived)
    }

    @Test
    fun `listTree and listCommits tolerate unknown refs and empty paths`() = runTest {
        assertTrue(withCache { service.listTree(repositoryId, "refs/heads/nope", null) }.isEmpty())
        // Empty path behaves like the root listing.
        assertTrue(withCache { service.listTree(repositoryId, "refs/heads/main", "") }.isNotEmpty())
        assertTrue(service.listCommits(repositoryId, "refs/heads/main", "", 10, 0).isNotEmpty())
    }

    @Test
    fun `compare and searchPaths return empty results for unknown refs`() = runTest {
        val cmp = service.compare(repositoryId, "refs/heads/nope", "refs/heads/main", mockk(relaxed = true))
        assertEquals(0, cmp.filesChanged)
        val cmp2 = service.compare(repositoryId, "refs/heads/main", "refs/heads/nope", mockk(relaxed = true))
        assertEquals(0, cmp2.filesChanged)
        assertTrue(service.searchPaths(repositoryId, "x", "refs/heads/nope").isEmpty())
    }

    @Test
    fun `searchContent guards empty branches and malformed hits`() = runTest {
        // A ref that strips to an empty branch name skips the search entirely.
        assertTrue(service.searchContent(repositoryId, "q", "refs/heads/", 10).isEmpty())

        coEvery { searchService.searchRaw(any()) } returns bosca.search.model.RawSearchResult(
            hits = listOf(
                kotlinx.serialization.json.buildJsonObject { put("content", "has Hello here") }, // no filePath
                kotlinx.serialization.json.buildJsonObject { put("filePath", "a.txt"); put("content", "") }, // empty content
                kotlinx.serialization.json.buildJsonObject {
                    put("filePath", "b.txt")
                    // JsonNull content -> str() resolves null.
                    put("content", kotlinx.serialization.json.JsonNull)
                },
            ),
            facets = emptyList(), estimatedHits = 3L,
            system = bosca.search.IndexStorageSystem(UUID.random(), "git-code"),
        )
        assertTrue(service.searchContent(repositoryId, "Hello", "main", 10).isEmpty())
    }

    @Test
    fun `searchCode blanks both name fallbacks and searchRepositories parses archived strings`() = runTest {
        coEvery { searchService.searchRaw(any()) } returns bosca.search.model.RawSearchResult(
            hits = listOf(kotlinx.serialization.json.buildJsonObject { put("repositoryId", repositoryId.toString()) }),
            facets = emptyList(), estimatedHits = 1L,
            system = bosca.search.IndexStorageSystem(UUID.random(), "git-code"),
        )
        val code = service.searchCode("q", null, null, 0, 10)
        assertEquals("", code.results[0].repositoryName)
        assertEquals("", code.results[0].repositorySlug)

        coEvery { searchService.searchRaw(any()) } returns bosca.search.model.RawSearchResult(
            hits = listOf(kotlinx.serialization.json.buildJsonObject {
                put("id", UUID.random().toString()); put("ownerId", UUID.random().toString())
                put("archived", "true")
            }),
            facets = emptyList(), estimatedHits = 1L,
            system = bosca.search.IndexStorageSystem(UUID.random(), "git-code"),
        )
        assertEquals(true, service.searchRepositories("q", null, null, 0, 10).results[0].archived)
    }

    @Test
    fun `listTree reports submodule entries`() = runTest {
        val ins = inMemoryRepo.objectDatabase.newInserter()
        val person = PersonIdent("Test", "test@example.com")
        val tree = TreeFormatter()
        // A gitlink entry points at a commit sha in another repository.
        tree.append("vendored", FileMode.GITLINK, firstCommitId)
        val blob = ins.insert(Constants.OBJ_BLOB, "x".toByteArray())
        tree.append("wrapper.txt", FileMode.REGULAR_FILE, blob)
        val commit = org.eclipse.jgit.lib.CommitBuilder().apply {
            setTreeId(ins.insert(tree)); setAuthor(person); setCommitter(person); setMessage("sub")
        }
        val id = ins.insert(commit)
        ins.flush()
        inMemoryRepo.refDatabase.newUpdate("refs/heads/sub", true).apply { setNewObjectId(id); update() }

        val entries = withCache { service.listTree(repositoryId, "refs/heads/sub", null) }
        assertEquals(
            bosca.git.model.TreeEntryType.SUBMODULE,
            entries.single { it.name == "vendored" }.type,
        )
    }

    @Test
    fun `blame returns empty for a missing file`() = runTest {
        assertTrue(service.blame(repositoryId, "refs/heads/main", "missing.txt").isEmpty())
    }

    @Test
    fun `annotated tag without a tagger still lists`() = runTest {
        val ins = inMemoryRepo.objectDatabase.newInserter()
        val tag = org.eclipse.jgit.lib.TagBuilder().apply {
            setObjectId(firstCommitId, Constants.OBJ_COMMIT)
            tag = "untagged-by"
            message = "no tagger"
        }
        val tagId = ins.insert(tag)
        ins.flush()
        inMemoryRepo.refDatabase.newUpdate("refs/tags/untagged-by", true).apply { setNewObjectId(tagId); update() }

        val info = service.listTags(repositoryId).single { it.name == "untagged-by" }
        assertEquals(null, info.taggerName)
        assertEquals(true, info.isAnnotated)
    }

    @Test
    fun `listChangedPaths includes deletions`() = runTest {
        val ins = inMemoryRepo.objectDatabase.newInserter()
        val person = PersonIdent("Test", "test@example.com")
        // Empty tree commit on top of main: everything from main is deleted.
        val commit = org.eclipse.jgit.lib.CommitBuilder().apply {
            setTreeId(ins.insert(TreeFormatter())); setAuthor(person); setCommitter(person)
            setMessage("wipe"); setParentId(firstCommitId)
        }
        val id = ins.insert(commit)
        ins.flush()

        val changed = service.listChangedPaths(repositoryId, firstCommitId.name(), id.name())
        assertTrue(changed.contains("README.md"))
    }

    @Test
    fun `listCommits stops at the requested limit`() = runTest {
        assertEquals(1, service.listCommits(repositoryId, "refs/heads/feature", null, 1, 0).size)
    }

    @Test
    fun `searchContent returns empty for a non-positive limit`() = runTest {
        assertTrue(service.searchContent(repositoryId, "q", "main", 0).isEmpty())
    }

    @Test
    fun `searchContent snippets default to empty for content-less hits`() = runTest {
        coEvery { searchService.searchRaw(any()) } returns bosca.search.model.RawSearchResult(
            hits = listOf(kotlinx.serialization.json.buildJsonObject { put("filePath", "a.txt") }),
            facets = emptyList(), estimatedHits = 1L,
            system = bosca.search.IndexStorageSystem(UUID.random(), "git-code"),
        )
        // No content -> the hit is dropped by the line-matching stage.
        assertTrue(service.searchContent(repositoryId, "q", "main", 10).isEmpty())
    }

    @Test
    fun `listTree reports symlink entries`() = runTest {
        val ins = inMemoryRepo.objectDatabase.newInserter()
        val person = PersonIdent("Test", "test@example.com")
        val link = ins.insert(Constants.OBJ_BLOB, "target".toByteArray())
        val tree = TreeFormatter().apply { append("ln", FileMode.SYMLINK, link) }
        val commit = org.eclipse.jgit.lib.CommitBuilder().apply {
            setTreeId(ins.insert(tree)); setAuthor(person); setCommitter(person); setMessage("ln")
        }
        val id = ins.insert(commit)
        ins.flush()
        inMemoryRepo.refDatabase.newUpdate("refs/heads/links", true).apply { setNewObjectId(id); update() }

        val entries = withCache { service.listTree(repositoryId, "refs/heads/links", null) }
        assertEquals(bosca.git.model.TreeEntryType.SYMLINK, entries.single().type)
    }

    @Test
    fun `listBranches counts a branch that is behind the default`() = runTest {
        // A branch parked at the first commit while the default advanced.
        inMemoryRepo.refDatabase.newUpdate("refs/heads/stale", true).apply {
            setNewObjectId(firstCommitId); update()
        }
        val stale = service.listBranches(repositoryId).single { it.name == "stale" }
        assertEquals(0, stale.ahead)
    }
}
