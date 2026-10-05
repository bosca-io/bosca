package bosca.bml.sample

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end GraphQL test — the integration the unit-tested client ([bosca.bml.graphql.HttpGraphQLClient])
 * and the renderer never proved together. `dashboard.bml` is generated in-memory by the K2 plugin during
 * this module's real Gradle compile; its `<script server>` fetches via the in-scope client `ctx.gql` and the
 * fetched value must land in the rendered HTML. This is BML's whole data story ("all data via the Bosca
 * GraphQL API") exercised from server script → renderer → HTML, with a stub [GraphQLClient] injected via
 * [RenderContext] standing in for the Bosca endpoint.
 */
class DashboardPageRenderTest {

    /** A canned GraphQL client that records the query it received and returns a fixed JSON response. */
    private class StubGraphQLClient(private val responseJson: String) : GraphQLClient {
        var lastQuery: String? = null
            private set

        override suspend fun execute(
            query: String,
            variables: JsonObject?,
            operationName: String?,
            token: String?,
        ): JsonElement {
            lastQuery = query
            return Json.parseToJsonElement(responseJson)
        }
    }

    @Test
    fun `the server script queries ctx_gql and the fetched value renders into the page`() = runBlocking {
        val gql = StubGraphQLClient("""{"page":{"title":"Fetched Title"}}""")
        val ctx = RenderContext(gql = gql)
        bml.generated.DashboardPage.render(ctx)
        val html = ctx.toString()
        assertEquals("query { page { title } }", gql.lastQuery, "the script's query must reach the injected client")
        assertTrue("<h1>Fetched Title</h1>" in html, "fetched GraphQL data must render into the page:\n$html")
    }

    @Test
    fun `with no GraphQL client the page degrades to its fallback instead of crashing`() = runBlocking {
        // With no endpoint configured, ctx.gql is the fail-fast stub — executing it throws, and the
        // page's catch renders the fallback rather than crashing.
        val ctx = RenderContext() // no gql
        bml.generated.DashboardPage.render(ctx)
        assertTrue("<h1>no data</h1>" in ctx.toString(), "an endpoint-less render must fall back, not crash:\n$ctx")
    }
}
