package bosca.graphql.server

import bosca.graphql.language.FragmentDefinition
import bosca.graphql.language.OperationDefinition
import bosca.graphql.language.SourceLocation
import bosca.graphql.parser.Parser
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Edge-case coverage across the executor, complexity analysis, DataLoader, errors, and the pipeline facade. */
class ServerEdgeCasesTest {

    private fun parse(q: String) = Parser.parse(q)

    // ---- executor operation-selection + collection edges ----

    private fun simple() = GraphQLExecutor(
        ExecutableSchema.fromSdl(
            """
            type Query { me: User node: Node pet: Pet a: String b: String nnList: [Int!] }
            type User implements Node { id: ID! name: String! }
            interface Node { id: ID! }
            union Pet = Cat
            type Cat { id: ID! }
            type Subscription { stream: Int notFlow: Int nullStream: Int }
            """.trimIndent(),
            runtimeWiring {
                type("Query") {
                    field("me") { mapOf("id" to "1", "name" to "Ada") }
                    field("node") { mapOf("id" to "9") } // no __typename → default resolver returns null type
                    field("pet") { mapOf("__typename" to "Cat", "id" to "c") }
                    field("a") { "a" }
                    field("b") { "b" }
                    field("nnList") { listOf(1, null, 3) } // a null in a [Int!] list
                }
                type("Pet") { resolveType { (it as Map<*, *>)["__typename"] as String? } }
                type("Subscription") {
                    field("stream") { flowOf(1) }
                    field("notFlow") { 42 } // not a Flow
                    field("nullStream") { null } // resolves to null
                }
            },
        ),
    )

    @Test
    fun `a multi-operation document with no operation name is a request error`() = runTest {
        val result = simple().execute(parse("query A { a } query B { b }"))
        assertEquals(null, result.data)
        assertTrue(result.errors.single().message.contains("Must provide an operation name"))
    }

    @Test
    fun `a multi-operation subscription document with no name is a request error`() = runTest {
        val result = simple().executeSubscription(parse("subscription A { stream } subscription B { stream }")).toList()
        assertTrue(result.single().errors.single().message.contains("Must provide an operation name"))
    }

    @Test
    fun `a subscription field resolving to a non-flow does not expose the resolved type`() = runTest {
        val result = simple().executeSubscription(parse("subscription { notFlow }")).toList()
        assertEquals("Subscription field 'notFlow' must resolve to a Flow", result.single().errors.single().message)
    }

    @Test
    fun `a subscription field resolving to null does not expose the resolved value`() = runTest {
        val result = simple().executeSubscription(parse("subscription { nullStream }")).toList()
        assertEquals("Subscription field 'nullStream' must resolve to a Flow", result.single().errors.single().message)
    }

    @Test
    fun `fragment-spread skip, duplicate, undefined, and type-mismatch are handled in collection`() = runTest {
        // @skip on a spread; the same fragment spread twice (deduped); an undefined spread; a spread whose type doesn't apply
        val q = """
            { me { ...A @skip(if: true) ...B ...B ...C } }
            fragment B on User { id }
            fragment C on Cat { id }
        """.trimIndent()
        val data = simple().execute(parse(q)).data as JsonObject
        assertEquals("1", data["me"]!!.jsonObject["id"]!!.jsonPrimitive.content) // ...B applied once; ...A skipped; ...C type mismatch
        assertTrue(!data["me"]!!.jsonObject.containsKey("name"))
    }

