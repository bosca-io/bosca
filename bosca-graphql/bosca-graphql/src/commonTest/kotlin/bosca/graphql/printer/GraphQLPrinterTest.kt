package bosca.graphql.printer

import bosca.graphql.language.EnumTypeDefinition
import bosca.graphql.language.Field
import bosca.graphql.language.InputObjectTypeDefinition
import bosca.graphql.language.ObjectTypeDefinition
import bosca.graphql.language.ObjectValue
import bosca.graphql.language.OperationDefinition
import bosca.graphql.language.SchemaDefinition
import bosca.graphql.language.SelectionSet
import bosca.graphql.language.StringValue
import bosca.graphql.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the AST printer: exact compact/pretty output for representative constructs, plus
 * round-trip idempotence — `print(parse(print(parse(x))))` is a fixed point — over a corpus of operations and
 * SDL. Idempotence of the canonical form is the practical statement of "parse(print(parse(x))) is AST-equal to
 * parse(x) ignoring source location".
 */
class GraphQLPrinterTest {

    private fun roundTripsCompact(source: String) {
        val once = GraphQLPrinter.printCompact(Parser.parse(source))
        val twice = GraphQLPrinter.printCompact(Parser.parse(once))
        assertEquals(once, twice, "compact not idempotent for: $source")
    }

    private fun roundTripsPretty(source: String) {
        val once = GraphQLPrinter.print(Parser.parse(source))
        val twice = GraphQLPrinter.print(Parser.parse(once))
        assertEquals(once, twice, "pretty not idempotent for: $source")
    }

    private fun bothRoundTrip(source: String) {
        roundTripsCompact(source)
        roundTripsPretty(source)
    }

    // ---- operations ----

    @Test
    fun `compact prints an operation on a single line`() {
        val ast = Parser.parse("query GetUser(\$id: ID!) { user(id: \$id) { id name } }")
        assertEquals("query GetUser(\$id: ID!) { user(id: \$id) { id name } }", GraphQLPrinter.printCompact(ast))
    }

    @Test
    fun `pretty prints an operation multi-line with two-space indent`() {
        val ast = Parser.parse("query GetUser(\$id: ID!) { user(id: \$id) { id name } }")
        assertEquals(
            """
            query GetUser(${'$'}id: ID!) {
              user(id: ${'$'}id) {
                id
                name
              }
            }
            """.trimIndent(),
            GraphQLPrinter.print(ast),
        )
    }

    @Test
    fun `prints aliases, arguments, directives, fragments, and inline fragments`() {
        val source = "query Q(\$x: Int) { a: field(n: \$x) @skip(if: true) { ...Frag ... on User { name } } } " +
            "fragment Frag on Node { id @include(if: false) }"
        assertEquals(source, GraphQLPrinter.printCompact(Parser.parse(source)))
        bothRoundTrip(source)
    }

    @Test
    fun `prints an anonymous query as its selection set (shorthand)`() {
        assertEquals("{ id name }", GraphQLPrinter.printCompact(Parser.parse("{ id name }")))
        assertEquals("query Named { id }", GraphQLPrinter.printCompact(Parser.parse("query Named { id }")))
    }

    @Test
    fun `prints mutations and subscriptions`() {
        bothRoundTrip("mutation M(\$id: ID!) { delete(id: \$id) { ok } }")
        bothRoundTrip("subscription S { ticks }")
    }

    @Test
    fun `prints variable definitions with defaults and directives`() {
        val source = "query Q(\$n: Int = 3, \$s: String @dir) { f(n: \$n, s: \$s) }"
        assertEquals(source, GraphQLPrinter.printCompact(Parser.parse(source)))
    }

    // ---- values ----

    @Test
    fun `prints every value kind`() {
        val source =
            "query Q { f(i: 1, fl: 2.5, s: \"x\", b: true, n: null, e: ACTIVE, l: [1, 2], o: {a: 1, b: \"y\"}) }"
        assertEquals(source, GraphQLPrinter.printCompact(Parser.parse(source)))
        bothRoundTrip(source)
    }

