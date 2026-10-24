package bosca.di

import bosca.di.annotation.InternalDI
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

@OptIn(InternalDI::class)
class SingletonInitializationTest {

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `slow singleton initialization succeeds and caches the instance`() = runTest {
        var calls = 0
        provides<Any>(singleton = true) {
            calls++
            delay(30.seconds)
            Any()
        }

        val instance = provide<Any>()

        assertSame(instance, provide<Any>())
        assertEquals(1, calls)
    }

    @Test
    fun `named singleton times out after a minute and can retry initialization`() = runTest {
        var calls = 0
        provides<Any>("slow", singleton = true) {
            calls++
            delay(if (calls == 1) 61.seconds else 30.seconds)
            Any()
        }

        assertFailsWith<TimeoutCancellationException> {
            provide<Any>("slow")
        }

        val instance = provide<Any>("slow")

        assertSame(instance, provide<Any>("slow"))
        assertEquals(2, calls)
    }

    @Test
    fun `caller cancellation interrupts initialization and allows retry`() = runTest {
        var calls = 0
        provides<Any>(singleton = true) {
            calls++
            delay(30.seconds)
            Any()
        }

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(1.seconds) {
                provide<Any>()
            }
        }

        val instance = provide<Any>()

        assertSame(instance, provide<Any>())
        assertEquals(2, calls)
    }
}
