package bosca.graphql.schema

import bosca.graphql.language.EnumTypeDefinition
import bosca.graphql.language.InputObjectTypeDefinition
import bosca.graphql.language.NamedType
import bosca.graphql.language.OperationType
import bosca.graphql.language.ScalarTypeDefinition
import bosca.graphql.language.UnionTypeDefinition
import bosca.graphql.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaExtensionsTest {

    @Test
    fun `merges enum, union, input, interface, and scalar extensions onto their base types`() {
        val schema = GraphQLSchema.fromSdl(
            """
            type Query { e: Episode s: Search i(f: Filter): Int n: Node }
            enum Episode { NEWHOPE }
            extend enum Episode { EMPIRE JEDI }
            type Human implements Node { id: ID! createdAt: String }
            type Droid implements Node { id: ID! createdAt: String }
            union Search = Human
            extend union Search = Droid
            input Filter { a: String }
            extend input Filter { b: Int }
            directive @tag on INTERFACE
            interface Node { id: ID! }
            extend interface Node @tag { createdAt: String }
            scalar Date
            extend scalar Date @specifiedBy(url: "https://example.com")
            """.trimIndent(),
        )
        assertEquals(listOf("NEWHOPE", "EMPIRE", "JEDI"), (schema.type("Episode") as EnumTypeDefinition).values.map { it.name })
        assertEquals(setOf("Human", "Droid"), schema.possibleTypes("Search").map { it.name }.toSet())
        assertEquals(listOf("a", "b"), (schema.type("Filter") as InputObjectTypeDefinition).fields.map { it.name })
        assertEquals(listOf("id", "createdAt"), schema.fields("Node")!!.map { it.name })
        assertEquals(1, (schema.type("Date") as ScalarTypeDefinition).directives.size)
    }

    @Test
    fun `a schema extension contributes a root operation type`() {
        val schema = GraphQLSchema.fromSdl(
            """
            schema { query: Query }
            type Query { a: Int }
            type Mutation { b: Int }
            extend schema { mutation: Mutation }
            """.trimIndent(),
        )
        assertEquals("Mutation", schema.mutationTypeName)
        assertEquals("Mutation", schema.rootType(OperationType.MUTATION)?.name)
    }

    @Test
    fun `rootType resolves mutation and subscription by convention`() {
        val schema = GraphQLSchema.fromSdl(
            """
            type Query { a: Int }
            type Mutation { b: Int }
            type Subscription { c: Int }
            """.trimIndent(),
        )
        assertEquals("Mutation", schema.rootType(OperationType.MUTATION)?.name)
        assertEquals("Subscription", schema.rootType(OperationType.SUBSCRIPTION)?.name)
    }

    @Test
    fun `possibleTypes is empty for concrete and unknown types`() {
        val schema = GraphQLSchema.fromSdl("type Query { x: Int }")
        assertEquals(emptyList(), schema.possibleTypes("Query"))
        assertEquals(emptyList(), schema.possibleTypes("DoesNotExist"))
    }

    @Test
    fun `classification of unknown types is false`() {
        val schema = GraphQLSchema.fromSdl("type Query { x: Int }")
        assertFalse(schema.isInputType("Nope"))
        assertFalse(schema.isOutputType("Nope"))
    }

    @Test
    fun `builds from an already-parsed document`() {
        val schema = GraphQLSchema.fromDocument(Parser.parse("type Query { x: Int }"))
        assertEquals("Query", schema.queryType?.name)
    }

    @Test
    fun `rejects implementing a non-interface type`() {
        val ex = assertFailsWith<SchemaException> {
            GraphQLSchema.fromSdl(
                """
                type Query { x: Foo }
                type Foo { y: Int }
                type Bar implements Foo { y: Int }
                """.trimIndent(),
            )
        }
        assertTrue(ex.message!!.contains("not an interface"), ex.message)
    }

    @Test
    fun `rejects extending unknown non-object types`() {
        listOf(
            "extend scalar X @specifiedBy(url: \"u\")",
            "extend interface X { a: Int }",
            "extend union X = A",
            "extend enum X { A }",
            "extend input X { a: Int }",
        ).forEach { ext ->
            assertFailsWith<SchemaException> { GraphQLSchema.fromSdl("type Query { q: Int }\n$ext") }
        }
    }

    @Test
    fun `rejects duplicate directive definitions`() {
        assertFailsWith<SchemaException> {
            GraphQLSchema.fromSdl("type Query { q: Int } directive @d on FIELD directive @d on OBJECT")
        }
    }

    @Test
    fun `rejects multiple schema definitions`() {
        assertFailsWith<SchemaException> {
            GraphQLSchema.fromSdl("schema { query: Query } schema { query: Query } type Query { a: Int }")
        }
    }

    @Test
    fun `does not overwrite user-redefined built-in scalars or directives`() {
        val schema = GraphQLSchema.fromSdl(
            """
            type Query { x: String }
            scalar String
            directive @skip(if: Boolean!) on FIELD
            """.trimIndent(),
        )
        assertNotNull(schema.type("String"))
        assertNotNull(schema.directives["skip"])
    }

    @Test
    fun `ignores executable definitions when building a schema`() {
        val schema = GraphQLSchema.fromSdl("type Query { x: Int }\nquery Q { x }")
        assertEquals("Query", schema.queryType?.name)
    }

    @Test
    fun `schema predicates cover every type kind`() {
        val schema = GraphQLSchema.fromSdl(
            """
            type Query { x: Int }
            interface I { a: Int }
            union U = Query
            enum E { A }
            input In { a: Int }
            scalar S
            """.trimIndent(),
        )
        assertNotNull(schema.fields("Query"))
        assertNotNull(schema.fields("I"))
        listOf("U", "E", "In", "S", "Nope").forEach { assertNull(schema.fields(it), "fields($it)") }

        assertTrue(schema.isInputType("In") && schema.isInputType("E") && schema.isInputType("S"))
        listOf("Query", "I", "U", "Nope").forEach { assertFalse(schema.isInputType(it), "isInputType($it)") }

        listOf("Query", "I", "U", "E", "S").forEach { assertTrue(schema.isOutputType(it), "isOutputType($it)") }
        listOf("In", "Nope").forEach { assertFalse(schema.isOutputType(it), "isOutputType($it)") }

        assertEquals(listOf("Query"), schema.possibleTypes("U").map { it.name }) // union member
        assertEquals(emptyList(), schema.possibleTypes("S"))                     // scalar -> else branch
    }

    @Test
    fun `root type getters defend against null and non-object names`() {
        // Construct directly (internal ctor) to reach the getters' null and `as?`-null branches, which the
        // builder's invariants make unreachable through fromSdl.
        val schema = GraphQLSchema(
            types = mapOf("S" to ScalarTypeDefinition(null, "S", emptyList())),
            directives = emptyMap(),
            queryTypeName = null, // null -> queryType getter's null branch
            mutationTypeName = "S", // present but not an object -> `as?` yields null
            subscriptionTypeName = "Missing", // absent name -> `as?` yields null
        )
        assertNull(schema.queryType)
        assertNull(schema.mutationType)
        assertNull(schema.subscriptionType)
        assertNull(schema.rootType(OperationType.QUERY))
    }

    @Test
    fun `accessors tolerate a malformed (directly built) schema`() {
        // The builder guarantees object roots and object union members; these defensive `as?` paths are only
        // reachable by constructing a schema directly with a non-object root and non-object/absent union members.
        val schema = GraphQLSchema(
            types = mapOf(
                "S" to ScalarTypeDefinition(null, "S", emptyList()),
                "U" to UnionTypeDefinition(null, "U", emptyList(), listOf(NamedType("S"), NamedType("Gone"))),
            ),
            directives = emptyMap(),
            queryTypeName = "S", // present but not an object -> queryType getter's `as?` yields null
            mutationTypeName = null,
            subscriptionTypeName = null,
        )
        assertNull(schema.queryType)
        assertEquals(emptyList(), schema.possibleTypes("U")) // members resolve to non-object / missing -> filtered out
    }

    @Test
    fun `rejects an unknown root type and an unknown implemented interface`() {
        assertFailsWith<SchemaException> { GraphQLSchema.fromSdl("schema { query: Missing }") }
        assertFailsWith<SchemaException> {
            GraphQLSchema.fromSdl("type Query { x: T } type T implements Iface { y: Int }")
        }
    }
}
