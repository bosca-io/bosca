package bosca.graphql

import bosca.graphql.schema.GraphQLSchema
import bosca.graphql.server.GraphQLContext
import bosca.graphql.server.ResolverContext
import bosca.server.BoscaApplication
import io.mockk.mockk
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

@Serializable
private data class GraphQLExtInput(val name: String, val count: Int)

class GraphQLExtTest {

    private val schema = GraphQLSchema.fromSdl("type Query { value: String }")

    private fun context(
        values: Map<String, Any?> = emptyMap(),
        arguments: Map<String, Any?> = emptyMap(),
    ) = ResolverContext(
        source = null,
        arguments = arguments,
        fieldName = "value",
        parentType = "Query",
        schema = schema,
        context = GraphQLContext(values),
    )

    @Test
    fun `application returns the request application and rejects a missing value`() {
        val application = mockk<BoscaApplication>()

        assertSame(application, context(mapOf("application" to application)).application)
        assertFailsWith<IllegalStateException> { context().application }
    }

    @Test
    fun `getArgumentObject converts a coerced GraphQL input map`() {
        val environment = context(
            arguments = mapOf("input" to mapOf("name" to "example", "count" to 3)),
        )

        assertEquals(GraphQLExtInput("example", 3), environment.getArgumentObject("input", Json))
    }
}
