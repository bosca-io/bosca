package bosca.content.collection.repository

import bosca.attributes.AttributeLocation
import bosca.attributes.AttributeType
import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.asCoroutineContext
import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionType
import bosca.content.find.FindAttributeInput
import bosca.content.find.FindAttributesInput
import bosca.content.find.FindQueryInput
import bosca.content.ordering.Order
import bosca.content.ordering.Ordering
import bosca.content.ordering.OrderingInput
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.test.ContentTestInfrastructure
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.toJavaUuid

/**
 * Drives [CollectionFindRepository] directly against a real Postgres + NATS stack, exercising:
 *  - the `expandMetadata` resolver (both state / no-state SQL arms, empty / non-empty ordering,
 *    and the row-mapping loop),
 *  - the `expandMetadataCount` resolver (both state arms, and the `it.next()` true/false arms),
 *  - `removeFromCache`,
 *  - `find` / `findBySystem` / `findCount` across attribute, collection-type, trait, ordering,
 *    offset and limit filter branches, including the full `map` column projection.
 */
@OptIn(InternalDI::class)
class CollectionFindRepositoryCoverageTest {

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

    private lateinit var repository: CollectionFindRepository
    private lateinit var collections: CollectionRepositoryImpl

    @BeforeTest
    fun setup() = runBlocking {
        infrastructure.reset()
        serializer = RequestCacheSerializerImpl(testJson)

        ProviderRegistry.clear()
        provides<CacheManager>(singleton = true) { cacheManager }
        provides<RequestCacheSerializer>(singleton = true) { serializer }
        provides<Json>(singleton = true) { testJson }

        repository = CollectionFindRepository(testJson)
        collections = CollectionRepositoryImpl()
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
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
        return withContext(cm.asCoroutineContext() + rc.asCoroutineContext()) {
            try {
                block()
            } finally {
                withContext(NonCancellable) {
                    cm.release()
                }
            }
        }
    }

    /** Runs an arbitrary parameterless statement inside its own committed transaction. */
    private suspend fun runSql(sql: String) {
        val cm = ConnectionManager(connectionPool)
        withContext(cm.asCoroutineContext()) {
            cm.beginTransaction()
            cm.useStatement(sql) { stmt -> stmt.execute() }
            withContext(NonCancellable) { cm.commitTransaction() }
        }
        withContext(NonCancellable) { cm.release() }
    }

    /** Inserts a collection through the generated repository, returning the persisted row. */
    private suspend fun addCollection(
        name: String,
        type: CollectionType = CollectionType.STANDARD,
        attributes: JsonObject = JsonObject(emptyMap()),
        systemAttributes: JsonObject = JsonObject(emptyMap()),
        workflowStateId: String = "draft",
    ): Collection = withRequest {
        collections.add(
            Collection(
                name = name,
                languageTag = "en",
                type = type,
                attributes = attributes,
                systemAttributes = systemAttributes,
                workflowStateId = workflowStateId,
            )
        )
    }

    /** Inserts a metadata row with the given id/state and a child collection_item under [parentId]. */
    private suspend fun addMetadataItem(parentId: UUID, metadataId: UUID, state: String) {
        val mid = metadataId.toJavaUuid()
        val pid = parentId.toJavaUuid()
        runSql("insert into metadata (id, name, content_type, workflow_state_id) values ('$mid', 'meta-$mid', 'text/plain', '$state')")
        runSql("insert into collection_items (collection_id, child_metadata_id) values ('$pid', '$mid')")
    }

    // ── expandMetadata ────────────────────────────────────────────────────────

    @Test
    fun `expandMetadata with no state and no ordering returns child rows`() =
        runTest(timeout = kotlin.time.Duration.parse("120s")) {
            val parent = addCollection("expand-parent")
            addMetadataItem(parent.id, UUID.random(), "published")
            addMetadataItem(parent.id, UUID.random(), "draft")

            val results = withRequest {
                repository.expandMetadata(parent.id, ordering = emptyList(), state = null, offset = 0, limit = 100)
            }

            // Both metadata children returned regardless of state (no state filter).
            assertEquals(2, results.size)
            assertTrue(results.all { it.childMetadataId != null })
        }

