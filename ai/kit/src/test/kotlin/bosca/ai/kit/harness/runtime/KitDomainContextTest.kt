package bosca.ai.kit.harness.runtime

import bosca.ai.agents.service.AgentService
import bosca.ai.chat.model.ChatSession
import bosca.ai.chat.service.ChatHistoryService
import bosca.ai.kit.harness.*
import bosca.ai.prompts.service.PromptService
import bosca.ai.memory.MemoryService
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.*
import bosca.content.metadata.service.*
import bosca.content.security.*
import bosca.di.asProvider
import bosca.documents.Content
import bosca.documents.TextNode
import bosca.git.model.Blob
import bosca.git.model.Repository
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.RepositoryBrowseService
import bosca.git.service.RepositoryService
import bosca.security.model.*
import bosca.security.service.*
import bosca.serialization.*
import bosca.workops.model.permission.*
import bosca.workops.model.requirement.*
import bosca.workops.model.spec.Spec
import bosca.workops.model.task.Task
import bosca.workops.service.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.*

class KitDomainContextTest {
    private val json = Json { serializersModule = SerializersModule { contextual(UUIDSerializer()); contextual(OffsetDateTimeSerializer()) } }
    private val principal = Principal(id = UUID.random(), anonymous = false)
    private val group = Group(UUID.random(), "readers", "", GroupType.PRINCIPAL)
    private var membership = listOf(group)
    private val security = mockk<SecurityService> {
        coEvery { getPrincipalById(principal.id) } returns principal
        coEvery { getPrincipalGroups(principal.id) } answers { membership }
    }
    private val groups = GroupEvaluator(security)
    private val authority = KitRuntimeAuthority(listOf("content:view", "collections:view", "git:read"), listOf(group.id))
    private val config = KitHarnessConfiguration("kit", "unused", "unused", scopes = authority.scopes,
        image = "runtime@sha256:" + "a".repeat(64), imageId = "sha256:" + "b".repeat(64), versions = RuntimeVersions("test", "test", "test", "test"))
    private val resolution = KitHarnessResolution(UUID.random(), "policy", "SYSTEM", config, authority)
    private val session = KitRuntimeSession(UUID.random(), principal.id, JsonNull, JsonNull,
        resolvedSettings = json.encodeToJsonElement(KitHarnessResolution.serializer(), resolution), idleAt = OffsetDateTime.now())
    private var chat = ChatSession(session.sessionId, principal.id, agentKey = "kit")
    private val agents = mockk<AgentService>()
    private val prompts = mockk<PromptService>()
    private val history = mockk<ChatHistoryService>()
    private val metadata = mockk<MetadataService>()
    private val documents = mockk<DocumentService>()
    private val collections = mockk<CollectionService>()
    private val memories = mockk<MemoryService>(relaxed = true)
    private val tasks = mockk<TaskService>()
    private val specs = mockk<SpecService>()
    private val requirements = mockk<RequirementService>()
    private val repositories = mockk<RepositoryService>()
    private val browse = mockk<RepositoryBrowseService>()
    private val content = EmptyMetadata.copy(id = UUID.random(), name = "Writing plan")
    private var document = Document(content.id, 1, title = "Plan", content = Content(bosca.documents.Document(content = listOf(TextNode(text = "Permitted rich body")))))
    private val task = Task(id = UUID.random(), key = "TEST-1", projectId = UUID.random(), taskTypeId = UUID.random(), statusId = UUID.random(), priorityId = UUID.random(),
        summary = "Write the guide", descriptionMarkdown = "Independent task description", metadataId = content.id,
        reporterProfileId = UUID.random(), createdByPrincipalId = principal.id, modifiedByPrincipalId = principal.id)
    private val spec = Spec(id = UUID.random(), key = "TEST-SPEC-1", metadataId = content.id, statusId = UUID.random(), workflowId = UUID.random(), ownerProfileId = UUID.random(),
        createdByPrincipalId = principal.id, modifiedByPrincipalId = principal.id)
    private val requirement = Requirement(id = UUID.random(), key = "TEST-REQ-1", metadataId = content.id, parentType = RequirementParent.SPEC, parentId = spec.id,
        statusId = UUID.random(), workflowId = UUID.random(), priorityId = UUID.random(), createdByPrincipalId = principal.id, modifiedByPrincipalId = principal.id)
    private val repository = Repository(id = UUID.random(), slug = "docs", name = "Documentation", ownerId = UUID.random())
    private var allowed = true
    private var contentAllowed = true
    private var commit = "a".repeat(40)
    private var blob = Blob("Git file content", 16, "b".repeat(40))
    private val taskSelection = KitContextSelection(KitContextSourceKind.TASK, task.id)
    private val specSelection = KitContextSelection(KitContextSourceKind.SPEC, spec.id)
    private val requirementSelection = KitContextSelection(KitContextSourceKind.REQUIREMENT, requirement.id)
    private val fileSelection = KitContextSelection(KitContextSourceKind.GIT_FILE, repository.id, "main", "docs/plan.md")
    private val resolver = KitContextResolver(agents, prompts, history, metadata, documents, collections,
        MetadataPermissionEvaluator(metadata, security, groups), CollectionPermissionEvaluator(collections, security, groups), security, json, memories,
        tasks.asProvider(), specs.asProvider(), requirements.asProvider(), TaskPermissionEvaluator(tasks, security, groups).asProvider(),
        SpecPermissionEvaluator(specs, security, groups).asProvider(), RequirementPermissionEvaluator(requirements, security, groups).asProvider(),
        repositories.asProvider(), browse.asProvider(), (RepositoryPermissionEvaluator(repositories, security, groups) as bosca.git.service.RepositoryAccessEvaluator).asProvider())

