package bosca.graphql.parser

import bosca.graphql.language.Document
import bosca.graphql.language.Field
import bosca.graphql.language.OperationDefinition
import bosca.graphql.language.StringValue
import bosca.graphql.printer.GraphQLPrinter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Edge-case coverage of the lexer (block strings), the value parser, and the printer's leaf-field path. */
class LexerParserPrinterCoverageTest {

    private fun stringValueOf(query: String): StringValue {
        val op = Parser.parse(query).definitions.filterIsInstance<OperationDefinition>().first()
        val field = op.selectionSet.selections.filterIsInstance<Field>().first()
        return field.arguments.first().value as StringValue
    }

    @Test
    fun `a block string exercises every lexer arm`() {
        val tq = "\"\"\"" // a GraphQL triple-quote delimiter
        // content hits, in order: a lone `"`, a `""`, a `\"` (backslash + 1 quote), a `\""` (backslash + 2 quotes),
        // a lone `\`, a lone CR (no LF), a standalone LF, a CRLF, an escaped `\"""`, and ordinary characters.
        val source = "{ f(s: ${tq}a\"b \"\"c \\\"q \\\"\"r back\\slash X\rY p\nq mid\r\nmore \\$tq tail$tq) }"
        val value = stringValueOf(source)
        assertTrue(value.block)
        assertTrue(value.value.contains(tq), "escaped triple-quote should unescape: ${value.value}")
        assertTrue(value.value.contains("tail") && value.value.contains("mid") && value.value.contains("back\\slash"))
    }

    @Test
    fun `an unterminated block string fails`() {
        val tq = "\"\"\""
        assertFailsWith<GraphQLSyntaxException> { Parser.parse("{ f(s: ${tq}never closed) }") }
    }

    @Test
    fun `a simple block string round-trips`() {
        assertEquals("hello", stringValueOf("{ f(s: \"\"\"hello\"\"\") }").value)
    }

    @Test
    fun `each block-string line ending normalizes to a newline`() {
        val tq = "\"\"\""
        assertEquals("a\nb", stringValueOf("{ f(s: ${tq}a\r\nb$tq) }").value) // CRLF
        assertEquals("a\nb", stringValueOf("{ f(s: ${tq}a\rb$tq) }").value) // lone CR
        assertEquals("a\nb", stringValueOf("{ f(s: ${tq}a\nb$tq) }").value) // lone LF
        // vertical tab (U+000B) and form feed (U+000C) are not valid source characters → rejected
        assertFailsWith<GraphQLSyntaxException> { stringValueOf("{ f(s: ${tq}a\u000Bc$tq) }") }
        assertFailsWith<GraphQLSyntaxException> { stringValueOf("{ f(s: ${tq}a\u000Cc$tq) }") }
    }

    @Test
    fun `the printer escapes control characters so output re-parses`() {
        val printed = GraphQLPrinter.printCompact(StringValue("a\u0001b"))
        assertTrue("\\u0001" in printed, printed)
    }

    @Test
    fun `constant default values of every kind parse`() {
        val tq = "\"\"\""
        Parser.parse(
            "query Q(\$i: Int = 1, \$fl: Float = 1.5, \$s: String = \"x\", \$bs: String = ${tq}b$tq, " +
                "\$t: Boolean = true, \$n: Int = null, \$e: E = ENUM_V, \$l: [Int] = [1, 2], \$o: In = { k: 1 }) { f }",
        )
    }

    @Test
    fun `every value kind parses, including a variable`() {
        val doc = Parser.parse(
            "query Q(\$v: Int) { f(i: 1, fl: 1.5, s: \"x\", bs: \"\"\"b\"\"\", t: true, fa: false, n: null, e: ENUM_V, l: [1, 2], o: { k: 1 }, var: \$v) }",
        )
        val args = (doc.definitions.first() as OperationDefinition).selectionSet.selections.filterIsInstance<Field>().first().arguments
        assertEquals(setOf("i", "fl", "s", "bs", "t", "fa", "n", "e", "l", "o", "var"), args.map { it.name }.toSet())
    }

    @Test
    fun `a variable in a constant position fails`() {
        // default values are constant; a variable there is rejected by parseValue's const guard
        assertFailsWith<GraphQLSyntaxException> { Parser.parse("query Q(\$x: Int = \$y) { f }") }
    }

    @Test
    fun `an unexpected token in value position fails`() {
        // a non-value token where a value is expected hits parseValue's else branch
        assertFailsWith<GraphQLSyntaxException> { Parser.parse("{ f(x: ) }") }
    }

    @Test
    fun `end-of-input where a value is expected fails`() {
        // the input ends right where parseValue expects a value (an EOF token reaches parseValue)
        assertFailsWith<GraphQLSyntaxException> { Parser.parse("{ f(x: ") }
    }

    @Test
    fun `the printer renders aliases, leaf fields, and sub-selections`() {
        assertEquals("{ a: leaf parent { child } }", GraphQLPrinter.printCompact(Parser.parse("{ a: leaf parent { child } }")))
        assertEquals("{ leaf }", GraphQLPrinter.printCompact(Parser.parse("{ leaf }")))
    }

    @Test
    fun `parse and print round-trip an operation with variables and directives`() {
        val source = "query Q(\$x: Int = 1) @dir { a(p: \$x) @skip(if: false) { b } }"
        val doc: Document = Parser.parse(source)
        // print is canonical and idempotent
        assertEquals(GraphQLPrinter.print(doc), GraphQLPrinter.print(Parser.parse(GraphQLPrinter.print(doc))))
    }
}