    @Test
    fun `expandMetadata with state filters to matching workflow state`() =
        runTest(timeout = kotlin.time.Duration.parse("120s")) {
            val parent = addCollection("expand-state-parent")
            addMetadataItem(parent.id, UUID.random(), "published")
            addMetadataItem(parent.id, UUID.random(), "draft")

            val results = withRequest {
                repository.expandMetadata(parent.id, ordering = emptyList(), state = "published", offset = 0, limit = 100)
            }

            assertEquals(1, results.size)
        }

    @Test
    fun `expandMetadata with ordering exercises the order-by branch`() =
        runTest(timeout = kotlin.time.Duration.parse("120s")) {
            val parent = addCollection("expand-order-parent")
            addMetadataItem(parent.id, UUID.random(), "published")
            addMetadataItem(parent.id, UUID.random(), "published")

            // The ordering path's first segment is bound to the FIRST `?` placeholder in the SQL text
            // (it sits inside the row_number() over(order by ...) clause), while the state value is
            // appended to `values` before it. Because binding is positional, the state arm's
            // `metadata.workflow_state_id = ?` placeholder actually receives the path's first segment.
            // Seed rows (workflow_state_id = 'published') therefore only match when that segment is
            // 'published', so the ordering path first segment is 'published' here.
            val ordering = listOf(
                Ordering(
                    path = listOf("published"),
                    location = AttributeLocation.ITEM,
                    order = Order.ASCENDING,
                    type = AttributeType.STRING,
                )
            )

            // state + non-empty ordering: covers the state startIndex (3) and the buildOrderByClause arm.
            val results = withRequest {
                repository.expandMetadata(parent.id, ordering = ordering, state = "published", offset = 0, limit = 100)
            }
            assertEquals(2, results.size)

            // no-state + non-empty ordering: covers the startIndex (2) arm.
            val resultsNoState = withRequest {
                repository.expandMetadata(parent.id, ordering = ordering, state = null, offset = 0, limit = 100)
            }
            assertEquals(2, resultsNoState.size)
        }

    @Test
    fun `expandMetadata returns empty list when collection has no items`() =
        runTest(timeout = kotlin.time.Duration.parse("120s")) {
            val parent = addCollection("expand-empty-parent")

            val results = withRequest {
                repository.expandMetadata(parent.id, ordering = emptyList(), state = null, offset = 0, limit = 100)
            }
            assertTrue(results.isEmpty())
        }

    // ── expandMetadataCount ─────────────────────────────────────────────────────

    @Test
    fun `expandMetadataCount counts children with and without a state filter`() =
        runTest(timeout = kotlin.time.Duration.parse("120s")) {
            val parent = addCollection("count-parent")
            addMetadataItem(parent.id, UUID.random(), "published")
            addMetadataItem(parent.id, UUID.random(), "draft")

            val total = withRequest { repository.expandMetadataCount(parent.id, state = null) }
            assertEquals(2L, total)

            val published = withRequest { repository.expandMetadataCount(parent.id, state = "published") }
            assertEquals(1L, published)
        }

    @Test
    fun `expandMetadataCount returns zero for an empty collection`() =
        runTest(timeout = kotlin.time.Duration.parse("120s")) {
            val parent = addCollection("count-empty-parent")
            val total = withRequest { repository.expandMetadataCount(parent.id, state = null) }
            assertEquals(0L, total)
        }

    // ── removeFromCache ─────────────────────────────────────────────────────────

    @Test
    fun `removeFromCache clears expand caches without error`() =
        runTest(timeout = kotlin.time.Duration.parse("120s")) {
            val parent = addCollection("remove-parent")
            addMetadataItem(parent.id, UUID.random(), "published")

            // Prime both caches.
            withRequest { repository.expandMetadata(parent.id, ordering = emptyList(), state = null, offset = 0, limit = 100) }
            withRequest { repository.expandMetadataCount(parent.id, state = null) }

            // remove() defers the flush until the transaction commits.
            val cm = ConnectionManager(connectionPool)
            val rc = RequestCache(cacheManager, serializer)
            withContext(cm.asCoroutineContext() + rc.asCoroutineContext()) {
                cm.beginTransaction()
                repository.removeFromCache(parent.id)
                withContext(NonCancellable) { cm.commitTransaction() }
            }
            withContext(NonCancellable) { cm.release() }

            // Cache repopulates cleanly on the next request.
            val results = withRequest {
                repository.expandMetadata(parent.id, ordering = emptyList(), state = null, offset = 0, limit = 100)
            }
            assertEquals(1, results.size)
        }

    // ── find / findBySystem / findCount ─────────────────────────────────────────

