package bosca.graphql.server

import bosca.graphql.parser.Parser
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The request-scoped [GraphQLContext] bag and the [ResolverContext] argument accessors. */
class ResolverContextTest {

    @Test
    fun `the context bag reads typed and untyped values`() {
        val context = GraphQLContext(mapOf("tenant" to "acme", "count" to 7))
        assertEquals("acme", context["tenant"])
        assertEquals("acme", context.getAs<String>("tenant"))
        assertEquals(7, context.getAs<Int>("count"))
        assertNull(context.getAs<String>("count")) // wrong type → null
        assertNull(context["missing"])
        assertEquals(emptyMap(), GraphQLContext.EMPTY.values)
    }

    @Test
    fun `a resolver reads the request context and its arguments`() = runTest {
        val executor = GraphQLExecutor(
            ExecutableSchema.fromSdl(
                "type Query { greet(name: String!): String }",
                runtimeWiring {
                    type("Query") {
                        field("greet") { ctx ->
                            val who = ctx.argument("name") as String
                            "${ctx.context.getAs<String>("salutation")} $who"
                        }
                    }
                },
            ),
        )
        val data = executor.execute(
            Parser.parse("""{ greet(name: "Ada") }"""),
            context = GraphQLContext(mapOf("salutation" to "Hello")),
        ).data as JsonObject
        assertEquals("Hello Ada", data["greet"]!!.jsonPrimitive.content)
    }

    @Test
    fun `generated-controller compatibility accessors expose native request state`() {
        val schema = bosca.graphql.schema.GraphQLSchema.fromSdl("type Query { value: String }")
        val graphQlContext = GraphQLContext(mapOf("call" to "request"))
        val loaders = DataLoaderRegistry()
        val context = ResolverContext(
            source = "source",
            arguments = mapOf("count" to 3),
            fieldName = "value",
            parentType = "Query",
            schema = schema,
            context = graphQlContext,
            dataLoaders = loaders,
        )

        assertEquals("source", context.sourceAs<String>())
        assertNull(context.sourceAs<Int>())
        assertEquals(3, context.getArgument<Int>("count"))
        assertNull(context.getArgument<String>("count"))
        assertSame(graphQlContext, context.graphQlContext)
        assertSame(loaders, context.dataLoaderRegistry)
    }
}
