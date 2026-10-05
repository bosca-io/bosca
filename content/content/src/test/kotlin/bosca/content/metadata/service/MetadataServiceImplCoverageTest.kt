package bosca.content.metadata.service

import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.asCoroutineContext
import bosca.category.service.CategoryService
import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.content.embedding.model.EmbeddingChunk
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.model.MetadataRelationshipInput
import bosca.content.metadata.model.MetadataSupplementaryInput
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.model.SourceStatus
import bosca.content.metadata.repository.MetadataCategoryRepositoryImpl
import bosca.content.metadata.repository.MetadataPermissionRepositoryImpl
import bosca.content.metadata.repository.MetadataProfileRepositoryImpl
import bosca.content.metadata.repository.MetadataRelationshipRepositoryImpl
import bosca.content.metadata.repository.MetadataRepositoryImpl
import bosca.content.metadata.repository.MetadataSupplementaryRepositoryImpl
import bosca.content.metadata.repository.MetadataTraitRepositoryImpl
import bosca.content.metadata.repository.MetadataWorkflowPlanRepositoryImpl
import bosca.content.recommendation.service.RecommendationContextClassifier
import bosca.content.transition.history.service.TransitionHistoryService
import bosca.content.transition.service.Transitioner
import bosca.content.video.service.VideoService
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.transaction
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.events.DisabledEventManagerFilter
import bosca.events.EventManager
import bosca.events.asCoroutineContext
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.sharedqueue.jobs.JobQueue
import bosca.slug.service.SlugService
import bosca.storage.service.ObjectStorageService
import bosca.test.ContentTestInfrastructure
import bosca.trait.service.TraitService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.junit.AfterClass
import org.junit.BeforeClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.toJavaUuid

/**
 * End-to-end coverage for [MetadataServiceImpl] driving the real PostgreSQL-backed
 * repositories. Cross-aggregate services (documents, guides, templates, storage, etc.)
 * are mocked; the metadata repositories run real SQL through a migrated schema.
 *
 * Complements [MetadataServiceCacheEndToEndTest] (which covers cache/collab-sync specifics)
 * by exercising the wide surface of simple setters, permissions, categories, traits,
 * relationships, supplementary, and state-machine methods.
 */
@OptIn(InternalDI::class)
class MetadataServiceImplCoverageTest {

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

    private lateinit var serializer: RequestCacheSerializer
    private lateinit var repository: MetadataRepositoryImpl
    private lateinit var service: MetadataServiceImpl

    private val collectionService = mockk<CollectionService>(relaxed = true)
    private val collectionTemplateService = mockk<CollectionTemplateService>(relaxed = true)
    private val find = mockk<ObjectProvider<bosca.content.metadata.repository.MetadataFindRepository>>(relaxed = true)
    private val transitionHistory = mockk<TransitionHistoryService>(relaxed = true)
    private val documentService = mockk<DocumentService>(relaxed = true)
    private val documentTemplateService = mockk<DocumentTemplateService>(relaxed = true)
    private val dataTemplateService = mockk<DataTemplateService>(relaxed = true)
    private val guideService = mockk<GuideService>(relaxed = true)
    private val guideTemplateService = mockk<GuideTemplateService>(relaxed = true)
    private val dataService = mockk<DataService>(relaxed = true)
    private val bibleService = mockk<BibleService>(relaxed = true)
    private val objectService = mockk<ObjectStorageService>(relaxed = true)
    private val slugService = mockk<SlugService>(relaxed = true)
    private val traitService = mockk<TraitService>(relaxed = true)
    private val categoriesService = mockk<CategoryService>(relaxed = true)
    private val securityService = mockk<ObjectProvider<SecurityService>>(relaxed = true)
    private val transitioner = mockk<ObjectProvider<Transitioner>>(relaxed = true)
    private val videoService = mockk<VideoService>(relaxed = true)
    private val jobQueue = mockk<JobQueue>(relaxed = true)

