@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.http

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.pages.ErrorPage
import bosca.pages.NotFoundPage
import bosca.server.BoscaApplication
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.ServerResponse
import bosca.server.config.ApplicationConfig
import bosca.server.middleware.StatusPagesMiddleware
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs

class StatusPageModuleTest {

    private lateinit var application: BoscaApplication

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        application = BoscaApplication(ApplicationConfig.load("".byteInputStream()))
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    private fun call(
        path: String,
        contentType: ContentType? = null,
        status: HttpStatusCode? = null,
    ): ServerCall {
        val request = mockk<ServerRequest>()
        every { request.path } returns path
        every { request.contentType() } returns contentType
        val response = mockk<ServerResponse>(relaxed = true)
        every { response.isCommitted } returns false
        every { response.status() } returns status
        return ServerCall(request, response, Parameters.Empty, application)
    }

    @Test
    fun `fallback exception page maps illegal state to bad request and other errors to server error`() = runTest {
        application.install(StatusPageModule())
        val middleware = assertIs<StatusPagesMiddleware>(application.middleware.single())

        var call = call("/web")
        middleware.onException(call, IllegalStateException("bad input"))
        verify { call.response.respondText("bad input", ContentType.Text.Plain, HttpStatusCode.BadRequest) }

        call = call("/web")
        middleware.onException(call, RuntimeException("failure"))
        verify {
            call.response.respondText(
                HttpStatusCode.InternalServerError.description,
                ContentType.Text.Plain,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    @Test
    fun `custom error page handles HTML and skips API and JSON requests`() = runTest {
        val errorPage = mockk<ErrorPage>()
        coEvery { errorPage.execute(any()) } returns Unit
        provides<ErrorPage> { errorPage }
        application.install(StatusPageModule())
        val middleware = assertIs<StatusPagesMiddleware>(application.middleware.single())

        val html = call("/page")
        middleware.onException(html, RuntimeException("failure"))
        coVerify(exactly = 1) { errorPage.execute(html) }

        middleware.onException(call("/api/items"), RuntimeException("api"))
        middleware.onException(call("/page", ContentType.Application.Json), RuntimeException("json"))
        coVerify(exactly = 1) { errorPage.execute(any()) }
    }

    @Test
    fun `custom not found page handles HTML and skips API and JSON requests`() = runTest {
        val notFoundPage = mockk<NotFoundPage>()
        coEvery { notFoundPage.execute(any()) } returns Unit
        provides<NotFoundPage> { notFoundPage }
        application.install(StatusPageModule())
        val middleware = assertIs<StatusPagesMiddleware>(application.middleware.single())

        val html = call("/missing", status = HttpStatusCode.NotFound)
        middleware.afterCall(html)
        coVerify(exactly = 1) { notFoundPage.execute(html) }

        middleware.afterCall(call("/api/missing", status = HttpStatusCode.NotFound))
        middleware.afterCall(call("/missing", ContentType.Application.Json, HttpStatusCode.NotFound))
        coVerify(exactly = 1) { notFoundPage.execute(any()) }
    }
}