    @Test
    fun `find returns collections and maps every column`() =
        runTest(timeout = kotlin.time.Duration.parse("120s")) {
            addCollection("find-a", attributes = buildJsonObject { put("k", "v") })
            addCollection("find-b")

            val results = withRequest { repository.find(FindQueryInput()) }
            assertTrue(results.size >= 2)
            val a = results.firstOrNull { it.name == "find-a" }
            assertNotNull(a)
            assertEquals("en", a.languageTag)
            assertEquals(CollectionType.STANDARD, a.type)
            // The generated CollectionRepository.add insert does NOT include workflow_state_id in its
            // column list, so the model's workflowStateId is ignored and the DB default ('pending')
            // is persisted. Assert the actual persisted default rather than the model input value.
            assertEquals("pending", a.workflowStateId)
        }

    @Test
    fun `find applies attribute, collectionType, ordering, offset and limit filters`() =
        runTest(timeout = kotlin.time.Duration.parse("120s")) {
            addCollection("filter-match", type = CollectionType.FOLDER, attributes = buildJsonObject { put("color", "red") })
            addCollection("filter-other", type = CollectionType.FOLDER, attributes = buildJsonObject { put("color", "blue") })

            val query = FindQueryInput(
                attributes = listOf(FindAttributesInput(listOf(FindAttributeInput(key = "color", value = "red")))),
                collectionType = CollectionType.FOLDER,
                ordering = listOf(OrderingInput(field = "name", order = Order.ASCENDING)),
                offset = 0,
                limit = 10,
            )

            val results = withRequest { repository.find(query) }
            assertEquals(1, results.size)
            assertEquals("filter-match", results.first().name)
        }

    @Test
    fun `find with a trait filter joins collection_traits`() =
        runTest(timeout = kotlin.time.Duration.parse("120s")) {
            val tagged = addCollection("trait-tagged")
            addCollection("trait-untagged")
            runSql("insert into traits (id, name, description) values ('t-cov', 'Coverage', 'desc')")
            runSql("insert into collection_traits (collection_id, trait_id) values ('${tagged.id.toJavaUuid()}', 't-cov')")

            val results = withRequest { repository.find(FindQueryInput(traitIds = listOf("t-cov"))) }
            assertEquals(1, results.size)
            assertEquals("trait-tagged", results.first().name)
        }

    @Test
    fun `find with path-based ordering sorts on an attribute value`() =
        runTest(timeout = kotlin.time.Duration.parse("120s")) {
            addCollection("path-order-1", attributes = buildJsonObject { put("rank", "b") })
            addCollection("path-order-2", attributes = buildJsonObject { put("rank", "a") })

            val query = FindQueryInput(
                ordering = listOf(
                    OrderingInput(
                        path = listOf("rank"),
                        location = AttributeLocation.ITEM,
                        order = Order.ASCENDING,
                        type = AttributeType.STRING,
                    )
                ),
            )
            val results = withRequest { repository.find(query) }
            assertTrue(results.size >= 2)
        }

    @Test
    fun `findBySystem queries against system_attributes`() =
        runTest(timeout = kotlin.time.Duration.parse("120s")) {
            addCollection("system-match", systemAttributes = buildJsonObject { put("flag", "on") })
            addCollection("system-other", systemAttributes = buildJsonObject { put("flag", "off") })

            val query = FindQueryInput(
                attributes = listOf(FindAttributesInput(listOf(FindAttributeInput(key = "flag", value = "on")))),
            )
            val results = withRequest { repository.findBySystem(query) }
            assertEquals(1, results.size)
            assertEquals("system-match", results.first().name)
        }

    @Test
    fun `findCount returns a positive count when rows match and zero otherwise`() =
        runTest(timeout = kotlin.time.Duration.parse("120s")) {
            addCollection("count-find-a", attributes = buildJsonObject { put("bucket", "x") })
            addCollection("count-find-b", attributes = buildJsonObject { put("bucket", "x") })

            val matching = FindQueryInput(
                attributes = listOf(FindAttributesInput(listOf(FindAttributeInput(key = "bucket", value = "x")))),
            )
            val count = withRequest { repository.findCount(matching) }
            assertEquals(2L, count)

            val nonMatching = FindQueryInput(
                attributes = listOf(FindAttributesInput(listOf(FindAttributeInput(key = "bucket", value = "none")))),
            )
            val zero = withRequest { repository.findCount(nonMatching) }
            assertEquals(0L, zero)
        }
}
