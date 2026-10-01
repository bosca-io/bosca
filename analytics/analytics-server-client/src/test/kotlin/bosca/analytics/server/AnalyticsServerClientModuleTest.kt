@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.analytics.server

import bosca.analytics.model.Events
import bosca.analytics.service.EventProcessingService
import bosca.di.ProviderRegistry
import bosca.di.provide
import bosca.di.provides
import bosca.observability.ErrorCapture
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.server.BoscaApplication
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import bosca.server.ServerResponse
import bosca.server.auth.CallAuthenticationContext
import bosca.server.config.ApplicationConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class AnalyticsServerClientModuleTest {
    private val applications = mutableListOf<BoscaApplication>()

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
    }

    @AfterTest
    fun tearDown() = runTest {
        applications.asReversed().forEach { it.shutdown() }
        applications.clear()
        ProviderRegistry.clear()
    }

    private fun application(iceberg: Boolean): BoscaApplication {
        val yaml = buildString {
            appendLine("analyticsServer:")
            appendLine("  appId: test-app")
            appendLine("  url: http://analytics.invalid")
            if (iceberg) appendLine("iceberg: {}")
        }
        return BoscaApplication(ApplicationConfig.load(yaml.byteInputStream())).also(applications::add)
    }

    private fun call(status: HttpStatusCode?, principal: AuthenticatedPrincipal? = null): ServerCall {
        val call = mockk<ServerCall>(relaxed = true)
        val response = mockk<ServerResponse>()
        every { response.status() } returns status
        every { call.response } returns response
        every { call.attributes } returns java.util.concurrent.ConcurrentHashMap<String, Any>().apply {
            put(AnalyticsMiddleware.ANALYTICS_SESSION_ID_ATTRIBUTE, "session-1")
        }
        val authenticationContext = CallAuthenticationContext()
        if (principal != null) {
            @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
            authenticationContext.principal("test", principal)
        }
        every { call.authenticationContext } returns authenticationContext
        return call
    }

    @Test
    fun `install selects the in-process client wires capture and flushes on shutdown`() = runTest {
        val processing = mockk<EventProcessingService>(relaxed = true)
        val batches = mutableListOf<Events>()
        coEvery { processing.queue(any(), capture(batches)) } returns Unit
        provides<EventProcessingService>(singleton = true) { processing }
        val application = application(iceberg = true)

        application.install(AnalyticsServerClientModule(headerAllowList = setOf("x-request-id")))

        assertIs<InProcessServerAnalyticsClient>(provide<ServerAnalyticsClient>())
        assertIs<AnalyticsMiddleware>(application.middleware.single())
        kotlin.test.assertSame<Any>(application.middleware.single(), application.handlerMiddleware.single())
        val capture = provide<ErrorCapture>()
        capture.capture(IllegalStateException("no call"), null, mapOf("source" to "unit"))
        capture.capture(IllegalStateException("bad request"), call(HttpStatusCode.BadRequest), emptyMap())
        val principalId = Uuid.random()
        capture.capture(
            IllegalStateException("server error"),
            call(
                HttpStatusCode.InternalServerError,
                AuthenticatedPrincipal(Principal(id = principalId), emptyList()),
            ),
            emptyMap(),
        )
        capture.capture(IllegalStateException("no status"), call(null), emptyMap())

        assertEquals(listOf(false, false, true, true), batches.map { it.events.single().error?.fatal })
        assertEquals(true, batches.first().events.single().error?.contextJson?.contains("\"source\":\"unit\""))
        assertEquals(true, batches[2].events.single().error?.contextJson?.contains(principalId.toString()))

        application.shutdown()
        applications.remove(application)
        coVerify(exactly = 1) { processing.flush() }
    }

    @Test
    fun `install selects and closes the HTTP client when iceberg is absent`() = runTest {
        val application = application(iceberg = false)

        application.install(AnalyticsServerClientModule())

        assertIs<HttpServerAnalyticsClient>(provide<ServerAnalyticsClient>())
        assertIs<AnalyticsMiddleware>(application.middleware.single())
        application.shutdown()
        applications.remove(application)
    }
}
