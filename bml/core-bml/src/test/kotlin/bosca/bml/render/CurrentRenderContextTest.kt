package bosca.bml.render

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The ambient render context: `currentRenderContext()` reads the coroutine-context element that
 * `withRenderContext` installs — the alternative to threading `ctx` through every site function.
 */
class CurrentRenderContextTest {

    @Test
    fun `currentRenderContext returns the installed context, arbitrarily deep`() = runTest {
        val ctx = RenderContext(params = mapOf("id" to "42"))
        suspend fun deepLoader(): String = currentRenderContext().params.getValue("id")
        val result = withRenderContext(ctx) { deepLoader() }
        assertEquals("42", result)
    }

    @Test
    fun `outside a render it fails fast and names the fix`() = runTest {
        val e = assertFailsWith<IllegalStateException> { currentRenderContext() }
        assertTrue("withRenderContext" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `client() returns the ambient context's gql`() = runTest {
        val gql = object : bosca.bml.graphql.GraphQLClient {
            override suspend fun execute(
                query: String,
                variables: kotlinx.serialization.json.JsonObject?,
                operationName: String?,
                token: String?,
            ): kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonPrimitive("ok")
        }
        withRenderContext(RenderContext(gql = gql)) { assertSame(gql, client()) }
    }

    @Test
    fun `default argument constructor retains the published message-jar descriptor`() {
        val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        val constructor = RenderContext::class.java.getDeclaredConstructor(
            HtmlWriter::class.java,
            bosca.bml.graphql.GraphQLClient::class.java,
            String::class.java,
            Map::class.java,
            Map::class.java,
            Map::class.java,
            java.util.Locale::class.java,
            bosca.bml.i18n.MessageSource::class.java,
            Boolean::class.javaPrimitiveType,
            BmlSession::class.java,
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            marker,
        )
        assertEquals(13, constructor.parameterCount)
    }

    @Test
    fun `gql is never null - the endpoint-less stub fails fast on execute with the fix`() = runTest {
        val ctx = RenderContext() // no gql configured
        withRenderContext(ctx) {
            val e = assertFailsWith<IllegalStateException> { client().execute("query { x }") }
            assertTrue("graphqlEndpoint" in e.message.orEmpty(), e.message)
        }
    }

    @Test
    fun `shared render can reject feature flag evaluation before any transport`() {
        val ctx = RenderContext()
        ctx.disallowFeatureFlags()

        val error = assertFailsWith<IllegalStateException> { ctx.featureFlags }

        assertTrue("shared-cache shell" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `re-installing the same instance is a passthrough and nesting overrides`() = runTest {
        val outer = RenderContext(params = mapOf("who" to "outer"))
        val inner = RenderContext(params = mapOf("who" to "inner"))
        withRenderContext(outer) {
            assertSame(outer, currentRenderContext())
            // Same instance again — the nested layer still sees it.
            withRenderContext(outer) { assertSame(outer, currentRenderContext()) }
            // A different render (a sliver re-render inside a request) overrides…
            withRenderContext(inner) { assertSame(inner, currentRenderContext()) }
            // …and restores on exit.
            assertSame(outer, currentRenderContext())
        }
    }
}
