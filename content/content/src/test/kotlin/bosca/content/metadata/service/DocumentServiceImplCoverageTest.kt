package bosca.content.metadata.service

import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.asCoroutineContext
import bosca.content.metadata.model.Document
import bosca.content.metadata.model.DocumentCollaboration
import bosca.content.metadata.model.DocumentCollaborationInput
import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.repository.DocumentCollaborationRepositoryImpl
import bosca.content.metadata.repository.DocumentRepositoryImpl
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.documents.Content
import bosca.documents.Document as DocumentNode
import bosca.documents.ParagraphNode
import bosca.documents.TextNode
import bosca.documents.marks.Superscript
import bosca.documents.yjs.ProseMirrorYjsBridge
import bosca.events.DisabledEventManagerFilter
import bosca.events.EventManager
import bosca.events.asCoroutineContext
import bosca.graphql.Batch
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.test.ContentTestInfrastructure
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.junit.AfterClass
import org.junit.BeforeClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.uuid.toJavaUuid

/**
 * DB-backed coverage for [DocumentServiceImpl]. The collaboration-sync routing (NONE / RESET /
 * MERGE-with-empty-existing) is covered by [DocumentServiceImplCollabSyncTest]; this test drives
 * the remaining paths against a real Postgres/NATS stack: the cache resolvers (single + batch),
 * `addDocument`, `setDocument` MERGE with existing non-empty content, `setDocumentTemplate`
 * (both branches), `getCollaboration` / `getCollaborationForUpdate`, the three
 * `markCollaboration*Dirty` transaction methods (success, missing-metadata, missing-collaboration),
 * `setCollaboration` (both false and true arms, including the null-content default), and
 * `removeCollaboration`.
 */
@OptIn(InternalDI::class)
class DocumentServiceImplCoverageTest {

    private lateinit var serializer: RequestCacheSerializer

    private val connectionPool
        get() = infrastructure.connectionPool

    private val cacheManager
        get() = infrastructure.cacheManager

