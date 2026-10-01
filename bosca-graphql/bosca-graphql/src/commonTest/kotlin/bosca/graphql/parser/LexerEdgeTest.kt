package bosca.graphql.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LexerEdgeTest {

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

    @Test
    fun `empty input yields no tokens`() {
        assertTrue(lex("").isEmpty())
        assertTrue(lex("   # only a comment, no newline").isEmpty())
    }

    @Test
    fun `skips a leading byte order mark`() {
        val t = lex("\uFEFFname")
        assertEquals(listOf("name"), t.map { it.value })
    }

    @Test
    fun `normalizes CRLF inside a block string and dedents`() {
        val t = lex("\"\"\"\r\n  a\r\n  b\r\n  \"\"\"").single()
        assertEquals(TokenKind.BLOCK_STRING, t.kind)
        assertEquals("a\nb", t.value)
    }

    @Test
    fun `rejects an unterminated block string`() {
        assertFailsWith<GraphQLSyntaxException> { lex("\"\"\"never closed") }
    }

    @Test
    fun `rejects a non-hex fixed-width unicode escape`() {
        assertFailsWith<GraphQLSyntaxException> { lex(""" "\u12zz" """) }
    }

    @Test
    fun `rejects an out-of-range variable-width unicode escape`() {
        assertFailsWith<GraphQLSyntaxException> { lex(""" "\u{110000}" """) }
    }

    @Test
    fun `lexes signed exponents and negative zero`() {
        assertEquals(TokenKind.FLOAT, lex("1.0e+5").single().kind)
        assertEquals(TokenKind.FLOAT, lex("2E-3").single().kind)
        assertEquals("-0", lex("-0").single().value)
    }

    @Test
    fun `decodes the remaining single-char escapes`() {
        // raw Kotlin string -> the lexer receives the literal escape sequences \\ \/ \r \b \f
        val t = lex(""" "x\\y\/z\r\b\f" """).single()
        val expected = "x\\y/z" + Char(13) + Char(8) + Char(12)
        assertEquals(expected, t.value)
    }

    @Test
    fun `rejects a lone or doubled dot`() {
        assertFailsWith<GraphQLSyntaxException> { lex(".") }
        assertFailsWith<GraphQLSyntaxException> { lex("..") }
    }

    @Test
    fun `rejects an unterminated escape and unterminated variable-width unicode`() {
        assertFailsWith<GraphQLSyntaxException> { lex("\"abc\\") }
        assertFailsWith<GraphQLSyntaxException> { lex(""" "\u{1F" """) }
    }

    @Test
    fun `reports the non-digit that ends a malformed fraction`() {
        assertFailsWith<GraphQLSyntaxException> { lex("0.x") }
    }

    @Test
    fun `decodes a fixed-width unicode escape`() {
        assertEquals("A", lex(""" "\u0041" """).single().value)
    }

    @Test
    fun `treats tab, CR, CRLF, and CR-terminated comments as ignorable`() {
        assertEquals(listOf("a", "b"), lex("a\tb").map { it.value })
        assertEquals(listOf("a", "b"), lex("a\rb").map { it.value })
        assertEquals(listOf("a", "b"), lex("a\r\nb").map { it.value })
        assertEquals(listOf("a", "b"), lex("a # comment\r b").map { it.value })
    }

    @Test
    fun `rejects a number that runs into a second dot`() {
        assertFailsWith<GraphQLSyntaxException> { lex("1.2.3") }
    }

    @Test
    fun `lexes an empty string`() {
        val t = lex("\"\"").single()
        assertEquals(TokenKind.STRING, t.kind)
        assertEquals("", t.value)
    }

    @Test
    fun `rejects a carriage return inside a normal string`() {
        assertFailsWith<GraphQLSyntaxException> { lex("\"a\rb\"") }
    }

    @Test
    fun `dedents block strings across tabs, lone CRs, blank middle lines, and single lines`() {
        assertEquals("a\nb", lex("\"\"\"\n\ta\n\tb\n\"\"\"").single().value)
        assertEquals("a\nb", lex("\"\"\"a\rb\"\"\"").single().value)
        assertEquals("a\n\nb", lex("\"\"\"\n    a\n  \n    b\n    \"\"\"").single().value)
        assertEquals("hello", lex("\"\"\"hello\"\"\"").single().value)
    }

    @Test
    fun `decodes an astral variable-width unicode escape as a surrogate pair`() {
        assertEquals(2, lex(""" "\u{1F600}" """).single().value.length)
    }

    @Test
    fun `rejects malformed unicode escapes`() {
        assertFailsWith<GraphQLSyntaxException> { lex(""" "\u{zz}" """) }
        assertFailsWith<GraphQLSyntaxException> { lex(""" "\u1" """) }
    }

    @Test
    fun `decodes a variable-width unicode escape inside the BMP`() {
        assertEquals("A", lex(""" "\u{41}" """).single().value)
    }

    @Test
    fun `combines a fixed-width surrogate pair and rejects lone or invalid surrogates`() {
        assertEquals("😀", lex(""" "\uD83D\uDE00" """).single().value) // valid escaped pair
        assertFailsWith<GraphQLSyntaxException> { lex(""" "\uD83Dx" """) } // lone leading: no following escape
        assertFailsWith<GraphQLSyntaxException> { lex(""" "\uD83D\t" """) } // leading + a non-\u escape
        assertFailsWith<GraphQLSyntaxException> { lex(""" "\uD83D\u0041" """) } // leading + low below the trailing range
        assertFailsWith<GraphQLSyntaxException> { lex(""" "\uD83D\uE000" """) } // leading + low above the trailing range
        assertFailsWith<GraphQLSyntaxException> { lex(""" "\uDE00" """) } // lone trailing surrogate
        assertFailsWith<GraphQLSyntaxException> { lex(""" "\u{D800}" """) } // a surrogate code point via \u{...}
        assertEquals("", lex(""" "\uE000" """).single().value) // a non-surrogate code point above the trailing range
    }

    @Test
    fun `rejects an unescaped control character but allows a tab in a string`() {
        assertFailsWith<GraphQLSyntaxException> { lex(" \"a\u0001b\" ") } // U+0001 is not a valid source character
        assertEquals("a\tb", lex(" \"a\tb\" ").single().value) // a tab (U+0009) is allowed
    }

    @Test
    fun `dedents a whitespace-only block string to empty`() {
        assertEquals("", lex("\"\"\"   \"\"\"").single().value)
    }

    @Test
    fun `rejects a backslash outside a string`() {
        assertFailsWith<GraphQLSyntaxException> { lex("\\") }
    }

    @Test
    fun `keeps single quotes and lone backslashes literal inside a block string`() {
        assertEquals("a\"b", lex("\"\"\"a\"b\"\"\"").single().value)
        assertEquals("a\\b", lex("\"\"\"a\\b\"\"\"").single().value)
    }

    @Test
    fun `rejects characters just past the name ranges`() {
        assertFailsWith<GraphQLSyntaxException> { lex("~") }
        assertFailsWith<GraphQLSyntaxException> { lex("\\") }
    }
}
