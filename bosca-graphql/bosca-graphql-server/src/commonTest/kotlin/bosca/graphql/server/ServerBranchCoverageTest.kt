package bosca.graphql.server

import bosca.graphql.language.Document
import bosca.graphql.language.FragmentDefinition
import bosca.graphql.language.OperationDefinition
import bosca.graphql.parser.Parser
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Targets the engine's remaining reachable branches: nullable-list element bubbling, inline-fragment skip/non-match,
 * directive `if` edge cases (absent arg, non-boolean variable), `__type` for a missing name, deprecated field
 * arguments, query-complexity directive/fragment edges, and the facade's error + empty-document paths.
 */
class ServerBranchCoverageTest {

    private val sdl = """
        type Query {
          me: User
          pet: Pet
          users: [User]
          withDep(a: Int @deprecated(reason: "old"), b: Int): Int
          search(filter: Filter): Status
        }
        type User implements Node { id: ID! name: String! }
        interface Node { id: ID! }
        union Pet = Cat | Dog
        type Cat { id: ID! meow: String! }
        type Dog { id: ID! bark: String! }
        enum Status { ACTIVE ARCHIVED @deprecated(reason: "gone") }
        input Filter { term: String old: Int @deprecated(reason: "x") }
    """.trimIndent()

    private fun executable() = ExecutableSchema.fromSdl(
        sdl,
        runtimeWiring {
            type("Query") {
                field("me") { mapOf("id" to "1", "name" to "Ada") }
                field("pet") { mapOf("__typename" to "Cat", "id" to "c", "meow" to "mrow") }
                field("users") { listOf(mapOf("id" to "1", "name" to "A"), mapOf("id" to "2")) } // 2nd lacks non-null name
                field("withDep") { 5 }
                field("search") { "ACTIVE" }
            }
            type("Pet") { resolveType { (it as Map<*, *>)["__typename"] as String? } }
        },
    )

    private fun executor() = GraphQLExecutor(executable())
    private fun query(q: String) = Parser.parse(q)

    @Test
    fun `a bubbling element in a nullable-element list becomes null`() = runTest {
        val users = (executor().execute(query("{ users { id name } }")).data as JsonObject)["users"] as JsonArray
        assertEquals(2, users.size)
        assertTrue(users[0] is JsonObject)
        assertEquals(JsonNull, users[1]) // the element's non-null `name` errored → element nulls (not the whole list)
    }

    @Test
    fun `an inline fragment is dropped by skip and by a non-matching type condition`() = runTest {
        // `... on Cat @skip(if: true)` → shouldInclude false; `... on Dog` on a Cat value → applies false.
        val pet = (executor().execute(query("{ pet { ... on Cat @skip(if: true) { meow } ... on Dog { bark } } }")).data as JsonObject)["pet"] as JsonObject
        assertTrue(pet.keys.isEmpty(), pet.toString())
    }

    @Test
    fun `a directive whose if argument is absent is ignored`() = runTest {
        val me = (executor().execute(query("{ me @skip { id } }")).data as JsonObject)["me"]
        assertTrue(me is JsonObject) // @skip with no `if` → treated as absent → field kept
    }

    @Test
    fun `a directive if bound to a non-boolean variable is ignored`() = runTest {
        val result = executor().execute(
            query("query Q(\$s: Int!) { me @skip(if: \$s) { id } }"),
            variables = buildJsonObject { put("s", JsonPrimitive(5)) },
        )
        assertTrue((result.data as JsonObject)["me"] is JsonObject) // 5 as? Boolean == null → not skipped
    }

    @Test
    fun `__type resolves a present type and yields null for a missing one`() = runTest {
        val data = executor().execute(query("""{ ok: __type(name: "User") { name } missing: __type(name: "Nope") { name } }""")).data as JsonObject
        assertEquals("User", (data["ok"] as JsonObject)["name"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, data["missing"]) // schema.type("Nope") == null → __type returns null
    }

    @Test
    fun `a deprecated field argument is hidden unless includeDeprecated is true`() = runTest {
        suspend fun argsOf(includeDeprecated: Boolean): Set<String> {
            val q = """{ __type(name: "Query") { fields { name args(includeDeprecated: $includeDeprecated) { name } } } }"""
            val fields = ((executor().execute(query(q)).data as JsonObject)["__type"] as JsonObject)["fields"] as JsonArray
            val withDep = fields.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "withDep" }
            return (withDep["args"] as JsonArray).map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        }
        assertEquals(setOf("b"), argsOf(includeDeprecated = false)) // `a` is @deprecated → filtered out
        assertEquals(setOf("a", "b"), argsOf(includeDeprecated = true)) // both kept
    }

