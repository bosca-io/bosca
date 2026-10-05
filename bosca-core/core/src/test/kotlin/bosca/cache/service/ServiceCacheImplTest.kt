@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.cache.service

import bosca.cache.Cache
import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.ServiceCache
import bosca.cache.asCoroutineContext
import bosca.cache.serializers.StringKeySerializer
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.graphql.Batch
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration

class ServiceCacheImplTest {

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `service cache delegates every operation through request context`() = runTest {
        val requestCache = mockk<RequestCache>()
        coEvery { requestCache.get<String, String>("items", "one", any()) } returns "resolved"
        coEvery { requestCache.put("items", "one", "stored") } returns Unit
        coEvery { requestCache.remove("items", "one", false) } returns Unit
        coEvery { requestCache.remove("items", "prefix", true) } returns Unit
        coEvery { requestCache.clear("items") } returns Unit
        coEvery { requestCache.getBatch<String, String>("items", any(), any()) } coAnswers {
            val batch = secondArg<Batch<String, String>>()
            batch.setData(batch.keys, batch.keys.map { "value-$it" })
        }
        val service = ServiceCacheImpl<String, String>("items", resolver = { "lookup-$it" })

        withContext(requestCache.asCoroutineContext()) {
            assertEquals("resolved", service.get("one"))
            service.put("one", "stored")
            assertEquals(listOf("value-a", "value-b"), service.getAll(listOf("a", "b")))
            val batch = Batch<String, String>(listOf("c"))
            service.addToBatch(batch)
            assertEquals(listOf("value-c"), batch.getResults())
            service.remove("one")
            service.remove("prefix", true)
            service.clear()
        }

        coVerify { requestCache.get<String, String>("items", "one", any()) }
        coVerify { requestCache.put("items", "one", "stored") }
        coVerify(exactly = 2) { requestCache.getBatch<String, String>("items", any(), any()) }
        coVerify { requestCache.remove("items", "one", false) }
        coVerify { requestCache.remove("items", "prefix", true) }
        coVerify { requestCache.clear("items") }
    }

    @Test
    fun `service cache requires a request cache context`() = runTest {
        val service = ServiceCacheImpl<String, String>("items", resolver = { null })
        assertFailsWith<IllegalStateException> { service.get("one") }
    }

    @Test
    fun `service cache factory registers backing cache and returns implementation`() {
        ProviderRegistry.clear()
        val manager = mockk<CacheManager>()
        val cache = mockk<Cache<String>>()
        coEvery { manager.maybeAddCache("factory", StringKeySerializer, any<Duration>()) } returns cache
        provides<CacheManager> { manager }

        val service = ServiceCache("factory", StringKeySerializer) { key: String -> "value-$key" }

        assertEquals("ServiceCacheImpl", service::class.simpleName)
        coVerify(exactly = 1) { manager.maybeAddCache("factory", StringKeySerializer, any<Duration>()) }
    }
}
