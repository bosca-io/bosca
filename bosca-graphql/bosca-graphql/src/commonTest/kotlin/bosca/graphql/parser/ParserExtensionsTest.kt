package bosca.graphql.parser

import bosca.graphql.language.DirectiveDefinition
import bosca.graphql.language.EnumTypeDefinition
import bosca.graphql.language.EnumTypeExtension
import bosca.graphql.language.InputObjectTypeDefinition
import bosca.graphql.language.InputObjectTypeExtension
import bosca.graphql.language.InterfaceTypeExtension
import bosca.graphql.language.ObjectTypeDefinition
import bosca.graphql.language.ObjectTypeExtension
import bosca.graphql.language.BooleanValue
import bosca.graphql.language.OperationDefinition
import bosca.graphql.language.OperationType
import bosca.graphql.language.ScalarTypeDefinition
import bosca.graphql.language.ScalarTypeExtension
import bosca.graphql.language.StringValue
import bosca.graphql.language.SchemaExtension
import bosca.graphql.language.UnionTypeDefinition
import bosca.graphql.language.Variable
import bosca.graphql.language.UnionTypeExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParserExtensionsTest {

    @Test
    fun `parses every type-system extension form`() {
        val doc = Parser.parse(
            """
            extend schema { mutation: Mutation }
            extend scalar Date @foo
            extend type T implements I & J @d { f: Int }
            extend interface I @d { g: Int }
            extend union U = A | B
            extend enum E { X Y }
            extend input In { k: Int = 1 }
            """.trimIndent(),
        )
        assertTrue(doc.definitions[0] is SchemaExtension)
        assertEquals(OperationType.MUTATION, (doc.definitions[0] as SchemaExtension).operationTypes.single().operation)
        assertEquals("Date", (doc.definitions[1] as ScalarTypeExtension).name)
        val typeExt = doc.definitions[2] as ObjectTypeExtension
        assertEquals(listOf("I", "J"), typeExt.interfaces.map { it.name })
        assertEquals("f", typeExt.fields.single().name)
        assertEquals("I", (doc.definitions[3] as InterfaceTypeExtension).name)
        assertEquals(listOf("A", "B"), (doc.definitions[4] as UnionTypeExtension).types.map { it.name })
        assertEquals(listOf("X", "Y"), (doc.definitions[5] as EnumTypeExtension).values.map { it.name })
        assertEquals("k", (doc.definitions[6] as InputObjectTypeExtension).fields.single().name)
    }

    @Test
    fun `parses mutation and subscription operations`() {
        val doc = Parser.parse("mutation M { create } subscription S { events }")
        assertEquals(OperationType.MUTATION, (doc.definitions[0] as OperationDefinition).operation)
        assertEquals(OperationType.SUBSCRIPTION, (doc.definitions[1] as OperationDefinition).operation)
    }

    @Test
    fun `accepts a leading pipe on union members and directive locations`() {
        val union = Parser.parse("union U = | A | B").definitions.single() as UnionTypeDefinition
        assertEquals(listOf("A", "B"), union.types.map { it.name })
        val directive = Parser.parse("directive @d on | FIELD | OBJECT").definitions.single() as DirectiveDefinition
        assertEquals(listOf("FIELD", "OBJECT"), directive.locations)
    }

    @Test
    fun `parses descriptions on fields, arguments, input fields, and enum values`() {
        val doc = Parser.parse(
            """
            type T { "field doc" f("arg doc" a: Int = 1): Int }
            input In { "in doc" k: String }
            enum E { "value doc" A }
            """.trimIndent(),
        )
        val field = (doc.definitions[0] as ObjectTypeDefinition).fields.single()
        assertEquals("field doc", field.description)
        assertEquals("arg doc", field.arguments.single().description)
        assertEquals("in doc", (doc.definitions[1] as InputObjectTypeDefinition).fields.single().description)
        assertEquals("value doc", (doc.definitions[2] as EnumTypeDefinition).values.single().description)
    }

    @Test
    fun `parseValue rejects a non-value token and trailing input`() {
        assertFailsWith<GraphQLSyntaxException> { Parser.parseValue("@") }
        assertFailsWith<GraphQLSyntaxException> { Parser.parseValue("123 extra") }
    }

    @Test
    fun `an empty or comment-only document has no definitions`() {
        assertEquals(0, Parser.parse("").definitions.size)
        assertEquals(0, Parser.parse("# just a comment").definitions.size)
    }

    @Test
    fun `reports the offending token kind in syntax errors`() {
        assertFailsWith<GraphQLSyntaxException> { Parser.parse("{ 123 }") }        // Int where a field name is expected
        assertFailsWith<GraphQLSyntaxException> { Parser.parse("{ \"s\" }") }      // String token
        assertFailsWith<GraphQLSyntaxException> { Parser.parse("fragment X Y { a }") } // missing the `on` keyword
        assertFailsWith<GraphQLSyntaxException> { Parser.parse("type") }           // <EOF> after a keyword
        assertFailsWith<GraphQLSyntaxException> { Parser.parse("\"desc only\"") }  // description with no definition
    }

    @Test
    fun `parses definitions with no body or members`() {
        assertTrue((Parser.parse("type T").definitions.single() as ObjectTypeDefinition).fields.isEmpty())
        assertTrue((Parser.parse("input In").definitions.single() as InputObjectTypeDefinition).fields.isEmpty())
        assertTrue((Parser.parse("enum E").definitions.single() as EnumTypeDefinition).values.isEmpty())
    }

    @Test
    fun `parses a leading ampersand in an implements list`() {
        val t = Parser.parse("type T implements & A & B { x: Int }").definitions.single() as ObjectTypeDefinition
        assertEquals(listOf("A", "B"), t.interfaces.map { it.name })
    }

    @Test
    fun `parses a schema extension with only directives (no operation block)`() {
        val ext = Parser.parse("extend schema @d").definitions.single() as SchemaExtension
        assertEquals("d", ext.directives.single().name)
        assertTrue(ext.operationTypes.isEmpty())
    }

    @Test
    fun `parses a block-string description`() {
        val scalar = Parser.parse("\"\"\"doc\"\"\" scalar S").definitions.single() as ScalarTypeDefinition
        assertEquals("doc", scalar.description)
    }

    @Test
    fun `parseValue handles block strings, the false literal, and a bare variable`() {
        assertTrue((Parser.parseValue("\"\"\"blk\"\"\"") as StringValue).block)
        assertEquals(false, (Parser.parseValue("false") as BooleanValue).value)
        assertEquals("x", (Parser.parseValue("\$x") as Variable).name)
    }

    @Test
    fun `reports more syntax errors`() {
        assertFailsWith<GraphQLSyntaxException> { Parser.parse("{ 1.5 }") }            // Float where a field name is expected
        assertFailsWith<GraphQLSyntaxException> { Parser.parse("@x") }                 // unexpected leading token
        assertFailsWith<GraphQLSyntaxException> { Parser.parse("\"d\" wibble") }       // description before a non-definition
        assertFailsWith<GraphQLSyntaxException> { Parser.parse("schema { foo: Bar }") } // invalid operation type in schema
        assertFailsWith<GraphQLSyntaxException> { Parser.parse("extend wibble") }      // unknown extend target
        assertFailsWith<GraphQLSyntaxException> { Parser.parse("extend @x") }          // non-name after extend
        assertFailsWith<GraphQLSyntaxException> { Parser.parse("{ \"\"\"x\"\"\" }") }  // block string where a field name is expected
    }

    @Test
    fun `parses a union with no members`() {
        val union = Parser.parse("union U @d").definitions.single() as UnionTypeDefinition
        assertTrue(union.types.isEmpty())
    }
}
