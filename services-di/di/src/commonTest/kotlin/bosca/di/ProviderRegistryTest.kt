package bosca.di

import bosca.di.annotation.InternalDI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(InternalDI::class)
class ProviderRegistryTest {

    private class StringProvider(private val value: String) : ObjectProvider<String> {
        override val type = String::class
        override suspend fun get(): String = value
    }

    private class ConflictingStringProvider : ObjectProvider<String> {
        override val type = String::class
        override suspend fun get(): String = "conflict"
    }

    private fun cleanup() {
        ProviderRegistry.clear()
    }

    @Test
    fun `register and retrieve provider by type`() {
        cleanup()
        val provider = "hello".asProvider()
        ProviderRegistry.register(String::class, provider)
        val retrieved = ProviderRegistry.get(String::class)
        assertEquals(provider, retrieved)
        cleanup()
    }

    @Test
    fun `register and retrieve provider by name`() {
        cleanup()
        val provider = "named-value".asProvider()
        ProviderRegistry.register(String::class, provider, "myName")
        val retrieved = ProviderRegistry.get(String::class, "myName")
        assertNotNull(retrieved)
        cleanup()
    }

    @Test
    fun `registering the same source for the same type replaces the provider`() = runBlocking {
        cleanup()
        ProviderRegistry.register(String::class, StringProvider("first"))
        ProviderRegistry.register(String::class, StringProvider("second"))

        assertEquals("second", ProviderRegistry.get(String::class).get())
        cleanup()
    }

    @Test
    fun `registering the same source for the same named binding replaces the provider`() = runBlocking {
        cleanup()
        ProviderRegistry.register(String::class, StringProvider("first"), "named")
        ProviderRegistry.register(String::class, StringProvider("second"), "named")

        assertEquals("second", ProviderRegistry.get(String::class, "named").get())
        cleanup()
    }

    @Test
    fun `registering different sources for the same type throws`() {
        cleanup()
        ProviderRegistry.register(String::class, StringProvider("first"))

        val exception = assertFailsWith<DuplicateProviderException> {
            ProviderRegistry.register(String::class, ConflictingStringProvider())
        }

        assertTrue(exception.message!!.contains("StringProvider"))
        assertTrue(exception.message!!.contains("ConflictingStringProvider"))
        cleanup()
    }

    @Test
    fun `registering different sources for the same named binding throws`() {
        cleanup()
        ProviderRegistry.register(String::class, StringProvider("first"), "named")

        assertFailsWith<DuplicateProviderException> {
            ProviderRegistry.register(String::class, ConflictingStringProvider(), "named")
        }
        cleanup()
    }

    @Test
    fun `an explicit override replaces a different source for the same type`() = runBlocking {
        cleanup()
        ProviderRegistry.register(String::class, StringProvider("first"))
        ProviderRegistry.register(
            String::class,
            ConflictingStringProvider(),
            overrideExisting = true,
        )

        assertEquals("conflict", ProviderRegistry.get(String::class).get())
        cleanup()
    }

    @Test
    fun `an explicit override replaces a different source for the same named binding`() = runBlocking {
        cleanup()
        ProviderRegistry.register(String::class, StringProvider("first"), "named")
        ProviderRegistry.register(
            String::class,
            ConflictingStringProvider(),
            "named",
            overrideExisting = true,
        )

        assertEquals("conflict", ProviderRegistry.get(String::class, "named").get())
        cleanup()
    }

    @Test
    fun `the same source can provide different named bindings`() = runBlocking {
        cleanup()
        ProviderRegistry.register(String::class, StringProvider("first"), "first")
        ProviderRegistry.register(String::class, StringProvider("second"), "second")

        assertEquals("first", ProviderRegistry.get(String::class, "first").get())
        assertEquals("second", ProviderRegistry.get(String::class, "second").get())
        cleanup()
    }

    @Test
    fun `get returns MissingObjectProvider for unregistered type`() {
        cleanup()
        val provider = ProviderRegistry.get(Int::class)
        assertFalse(provider.exists)
        cleanup()
    }

    @Test
    fun `get returns MissingObjectProvider for unregistered named type`() {
        cleanup()
        val provider = ProviderRegistry.get(Int::class, "nonexistent")
        assertFalse(provider.exists)
        cleanup()
    }

    @Test
    fun `MissingObjectProvider throws MissingProviderException on get`() = runBlocking {
        cleanup()
        val provider = MissingObjectProvider(String::class, null)
        assertFailsWith<MissingProviderException> {
            provider.get()
        }
        cleanup()
    }

    @Test
    fun `MissingObjectProvider exists returns false`() {
        val provider = MissingObjectProvider(String::class, "test")
        assertFalse(provider.exists)
    }