    init {
        coEvery { history.getSession(session.sessionId) } answers { chat }
        coEvery { agents.getByKey("kit") } returns null
        coEvery { tasks.getById(task.id) } returns task
        coEvery { specs.getById(spec.id) } returns spec
        coEvery { requirements.getById(requirement.id) } returns requirement
        coEvery { repositories.findById(repository.id) } returns repository
        coEvery { tasks.getPermissions(any()) } answers { if (allowed) listOf(TaskPermission(task.id, group.id, PermissionAction.VIEW)) else emptyList() }
        coEvery { specs.getPermissions(any()) } answers { if (allowed) listOf(SpecPermission(spec.id, group.id, PermissionAction.VIEW)) else emptyList() }
        coEvery { requirements.getPermissions(any()) } answers { if (allowed) listOf(RequirementPermission(requirement.id, group.id, PermissionAction.VIEW)) else emptyList() }
        coEvery { repositories.getPermissions(any()) } answers { if (allowed) listOf(mockk<EntityPermission> {
            every { groupId } returns group.id; every { action } returns PermissionAction.VIEW
        }) else emptyList() }
        coEvery { tasks.isParentAllowed(any(), any(), any()) } returns false
        coEvery { specs.isParentAllowed(any(), any(), any()) } returns false
        coEvery { requirements.isParentAllowed(any(), any(), any()) } returns false
        coEvery { repositories.isParentAllowed(any(), any(), any()) } returns false
        coEvery { metadata.getById(any()) } answers { if (firstArg<UUID>() == content.id) content.copy(public = !contentAllowed, workflowStateId = if (contentAllowed) content.workflowStateId else "published") else null }
        coEvery { metadata.getPermissions(any()) } answers { if (contentAllowed) listOf(MetadataPermission(content.id, group.id, PermissionAction.VIEW)) else emptyList() }
        coEvery { metadata.isParentAllowed(any(), any(), any()) } returns false
        coEvery { documents.getDocument(content.id, 1) } answers { document }
        coEvery { browse.resolveRef(repository.id, any()) } answers { commit }
        coEvery { browse.readBlob(repository.id, any(), any()) } answers { blob }
    }

