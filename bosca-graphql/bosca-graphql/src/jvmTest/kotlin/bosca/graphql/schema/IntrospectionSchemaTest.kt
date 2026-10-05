package bosca.graphql.schema

import bosca.graphql.language.NonNullType
import bosca.graphql.language.ObjectTypeDefinition
import bosca.graphql.language.ScalarTypeDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** (foundation half): merging the introspection type system into an assembled schema. */
class IntrospectionSchemaTest {

    @Test
    fun `parses the eight standard introspection types`() {
        val names = IntrospectionSchema.types.map { it.name }.toSet()
        assertEquals(
            setOf("__Schema", "__Type", "__Field", "__InputValue", "__EnumValue", "__Directive", "__TypeKind", "__DirectiveLocation"),
            names,
        )
    }

    @Test
    fun `withIntrospection adds the introspection types and the query meta-fields`() {
        val schema = GraphQLSchema.fromSdl("type Query { me: String }").withIntrospection()

        assertNotNull(schema.type("__Schema"))
        assertNotNull(schema.type("__Type"))
        assertNotNull(schema.type("__TypeKind"))

        val query = schema.queryType!!
        val schemaField = query.fields.first { it.name == "__schema" }
        assertEquals("__Schema", ((schemaField.type as NonNullType).type as bosca.graphql.language.NamedType).name)
        val typeField = query.fields.first { it.name == "__type" }
        assertEquals("name", typeField.arguments.single().name)
        assertTrue(query.fields.any { it.name == "me" }) // the user field is preserved
    }

    @Test
    fun `withIntrospection is idempotent`() {
        val once = GraphQLSchema.fromSdl("type Query { me: String }").withIntrospection()
        val twice = once.withIntrospection()

        assertEquals(once.types.size, twice.types.size)
        val metaFieldCount = (twice.queryType as ObjectTypeDefinition).fields.count { it.name.startsWith("__") }
        assertEquals(2, metaFieldCount) // __schema + __type, not duplicated
    }

    @Test
    fun `withIntrospection preserves a user type that already carries a reserved name`() {
        // A schema can't normally declare __X, but if one ever shares an introspection name, the user's wins.
        val schema = GraphQLSchema.fromSdl("type Query { me: String }").withIntrospection()
        assertSame(IntrospectionSchema.types.first { it.name == "__Schema" }, schema.type("__Schema"))
    }

    @Test
    fun `the SDL constant is populated`() {
        assertTrue(IntrospectionSchema.SDL.contains("type __Schema"))
        assertTrue(IntrospectionSchema.SDL.contains("enum __TypeKind"))
    }

    @Test
    fun `withIntrospection adds the types but no meta-fields when there is no query type`() {
        // a schema with no query root: the introspection types are still added, but no meta-fields are spliced
        val schema = GraphQLSchema(emptyMap(), emptyMap(), null, null, null).withIntrospection()
        assertNotNull(schema.type("__Schema"))
        assertNull(schema.queryType)
    }

    @Test
    fun `withIntrospection skips meta-fields when the query type name is not an object`() {
        // queryTypeName resolves to a scalar (not an object), so the meta-fields cannot be spliced
        val scalarQuery = ScalarTypeDefinition(null, "Query", emptyList())
        val schema = GraphQLSchema(mapOf("Query" to scalarQuery), emptyMap(), "Query", null, null).withIntrospection()
        assertNotNull(schema.type("__Type"))
        assertSame(scalarQuery, schema.type("Query")) // the non-object query type is left untouched
    }

    @Test
    fun `the query meta-fields reference the introspection types`() {
        assertEquals(2, IntrospectionSchema.queryMetaFields.size)
        val type = IntrospectionSchema.queryMetaFields.first { it.name == "__type" }
        assertEquals("__Type", (type.type as bosca.graphql.language.NamedType).name)
        assertNull(type.description)
    }
}
