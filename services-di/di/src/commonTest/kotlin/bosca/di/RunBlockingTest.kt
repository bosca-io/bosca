package bosca.di

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RunBlockingTest {

    @Test
    fun `runBlocking returns value from non-suspending block`() {
        val result = runBlocking { 42 }
        assertEquals(42, result)
    }

    @Test
    fun `runBlocking returns string from block`() {
        val result = runBlocking { "hello" }
        assertEquals("hello", result)
    }

    @Test
    fun `runBlockingNoSuspend returns value from non-suspending block`() {
        val result = runBlockingNoSuspend { "test" }
        assertEquals("test", result)
    }

    @Test
    fun `runBlockingNoSuspend returns computed value`() {
        val result = runBlockingNoSuspend { 2 + 3 }
        assertEquals(5, result)
    }

    @Test
    fun `runBlocking propagates exceptions`() {
        assertFailsWith<IllegalStateException> {
            runBlocking { error("test error") }
        }
    }

    @Test
    fun `runBlockingNoSuspend propagates exceptions`() {
        assertFailsWith<IllegalStateException> {
            runBlockingNoSuspend { error("test error") }
        }
    }
}