    private fun select(vararg sources: KitContextSelection) {
        chat = chat.copy(state = buildJsonObject { put("harnessSources", json.encodeToJsonElement(ListSerializer(KitContextSelection.serializer()), sources.toList())) })
    }
    private fun warmed(context: KitResolvedContext) = session.copy(contextManifest = json.encodeToJsonElement(KitContextManifest.serializer(), context.manifest))

    @Test fun `WorkOps selections include independently authorized task text and rich documents`() = runTest {
        select(taskSelection, specSelection, requirementSelection)
        val result = resolver.resolve(session)
        assertEquals(listOf("task", "spec", "requirement"), result.manifest.sources.map { it.kind })
        assertEquals(listOf("Write the guide", "Writing plan", "Writing plan"), result.references.map { it.title })
        assertTrue(result.references.all { it.selected })
        assertContains(result.resources.first().second, "Independent task description")
        assertTrue(result.resources.all { "Permitted rich body" in it.second })
        assertTrue(resolver.canRestoreWorkspace(warmed(result)))
        document = Document(content.id, 1, title = "Plan", content = Content(bosca.documents.Document(content = listOf(TextNode(text = "Changed at the same metadata version")))))
        assertNotEquals(result.manifest.revision, resolver.resolve(session).manifest.revision)
        assertTrue(resolver.canRestoreWorkspace(warmed(result)), "An authorized edit can retain the workspace")
        allowed = false
        clearMocks(documents, answers = false)
        val denied = resolver.resolve(session)
        assertTrue(denied.references.isEmpty()); assertTrue(denied.resources.isEmpty())
        coVerify(exactly = 0) { documents.getDocument(any(), any()) }
        assertFalse(resolver.canRestoreWorkspace(warmed(result)))
    }

    @Test fun `WorkOps entity visibility cannot disclose a linked private document body`() = runTest {
        select(taskSelection, specSelection)
        val first = resolver.resolve(session)
        contentAllowed = false
        clearMocks(documents, answers = false)
        val result = resolver.resolve(session)
        assertContains(result.resources.first().second, "Independent task description")
        assertFalse(result.resources.any { "Permitted rich body" in it.second })
        coVerify(exactly = 0) { documents.getDocument(any(), any()) }
        assertNotEquals(first.manifest.revision, result.manifest.revision)
        assertFalse(resolver.canRestoreWorkspace(warmed(first)))
    }

    @Test fun `Git selection reads the resolved commit and retains distinct paths and refs`() = runTest {
        select(fileSelection, fileSelection.copy(path = "docs/other.md"), fileSelection.copy(ref = "release"), fileSelection)
        val result = resolver.resolve(session)
        assertEquals(3, result.resources.size)
        assertEquals(3, result.manifest.sources.size)
        assertTrue(result.manifest.sources.all { it.version == commit })
        coVerify { browse.readBlob(repository.id, commit, "docs/plan.md") }
        coVerify(exactly = 0) { browse.readBlob(repository.id, "main", any()) }
        assertEquals(listOf("main", "main", "release"), result.references.map { it.ref })
        assertContains(result.resources.first().second, "Requested ref: main")
        assertContains(result.resources.last().second, "Requested ref: release")
        commit = "c".repeat(40)
        assertNotEquals(result.manifest.revision, resolver.resolve(session).manifest.revision)
        assertTrue(resolver.canRestoreWorkspace(warmed(result)))
        allowed = false
        clearMocks(browse, answers = false)
        assertTrue(resolver.resolve(session).references.isEmpty())
        coVerify(exactly = 0) { browse.readBlob(any(), any(), any()) }
        assertFalse(resolver.canRestoreWorkspace(warmed(result)))
    }

