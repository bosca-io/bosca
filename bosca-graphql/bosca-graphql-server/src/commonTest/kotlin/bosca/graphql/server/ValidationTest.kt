package bosca.graphql.server

import bosca.graphql.language.Document
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * the foundation operation validation wired into the [GraphQL] pipeline. Invalid operations
 * are rejected with validation errors before any resolver runs; SDL validation runs at schema-build time.
 */
class ValidationTest {

    private val sdl = """
        type Query { name: String user: User obj: User }
        type User { id: ID! }
    """.trimIndent()

    private class Flags {
        var resolved = false
    }

    private class ValidationCounter : SimpleInstrumentation() {
        var count = 0
        override suspend fun beginValidation(document: Document): InstrumentationPhase<List<GraphQLError>> {
            count++
            return noopPhase()
        }
    }

    private fun graphql(
        flags: Flags = Flags(),
        instrumentation: Instrumentation = Instrumentation.NONE,
        preparsedDocumentProvider: PreparsedDocumentProvider = PreparsedDocumentProvider.NONE,
    ) = GraphQL(
        ExecutableSchema.fromSdl(
            sdl,
            runtimeWiring {
                type("Query") {
                    field("name") { flags.resolved = true; "Ada" }
                    field("user") { mapOf("id" to "1") }
                    field("obj") { mapOf("id" to "1") }
                }
            },
        ),
        instrumentation = instrumentation,
        preparsedDocumentProvider = preparsedDocumentProvider,
    )

    private suspend fun rejects(query: String, vararg messageFragments: String) {
        val flags = Flags()
        val result = graphql(flags).execute(GraphQLRequest(query))
        assertEquals(null, result.data, "$query should be rejected before execution")
        assertTrue(result.errors.isNotEmpty(), "$query should produce validation errors")
        messageFragments.forEach { fragment ->
            assertTrue(result.errors.any { it.message.contains(fragment) }, "expected an error containing '$fragment' for $query, got ${result.errors.map { it.message }}")
        }
        assertFalse(flags.resolved, "no resolver should run for the invalid query $query")
    }

    @Test
    fun `an unknown field is rejected before execution`() = runTest { rejects("{ bogus }", "bogus") }

    @Test
    fun `a leaf field with a sub-selection is rejected`() = runTest { rejects("{ name { x } }", "name") }

    @Test
    fun `a composite field without a sub-selection is rejected`() = runTest { rejects("{ user }", "user") }

    @Test
    fun `an unknown argument is rejected`() = runTest { rejects("{ user(bad: 1) { id } }", "bad") }

    @Test
    fun `a fragment on an unknown type is rejected`() = runTest { rejects("{ user { ...F } } fragment F on Nope { id }", "Nope") }

    @Test
    fun `a valid operation proceeds unaffected`() = runTest {
        val flags = Flags()
        val result = graphql(flags).execute(GraphQLRequest("{ name user { id } }"))
        assertTrue(result.errors.isEmpty(), "${result.errors}")
        val data = result.data as JsonObject
        assertEquals("Ada", data["name"]!!.jsonPrimitive.content)
        assertEquals("1", data["user"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertTrue(flags.resolved)
    }

    @Test
    fun `invalid queries are retained in the bounded preparsed cache`() = runTest {
        val counter = ValidationCounter()
        val graphql = graphql(instrumentation = counter, preparsedDocumentProvider = InMemoryPreparsedDocumentProvider())
        repeat(3) {
            val result = graphql.execute(GraphQLRequest("{ bogus }"))
            assertTrue(result.errors.isNotEmpty())
        }
        assertEquals(1, counter.count)
    }

    @Test
    fun `SDL validation runs at schema-build time`() = runTest {
        // A field referencing an undefined type is a build-time SDL error, not a request-time one.
        val failure = assertFailsWith<Exception> {
            ExecutableSchema.fromSdl("type Query { a: Undefined }", runtimeWiring { })
        }
        assertTrue(failure.message?.contains("Undefined") == true, "expected the build error to name the bad type: ${failure.message}")
    }
}
