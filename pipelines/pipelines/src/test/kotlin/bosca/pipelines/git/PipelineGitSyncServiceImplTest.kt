package bosca.pipelines.git

import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.git.model.Blob
import bosca.git.model.TreeEntry
import bosca.git.model.TreeEntryType
import bosca.git.service.CommitFileInput
import bosca.git.service.CommitFileResult
import bosca.git.service.RepositoryBrowseService
import bosca.git.service.RepositoryWriteService
import bosca.pipelines.model.Pipeline
import bosca.pipelines.repository.PipelineRecord
import bosca.pipelines.repository.PipelineRepository
import bosca.pipelines.service.PipelineService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkConstructor
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PipelineGitSyncServiceImplTest {

    private val pipelineService = mockk<PipelineService>(relaxed = true)
    private val repository = mockk<PipelineRepository>(relaxed = true)
    private val writeService = mockk<RepositoryWriteService>(relaxed = true)
    private val browseService = mockk<RepositoryBrowseService>(relaxed = true)

    private val sync = PipelineGitSyncServiceImpl(pipelineService, repository, writeService, browseService)

    private val repoId = Uuid.random()
    private val emptyGraph = JsonObject(mapOf("nodes" to JsonArray(emptyList()), "edges" to JsonArray(emptyList())))

    private fun pipeline(
        id: UUID = Uuid.random(),
        gitRepositoryId: UUID? = repoId,
        gitPath: String? = "pipelines/notify.yaml",
    ) = Pipeline(
        id = id,
        name = "Notify",
        description = "",
        acceptedInputType = "Event",
        triggered = true,
        gitRepositoryId = gitRepositoryId,
        gitPath = gitPath,
    )

    // --- pushToGit ---

    @Test
    fun `pushToGit happy path commits the YAML and returns Ok with commit sha`() = runTest {
        val p = pipeline()
        coEvery { pipelineService.get(p.id) } returns p
        coEvery { pipelineService.graphAsJsonElement(p) } returns emptyGraph
        val inputSlot = slot<CommitFileInput>()
        coEvery { writeService.commitFile(capture(inputSlot)) } returns
            CommitFileResult(commitSha = "abc123", branch = "main", path = "pipelines/notify.yaml")

        val result = sync.pushToGit(p.id, "Tester", "test@example.com")

        val ok = assertIs<PipelineSyncResult.Ok>(result)
        assertEquals("abc123", ok.commitSha)
        assertEquals("pipelines/notify.yaml", inputSlot.captured.path)
        assertTrue(inputSlot.captured.content.contains("name: Notify"))
        assertTrue(inputSlot.captured.content.contains("accepted_input_type: Event"))
        coVerify { repository.setSyncError(p.id, null) }
    }

    @Test
    fun `pushToGit fails when the pipeline is not linked to a repository`() = runTest {
        val p = pipeline(gitRepositoryId = null)
        coEvery { pipelineService.get(p.id) } returns p

        val result = sync.pushToGit(p.id, "Tester", "test@example.com")

        val failure = assertIs<PipelineSyncResult.Failure>(result)
        assertTrue(failure.message.contains("not linked"))
        coVerify(exactly = 0) { writeService.commitFile(any()) }
    }

    @Test
    fun `pushToGit records the sync error when the commit fails`() = runTest {
        val p = pipeline()
        coEvery { pipelineService.get(p.id) } returns p
        coEvery { pipelineService.graphAsJsonElement(p) } returns emptyGraph
        coEvery { writeService.commitFile(any()) } throws RuntimeException("disk full")

        val result = sync.pushToGit(p.id, "Tester", "test@example.com")

        assertIs<PipelineSyncResult.Failure>(result)
        coVerify { repository.setSyncError(p.id, match { it.contains("disk full") }) }
    }

    // --- onPushEvent ---

    @Test
    fun `onPushEvent ignores pushes that touch no pipeline files`() = runTest {
        coEvery { browseService.listChangedPaths(repoId, "old", "new") } returns setOf("src/Main.kt", "README.md")

        sync.onPushEvent(repoId, "old", "new")

        coVerify(exactly = 0) { browseService.listTree(any(), any(), any()) }
    }

    @Test
    fun `onPushEvent triggers a pull when a pipeline file changed`() = runTest {
        coEvery { browseService.listChangedPaths(repoId, "old", "new") } returns setOf("pipelines/p.yaml")
        // Empty tree on the new commit — pullFromGit will succeed trivially.
        coEvery { browseService.listTree(repoId, "new", any()) } returns emptyList()
        val connectionManager = mockk<ConnectionManager>(relaxed = true)
        withContext(connectionManager.asCoroutineContext()) {
            sync.onPushEvent(repoId, "old", "new")
        }

        coVerify(atLeast = 1) { browseService.listTree(repoId, "new", any()) }
    }

    // --- pullFromGit ---

    @Test
    fun `pullFromGit reports parse errors and upserts nothing`() = runTest {
        coEvery { browseService.listTree(repoId, "abc", PipelineRepoLayout.PIPELINES_DIR) } returns listOf(
            TreeEntry(name = "broken.yaml", path = "pipelines/broken.yaml", type = TreeEntryType.BLOB, mode = 0, sha = "x")
        )
        coEvery { browseService.readBlob(repoId, "abc", "pipelines/broken.yaml") } returns
            Blob(content = "- not\n- a\n- mapping\n", size = 20, sha = "x")

        val result = sync.pullFromGit(repoId, "abc")

        val failed = assertIs<PipelineSyncResult.ValidationFailed>(result)
        assertEquals("pipelines/broken.yaml", failed.errors.single().path)
        coVerify(exactly = 0) { pipelineService.save(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `pullFromGit ignores a parser-declined file and upserts nothing`() = runTest {
        mockkConstructor(PipelineRepoFileParser::class)
        try {
            val path = "pipelines/ignored.yaml"
            val content = "name: Ignored\naccepted_input_type: Event\n"
            every { anyConstructed<PipelineRepoFileParser>().parse(path, content) } returns
                ParsedPipelineFile.UnknownPath(path)
            coEvery { browseService.listTree(repoId, "abc", PipelineRepoLayout.PIPELINES_DIR) } returns listOf(
                TreeEntry(name = "ignored.yaml", path = path, type = TreeEntryType.BLOB, mode = 0, sha = "x"),
            )
            coEvery { browseService.readBlob(repoId, "abc", path) } returns
                Blob(content = content, size = content.length.toLong(), sha = "x")
            val service = PipelineGitSyncServiceImpl(pipelineService, repository, writeService, browseService)
            val connectionManager = mockk<ConnectionManager>(relaxed = true)

            val result = withContext(connectionManager.asCoroutineContext()) {
                service.pullFromGit(repoId, "abc")
            }

            assertEquals("abc", assertIs<PipelineSyncResult.Ok>(result).commitSha)
            coVerify(exactly = 0) { pipelineService.validateGraph(any()) }
            coVerify(exactly = 0) { pipelineService.save(any(), any(), any(), any(), any(), any(), any()) }
        } finally {
            unmockkConstructor(PipelineRepoFileParser::class)
        }
    }

    @Test
    fun `pullFromGit reports graph validation errors and upserts nothing`() = runTest {
        val content = "name: P\naccepted_input_type: T\nnodes:\n- id: n1\n  type: bogus\n"
        coEvery { browseService.listTree(repoId, "abc", PipelineRepoLayout.PIPELINES_DIR) } returns listOf(
            TreeEntry(name = "p.yaml", path = "pipelines/p.yaml", type = TreeEntryType.BLOB, mode = 0, sha = "x")
        )
        coEvery { browseService.readBlob(repoId, "abc", "pipelines/p.yaml") } returns
            Blob(content = content, size = content.length.toLong(), sha = "x")
        coEvery { pipelineService.validateGraph(any()) } returns "unknown node type 'bogus'"

        val result = sync.pullFromGit(repoId, "abc")

        val failed = assertIs<PipelineSyncResult.ValidationFailed>(result)
        assertTrue(failed.errors.single().message.contains("bogus"))
        coVerify(exactly = 0) { pipelineService.save(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `pullFromGit creates a new pipeline and links it when no row matches the path`() = runTest {
        val content = "name: New Pipeline\naccepted_input_type: Event\ntriggered: true\n"
        coEvery { browseService.listTree(repoId, "abc", PipelineRepoLayout.PIPELINES_DIR) } returns listOf(
            TreeEntry(name = "new.yaml", path = "pipelines/new.yaml", type = TreeEntryType.BLOB, mode = 0, sha = "x")
        )
        coEvery { browseService.readBlob(repoId, "abc", "pipelines/new.yaml") } returns
            Blob(content = content, size = content.length.toLong(), sha = "x")
        coEvery { pipelineService.validateGraph(any()) } returns null
        coEvery { repository.getByGitRepository(repoId, "pipelines/new.yaml") } returns null
        val saved = pipeline(gitPath = "pipelines/new.yaml")
        coEvery {
            pipelineService.save(UUID.NIL, "New Pipeline", "", "Event", true, 0, any())
        } returns saved

        val connectionManager = mockk<ConnectionManager>(relaxed = true)
        val result = withContext(connectionManager.asCoroutineContext()) {
            sync.pullFromGit(repoId, "abc")
        }

        val ok = assertIs<PipelineSyncResult.Ok>(result)
        assertEquals("abc", ok.commitSha)
        coVerify { repository.linkToGit(saved.id, repoId, "pipelines/new.yaml") }
        coVerify { repository.setSyncError(saved.id, null) }
    }

    @Test
    fun `pullFromGit updates an existing pipeline at its current version without relinking`() = runTest {
        val existingId = Uuid.random()
        val content = "name: Updated\naccepted_input_type: Event\n"
        coEvery { browseService.listTree(repoId, "abc", PipelineRepoLayout.PIPELINES_DIR) } returns listOf(
            TreeEntry(name = "p.yaml", path = "pipelines/p.yaml", type = TreeEntryType.BLOB, mode = 0, sha = "x")
        )
        coEvery { browseService.readBlob(repoId, "abc", "pipelines/p.yaml") } returns
            Blob(content = content, size = content.length.toLong(), sha = "x")
        coEvery { pipelineService.validateGraph(any()) } returns null
        coEvery { repository.getByGitRepository(repoId, "pipelines/p.yaml") } returns PipelineRecord(
            id = existingId,
            name = "Old Name",
            acceptedInputType = "Event",
            graph = emptyGraph,
            version = 4,
        )
        coEvery {
            pipelineService.save(existingId, "Updated", "", "Event", false, 4, any())
        } returns pipeline(id = existingId)

        val connectionManager = mockk<ConnectionManager>(relaxed = true)
        val result = withContext(connectionManager.asCoroutineContext()) {
            sync.pullFromGit(repoId, "abc")
        }

        assertIs<PipelineSyncResult.Ok>(result)
        coVerify { pipelineService.save(existingId, "Updated", "", "Event", false, 4, any()) }
        coVerify(exactly = 0) { repository.linkToGit(any(), any(), any()) }
    }

    // --- backfill ---

    @Test
    fun `backfill links each entry and pushes it`() = runTest {
        val p = pipeline(gitPath = "pipelines/notify.yaml")
        coEvery { pipelineService.get(p.id) } returns p
        coEvery { pipelineService.graphAsJsonElement(p) } returns emptyGraph
        coEvery { writeService.commitFile(any()) } returns
            CommitFileResult(commitSha = "sha9", branch = "main", path = "pipelines/notify.yaml")

        val result = sync.backfill(
            repoId,
            listOf(PipelineBackfillEntry(p.id, "pipelines/notify.yaml")),
            "Tester",
            "test@example.com",
        )

        val ok = assertIs<PipelineSyncResult.Ok>(result)
        assertEquals("sha9", ok.commitSha)
        coVerify { repository.linkToGit(p.id, repoId, "pipelines/notify.yaml") }
    }

    @Test
    fun `backfill aggregates per-entry failures without stopping`() = runTest {
        val good = pipeline()
        val missingId = Uuid.random()
        coEvery { pipelineService.get(good.id) } returns good
        coEvery { pipelineService.get(missingId) } returns null
        coEvery { pipelineService.graphAsJsonElement(good) } returns emptyGraph
        coEvery { writeService.commitFile(any()) } returns
            CommitFileResult(commitSha = "sha1", branch = "main", path = "pipelines/notify.yaml")

        val result = sync.backfill(
            repoId,
            listOf(
                PipelineBackfillEntry(missingId, "pipelines/missing.yaml"),
                PipelineBackfillEntry(good.id, "pipelines/notify.yaml"),
            ),
            "Tester",
            "test@example.com",
        )

        val failure = assertIs<PipelineSyncResult.Failure>(result)
        assertTrue(failure.message.contains("pipelines/missing.yaml"))
        // The good entry was still pushed.
        coVerify { writeService.commitFile(any()) }
    }

    @Test
    fun `backfill with no entries returns Ok with a null commit sha`() = runTest {
        val result = sync.backfill(repoId, emptyList(), "Tester", "test@example.com")
        val ok = assertIs<PipelineSyncResult.Ok>(result)
        assertEquals(null, ok.commitSha)
        coVerify(exactly = 0) { repository.linkToGit(any(), any(), any()) }
        coVerify(exactly = 0) { writeService.commitFile(any()) }
    }

    // --- pushToGit: remaining failure arms ---

    @Test
    fun `pushToGit fails when the pipeline does not exist`() = runTest {
        val id = Uuid.random()
        coEvery { pipelineService.get(id) } returns null

        val result = sync.pushToGit(id, "Tester", "test@example.com")

        val failure = assertIs<PipelineSyncResult.Failure>(result)
        assertTrue(failure.message.contains("Pipeline not found"))
        coVerify(exactly = 0) { writeService.commitFile(any()) }
    }

    @Test
    fun `pushToGit fails when the pipeline has no git path`() = runTest {
        val p = pipeline(gitPath = null)
        coEvery { pipelineService.get(p.id) } returns p

        val result = sync.pushToGit(p.id, "Tester", "test@example.com")

        val failure = assertIs<PipelineSyncResult.Failure>(result)
        assertTrue(failure.message.contains("no git_path"))
        coVerify(exactly = 0) { writeService.commitFile(any()) }
    }

    @Test
    fun `pushToGit falls back to the exception class name when the message is null`() = runTest {
        val p = pipeline()
        coEvery { pipelineService.get(p.id) } returns p
        coEvery { pipelineService.graphAsJsonElement(p) } returns emptyGraph
        // An exception with a null message exercises the `?: e::class.simpleName` arm.
        coEvery { writeService.commitFile(any()) } throws IllegalStateException()

        val result = sync.pushToGit(p.id, "Tester", "test@example.com")

        val failure = assertIs<PipelineSyncResult.Failure>(result)
        assertTrue(failure.message.contains("IllegalStateException"))
        coVerify { repository.setSyncError(p.id, match { it.contains("IllegalStateException") }) }
    }

    @Test
    fun `pushToGit still returns Failure when persisting the sync error itself fails`() = runTest {
        val p = pipeline()
        coEvery { pipelineService.get(p.id) } returns p
        coEvery { pipelineService.graphAsJsonElement(p) } returns emptyGraph
        coEvery { writeService.commitFile(any()) } throws RuntimeException("disk full")
        // recordSyncError's own setSyncError write throws -> the inner catch logs and we still
        // return Failure(message) with the original cause.
        coEvery { repository.setSyncError(p.id, match { it != null }) } throws RuntimeException("db down")

        val result = sync.pushToGit(p.id, "Tester", "test@example.com")

        val failure = assertIs<PipelineSyncResult.Failure>(result)
        assertTrue(failure.message.contains("disk full"))
    }

    // --- pullFromGit: file-reading filters (readAllFiles) ---

    @Test
    fun `pullFromGit skips non-blob entries, unknown paths, missing blobs and null content`() = runTest {
        val good = "name: New\naccepted_input_type: Event\n"
        coEvery { browseService.listTree(repoId, "abc", PipelineRepoLayout.PIPELINES_DIR) } returns listOf(
            // a tree (directory) entry -> filtered by the type check
            TreeEntry(name = "sub", path = "pipelines/sub", type = TreeEntryType.TREE, mode = 0, sha = "t"),
            // a blob, but not a pipeline file -> filtered by isPipelineFile
            TreeEntry(name = "notes.md", path = "pipelines/notes.md", type = TreeEntryType.BLOB, mode = 0, sha = "m"),
            // a blob whose readBlob returns null -> skipped via `?: continue`
            TreeEntry(name = "gone.yaml", path = "pipelines/gone.yaml", type = TreeEntryType.BLOB, mode = 0, sha = "g"),
            // a blob whose content is null -> skipped via `?: continue`
            TreeEntry(name = "nullc.yaml", path = "pipelines/nullc.yaml", type = TreeEntryType.BLOB, mode = 0, sha = "n"),
            // a real, parseable pipeline file -> upserted
            TreeEntry(name = "new.yaml", path = "pipelines/new.yaml", type = TreeEntryType.BLOB, mode = 0, sha = "x"),
        )
        coEvery { browseService.readBlob(repoId, "abc", "pipelines/gone.yaml") } returns null
        coEvery { browseService.readBlob(repoId, "abc", "pipelines/nullc.yaml") } returns
            Blob(content = null, size = 0, sha = "n")
        coEvery { browseService.readBlob(repoId, "abc", "pipelines/new.yaml") } returns
            Blob(content = good, size = good.length.toLong(), sha = "x")
        coEvery { pipelineService.validateGraph(any()) } returns null
        coEvery { repository.getByGitRepository(repoId, "pipelines/new.yaml") } returns null
        val saved = pipeline(gitPath = "pipelines/new.yaml")
        coEvery { pipelineService.save(UUID.NIL, "New", "", "Event", false, 0, any()) } returns saved

        val connectionManager = mockk<ConnectionManager>(relaxed = true)
        val result = withContext(connectionManager.asCoroutineContext()) {
            sync.pullFromGit(repoId, "abc")
        }

        assertIs<PipelineSyncResult.Ok>(result)
        // Only the one real pipeline file produced a save/link; the markdown blob was never read.
        coVerify(exactly = 1) { pipelineService.save(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { browseService.readBlob(repoId, "abc", "pipelines/notes.md") }
        coVerify { repository.linkToGit(saved.id, repoId, "pipelines/new.yaml") }
    }

    @Test
    fun `pullFromGit on an empty tree upserts nothing and returns Ok`() = runTest {
        coEvery { browseService.listTree(repoId, "abc", PipelineRepoLayout.PIPELINES_DIR) } returns emptyList()
        val connectionManager = mockk<ConnectionManager>(relaxed = true)
        val result = withContext(connectionManager.asCoroutineContext()) {
            sync.pullFromGit(repoId, "abc")
        }
        val ok = assertIs<PipelineSyncResult.Ok>(result)
        assertEquals("abc", ok.commitSha)
        coVerify(exactly = 0) { pipelineService.save(any(), any(), any(), any(), any(), any(), any()) }
    }

    // --- onPushEvent: result-handling arms ---

    @Test
    fun `onPushEvent logs and swallows when the pull fails validation`() = runTest {
        coEvery { browseService.listChangedPaths(repoId, "old", "new") } returns setOf("pipelines/p.yaml")
        val content = "- not\n- a\n- mapping\n"
        coEvery { browseService.listTree(repoId, "new", PipelineRepoLayout.PIPELINES_DIR) } returns listOf(
            TreeEntry(name = "p.yaml", path = "pipelines/p.yaml", type = TreeEntryType.BLOB, mode = 0, sha = "x")
        )
        coEvery { browseService.readBlob(repoId, "new", "pipelines/p.yaml") } returns
            Blob(content = content, size = content.length.toLong(), sha = "x")

        // Does not throw; the ValidationFailed arm just logs.
        sync.onPushEvent(repoId, "old", "new")

        coVerify(exactly = 0) { pipelineService.save(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `onPushEvent takes the Ok arm silently when the pull succeeds`() = runTest {
        // changed touches a pipeline file -> a pull runs; an empty tree makes pullFromGit return Ok,
        // which is the `Ok -> Unit` arm of the result when(): no log, no throw.
        coEvery { browseService.listChangedPaths(repoId, "old", "new") } returns setOf("pipelines/p.yaml")
        coEvery { browseService.listTree(repoId, "new", PipelineRepoLayout.PIPELINES_DIR) } returns emptyList()

        val connectionManager = mockk<ConnectionManager>(relaxed = true)
        withContext(connectionManager.asCoroutineContext()) {
            sync.onPushEvent(repoId, "old", "new")
        }

        coVerify(exactly = 0) { pipelineService.save(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `onPushEvent logs and swallows when the pull itself fails`() = runTest {
        // The `Failure ->` arm of onPushEvent's when(): the real pull never returns Failure, so we
        // spy the service and stub pullFromGit to return Failure. listChangedPaths still gates the call.
        val spy = spyk(sync)
        coEvery { browseService.listChangedPaths(repoId, "old", "new") } returns setOf("pipelines/p.yaml")
        coEvery { spy.pullFromGit(repoId, "new") } returns PipelineSyncResult.Failure("boom")

        // Does not throw; the Failure arm just logs.
        spy.onPushEvent(repoId, "old", "new")

        coVerify(exactly = 1) { spy.pullFromGit(repoId, "new") }
    }

    // --- backfill: the ValidationFailed arm of its per-entry when() ---

    @Test
    fun `backfill aggregates a ValidationFailed push into the per-entry errors`() = runTest {
        // pushToGit never returns ValidationFailed, so we spy and stub it to exercise that when() arm.
        val spy = spyk(sync)
        val id = Uuid.random()
        coEvery { spy.pushToGit(id, "Tester", "test@example.com") } returns
            PipelineSyncResult.ValidationFailed(listOf(PipelineRepoValidationError("pipelines/x.yaml", "bad node")))

        val result = spy.backfill(
            repoId,
            listOf(PipelineBackfillEntry(id, "pipelines/x.yaml")),
            "Tester",
            "test@example.com",
        )

        val failure = assertIs<PipelineSyncResult.Failure>(result)
        assertTrue(failure.message.contains("bad node"))
        assertTrue(failure.message.contains("pipelines/x.yaml"))
        coVerify { repository.linkToGit(id, repoId, "pipelines/x.yaml") }
    }

    @Test
    fun `backfill collects the commit sha from a successful push (Ok arm)`() = runTest {
        // Re-drives the `Ok -> result.commitSha?.let { lastCommitSha = it }` arm with a spy so the
        // when() over the push result is explicit and the non-null commitSha let-arm fires.
        val spy = spyk(sync)
        val id = Uuid.random()
        coEvery { spy.pushToGit(id, "Tester", "test@example.com") } returns PipelineSyncResult.Ok(commitSha = "deadbeef")

        val result = spy.backfill(
            repoId,
            listOf(PipelineBackfillEntry(id, "pipelines/x.yaml")),
            "Tester",
            "test@example.com",
        )

        val ok = assertIs<PipelineSyncResult.Ok>(result)
        assertEquals("deadbeef", ok.commitSha)
    }

    @Test
    fun `backfill keeps a null last commit sha when a successful push reports no commit sha`() = runTest {
        // L118 `result.commitSha?.let { lastCommitSha = it }` null arm: an Ok push whose commitSha is
        // null does NOT update lastCommitSha, so the overall backfill returns Ok with a null sha.
        val spy = spyk(sync)
        val id = Uuid.random()
        coEvery { spy.pushToGit(id, "Tester", "test@example.com") } returns PipelineSyncResult.Ok(commitSha = null)

        val result = spy.backfill(
            repoId,
            listOf(PipelineBackfillEntry(id, "pipelines/x.yaml")),
            "Tester",
            "test@example.com",
        )

        val ok = assertIs<PipelineSyncResult.Ok>(result)
        assertEquals(null, ok.commitSha)
    }
}
