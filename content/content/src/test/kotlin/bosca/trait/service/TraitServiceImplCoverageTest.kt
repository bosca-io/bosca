package bosca.trait.service

import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.asCoroutineContext
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.events.DisabledEventManagerFilter
import bosca.events.EventManager
import bosca.events.asCoroutineContext
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUIDSerializer
import bosca.test.ContentTestInfrastructure
import bosca.trait.model.TraitInput
import bosca.trait.repository.TraitRepositoryImpl
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.junit.AfterClass
import org.junit.BeforeClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(InternalDI::class)
class TraitServiceImplCoverageTest {

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

    private lateinit var service: TraitServiceImpl

    /**
     * A workflow id seeded by the core V10 migration (see workflows insert). The
     * traits.delete_workflow_id column carries an FK to workflows(id), so any non-null
     * value used in a test must reference a real, seeded workflow.
     */
    private val seededWorkflowId = "metadata.process"

    @BeforeTest
    fun setup() = runBlocking {
        infrastructure.reset()
        serializer = RequestCacheSerializerImpl(testJson)

        ProviderRegistry.clear()
        provides<CacheManager>(singleton = true) { cacheManager }
        provides<RequestCacheSerializer>(singleton = true) { serializer }
        provides<Json>(singleton = true) { testJson }

        // Constructed after CacheManager is provided — the ServiceCache factory calls
        // provide<CacheManager>() during TraitServiceImpl construction.
        service = TraitServiceImpl(TraitRepositoryImpl())
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

    /** Directly seeds a trait_content_types row (no repository method mutates it). */
    private suspend fun linkContentType(traitId: String, contentType: String) {
        val cm = ConnectionManager(connectionPool)
        withContext(cm.asCoroutineContext()) {
            cm.beginTransaction()
            cm.useStatement("insert into trait_content_types (trait_id, content_type) values (?, ?)") { stmt ->
                stmt.setString(1, traitId)
                stmt.setString(2, contentType)
                stmt.execute()
            }
            withContext(NonCancellable) {
                cm.commitTransaction()
            }
        }
        withContext(NonCancellable) {
            cm.release()
        }
    }

    private fun input(
        id: String,
        name: String = "Name $id",
        description: String = "Description $id",
        deleteWorkflowId: String? = null,
    ) = TraitInput(
        id = id,
        name = name,
        description = description,
        deleteWorkflowId = deleteWorkflowId,
        workflowIds = emptyList(),
        contentTypes = emptyList(),
    )

    @Test
    fun `add creates a trait and returns it`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val added = withRequest {
            service.add(input("t-add", name = "Added", description = "Desc", deleteWorkflowId = seededWorkflowId))
        }

        assertEquals("t-add", added.id)
        assertEquals("Added", added.name)
        assertEquals("Desc", added.description)
        assertEquals(seededWorkflowId, added.deleteWorkflowId)

        val fetched = withRequest { service.get("t-add") }
        assertNotNull(fetched)
        assertEquals("t-add", fetched.id)
    }

    @Test
    fun `add with null deleteWorkflowId persists null`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val added = withRequest {
            service.add(input("t-null", deleteWorkflowId = null))
        }
        assertNull(added.deleteWorkflowId)
    }

    @Test
    fun `get returns null when trait does not exist`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val fetched = withRequest { service.get("missing") }
        assertNull(fetched)
    }

    @Test
    fun `getAll returns all traits and clears cache after add`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        // Prime the traits:all cache while empty.
        val initial = withRequest { service.getAll() }
        assertTrue(initial.none { it.id == "t-all-1" })

        // add() clears the traits:all cache, so a subsequent getAll re-resolves.
        withRequest { service.add(input("t-all-1", name = "All One")) }
        withRequest { service.add(input("t-all-2", name = "All Two")) }

        val all = withRequest { service.getAll() }
        assertTrue(all.any { it.id == "t-all-1" })
        assertTrue(all.any { it.id == "t-all-2" })

        // Second call within a fresh request returns the same (cached) content.
        val allAgain = withRequest { service.getAll() }
        assertTrue(allAgain.any { it.id == "t-all-1" })
    }

    @Test
    fun `getAll by ids filters out missing entries`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        withRequest { service.add(input("t-ids-1")) }
        withRequest { service.add(input("t-ids-2")) }

        // Exercises the batch-resolver path of the traits id cache. filterNotNull()
        // drops any unresolved keys, so the result never contains nulls.
        val result = withRequest { service.getAll(listOf("t-ids-1", "t-ids-2", "does-not-exist")) }
        assertTrue(result.none { it.id == "does-not-exist" })
    }

    @Test
    fun `getAll by ids with empty list returns empty`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val result = withRequest { service.getAll(emptyList()) }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `edit updates the trait name and returns updated`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        withRequest { service.add(input("t-edit", name = "Original", description = "OrigDesc", deleteWorkflowId = seededWorkflowId)) }

        val updated = withRequest {
            service.edit(input("t-edit", name = "Renamed", description = "IgnoredDesc", deleteWorkflowId = seededWorkflowId))
        }
        assertEquals("Renamed", updated.name)

        val fetched = withRequest { service.get("t-edit") }
        assertNotNull(fetched)
        assertEquals("Renamed", fetched.name)
    }

    @Test
    fun `edit throws when trait not found`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        assertFailsWith<NoSuchElementException> {
            withRequest { service.edit(input("t-missing-edit", name = "Nope")) }
        }
    }

    @Test
    fun `delete removes the trait`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        withRequest { service.add(input("t-del")) }
        assertNotNull(withRequest { service.get("t-del") })

        withRequest { service.delete("t-del") }

        assertNull(withRequest { service.get("t-del") })
    }

    @Test
    fun `getTraitsByContentType returns linked traits`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        withRequest { service.add(input("t-ct-1", name = "CT One")) }
        withRequest { service.add(input("t-ct-2", name = "CT Two")) }
        linkContentType("t-ct-1", "application/vnd.bosca.article")
        linkContentType("t-ct-2", "application/vnd.bosca.other")

        val result = withRequest { service.getTraitsByContentType("application/vnd.bosca.article") }
        assertEquals(1, result.size)
        assertEquals("t-ct-1", result.first().id)

        val none = withRequest { service.getTraitsByContentType("application/vnd.bosca.none") }
        assertTrue(none.isEmpty())
    }

    @Test
    fun `getWorkflowIds is not yet implemented`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        assertFailsWith<NotImplementedError> {
            withRequest { service.getWorkflowIds("t-wf") }
        }
    }

    @Test
    fun `getContentTypes is not yet implemented`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        assertFailsWith<NotImplementedError> {
            withRequest { service.getContentTypes("t-ct") }
        }
    }
}
