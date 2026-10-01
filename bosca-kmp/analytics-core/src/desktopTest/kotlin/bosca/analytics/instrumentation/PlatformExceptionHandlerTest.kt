package bosca.analytics.instrumentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class PlatformExceptionHandlerTest {
    @Test
    fun `desktop handler captures forwards and restores uncaught exceptions`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val forwarded = mutableListOf<Throwable>()
        val captured = mutableListOf<Throwable>()
        val previous = Thread.UncaughtExceptionHandler { _, error -> forwarded += error }
        Thread.setDefaultUncaughtExceptionHandler(previous)
        val handler = PlatformExceptionHandler { captured += it }
        try {
            handler.install()
            val installed = Thread.getDefaultUncaughtExceptionHandler()
            assertNotSame(previous, installed)
            val error = IllegalStateException("failed")
            installed?.uncaughtException(Thread.currentThread(), error)
            assertEquals(listOf<Throwable>(error), captured)
            assertEquals(listOf<Throwable>(error), forwarded)

            handler.uninstall()
            assertSame(previous, Thread.getDefaultUncaughtExceptionHandler())
        } finally {
            handler.uninstall()
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun `desktop handler supports no previous handler and does not replace a successor`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val captured = mutableListOf<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler(null)
        val handler = PlatformExceptionHandler { captured += it }
        try {
            handler.install()
            val installed = Thread.getDefaultUncaughtExceptionHandler()
            val error = IllegalArgumentException("failed")
            installed?.uncaughtException(Thread.currentThread(), error)
            assertEquals(listOf<Throwable>(error), captured)

            val successor = Thread.UncaughtExceptionHandler { _, _ -> }
            Thread.setDefaultUncaughtExceptionHandler(successor)
            handler.uninstall()
            assertSame(successor, Thread.getDefaultUncaughtExceptionHandler())
        } finally {
            handler.uninstall()
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
