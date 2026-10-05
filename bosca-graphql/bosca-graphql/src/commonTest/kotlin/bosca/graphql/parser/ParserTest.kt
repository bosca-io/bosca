package bosca.graphql.parser

import bosca.graphql.language.BooleanValue
import bosca.graphql.language.DirectiveDefinition
import bosca.graphql.language.EnumTypeDefinition
import bosca.graphql.language.EnumValue
import bosca.graphql.language.Field
import bosca.graphql.language.FloatValue
import bosca.graphql.language.FragmentDefinition
import bosca.graphql.language.FragmentSpread
import bosca.graphql.language.InlineFragment
import bosca.graphql.language.InputObjectTypeDefinition
import bosca.graphql.language.IntValue
import bosca.graphql.language.ListType
import bosca.graphql.language.ListValue
import bosca.graphql.language.NamedType
import bosca.graphql.language.NonNullType
import bosca.graphql.language.NullValue
import bosca.graphql.language.ObjectTypeDefinition
import bosca.graphql.language.ObjectTypeExtension
import bosca.graphql.language.ObjectValue
import bosca.graphql.language.OperationDefinition
import bosca.graphql.language.OperationType
import bosca.graphql.language.ScalarTypeDefinition
import bosca.graphql.language.SchemaDefinition
import bosca.graphql.language.StringValue
import bosca.graphql.language.UnionTypeDefinition
import bosca.graphql.language.Variable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserTest {

    @Test
    fun `parses a query shorthand`() {
        val doc = Parser.parse("{ hero name }")
        val op = doc.definitions.single() as OperationDefinition
        assertEquals(OperationType.QUERY, op.operation)
        assertNull(op.name)
        assertEquals(listOf("hero", "name"), op.selectionSet.selections.map { (it as Field).name })
    }

    @Test
    fun `parses a named query with variables, aliases, arguments, and directives`() {
        val doc = Parser.parse(
            """
            query Hero(${'$'}id: ID!, ${'$'}withFriends: Boolean = false) {
              human: character(id: ${'$'}id) @include(if: ${'$'}withFriends) {
                name
              }
            }
            """.trimIndent(),
        )
        val op = doc.definitions.single() as OperationDefinition
        assertEquals(OperationType.QUERY, op.operation)
        assertEquals("Hero", op.name)
        assertEquals(2, op.variableDefinitions.size)
        val idVar = op.variableDefinitions[0]
        assertEquals("id", idVar.variable.name)
        val idType = idVar.type as NonNullType
        assertEquals("ID", (idType.type as NamedType).name)
        val withFriends = op.variableDefinitions[1]
        assertEquals(BooleanValue(false), (withFriends.defaultValue as BooleanValue).copy(location = null))

        val field = op.selectionSet.selections.single() as Field
        assertEquals("human", field.alias)
        assertEquals("character", field.name)
        assertEquals("id", field.arguments.single().name)
        assertEquals("id", (field.arguments.single().value as Variable).name)
        assertEquals("include", field.directives.single().name)
        assertEquals("name", (field.selectionSet!!.selections.single() as Field).name)
    }

    @Test
    fun `parses fragment spreads and inline fragments`() {
        val doc = Parser.parse(
            """
            query {
              ...heroFields
              ... on Droid { primaryFunction }
              ... @skip(if: true) { secret }
            }
            """.trimIndent(),
        )
        val sels = (doc.definitions.single() as OperationDefinition).selectionSet.selections
        assertEquals("heroFields", (sels[0] as FragmentSpread).name)
        val inline = sels[1] as InlineFragment
        assertEquals("Droid", inline.typeCondition!!.name)
        val typeless = sels[2] as InlineFragment
        assertNull(typeless.typeCondition)
        assertEquals("skip", typeless.directives.single().name)
    }

    @Test
    fun `parses a fragment definition`() {
        val doc = Parser.parse("fragment heroFields on Character { name age }")
        val frag = doc.definitions.single() as FragmentDefinition
        assertEquals("heroFields", frag.name)
        assertEquals("Character", frag.typeCondition.name)
        assertEquals(listOf("name", "age"), frag.selectionSet.selections.map { (it as Field).name })
    }

    @Test
    fun `rejects a fragment named on`() {
        assertFailsWith<GraphQLSyntaxException> { Parser.parse("fragment on on Character { name }") }
    }

    @Test
    fun `parses every scalar value kind`() {
        assertEquals("42", (Parser.parseValue("42") as IntValue).value)
        assertEquals("3.14", (Parser.parseValue("3.14") as FloatValue).value)
        assertEquals("hi", (Parser.parseValue("\"hi\"") as StringValue).value)
        assertTrue((Parser.parseValue("true") as BooleanValue).value)
        assertTrue(Parser.parseValue("null") is NullValue)
        assertEquals("ACTIVE", (Parser.parseValue("ACTIVE") as EnumValue).value)
    }

    @Test
    fun `parses list and object values`() {
        val list = Parser.parseValue("[1, 2, 3]") as ListValue
        assertEquals(listOf("1", "2", "3"), list.values.map { (it as IntValue).value })

        val obj = Parser.parseValue("""{ name: "Ada", tags: [true, null] }""") as ObjectValue
        assertEquals(listOf("name", "tags"), obj.fields.map { it.name })
        assertEquals("Ada", (obj.fields[0].value as StringValue).value)
        assertTrue(obj.fields[1].value is ListValue)
    }

    @Test
    fun `rejects a variable inside a constant value`() {
        // default values are constant — a variable there is a syntax error
        assertFailsWith<GraphQLSyntaxException> { Parser.parse("query (${'$'}x: Int = ${'$'}y) { a }") }
    }

    @Test
    fun `parses nested non-null and list type references`() {
        val doc = Parser.parse("query (${'$'}m: [[Int!]!]!) { a }")
        val type = (doc.definitions.single() as OperationDefinition).variableDefinitions.single().type
        // [[Int!]!]!  ->  NonNull(List(NonNull(List(NonNull(Int)))))
        val outer = type as NonNullType
        val outerList = outer.type as ListType
        val midNonNull = outerList.type as NonNullType
        val innerList = midNonNull.type as ListType
        val innerNonNull = innerList.type as NonNullType
        assertEquals("Int", (innerNonNull.type as NamedType).name)
    }

    @Test
    fun `parses an object type definition with descriptions, interfaces, args, defaults, and directives`() {
        val doc = Parser.parse(
            """
            ""${'"'}A character in the saga.""${'"'}
            type Human implements Character & Sentient @key(fields: "id") {
              "The character's name."
              name(uppercase: Boolean = false): String!
              friends: [Character!]
            }
            """.trimIndent(),
        )
        val type = doc.definitions.single() as ObjectTypeDefinition
        assertEquals("Human", type.name)
        assertEquals("A character in the saga.", type.description)
        assertEquals(listOf("Character", "Sentient"), type.interfaces.map { it.name })
        assertEquals("key", type.directives.single().name)

        val name = type.fields[0]
        assertEquals("The character's name.", name.description)
        assertEquals("uppercase", name.arguments.single().name)
        assertEquals(BooleanValue(false), (name.arguments.single().defaultValue as BooleanValue).copy(location = null))
        assertTrue(name.type is NonNullType)

        val friends = type.fields[1]
        assertTrue(friends.type is ListType)
    }

    @Test
    fun `parses scalar, enum, union, input, schema, and directive definitions`() {
        val doc = Parser.parse(
            """
            scalar DateTime @specifiedBy(url: "https://example.com")
            enum Episode { NEWHOPE EMPIRE JEDI }
            union SearchResult = Human | Droid | Starship
            input ReviewInput { stars: Int! commentary: String }
            schema { query: Query mutation: Mutation }
            directive @auth(role: String!) repeatable on FIELD | OBJECT
            """.trimIndent(),
        )
        assertEquals("DateTime", (doc.definitions[0] as ScalarTypeDefinition).name)

        val episode = doc.definitions[1] as EnumTypeDefinition
        assertEquals(listOf("NEWHOPE", "EMPIRE", "JEDI"), episode.values.map { it.name })

        val union = doc.definitions[2] as UnionTypeDefinition
        assertEquals(listOf("Human", "Droid", "Starship"), union.types.map { it.name })

        val input = doc.definitions[3] as InputObjectTypeDefinition
        assertEquals(listOf("stars", "commentary"), input.fields.map { it.name })

        val schema = doc.definitions[4] as SchemaDefinition
        assertEquals(OperationType.QUERY, schema.operationTypes[0].operation)
        assertEquals("Mutation", schema.operationTypes[1].type.name)

        val directive = doc.definitions[5] as DirectiveDefinition
        assertEquals("auth", directive.name)
        assertTrue(directive.repeatable)
        assertEquals(listOf("FIELD", "OBJECT"), directive.locations)
    }

    @Test
    fun `parses a type extension`() {
        val doc = Parser.parse("extend type Query { newField: Int }")
        val ext = doc.definitions.single() as ObjectTypeExtension
        assertEquals("Query", ext.name)
        assertEquals("newField", ext.fields.single().name)
    }

    @Test
    fun `parses a multi-definition document mixing SDL and an operation`() {
        val doc = Parser.parse(
            """
            type Query { me: User }
            type User { id: ID! name: String }
            query Me { me { id name } }
            """.trimIndent(),
        )
        assertEquals(3, doc.definitions.size)
        assertTrue(doc.definitions[0] is ObjectTypeDefinition)
        assertTrue(doc.definitions[2] is OperationDefinition)
    }

    @Test
    fun `reports an error with location on a missing closing brace`() {
        val ex = assertFailsWith<GraphQLSyntaxException> { Parser.parse("{ a b ") }
        assertTrue(ex.message!!.contains("line"), ex.message)
    }

    @Test
    fun `preserves independent AST locations while advancing the lexer cursor`() {
        val operation = Parser.parse(
            """
            {
              first
              alias: second(arg: 42)
            }
            """.trimIndent(),
        ).definitions.single() as OperationDefinition
        val first = operation.selectionSet.selections[0] as Field
        val second = operation.selectionSet.selections[1] as Field
        val argument = second.arguments.single()
        val value = argument.value as IntValue
        val operationLocation = requireNotNull(operation.location)
        val firstLocation = requireNotNull(first.location)
        val secondLocation = requireNotNull(second.location)
        val argumentLocation = requireNotNull(argument.location)
        val valueLocation = requireNotNull(value.location)

        assertEquals(listOf(1, 1), listOf(operationLocation.line, operationLocation.column))
        assertEquals(listOf(2, 3), listOf(firstLocation.line, firstLocation.column))
        assertEquals(listOf(3, 3), listOf(secondLocation.line, secondLocation.column))
        assertEquals(listOf(3, 17), listOf(argumentLocation.line, argumentLocation.column))
        assertEquals(listOf(3, 22), listOf(valueLocation.line, valueLocation.column))
    }

    @Test
    fun `rejects an unknown top-level keyword`() {
        assertFailsWith<GraphQLSyntaxException> { Parser.parse("wibble Foo { a }") }
    }

    @Test
    fun `parser limits bound token work for documents and values`() {
        assertEquals(1, Parser.parse("{ a }", ParserLimits(maxTokens = 3, maxNestingDepth = 10)).definitions.size)
        assertEquals("1", (Parser.parseValue("[1]", ParserLimits(maxTokens = 3, maxNestingDepth = 10)) as ListValue)
            .values.single().let { (it as IntValue).value })
        val documentError = assertFailsWith<GraphQLSyntaxException> {
            Parser.parse("{ a }", ParserLimits(maxTokens = 2, maxNestingDepth = 10))
        }
        assertTrue(documentError.reason.contains("maximum token count of 2"))

        val valueError = assertFailsWith<GraphQLSyntaxException> {
            Parser.parseValue("[1, 2]", ParserLimits(maxTokens = 3, maxNestingDepth = 10))
        }
        assertTrue(valueError.reason.contains("maximum token count of 3"))
    }

    @Test
    fun `default parser limit accepts documents well beyond the legacy token ceiling`() {
        val fields = List(60_000) { index -> "field$index" }
        val operation = Parser.parse("{ ${fields.joinToString(" ")} }")
            .definitions.single() as OperationDefinition

        assertEquals(60_000, operation.selectionSet.selections.size)
        assertEquals(1_000_000, ParserLimits.DEFAULT.maxTokens)
        assertEquals(512, ParserLimits.DEFAULT.maxNestingDepth)
    }

    @Test
    fun `parser limits bound recursive selection value and type nesting`() {
        val selectionError = assertFailsWith<GraphQLSyntaxException> {
            Parser.parse("{ a { b } }", ParserLimits(maxTokens = 20, maxNestingDepth = 1))
        }
        assertTrue(selectionError.reason.contains("maximum nesting depth of 1"))

        val valueError = assertFailsWith<GraphQLSyntaxException> {
            Parser.parseValue("[[1]]", ParserLimits(maxTokens = 20, maxNestingDepth = 1))
        }
        assertTrue(valueError.reason.contains("maximum nesting depth of 1"))

        val typeError = assertFailsWith<GraphQLSyntaxException> {
            Parser.parse("query (\$v: [[Int]]) { a }", ParserLimits(maxTokens = 20, maxNestingDepth = 1))
        }
        assertTrue(typeError.reason.contains("maximum nesting depth of 1"))
    }

    @Test
    fun `parser limits require positive bounds`() {
        assertFailsWith<IllegalArgumentException> { ParserLimits(maxTokens = 0) }
        assertFailsWith<IllegalArgumentException> { ParserLimits(maxNestingDepth = 0) }
    }
}
