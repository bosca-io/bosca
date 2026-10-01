package bosca.graphql.server

import bosca.graphql.schema.GraphQLSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * binding a [RuntimeWiring] onto a schema to produce an [ExecutableSchema] — resolver lookups
 * (wired + default), abstract type resolution, scalar coercings, and clear build-time wiring errors.
 */
class ExecutableSchemaTest {

    private val schema = GraphQLSchema.fromSdl(
        """
        type Query { me: User node: Node result: Result }
        type User { id: ID! name: String! }
        type Admin { id: ID! }
        interface Node { id: ID! }
        union Result = User | Admin
        scalar DateTime
        """.trimIndent(),
    )

    private fun executable() = ExecutableSchema.from(
        schema,
        runtimeWiring {
            type("Query") {
                field("me") { mapOf("id" to "1", "name" to "Ada") }
            }
            type("Node") { resolveType { (it as Map<*, *>)["__typename"] as String? } }
            scalar("DateTime", ExtendedScalars.DateTime)
        },
    )

    private fun context(source: Any?, field: String, type: String, args: Map<String, Any?> = emptyMap()) =
        ResolverContext(source, args, field, type, schema)

    @Test
    fun `resolves a wired field`() = runTest {
        val value = executable().resolver("Query", "me").resolve(context(null, "me", "Query"))
        assertEquals(mapOf("id" to "1", "name" to "Ada"), value)
    }

    @Test
    fun `an unwired field falls back to reading the map source`() = runTest {
        val value = executable().resolver("User", "name").resolve(context(mapOf("name" to "Ada"), "name", "User"))
        assertEquals("Ada", value)
    }

    @Test
    fun `arguments are exposed to the resolver`() = runTest {
        val exec = ExecutableSchema.from(
            GraphQLSchema.fromSdl("type Query { add(a: Int, b: Int): Int }"),
            runtimeWiring { type("Query") { field("add") { ctx -> ctx.arg<Int>("a")!! + ctx.arg<Int>("b")!! } } },
        )
        val sum = exec.resolver("Query", "add").resolve(
            ResolverContext(null, mapOf("a" to 2, "b" to 3), "add", "Query", exec.schema),
        )
        assertEquals(5, sum)
    }

    @Test
    fun `a wired type resolver is used, and an unwired abstract type falls back to __typename`() {
        val exec = executable()
        assertEquals("User", exec.typeResolver("Node").resolveType(mapOf("__typename" to "User")))
        // Result has no wired resolver -> default reads __typename
        assertEquals("Admin", exec.typeResolver("Result").resolveType(mapOf("__typename" to "Admin")))
    }

    @Test
    fun `coercings cover built-in and wired custom scalars`() {
        val exec = executable()
        val int = assertNotNull(exec.coercing("Int"))
        assertEquals(5, int.parseValue(5))
        assertEquals(JsonPrimitive(5), int.serialize(5))
        assertNotNull(exec.coercing("DateTime"))
        assertNull(exec.coercing("NotAScalar"))
    }

    @Test
    fun `default resolvers return null for non-map values`() = runTest {
        val exec = executable()
        // default field resolver on a non-map source
        assertNull(exec.resolver("User", "name").resolve(context("not-a-map", "name", "User")))
        // default abstract-type resolver on a non-map value
        assertNull(exec.typeResolver("Result").resolveType("not-a-map"))
    }

    @Test
    fun `the non-reified argument accessor reads coerced arguments`() {
        val ctx = context(null, "f", "Query", args = mapOf("a" to 1))
        assertEquals(1, ctx.argument("a"))
        assertNull(ctx.argument("missing"))
    }

    // ---- build-time wiring errors ----

    @Test
    fun `rejects wiring an unknown field`() {
        assertFailsWith<ExecutableSchemaException> {
            ExecutableSchema.from(schema, runtimeWiring { type("Query") { field("nope") { null } }; scalar("DateTime", ExtendedScalars.DateTime) })
        }.also { assertEquals(true, it.message!!.contains("unknown field 'Query.nope'")) }
    }

    @Test
    fun `rejects wiring fields on a non-composite type`() {
        assertFailsWith<ExecutableSchemaException> {
            ExecutableSchema.from(schema, runtimeWiring { type("DateTime") { field("x") { null } } })
        }.also { assertEquals(true, it.message!!.contains("not an object or interface")) }
    }

    @Test
    fun `rejects a type resolver on a concrete object type`() {
        assertFailsWith<ExecutableSchemaException> {
            ExecutableSchema.from(schema, runtimeWiring { type("User") { resolveType { null } }; scalar("DateTime", ExtendedScalars.DateTime) })
        }.also { assertEquals(true, it.message!!.contains("only interfaces and unions")) }
    }

    @Test
    fun `rejects a Coercing on a non-scalar type`() {
        assertFailsWith<ExecutableSchemaException> {
            ExecutableSchema.from(schema, runtimeWiring { scalar("User", ExtendedScalars.DateTime); scalar("DateTime", ExtendedScalars.DateTime) })
        }.also { assertEquals(true, it.message!!.contains("not a scalar type")) }
    }

    @Test
    fun `rejects a custom scalar with no Coercing`() {
        assertFailsWith<ExecutableSchemaException> {
            ExecutableSchema.from(schema, runtimeWiring { type("Query") { field("me") { null } } })
        }.also { assertEquals(true, it.message!!.contains("No Coercing registered for custom scalar 'DateTime'")) }
    }

    @Test
    fun `fromSdl builds end-to-end`() = runTest {
        val exec = ExecutableSchema.fromSdl(
            "type Query { ping: String }",
            runtimeWiring { type("Query") { field("ping") { "pong" } } },
        )
        assertEquals("pong", exec.resolver("Query", "ping").resolve(context(null, "ping", "Query")))
    }

    @Test
    fun `prebuilt type wiring merges fields and abstract type resolvers`() = runTest {
        val resolver = FieldResolver { "wired" }
        val typeResolver = TypeResolver { "User" }
        val queryWiring = TypeRuntimeWiring.newTypeWiring("Query")
            .field("me", resolver)
            .build()
        val nodeWiring = TypeRuntimeWiring.newTypeWiring("Node")
            .resolveType(typeResolver)
            .build()
        val emptyWiring = TypeRuntimeWiring.newTypeWiring("Admin").build()

        val wiring = RuntimeWiringBuilder()
            .type(queryWiring)
            .type(nodeWiring)
            .type(emptyWiring)
            .scalar("DateTime", ExtendedScalars.DateTime)
            .build()

        assertSame(resolver, wiring.fieldResolvers.getValue("Query").getValue("me"))
        assertSame(typeResolver, wiring.typeResolvers.getValue("Node"))
        assertEquals(emptyMap(), wiring.fieldResolvers["Admin"].orEmpty())

        val exec = ExecutableSchema.from(schema, wiring)
        assertEquals("wired", exec.resolver("Query", "me").resolve(context(null, "me", "Query")))
        assertEquals("User", exec.typeResolver("Node").resolveType(null))
    }
}