    @Test
    fun `an undefined, non-skipped fragment spread is skipped during collection`() = runTest {
        // executes pre-validation: an unknown fragment spread simply contributes nothing
        val data = simple().execute(parse("{ me { ...Unknown id } }")).data as JsonObject
        assertEquals("1", data["me"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `include-false and a variable-driven directive are applied`() = runTest {
        val r1 = simple().execute(parse("{ me { id name @include(if: false) } }")).data as JsonObject
        assertEquals(setOf("id"), r1["me"]!!.jsonObject.keys)
        val r2 = simple().execute(
            parse("query Q(\$c: Boolean!) { me { id name @include(if: \$c) } }"),
            variables = buildJsonObject { put("c", true) },
        ).data as JsonObject
        assertEquals(setOf("id", "name"), r2["me"]!!.jsonObject.keys)
    }

    @Test
    fun `an inline fragment without a type condition is collected`() = runTest {
        val data = simple().execute(parse("{ me { ... { id } } }")).data as JsonObject
        assertEquals("1", data["me"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a null element in a non-null list nulls the whole list`() = runTest {
        val result = simple().execute(parse("{ nnList }"))
        assertEquals(JsonNull, (result.data as JsonObject)["nnList"])
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun `an object queried with no sub-selection yields an empty object`() = runTest {
        // exercises subSelection over a field whose own selectionSet is null
        val data = simple().execute(parse("{ me }")).data as JsonObject
        assertEquals(emptyMap(), data["me"]!!.jsonObject)
    }

    @Test
    fun `an abstract value with no resolvable type errors`() = runTest {
        // node returns a map with no __typename; the default type resolver yields null
        val result = simple().execute(parse("{ node { id } }"))
        assertEquals(JsonNull, (result.data as JsonObject)["node"])
        assertTrue(result.errors.single().message.contains("resolve"))
    }

    // ---- QueryComplexity ----

    private fun op(q: String) = parse(q).definitions.filterIsInstance<OperationDefinition>().first()
    private fun frags(q: String) = parse(q).definitions.filterIsInstance<FragmentDefinition>().associateBy { it.name }

    @Test
    fun `depth descends through fragment spreads and inline fragments`() {
        val q = "{ a { ...F ... { x } } } fragment F on T { b { c } }"
        assertEquals(3, QueryComplexity.depth(op(q), frags(q), JsonObject(emptyMap()))) // a → b → c
    }

    @Test
    fun `depth of an all-skipped selection set is zero`() {
        val q = "{ a @skip(if: true) }"
        assertEquals(0, QueryComplexity.depth(op(q), frags(q), JsonObject(emptyMap())))
    }

    @Test
    fun `complexity descends through fragments, inline fragments, and meta-fields with a custom calculator`() {
        val q = "{ a { ...F ... { x } __typename } } fragment F on T { b { c } }"
        // default calculator: a(1 + F:b(1+c)=2 + inline:x(1) + __typename(0)) = 1 + 2 + 1 = 4
        assertEquals(4, QueryComplexity.complexity(op(q), frags(q), JsonObject(emptyMap())))
        // a custom calculator weights every field by 10
        val weighted = FieldComplexityCalculator { _, _, child -> 10 + child }
        assertTrue(QueryComplexity.complexity(op(q), frags(q), JsonObject(emptyMap()), weighted) > 4)
    }

    @Test
    fun `query limits skip a document with no identifiable operation and honor a named one`() = runTest {
        val schema = ExecutableSchema.fromSdl("type Query { a: String }", runtimeWiring { }).schema
        val ambiguous = ExecutionParameters(parse("query A { a } query B { a }"), null, JsonObject(emptyMap()), schema)
        assertTrue(MaxQueryDepthInstrumentation(1).instrumentExecution(ambiguous).isEmpty()) // operation() null → no rejection
        assertTrue(MaxQueryComplexityInstrumentation(1).instrumentExecution(ambiguous).isEmpty())
        // a named operation is selected and measured
        val named = ExecutionParameters(parse("query A { a } query B { a a a } "), "B", JsonObject(emptyMap()), schema)
        assertTrue(MaxQueryComplexityInstrumentation(1).instrumentExecution(named).isNotEmpty()) // B complexity 3 > 1
        assertTrue(MaxQueryComplexityInstrumentation(10).instrumentExecution(named).isEmpty()) // within limit
    }

    @Test
    fun `complexity honors skip and a non-boolean directive if`() {
        assertEquals(1, QueryComplexity.complexity(op("{ a b @skip(if: true) }"), frags(""), JsonObject(emptyMap()))) // b skipped
        assertEquals(2, QueryComplexity.complexity(op("{ a b @skip(if: 5) }"), frags(""), JsonObject(emptyMap()))) // non-boolean if ignored
        // skip:false and include:true keep the field (the not-removed side of each directive check)
        assertEquals(2, QueryComplexity.complexity(op("{ a b @skip(if: false) }"), frags(""), JsonObject(emptyMap())))
        assertEquals(2, QueryComplexity.complexity(op("{ a b @include(if: true) }"), frags(""), JsonObject(emptyMap())))
    }

    @Test
    fun `the include directive and a variable if are honored in analysis`() {
        val kept = "query Q(\$s: Boolean!) { a @include(if: \$s) }"
        assertEquals(1, QueryComplexity.depth(op(kept), frags(kept), buildJsonObject { put("s", true) }))
        assertEquals(0, QueryComplexity.depth(op(kept), frags(kept), buildJsonObject { put("s", false) }))
    }

    // ---- DataLoader ----

    @Test
    fun `loadMany of an empty list returns empty and an unknown loader throws`() = runTest {
        val registry = dataLoaderRegistry { loader<String, String>("x") { it } }
        assertEquals(emptyList(), registry.loader<String, String>("x").loadMany(emptyList()))
        assertFailsWith<IllegalStateException> { registry.loader<String, String>("missing") }
    }

    // ---- GraphQLError ----

    @Test
    fun `error toJson includes extensions, locations, and path when present`() {
        val error = GraphQLError(
            "boom",
            locations = listOf(SourceLocation(2, 3, 5)),
            path = listOf("a", 0, "b"),
            extensions = buildJsonObject { put("code", "X") },
        )
        val json = error.toJson()
        assertEquals("boom", json["message"]!!.jsonPrimitive.content)
        assertTrue(json.containsKey("locations") && json.containsKey("path") && json.containsKey("extensions"))
        // an empty extensions map is omitted
        assertTrue(!GraphQLError("x", extensions = JsonObject(emptyMap())).toJson().containsKey("extensions"))
    }

    // ---- DefaultDataFetcherExceptionHandler ----

    @Test
    fun `the default exception handler falls back when the exception has no message`() = runTest {
        val handled = DefaultDataFetcherExceptionHandler.handle(RuntimeException(), emptyList(), null, GraphQLContext.EMPTY)
        assertEquals("Internal server error", handled.message)
        val withExt = DefaultDataFetcherExceptionHandler.handle(
            GraphQLException("nope", buildJsonObject { put("c", 1) }),
            emptyList(),
            SourceLocation(1, 1, 0),
            GraphQLContext.EMPTY,
        )
        assertEquals("nope", withExt.message)
        assertTrue(withExt.extensions != null && withExt.locations.isNotEmpty())
    }

    // ---- ResolverContext.arg type mismatch ----

    @Test
    fun `arg returns null when the argument is of another type`() = runTest {
        val executor = GraphQLExecutor(
            ExecutableSchema.fromSdl(
                "type Query { f(x: Int!): String }",
                runtimeWiring { type("Query") { field("f") { ctx -> "${ctx.arg<String>("x")}" } } }, // x is an Int, asked as String → null
            ),
        )
        val data = executor.execute(parse("{ f(x: 5) }")).data as JsonObject
        assertEquals("null", data["f"]!!.jsonPrimitive.content)
    }

    // ---- PreparsedDocumentProvider: bounded negative caching ----

    @Test
    fun `a parse failure is cached and the facade reports it`() = runTest {
        val provider = InMemoryPreparsedDocumentProvider()
        val graphql = GraphQL(ExecutableSchema.fromSdl("type Query { a: String }", runtimeWiring { }), preparsedDocumentProvider = provider)
        graphql.execute(GraphQLRequest("{ a ")) // syntax error
        assertEquals(1, provider.size)
    }

    @Test
    fun `the facade surfaces a parse error for a subscription request`() = runTest {
        val graphql = GraphQL(simpleSchema())
        val result = graphql.executeSubscription(GraphQLRequest("subscription { ")).toList()
        assertTrue(result.single().errors.isNotEmpty())
    }

    private fun simpleSchema() = ExecutableSchema.fromSdl(
        "type Query { a: String } type Subscription { s: Int }",
        runtimeWiring { type("Subscription") { field("s") { flowOf(1) } } },
    )
}
