package bosca.graphql.server

import bosca.graphql.parser.Parser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * the subscription execution strategy. A subscription's root field resolves to a source `Flow`; the
 * engine maps each event to an [ExecutionResult]. Driven under `runTest` so streaming is deterministic.
 */
class SubscriptionTest {

    private val sdl = """
        type Query { ping: String }
        type Subscription {
          counter: Int
          messageAdded: Message
          onChannel(name: String!): String
          flaky: Message
          mustHave: Int!
          notAStream: String
          blowsUp: Int
        }
        type Message { id: ID! text: String! }
    """.trimIndent()

    private fun message(id: String, text: String?) = buildMap<String, Any?> { put("id", id); put("text", text) }

    private fun executor() = GraphQLExecutor(
        ExecutableSchema.fromSdl(
            sdl,
            runtimeWiring {
                type("Subscription") {
                    field("counter") { flowOf(1, 2, 3) }
                    field("messageAdded") { flowOf(message("1", "hi"), message("2", "yo")) }
                    field("onChannel") { ctx -> flowOf("${ctx.arg<String>("name")}:a", "${ctx.arg<String>("name")}:b") }
                    field("flaky") { flowOf(message("1", "ok"), message("2", null)) } // 2nd event has null non-null text
                    field("mustHave") { flowOf<Int?>(1, null) } // 2nd event is null for a non-null root field
                    field("notAStream") { "I am not a Flow" }
                    field("blowsUp") { throw RuntimeException("cannot subscribe") }
                }
            },
        ),
    )

    private fun query(q: String) = Parser.parse(q)

    @Test
    fun `a subscription yields an ordered stream of results from the source flow`() = runTest {
        val results = executor().executeSubscription(query("subscription { counter }")).toList()
        assertEquals(listOf(1, 2, 3), results.map { (it.data as JsonObject)["counter"]!!.jsonPrimitive.int })
        assertTrue(results.all { it.errors.isEmpty() })
    }

    @Test
    fun `object events are completed against the field sub-selection`() = runTest {
        val results = executor().executeSubscription(query("subscription { messageAdded { id text } }")).toList()
        assertEquals(2, results.size)
        val first = (results[0].data as JsonObject)["messageAdded"]!!.jsonObject
        assertEquals("1", first["id"]!!.jsonPrimitive.content)
        assertEquals("hi", first["text"]!!.jsonPrimitive.content)
        assertEquals("yo", (results[1].data as JsonObject)["messageAdded"]!!.jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `subscription field arguments are coerced and passed`() = runTest {
        val results = executor().executeSubscription(query("""subscription { onChannel(name: "news") }""")).toList()
        assertEquals(listOf("news:a", "news:b"), results.map { (it.data as JsonObject)["onChannel"]!!.jsonPrimitive.content })
    }

    @Test
    fun `a per-event error nulls that event but the stream continues`() = runTest {
        val results = executor().executeSubscription(query("subscription { flaky { id text } }")).toList()
        assertEquals(2, results.size)
        // event 1 is fine
        assertEquals("ok", (results[0].data as JsonObject)["flaky"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(results[0].errors.isEmpty())
        // event 2: text is null for a non-null field → flaky bubbles to null, with an error, but the stream survived
        assertEquals(JsonNull, (results[1].data as JsonObject)["flaky"])
        assertEquals(listOf("flaky", "text"), results[1].errors.single().path)
    }

    @Test
    fun `cancelling the result stream tears down the source flow`() = runTest {
        var torndown = false
        val source: Flow<Int> = flow {
            emit(1); emit(2); emit(3); emit(4); emit(5)
        }.onCompletion { torndown = true }
        val executor = GraphQLExecutor(
            ExecutableSchema.fromSdl(sdl, runtimeWiring { type("Subscription") { field("counter") { source } } }),
        )
        val results = executor.executeSubscription(query("subscription { counter }")).take(2).toList()
        assertEquals(listOf(1, 2), results.map { (it.data as JsonObject)["counter"]!!.jsonPrimitive.int })
        assertTrue(torndown, "the source flow should be cancelled/closed when the collector stops early")
    }

    @Test
    fun `a non-null root field that errors for an event nulls that event's data`() = runTest {
        val results = executor().executeSubscription(query("subscription { mustHave }")).toList()
        assertEquals(2, results.size)
        assertEquals(1, (results[0].data as JsonObject)["mustHave"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, results[1].data) // non-null field, null event → whole data null
        assertTrue(results[1].errors.isNotEmpty())
    }

    @Test
    fun `__typename cannot be a subscription root field`() = runTest {
        val results = executor().executeSubscription(query("subscription { __typename }")).toList()
        assertTrue(results.single().errors.single().message.contains("__typename"))
    }

    @Test
    fun `an unknown root field is a subscribe error`() = runTest {
        val results = executor().executeSubscription(query("subscription { nope }")).toList()
        assertTrue(results.single().errors.single().message.contains("Cannot query field 'nope'"))
    }

    @Test
    fun `a missing required argument is a subscribe error`() = runTest {
        val results = executor().executeSubscription(query("subscription { onChannel }")).toList()
        assertTrue(results.single().errors.isNotEmpty())
    }

    @Test
    fun `executeSubscription rejects a non-subscription operation`() = runTest {
        val results = executor().executeSubscription(query("{ ping }")).toList()
        assertEquals(1, results.size)
        assertTrue(results.single().errors.single().message.contains("requires a subscription"))
    }

    @Test
    fun `a field that does not resolve to a Flow is a subscribe error`() = runTest {
        val results = executor().executeSubscription(query("subscription { notAStream }")).toList()
        assertTrue(results.single().errors.single().message.contains("must resolve to a Flow"))
    }

    @Test
    fun `a throwing subscribe resolver is a subscribe error`() = runTest {
        val results = executor().executeSubscription(query("subscription { blowsUp }")).toList()
        assertEquals("Internal server error", results.single().errors.single().message)
    }

    @Test
    fun `more than one root field is a subscribe error`() = runTest {
        val results = executor().executeSubscription(query("subscription { counter messageAdded { id } }")).toList()
        assertTrue(results.single().errors.single().message.contains("exactly one root field"))
    }

    @Test
    fun `a bad variable is a subscribe error`() = runTest {
        val results = executor().executeSubscription(query("subscription S(\$n: String!) { onChannel(name: \$n) }")).toList()
        assertTrue(results.single().errors.isNotEmpty())
    }

    @Test
    fun `a schema with no subscription root type is a subscribe error`() = runTest {
        val executor = GraphQLExecutor(ExecutableSchema.fromSdl("type Query { x: Int }", runtimeWiring { }))
        val results = executor.executeSubscription(query("subscription { x }")).toList()
        assertTrue(results.single().errors.single().message.contains("no subscription root type"))
    }

    @Test
    fun `an unknown operation name is a subscribe error`() = runTest {
        val results = executor().executeSubscription(query("subscription S { counter }"), operationName = "Other").toList()
        assertTrue(results.single().errors.single().message.contains("Other"))
    }
}
