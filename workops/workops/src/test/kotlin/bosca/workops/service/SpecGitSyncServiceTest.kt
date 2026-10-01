package bosca.workops.service

import bosca.content.metadata.model.Document
import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.CollaborationSyncMode
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.documents.Content
import bosca.documents.MarkdownConverter
import bosca.git.service.CommitFileInput
import bosca.git.service.CommitFileResult
import bosca.git.service.RepositoryWriteService
import bosca.serialization.UUID
import bosca.workops.model.audit.FieldChange
import bosca.workops.model.spec.Spec
import bosca.workops.repository.SpecHistoryRepository
import bosca.workops.repository.SpecRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpecGitSyncServiceTest {

    private val specRepository = mockk<SpecRepository>()
    private val historyRepository = mockk<SpecHistoryRepository>()
    private val repositoryWriteService = mockk<RepositoryWriteService>()
    private val metadataService = mockk<MetadataService>()
    private val documentService = mockk<DocumentService>()
    private val json = Json { ignoreUnknownKeys = true }
    private val service = SpecGitSyncServiceImpl(
        specRepository,
        historyRepository,
        repositoryWriteService,
        metadataService,
        documentService,
        json,
    )

    @Test
    fun `push exits when the spec has no complete git destination`() = runTest {
        val withoutRepository = spec(repositoryId = null, path = "spec.md")
        val withoutPath = spec(repositoryId = UUID.random(), path = null)

        assertNull(service.pushToGit(withoutRepository, "raw", UUID.random(), "Author", "author@example.com"))
        assertNull(service.pushToGit(withoutPath, "raw", UUID.random(), "Author", "author@example.com"))

        coVerify(exactly = 0) { repositoryWriteService.commitFile(any()) }
        coVerify(exactly = 0) { documentService.getDocument(any(), any()) }
    }

    @Test
    fun `push converts the stored rich document to markdown and writes history`() = runTest {
        val spec = spec(path = "specs/git-spec-6.md")
        val principalId = UUID.random()
        val richContent = MarkdownConverter.fromMarkdown("# Durable spec\n")
        val commit = slot<CommitFileInput>()
        val changes = slot<JsonElement>()
        coEvery { documentService.getDocument(spec.metadataId, 1) } returns Document(
            metadataId = spec.metadataId,
            version = 1,
            title = spec.key,
            content = richContent,
        )
        coEvery { repositoryWriteService.commitFile(capture(commit)) } returns CommitFileResult(
            commitSha = "abc123",
            branch = "main",
            path = spec.gitPath ?: error("missing spec git path"),
        )
        coEvery { historyRepository.add(spec.id, any(), principalId, null, capture(changes)) } returns
                mockk(relaxed = true)

        val sha = service.pushToGit(spec, "stale raw", principalId, "Bosca", "bosca@example.com")

        assertEquals("abc123", sha)
        assertEquals(spec.gitRepositoryId, commit.captured.repositoryId)
        assertEquals("main", commit.captured.branch)
        assertEquals("# Durable spec\n\n", commit.captured.content)
        assertEquals("Update spec ${spec.key}", commit.captured.message)
        assertEquals("Bosca", commit.captured.authorName)
        assertEquals("bosca@example.com", commit.captured.authorEmail)
        assertEquals(
            listOf(FieldChange(fieldKey = "git_push", toValue = kotlinx.serialization.json.JsonPrimitive("abc123"))),
            json.decodeFromJsonElement(ListSerializer(FieldChange.serializer()), changes.captured),
        )
    }

    @Test
    fun `push uses supplied content for missing rich content and non markdown paths`() = runTest {
        val missingDocument = spec(path = "missing.md")
        val emptyDocument = spec(path = "empty.md")
        val jsonSpec = spec(path = "spec.json")
        val commits = mutableListOf<CommitFileInput>()
        coEvery { documentService.getDocument(missingDocument.metadataId, 1) } returns null
        coEvery { documentService.getDocument(emptyDocument.metadataId, 1) } returns Document(
            metadataId = emptyDocument.metadataId,
            version = 1,
            title = emptyDocument.key,
            content = null,
        )
        coEvery { repositoryWriteService.commitFile(capture(commits)) } answers {
            val input = firstArg<CommitFileInput>()
            CommitFileResult("sha-${commits.size}", input.branch, input.path)
        }
        coEvery { historyRepository.add(any(), any(), any(), null, any()) } returns mockk(relaxed = true)

        service.pushToGit(missingDocument, "missing raw", UUID.random(), "A", "a@example.com")
        service.pushToGit(emptyDocument, "empty raw", UUID.random(), "A", "a@example.com")
        service.pushToGit(jsonSpec, "{\"type\":\"doc\"}", UUID.random(), "A", "a@example.com")

        assertEquals(listOf("missing raw", "empty raw", "{\"type\":\"doc\"}"), commits.map { it.content })
        coVerify(exactly = 0) { documentService.getDocument(jsonSpec.metadataId, any()) }
    }

    @Test
    fun `pull exits at every unresolved dependency and rejects malformed rich JSON`() = runTest {
        val missingSpecId = UUID.random()
        val noRepository = spec(repositoryId = null, path = "spec.md")
        val noPath = spec(repositoryId = UUID.random(), path = null)
        val noFile = spec(path = "missing.md")
        val noMetadata = spec(path = "metadata.md")
        val invalidJson = spec(path = "invalid.json")
        val metadata = mockk<Metadata>(relaxed = true)
        coEvery { specRepository.getActiveById(missingSpecId) } returns null
        coEvery { specRepository.getActiveById(noRepository.id) } returns noRepository
        coEvery { specRepository.getActiveById(noPath.id) } returns noPath
        coEvery { specRepository.getActiveById(noFile.id) } returns noFile
        coEvery { specRepository.getActiveById(noMetadata.id) } returns noMetadata
        coEvery { specRepository.getActiveById(invalidJson.id) } returns invalidJson
        coEvery {
            repositoryWriteService.readFile(
                noFile.gitRepositoryId ?: error("missing repository"),
                "sha",
                noFile.gitPath ?: error("missing path"),
            )
        } returns null
        coEvery {
            repositoryWriteService.readFile(
                noMetadata.gitRepositoryId ?: error("missing repository"),
                "sha",
                noMetadata.gitPath ?: error("missing path"),
            )
        } returns "# Content"
        coEvery { metadataService.getById(noMetadata.metadataId) } returns null
        coEvery {
            repositoryWriteService.readFile(
                invalidJson.gitRepositoryId ?: error("missing repository"),
                "sha",
                invalidJson.gitPath ?: error("missing path"),
            )
        } returns "not json"
        coEvery { metadataService.getById(invalidJson.metadataId) } returns metadata

        val results = listOf(
            service.pullFromGit(missingSpecId, "sha", UUID.random(), null),
            service.pullFromGit(noRepository.id, "sha", UUID.random(), null),
            service.pullFromGit(noPath.id, "sha", UUID.random(), null),
            service.pullFromGit(noFile.id, "sha", UUID.random(), null),
            service.pullFromGit(noMetadata.id, "sha", UUID.random(), null),
            service.pullFromGit(invalidJson.id, "sha", UUID.random(), null),
        )

        assertEquals(List(results.size) { null }, results)
        coVerify(exactly = 0) { documentService.setDocument(any(), any(), any()) }
        coVerify(exactly = 0) { historyRepository.add(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `pull converts markdown and JSON into rich documents and records history`() = runTest {
        val markdownSpec = spec(path = "spec.md")
        val jsonSpec = spec(path = "spec.json")
        val principalId = UUID.random()
        val markdownMetadata = mockk<Metadata>(relaxed = true)
        val jsonMetadata = mockk<Metadata>(relaxed = true)
        val inputs = mutableListOf<DocumentInput>()
        coEvery { specRepository.getActiveById(markdownSpec.id) } returns markdownSpec
        coEvery { specRepository.getActiveById(jsonSpec.id) } returns jsonSpec
        coEvery {
            repositoryWriteService.readFile(any(), "markdown-sha", "spec.md")
        } returns "# From Git\n"
        coEvery {
            repositoryWriteService.readFile(any(), "json-sha", "spec.json")
        } returns json.encodeToString(Content.serializer(), Content())
        coEvery { metadataService.getById(markdownSpec.metadataId) } returns markdownMetadata
        coEvery { metadataService.getById(jsonSpec.metadataId) } returns jsonMetadata
        coEvery { documentService.setDocument(any(), capture(inputs), CollaborationSyncMode.NONE) } just Runs
        coEvery { historyRepository.add(any(), any(), principalId, null, any()) } returns mockk(relaxed = true)

        assertEquals(markdownSpec, service.pullFromGit(markdownSpec.id, "markdown-sha", principalId, UUID.random()))
        assertEquals(jsonSpec, service.pullFromGit(jsonSpec.id, "json-sha", principalId, null))

        assertEquals(listOf(markdownSpec.key, jsonSpec.key), inputs.map { it.title })
        assertEquals("# From Git\n\n", MarkdownConverter.toMarkdown(inputs[0].content ?: error("missing markdown")))
        assertEquals(Content(), inputs[1].content)
        coVerify(exactly = 2) { historyRepository.add(any(), any(), principalId, null, any()) }
    }

    @Test
    fun `push event pulls only specs whose git paths changed`() = runTest {
        val repositoryId = UUID.random()
        val changed = spec(repositoryId = repositoryId, path = "changed.md")
        val unchanged = spec(repositoryId = repositoryId, path = "unchanged.md")
        val unlinked = spec(repositoryId = repositoryId, path = null)
        val metadata = mockk<Metadata>(relaxed = true)
        coEvery { specRepository.listByGitRepository(repositoryId) } returns listOf(unlinked, unchanged, changed)
        coEvery { specRepository.getActiveById(changed.id) } returns changed
        coEvery { repositoryWriteService.readFile(repositoryId, "after", "changed.md") } returns "Changed"
        coEvery { metadataService.getById(changed.metadataId) } returns metadata
        coEvery { documentService.setDocument(metadata, any(), CollaborationSyncMode.NONE) } just Runs
        coEvery { historyRepository.add(changed.id, any(), UUID.NIL, null, any()) } returns mockk(relaxed = true)

        service.onPushEvent(repositoryId, "refs/heads/main", "before", "after", setOf("changed.md"))

        coVerify(exactly = 1) { repositoryWriteService.readFile(repositoryId, "after", "changed.md") }
        coVerify(exactly = 0) { repositoryWriteService.readFile(repositoryId, any(), "unchanged.md") }
        coVerify(exactly = 1) { documentService.setDocument(metadata, any(), CollaborationSyncMode.NONE) }
    }

    private fun spec(
        repositoryId: UUID? = UUID.random(),
        path: String? = "spec.md",
    ): Spec {
        val principalId = UUID.random()
        return Spec(
            id = UUID.random(),
            key = "GIT-SPEC-6",
            metadataId = UUID.random(),
            projectId = UUID.random(),
            statusId = UUID.random(),
            workflowId = UUID.random(),
            ownerProfileId = UUID.random(),
            gitRepositoryId = repositoryId,
            gitPath = path,
            createdByPrincipalId = principalId,
            modifiedByPrincipalId = principalId,
        )
    }
}
