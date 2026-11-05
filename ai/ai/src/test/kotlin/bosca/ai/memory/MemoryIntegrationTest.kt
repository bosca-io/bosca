@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ai.memory

import bosca.ai.chat.model.ChatSession
import bosca.ai.chat.repository.AiMigration
import bosca.ai.chat.repository.ChatHistoryRepositoryImpl
import bosca.ai.chat.service.ChatHistoryService
import bosca.cache.*
import bosca.cache.nats.NatsCacheManager
import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionPermission
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.*
import bosca.content.metadata.repository.*
import bosca.content.metadata.service.*
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.db.*
import bosca.db.migrations.CoreMigration
import bosca.db.migrations.FlywayMigration
import bosca.di.*
import bosca.documents.Content
import bosca.documents.TextNode
import bosca.events.DisabledEventManagerFilter
import bosca.events.EventManager
import bosca.events.asCoroutineContext
import bosca.search.model.SearchDocument
import bosca.search.model.SearchResult
import bosca.search.service.SearchService
import bosca.security.model.*
import bosca.security.service.*
import bosca.serialization.*
import bosca.test.resources.SharedNatsContainer
import bosca.test.resources.SharedPostgreSQLContainer
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.*
import kotlin.uuid.toJavaUuid

class MemoryIntegrationTest {
    @Test fun `content memory survives conversations and concurrent retries while revisions ACLs and deletion remain authoritative`(): Unit = kotlinx.coroutines.runBlocking {
        val postgres = SharedPostgreSQLContainer().withReuse(true)
        val nats = SharedNatsContainer().withExposedPorts(4222).withCommand("-js").withReuse(true)
        postgres.start(); nats.start()
        val pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 6), "memory"))
        val natsPool = nats.newConnectionPool(1)
        val cache = NatsCacheManager(natsPool)
        val json = Json { serializersModule = SerializersModule { contextual(UUIDSerializer()); contextual(OffsetDateTimeSerializer()) } }
        val serializer = RequestCacheSerializerImpl(json)
        ProviderRegistry.clear()
        provides<ConnectionPool> { pool }; provides<Json> { json }; provides<CacheManager> { cache }; provides<RequestCacheSerializer> { serializer }
        suspend fun <T> request(block: suspend () -> T): T {
            val manager = ConnectionManager(pool)
            val requestCache = RequestCache(cache, serializer)
            val events = EventManager().apply { filter = DisabledEventManagerFilter }
            return withContext(manager.asCoroutineContext() + requestCache.asCoroutineContext() + events.asCoroutineContext()) {
                try { block() } finally { withContext(NonCancellable) { manager.release() } }
            }
        }
        try {
            FlywayMigration(pool).migrate(listOf(CoreMigration(), AiMigration()))
            val principal = Principal(id = UUID.random(), anonymous = false)
            val other = Principal(id = UUID.random(), anonymous = false)
            val privateGroup = Group(UUID.random(), "${principal.id}.user", "Private", GroupType.PRINCIPAL)
            val shared = Group(UUID.random(), "memory-test-${UUID.random()}", "Shared", GroupType.PRINCIPAL)
            fun auth(person: Principal = principal, groups: List<Group> = listOf(privateGroup, shared), scopes: List<String> = listOf("content:view", "content:edit", "content:delete", "content:manage", "collections:view", "collections:edit")) =
                object : AuthenticationContext(null, null) { override fun principal() = ScopedAuthenticatedPrincipal(person, groups, scopes, groups.map { it.id }.toSet(), 1) }
            val security = mockk<SecurityService>(relaxed = true)
            coEvery { security.getGroupByName(privateGroup.name, GroupType.PRINCIPAL) } returns privateGroup
            val collection = Collection(id = UUID.random(), name = "Project memory", languageTag = "en", workflowStateId = "pending")
            val collections = mockk<CollectionService>(relaxed = true)
            var collectionVisible = true
            coEvery { collections.getById(collection.id) } answers { if (collectionVisible) collection else null }
            coEvery { collections.getPermissions(collection) } returns listOf(PermissionAction.VIEW, PermissionAction.EDIT, PermissionAction.DELETE).map {
                CollectionPermission(collection.id, shared.id, it)
            }
            val history = mockk<ChatHistoryService>()
            val search = mockk<SearchService>()
            val groups = GroupEvaluator(security)
            lateinit var metadata: MetadataService
            val documentRepository = DocumentRepositoryImpl()
            val documents = DocumentServiceImpl(provider { metadata }, documentRepository, DocumentCollaborationRepositoryImpl(), mockk(relaxed = true), json)
            metadata = MetadataServiceImpl(MetadataRepositoryImpl(), collections, mockk(relaxed = true), provider { MetadataFindRepository(json) },
                MetadataPermissionRepositoryImpl(), MetadataTraitRepositoryImpl(), MetadataCategoryRepositoryImpl(), MetadataWorkflowPlanRepositoryImpl(),
                MetadataProfileRepositoryImpl(), MetadataSupplementaryRepositoryImpl(), MetadataRelationshipRepositoryImpl(), mockk(relaxed = true),
                documents, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
                mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), provider { security },
                mockk(relaxed = true), mockk(relaxed = true))
            val permissions = MetadataPermissionEvaluator(metadata, security, groups)
            val saves = MemorySaveRepositoryImpl()
            val service = MemoryServiceImpl(metadata, documents, collections, permissions, CollectionPermissionEvaluator(collections, security, groups),
                security, groups, history, search, saves, json)
            request {
                for (person in listOf(principal, other)) connection().useStatement("insert into principals (id) values (?)") {
                    it.setObject(1, person.id.toJavaUuid()); it.executeUpdate()
                }
                for (group in listOf(privateGroup, shared)) connection().useStatement("insert into groups (id, name, description, type) values (?, ?, ?, 'principal')") {
                    it.setObject(1, group.id.toJavaUuid()); it.setString(2, group.name); it.setString(3, group.description); it.executeUpdate()
                }
                connection().useStatement("insert into traits (id, name, description) values ('ai.memory', 'Memory', '') on conflict do nothing") { it.executeUpdate() }
            }
            val firstChat = request { ChatHistoryRepositoryImpl().createSession(UUID.random(), principal.id, null, "codex", "First", JsonObject(emptyMap())) }
            val secondChat = request { ChatHistoryRepositoryImpl().createSession(UUID.random(), principal.id, null, "claude", "Later", JsonObject(emptyMap())) }
            coEvery { history.getSession(firstChat.id) } returns firstChat
            coEvery { history.getSession(secondChat.id) } returns secondChat
            val input = RememberMemoryInput(UUID.random(), "Use concise release notes.", conversationId = firstChat.id, tags = listOf("writing"))
            val saved = coroutineScope { (1..5).map { async { request { service.remember(auth(), input) } } }.awaitAll() }
            assertEquals(1, saved.map { it.id }.distinct().size)
            assertEquals(1, saved.map { it.revision }.distinct().size)
            val memory = saved.first()
            assertEquals(input.statement, memory.statement)
            assertEquals(firstChat.id, memory.provenance.conversationId)
            assertEquals(MemoryScope.PERSONAL, memory.provenance.scope)
            request {
                assertEquals(input.statement, assertNotNull(service.get(auth(), memory.id)).statement)
                assertEquals(memory.revision, service.get(auth(), memory.id)?.revision)
                assertNull(service.get(auth(other, listOf(shared)), memory.id))
                // Built-in editor and manager roles reach all metadata, but not another person's personal memory.
                for (role in listOf("editors", "managers")) {
                    val elevated = auth(other, listOf(shared, Group(UUID.random(), role, role, GroupType.SYSTEM)))
                    assertNull(service.get(elevated, memory.id))
                    assertFailsWith<IllegalArgumentException> { service.update(elevated, memory.id, memory.revision, "Elevated overwrite", false) }
                    assertFailsWith<IllegalArgumentException> { service.forget(elevated, memory.id, memory.revision) }
                }
                assertNull(service.get(auth(groups = listOf(shared)), memory.id))
                assertNull(service.get(auth(scopes = listOf("content:edit")), memory.id))
                assertEquals(memory.id, service.search(auth(), MemoryQuery(tags = listOf("writing"))).single().id)
                assertEquals(memory.revision, service.reads(auth(), firstChat.id).single().revision, "Saving from a conversation records its read")
                service.recordRead(auth(), secondChat.id, memory)
                assertEquals(memory.revision, service.reads(auth(), secondChat.id).single().revision)
                assertFailsWith<IllegalArgumentException> { service.recordRead(auth(other, listOf(shared)), secondChat.id, memory) }
                assertFailsWith<IllegalArgumentException> { service.remember(auth(), input.copy(statement = "Different request")) }
                for (secret in listOf("api_key=example-secret", "Bearer scoped-secret", "bsk_example_scoped_token", "-----BEGIN PRIVATE KEY-----")) {
                    assertFailsWith<IllegalArgumentException> { service.remember(auth(), input.copy(requestId = UUID.random(), statement = secret)) }
                }
            }
            // Pages are taken from visible, matching memories, so filtered pages neither repeat nor skip results.
            val paged = request {
                (0 until 6).mapNotNull { index ->
                    val tags = if (index % 2 == 0) listOf("paging") else listOf("other")
                    service.remember(auth(), RememberMemoryInput(UUID.random(), "Paging fixture $index.", tags = tags)).takeIf { index % 2 == 0 }
                }
            }
            request {
                val first = service.search(auth(), MemoryQuery(tags = listOf("paging"), limit = 2))
                val second = service.search(auth(), MemoryQuery(tags = listOf("paging"), limit = 2, offset = 2))
                assertEquals(2, first.size)
                assertEquals(1, second.size)
                assertEquals(paged.map { it.id }.toSet(), (first + second).map { it.id }.toSet())
                assertTrue(service.search(auth(), MemoryQuery(tags = listOf("paging"), limit = 2, offset = 3)).isEmpty())
                for (fixture in paged + service.search(auth(), MemoryQuery(tags = listOf("other"), limit = 20))) {
                    service.forget(auth(), fixture.id, fixture.revision)
                }
            }
            request { ChatHistoryRepositoryImpl().deleteSession(firstChat.id) }
            coEvery { history.getSession(firstChat.id) } returns null
            request { assertEquals(memory.id, service.remember(auth(), input).id) }
            // The content editor may replace a draft without advancing its version; its change still conflicts.
            withTimeout(10_000) {
                coroutineScope {
                    val draftLocked = CompletableDeferred<Unit>()
                    val saveDraft = CompletableDeferred<Unit>()
                    val editor = async {
                        request {
                            transaction {
                                documents.getDocumentForUpdate(memory.id, 1)
                                draftLocked.complete(Unit)
                                saveDraft.await()
                                metadata.setDocument(requireNotNull(metadata.getById(memory.id)), DocumentInput(title = "Memory",
                                    content = Content(bosca.documents.Document(content = listOf(TextNode(text = "Edited in Studio"))))))
                            }
                        }
                    }
                    draftLocked.await()
                    val competingPid = CompletableDeferred<Int>()
                    val competingEdit = async {
                        request {
                            transaction {
                                connection().useStatement("select pg_backend_pid()") {
                                    it.executeQuery().use { rows -> rows.next(); competingPid.complete(rows.getInt(1)) }
                                }
                                assertFailsWith<IllegalArgumentException> {
                                    service.update(auth(), memory.id, memory.revision, "Concurrent stale overwrite", false)
                                }
                            }
                        }
                    }
                    val pid = competingPid.await()
                    try {
                        // Observe the real row-lock wait before releasing the editor's transaction.
                        withTimeout(5_000) {
                            while (!request {
                                connection().useStatement("select wait_event_type = 'Lock' from pg_stat_activity where pid = ?") {
                                    it.setInt(1, pid)
                                    it.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
                                }
                            }) delay(10)
                        }
                    } finally { saveDraft.complete(Unit) }
                    editor.await(); competingEdit.await()
                }
            }
            request { assertFailsWith<IllegalArgumentException> { service.update(auth(), memory.id, memory.revision, "Stale overwrite", false) } }
            val edited = request { service.get(auth(), memory.id) } ?: error("missing memory")
            request {
                // Ordinary draft edits preserve the existing supplementary records.
                repeat(2) {
                    val attachment = UUID.random()
                    connection().useStatement("insert into metadata_supplementary (id, metadata_id, plan_id, key, name, content_type) values (?, ?, ?, 'reference', 'Reference', 'text/plain')") { statement ->
                        statement.setObject(1, attachment.toJavaUuid()); statement.setObject(2, memory.id.toJavaUuid())
                        statement.setObject(3, UUID.random().toJavaUuid()); statement.executeUpdate()
                    }
                    connection().useStatement("insert into metadata_supplementary_traits (id, trait_id) values (?, 'ai.memory')") { statement ->
                        statement.setObject(1, attachment.toJavaUuid()); statement.executeUpdate()
                    }
                }
            }
            val updated = request { service.update(auth(), memory.id, edited.revision, "Prefer short release notes with links.", false) }
            assertEquals(memory.version, updated.version)
            assertNotEquals(edited.revision, updated.revision)
            request {
                assertEquals(listOf(MemoryAttributes.TRAIT), metadata.getTraitIds(memory.id))
                assertEquals("Prefer short release notes with links.", service.get(auth(), memory.id)?.statement)
                assertEquals(memory.revision, service.reads(auth(), secondChat.id).single().revision)
                assertFailsWith<IllegalArgumentException> { service.forget(auth(), memory.id, edited.revision) }
                val again = service.update(auth(), memory.id, updated.revision, "Prefer concise notes and source links.", false)
                assertEquals(memory.version, again.version)
                assertNotEquals(updated.revision, again.revision)
                assertEquals(2, metadata.getSupplementary(memory.id).size)
                assertTrue(service.forget(auth(), memory.id, again.revision))
                assertNull(service.get(auth(), memory.id))
                assertTrue(service.search(auth(), MemoryQuery()).isEmpty())
                assertFailsWith<IllegalArgumentException> { service.remember(auth(), input) }
                assertEquals(memory.id, saves.getForUpdate(principal.id, input.requestId)?.metadataId)
                service.clearReads(secondChat.id)
                assertTrue(service.reads(auth(), secondChat.id).isEmpty())
            }
            val source = request { metadata.add(null, null, MetadataInput(name = "Authoritative plan", languageTag = "en", contentType = "bosca/v-document",
                document = DocumentInput(title = "Plan", content = Content(bosca.documents.Document(content = listOf(TextNode(text = "Launch in May"))))))) }
            request { metadata.addPermission(PermissionInput(PermissionAction.VIEW, source.id, shared.id)) }
            val sharedMemory = request { service.remember(auth(), RememberMemoryInput(UUID.random(), "Launch is planned for May.", MemoryScope.COLLECTION, collection.id, source.id)) }
            request {
                assertNotNull(service.get(auth(other, listOf(shared)), sharedMemory.id))
                assertEquals(sharedMemory.id, service.search(auth(other, listOf(shared)), MemoryQuery(collectionIds = listOf(collection.id), personal = false)).single().id)
                assertTrue(service.search(auth(other, listOf(shared)), MemoryQuery()).isEmpty())
                metadata.setDocument(source, DocumentInput(title = "Plan", content = Content(bosca.documents.Document(content = listOf(TextNode(text = "Launch in June"))))))
            }
            request {
                val stale = assertNotNull(service.get(auth(), sharedMemory.id))
                assertTrue(stale.sourceChanged)
                val corrected = service.update(auth(), stale.id, stale.revision, "Launch is planned for June.", true)
                assertFalse(corrected.sourceChanged)
                assertNotEquals(stale.provenance.sourceRevision, corrected.provenance.sourceRevision)
                val indexed = requireNotNull(metadata.getById(sharedMemory.id))
                coEvery { search.search(match { it.storageSystemName == "Admin Search Index" && it.limit == 100 && it.filter == listOf("contentType = \"${MemoryAttributes.CONTENT_TYPE}\"") }) } returns
                    SearchResult(listOf(SearchDocument(metadata = indexed)), emptyList(), 5000, mockk())
                collectionVisible = false
                assertTrue(service.search(auth(), MemoryQuery(text = "launch", collectionIds = listOf(collection.id))).isEmpty())
                collectionVisible = true
                metadata.deletePermission(PermissionInput(PermissionAction.VIEW, source.id, shared.id))
            }
            request {
                assertNull(service.get(auth(), sharedMemory.id))
                assertTrue(service.search(auth(), MemoryQuery(text = "launch", collectionIds = listOf(collection.id))).isEmpty())
                metadata.addPermission(PermissionInput(PermissionAction.VIEW, source.id, shared.id))
                metadata.markDeleted(source.id)
                assertNull(service.get(auth(), sharedMemory.id))
            }
            val expiring = request { service.remember(auth(), RememberMemoryInput(UUID.random(), "Temporary preference", expires = OffsetDateTime.now().plusDays(1))) }
            request {
                val item = requireNotNull(metadata.getById(expiring.id))
                metadata.setSystemAttributes(item, json.encodeToJsonElement(MemoryAttributes.serializer(), expiring.provenance.copy(expires = OffsetDateTime.now().minusSeconds(1))))
                assertNull(service.get(auth(), expiring.id))
                val adminGroup = Group(UUID.random(), "administrators", "Administrators", GroupType.SYSTEM)
                val admin = object : AuthenticationContext(null, null) {
                    override fun principal() = AuthenticatedPrincipal(principal, listOf(adminGroup))
                }
                // Memory eligibility does not change the underlying content's ACL behavior.
                assertTrue(permissions.isAllowed(admin, item, PermissionAction.VIEW))
                assertTrue(permissions.isContentAllowed(admin, item, PermissionAction.VIEW))
                assertNotNull(documents.getDocument(item.id, item.version))
                assertNull(service.get(admin, expiring.id))
            }
            repeat(7) { index -> request { service.remember(auth(), RememberMemoryInput(UUID.random(), "Bounded preference $index", tags = listOf("bounded"))) } }
            request {
                assertEquals(3, service.search(auth(), MemoryQuery(tags = listOf("bounded"), limit = 3)).size)
                assertEquals(5, service.search(auth(), MemoryQuery()).size)
                assertFailsWith<IllegalArgumentException> { service.search(auth(), MemoryQuery(limit = 21)) }
            }
        } finally {
            ProviderRegistry.clear(); pool.close(); natsPool.close(); nats.stop(); postgres.stop()
        }
    }

    private inline fun <reified T : Any> provider(crossinline value: () -> T): ObjectProvider<T> = mockk {
        coEvery { get() } answers { value() }
        every { exists } returns true
    }
}