    @BeforeTest
    fun setup() = runBlocking {
        unmockkAll()
        infrastructure.reset()
        serializer = RequestCacheSerializerImpl(testJson)

        ProviderRegistry.clear()
        provides<CacheManager>(singleton = true) { cacheManager }
        provides<RequestCacheSerializer>(singleton = true) { serializer }
        provides<Json>(singleton = true) { testJson }
        // importFromUrl enqueues an ImportUrlJob onto the "contentQueue"; a relaxed queue keeps it a no-op.
        provides<JobQueue>(name = "contentQueue", singleton = true) { jobQueue }

        coEvery { slugService.createSlug(any()) } answers { firstArg<String>().lowercase().replace(" ", "-") }

        repository = MetadataRepositoryImpl()

        service = MetadataServiceImpl(
            repository,
            collectionService,
            collectionTemplateService,
            find,
            MetadataPermissionRepositoryImpl(),
            MetadataTraitRepositoryImpl(),
            MetadataCategoryRepositoryImpl(),
            MetadataWorkflowPlanRepositoryImpl(),
            MetadataProfileRepositoryImpl(),
            MetadataSupplementaryRepositoryImpl(),
            MetadataRelationshipRepositoryImpl(),
            transitionHistory,
            documentService,
            documentTemplateService,
            dataTemplateService,
            guideService,
            guideTemplateService,
            dataService,
            bibleService,
            objectService,
            slugService,
            traitService,
            categoriesService,
            securityService,
            transitioner,
            videoService,
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

    /** Runs an ad-hoc statement with no parameters, in its own committed transaction. */
    private suspend fun rawExec(sql: String) {
        val cm = ConnectionManager(connectionPool)
        withContext(cm.asCoroutineContext()) {
            cm.beginTransaction()
            cm.useStatement(sql) { stmt -> stmt.execute() }
            withContext(NonCancellable) { cm.commitTransaction() }
        }
        withContext(NonCancellable) { cm.release() }
    }

    private suspend fun rawUpdate(sql: String, id: UUID) {
        val cm = ConnectionManager(connectionPool)
        withContext(cm.asCoroutineContext()) {
            cm.beginTransaction()
            cm.useStatement(sql) { stmt ->
                stmt.setObject(1, id.toJavaUuid())
                stmt.execute()
            }
            withContext(NonCancellable) { cm.commitTransaction() }
        }
        withContext(NonCancellable) { cm.release() }
    }

    private data class PersistedEmbeddingChunk(
        val index: Int,
        val tokenStart: Int,
        val tokenEnd: Int,
        val tokenCount: Int,
        val aggregationWeight: Double,
        val dimension: Int,
        val firstValue: Float,
    )

    private suspend fun readEmbeddingChunks(id: UUID): List<PersistedEmbeddingChunk> = withRequest {
        connection().useStatement(
            """
                select
                    chunk_index,
                    token_start,
                    token_end,
                    token_count,
                    aggregation_weight,
                    vector_dims(embedding),
                    embedding::text
                from metadata_embeddings
                where metadata_id = ?
                order by chunk_index
            """.trimIndent(),
        ) { stmt ->
            stmt.setObject(1, id.toJavaUuid())
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val firstValue = rs.getString(7)
                            .removePrefix("[")
                            .substringBefore(',')
                            .removeSuffix("]")
                            .toFloat()
                        add(
                            PersistedEmbeddingChunk(
                                index = rs.getInt(1),
                                tokenStart = rs.getInt(2),
                                tokenEnd = rs.getInt(3),
                                tokenCount = rs.getInt(4),
                                aggregationWeight = rs.getDouble(5),
                                dimension = rs.getInt(6),
                                firstValue = firstValue,
                            ),
                        )
                    }
                }
            }
        }
    }

    private suspend fun setEmbeddingsInNamedTransaction(
        applicationName: String,
        metadata: Metadata,
        embeddings: List<EmbeddingChunk>,
        started: CompletableDeferred<Unit>,
    ) = withRequest {
        val manager = connection()
        manager.beginTransaction()
        try {
            manager.useStatement("select set_config('application_name', ?, true)") { stmt ->
                stmt.setString(1, applicationName)
                stmt.executeQuery().use { rs -> check(rs.next()) }
            }
            started.complete(Unit)
            service.setEmbeddings(metadata, embeddings)
            withContext(NonCancellable) { manager.commitTransaction() }
        } catch (error: Exception) {
            withContext(NonCancellable) { manager.rollbackTransaction() }
            throw error
        }
    }

    private suspend fun waitingNamedTransactions(applicationNamePrefix: String): Int = withRequest {
        connection().useStatement(
            """
                select count(*)
                from pg_stat_activity
                where application_name like ?
                  and wait_event_type = 'Lock'
            """.trimIndent(),
        ) { stmt ->
            stmt.setString(1, "$applicationNamePrefix%")
            stmt.executeQuery().use { rs ->
                check(rs.next())
                rs.getInt(1)
            }
        }
    }

    /**
     * Inserts a metadata row. NOTE: [MetadataRepository.add] does not list `workflow_state_id`
     * in its INSERT column list, so the DB always applies its `'pending'` default regardless of
     * the [Metadata.workflowStateId] passed in. To make state-dependent tests meaningful, we
     * persist the requested state with a follow-up UPDATE (only valid, seeded workflow_state ids
     * satisfy the FK) and re-read the row so the returned object matches the persisted state.
     */
    private suspend fun insertMetadata(
        name: String = "Meta",
        contentType: String = "text/plain",
        workflowStateId: String = "pending",
    ): Metadata {
        val added = withRequest {
            repository.add(
                Metadata(
                    name = name,
                    type = MetadataType.STANDARD,
                    contentType = contentType,
                    contentLength = null,
                    languageTag = "en",
                    workflowStateId = workflowStateId,
                    attributes = JsonObject(emptyMap()),
                )
            )
        }
        if (workflowStateId == "pending") return added
        rawUpdate("UPDATE metadata SET workflow_state_id = '$workflowStateId' WHERE id = ?", added.id)
        return withRequest { repository.getById(added.id) } ?: error("missing metadata after state update")
    }

    // ── Simple boolean/attribute setters ─────────────────────────────────────

    @Test
    fun `setName renames existing and returns null for missing`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata(name = "Before")
        val renamed = withRequest { service.setName(m.id, "After") }
        assertNotNull(renamed)
        assertEquals("After", renamed.name)

        val missing = withRequest { service.setName(UUID.random(), "X") }
        assertNull(missing)
    }

    @Test
    fun `flag setters flip persisted state`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        withRequest {
            service.setSearchable(m.id, false)
            service.setRecommendable(m.id, false)
            service.setCommentsEnabled(m.id, true)
            service.setCommentRepliesEnabled(m.id, true)
            service.setSyncVariantRelationships(m.id, false)
            service.setSyncVariantCollections(m.id, false)
        }
        val after = withRequest { service.getById(m.id) }
        assertNotNull(after)
        assertFalse(after.searchable)
        assertFalse(after.recommendable)
        assertTrue(after.commentsEnabled)
        assertTrue(after.commentRepliesEnabled)
        assertFalse(after.syncVariantRelationships)
        assertFalse(after.syncVariantCollections)
    }

    @Test
    fun `setParent updates parent id`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val parent = insertMetadata(name = "Parent")
        val child = insertMetadata(name = "Child")
        withRequest { service.setParent(child.id, parent.id) }
        val after = withRequest { service.getById(child.id) }
        assertNotNull(after)
        assertEquals(parent.id, after.parentId)
        // clear parent
        withRequest { service.setParent(child.id, null) }
        val cleared = withRequest { service.getById(child.id) }
        assertNotNull(cleared)
        assertNull(cleared.parentId)
    }

    @Test
    fun `public flag setters dispatch and persist`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        withRequest {
            service.setPublic(m, true)
            service.setPublicContent(m, true)
            service.setPublicSupplementary(m, true)
        }
        val after = withRequest { service.getById(m.id) }
        assertNotNull(after)
        assertTrue(after.public)
        assertTrue(after.publicContent)
        assertTrue(after.publicSupplementary)
    }

    @Test
    fun `setSystemAttributes persists`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        withRequest { service.setSystemAttributes(m, buildJsonObject { put("k", "v") }) }
        val after = withRequest { service.getById(m.id) }
        assertNotNull(after)
        assertNotNull(after.systemAttributes)
    }

    @Test
    fun `setAttributes rejects arrays and persists objects`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        assertFailsWith<IllegalStateException> {
            withRequest { service.setAttributes(m, JsonArray(emptyList())) }
        }
        withRequest { service.setAttributes(m, buildJsonObject { put("a", 1) }) }
        val after = withRequest { service.getById(m.id) }
        assertNotNull(after)
        val obj = after.attributes as JsonObject
        assertTrue(obj.containsKey("a"))
    }

    @Test
    fun `mergeAttributes merges and no-ops when unchanged`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        withRequest { service.setAttributes(m, buildJsonObject { put("a", 1) }) }
        withRequest { service.mergeAttributes(m, buildJsonObject { put("b", 2) }) }
        val merged = withRequest { service.getById(m.id) }
        assertNotNull(merged)
        val obj = merged.attributes as JsonObject
        assertTrue(obj.containsKey("a"))
        assertTrue(obj.containsKey("b"))

        // merging the same value again should be a no-op (early return branch)
        withRequest { service.mergeAttributes(m, buildJsonObject { put("b", 2) }) }

        assertFailsWith<IllegalStateException> {
            withRequest { service.mergeAttributes(m, JsonArray(emptyList())) }
        }
    }

    // ── Uploaded / embedding / source status / locked ────────────────────────

    @Test
    fun `setUploaded stores content type and null falls back to octet-stream`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        withRequest { service.setUploaded(m.id, "image/png", 42L) }
        val first = withRequest { service.getById(m.id) }
        assertNotNull(first)
        assertEquals("image/png", first.contentType)
        assertEquals(42L, first.contentLength)

        withRequest { service.setUploaded(m.id, null, 7L) }
        val second = withRequest { service.getById(m.id) }
        assertNotNull(second)
        assertEquals("application/octet-stream", second.contentType)

        assertFailsWith<IllegalStateException> {
            withRequest { service.setUploaded(UUID.random(), "image/png", 1L) }
        }
        assertFailsWith<IllegalStateException> {
            withRequest { service.clearUploaded(UUID.random()) }
        }
    }

    @Test
    fun `clearUploaded violates content_type not-null against the real schema`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        withRequest { service.setUploaded(m.id, "image/png", 42L) }
        // MetadataRepository.clearUploaded issues `content_type = null`, but metadata.content_type is
        // NOT NULL (check (length(content_type) > 0)) for the entire migration history — so the UPDATE
        // raises a not-null violation before the service ever reaches getById. Assert the real behavior.
        assertFailsWith<Exception> {
            withRequest { service.clearUploaded(m.id) }
        }
    }

    @Test
    fun `setEmbeddings atomically replaces persisted overlap-aware chunks`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        // metadata_embeddings.embedding is vector(768); pgvector rejects any other dimensionality.
        val firstEmbedding = List(768) { 0.25f }
        val secondEmbedding = List(768) { 0.5f }
        withRequest {
            service.setEmbeddings(
                m,
                listOf(
                    EmbeddingChunk(
                        index = 0,
                        tokenCount = 4,
                        tokenStart = 0,
                        tokenEnd = 4,
                        aggregationWeight = 3.0,
                        embedding = firstEmbedding,
                    ),
                    EmbeddingChunk(
                        index = 1,
                        tokenCount = 4,
                        tokenStart = 2,
                        tokenEnd = 6,
                        aggregationWeight = 3.0,
                        embedding = secondEmbedding,
                    ),
                ),
            )
        }
        assertEquals(
            listOf(
                PersistedEmbeddingChunk(0, 0, 4, 4, 3.0, 768, 0.25f),
                PersistedEmbeddingChunk(1, 2, 6, 4, 3.0, 768, 0.5f),
            ),
            readEmbeddingChunks(m.id),
        )

        withRequest {
            service.setEmbeddings(
                m,
                listOf(EmbeddingChunk(index = 0, tokenCount = 6, embedding = secondEmbedding)),
            )
        }
        assertEquals(
            listOf(PersistedEmbeddingChunk(0, 0, 6, 6, 6.0, 768, 0.5f)),
            readEmbeddingChunks(m.id),
        )
    }

    @Test
    fun `setEmbeddings does not let a stale source snapshot replace current chunks`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val original = insertMetadata()
            val currentEmbedding = List(768) { 0.25f }
            val staleEmbedding = List(768) { 0.75f }

            assertTrue(withRequest {
                service.setEmbeddings(
                    original,
                    listOf(EmbeddingChunk(index = 0, tokenCount = 8, embedding = currentEmbedding)),
                )
            })
            withRequest { repository.setModified(original.id) }
            val current = withRequest { service.getById(original.id) }
            assertNotNull(current)
            assertTrue(current.modified != original.modified)

            assertFalse(withRequest {
                service.setEmbeddings(
                    original,
                    listOf(EmbeddingChunk(index = 0, tokenCount = 8, embedding = staleEmbedding)),
                )
            })
            assertEquals(
                listOf(PersistedEmbeddingChunk(0, 0, 8, 8, 8.0, 768, 0.25f)),
                readEmbeddingChunks(original.id),
            )

            assertTrue(withRequest {
                service.setEmbeddings(
                    current,
                    listOf(EmbeddingChunk(index = 0, tokenCount = 8, embedding = staleEmbedding)),
                )
            })
            assertEquals(
                listOf(PersistedEmbeddingChunk(0, 0, 8, 8, 8.0, 768, 0.75f)),
                readEmbeddingChunks(original.id),
            )
        }

    @Test
    fun `setEmbeddings rejects invalid chunks and missing metadata`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val metadata = insertMetadata()
        val embedding = List(768) { 0.25f }

        assertFailsWith<IllegalArgumentException> {
            withRequest {
                service.setEmbeddings(
                    metadata,
                    listOf(EmbeddingChunk(index = 1, tokenCount = 1, embedding = embedding)),
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            withRequest {
                service.setEmbeddings(
                    metadata,
                    listOf(EmbeddingChunk(index = 0, tokenCount = 0, embedding = embedding)),
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            withRequest {
                service.setEmbeddings(
                    metadata,
                    listOf(
                        EmbeddingChunk(index = 0, tokenCount = 1, embedding = embedding),
                        EmbeddingChunk(
                            index = 1,
                            tokenStart = 1,
                            tokenCount = 1,
                            embedding = embedding.dropLast(1),
                        ),
                    ),
                )
            }
        }
        assertFailsWith<IllegalStateException> {
            withRequest {
                service.setEmbeddings(
                    metadata.copy(id = UUID.random()),
                    listOf(EmbeddingChunk(index = 0, tokenCount = 1, embedding = embedding)),
                )
            }
        }
    }

    @Test
    fun `setEmbeddings serializes concurrent replacements for one metadata item`() =
        runBlocking {
            withTimeout(kotlin.time.Duration.parse("60s")) {
                val metadata = insertMetadata()
                val lockAcquired = CompletableDeferred<Unit>()
                val releaseLock = CompletableDeferred<Unit>()
                val replacements = listOf(
                    listOf(EmbeddingChunk(index = 0, tokenCount = 32, embedding = List(768) { 0.25f })),
                    listOf(
                        EmbeddingChunk(index = 0, tokenCount = 24, embedding = List(768) { 0.5f }),
                        EmbeddingChunk(
                            index = 1,
                            tokenCount = 8,
                            tokenStart = 24,
                            tokenEnd = 32,
                            embedding = List(768) { 0.75f },
                        ),
                    ),
                )
                val prefix = "embedding-replacement-${metadata.id}"

                coroutineScope {
                    val lockHolder = async {
                        withRequest {
                            transaction {
                                checkNotNull(repository.lockForEmbeddingReplacement(metadata.id))
                                lockAcquired.complete(Unit)
                                releaseLock.await()
                            }
                        }
                    }
                    lockAcquired.await()
                    val started = replacements.map { CompletableDeferred<Unit>() }
                    val writes = replacements.mapIndexed { index, replacement ->
                        async {
                            setEmbeddingsInNamedTransaction(
                                "$prefix-$index",
                                metadata,
                                replacement,
                                started[index],
                            )
                        }
                    }
                    started.forEach { it.await() }
                    withTimeout(10_000) {
                        while (waitingNamedTransactions(prefix) != replacements.size) delay(25)
                    }
                    assertTrue(writes.none { it.isCompleted })
                    releaseLock.complete(Unit)
                    lockHolder.await()
                    writes.awaitAll()
                }

                val persisted = readEmbeddingChunks(metadata.id)
                val firstReplacement = listOf(PersistedEmbeddingChunk(0, 0, 32, 32, 32.0, 768, 0.25f))
                val secondReplacement = listOf(
                    PersistedEmbeddingChunk(0, 0, 24, 24, 24.0, 768, 0.5f),
                    PersistedEmbeddingChunk(1, 24, 32, 8, 8.0, 768, 0.75f),
                )
                assertTrue(
                    persisted == firstReplacement || persisted == secondReplacement,
                    "expected one complete replacement, got $persisted",
                )
            }
        }

    @Test
    fun `setEmbeddings rolls back deletion when batch insertion fails`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val metadata = insertMetadata()
            val committed = List(768) { 0.25f }
            withRequest {
                service.setEmbeddings(
                    metadata,
                    listOf(EmbeddingChunk(index = 0, tokenCount = 8, embedding = committed)),
                )
            }

            assertFailsWith<Exception> {
                withRequest {
                    service.setEmbeddings(
                        metadata,
                        listOf(EmbeddingChunk(index = 0, tokenCount = 4, embedding = List(767) { 0.5f })),
                    )
                }
            }

            assertEquals(
                listOf(PersistedEmbeddingChunk(0, 0, 8, 8, 8.0, 768, 0.25f)),
                readEmbeddingChunks(metadata.id),
            )
        }

    @Test
    fun `setSourceStatus updates and errors when missing`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        withRequest { service.setSourceStatus(m.id, SourceStatus.IMPORTED) }
        val after = withRequest { service.getById(m.id) }
        assertNotNull(after)
        assertEquals(SourceStatus.IMPORTED, after.sourceStatus)

        assertFailsWith<IllegalStateException> {
            withRequest { service.setSourceStatus(UUID.random(), SourceStatus.FAILED) }
        }
    }

    @Test
    fun `importFromUrl sets pending status and enqueues job`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        withRequest { service.importFromUrl(m.id, "https://example.com/file.bin", "application/pdf", ready = true, principalId = UUID.random()) }
        val after = withRequest { service.getById(m.id) }
        assertNotNull(after)
        assertEquals(SourceStatus.PENDING, after.sourceStatus)
    }

    @Test
    fun `setLocked dispatches locked and unlocked events`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        withRequest { service.setLocked(m.id, m.version, true) }
        val locked = withRequest { service.getById(m.id) }
        assertNotNull(locked)
        assertTrue(locked.locked)

        withRequest { service.setLocked(m.id, m.version, false) }
        val unlocked = withRequest { service.getById(m.id) }
        assertNotNull(unlocked)
        assertFalse(unlocked.locked)
    }

    // ── Get accessors ────────────────────────────────────────────────────────

    @Test
    fun `getAll getDeleted and getByIds`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata(name = "Listable")
        val all = withRequest { service.getAll(0, 1000) }
        assertTrue(all.any { it.id == m.id })

        val byIds = withRequest { service.getByIds(listOf(m.id)) }
        assertEquals(1, byIds.size)

        withRequest { service.markDeleted(m.id) }
        val deleted = withRequest { service.getDeleted(0, 1000) }
        assertTrue(deleted.any { it.id == m.id })
    }

    @Test
    fun `getById with version and getLanguageVariantById`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        val byVersion = withRequest { service.getById(m.id, m.version) }
        assertNotNull(byVersion)
        assertEquals(m.id, byVersion.id)

        // no language variant registered -> null
        val variant = withRequest { service.getLanguageVariantById(m.id, "fr") }
        assertNull(variant)
    }

    @Test
    fun `getByParentId resolves through parent chain`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val parent = insertMetadata(name = "P")
        val child = insertMetadata(name = "C")
        withRequest { service.setParent(child.id, parent.id) }

        // Query by the child: it has a parent, so it resolves the parent's children.
        val byChild = withRequest { service.getByParentId(child.id) }
        assertTrue(byChild.any { it.id == child.id })

        // Query by parent (no parentId) -> its own children.
        val byParent = withRequest { service.getByParentId(parent.id) }
        assertTrue(byParent.any { it.id == child.id })

        // Missing id -> empty
        val missing = withRequest { service.getByParentId(UUID.random()) }
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `getCategories getTraitIds getProfiles getPermissions default empty`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        assertTrue(withRequest { service.getCategories(m.id) }.isEmpty())
        assertTrue(withRequest { service.getTraitIds(m.id) }.isEmpty())
        assertTrue(withRequest { service.getProfiles(m.id) }.isEmpty())
        assertTrue(withRequest { service.getPermissions(m) }.isEmpty())
        assertTrue(withRequest { service.getPlans(m.id) }.isEmpty())
        assertTrue(withRequest { service.getSupplementary(m.id) }.isEmpty())
        assertTrue(withRequest { service.getRelationships(m.id) }.isEmpty())
    }

    // ── Permissions ──────────────────────────────────────────────────────────

    @Test
    fun `addPermission and deletePermission round-trip`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        val groupId = UUID.random()
        // metadata_permissions.group_id FKs to groups(id) — seed the group before referencing it.
        rawExec("insert into groups (id, name, description) values ('$groupId', 'perm-group-$groupId', 'Test group')")
        val added = withRequest {
            service.addPermission(PermissionInput(action = PermissionAction.VIEW, entityId = m.id, groupId = groupId))
        }
        assertEquals(m.id, added.entityId)
        assertEquals(PermissionAction.VIEW, added.action)

        val perms = withRequest { service.getPermissions(m) }
        assertTrue(perms.any { it.groupId == groupId && it.action == PermissionAction.VIEW })

        val removed = withRequest {
            service.deletePermission(PermissionInput(action = PermissionAction.VIEW, entityId = m.id, groupId = groupId))
        }
        assertEquals(m.id, removed.entityId)
        val after = withRequest { service.getPermissions(m) }
        assertFalse(after.any { it.groupId == groupId })
    }

    // ── Categories ───────────────────────────────────────────────────────────

    @Test
    fun `addCategory deleteCategory and setCategories`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        val catId = UUID.random()
        rawExec("insert into categories (id, name) values ('$catId', 'Cat')")

        withRequest { service.addCategory(m.id, catId) }
        // deleteCategory removes single
        withRequest { service.deleteCategory(m.id, catId) }

        val cat2 = UUID.random()
        rawExec("insert into categories (id, name) values ('$cat2', 'Cat2')")
        withRequest { service.setCategories(m.id, listOf(catId, cat2)) }
        // No exception implies FK-valid category inserts succeeded.
    }

    // ── Traits ───────────────────────────────────────────────────────────────

    @Test
    fun `addTrait deleteTrait and setTraits`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        rawExec("insert into traits (id, name, description) values ('trait-a', 'A', '')")
        rawExec("insert into traits (id, name, description) values ('trait-b', 'B', '')")

        withRequest { service.addTrait(m.id, "trait-a") }
        withRequest { service.deleteTrait(m.id, "trait-a") }
        withRequest { service.setTraits(m.id, listOf("trait-a", "trait-b")) }
        // No exception implies FK-valid trait inserts succeeded.
    }

    // ── State machine ────────────────────────────────────────────────────────

    @Test
    fun `setState records history and updates state`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        // 'approved' is a seeded workflow_state; the FK on metadata.workflow_state_id requires a real id.
        val m = insertMetadata(workflowStateId = "draft")
        val updated = withRequest { service.setState(m, "approved", "moving", Principal(id = UUID.random())) }
        assertEquals("approved", updated.workflowStateId)
    }

    @Test
    fun `setPendingState with and without notify`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata(workflowStateId = "draft")
        val pending = withRequest {
            service.setPendingState(m, "processing", "status", valid = null, principal = Principal(id = UUID.random()), notifyEvent = true)
        }
        assertEquals("processing", pending.workflowStatePendingId)

        val m2 = insertMetadata(workflowStateId = "draft")
        val pending2 = withRequest {
            service.setPendingState(m2, "processing", "status", valid = null, principal = null, notifyEvent = false)
        }
        assertEquals("processing", pending2.workflowStatePendingId)
    }

    @Test
    fun `setPendingStateFailed reverts to current state`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata(workflowStateId = "draft")
        val pending = withRequest {
            service.setPendingState(m, "processing", "status", valid = null, principal = null, notifyEvent = false)
        }
        val failed = withRequest { service.setPendingStateFailed(pending, "boom", Principal(id = UUID.random())) }
        assertEquals("draft", failed.workflowStateId)
    }

    @Test
    fun `setPendingStateFailed errors when no pending state`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata(workflowStateId = "draft")
        assertFailsWith<IllegalStateException> {
            withRequest { service.setPendingStateFailed(m, "boom", null) }
        }
    }

    @Test
    fun `setPendingStateComplete advances to pending state`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        // 'approved' is a seeded workflow_state; both workflow_state_pending_id and workflow_state_id FK to it.
        val m = insertMetadata(workflowStateId = "draft")
        val pending = withRequest {
            service.setPendingState(m, "approved", "status", valid = null, principal = null, notifyEvent = false)
        }
        val completed = withRequest { service.setPendingStateComplete(pending, "done", null) }
        assertEquals("approved", completed.workflowStateId)
    }

    @Test
    fun `setPendingStateComplete published notifies listeners`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata(workflowStateId = "draft")
        val pending = withRequest {
            service.setPendingState(m, "published", "status", valid = null, principal = null, notifyEvent = false)
        }
        val completed = withRequest { service.setPendingStateComplete(pending, "done", Principal(id = UUID.random())) }
        assertEquals("published", completed.workflowStateId)
    }

    @Test
    fun `setPendingStateComplete errors when no pending state`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata(workflowStateId = "draft")
        assertFailsWith<IllegalStateException> {
            withRequest { service.setPendingStateComplete(m, "done", null) }
        }
    }

    @Test
    fun `setReady on non-pending state warns and readies`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata(workflowStateId = "pending")
        rawUpdate("UPDATE metadata SET workflow_state_id = 'draft' WHERE id = ?", m.id)
        val current = withRequest { service.getById(m.id) }
        assertNotNull(current)

        val readied = withRequest { service.setReady(current, Principal(id = UUID.random())) }
        assertNotNull(readied.ready)
    }

    @Test
    fun `setReady inside a transaction is rejected`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata(workflowStateId = "draft")
        val current = withRequest { service.getById(m.id) } ?: error("missing")
        val cm = ConnectionManager(connectionPool)
        val rc = RequestCache(cacheManager, serializer)
        val em = EventManager().apply { filter = DisabledEventManagerFilter }
        try {
            withContext(cm.asCoroutineContext() + rc.asCoroutineContext() + em.asCoroutineContext()) {
                cm.beginTransaction()
                assertFailsWith<IllegalStateException> {
                    service.setReady(current, Principal(id = UUID.random()))
                }
                withContext(NonCancellable) { cm.commitTransaction() }
            }
        } finally {
            withContext(NonCancellable) { cm.release() }
        }
    }

    @Test
    fun `setNotReady clears ready`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata(workflowStateId = "draft")
        rawUpdate("UPDATE metadata SET ready = now() WHERE id = ?", m.id)
        val current = withRequest { service.getById(m.id) } ?: error("missing")
        assertNotNull(current.ready)
        withRequest { service.setNotReady(current) }
        val after = withRequest { service.getById(m.id) }
        assertNotNull(after)
        assertNull(after.ready)
    }

    // ── markDeleted / delete ─────────────────────────────────────────────────

    @Test
    fun `markDeleted soft-deletes`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        withRequest { service.markDeleted(m.id) }
        val after = withRequest { service.getById(m.id) }
        assertNotNull(after)
        assertTrue(after.deleted)
    }

    @Test
    fun `delete removes row and object storage paths`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        withRequest { service.delete(m) }
        val after = withRequest { service.getById(m.id) }
        assertNull(after)
    }

    // ── Guide delegation ─────────────────────────────────────────────────────

    @Test
    fun `guide deletion helpers delegate and clear cache`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        withRequest { service.deleteGuide(m) }
        withRequest { service.deleteGuideStep(m, 1L) }
        withRequest { service.deleteGuideStepModule(m, 1L, 2L) }
    }

    // ── Supplementary ────────────────────────────────────────────────────────

    @Test
    fun `addSupplementary and accessors`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        val supp = withRequest {
            service.addSupplementary(
                MetadataSupplementaryInput(
                    metadataId = m.id,
                    key = "thumb",
                    name = "Thumbnail",
                    contentType = "image/png",
                    attributes = JsonObject(emptyMap()),
                )
            )
        }
        assertEquals(m.id, supp.metadataId)

        val byId = withRequest { service.getSupplementaryById(supp.id) }
        assertNotNull(byId)
        val byKey = withRequest { service.getSupplementaryByMetadataAndKey(m.id, "thumb") }
        assertNotNull(byKey)
        val list = withRequest { service.getSupplementary(m.id) }
        assertTrue(list.any { it.key == "thumb" })
    }

    @Test
    fun `addSupplementary rejects non-object attributes`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        assertFailsWith<IllegalStateException> {
            withRequest {
                service.addSupplementary(
                    MetadataSupplementaryInput(
                        metadataId = m.id,
                        key = "bad",
                        name = "Bad",
                        contentType = "image/png",
                        attributes = JsonArray(emptyList()),
                    )
                )
            }
        }
    }

    @Test
    fun `setSupplementaryUploaded by supplementary id`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        withRequest {
            service.addSupplementary(
                MetadataSupplementaryInput(metadataId = m.id, key = "k", name = "n", contentType = "image/png")
            )
        }
        // repository.setUploaded keys on (metadata_id, key) semantics via id lookup
        val supp = withRequest { service.getSupplementaryByMetadataAndKey(m.id, "k") } ?: error("missing")
        withRequest { service.setSupplementaryUploaded(supp.id, "image/jpeg", 99L) }
        val after = withRequest { service.getSupplementaryById(supp.id) }
        assertNotNull(after)
        assertEquals("image/jpeg", after.contentType)
    }

    @Test
    fun `setSupplementaryUploaded with null content type falls back`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        withRequest {
            service.addSupplementary(
                MetadataSupplementaryInput(metadataId = m.id, key = "k2", name = "n", contentType = "image/png")
            )
        }
        val supp = withRequest { service.getSupplementaryByMetadataAndKey(m.id, "k2") } ?: error("missing")
        withRequest { service.setSupplementaryUploaded(supp.id, null, 5L) }
        val after = withRequest { service.getSupplementaryById(supp.id) }
        assertNotNull(after)
        assertEquals("application/octet-stream", after.contentType)
    }

    @Test
    fun `setSupplementaryUploaded metadata overload updates and mismatched id fails`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        withRequest {
            service.addSupplementary(
                MetadataSupplementaryInput(metadataId = m.id, key = "k3", name = "n", contentType = "image/png")
            )
        }
        val supp = withRequest { service.getSupplementaryByMetadataAndKey(m.id, "k3") } ?: error("missing")
        withRequest { service.setSupplementaryUploaded(m, supp.id, 12L, "image/gif") }
        val after = withRequest { service.getSupplementaryById(supp.id) }
        assertNotNull(after)
        assertEquals("image/gif", after.contentType)

        // missing supplementary id -> silent no-op (early return branch)
        withRequest { service.setSupplementaryUploaded(m, UUID.random(), 1L, "x/y") }

        // mismatched metadata id -> require() throws
        val other = insertMetadata(name = "Other")
        assertFailsWith<IllegalArgumentException> {
            withRequest { service.setSupplementaryUploaded(other, supp.id, 1L, "x/y") }
        }
    }

    @Test
    fun `updateSupplementaryContent string overload`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        withRequest {
            service.addSupplementary(
                MetadataSupplementaryInput(metadataId = m.id, key = "c", name = "n", contentType = "text/plain")
            )
        }
        val supp = withRequest { service.getSupplementaryByMetadataAndKey(m.id, "c") } ?: error("missing")
        withRequest { service.updateSupplementaryContent(m, supp.id, "hello", "text/plain") }
        val after = withRequest { service.getSupplementaryById(supp.id) }
        assertNotNull(after)
        assertEquals(5L, after.contentLength)

        // missing id -> no-op
        withRequest { service.updateSupplementaryContent(m, UUID.random(), "x", "text/plain") }

        // mismatched metadata id -> require throws
        val other = insertMetadata(name = "Other")
        assertFailsWith<IllegalArgumentException> {
            withRequest { service.updateSupplementaryContent(other, supp.id, "x", "text/plain") }
        }
    }

    @Test
    fun `deleteSupplementary and detachSupplementary`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        withRequest {
            service.addSupplementary(MetadataSupplementaryInput(metadataId = m.id, key = "d", name = "n", contentType = "text/plain"))
            service.addSupplementary(MetadataSupplementaryInput(metadataId = m.id, key = "e", name = "n", contentType = "text/plain"))
        }
        val d = withRequest { service.getSupplementaryByMetadataAndKey(m.id, "d") } ?: error("missing")
        val e = withRequest { service.getSupplementaryByMetadataAndKey(m.id, "e") } ?: error("missing")

        withRequest { service.deleteSupplementary(m, d.id) }
        assertNull(withRequest { service.getSupplementaryById(d.id) })

        withRequest { service.detachSupplementary(m, e.id) }

        // deleteSupplementary missing id -> no-op
        withRequest { service.deleteSupplementary(m, UUID.random()) }
        // detach missing id -> no-op
        withRequest { service.detachSupplementary(m, UUID.random()) }
    }

    @Test
    fun `setSupplementaryUploaded by content type non-existing id errors`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        // The id-only overload keys on the supplementary's own id; a random id -> repo returns
        // no row -> service errors resolving the metadata.
        assertFailsWith<Exception> {
            withRequest { service.setSupplementaryUploaded(UUID.random(), "image/png", 1L) }
        }
    }

    // ── Relationships ────────────────────────────────────────────────────────

    @Test
    fun `addRelationship input overload and object overload`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val a = insertMetadata(name = "A")
        val b = insertMetadata(name = "B")
        val rel = withRequest {
            service.addRelationship(
                MetadataRelationshipInput(id1 = a.id, id2 = b.id, relationship = "related", attributes = JsonObject(emptyMap()))
            )
        }
        assertEquals(a.id, rel.metadataId1)
        assertEquals(b.id, rel.metadataId2)

        val list = withRequest { service.getRelationships(a.id) }
        assertTrue(list.any { it.metadataId2 == b.id })
    }

    @Test
    fun `addRelationship rejects non-object attributes`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val a = insertMetadata(name = "A")
        val b = insertMetadata(name = "B")
        assertFailsWith<IllegalStateException> {
            withRequest {
                service.addRelationship(
                    MetadataRelationship(metadataId1 = a.id, metadataId2 = b.id, relationship = "r", attributes = JsonArray(emptyList()))
                )
            }
        }
    }

    @Test
    fun `addRelationship errors when source metadata missing`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val b = insertMetadata(name = "B")
        assertFailsWith<IllegalStateException> {
            withRequest {
                service.addRelationship(
                    MetadataRelationship(metadataId1 = UUID.random(), metadataId2 = b.id, relationship = "r", attributes = JsonObject(emptyMap()))
                )
            }
        }
    }

    @Test
    fun `mergeAttributes on relationship merges and no-ops`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val a = insertMetadata(name = "A")
        val b = insertMetadata(name = "B")
        withRequest {
            service.addRelationship(
                MetadataRelationship(metadataId1 = a.id, metadataId2 = b.id, relationship = "rel", attributes = buildJsonObject { put("x", 1) })
            )
        }
        withRequest { service.mergeAttributes(a.id, b.id, "rel", buildJsonObject { put("y", 2) }) }
        // merge same -> no-op branch
        withRequest { service.mergeAttributes(a.id, b.id, "rel", buildJsonObject { put("y", 2) }) }

        assertFailsWith<IllegalStateException> {
            withRequest { service.mergeAttributes(a.id, b.id, "rel", JsonArray(emptyList())) }
        }
    }

    @Test
    fun `removeRelationship deletes the relationship`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val a = insertMetadata(name = "A")
        val b = insertMetadata(name = "B")
        withRequest {
            service.addRelationship(
                MetadataRelationship(metadataId1 = a.id, metadataId2 = b.id, relationship = "rel", attributes = JsonObject(emptyMap()))
            )
        }
        withRequest { service.removeRelationship(a.id, b.id, "rel") }
        val list = withRequest { service.getRelationships(a.id) }
        assertFalse(list.any { it.metadataId2 == b.id && it.relationship == "rel" })
    }

    // ── Parents delegation ───────────────────────────────────────────────────

    @Test
    fun `getParents delegates to collection service`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        coEvery { collectionService.getMetadataParents(m.id) } returns emptyList()
        coEvery { collectionService.getMetadataParents(m.id, 0, 10) } returns emptyList()
        assertTrue(withRequest { service.getParents(m.id) }.isEmpty())
        assertTrue(withRequest { service.getParents(m.id, 0, 10) }.isEmpty())
    }

    // ── Collaboration dirty markers ──────────────────────────────────────────

    @Test
    fun `markCollaboration dirty helpers only fire when doc or data exists`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata()
        // relaxed documentService/dataService return null docs by default -> no delegation
        withRequest { service.markCollaborationCollectionsDirty(m.id) }
        withRequest { service.markCollaborationRelationshipsDirty(m.id) }
        withRequest { service.markCollaborationAttributesDirty(m.id) }

        // errors when metadata missing
        assertFailsWith<IllegalStateException> {
            withRequest { service.markCollaborationCollectionsDirty(UUID.random()) }
        }
    }

    // ── add() ─────────────────────────────────────────────────────────────────

    @Test
    fun `add creates metadata with no parent`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val classifier = mockk<RecommendationContextClassifier>()
        provides<RecommendationContextClassifier>(singleton = true) { classifier }
        coEvery { classifier.classifyMetadata("text/plain", any()) } returns listOf("default")
        val created = withRequest {
            service.add(
                parent = null,
                collectionItemAttributes = null,
                input = MetadataInput(
                    name = "Fresh",
                    languageTag = "en",
                    contentType = "text/plain",
                )
            )
        }
        assertEquals("Fresh", created.name)
        val fetched = withRequest { service.getById(created.id) }
        assertNotNull(fetched)
        assertEquals("Fresh", fetched.name)
        assertEquals(listOf("default"), fetched.recommendationContexts)
        coVerify(exactly = 1) { classifier.classifyMetadata("text/plain", any()) }

        withRequest { service.setRecommendationContexts(created.id, listOf("images")) }
        assertEquals(listOf("images"), withRequest { service.getById(created.id) }?.recommendationContexts)
    }

    @Test
    fun `add with explicit slug uses provided slug`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = withRequest {
            service.add(
                parent = null,
                collectionItemAttributes = null,
                input = MetadataInput(
                    name = "Slugged",
                    languageTag = "en",
                    contentType = "text/plain",
                    slug = "custom-slug",
                )
            )
        }
        assertNotNull(created.id)
    }

    @Test
    fun `add with parent mismatch errors`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val parent = Collection(id = UUID.random(), name = "P", languageTag = "en", workflowStateId = "pending")
        assertFailsWith<IllegalStateException> {
            withRequest {
                service.add(
                    parent = parent,
                    collectionItemAttributes = null,
                    input = MetadataInput(
                        name = "Bad",
                        languageTag = "en",
                        contentType = "text/plain",
                        parentCollectionId = UUID.random(),
                    )
                )
            }
        }
    }

    // ── edit() ──────────────────────────────────────────────────────────────

    @Test
    fun `edit updates name and content type`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val m = insertMetadata(name = "Old")
        val edited = withRequest {
            service.edit(
                m.id,
                MetadataInput(name = "New", languageTag = "en", contentType = "text/markdown")
            )
        }
        assertEquals("New", edited.name)
        assertEquals("text/markdown", edited.contentType)
    }

    @Test
    fun `edit missing metadata throws`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        assertFailsWith<NoSuchElementException> {
            withRequest {
                service.edit(UUID.random(), MetadataInput(name = "X", languageTag = "en", contentType = "text/plain"))
            }
        }
    }
}
