package bosca.bml.ide

import com.intellij.psi.tree.IElementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the lexer isolates exactly the regions language injection relies on: a
 * `<script server>` body as one Kotlin token, a `<script client>` body as one TS token,
 * and `{ … }` interpolation as a single token (braces included, depth-matched).
 */
class BmlLexerTest {

    private fun lex(text: String): List<Pair<IElementType, String>> {
        val lexer = BmlLexer()
        lexer.start(text, 0, text.length, 0)
        val tokens = ArrayList<Pair<IElementType, String>>()
        while (lexer.tokenType != null) {
            tokens += lexer.tokenType!! to text.substring(lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return tokens
    }

    private fun bodyOf(type: IElementType, text: String): String =
        lex(text).first { it.first == type }.second

    @Test
    fun `tokens cover the whole input with no gaps`() {
        val text = "<page route=\"/\">\n  <h1>Hi { name }</h1>\n  {# note #}\n</page>"
        val lexer = BmlLexer()
        lexer.start(text, 0, text.length, 0)
        var cursor = 0
        while (lexer.tokenType != null) {
            assertEquals("no gap before token", cursor, lexer.tokenStart)
            cursor = lexer.tokenEnd
            lexer.advance()
        }
        assertEquals("tokens reach end of input", text.length, cursor)
    }

    @Test
    fun `directive values keep quoted kotlin calls whole, plain values do not change`() {
        // :bound / @event values are Kotlin expressions: the delimiter quote only closes at
        // bracket depth 0, so t("nav.close") stays one ATTR_VALUE.
        val text = """<button :aria-label="t("nav.close")" @click="go("home")" title="I (heart" data-x="y">x</button>"""
        val values = lex(text).filter { it.first == BmlTokens.ATTR_VALUE }.map { it.second }
        assertEquals(
            listOf(
                "\"t(\"nav.close\")\"",
                "\"go(\"home\")\"",
                "\"I (heart\"",
                "\"y\"",
            ),
            values,
        )
    }

    @Test
    fun `t-count is a directive with an expression-aware value and category tags are keywords`() {
        val text = """<span t="cart.items" t:count="pick("a").size"><t:one>x</t:one><t:other>y</t:other></span>"""
        val tokens = lex(text)
        // t:count is Kotlin-valued: directive-colored, and its value keeps the inner call whole.
        assertTrue(tokens.any { it.first == BmlTokens.ATTR_DIRECTIVE && it.second == "t:count" })
        assertTrue(tokens.any { it.first == BmlTokens.ATTR_VALUE && it.second == "\"pick(\"a\").size\"" })
        // The plain t attribute stays a plain attribute; category tags highlight as keywords.
        assertTrue(tokens.any { it.first == BmlTokens.ATTR_NAME && it.second == "t" })
        assertTrue(tokens.any { it.first == BmlTokens.TAG_KEYWORD && it.second == "t:one" })
        assertTrue(tokens.any { it.first == BmlTokens.TAG_KEYWORD && it.second == "t:other" })
    }

    @Test
    fun `directive lexing still covers the whole input with no gaps`() {
        val text = """<a :href="link("x")" class="c">t</a>"""
        val lexer = BmlLexer()
        lexer.start(text, 0, text.length, 0)
        var cursor = 0
        while (lexer.tokenType != null) {
            assertEquals("no gap before token", cursor, lexer.tokenStart)
            cursor = lexer.tokenEnd
            lexer.advance()
        }
        assertEquals("tokens reach end of input", text.length, cursor)
    }

    @Test
    fun `server script body is a single Kotlin-host token`() {
        val text = "<script server provides=\"x\">\n  bosca.query(GetX(id = id)).x\n</script>"
        val body = bodyOf(BmlTokens.SCRIPT_SERVER_BODY, text)
        assertTrue(body.contains("bosca.query(GetX(id = id)).x"))
        assertTrue("body must not include the closing tag", !body.contains("</script"))
    }

    @Test
    fun `client script body is a single TypeScript-host token`() {
        val text = "<script client>\n  import S from \"sortablejs\"\n</script>"
        val body = bodyOf(BmlTokens.SCRIPT_CLIENT_BODY, text)
        assertTrue(body.contains("import S from"))
    }

    @Test
    fun `interpolation is one token with depth-matched braces`() {
        // The lambda's inner braces must not end the interpolation early.
        val text = "<p>{ items.map { it.id } }</p>"
        val interp = bodyOf(BmlTokens.INTERPOLATION, text)
        assertEquals("{ items.map { it.id } }", interp)
    }

    @Test
    fun `bml comment is one comment token`() {
        val text = "{# a comment #}<p>x</p>"
        val comment = bodyOf(BmlTokens.COMMENT, text)
        assertEquals("{# a comment #}", comment)
    }

    @Test
    fun `self-closing script is not treated as a body opener`() {
        val text = "<script src=\"a.js\" />after"
        val types = lex(text).map { it.first }
        assertTrue("no script body emitted", types.none {
            it == BmlTokens.SCRIPT_SERVER_BODY || it == BmlTokens.SCRIPT_CLIENT_BODY
        })
    }

    @Test
    fun `open tag is tokenized into brackets, name, and attribute parts`() {
        val tokens = lex("<page route=\"/\">")
        val types = tokens.map { it.first }
        assertEquals(BmlTokens.ANGLE, types.first())
        assertEquals(BmlTokens.ANGLE, types.last())
        // `page` is a BML special tag -> keyword (plain HTML tags stay TAG_NAME).
        assertEquals("page", tokens.first { it.first == BmlTokens.TAG_KEYWORD }.second)
        assertEquals("route", tokens.first { it.first == BmlTokens.ATTR_NAME }.second)
        assertTrue("has '='", types.contains(BmlTokens.ATTR_EQ))
        assertEquals("\"/\"", tokens.first { it.first == BmlTokens.ATTR_VALUE }.second)
    }

    @Test
    fun `route is lexed as a keyword tag`() {
        val tokens = lex("<route path=\"/feed.json\"></route>")
        assertEquals(listOf("route", "route"), tokens.filter { it.first == BmlTokens.TAG_KEYWORD }.map { it.second })
        assertEquals("path", tokens.first { it.first == BmlTokens.ATTR_NAME }.second)
    }

    @Test
    fun `inject is lexed as a keyword tag`() {
        val tokens = lex("<inject name=\"clock\" type=\"java.time.Clock\" init=\"start\"/>")
        assertTrue(tokens.any { it.first == BmlTokens.TAG_KEYWORD && it.second == "inject" })
    }

    @Test
    fun `close tag keeps its name and never opens a script body`() {
        val tokens = lex("</script>after")
        assertEquals("script", tokens.first { it.first == BmlTokens.TAG_KEYWORD }.second)
        assertTrue("no body opened by a close tag", tokens.none {
            it.first == BmlTokens.SCRIPT_SERVER_BODY || it.first == BmlTokens.SCRIPT_CLIENT_BODY
        })
        assertTrue("text after the close tag survives", tokens.any { it.first == BmlTokens.TEXT && it.second == "after" })
    }

    @Test
    fun `bound and event attribute names plus tag-level spread are tokenized`() {
        val tokens = lex("<a :href=\"u\" @click=\"f\" {...r}>x</a>")
        // `a` is a plain HTML tag (TAG_NAME), while :href / @click are directives (ATTR_DIRECTIVE).
        assertEquals("a", tokens.first { it.first == BmlTokens.TAG_NAME }.second)
        val directives = tokens.filter { it.first == BmlTokens.ATTR_DIRECTIVE }.map { it.second }
        assertTrue(":href tokenized", directives.contains(":href"))
        assertTrue("@click tokenized", directives.contains("@click"))
        assertEquals("{...r}", tokens.first { it.first == BmlTokens.TAG_EXPR }.second)
    }

    @Test
    fun `server script after granular tag tokenization is still one body token`() {
        val text = "<script server>\n  val x = bosca.query(GetX()).x\n</script>"
        val body = bodyOf(BmlTokens.SCRIPT_SERVER_BODY, text)
        assertTrue(body.contains("val x = bosca.query(GetX()).x"))
        // and the closing </script> is tokenized as a normal close tag (script is a keyword tag)
        assertTrue(lex(text).any { it.first == BmlTokens.TAG_KEYWORD && it.second == "script" })
    }

    @Test
    fun `for tag iterable is a single Kotlin flow-expression token`() {
        val tokens = lex("""<for item in listOf("a", "b")>x</for>""")
        assertEquals("""item in listOf("a", "b")""", tokens.first { it.first == BmlTokens.FLOW_EXPR }.second)
        assertTrue("for is a keyword tag", tokens.any { it.first == BmlTokens.TAG_KEYWORD && it.second == "for" })
    }

    @Test
    fun `if tag condition is a single flow-expression token`() {
        val tokens = lex("<if user.active>x</if>")
        assertEquals("user.active", tokens.first { it.first == BmlTokens.FLOW_EXPR }.second)
    }

    @Test
    fun `feature flag if is tokenized as attributes with a kotlin predicate`() {
        val tokens = lex("""<if flag="checkout" variation="compact">x</if>""")
        assertEquals(listOf("flag", "variation"), tokens.filter { it.first == BmlTokens.ATTR_NAME }.map { it.second })
        assertTrue(tokens.none { it.first == BmlTokens.FLOW_EXPR })

        val predicate = lex("""<else-if flag="checkout" :when='flag.variationKey == "compact"'>x</else-if>""")
        assertTrue(predicate.any { it.first == BmlTokens.ATTR_DIRECTIVE && it.second == ":when" })
        assertEquals(
            "'flag.variationKey == \"compact\"'",
            predicate.last { it.first == BmlTokens.ATTR_VALUE }.second,
        )
    }

    @Test
    fun `plain flag variable remains an if flow expression`() {
        val tokens = lex("<if flag>x</if>")
        assertEquals("flag", tokens.first { it.first == BmlTokens.FLOW_EXPR }.second)
    }

    @Test
    fun `flag equality operators remain flow expressions`() {
        listOf("flag==\"control\"", "flag == \"control\"", "flag === other").forEach { expression ->
            val tokens = lex("<if $expression>x</if>")
            assertEquals(expression, tokens.first { it.first == BmlTokens.FLOW_EXPR }.second)
            assertTrue(tokens.none { it.first == BmlTokens.ATTR_NAME && it.second == "flag" })
        }
    }

    @Test
    fun `style body is one raw CSS token, not a kotlin interpolation`() {
        val text = "<style>.count { color: red; }</style>"
        val types = lex(text).map { it.first }
        assertTrue("style body is one STYLE_BODY token", types.contains(BmlTokens.STYLE_BODY))
        // The CSS rule's `{ … }` must NOT be lexed as `{ kotlinExpr }` interpolation (the old bug).
        assertTrue("CSS braces must not become interpolation", !types.contains(BmlTokens.INTERPOLATION))
        assertEquals(".count { color: red; }", bodyOf(BmlTokens.STYLE_BODY, text))
        assertTrue("style is a keyword tag", lex(text).any { it.first == BmlTokens.TAG_KEYWORD && it.second == "style" })
    }

    @Test
    fun `scoped style has its attribute then one raw body token`() {
        val tokens = lex("<style scoped>.x{color:red}</style>")
        assertTrue("scoped attribute", tokens.any { it.first == BmlTokens.ATTR_NAME && it.second == "scoped" })
        assertTrue("single style body", tokens.any { it.first == BmlTokens.STYLE_BODY })
    }

    @Test
    fun `an unterminated tag recovers so a following style tag is still recognized`() {
        // Mid-typing: `<pr` was never closed; the `<style>` below must NOT be eaten as its attributes,
        // and the CSS `{ … }` must NOT become a Kotlin interpolation (A representative screenshot bug).
        val text = "<component tag=\"x\">\n<pr\n<style scoped>.c { color: red; }</style>\n</component>"
        val types = lex(text).map { it.first }
        assertTrue("style body recognized after the broken tag", types.contains(BmlTokens.STYLE_BODY))
        assertTrue("CSS braces are not lexed as interpolation", !types.contains(BmlTokens.INTERPOLATION))
        assertEquals(".c { color: red; }", bodyOf(BmlTokens.STYLE_BODY, text))
    }
}