    @Test
    fun `escapes special characters in string values`() {
        val source = "query Q { f(s: \"a\\\"b\\\\c\\nd\\te\") }"
        val printed = GraphQLPrinter.printCompact(Parser.parse(source))
        assertTrue("\\\"" in printed && "\\\\" in printed && "\\n" in printed && "\\t" in printed, printed)
        roundTripsCompact(source)
    }

    @Test
    fun `prints block strings`() {
        val source = "query Q { f(s: \"\"\"hello world\"\"\") }"
        val printed = GraphQLPrinter.printCompact(Parser.parse(source))
        assertTrue("\"\"\"hello world\"\"\"" in printed, printed)
        roundTripsCompact(source)
    }

    @Test
    fun `prints an empty selection set`() {
        assertEquals("{}", GraphQLPrinter.printCompact(SelectionSet(emptyList())))
    }

    @Test
    fun `prints list and non-null type wrappers`() {
        val source = "query Q(\$a: [Int!]!, \$b: [[String]]) { f(a: \$a, b: \$b) }"
        assertEquals(source, GraphQLPrinter.printCompact(Parser.parse(source)))
    }

    // ---- SDL ----

    @Test
    fun `prints SDL object, interface, union, enum, input, scalar, schema, and directive definitions`() {
        val sdl = """
            schema { query: Query mutation: Mutation }
            scalar DateTime
            interface Node { id: ID! }
            type User implements Node { id: ID! name(upper: Boolean = false): String! posts: [Post!]! }
            type Post { id: ID! }
            union Content = User | Post
            enum Status { ACTIVE ARCHIVED }
            input Filter { term: String! limit: Int = 10 }
            directive @auth(role: String!) repeatable on FIELD_DEFINITION | OBJECT
        """.trimIndent()
        bothRoundTrip(sdl)
        // spot-check pretty structure
        val pretty = GraphQLPrinter.print(Parser.parse(sdl))
        assertTrue("type User implements Node {\n  id: ID!" in pretty, pretty)
        assertTrue("union Content = User | Post" in pretty, pretty)
        assertTrue("directive @auth(role: String!) repeatable on FIELD_DEFINITION | OBJECT" in pretty, pretty)
        assertTrue("name(upper: Boolean = false): String!" in pretty, pretty)
    }

    @Test
    fun `prints descriptions on types and fields`() {
        val sdl = """
            "A user."
            type User {
              "The id."
              id: ID!
            }
        """.trimIndent()
        val pretty = GraphQLPrinter.print(Parser.parse(sdl))
        assertTrue("\"A user.\"\ntype User" in pretty, pretty)
        assertTrue("  \"The id.\"\n  id: ID!" in pretty, pretty)
        roundTripsPretty(sdl)
    }

    @Test
    fun `prints type-system extensions`() {
        val sdl = """
            extend schema { subscription: Subscription }
            extend scalar DateTime @dir
            extend type User implements Node @dir { extra: String }
            extend interface Node @dir { extra: String }
            extend union Content = Comment
            extend enum Status @dir { DELETED }
            extend input Filter { sort: String }
        """.trimIndent()
        bothRoundTrip(sdl)
        assertTrue("extend type User implements Node @dir" in GraphQLPrinter.printCompact(Parser.parse(sdl)))
    }

    @Test
    fun `prints a fieldless extension without braces`() {
        val source = "extend type User @dir"
        assertEquals(source, GraphQLPrinter.printCompact(Parser.parse(source)))
    }

    // ---- corpus round-trip ----

    @Test
    fun `round-trips a corpus of operations and SDL`() {
        listOf(
            "{ a b c }",
            "query Q { a { b { c } } }",
            "query Q(\$x: Int!) { f(x: \$x) @d }",
            "mutation M { m(input: {a: 1, b: [true, false]}) { ok } }",
            "fragment F on T { a } query Q { ...F }",
            "type Q { f(a: Int, b: String = \"z\"): [ID!]! @d }",
            "enum E { A B C }",
            "input I { x: Int y: I2 } input I2 { z: String }",
        ).forEach { bothRoundTrip(it) }
    }

