package bosca.di

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObjectProviderTest {

    @Test
    fun `ObjectProvider exists defaults to true`() {
        val provider = object : ObjectProvider<String> {
            override val type = String::class
            override suspend fun get() = "test"
        }
        assertTrue(provider.exists)
    }

    @Test
    fun `asProvider wraps value in ObjectProvider`() = runBlocking {
        val provider = "hello".asProvider()
        assertEquals(String::class, provider.type)
        assertEquals("hello", provider.get())
    }

    @Test
    fun `asProvider with integer`() = runBlocking {
        val provider = 42.asProvider()
        assertEquals(Int::class, provider.type)
        assertEquals(42, provider.get())
    }

    @Test
    fun `getBlocking retrieves value synchronously`() {
        val provider = "blocking".asProvider()
        assertEquals("blocking", provider.getBlocking())
    }

    @Test
    fun `asProvider always returns same value`() = runBlocking {
        val provider = "immutable".asProvider()
        assertEquals(provider.get(), provider.get())
    }
}
