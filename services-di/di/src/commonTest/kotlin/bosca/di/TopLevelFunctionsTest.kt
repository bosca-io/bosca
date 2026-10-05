package bosca.di

import bosca.di.annotation.InternalDI
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@OptIn(InternalDI::class)
class TopLevelFunctionsTest {

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
    }

    @Test
    fun `provides registers and provide retrieves`() = runBlocking {
        provides { "from-provides" }
        val result: String = provide()
        assertEquals("from-provides", result)
    }

    @Test
    fun `provides with name registers and provide with name retrieves`() = runBlocking {
        provides("myName") { "named-value" }
        val result: String = provide("myName")
        assertEquals("named-value", result)
    }

    @Test
    fun `provideBlocking retrieves registered value`() {
        provides { "blocking-value" }
        val result: String = provideBlocking()
        assertEquals("blocking-value", result)
    }

    @Test
    fun `provideBlocking with name retrieves registered value`() {
        provides("named") { "named-blocking" }
        val result: String = provideBlocking("named")
        assertEquals("named-blocking", result)
    }

    @Test
    fun `provideLazy returns lazy that resolves to value`() {
        provides { "lazy-value" }
        val lazy: Lazy<String> = provideLazy()
        assertEquals("lazy-value", lazy.value)
    }

    @Test
    fun `provideLazy with name returns lazy that resolves`() {
        provides("named") { "lazy-named" }
        val lazy: Lazy<String> = provideLazy("named")
        assertEquals("lazy-named", lazy.value)
    }

    @Test
    fun `provideProvider returns ObjectProvider`() {
        provides { "provider-value" }
        val provider: ObjectProvider<String> = provideProvider()
        assertNotNull(provider)
    }

    @Test
    fun `provideProvider with name returns ObjectProvider`() {
        provides("named") { "provider-named" }
        val provider: ObjectProvider<String> = provideProvider("named")
        assertNotNull(provider)
    }

    @Test
    fun `providerMissing registers MissingObjectProvider`() {
        providerMissing<Double>()
        val provider = ProviderRegistry.get(Double::class)
        assertFalse(provider.exists)
    }

    @Test
    fun `provides with singleton true caches value`() = runBlocking {
        var callCount = 0
        provides(singleton = true) {
            callCount++
            "singleton-$callCount"
        }
        val first: String = provide()
        val second: String = provide()
        assertEquals(first, second)
        assertEquals(1, callCount)
    }

    @Test
    fun `provides with singleton false creates new value each time`() = runBlocking {
        var callCount = 0
        provides(singleton = false) {
            callCount++
            "value-$callCount"
        }
        provide<String>()
        provide<String>()
        assertEquals(2, callCount)
    }

    @Test
    fun `register with ProviderRegistrar delegates to registrar`() {
        var registered = false
        val registrar = object : ProviderRegistrar {
            override fun register() {
                registered = true
            }
        }
        register(registrar)
        assertEquals(true, registered)
    }

    @Test
    fun `provide throws for unregistered type`(): Unit = runBlocking {
        assertFailsWith<MissingProviderException> {
            provide<Long>()
        }
    }
}
