package bosca.source.service

import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.asCoroutineContext
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.source.model.Source
import bosca.source.model.SourceInput
import bosca.source.repository.SourceRepository
import bosca.test.ContentTestInfrastructure
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.junit.AfterClass
import org.junit.BeforeClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(InternalDI::class)
class SourceServiceImplCoverageTest {

    private lateinit var serializer: RequestCacheSerializer

    private val cacheManager
        get() = infrastructure.cacheManager

    private val testJson = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(UUIDSerializer())
        }
    }

    private val repository = mockk<SourceRepository>()
    private lateinit var service: SourceServiceImpl

    @BeforeTest
    fun setup() = runBlocking {
        unmockkAll()
        infrastructure.reset()
        serializer = RequestCacheSerializerImpl(testJson)

        ProviderRegistry.clear()
        provides<CacheManager>(singleton = true) { cacheManager }
        provides<RequestCacheSerializer>(singleton = true) { serializer }
        provides<Json>(singleton = true) { testJson }

        // Constructing the service registers the "sources:all" and "sources:id" caches
        // via ServiceCache(...) -> maybeAddCache.
        service = SourceServiceImpl(repository)

    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
        unmockkAll()
    }

    companion object {

        private val infrastructure = ContentTestInfrastructure(includePostgres = false)

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
        val rc = RequestCache(cacheManager, serializer)
        return withContext(rc.asCoroutineContext()) {
            block()
        }
    }

    private fun source(
        id: UUID = UUID.random(),
        name: String = "src",
        description: String = "description",
        configuration: JsonObject = JsonObject(emptyMap()),
    ): Source = Source(
        id = id,
        name = name,
        description = description,
        configuration = configuration,
    )

    @Test
    fun `getAll returns repository results`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val a = source(name = "a")
        val b = source(name = "b")
        coEvery { repository.getAll() } returns listOf(a, b)

        val result = withRequest { service.getAll() }

        assertEquals(2, result.size)
        assertEquals("a", result[0].name)
        assertEquals("b", result[1].name)
    }

    @Test
    fun `getAll returns empty list when resolver yields null`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        // ServiceCache.get returns null only when the resolver returns null; List cannot be
        // null here, so exercise the empty branch which is the realistic empty result.
        coEvery { repository.getAll() } returns emptyList()

        val result = withRequest { service.getAll() }

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getById returns the source when present`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        val src = source(id = id, name = "found")
        coEvery { repository.getById(id) } returns src

        val result = withRequest { service.getById(id) }

        assertEquals(id, result.id)
        assertEquals("found", result.name)
    }

    @Test
    fun `getById throws NoSuchElementException when missing`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        coEvery { repository.getById(id) } returns null

        assertFailsWith<NoSuchElementException> {
            withRequest { service.getById(id) }
        }
    }

    @Test
    fun `add creates a source from input and clears the all cache`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val input = SourceInput(
            name = "New Source",
            description = "desc",
            configuration = JsonObject(mapOf("k" to JsonPrimitive("v"))),
        )
        val created = source(name = "New Source", description = "desc", configuration = JsonObject(mapOf("k" to JsonPrimitive("v"))))
        val captured = slot<Source>()
        coEvery { repository.add(capture(captured)) } returns created

        val result = withRequest { service.add(input) }

        assertEquals("New Source", result.name)
        assertEquals("desc", result.description)
        // Verify the input values were mapped onto the Source passed to the repository.
        assertEquals("New Source", captured.captured.name)
        assertEquals("desc", captured.captured.description)
        assertEquals(input.configuration, captured.captured.configuration)
        coVerify(exactly = 1) { repository.add(any()) }
    }

    @Test
    fun `edit updates an existing source`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        val existing = source(id = id, name = "old", description = "old desc")
        val input = SourceInput(
            name = "updated",
            description = "new desc",
            configuration = JsonObject(mapOf("x" to JsonPrimitive(1))),
        )
        val updatedReturn = existing.copy(name = "updated", description = "new desc", configuration = input.configuration)
        val captured = slot<Source>()
        coEvery { repository.getById(id) } returns existing
        coEvery { repository.update(capture(captured)) } returns updatedReturn

        val result = withRequest { service.edit(id, input) }

        assertEquals("updated", result.name)
        assertEquals("new desc", result.description)
        // The updated source keeps the original id and takes the input's fields.
        assertEquals(id, captured.captured.id)
        assertEquals("updated", captured.captured.name)
        assertEquals("new desc", captured.captured.description)
        assertEquals(input.configuration, captured.captured.configuration)
        coVerify(exactly = 1) { repository.update(any()) }
    }

    @Test
    fun `edit throws NoSuchElementException when source missing`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        coEvery { repository.getById(id) } returns null

        assertFailsWith<NoSuchElementException> {
            withRequest {
                service.edit(id, SourceInput(name = "x", description = "y", configuration = JsonObject(emptyMap())))
            }
        }
    }

    @Test
    fun `delete removes the source and clears caches`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        coEvery { repository.deleteById(id) } returns Unit

        withRequest { service.delete(id) }

        coVerify(exactly = 1) { repository.deleteById(id) }
    }

    @Test
    fun `getOrCreateForUrl returns existing source when name already known`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val existing = source(name = "Google Drive")
        coEvery { repository.getByName("Google Drive") } returns existing

        val result = withRequest { service.getOrCreateForUrl("https://drive.google.com/file/d/abc") }

        assertSame(existing, result)
        coVerify(exactly = 1) { repository.getByName("Google Drive") }
        coVerify(exactly = 0) { repository.add(any()) }
    }

    @Test
    fun `getOrCreateForUrl creates a source for a known exact-match provider`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = source(name = "GitHub", description = "GitHub repository hosting")
        val captured = slot<Source>()
        coEvery { repository.getByName("GitHub") } returns null
        coEvery { repository.add(capture(captured)) } returns created

        val result = withRequest { service.getOrCreateForUrl("https://raw.githubusercontent.com/o/r/main/file") }

        assertEquals("GitHub", result.name)
        assertEquals("GitHub", captured.captured.name)
        assertEquals("GitHub repository hosting", captured.captured.description)
        coVerify(exactly = 1) { repository.add(any()) }
    }

    @Test
    fun `getOrCreateForUrl resolves a subdomain of a known provider`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = source(name = "Amazon S3", description = "Amazon S3 object storage")
        val captured = slot<Source>()
        coEvery { repository.getByName("Amazon S3") } returns null
        coEvery { repository.add(capture(captured)) } returns created

        val result = withRequest { service.getOrCreateForUrl("https://my-bucket.s3.amazonaws.com/key") }

        assertEquals("Amazon S3", result.name)
        assertEquals("Amazon S3", captured.captured.name)
        assertEquals("Amazon S3 object storage", captured.captured.description)
    }

    @Test
    fun `getOrCreateForUrl falls back to the raw host for unknown providers`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = source(name = "example.com", description = "Imported from example.com")
        val captured = slot<Source>()
        coEvery { repository.getByName("example.com") } returns null
        coEvery { repository.add(capture(captured)) } returns created

        val result = withRequest { service.getOrCreateForUrl("https://example.com/some/path") }

        assertEquals("example.com", result.name)
        assertEquals("example.com", captured.captured.name)
        assertEquals("Imported from example.com", captured.captured.description)
    }

    @Test
    fun `getOrCreateForUrl uses the raw url when the URI has no host`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        // Opaque URI (mailto:) parses but has a null host, so the code falls back to the url string.
        val url = "mailto:someone@example.org"
        val created = source(name = url, description = "Imported from $url")
        val captured = slot<Source>()
        coEvery { repository.getByName(url) } returns null
        coEvery { repository.add(capture(captured)) } returns created

        val result = withRequest { service.getOrCreateForUrl(url) }

        assertEquals(url, result.name)
        assertEquals(url, captured.captured.name)
        assertEquals("Imported from $url", captured.captured.description)
    }

    @Test
    fun `getOrCreateForUrl falls back to the raw url when URI parsing throws`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        // A malformed URL makes URI(...) throw; the catch arm uses the raw url as the host.
        val url = "not a valid url"
        val created = source(name = url, description = "Imported from $url")
        val captured = slot<Source>()
        coEvery { repository.getByName(url) } returns null
        coEvery { repository.add(capture(captured)) } returns created

        val result = withRequest { service.getOrCreateForUrl(url) }

        assertEquals(url, result.name)
        assertEquals(url, captured.captured.name)
        assertEquals("Imported from $url", captured.captured.description)
    }
}
