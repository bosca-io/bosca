package bosca.feeds.graphql

import bosca.feeds.configuration.FeedsSchemaRegistrar
import bosca.graphql.parser.Parser
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Module-level: the generated [FeedsSchemaRegistrar] finds and loads `feeds.graphqls`, the
 * SDL declares the namespace + types, and it parses. Catches missing/misnamed schema resources and
 * SDL syntax errors without the server DI container (the full cross-module merge — which resolves the
 * platform `UUID`/`JSON`/`DateTime` scalars and the `Query`/`Mutation` roots — runs at server boot).
 */
class FeedsSchemaTest {

    @Test
    fun `module sdl loads and parses with the feeds namespace and source types`() = runBlocking<Unit> {
        val sdl = FeedsSchemaRegistrar().load()

        assertTrue("type Feeds" in sdl, "Feeds type should be declared")
        assertTrue("feeds: Feeds!" in sdl, "Query.feeds field should be declared")
        assertTrue("feeds: FeedsMutation!" in sdl, "Mutation.feeds field should be declared")
        assertTrue("type FeedSource" in sdl, "FeedSource type should be declared")
        assertTrue("type FeedSourcesMutation" in sdl, "FeedSourcesMutation type should be declared")
        assertTrue("type FeedSourceMutation" in sdl, "FeedSourceMutation type should be declared")
        assertTrue("input FeedSourceInput" in sdl, "FeedSourceInput input should be declared")
        assertTrue("contextType: String" in sdl, "Recommendation-serving fields should accept a context type")

        // Syntactic validation of the module SDL; throws on a syntax error.
        Parser.parse(sdl)
    }
}
