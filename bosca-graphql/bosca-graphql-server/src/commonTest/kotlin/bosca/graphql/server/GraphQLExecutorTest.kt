package bosca.graphql.server

import bosca.graphql.parser.Parser
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * the execution engine — queries/mutations, aliases, nested selections, fragments, `@skip`/`@include`,
 * lists, abstract types, null-bubbling, and the concurrent-query / serial-mutation strategy.
 */
class GraphQLExecutorTest {

    private val sdl = """
        type Query {
          me: User
          user(id: ID!): User
          numbers: [Int!]
          maybe: [Int]
          node: Node
          pet: Pet
          fail: String
          failNonNull: String!
          nullName: User
          slowA: String
          slowB: String
          status: Status
          badEnum: Status
          badList: [Int!]
          badScalar: Int
          orphan: Node
        }
        type Mutation { log(msg: String!): Int }
        type User implements Node { id: ID! name: String! best: User }
        type Admin implements Node { id: ID! level: Int! }
        interface Node { id: ID! }
        union Pet = Cat
        type Cat { id: ID! meow: String! }
        enum Status { ACTIVE ARCHIVED }
    """.trimIndent()

    private fun executor() = GraphQLExecutor(
        ExecutableSchema.fromSdl(
            sdl,
            runtimeWiring {
                type("Query") {
                    field("me") { mapOf("id" to "1", "name" to "Ada") }
                    field("user") { ctx -> mapOf("id" to ctx.arg<String>("id"), "name" to "U") }
                    field("numbers") { listOf(1, 2, 3) }
                    field("maybe") { listOf(1, null, 3) }
                    field("node") { mapOf("__typename" to "User", "id" to "9", "name" to "Nine") }
                    field("pet") { mapOf("__typename" to "Cat", "id" to "c", "meow" to "mrow") }
                    field("fail") { throw RuntimeException("boom") }
                    field("failNonNull") { throw RuntimeException("boom2") }
                    field("nullName") { mapOf("id" to "1") } // no "name" → default resolver yields null for String!
                    field("slowA") { delay(100); "a" }
                    field("slowB") { delay(100); "b" }
                    field("status") { "ACTIVE" }
                    field("badEnum") { "NOPE" }
                    field("badList") { "not-a-list" }
                    field("badScalar") { "not-an-int" }
                    field("orphan") { mapOf("id" to "x") } // a Node value with no __typename → unresolvable
                }
                type("Pet") { resolveType { (it as Map<*, *>)["__typename"] as String? } }
                // Node intentionally left to the default __typename resolver
            },
        ),
    )

    private fun query(q: String) = Parser.parse(q)