    private val testJson = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(OffsetDateTimeSerializer())
            contextual(UUIDSerializer())
        }
    }

    private lateinit var service: DocumentServiceImpl
    private lateinit var documentRepository: DocumentRepositoryImpl
    private lateinit var collaborationRepository: DocumentCollaborationRepositoryImpl

    private val metadataService = mockk<MetadataService>(relaxed = true)
    private val metadataServiceProvider = mockk<ObjectProvider<MetadataService>>()
    private val contentEntityLinkService = mockk<ContentEntityLinkService>(relaxed = true)

    @BeforeTest
    fun setup() = runBlocking {
        unmockkAll()
        infrastructure.reset()
        serializer = RequestCacheSerializerImpl(testJson)

        ProviderRegistry.clear()
        provides<CacheManager>(singleton = true) { cacheManager }
        provides<RequestCacheSerializer>(singleton = true) { serializer }
        provides<Json>(singleton = true) { testJson }

        coEvery { metadataServiceProvider.get() } returns metadataService

        documentRepository = DocumentRepositoryImpl()
        collaborationRepository = DocumentCollaborationRepositoryImpl()

        service = DocumentServiceImpl(
            metadataServiceProvider,
            documentRepository,
            collaborationRepository,
            contentEntityLinkService,
            testJson,
        )
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
        unmockkAll()
    }

    companion object {

        private val infrastructure = ContentTestInfrastructure()

        @BeforeClass
        @JvmStatic
        fun startInfrastructure() = runBlocking {
            infrastructure.start()
        }

        @AfterClass
        @JvmStatic
        fun stopInfrastructure() = runBlocking {
            infrastructure.stop()
        }
    }

    private suspend fun <T> withRequest(block: suspend () -> T): T {
        val cm = ConnectionManager(connectionPool)
        val rc = RequestCache(cacheManager, serializer)
        val em = EventManager().apply { filter = DisabledEventManagerFilter }
        return withContext(cm.asCoroutineContext() + rc.asCoroutineContext() + em.asCoroutineContext()) {
            try {
                block()
            } finally {
                withContext(NonCancellable) {
                    cm.release()
                }
            }
        }
    }

    /** Insert a metadata row so `documents.metadata_id` FK is satisfied. Returns the id. */
    private suspend fun insertMetadata(name: String = "Doc Metadata"): UUID {
        val id = UUID.random()
        val cm = ConnectionManager(connectionPool)
        withContext(cm.asCoroutineContext()) {
            cm.beginTransaction()
            cm.useStatement("insert into metadata (id, name, content_type) values (?, ?, 'bosca/v-document')") { stmt ->
                stmt.setObject(1, id.toJavaUuid())
                stmt.setString(2, name)
                stmt.execute()
            }
            withContext(NonCancellable) { cm.commitTransaction() }
        }
        withContext(NonCancellable) { cm.release() }
        return id
    }

    private suspend fun replaceContent(id: UUID, content: String) {
        val cm = ConnectionManager(connectionPool)
        try {
            withContext(cm.asCoroutineContext()) {
                cm.beginTransaction()
                cm.useStatement("update documents set content = ?::jsonb where metadata_id = ? and version = 1") { stmt ->
                    stmt.setString(1, content)
                    stmt.setObject(2, id.toJavaUuid())
                    stmt.execute()
                }
                withContext(NonCancellable) { cm.commitTransaction() }
            }
        } finally {
            withContext(NonCancellable) { cm.release() }
        }
    }

    private val legacySuperscriptContent = """{"document":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"superscript","content":[{"type":"text","text":"2"}]}]}]}}"""

    // ── cache resolvers ──────────────────────────────────────────────────────

    @Test
    fun `getDocument single-key resolver misses then hits after addDocument`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata()

        // Miss: no document row yet — single-key resolver returns null.
        val missing = withRequest { service.getDocument(id, 1) }
        assertNull(missing)

        withRequest { service.addDocument(id, 1, DocumentInput(title = "My Document", content = Content())) }

        // Fresh request → cache miss → single-key resolver reads the row.
        val fetched = withRequest { service.getDocument(id, 1) }
        assertNotNull(fetched)
        assertEquals("My Document", fetched.title)
        assertEquals(id, fetched.metadataId)
    }

    @Test
    fun `getDocument reads legacy superscript content without changing the stored JSON`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata()
        withRequest { service.addDocument(id, 1, DocumentInput(title = "Legacy", content = Content())) }
        replaceContent(id, legacySuperscriptContent)

        val document = assertNotNull(withRequest { service.getDocument(id, 1) })
        val paragraph = assertIs<ParagraphNode>(document.content?.document?.content?.single())
        val text = assertIs<TextNode>(paragraph.content.single())
        assertEquals("2", text.text)
        assertIs<Superscript>(text.marks.single())
        assertEquals(testJson.parseToJsonElement(legacySuperscriptContent), withRequest { documentRepository.getRawContent(id, 1) })
    }

    @Test
    fun `getDocumentsBatch recovers a legacy row and still returns valid rows`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val legacyId = insertMetadata("Legacy")
        val validId = insertMetadata("Valid")
        withRequest { service.addDocument(legacyId, 1, DocumentInput(title = "Legacy", content = Content())) }
        withRequest { service.addDocument(validId, 1, DocumentInput(title = "Valid", content = Content())) }
        replaceContent(legacyId, legacySuperscriptContent)

        val batch = Batch<MetadataCacheKeyId, Document>(
            keys = listOf(MetadataCacheKeyId(legacyId, 1), MetadataCacheKeyId(validId, 1)),
        )
        withRequest { service.getDocumentsBatch(batch) }

        val legacy = assertNotNull(batch.getData(MetadataCacheKeyId(legacyId, 1)))
        val paragraph = assertIs<ParagraphNode>(legacy.content?.document?.content?.single())
        assertIs<Superscript>(assertIs<TextNode>(paragraph.content.single()).marks.single())
        assertEquals("Valid", batch.getData(MetadataCacheKeyId(validId, 1))?.title)
    }

    @Test
    fun `getDocument still fails for unsupported malformed content`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata()
        withRequest { service.addDocument(id, 1, DocumentInput(title = "Malformed", content = Content())) }
        replaceContent(id, """{"document":{"type":"doc","content":[{"type":"unsupported"}]}}""")

        assertFailsWith<SerializationException> { withRequest { service.getDocument(id, 1) } }
    }

    @Test
    fun `addDocument extracts content links when content present`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata()
        withRequest { service.addDocument(id, 1, DocumentInput(title = "Linked", content = Content())) }
        coVerify(exactly = 1) { contentEntityLinkService.extractAndStore(id, 1, any()) }
    }

    @Test
    fun `getDocumentsBatch resolves present and absent keys via the batch resolver`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val present = insertMetadata("Present")
        val absent = UUID.random()
        withRequest { service.addDocument(present, 1, DocumentInput(title = "Batch Doc", content = Content())) }

        val batch = Batch<MetadataCacheKeyId, Document>(
            keys = listOf(MetadataCacheKeyId(present, 1), MetadataCacheKeyId(absent, 1))
        )
        withRequest { service.getDocumentsBatch(batch) }

        val resolved = batch.getData(MetadataCacheKeyId(present, 1))
        assertNotNull(resolved)
        assertEquals("Batch Doc", resolved.title)
        assertNull(batch.getData(MetadataCacheKeyId(absent, 1)))
    }

    @Test
    fun `removeFromCache clears a cached document`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata()
        withRequest { service.addDocument(id, 1, DocumentInput(title = "Original", content = Content())) }

        // Populate cache.
        assertEquals("Original", withRequest { service.getDocument(id, 1) }?.title)

        // Mutate the row behind the cache's back, then invalidate inside a committed transaction.
        val cm = ConnectionManager(connectionPool)
        val rc = RequestCache(cacheManager, serializer)
        withContext(cm.asCoroutineContext() + rc.asCoroutineContext()) {
            cm.beginTransaction()
            cm.useStatement("update documents set title = 'Renamed' where metadata_id = ?") { stmt ->
                stmt.setObject(1, id.toJavaUuid())
                stmt.execute()
            }
            service.removeFromCache(id, 1)
            withContext(NonCancellable) { cm.commitTransaction() }
        }
        withContext(NonCancellable) { cm.release() }

        // Cache was invalidated → next read reflects the new title.
        assertEquals("Renamed", withRequest { service.getDocument(id, 1) }?.title)
    }

    // ── setDocument MERGE with existing non-empty content ──────────────────────

    @Test
    fun `setDocument MERGE with no existing collaboration row seeds a fresh Yjs update`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata("Merge Seed")
        val metadata = metadata(id)

        // No existing collab row → the `existing == null` arm calls toYDocUpdate and inserts.
        withRequest { service.setDocument(metadata, richDocumentInput("seed"), CollaborationSyncMode.MERGE) }

        val seeded = withRequest { service.getCollaboration(id, 1) }
        assertNotNull(seeded)
        assertTrue(seeded.content.isNotEmpty(), "MERGE with no existing row seeds a Yjs update")
    }

    @Test
    fun `setDocument MERGE merges into an existing non-empty collaboration document`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata("Merge Target")
        val metadata = metadata(id)

        // Create the document row and a real, non-empty Yjs collaboration row.
        withRequest { service.addDocument(id, 1, richDocumentInput("v1")) }
        val baseline = ProseMirrorYjsBridge.toYDocUpdate(richContent("v1"))
        assertTrue(baseline.isNotEmpty())
        withRequest { collaborationRepository.setCollaboration(DocumentCollaboration(id, 1, baseline)) }

        // MERGE finds existing non-empty content → takes the mergeDocument branch.
        withRequest { service.setDocument(metadata, richDocumentInput("v2"), CollaborationSyncMode.MERGE) }

        val merged = withRequest { service.getCollaboration(id, 1) }
        assertNotNull(merged)
        assertTrue(merged.content.isNotEmpty())
    }

    @Test
    fun `setDocument RESET deletes the collaboration row against a real database`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata("Reset Target")
        val metadata = metadata(id)

        // Seed a collaboration row via MERGE first.
        withRequest { service.setDocument(metadata, richDocumentInput("v1"), CollaborationSyncMode.MERGE) }
        assertNotNull(withRequest { service.getCollaboration(id, 1) })

        // RESET should drop it.
        withRequest { service.setDocument(metadata, DocumentInput(title = "v2", content = Content()), CollaborationSyncMode.RESET) }
        assertNull(withRequest { service.getCollaboration(id, 1) })
    }

    // ── setDocumentTemplate ────────────────────────────────────────────────────

    @Test
    fun `setDocumentTemplate creates the document when none exists then assigns the template`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata("Templated Doc")
        val templateId = insertDocumentTemplate()
        val metadata = metadata(id, name = "Templated Doc")

        // No document row yet → the null branch runs setDocument to create one.
        assertNull(withRequest { service.getDocument(id, 1) })

        withRequest { service.setDocumentTemplate(metadata, templateId, 1) }

        val doc = withRequest { service.getDocument(id, 1) }
        assertNotNull(doc)
        // setDocument created it using metadata.name as the title.
        assertEquals("Templated Doc", doc.title)
        assertEquals(templateId, doc.templateMetadataId)
        assertEquals(1, doc.templateMetadataVersion)
    }

    @Test
    fun `setDocumentTemplate skips document creation when one already exists`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata("Existing Doc")
        val templateId = insertDocumentTemplate()
        val metadata = metadata(id, name = "Existing Doc")

        withRequest { service.addDocument(id, 1, DocumentInput(title = "Preexisting Title", content = Content())) }

        withRequest { service.setDocumentTemplate(metadata, templateId, 1) }

        val doc = withRequest { service.getDocument(id, 1) }
        assertNotNull(doc)
        // Title was NOT overwritten because the document already existed.
        assertEquals("Preexisting Title", doc.title)
        assertEquals(templateId, doc.templateMetadataId)
    }

    // ── collaboration reads ────────────────────────────────────────────────────

    @Test
    fun `getCollaboration and getCollaborationForUpdate return null when absent and the row when present`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata("Collab Reads")
        assertNull(withRequest { service.getCollaboration(id, 1) })
        assertNull(withRequest { service.getCollaborationForUpdate(id, 1) })

        withRequest { service.addDocument(id, 1, DocumentInput(title = "Doc", content = Content())) }
        withRequest {
            collaborationRepository.setCollaboration(DocumentCollaboration(id, 1, byteArrayOf(1, 2, 3)))
        }

        val collab = withRequest { service.getCollaboration(id, 1) }
        assertNotNull(collab)
        assertEquals(3, collab.content.size)

        val forUpdate = withRequest { service.getCollaborationForUpdate(id, 1) }
        assertNotNull(forUpdate)
        assertEquals(3, forUpdate.content.size)
    }

    // ── setCollaboration ───────────────────────────────────────────────────────

    @Test
    fun `setCollaboration returns false when the document does not exist`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata("No Doc")
        val result = withRequest { service.setCollaboration(DocumentCollaborationInput(id, 1, byteArrayOf(9))) }
        assertFalse(result)
        assertNull(withRequest { service.getCollaboration(id, 1) })
    }

    @Test
    fun `setCollaboration writes content when the document exists`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata("Has Doc")
        withRequest { service.addDocument(id, 1, DocumentInput(title = "Doc", content = Content())) }

        val result = withRequest { service.setCollaboration(DocumentCollaborationInput(id, 1, byteArrayOf(4, 5))) }
        assertTrue(result)

        val collab = withRequest { service.getCollaboration(id, 1) }
        assertNotNull(collab)
        assertEquals(2, collab.content.size)
    }

    @Test
    fun `setCollaboration defaults null content to an empty byte array`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata("Null Content")
        withRequest { service.addDocument(id, 1, DocumentInput(title = "Doc", content = Content())) }

        // content = null → the elvis writes ByteArray(0).
        val result = withRequest { service.setCollaboration(DocumentCollaborationInput(id, 1, null)) }
        assertTrue(result)

        val collab = withRequest { service.getCollaboration(id, 1) }
        assertNotNull(collab)
        assertTrue(collab.content.isEmpty())
    }

    // ── removeCollaboration ────────────────────────────────────────────────────

    @Test
    fun `removeCollaboration deletes the collaboration row`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata("Remove Collab")
        withRequest { service.addDocument(id, 1, DocumentInput(title = "Doc", content = Content())) }
        withRequest { collaborationRepository.setCollaboration(DocumentCollaboration(id, 1, byteArrayOf(7))) }
        assertNotNull(withRequest { service.getCollaboration(id, 1) })

        withRequest { service.removeCollaboration(id, 1) }
        assertNull(withRequest { service.getCollaboration(id, 1) })
    }

    // ── markCollaboration*Dirty ─────────────────────────────────────────────────

    @Test
    fun `markCollaborationCollectionsDirty flips the collections dirty flag`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = seedDirtyFixture()
        coEvery { metadataService.getById(id) } returns metadata(id)

        withRequest { service.markCollaborationCollectionsDirty(id) }

        val collab = withRequest { service.getCollaboration(id, 1) }
        assertNotNull(collab)
        assertTrue(bosca.content.collaboration.Updater().use { it.areCollectionsDirty(collab.content) })
    }

    @Test
    fun `markCollaborationRelationshipsDirty flips the relationships dirty flag`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = seedDirtyFixture()
        coEvery { metadataService.getById(id) } returns metadata(id)

        withRequest { service.markCollaborationRelationshipsDirty(id) }

        val collab = withRequest { service.getCollaboration(id, 1) }
        assertNotNull(collab)
        assertTrue(bosca.content.collaboration.Updater().use { it.areRelationshipsDirty(collab.content) })
    }

    @Test
    fun `markCollaborationAttributesDirty flips the attributes dirty flag`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = seedDirtyFixture()
        coEvery { metadataService.getById(id) } returns metadata(id)

        withRequest { service.markCollaborationAttributesDirty(id) }

        val collab = withRequest { service.getCollaboration(id, 1) }
        assertNotNull(collab)
        assertTrue(bosca.content.collaboration.Updater().use { it.areAttributesDirty(collab.content) })
    }

    @Test
    fun `markCollaborationCollectionsDirty returns early when metadata is missing`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns null

        // Should not throw and should not attempt any collaboration write.
        withRequest { service.markCollaborationCollectionsDirty(id) }
        assertNull(withRequest { service.getCollaboration(id, 1) })
    }

    @Test
    fun `markCollaborationRelationshipsDirty returns early when the collaboration row is missing`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = insertMetadata("No Collab Row")
        // Document exists but no collaboration row.
        withRequest { service.addDocument(id, 1, DocumentInput(title = "Doc", content = Content())) }
        coEvery { metadataService.getById(id) } returns metadata(id)

        withRequest { service.markCollaborationRelationshipsDirty(id) }
        assertNull(withRequest { service.getCollaboration(id, 1) })
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Metadata id + document row + a fresh (empty) Yjs collaboration row for the dirty tests. */
    private suspend fun seedDirtyFixture(): UUID {
        val id = insertMetadata("Dirty Fixture")
        withRequest { service.addDocument(id, 1, DocumentInput(title = "Doc", content = Content())) }
        withRequest { collaborationRepository.setCollaboration(DocumentCollaboration(id, 1, ByteArray(0))) }
        return id
    }

    /** Non-empty ProseMirror content: a single paragraph carrying [text]. */
    private fun richContent(text: String): Content =
        Content(document = DocumentNode(content = listOf(ParagraphNode(content = listOf(TextNode(text = text))))))

    private fun richDocumentInput(text: String): DocumentInput =
        DocumentInput(title = text, content = richContent(text))

    /** A document template row so `documents.template_metadata_id` FK is satisfied. */
    private suspend fun insertDocumentTemplate(): UUID {
        val templateId = insertMetadata("Doc Template")
        val cm = ConnectionManager(connectionPool)
        withContext(cm.asCoroutineContext()) {
            cm.beginTransaction()
            cm.useStatement("insert into document_templates (metadata_id, version, content) values (?, 1, '{}'::jsonb)") { stmt ->
                stmt.setObject(1, templateId.toJavaUuid())
                stmt.execute()
            }
            withContext(NonCancellable) { cm.commitTransaction() }
        }
        withContext(NonCancellable) { cm.release() }
        return templateId
    }

    /**
     * A [Metadata] stub for the paths that only read `id` and `version`. Mirrors
     * [DocumentServiceImplCollabSyncTest]'s approach — the concrete `Metadata` class only feeds
     * two property reads here, so stubbing exactly those avoids over-specifying its constructor.
     */
    private fun metadata(id: UUID, version: Int = 1, name: String = "Doc Metadata"): Metadata {
        val metadata = mockk<Metadata>(relaxed = true)
        coEvery { metadata.id } returns id
        coEvery { metadata.version } returns version
        coEvery { metadata.name } returns name
        return metadata
    }
}
