package bosca.ai.kit.harness.runtime

import bosca.ai.agents.model.Agent
import bosca.ai.agents.service.AgentService
import bosca.ai.chat.model.ChatHistoryMessage
import bosca.ai.chat.model.ChatSession
import bosca.ai.chat.service.ChatHistoryService
import bosca.ai.kit.harness.*
import bosca.ai.prompts.model.Prompt
import bosca.ai.prompts.service.PromptService
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Document
import bosca.content.metadata.model.EmptyMetadata
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.documents.Content
import bosca.documents.TextNode
import bosca.security.model.*
import bosca.security.service.*
import bosca.serialization.*
import com.agentclientprotocol.model.ContentBlock
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.*

class KitContextResolverTest {
    private val json = Json { serializersModule = SerializersModule {
        contextual(UUIDSerializer()); contextual(OffsetDateTimeSerializer())
    } }
    private val principal = Principal(id = UUID.random(), anonymous = false)
    private val group = Group(UUID.random(), "context-users", "", GroupType.PRINCIPAL)
    private val security = mockk<SecurityService> {
        coEvery { getPrincipalById(principal.id) } returns principal
        coEvery { getPrincipalGroups(principal.id) } returns listOf(group)
    }
    private val agents = mockk<AgentService>()
    private val prompts = mockk<PromptService>()
    private val history = mockk<ChatHistoryService>()
    private val metadata = mockk<MetadataService>()
    private val documents = mockk<DocumentService>()
    private val collections = mockk<CollectionService>()
    private val evaluator = GroupEvaluator(security)
    private val memories = mockk<bosca.ai.memory.MemoryService>(relaxed = true)
    private val resolver = KitContextResolver(agents, prompts, history, metadata, documents, collections,
        MetadataPermissionEvaluator(metadata, security, evaluator), CollectionPermissionEvaluator(collections, security, evaluator), security, json, memories,
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
    private val authority = KitRuntimeAuthority(listOf("content:view", "collections:view"), listOf(group.id))
    private val config = KitHarnessConfiguration("kit", "unused", "unused", scopes = authority.scopes,
        image = "runtime@sha256:" + "a".repeat(64), imageId = "sha256:" + "b".repeat(64), versions = RuntimeVersions("test", "test", "test", "codex-0.160.1"))
    private val resolution = KitHarnessResolution(UUID.random(), "policy", "SYSTEM", config, authority)
    private val session = KitRuntimeSession(UUID.random(), principal.id, JsonObject(emptyMap()), JsonObject(emptyMap()),
        resolvedSettings = json.encodeToJsonElement(KitHarnessResolution.serializer(), resolution), idleAt = OffsetDateTime.now())
    private var chat = ChatSession(session.sessionId, principal.id, agentKey = "kit")
    private val source = EmptyMetadata.copy(id = UUID.random(), name = "Private plan")
    private var document = Document(source.id, 1, title = "Plan", content = Content(bosca.documents.Document(content = listOf(TextNode(text = "Allowed body")))))
    private var allowed = true

    init {
        coEvery { history.getSession(session.sessionId) } answers { chat }
        coEvery { history.getRecentMessages(any(), any(), any()) } returns emptyList()
        coEvery { agents.getByKey("kit") } returns null
        coEvery { metadata.getById(source.id) } returns source
        coEvery { metadata.getPermissions(any()) } answers {
            if (allowed) listOf(bosca.content.metadata.model.MetadataPermission(source.id, group.id, PermissionAction.VIEW)) else emptyList()
        }
        coEvery { metadata.isParentAllowed(any(), any(), any()) } returns false
        coEvery { documents.getDocument(source.id, 1) } answers { document }
    }

    private fun select(ids: List<UUID> = listOf(source.id)) {
        chat = chat.copy(state = buildJsonObject { putJsonArray("attachments") { ids.forEach { add(it.toString()) } } })
    }

    @Test fun `saved memory revisions and tool reads invalidate native context without reintroducing removed bodies`() = runTest {
        val id = UUID.random()
        var memory: bosca.ai.memory.Memory? = bosca.ai.memory.Memory(id, 1, "first", "Use concise answers",
            bosca.content.metadata.model.MemoryAttributes(bosca.content.metadata.model.MemoryScope.PERSONAL, principal.id), false, emptyList(), OffsetDateTime.now(), null)
        coEvery { memories.search(any(), any()) } answers { listOfNotNull(memory) }
        coEvery { memories.get(any(), id) } answers { memory }
        val first = resolver.resolve(session)
        assertContains(first.resources.single().second, "Use concise answers")
        assertEquals("memory", first.manifest.sources.single().kind)
        assertEquals("1", first.manifest.sources.single().version)
        val warmed = session.copy(contextManifest = json.encodeToJsonElement(KitContextManifest.serializer(), first.manifest))
        assertTrue(resolver.canRestoreWorkspace(warmed))
        memory = requireNotNull(memory).copy(version = 2, revision = "second", statement = "Use detailed answers")
        assertNotEquals(first.manifest.revision, resolver.resolve(warmed).manifest.revision)
        assertFalse(resolver.canRestoreWorkspace(warmed))
        memory = null
        assertFalse(resolver.canRestoreWorkspace(warmed))
        assertTrue(resolver.resolve(warmed).references.isEmpty())

        // A memory fetched through MCP was not necessarily one of the five initial selections.
        val empty = resolver.resolve(session)
        val receipt = bosca.ai.memory.MemoryRead(session.sessionId, id, "first", false)
        coEvery { memories.reads(any(), session.sessionId) } returns listOf(receipt)
        val previouslyEmpty = session.copy(contextManifest = json.encodeToJsonElement(KitContextManifest.serializer(), empty.manifest))
        assertNotEquals(empty.manifest.revision, resolver.resolve(previouslyEmpty).manifest.revision)
        assertFalse(resolver.canRestoreWorkspace(previouslyEmpty))
        assertTrue(resolver.resolve(previouslyEmpty).resources.isEmpty())
        resolver.clearMemoryReads(session.sessionId)
        coVerify { memories.clearReads(session.sessionId) }
    }

    @Test fun `new unchanged memory tool reads keep the native session warm until their delivered revision changes`() = runTest {
        val original = resolver.resolve(session)
        val warmed = session.copy(contextManifest = json.encodeToJsonElement(KitContextManifest.serializer(), original.manifest))
        var memory = bosca.ai.memory.Memory(UUID.random(), 1, "delivered", "Read on demand",
            bosca.content.metadata.model.MemoryAttributes(bosca.content.metadata.model.MemoryScope.PERSONAL, principal.id), false, emptyList(), OffsetDateTime.now(), null)
        val receipt = bosca.ai.memory.MemoryRead(session.sessionId, memory.id, memory.revision, memory.sourceChanged)
        coEvery { memories.reads(any(), session.sessionId) } returns listOf(receipt)
        coEvery { memories.get(any(), memory.id) } answers { memory }
        val current = resolver.resolve(warmed)
        assertEquals(original.manifest.revision, current.manifest.revision)
        assertEquals(memory.id.toString(), current.manifest.sources.single().id)
        assertTrue(current.resources.isEmpty(), "Already delivered tool data does not consume the injection budget")
        assertTrue(resolver.canRestoreWorkspace(warmed))
        memory = memory.copy(version = 2, revision = "edited", statement = "Corrected on demand")
        assertNotEquals(original.manifest.revision, resolver.resolve(warmed).manifest.revision)
        assertFalse(resolver.canRestoreWorkspace(warmed))
    }

    @Test fun `memory selection stays bounded and source changes are explicitly uncertain`() = runTest {
        val memory = bosca.ai.memory.Memory(UUID.random(), 1, "one", "Remembered plan",
            bosca.content.metadata.model.MemoryAttributes(bosca.content.metadata.model.MemoryScope.COLLECTION, principal.id,
                sourceMetadataId = source.id, sourceVersion = 1), true, emptyList(), OffsetDateTime.now(), null)
        coEvery { memories.search(any(), match { it.limit == 5 && it.ids.size <= 20 && it.collectionIds.size <= 16 }) } returns listOf(memory)
        val context = resolver.resolve(session)
        assertContains(context.resources.single().second, "this memory is uncertain")
        assertContains(context.resources.single().second, source.id.toString())
        assertTrue(context.manifest.usedCharacters <= context.manifest.budgetCharacters)
    }

    @Test fun `current ACL and token ceiling prevent private titles and bodies from reaching other callers`() = runTest {
        select()
        val permitted = resolver.resolve(session)
        assertContains(permitted.resources.single().second, "Allowed body")
        assertEquals("Private plan", permitted.references.single().title)
        allowed = false
        clearMocks(documents, answers = false)
        val denied = resolver.resolve(session)
        assertTrue(denied.resources.isEmpty())
        assertTrue(denied.references.isEmpty())
        assertEquals(listOf("Some selected sources are unavailable."), denied.manifest.omissions)
        coVerify(exactly = 0) { documents.getDocument(any(), any()) }
        assertNotEquals(permitted.manifest.revision, denied.manifest.revision)
        allowed = true
        val restricted = object : AuthenticationContext(null, null) {
            override fun principal() = ScopedAuthenticatedPrincipal(principal, listOf(group), emptyList(), setOf(group.id), 0)
        }
        assertTrue(resolver.resolve(session, caller = restricted).references.isEmpty())
        val other = session.copy(principalId = UUID.random())
        coEvery { security.getPrincipalById(other.principalId) } returns principal.copy(id = other.principalId)
        coEvery { security.getPrincipalGroups(other.principalId) } returns emptyList()
        assertFailsWith<IllegalArgumentException> { resolver.resolve(other) }
    }

    @Test fun `metadata visibility alone does not fetch a private body`() = runTest {
        select()
        allowed = false
        coEvery { metadata.getById(source.id) } returns source.copy(public = true, workflowStateId = "published")
        val resolved = resolver.resolve(session)
        assertEquals(1, resolved.resources.size)
        assertFalse(resolved.resources.single().second.contains("Allowed body"))
        coVerify(exactly = 0) { documents.getDocument(any(), any()) }
    }

    @Test fun `same-version document edits and instruction changes invalidate native context`() = runTest {
        select()
        val initial = resolver.resolve(session)
        document = Document(source.id, 1, title = "Plan", content = Content(bosca.documents.Document(content = listOf(TextNode(text = "Updated body")))))
        val edited = resolver.resolve(session)
        assertEquals(initial.manifest.sources.single().version, edited.manifest.sources.single().version)
        assertNotEquals(initial.manifest.revision, edited.manifest.revision)
        val agent = Agent(UUID.random(), "kit", "Kit", "", UUID.random(), UUID.random())
        coEvery { agents.getByKey("kit") } returns agent
        coEvery { prompts.get(agent.promptId) } returns Prompt(agent.promptId, "kit", "Kit", "", "Use the managed writing rules.", "", "text", "text", null)
        val instructed = resolver.resolve(session)
        assertContains(instructed.instructions, "managed writing rules")
        assertNotEquals(edited.manifest.instructionRevision, instructed.manifest.instructionRevision)
        assertEquals(edited.manifest.sources, instructed.manifest.sources)
        coEvery { prompts.get(agent.promptId) } returns null
        assertFailsWith<IllegalArgumentException> { resolver.resolve(session) }
    }

    @Test fun `explicit selections deduplicate before defaults and bounded excerpts use resource or text capabilities`() = runTest {
        val ids = List(20) { UUID.random() }
        select(listOf(source.id, source.id) + ids)
        ids.forEach { id ->
            coEvery { metadata.getById(id) } returns source.copy(id = id)
            coEvery { documents.getDocument(id, 1) } returns document
        }
        document = Document(source.id, 1, title = "Plan", content = Content(bosca.documents.Document(content = listOf(TextNode(text = "x".repeat(12000))))))
        val bounded = session.copy(resolvedSettings = json.encodeToJsonElement(KitHarnessResolution.serializer(), resolution.copy(configuration = config.copy(contextCharacters = 4096))))
        val resolved = resolver.resolve(bounded)
        assertEquals(resolved, resolver.resolve(bounded))
        assertEquals(source.id.toString(), resolved.manifest.sources.first().id)
        assertEquals(resolved.manifest.sources.size, resolved.manifest.sources.distinctBy { it.id }.size)
        assertTrue(resolved.manifest.usedCharacters <= 4096)
        assertContains(resolved.manifest.omissions, "Only the first 16 selected sources were considered.")
        assertTrue(resolved.content(true).drop(1).all { it is ContentBlock.Resource })
        assertTrue(resolved.content(false).all { it is ContentBlock.Text })
        assertEquals(resolved.manifest.usedCharacters, resolved.instructions.length + resolved.resources.sumOf { it.second.length })
        coVerify(exactly = 0) { metadata.getById(ids.last()) }
    }

    @Test fun `workspace reuse requires continued access to previously injected bodies even after deselection`() = runTest {
        select()
        val initial = resolver.resolve(session)
        val pinned = session.copy(contextManifest = json.encodeToJsonElement(KitContextManifest.serializer(), initial.manifest))
        select(emptyList())
        assertTrue(resolver.canRestoreWorkspace(pinned))
        allowed = false
        coEvery { metadata.getById(source.id) } returns source.copy(public = true, workflowStateId = "published")
        assertFalse(resolver.canRestoreWorkspace(pinned), "A visible title does not authorize restoring formerly private content")
        coEvery { metadata.getById(source.id) } returns source.copy(deleted = true)
        assertFalse(resolver.canRestoreWorkspace(pinned))
    }

    @Test fun `conversation references precede managed defaults and deleted documents never load`() = runTest {
        select()
        val defaultId = UUID.random()
        val agent = Agent(UUID.random(), "kit", "Kit", "", UUID.random(), UUID.random(), configuration = buildJsonObject {
            put("harnessContext", json.encodeToJsonElement(KitContextConfiguration.serializer(), KitContextConfiguration(metadataIds = listOf(source.id, defaultId))))
        })
        coEvery { agents.getByKey("kit") } returns agent
        coEvery { prompts.get(agent.promptId) } returns Prompt(agent.promptId, "kit", "Kit", "", "Managed guidance", "", "text", "text", null)
        coEvery { metadata.getById(defaultId) } returns source.copy(id = defaultId, name = "Default source")
        coEvery { documents.getDocument(defaultId, 1) } returns null
        val resolved = resolver.resolve(session)
        assertEquals(listOf(source.id.toString(), defaultId.toString()), resolved.manifest.sources.map { it.id })
        assertEquals(agent.id.toString(), resolved.manifest.agentId)
        assertEquals(agent.promptId.toString(), resolved.manifest.promptId)
        coEvery { metadata.getById(source.id) } returns source.copy(deleted = true)
        clearMocks(documents, answers = false)
        assertEquals(listOf(defaultId.toString()), resolver.resolve(session).manifest.sources.map { it.id })
        coVerify(exactly = 0) { documents.getDocument(source.id, any()) }
        coEvery { prompts.get(agent.promptId) } returns Prompt(agent.promptId, "kit", "Kit", "", "x".repeat(32000), "", "text", "text", null)
        assertFailsWith<IllegalArgumentException> { resolver.resolve(session) }
    }

    @Test fun `history excludes current and future turns and never exceeds the handoff budget`() = runTest {
        val turn = KitRuntimeTurn(UUID.random(), session.sessionId, message = JsonNull)
        val earlier = ChatHistoryMessage(sessionId = session.sessionId, author = "User", event = JsonPrimitive("earlier"), created = turn.created.minusSeconds(1))
        coEvery { history.getRecentMessages(session.sessionId, turn.created, 31) } returns listOf(
            earlier.copy(event = JsonPrimitive("future"), created = turn.created.plusSeconds(1)),
            earlier.copy(event = JsonPrimitive("current"), created = turn.created), earlier,
            earlier.copy(event = JsonPrimitive("x".repeat(50000)), created = turn.created.minusDays(1)))
        val resolved = resolver.resolve(session, turn)
        assertContains(resolved.history, "earlier")
        assertFalse(resolved.history.contains("future"))
        assertFalse(resolved.history.contains("current"))
        assertEquals(1, resolved.manifest.historyMessages)
        assertTrue(resolved.manifest.usedCharacters <= config.contextCharacters)
        assertTrue(resolver.resolve(session.copy(needsContext = false), turn).history.isEmpty())
        assertEquals(resolved.manifest.revision, resolver.resolve(session).manifest.revision, "History does not cause native-session invalidation")
    }
}