    @Test
    fun `every introspection meta-field resolves, including descriptions and deprecation on members`() = runTest {
        val q = """
            {
              __schema { description directives { name description } }
              q: __type(name: "Query") {
                specifiedByURL
                fields { name args { name description isDeprecated deprecationReason } }
              }
              e: __type(name: "Status") { enumValues(includeDeprecated: true) { name description isDeprecated deprecationReason } }
              i: __type(name: "Filter") { inputFields(includeDeprecated: true) { name description isDeprecated deprecationReason } }
            }
        """.trimIndent()
        val data = executor().execute(query(q)).data as JsonObject
        assertEquals(JsonNull, (data["__schema"] as JsonObject)["description"]) // null-returning meta resolver runs
        assertEquals(JsonNull, (data["q"] as JsonObject)["specifiedByURL"])
        // the deprecated enum value reports its reason
        val statusValues = ((data["e"] as JsonObject)["enumValues"] as JsonArray).map { it.jsonObject }
        val archived = statusValues.first { it["name"]!!.jsonPrimitive.content == "ARCHIVED" }
        assertEquals(true, archived["isDeprecated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("gone", archived["deprecationReason"]!!.jsonPrimitive.content)
        // the deprecated input field reports its reason
        val inputFields = ((data["i"] as JsonObject)["inputFields"] as JsonArray).map { it.jsonObject }
        assertEquals("x", inputFields.first { it["name"]!!.jsonPrimitive.content == "old" }["deprecationReason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a full pipeline run with the default instrumentation exercises the no-op hooks`() = runTest {
        // GraphQL's default instrumentation is Instrumentation.NONE, which inherits every begin* default (noopPhase)
        // plus instrumentExecution; a successful end-to-end run invokes each.
        val result = GraphQL(executable()).execute(GraphQLRequest("{ me { id name } }"))
        assertEquals("Ada", ((result.data as JsonObject)["me"] as JsonObject)["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the no-op instrumentation default hooks are invoked directly`() = runTest {
        val none = Instrumentation.NONE
        val doc = Parser.parse("{ me { id } }")
        val params = ExecutionParameters(doc, null, JsonObject(emptyMap()), executable().schema)
        none.beginParse("{ me { id } }").onCompleted(null, null)
        none.beginValidation(doc).onCompleted(null, null)
        none.beginExecution(params).onCompleted(null, null)
        none.beginField(FieldParameters("Query", "me", emptyList())).onCompleted(null, null)
        assertEquals(emptyList(), none.instrumentExecution(params))
    }

    // ---- query complexity directive / fragment edges ----

    private fun parse(q: String): Pair<OperationDefinition, Map<String, FragmentDefinition>> {
        val document = Parser.parse(q)
        return document.definitions.filterIsInstance<OperationDefinition>().first() to
            document.definitions.filterIsInstance<FragmentDefinition>().associateBy { it.name }
    }

    @Test
    fun `complexity skips an unknown fragment spread`() {
        val (op, frags) = parse("{ a { ...Unknown } }") // fragments["Unknown"] == null → contributes 0
        assertEquals(1, QueryComplexity.complexity(op, frags, JsonObject(emptyMap())))
    }

    @Test
    fun `complexity ignores a directive with no if argument`() {
        val (op, frags) = parse("{ a @skip { b } }") // @skip, no `if` → not skipped
        assertEquals(2, QueryComplexity.complexity(op, frags, JsonObject(emptyMap())))
    }

    @Test
    fun `complexity ignores a directive if bound to a non-primitive variable`() {
        val (op, frags) = parse("query Q(\$s: Boolean!) { a @skip(if: \$s) { b } }")
        val variables = buildJsonObject { put("s", buildJsonObject { put("nested", JsonPrimitive(1)) }) } // a JSON object, not a primitive
        assertEquals(2, QueryComplexity.complexity(op, frags, variables)) // non-primitive → ignored → field kept
    }

    // ---- facade error + empty-document paths ----

    @Test
    fun `the facade rethrows and notifies instrumentation when completion throws`() = runTest {
        val throwing = object : SimpleInstrumentation() {
            override suspend fun beginExecution(parameters: ExecutionParameters): InstrumentationPhase<ExecutionResult> =
                InstrumentationPhase { _, _ -> throw RuntimeException("completion boom") }
        }
        val graphql = GraphQL(executable(), instrumentation = throwing)
        assertFailsWith<RuntimeException> { graphql.execute(GraphQLRequest("{ me { id } }")) }
    }

    @Test
    fun `the facade reports when the provider yields no document and no errors`() = runTest {
        // a (degenerate) provider that returns neither a document nor errors → the "no document produced" guard
        val emptyProvider = PreparsedDocumentProvider { _, _ -> PreparsedDocument(document = null, errors = emptyList()) }
        val result = GraphQL(executable(), preparsedDocumentProvider = emptyProvider).execute(GraphQLRequest("{ me { id } }"))
        assertEquals(null, result.data)
        assertTrue(result.errors.single().message.contains("No document"), result.errors.toString())
    }
}
