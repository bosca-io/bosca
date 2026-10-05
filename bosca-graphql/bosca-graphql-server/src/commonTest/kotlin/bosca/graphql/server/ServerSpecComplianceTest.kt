package bosca.graphql.server

import bosca.graphql.parser.Parser
import bosca.graphql.schema.GraphQLSchema
import bosca.graphql.schema.SchemaException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Spec features added for full compliance on the server side: `@oneOf` input objects (schema validation +
 * exactly-one-non-null coercion + `__Type.isOneOf`), `@specifiedBy`/`specifiedByURL`, `__Schema.description`,
 * and the top-level response `extensions` (§3.5/§3.10/§4/§7).
 */
class ServerSpecComplianceTest {

    private val sdl = """
        "A demo schema."
        schema { query: Query }
        type Query { pick(input: Choice): String spec: URL }
        input Choice @oneOf { a: String b: Int }
        scalar URL @specifiedBy(url: "https://example.com/url")
    """.trimIndent()

    private fun executor() = GraphQLExecutor(
        ExecutableSchema.fromSdl(
            sdl,
            runtimeWiring {
                type("Query") {
                    field("pick") { ctx -> ctx.arg<Any>("input")?.toString() ?: "none" }
                    field("spec") { "u" }
                }
                scalar("URL", Scalars.coercings.getValue("String"))
            },
        ),
    )

    private suspend fun run(query: String, variables: JsonObject = JsonObject(emptyMap())) =
        executor().execute(Parser.parse(query), variables = variables)

    @Test
    fun `a oneOf input accepts exactly one non-null field as a literal`() = runTest {
        assertTrue(run("""{ pick(input: { a: "x" }) }""").errors.isEmpty())
        assertTrue(run("""{ pick(input: { a: "x", b: 1 }) }""").errors.any { "exactly one non-null" in it.message })
        assertTrue(run("{ pick(input: {}) }").errors.any { "exactly one non-null" in it.message })
        assertTrue(run("{ pick(input: { a: null }) }").errors.any { "exactly one non-null" in it.message })
    }

    @Test
    fun `a oneOf input via a variable enforces exactly one non-null field`() = runTest {
        assertTrue(run("query Q(\$c: Choice) { pick(input: \$c) }", json("""{"c":{"a":"x"}}""")).errors.isEmpty())
        assertTrue(run("query Q(\$c: Choice) { pick(input: \$c) }", json("""{"c":{"a":"x","b":1}}""")).errors.isNotEmpty())
    }

    @Test
    fun `a oneOf input object rejects non-null or defaulted fields at schema build`() {
        assertFailsWith<SchemaException> { GraphQLSchema.fromSdl("type Query { x: Int } input Bad @oneOf { a: String! }") }
        assertFailsWith<SchemaException> { GraphQLSchema.fromSdl("""type Query { x: Int } input Bad @oneOf { a: String = "x" }""") }
    }

    @Test
    fun `introspection reports isOneOf, specifiedByURL, and the schema description`() = runTest {
        val q = """
            {
              __schema { description }
              c: __type(name: "Choice") { isOneOf }
              q: __type(name: "Query") { isOneOf }
              u: __type(name: "URL") { specifiedByURL }
              s: __type(name: "String") { specifiedByURL }
            }
        """.trimIndent()
        val data = run(q).data as JsonObject
        assertEquals("A demo schema.", (data["__schema"] as JsonObject)["description"]!!.jsonPrimitive.content)
        assertEquals(true, (data["c"] as JsonObject)["isOneOf"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, (data["q"] as JsonObject)["isOneOf"]) // null for a non-input-object type
        assertEquals("https://example.com/url", (data["u"] as JsonObject)["specifiedByURL"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, (data["s"] as JsonObject)["specifiedByURL"])
    }

    @Test
    fun `the schema description is null when not declared`() = runTest {
        val plain = GraphQLExecutor(ExecutableSchema.fromSdl("type Query { x: Int }", runtimeWiring {}))
        val data = plain.execute(Parser.parse("{ __schema { description } }")).data as JsonObject
        assertEquals(JsonNull, (data["__schema"] as JsonObject)["description"])
    }

    @Test
    fun `the response carries top-level extensions only when present`() {
        val withExt = ExecutionResult(data = JsonNull, extensions = buildJsonObject { put("trace", JsonPrimitive(1)) }).toJson()
        assertTrue("extensions" in withExt.keys)
        assertTrue("extensions" !in ExecutionResult(data = JsonNull).toJson().keys)
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
}