    @Test
    fun `executes a query with aliases and nested selections`() = runTest {
        val result = executor().execute(query("{ a: me { id name } }"))
        assertEquals(buildJsonObject { put("a", buildJsonObject { put("id", "1"); put("name", "Ada") }) }, result.data)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `passes coerced arguments to resolvers`() = runTest {
        val result = executor().execute(query("{ user(id: \"7\") { id } }"))
        assertEquals("7", (result.data as JsonObject)["user"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `__typename, named fragments, and inline fragments resolve`() = runTest {
        val result = executor().execute(
            query("{ me { __typename ...F } node { ... on User { name } } pet { ... on Cat { meow } } }\nfragment F on User { id }"),
        )
        val data = result.data as JsonObject
        assertEquals("User", data["me"]!!.jsonObject["__typename"]!!.jsonPrimitive.content)
        assertEquals("1", data["me"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("Nine", data["node"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("mrow", data["pet"]!!.jsonObject["meow"]!!.jsonPrimitive.content)
    }

    @Test
    fun `applies skip and include directives, including via variables`() = runTest {
        val r1 = executor().execute(query("{ me { id name @skip(if: true) } }"))
        assertEquals(setOf("id"), (r1.data as JsonObject)["me"]!!.jsonObject.keys)

        val r2 = executor().execute(query("{ me { id name @include(if: false) } }"))
        assertEquals(setOf("id"), (r2.data as JsonObject)["me"]!!.jsonObject.keys)

        val r3 = executor().execute(
            query("query Q(\$s: Boolean!) { me { id name @skip(if: \$s) } }"),
            variables = Json.parseToJsonElement("""{"s":true}""").jsonObject,
        )
        assertEquals(setOf("id"), (r3.data as JsonObject)["me"]!!.jsonObject.keys)
    }

    @Test
    fun `completes lists, preserving nulls in a nullable-element list`() = runTest {
        assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2), JsonPrimitive(3))), (executor().execute(query("{ numbers }")).data as JsonObject)["numbers"])
        assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonNull, JsonPrimitive(3))), (executor().execute(query("{ maybe }")).data as JsonObject)["maybe"])
    }

    @Test
    fun `JSON scalar serializes legacy Kotlin maps returned by resolvers`() = runTest {
        val executor = GraphQLExecutor(
            ExecutableSchema.fromSdl(
                "scalar JSON\ntype Query { legacyJson: JSON }",
                runtimeWiring {
                    scalar("JSON", ExtendedScalars.Json)
                    type("Query") {
                        field("legacyJson") {
                            mapOf(
                                "type" to "Study",
                                "items" to listOf(1, null, mapOf("visible" to true)),
                            )
                        }
                    }
                },
            ),
        )
        val result = executor.execute(query("{ legacyJson }"))
        assertTrue(result.errors.isEmpty())
        assertEquals(
            Json.parseToJsonElement("""{"type":"Study","items":[1,null,{"visible":true}]}"""),
            (result.data as JsonObject)["legacyJson"],
        )
    }

    @Test
    fun `a resolver exception nulls a nullable field and records an error`() = runTest {
        val result = executor().execute(query("{ fail }"))
        assertEquals(JsonNull, (result.data as JsonObject)["fail"])
        assertEquals(1, result.errors.size)
        assertEquals("Internal server error", result.errors.single().message)
    }

    @Test
    fun `null-bubbling — a non-null child error nulls the nearest nullable ancestor`() = runTest {
        // nullName.name is String! but resolves to null → User can't be built → nullName (nullable) becomes null
        val result = executor().execute(query("{ nullName { name } }"))
        assertEquals(JsonNull, (result.data as JsonObject)["nullName"])
        assertEquals(listOf("nullName", "name"), result.errors.single().path)
    }

    @Test
    fun `null-bubbling — a non-null root field error nulls the whole data`() = runTest {
        val result = executor().execute(query("{ failNonNull }"))
        assertEquals(JsonNull, result.data)
        assertEquals(listOf("failNonNull"), result.errors.single().path)
    }

    @Test
    fun `an unknown operation name is a request error with no data`() = runTest {
        val result = executor().execute(query("query A { me { id } }"), operationName = "Nope")
        assertEquals(null, result.data)
        assertTrue(result.errors.single().message.contains("Nope"))
    }

    @Test
    fun `query fields resolve concurrently`() = runTest {
        val result = executor().execute(query("{ slowA slowB }"))
        assertEquals(100, testScheduler.currentTime) // concurrent: max(100,100), not 200
        assertEquals("a", (result.data as JsonObject)["slowA"]!!.jsonPrimitive.content)
        assertEquals("b", (result.data as JsonObject)["slowB"]!!.jsonPrimitive.content)
    }

    @Test
    fun `completes enum output and rejects an invalid enum value`() = runTest {
        assertEquals("ACTIVE", (executor().execute(query("{ status }")).data as JsonObject)["status"]!!.jsonPrimitive.content)
        val bad = executor().execute(query("{ badEnum }"))
        assertEquals(JsonNull, (bad.data as JsonObject)["badEnum"])
        assertTrue(bad.errors.single().message.contains("Status"))
        assertTrue(!bad.errors.single().message.contains("NOPE"))
    }

    @Test
    fun `field errors — list type mismatch, scalar serialize failure, unresolvable abstract type`() = runTest {
        val list = executor().execute(query("{ badList }"))
        assertEquals(JsonNull, (list.data as JsonObject)["badList"])
        assertTrue(list.errors.single().message.contains("list"))

        val scalar = executor().execute(query("{ badScalar }"))
        assertEquals(JsonNull, (scalar.data as JsonObject)["badScalar"])

        val orphan = executor().execute(query("{ orphan { id } }"))
        assertEquals(JsonNull, (orphan.data as JsonObject)["orphan"])
        assertTrue(orphan.errors.single().message.contains("resolve"))
    }

    @Test
    fun `argument coercion failure during execution nulls the field`() = runTest {
        val result = executor().execute(query("{ user { id } }")) // user(id: ID!) missing the required arg
        assertEquals(JsonNull, (result.data as JsonObject)["user"])
        assertTrue(result.errors.single().path == listOf("user"))
    }

    @Test
    fun `an unknown field is reported defensively and nulled`() = runTest {
        val result = executor().execute(query("{ me { id } bogus }"))
        assertEquals(JsonNull, (result.data as JsonObject)["bogus"])
        assertTrue(result.errors.single().message.contains("bogus"))
    }

    @Test
    fun `a non-boolean skip if is ignored`() = runTest {
        val result = executor().execute(query("{ me { id name @skip(if: 5) } }"))
        assertEquals(setOf("id", "name"), (result.data as JsonObject)["me"]!!.jsonObject.keys)
    }

    @Test
    fun `a bad variable is a request error`() = runTest {
        val result = executor().execute(query("query Q(\$x: Int!) { me { id } }")) // required variable not provided
        assertEquals(null, result.data)
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun `a subscription is rejected by execute`() = runTest {
        val result = executor().execute(query("subscription S { me { id } }"))
        assertEquals(null, result.data)
        assertTrue(result.errors.single().message.contains("Subscription"))
    }

    @Test
    fun `a mutation against a schema with no mutation root is a request error`() = runTest {
        val executor = GraphQLExecutor(ExecutableSchema.fromSdl("type Query { x: Int }", runtimeWiring { }))
        val result = executor.execute(query("mutation { x }"))
        assertEquals(null, result.data)
        assertTrue(result.errors.single().message.contains("mutation root"))
    }

    @Test
    fun `mutation top-level fields execute serially in order`() = runTest {
        val order = mutableListOf<String>()
        val executor = GraphQLExecutor(
            ExecutableSchema.fromSdl(
                sdl,
                runtimeWiring {
                    type("Mutation") {
                        field("log") { ctx -> order.add(ctx.arg<String>("msg")!!); delay(100); order.size }
                    }
                },
            ),
        )
        val result = executor.execute(query("mutation { a: log(msg: \"1\") b: log(msg: \"2\") c: log(msg: \"3\") }"))
        assertEquals(300, testScheduler.currentTime) // serial: 100+100+100
        assertEquals(listOf("1", "2", "3"), order)
        // deterministic, ordered response keys
        assertEquals(listOf("a", "b", "c"), (result.data as JsonObject).keys.toList())
    }
}