    @Test fun `caller scope and current membership restrict all domain reads`() = runTest {
        select(taskSelection, specSelection, requirementSelection, fileSelection)
        val first = resolver.resolve(session)
        val caller = object : AuthenticationContext(null, null) {
            override fun principal() = ScopedAuthenticatedPrincipal(principal, listOf(group), emptyList(), setOf(group.id), 1)
        }
        assertTrue(resolver.resolve(session, caller = caller).references.isEmpty())
        membership = emptyList()
        assertTrue(resolver.resolve(session).references.isEmpty())
        assertFalse(resolver.canRestoreWorkspace(warmed(first)))
    }

    @Test fun `binary missing and failing Git reads preserve their real outcomes`() = runTest {
        select(fileSelection)
        blob = Blob(null, 2_000_000, "b".repeat(40), true)
        val result = resolver.resolve(session)
        assertContains(result.resources.single().second, "Binary or oversized file")
        assertFalse(result.manifest.sources.single().contentAllowed)
        coEvery { browse.resolveRef(repository.id, any()) } returns null
        assertTrue(resolver.resolve(session).references.isEmpty())
        val failure = java.io.IOException("Git read failed")
        coEvery { browse.resolveRef(repository.id, any()) } throws failure
        assertSame(failure, assertFailsWith<java.io.IOException> { resolver.resolve(session) })
    }

    @Test fun `source selection validates ref paths and the shared context budget`() = runTest {
        for (invalid in listOf(fileSelection.copy(path = "../private"), fileSelection.copy(path = "/absolute"), fileSelection.copy(ref = ""), taskSelection.copy(path = "a"))) {
            assertFailsWith<IllegalArgumentException> { invalid.validate() }
        }
        select(fileSelection)
        blob = blob.copy(content = "x".repeat(50_000))
        val restricted = session.copy(resolvedSettings = json.encodeToJsonElement(KitHarnessResolution.serializer(), resolution.copy(configuration = config.copy(contextCharacters = 4096))))
        val result = resolver.resolve(restricted)
        assertTrue(result.manifest.usedCharacters <= 4096)
        assertContains(result.resources.single().second, "[Excerpt truncated]")
        blob = blob.copy(content = "small")
        select(*(0..16).map { fileSelection.copy(path = "file-$it.md") }.toTypedArray())
        val bounded = resolver.resolve(session)
        assertEquals(16, bounded.manifest.sources.size)
        assertTrue(bounded.manifest.omissions.any { "first 16" in it })
        coVerify(exactly = 0) { browse.readBlob(any(), any(), "file-16.md") }
    }

    @Test fun `explicit sources precede and deduplicate managed domain defaults`() = runTest {
        val agent = bosca.ai.agents.model.Agent(UUID.random(), "kit", "Kit", "", UUID.random(), UUID.random(),
            configuration = buildJsonObject { put("harnessContext", json.encodeToJsonElement(KitContextConfiguration.serializer(),
                KitContextConfiguration(sources = listOf(specSelection, fileSelection)))) })
        coEvery { agents.getByKey("kit") } returns agent
        coEvery { prompts.get(agent.promptId) } returns bosca.ai.prompts.model.Prompt(agent.promptId, "kit", "Kit", "", "Instructions", "", "text", "text", null)
        select(fileSelection)
        val result = resolver.resolve(session)
        assertEquals(listOf("git_file", "spec"), result.manifest.sources.map { it.kind })
        assertEquals(listOf(true, false), result.references.map { it.selected })
        assertEquals(1, result.selectionCount)
    }

    @Test fun `missing optional domain providers omit selections without affecting ordinary context`() = runTest {
        val optional = KitContextResolver(agents, prompts, history, metadata, documents, collections,
            MetadataPermissionEvaluator(metadata, security, groups), CollectionPermissionEvaluator(collections, security, groups), security, json, memories,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        select(taskSelection, specSelection, requirementSelection, fileSelection)
        val result = optional.resolve(session)
        assertTrue(result.references.isEmpty())
        assertEquals(4, result.selectionCount)
        assertContains(result.manifest.omissions, "Some selected sources are unavailable.")
        coVerify(exactly = 0) { documents.getDocument(any(), any()) }
    }
}