    @Test
    fun `MissingProviderException contains type info`() {
        val exception = MissingProviderException(String::class, null)
        assertTrue(exception.message!!.contains("String"))
    }

    @Test
    fun `MissingProviderException contains name info`() {
        val exception = MissingProviderException(String::class, "myName")
        assertTrue(exception.message!!.contains("myName"))
    }

    @Test
    fun `clear removes all providers`() {
        cleanup()
        val provider = "test".asProvider()
        ProviderRegistry.register(String::class, provider)
        ProviderRegistry.register(String::class, provider, "named")
        ProviderRegistry.clear()
        assertFalse(ProviderRegistry.get(String::class).exists)
        assertFalse(ProviderRegistry.get(String::class, "named").exists)
    }

    @Test
    fun `register with ProviderRegistrar calls register`() {
        cleanup()
        var called = false
        val registrar = object : ProviderRegistrar {
            override fun register() {
                called = true
            }
        }
        ProviderRegistry.register(registrar)
        assertTrue(called)
        cleanup()
    }

    @Test
    fun `register multiple ProviderRegistrars`() {
        cleanup()
        var count = 0
        val registrar1 = object : ProviderRegistrar {
            override fun register() { count++ }
        }
        val registrar2 = object : ProviderRegistrar {
            override fun register() { count++ }
        }
        ProviderRegistry.register(registrar1, registrar2)
        assertEquals(2, count)
        cleanup()
    }

    @Test
    fun `findAll returns empty list for unregistered type`() {
        cleanup()
        val result = ProviderRegistry.findAll(Double::class)
        assertTrue(result.isEmpty())
        cleanup()
    }

    @Test
    fun `findAll returns type provider`() {
        cleanup()
        val provider = 42.asProvider()
        ProviderRegistry.register(Int::class, provider)
        val result = ProviderRegistry.findAll(Int::class)
        assertEquals(1, result.size)
        cleanup()
    }

    @Test
    fun `findAll returns both type and named providers`() {
        cleanup()
        val typeProvider = 1.asProvider()
        val namedProvider = 2.asProvider()
        ProviderRegistry.register(Int::class, typeProvider)
        ProviderRegistry.register(Int::class, namedProvider, "named")
        val result = ProviderRegistry.findAll(Int::class)
        assertEquals(2, result.size)
        cleanup()
    }

    @Test
    fun `findAllWithNames returns empty map for unregistered type`() {
        cleanup()
        val result = ProviderRegistry.findAllWithNames(Double::class)
        assertTrue(result.isEmpty())
        cleanup()
    }

    @Test
    fun `findAllWithNames includes type provider with empty string key`() {
        cleanup()
        val provider = 42.asProvider()
        ProviderRegistry.register(Int::class, provider)
        val result = ProviderRegistry.findAllWithNames(Int::class)
        assertTrue(result.containsKey(""))
        cleanup()
    }

    @Test
    fun `findAllWithNames includes named providers with correct name keys`() {
        cleanup()
        val provider = 42.asProvider()
        ProviderRegistry.register(Int::class, provider, "first")
        val result = ProviderRegistry.findAllWithNames(Int::class)
        assertTrue(result.containsKey("first"))
        cleanup()
    }

    @Test
    fun `singleton provider caches value`() = runBlocking {
        cleanup()
        var callCount = 0
        val provider = object : ObjectProvider<String> {
            override val type = String::class
            override suspend fun get(): String {
                callCount++
                return "singleton"
            }
        }
        ProviderRegistry.register(String::class, provider, singleton = true)
        val retrieved = ProviderRegistry.get(String::class)
        assertEquals("singleton", retrieved.get())
        assertEquals("singleton", retrieved.get())
        assertEquals(1, callCount)
        cleanup()
    }

    @Test
    fun `non-singleton provider creates new value each time`() = runBlocking {
        cleanup()
        var callCount = 0
        val provider = object : ObjectProvider<String> {
            override val type = String::class
            override suspend fun get(): String {
                callCount++
                return "value-$callCount"
            }
        }
        ProviderRegistry.register(String::class, provider, singleton = false)
        val retrieved = ProviderRegistry.get(String::class)
        retrieved.get()
        retrieved.get()
        assertEquals(2, callCount)
        cleanup()
    }

    @Test
    fun `singleton named provider caches value`() = runBlocking {
        cleanup()
        var callCount = 0
        val provider = object : ObjectProvider<String> {
            override val type = String::class
            override suspend fun get(): String {
                callCount++
                return "singleton-named"
            }
        }
        ProviderRegistry.register(String::class, provider, "myName", singleton = true)
        val retrieved = ProviderRegistry.get(String::class, "myName")
        assertEquals("singleton-named", retrieved.get())
        assertEquals("singleton-named", retrieved.get())
        assertEquals(1, callCount)
        cleanup()
    }
}
