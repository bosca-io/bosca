package bosca.graphql.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LexerTest {

    private fun lex(source: String): List<Token> {
        val lexer = Lexer(source)
        val tokens = mutableListOf<Token>()
        while (true) {
            val t = lexer.nextToken()
            if (t.kind == TokenKind.EOF) break
            tokens.add(t)
        }
        return tokens
    }

    private fun kinds(source: String) = lex(source).map { it.kind }

    @Test
    fun `lexes every punctuator`() {
        assertEquals(
            listOf(
                TokenKind.BANG, TokenKind.DOLLAR, TokenKind.AMP, TokenKind.PAREN_L, TokenKind.PAREN_R,
                TokenKind.SPREAD, TokenKind.COLON, TokenKind.EQUALS, TokenKind.AT,
                TokenKind.BRACKET_L, TokenKind.BRACKET_R, TokenKind.BRACE_L, TokenKind.PIPE, TokenKind.BRACE_R,
            ),
            kinds("! $ & ( ) ... : = @ [ ] { | }"),
        )
    }

    @Test
    fun `lexes names`() {
        val t = lex("foo _bar Baz123")
        assertEquals(listOf("foo", "_bar", "Baz123"), t.map { it.value })
        assertTrue(t.all { it.kind == TokenKind.NAME })
    }

    @Test
    fun `lexes int and float values and distinguishes them`() {
        assertEquals(listOf(TokenKind.INT, TokenKind.INT), kinds("0 -42"))
        assertEquals(
            listOf(TokenKind.FLOAT, TokenKind.FLOAT, TokenKind.FLOAT, TokenKind.FLOAT),
            kinds("1.5 -0.25 6.022e23 1e10"),
        )
        assertEquals("-42", lex("-42").single().value)
        assertEquals("6.022e23", lex("6.022e23").single().value)
    }

    @Test
    fun `lexes a string and decodes escapes`() {
        val t = lex(""" "hello\n\t\"world\"A" """).single()
        assertEquals(TokenKind.STRING, t.kind)
        assertEquals("hello\n\t\"world\"A", t.value)
    }

    @Test
    fun `lexes a variable-width unicode escape into an astral code point`() {
        val t = lex(""" "smile \u{1F600}" """).single()
        assertEquals("smile 😀", t.value)
    }

    @Test
    fun `lexes a block string and removes common indentation`() {
        val src = "\"\"\"\n    line one\n      line two\n    line three\n    \"\"\""
        val t = lex(src).single()
        assertEquals(TokenKind.BLOCK_STRING, t.kind)
        assertEquals("line one\n  line two\nline three", t.value)
    }

    @Test
    fun `block string keeps escaped triple-quotes literal`() {
        val t = lex("\"\"\"a \\\"\"\" b\"\"\"").single()
        assertEquals("a \"\"\" b", t.value)
    }

    @Test
    fun `ignores commas, comments, and whitespace`() {
        assertEquals(
            listOf(TokenKind.NAME, TokenKind.NAME),
            kinds("a, # this is a comment\n  b"),
        )
    }

    @Test
    fun `tracks line and column`() {
        val tokens = lex("a\n  bb")
        assertEquals(1, tokens[0].location.line)
        assertEquals(1, tokens[0].location.column)
        assertEquals(2, tokens[1].location.line)
        assertEquals(3, tokens[1].location.column)
    }

    @Test
    fun `rejects an unexpected character`() {
        assertFailsWith<GraphQLSyntaxException> { lex("a % b") }
    }

    @Test
    fun `rejects an unterminated string`() {
        assertFailsWith<GraphQLSyntaxException> { lex("\"no closing quote") }
        assertFailsWith<GraphQLSyntaxException> { lex("\"line\nbreak\"") }
    }

    @Test
    fun `rejects an invalid escape`() {
        assertFailsWith<GraphQLSyntaxException> { lex(""" "bad \x escape" """) }
    }

    @Test
    fun `rejects malformed numbers`() {
        assertFailsWith<GraphQLSyntaxException> { lex("01") }      // leading zero
        assertFailsWith<GraphQLSyntaxException> { lex("1abc") }    // number runs into a name
        assertFailsWith<GraphQLSyntaxException> { lex("1.") }      // missing fractional digits
        assertFailsWith<GraphQLSyntaxException> { lex("-") }       // missing digits
    }
}
