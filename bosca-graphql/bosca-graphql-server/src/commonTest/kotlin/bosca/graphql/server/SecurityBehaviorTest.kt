package bosca.graphql.server

import bosca.graphql.language.Value
import bosca.graphql.parser.Parser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SecurityBehaviorTest {

    private val sensitiveScalar = object : Coercing {
        override fun serialize(value: Any?) = error("sensitive serialize detail: $value")
        override fun parseValue(input: Any?): Any? = error("sensitive variable detail: $input")
        override fun parseLiteral(literal: Value): Any? = error("sensitive literal detail: $literal")
    }

    private fun executable() = ExecutableSchema.fromSdl(
        """
        scalar Secret
        type Query {
          echo(value: Secret): String
          secret: Secret
          badNode: Node
          throwingNode: Node
          fatal: String
          cancelled: String
        }
        interface Node { id: ID! }
        type User implements Node { id: ID! }
        """.trimIndent(),
        runtimeWiring {
            scalar("Secret", sensitiveScalar)
            type("Query") {
                field("echo") { "not reached" }
                field("secret") { "database-secret" }
                field("badNode") { mapOf("kind" to "invalid", "id" to "1") }
                field("throwingNode") { mapOf("kind" to "throws", "id" to "2") }
                field("fatal") { throw AssertionError("fatal resolver failure") }
                field("cancelled") { throw CancellationException("cancel resolver") }
            }
            type("Node") {
                resolveType { value ->
                    if ((value as Map<*, *>)["kind"] == "throws") {
                        error("sensitive type resolver detail")
                    }
                    "Query"
                }
            }
        },
    )

    @Test
    fun `unexpected scalar input failures are sanitized`() = runTest {
        val executor = GraphQLExecutor(executable())

        val literal = executor.execute(Parser.parse("""{ echo(value: "secret-literal") }"""))
        assertEquals(JsonNull, (literal.data as JsonObject)["echo"])
        assertEquals("Invalid value for scalar 'Secret'", literal.errors.single().message)
        assertFalse(literal.errors.single().message.contains("sensitive"))

        val variable = executor.execute(
            Parser.parse("query Q(\$value: Secret) { echo(value: \$value) }"),
            variables = mapOf("value" to JsonPrimitive("secret-variable")),
        )
        assertEquals(null, variable.data)
        assertEquals("Invalid value for scalar 'Secret'", variable.errors.single().message)
        assertFalse(variable.errors.single().message.contains("sensitive"))
    }

    @Test
    fun `unexpected scalar output failures are sanitized`() = runTest {
        val result = GraphQLExecutor(executable()).execute(Parser.parse("{ secret }"))
        assertEquals(JsonNull, (result.data as JsonObject)["secret"])
        assertEquals("Could not serialize value as 'Secret'", result.errors.single().message)
        assertFalse(result.errors.single().message.contains("database-secret"))
    }

    @Test
    fun `abstract type resolution validates membership and sanitizes failures`() = runTest {
        val executor = GraphQLExecutor(executable())
        for (field in listOf("badNode", "throwingNode")) {
            val result = executor.execute(Parser.parse("{ $field { id } }"))
            assertEquals(JsonNull, (result.data as JsonObject)[field])
            assertEquals("Could not resolve the concrete type of 'Node'", result.errors.single().message)
            assertFalse(result.errors.single().message.contains("sensitive"))
        }
    }

    @Test
    fun `fatal errors and cancellation are not converted to GraphQL field errors`() = runTest {
        val executor = GraphQLExecutor(executable())
        assertFailsWith<AssertionError> { executor.execute(Parser.parse("{ fatal }")) }
        assertFailsWith<CancellationException> { executor.execute(Parser.parse("{ cancelled }")) }
    }
}
