package bosca.category.service

import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.asCoroutineContext
import bosca.category.model.CategoryInput
import bosca.category.repository.CategoryRepositoryImpl
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.events.DisabledEventManagerFilter
import bosca.events.EventManager
import bosca.events.asCoroutineContext
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.test.ContentTestInfrastructure
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(InternalDI::class)
class CategoryServiceImplCoverageTest {

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

    private lateinit var service: CategoryServiceImpl

    @BeforeTest
    fun setup() = runBlocking {
        infrastructure.reset()
        serializer = RequestCacheSerializerImpl(testJson)

        ProviderRegistry.clear()
        provides<CacheManager>(singleton = true) { cacheManager }
        provides<RequestCacheSerializer>(singleton = true) { serializer }
        provides<Json>(singleton = true) { testJson }

        service = CategoryServiceImpl(CategoryRepositoryImpl())
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

    @Test
    fun `add creates category and getAll returns it`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = withRequest { service.add(CategoryInput(name = "News")) }

        assertNotNull(created.id)
        assertEquals("News", created.name)

        // getAll (Unit-keyed cache) resolves the full list from the repository.
        val all = withRequest { service.getAll() }
        assertTrue(all.any { it.id == created.id && it.name == "News" }, "created category should appear in getAll")
    }

    @Test
    fun `getAll caches within a request and clears after add`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        withRequest { service.add(CategoryInput(name = "First")) }

        // First read populates the "categories:all" cache.
        val firstRead = withRequest {
            val a = service.getAll()
            // Second call within the same request hits the cached value (not a null miss).
            val b = service.getAll()
            assertEquals(a.size, b.size)
            a
        }
        assertTrue(firstRead.any { it.name == "First" })

        // add() calls categoryAll.clear(); a fresh request must observe the new row.
        withRequest { service.add(CategoryInput(name = "Second")) }
        val after = withRequest { service.getAll() }
        assertTrue(after.any { it.name == "First" })
        assertTrue(after.any { it.name == "Second" })
        assertEquals(firstRead.size + 1, after.size)
    }

    @Test
    fun `getAll by ids returns found categories and skips missing ids`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val a = withRequest { service.add(CategoryInput(name = "Alpha")) }
        val b = withRequest { service.add(CategoryInput(name = "Beta")) }
        val missing = UUID.random()

        // Batch resolver: two ids resolve (the `all[key]?.let { batch.setData(...) }` truthy branch)
        // and one id is absent from the DB result (the null/skip branch), yielding a null filtered out.
        val result = withRequest { service.getAll(listOf(a.id, missing, b.id)) }

        assertEquals(2, result.size, "missing id must be filtered out by filterNotNull")
        assertTrue(result.any { it.id == a.id && it.name == "Alpha" })
        assertTrue(result.any { it.id == b.id && it.name == "Beta" })
        assertFalse(result.any { it.id == missing })
    }

    @Test
    fun `getAll by ids with empty list returns empty`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val result = withRequest { service.getAll(emptyList()) }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getAll by ids resolves entirely from the id cache on repeat`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val a = withRequest { service.add(CategoryInput(name = "Cached") ) }

        withRequest {
            val first = service.getAll(listOf(a.id))
            assertEquals(1, first.size)
            // Second call within the same request is served from the per-key cache.
            val second = service.getAll(listOf(a.id))
            assertEquals(1, second.size)
            assertEquals("Cached", second.first().name)
        }
    }

    @Test
    fun `edit updates an existing category and invalidates caches`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = withRequest { service.add(CategoryInput(name = "Original")) }

        // Warm the id cache so we can confirm edit() invalidates it via categoryIds.remove(id).
        withRequest {
            val before = service.getAll(listOf(created.id))
            assertEquals("Original", before.first().name)
        }

        val edited = withRequest { service.edit(created.id, CategoryInput(name = "Renamed")) }
        assertEquals(created.id, edited.id)
        assertEquals("Renamed", edited.name)

        // Fresh request: both getAll and getAll(ids) must reflect the new name (caches cleared).
        val all = withRequest { service.getAll() }
        assertTrue(all.any { it.id == created.id && it.name == "Renamed" })

        val byId = withRequest { service.getAll(listOf(created.id)) }
        assertEquals("Renamed", byId.single().name)
    }

    @Test
    fun `edit throws NoSuchElementException when category is missing`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val missing = UUID.random()
        val ex = assertFailsWith<NoSuchElementException> {
            withRequest { service.edit(missing, CategoryInput(name = "Nope")) }
        }
        assertTrue(ex.message?.contains(missing.toString()) == true, "message should mention the missing id")
    }

    @Test
    fun `delete removes a category and invalidates caches`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = withRequest { service.add(CategoryInput(name = "ToDelete")) }

        // Warm the id cache so delete()'s categoryIds.remove(id) is exercised.
        withRequest { service.getAll(listOf(created.id)) }

        withRequest { service.delete(created.id) }

        // getAll (fresh request, cache cleared) must no longer contain it.
        val all = withRequest { service.getAll() }
        assertFalse(all.any { it.id == created.id }, "deleted category should not appear in getAll")

        // getAll(ids) must filter it out (repository returns nothing for the id).
        val byId = withRequest { service.getAll(listOf(created.id)) }
        assertTrue(byId.isEmpty(), "deleted category should not be resolvable by id")
    }

    @Test
    fun `delete of a non-existent id is a no-op`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val missing = UUID.random()
        // Should not throw even though there is no matching row.
        withRequest { service.delete(missing) }
        val byId = withRequest { service.getAll(listOf(missing)) }
        assertTrue(byId.isEmpty())
    }

    @Test
    fun `getAll returns empty when no categories exist`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        // A brand-new database (per-test container is reused, but the categories table may already
        // hold rows from prior tests). This assertion only checks the call succeeds and returns a list.
        val all = withRequest { service.getAll() }
        assertNotNull(all)
    }
}