    @Test
    fun `pretty selection set nests indentation correctly`() {
        val ast = Parser.parse("{ a { b { c d } } }")
        assertEquals(
            "{\n  a {\n    b {\n      c\n      d\n    }\n  }\n}",
            GraphQLPrinter.print(ast),
        )
    }

    @Test
    fun `prints individual sub-nodes via the public entry point`() {
        val op = Parser.parse("query Q(\$x: [Int!]) { a: f(n: 1, o: {k: \"v\"}) @d(if: true) }")
            .definitions.first() as OperationDefinition
        val variableDefinition = op.variableDefinitions.first()
        assertEquals("\$x: [Int!]", GraphQLPrinter.print(variableDefinition))
        assertEquals("[Int!]", GraphQLPrinter.print(variableDefinition.type))
        val field = op.selectionSet.selections.first() as Field
        assertEquals("a: f(n: 1, o: {k: \"v\"}) @d(if: true)", GraphQLPrinter.print(field))
        assertEquals("n: 1", GraphQLPrinter.print(field.arguments.first()))
        assertEquals("1", GraphQLPrinter.print(field.arguments.first().value))
        assertEquals("k: \"v\"", GraphQLPrinter.print((field.arguments[1].value as ObjectValue).fields.first()))
        assertEquals("@d(if: true)", GraphQLPrinter.print(field.directives.first()))

        val sdl = Parser.parse("type T { f: Int } input I { x: String } enum E { A } schema { query: Query }")
        assertEquals("f: Int", GraphQLPrinter.print((sdl.definitions[0] as ObjectTypeDefinition).fields.first()))
        assertEquals("x: String", GraphQLPrinter.print((sdl.definitions[1] as InputObjectTypeDefinition).fields.first()))
        assertEquals("A", GraphQLPrinter.print((sdl.definitions[2] as EnumTypeDefinition).values.first()))
        assertEquals("query: Query", GraphQLPrinter.print((sdl.definitions[3] as SchemaDefinition).operationTypes.first()))
    }

    @Test
    fun `prints an anonymous operation that has variables`() {
        assertEquals("query(\$x: Int) { f(x: \$x) }", GraphQLPrinter.printCompact(Parser.parse("query (\$x: Int) { f(x: \$x) }")))
    }

    @Test
    fun `prints an inline fragment with no type condition`() {
        val source = "{ x ... @include(if: true) { y } }"
        assertEquals(source, GraphQLPrinter.printCompact(Parser.parse(source)))
    }

    @Test
    fun `prints a description in compact mode and an argument description inline`() {
        assertEquals("\"d\" scalar X", GraphQLPrinter.printCompact(Parser.parse("\"d\" scalar X")))
        assertTrue("\"the arg\" a: Int" in GraphQLPrinter.printCompact(Parser.parse("type T { f(\"the arg\" a: Int): String }")))
    }

    @Test
    fun `prints a union extension with no members`() {
        assertEquals("extend union Content @dir", GraphQLPrinter.printCompact(Parser.parse("extend union Content @dir")))
    }

    @Test
    fun `escapes carriage return, backspace, and form feed`() {
        assertEquals("\"a\\rb\\bc\\fd\"", GraphQLPrinter.print(StringValue("a\rb\bc\u000Cd")))
    }

    @Test
    fun `escapes a triple quote inside a block string`() {
        assertEquals("\"\"\"a\\\"\"\"b\"\"\"", GraphQLPrinter.print(StringValue("a\"\"\"b", block = true)))
    }

    @Test
    fun `prints a field with no sub-selection`() {
        // exercises the Field branch with a null selectionSet via the top-level render of a bare Field
        val field = (Parser.parse("{ lonely }").let {
            (it.definitions.first() as bosca.graphql.language.OperationDefinition).selectionSet.selections.first()
        }) as Field
        assertEquals("lonely", GraphQLPrinter.print(field))
    }
}
