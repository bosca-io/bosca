package bosca.server

import bosca.server.config.ApplicationConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoscaApplicationModuleTest {

    private fun createApp(): BoscaApplication {
        val config = mockk<ApplicationConfig>(relaxed = true)
        every { config.propertyOrNull(any()) } returns null
        return BoscaApplication(config)
    }

    private class CountingModule : BoscaApplicationModule {
        var installCount = 0
        override suspend fun install(application: BoscaApplication) {
            installCount++
        }
    }

    private class AnotherModule : BoscaApplicationModule {
        var installed = false
        override suspend fun install(application: BoscaApplication) {
            installed = true
        }
    }

    private class OrchestratorModule : BoscaApplicationModule {
        override suspend fun install(application: BoscaApplication) {
            application.install(CountingModule())
            application.install(AnotherModule())
        }
    }

    @Test
    fun `module installs and runs install once`() = runTest {
        val app = createApp()
        val module = CountingModule()
        app.install(module)
        assertEquals(1, module.installCount)
    }

    @Test
    fun `duplicate module install is skipped`() = runTest {
        val app = createApp()
        val first = CountingModule()
        val second = CountingModule()
        app.install(first)
        app.install(second)
        assertEquals(1, first.installCount)
        assertEquals(0, second.installCount)
    }

    @Test
    fun `different module types install independently`() = runTest {
        val app = createApp()
        val counting = CountingModule()
        val another = AnotherModule()
        app.install(counting)
        app.install(another)
        assertEquals(1, counting.installCount)
        assertTrue(another.installed)
    }

    @Test
    fun `installed module is recorded in modules map`() = runTest {
        val app = createApp()
        assertTrue(app.modules.isEmpty())
        val module = CountingModule()
        app.install(module)
        assertTrue(app.modules.containsKey(CountingModule::class))
        assertEquals(module, app.modules[CountingModule::class])
    }

    @Test
    fun `orchestrator module installs sub-modules`() = runTest {
        val app = createApp()
        app.install(OrchestratorModule())
        assertTrue(app.modules.containsKey(OrchestratorModule::class))
        assertTrue(app.modules.containsKey(CountingModule::class))
        assertTrue(app.modules.containsKey(AnotherModule::class))
        assertEquals(3, app.modules.size)
    }
}
