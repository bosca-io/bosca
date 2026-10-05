@file:OptIn(InternalDI::class)

package bosca.server

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.observability.ErrorCapture
import bosca.server.config.ApplicationConfig
import io.mockk.every
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertSame

class BoscaApplicationErrorCaptureTest {

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    private fun createApplication(): BoscaApplication {
        val config = mockk<ApplicationConfig>(relaxed = true)
        every { config.propertyOrNull(any()) } returns null
        return BoscaApplication(config)
    }

    @Test
    fun `error capture resolves the interface provider`() {
        val application = createApplication()
        val capture = ErrorCapture { _, _, _ -> }
        provides<ErrorCapture>(singleton = true) { capture }

        assertSame(capture, application.errorCapture)
    }

    @Test
    fun `error capture falls back to noop when no provider is registered`() {
        assertSame(ErrorCapture.Noop, createApplication().errorCapture)
    }
}
