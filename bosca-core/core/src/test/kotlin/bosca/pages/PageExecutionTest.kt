@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.pages

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.security.service.AuthenticationContext
import bosca.security.service.AuthenticationProviders
import bosca.server.BoscaApplication
import bosca.server.Parameters
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.ServerResponse
import bosca.server.config.ApplicationConfig
import gg.jte.Content
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class TestPage(
    private val contextEnabled: Boolean,
    override val title: String? = null,
    private val action: suspend () -> Content?,
) : Page() {
    override val useContext: Boolean get() = contextEnabled

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): Content? = action()
}

class PageExecutionTest {

    private lateinit var localizationSource: LocalizationSource

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        val pool = mockk<ConnectionPool>()
        every { pool.connection() } answers { ConnectionManager(pool) }
        localizationSource = mockk()
        provides<ConnectionPool> { pool }
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        provides<AuthenticationProviders> { AuthenticationProviders(emptyArray()) }
        provides<LocalizationSource> { localizationSource }
    }

    @AfterTest
    fun teardown() {
        Page.defaultUseContext = true
        ProviderRegistry.clear()
    }

    private fun call(
        responses: MutableList<Any>,
        query: Parameters = Parameters.Empty,
        acceptLanguage: String? = null,
    ): ServerCall {
        val ctx = mockk<ChannelHandlerContext>(relaxed = true)
        val channel = mockk<Channel>(relaxed = true)
        val future = mockk<ChannelFuture>(relaxed = true)
        every { ctx.channel() } returns channel
        every { channel.isActive } returns true
        every { channel.isWritable } returns true
        every { ctx.alloc() } returns UnpooledByteBufAllocator.DEFAULT
        every { ctx.writeAndFlush(capture(responses)) } returns future
        every { ctx.write(any()) } returns future

        val request = mockk<ServerRequest>(relaxed = true)
        every { request.queryParameters } returns query
        every { request.acceptLanguage() } returns acceptLanguage
        val config = mockk<ApplicationConfig>(relaxed = true)
        every { config.propertyOrNull(any()) } returns null
        return ServerCall(request, ServerResponse(ctx), Parameters.Empty, BoscaApplication(config))
    }

    @Test
    fun `page loads requested localization and renders inside page context`() = runTest {
        val localization = MapLocalization(mapOf("greeting" to "Bonjour"))
        coEvery { localizationSource.get("fr-FR") } returns localization
        val content = Content { output ->
            output.writeContent(PageContext.localize("greeting").toString())
            output.writeContent("|")
            output.writeContent(PageContext.details.title)
        }
        val responses = mutableListOf<Any>()

        TestPage(contextEnabled = true, title = "Welcome") { content }
            .execute(call(responses, Parameters.fromSingleValueMap(mapOf("language" to "fr-FR")), "en-US"))

        coVerify(exactly = 1) { localizationSource.get("fr-FR") }
        val response = responses.single() as DefaultFullHttpResponse
        assertEquals(200, response.status().code())
        assertEquals("Bonjour|Welcome", response.content().toString(Charsets.UTF_8))
        assertFailsWith<IllegalStateException> { PageContext.details }
    }

    @Test
    fun `page uses accepted and default languages and can render without context`() = runTest {
        val localization = MapLocalization(emptyMap())
        coEvery { localizationSource.get(any()) } returns localization

        var responses = mutableListOf<Any>()
        TestPage(contextEnabled = true) { Content { it.writeContent("accepted") } }
            .execute(call(responses, acceptLanguage = "es-419"))
        coVerify { localizationSource.get("es-419") }

        responses = mutableListOf()
        TestPage(contextEnabled = true) { Content { it.writeContent("default") } }
            .execute(call(responses))
        coVerify { localizationSource.get("en-US") }

        responses = mutableListOf()
        TestPage(contextEnabled = false) { Content { it.writeContent("plain") } }
            .execute(call(responses))
        assertEquals("plain", (responses.single() as DefaultFullHttpResponse).content().toString(Charsets.UTF_8))
    }

    @Test
    fun `page skips response for null content and default context flag is mutable`() = runTest {
        Page.defaultUseContext = false
        val responses = mutableListOf<Any>()
        TestPage(contextEnabled = Page.defaultUseContext) { null }.execute(call(responses))
        assertEquals(emptyList(), responses)
    }

    @Test
    fun `page template exposes localization and details only inside its block`() {
        val config = mockk<ApplicationConfig>(relaxed = true)
        every { config.propertyOrNull(any()) } returns null
        val application = BoscaApplication(config)
        val result = PageTemplate.run {
            application.execute(
                MapLocalization(mapOf("key" to "value {0}")),
                PageDetails(title = "Title"),
            ) {
                "${PageContext.localize("key", "x")}|${PageContext.details.title}"
            }
        }

        assertEquals("value x|Title", result)
        assertFailsWith<IllegalStateException> { PageContext.details }
        assertFailsWith<IllegalStateException> { PageContext.setLocalization(MapLocalization(emptyMap())) }
    }
}
