package bosca.graphql.schema

import bosca.graphql.language.NonNullType
import bosca.graphql.language.ObjectTypeDefinition
import bosca.graphql.language.OperationType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaTest {

    @Test
    fun `resolves conventional root types and injects built-in scalars and directives`() {
        val schema = GraphQLSchema.fromSdl(
            """
            type Query { me: User }
            type Mutation { noop: Boolean }
            type User { id: ID! name: String }
            """.trimIndent(),
        )
        assertEquals("Query", schema.queryType?.name)
        assertEquals("Mutation", schema.mutationType?.name)
        assertNull(schema.subscriptionType)
        assertEquals(schema.queryType, schema.rootType(OperationType.QUERY))

        // built-in scalars present even though the SDL never declared them
        listOf("Int", "Float", "String", "Boolean", "ID").forEach {
            assertNotNull(schema.type(it), "missing built-in scalar $it")
            assertTrue(schema.isBuiltInScalar(it))
        }
        // built-in directives present
        listOf("skip", "include", "deprecated", "specifiedBy").forEach {
            assertNotNull(schema.directives[it], "missing built-in directive @$it")
        }
    }

    @Test
    fun `honors an explicit schema definition mapping non-conventional root names`() {
        val schema = GraphQLSchema.fromSdl(
            """
            schema { query: RootQuery }
            type RootQuery { ping: String }
            """.trimIndent(),
        )
        assertEquals("RootQuery", schema.queryTypeName)
        assertEquals("RootQuery", schema.queryType?.name)
    }

    @Test
    fun `merges type extensions onto the base type`() {
        val schema = GraphQLSchema.fromSdl(
            """
            type Query { a: Int }
            extend type Query { b: String c: Boolean }
            """.trimIndent(),
        )
        assertEquals(listOf("a", "b", "c"), schema.fields("Query")!!.map { it.name })
        assertEquals("String", (schema.field("Query", "b")!!.type as? bosca.graphql.language.NamedType)?.name)
    }

    @Test
    fun `field and fields lookups work for objects and interfaces`() {
        val schema = GraphQLSchema.fromSdl(
            """
            type Query { node: Node }
            interface Node { id: ID! }
            type User implements Node { id: ID! name: String! }
            """.trimIndent(),
        )
        assertEquals(listOf("id"), schema.fields("Node")!!.map { it.name })
        assertEquals(listOf("id", "name"), schema.fields("User")!!.map { it.name })
        val name = schema.field("User", "name")!!
        assertTrue(name.type is NonNullType)
        assertNull(schema.fields("ID")) // a scalar has no fields
    }

    @Test
    fun `possibleTypes resolves union members and interface implementors`() {
        val schema = GraphQLSchema.fromSdl(
            """
            type Query { search: SearchResult }
            union SearchResult = Human | Droid
            interface Character { name: String }
            type Human implements Character { name: String }
            type Droid implements Character { name: String primaryFunction: String }
            """.trimIndent(),
        )
        assertEquals(setOf("Human", "Droid"), schema.possibleTypes("SearchResult").map { it.name }.toSet())
        assertEquals(setOf("Human", "Droid"), schema.possibleTypes("Character").map { it.name }.toSet())
    }

    @Test
    fun `classifies input and output types`() {
        val schema = GraphQLSchema.fromSdl(
            """
            type Query { x: Int }
            input Filter { term: String }
            enum Color { RED GREEN }
            type Thing { id: ID }
            """.trimIndent(),
        )
        assertTrue(schema.isInputType("Filter") && schema.isInputType("Color") && schema.isInputType("String"))
        assertFalse(schema.isInputType("Thing"))
        assertTrue(schema.isOutputType("Thing") && schema.isOutputType("Color"))
        assertFalse(schema.isOutputType("Filter"))
    }

    @Test
    fun `rejects duplicate type definitions`() {
        val ex = assertFailsWith<SchemaException> {
            GraphQLSchema.fromSdl("type Query { a: Int } type Query { b: Int }")
        }
        assertTrue(ex.message!!.contains("Duplicate"), ex.message)
    }

    @Test
    fun `rejects extending an unknown type`() {
        assertFailsWith<SchemaException> {
            GraphQLSchema.fromSdl("type Query { a: Int } extend type Missing { b: Int }")
        }
    }

    @Test
    fun `rejects a reference to an undefined type`() {
        val ex = assertFailsWith<SchemaException> {
            GraphQLSchema.fromSdl("type Query { user: User }") // User never defined
        }
        assertTrue(ex.message!!.contains("Unknown type 'User'"), ex.message)
    }

    @Test
    fun `rejects a schema without a query root`() {
        assertFailsWith<SchemaException> { GraphQLSchema.fromSdl("type Foo { a: Int }") }
    }

    @Test
    fun `rejects a non-object root type`() {
        assertFailsWith<SchemaException> {
            GraphQLSchema.fromSdl("schema { query: Foo } scalar Foo")
        }
    }
}
