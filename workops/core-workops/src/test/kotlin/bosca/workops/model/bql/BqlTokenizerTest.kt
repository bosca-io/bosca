package bosca.workops.model.bql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the BQL tokenizer. Pins the lexing contract so the
 * parser can rely on exact token kinds and byte-offset windows.
 */
class BqlTokenizerTest {

    private fun tokenize(src: String): List<BqlToken> = BqlTokenizer(src).tokenize()

    private fun kinds(src: String): List<BqlTokenKind> = tokenize(src).map { it.kind }

    @Test
    fun `empty input produces only EOF`() {
        val tokens = tokenize("")
        assertEquals(1, tokens.size)
        assertEquals(BqlTokenKind.EOF, tokens.single().kind)
    }

    @Test
    fun `whitespace-only input produces only EOF`() {
        val tokens = tokenize("   \t\n  ")
        assertEquals(1, tokens.size)
        assertEquals(BqlTokenKind.EOF, tokens.single().kind)
    }

    @Test
    fun `bare identifier`() {
        val tokens = tokenize("status")
        assertEquals(2, tokens.size)
        assertEquals(BqlTokenKind.IDENT, tokens[0].kind)
        assertEquals("status", tokens[0].text)
        assertEquals(0, tokens[0].start)
        assertEquals(6, tokens[0].end)
    }

    @Test
    fun `dotted identifier stays as one token`() {
        val tokens = tokenize("custom.field")
        assertEquals(BqlTokenKind.IDENT, tokens[0].kind)
        assertEquals("custom.field", tokens[0].text)
    }

    @Test
    fun `comparison operators`() {
        val ops = listOf("=" to "=", "!=" to "!=", "<" to "<", "<=" to "<=", ">" to ">", ">=" to ">=", "~" to "~")
        for ((src, expected) in ops) {
            val tokens = tokenize(src)
            assertEquals(BqlTokenKind.OPERATOR, tokens[0].kind, "expected OPERATOR for '$src'")
            assertEquals(expected, tokens[0].text, "operator text mismatch for '$src'")
        }
    }

    @Test
    fun `double-quoted string literal`() {
        val tokens = tokenize("\"hello world\"")
        assertEquals(BqlTokenKind.STRING, tokens[0].kind)
        assertEquals("hello world", tokens[0].text)
    }

    @Test
    fun `single-quoted string literal`() {
        val tokens = tokenize("'hello world'")
        assertEquals(BqlTokenKind.STRING, tokens[0].kind)
        assertEquals("hello world", tokens[0].text)
    }

    @Test
    fun `unterminated double-quoted string emits what was consumed`() {
        val tokens = tokenize("\"oops")
        assertEquals(BqlTokenKind.STRING, tokens[0].kind)
        assertEquals("oops", tokens[0].text)
        // The token should span from the opening quote to end-of-input.
        assertEquals(0, tokens[0].start)
        assertEquals(5, tokens[0].end)
    }

    @Test
    fun `unterminated single-quoted string emits what was consumed`() {
        val tokens = tokenize("'oops")
        assertEquals(BqlTokenKind.STRING, tokens[0].kind)
        assertEquals("oops", tokens[0].text)
    }

    @Test
    fun `integer number`() {
        val tokens = tokenize("42")
        assertEquals(BqlTokenKind.NUMBER, tokens[0].kind)
        assertEquals("42", tokens[0].text)
    }

    @Test
    fun `decimal number`() {
        val tokens = tokenize("3.14")
        assertEquals(BqlTokenKind.NUMBER, tokens[0].kind)
        assertEquals("3.14", tokens[0].text)
    }

    @Test
    fun `negative number`() {
        val tokens = tokenize("-7")
        assertEquals(BqlTokenKind.NUMBER, tokens[0].kind)
        assertEquals("-7", tokens[0].text)
    }

    @Test
    fun `negative decimal number`() {
        val tokens = tokenize("-12.5")
        assertEquals(BqlTokenKind.NUMBER, tokens[0].kind)
        assertEquals("-12.5", tokens[0].text)
    }

    @Test
    fun `boolean literals`() {
        val trueTokens = tokenize("true")
        assertEquals(BqlTokenKind.BOOL, trueTokens[0].kind)
        assertEquals("true", trueTokens[0].text)

        val falseTokens = tokenize("false")
        assertEquals(BqlTokenKind.BOOL, falseTokens[0].kind)
        assertEquals("false", falseTokens[0].text)
    }

    @Test
    fun `null literal`() {
        val tokens = tokenize("null")
        assertEquals(BqlTokenKind.NULL, tokens[0].kind)
        assertEquals("null", tokens[0].text)
    }

    @Test
    fun `parentheses and comma`() {
        val tokens = tokenize("(a, b)")
        assertEquals(
            listOf(BqlTokenKind.LPAREN, BqlTokenKind.IDENT, BqlTokenKind.COMMA, BqlTokenKind.IDENT, BqlTokenKind.RPAREN, BqlTokenKind.EOF),
            kinds("(a, b)"),
        )
        assertEquals("(", tokens[0].text)
        assertEquals("a", tokens[1].text)
        assertEquals(",", tokens[2].text)
        assertEquals("b", tokens[3].text)
        assertEquals(")", tokens[4].text)
    }

    @Test
    fun `keywords come back as IDENT`() {
        // Reserved words like AND, OR, NOT, IN, IS, ORDER, BY are plain IDENT tokens.
        val keywords = listOf("AND", "OR", "NOT", "IN", "IS", "ORDER", "BY", "ASC", "DESC")
        for (kw in keywords) {
            val tokens = tokenize(kw)
            assertEquals(BqlTokenKind.IDENT, tokens[0].kind, "expected IDENT for keyword '$kw'")
            assertEquals(kw, tokens[0].text)
        }
    }

    @Test
    fun `full expression tokenizes correctly`() {
        val src = "status = Done AND priority != Low"
        val expected = listOf(
            BqlTokenKind.IDENT,    // status
            BqlTokenKind.OPERATOR, // =
            BqlTokenKind.IDENT,    // Done
            BqlTokenKind.IDENT,    // AND
            BqlTokenKind.IDENT,    // priority
            BqlTokenKind.OPERATOR, // !=
            BqlTokenKind.IDENT,    // Low
            BqlTokenKind.EOF,
        )
        assertEquals(expected, kinds(src))
    }

    @Test
    fun `byte offsets are accurate across whitespace`() {
        val src = "  abc  =  123  "
        val tokens = tokenize(src)
        // "abc" starts at 2, ends at 5
        assertEquals(2, tokens[0].start)
        assertEquals(5, tokens[0].end)
        // "=" starts at 7, ends at 8
        assertEquals(7, tokens[1].start)
        assertEquals(8, tokens[1].end)
        // "123" starts at 10, ends at 13
        assertEquals(10, tokens[2].start)
        assertEquals(13, tokens[2].end)
    }

    @Test
    fun `escaped character in double-quoted string`() {
        val tokens = tokenize("\"say \\\"hello\\\"\"")
        assertEquals(BqlTokenKind.STRING, tokens[0].kind)
        assertEquals("say \"hello\"", tokens[0].text)
    }

    @Test
    fun `unknown character emits OPERATOR token for parser error reporting`() {
        val tokens = tokenize("@")
        assertEquals(BqlTokenKind.OPERATOR, tokens[0].kind)
        assertEquals("@", tokens[0].text)
        assertEquals(0, tokens[0].start)
        assertEquals(1, tokens[0].end)
    }
}
