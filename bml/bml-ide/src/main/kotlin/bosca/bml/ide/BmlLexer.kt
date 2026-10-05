package bosca.bml.ide

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * A fully-restartable lexer for `.bml`. Tags are tokenized into parts (brackets / name /
 * attribute name / `=` / value) for highlighting, while the **injectable** regions —
 * `<script server>`/`<script client>` bodies and `{ … }` interpolation — stay single tokens
 * so the parser can wrap them as injection hosts.
 *
 * All context needed to resume incremental lexing is encoded in the integer state: whether we
 * are reading an open- vs close-tag name, a plain tag's attributes vs a `<script>` tag's (and,
 * within that, whether `server`/`client` has been seen), or a script body. A close tag enters a
 * state that can never open a body, so `</script>` ends a script region instead of restarting it.
 */
class BmlLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var bufferEndOffset = 0
    private var tokenStartOffset = 0
    private var tokenEndOffset = 0
    private var currentState = STATE_DEFAULT
    private var currentToken: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.bufferEndOffset = endOffset
        this.tokenStartOffset = startOffset
        this.tokenEndOffset = startOffset
        this.currentState = initialState
        advance()
    }

    override fun getState(): Int = currentState
    override fun getTokenType(): IElementType? = currentToken
    override fun getTokenStart(): Int = tokenStartOffset
    override fun getTokenEnd(): Int = tokenEndOffset
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = bufferEndOffset

    override fun advance() {
        tokenStartOffset = tokenEndOffset
        if (tokenStartOffset >= bufferEndOffset) {
            currentToken = null
            return
        }
        when (currentState) {
            STATE_SCRIPT_SERVER_BODY -> lexScriptBody(BmlTokens.SCRIPT_SERVER_BODY)
            STATE_SCRIPT_CLIENT_BODY -> lexScriptBody(BmlTokens.SCRIPT_CLIENT_BODY)
            STATE_IN_OPEN_NAME, STATE_IN_CLOSE_NAME -> lexTagName()
            STATE_IN_FLOW -> lexFlow()
            STATE_STYLE_BODY -> lexStyleBody()
            STATE_IN_TAG, STATE_IN_CLOSE_TAG, STATE_IN_TAG_SCRIPT,
            STATE_IN_TAG_SCRIPT_SERVER, STATE_IN_TAG_SCRIPT_CLIENT, STATE_IN_TAG_STYLE,
            STATE_IN_TAG_DIRECTIVE,
            -> lexInTag()
            else -> lexDefault()
        }
    }

    private fun lexScriptBody(type: IElementType) {
        val close = indexOf("</script", tokenStartOffset)
        val end = if (close < 0) bufferEndOffset else close
        currentState = STATE_DEFAULT
        if (end <= tokenStartOffset) {
            // Empty body (`<script server></script>`): no body token; lex the closing tag.
            lexDefault()
            return
        }
        tokenEndOffset = end
        currentToken = type
    }

    /** `<style>` body is raw CSS up to `</style>` — never scanned for `{ … }` interpolation (it would
     *  otherwise inject CSS rule blocks as Kotlin). One [BmlTokens.STYLE_BODY] token; the injector adds CSS. */
    private fun lexStyleBody() {
        val close = indexOf("</style", tokenStartOffset)
        val end = if (close < 0) bufferEndOffset else close
        currentState = STATE_DEFAULT
        if (end <= tokenStartOffset) {
            // Empty body (`<style></style>`): no body token; lex the closing tag.
            lexDefault()
            return
        }
        tokenEndOffset = end
        currentToken = BmlTokens.STYLE_BODY
    }

    private fun lexDefault() {
        val c = buffer[tokenStartOffset]
        when {
            matches("{#", tokenStartOffset) -> {
                val close = indexOf("#}", tokenStartOffset + 2)
                tokenEndOffset = if (close < 0) bufferEndOffset else close + 2
                currentToken = BmlTokens.COMMENT
            }
            matches("<!--", tokenStartOffset) -> {
                val close = indexOf("-->", tokenStartOffset + 4)
                tokenEndOffset = if (close < 0) bufferEndOffset else close + 3
                currentToken = BmlTokens.COMMENT
            }
            matches("</", tokenStartOffset) -> {
                tokenEndOffset = tokenStartOffset + 2
                currentToken = BmlTokens.ANGLE
                currentState = STATE_IN_CLOSE_NAME
            }
            c == '<' -> {
                tokenEndOffset = tokenStartOffset + 1
                currentToken = BmlTokens.ANGLE
                currentState = STATE_IN_OPEN_NAME
            }
            c == '{' -> lexBraces(BmlTokens.INTERPOLATION)
            c.isWhitespace() -> lexWhitespace()
            else -> {
                var i = tokenStartOffset
                while (i < bufferEndOffset && buffer[i] != '<' && buffer[i] != '{') i++
                tokenEndOffset = i
                currentToken = BmlTokens.TEXT
            }
        }
    }

    private fun lexTagName() {
        val c = buffer[tokenStartOffset]
        when {
            c.isWhitespace() -> lexWhitespace() // stay in the name state
            isNameChar(c) -> {
                var i = tokenStartOffset
                while (i < bufferEndOffset && isNameChar(buffer[i])) i++
                tokenEndOffset = i
                val name = buffer.subSequence(tokenStartOffset, tokenEndOffset).toString()
                // BML special/control tags get a keyword color; plain HTML tags stay markup-tag.
                currentToken = if (name in SPECIAL_TAGS) BmlTokens.TAG_KEYWORD else BmlTokens.TAG_NAME
                currentState = when {
                    currentState == STATE_IN_CLOSE_NAME -> STATE_IN_CLOSE_TAG
                    name == "script" -> STATE_IN_TAG_SCRIPT
                    name == "style" -> STATE_IN_TAG_STYLE
                    name in FLOW_TAGS -> STATE_IN_FLOW
                    else -> STATE_IN_TAG
                }
            }
            else -> {
                // No name present (e.g. `<>`); move into attribute mode and re-lex this char.
                currentState = if (currentState == STATE_IN_CLOSE_NAME) STATE_IN_CLOSE_TAG else STATE_IN_TAG
                lexInTag()
            }
        }
    }

    private fun lexInTag() {
        val c = buffer[tokenStartOffset]
        when {
            c.isWhitespace() -> lexWhitespace()
            // A `<` inside a tag means the current tag was never closed (e.g. half-typed `<pr`); recover
            // by abandoning it and re-lexing from this `<` as a new tag, so a following <style>/<script>
            // is still recognized (and its CSS/Kotlin stays correct) instead of being eaten as attributes.
            c == '<' -> { currentState = STATE_DEFAULT; lexDefault() }
            c == '>' -> {
                tokenEndOffset = tokenStartOffset + 1
                currentToken = BmlTokens.ANGLE
                currentState = bodyStateAfterTag()
            }
            c == '/' && peek(tokenStartOffset + 1) == '>' -> {
                tokenEndOffset = tokenStartOffset + 2
                currentToken = BmlTokens.ANGLE
                currentState = STATE_DEFAULT // self-closing: never a body
            }
            c == '=' -> {
                tokenEndOffset = tokenStartOffset + 1
                currentToken = BmlTokens.ATTR_EQ
            }
            c == '"' || c == '\'' -> {
                if (currentState == STATE_IN_TAG_DIRECTIVE) {
                    lexAttrExprValue(c)
                    currentState = STATE_IN_TAG
                } else {
                    lexAttrValue(c)
                }
            }
            c == '{' -> lexBraces(BmlTokens.TAG_EXPR)
            isAttrNameChar(c) -> {
                var i = tokenStartOffset
                while (i < bufferEndOffset && isAttrNameChar(buffer[i])) i++
                tokenEndOffset = i
                val name = buffer.subSequence(tokenStartOffset, tokenEndOffset).toString()
                // `:bound` / `@event` directives — and `t:count`, whose value is the plural count
                // EXPRESSION — get a distinct color; plain attributes don't. Their
                // values are Kotlin, so the next quoted value lexes quote-in-bracket aware
                // (mirrors the compiler's readQuotedExpr) and injects as Kotlin.
                currentToken = if (name.startsWith(":") || name.startsWith("@") || name == "t:count") {
                    if (currentState == STATE_IN_TAG || currentState == STATE_IN_TAG_DIRECTIVE) {
                        currentState = STATE_IN_TAG_DIRECTIVE
                    }
                    BmlTokens.ATTR_DIRECTIVE
                } else {
                    if (currentState == STATE_IN_TAG_DIRECTIVE) currentState = STATE_IN_TAG
                    BmlTokens.ATTR_NAME
                }
                noteScriptKind(name)
            }
            else -> {
                tokenEndOffset = tokenStartOffset + 1
                currentToken = BmlTokens.ATTR_NAME // tolerate a stray char
            }
        }
    }

    /** Track whether a `<script>` tag is server/client; `client` wins if both appear. */
    private fun noteScriptKind(attrName: String) {
        if (currentState == STATE_IN_TAG_SCRIPT || currentState == STATE_IN_TAG_SCRIPT_SERVER ||
            currentState == STATE_IN_TAG_SCRIPT_CLIENT
        ) {
            when (attrName) {
                "client" -> currentState = STATE_IN_TAG_SCRIPT_CLIENT
                "server" -> if (currentState != STATE_IN_TAG_SCRIPT_CLIENT) currentState = STATE_IN_TAG_SCRIPT_SERVER
            }
        }
    }

    private fun bodyStateAfterTag(): Int = when (currentState) {
        STATE_IN_TAG_SCRIPT, STATE_IN_TAG_SCRIPT_SERVER -> STATE_SCRIPT_SERVER_BODY
        STATE_IN_TAG_SCRIPT_CLIENT -> STATE_SCRIPT_CLIENT_BODY
        STATE_IN_TAG_STYLE -> STATE_STYLE_BODY
        else -> STATE_DEFAULT // plain tag or close tag
    }

    private fun lexAttrValue(quote: Char) {
        var i = tokenStartOffset + 1
        while (i < bufferEndOffset && buffer[i] != quote) i++
        if (i < bufferEndOffset) i++ // include the closing quote
        tokenEndOffset = i
        currentToken = BmlTokens.ATTR_VALUE
    }

    /**
     * A `:bound`/`@event` value: a Kotlin expression, so the delimiter quote only closes at
     * bracket depth 0 — `:aria-label="t("nav.close")"` stays one token (mirrors the compiler's
     * readQuotedExpr). Kotlin string/char literals inside are skipped whole.
     */
    private fun lexAttrExprValue(quote: Char) {
        var i = tokenStartOffset + 1
        var depth = 0
        while (i < bufferEndOffset) {
            val ch = buffer[i]
            when {
                ch == '\\' -> i++ // skip the escaped char with the backslash
                ch == quote && depth == 0 -> break
                ch == '"' || ch == '\'' -> { // a Kotlin string/char literal: skip to its end
                    i++
                    while (i < bufferEndOffset && buffer[i] != ch) {
                        if (buffer[i] == '\\') i++
                        i++
                    }
                }
                ch == '(' || ch == '[' || ch == '{' -> depth++
                ch == ')' || ch == ']' || ch == '}' -> if (depth > 0) depth--
            }
            i++
        }
        if (i < bufferEndOffset) i++ // include the closing quote
        tokenEndOffset = i
        currentToken = BmlTokens.ATTR_VALUE
    }

    /**
     * Inside a `<for>`/`<if>`/`<else-if>` tag: capture the control-flow Kotlin expression
     * (`item in listOf(…)` / a condition) as one [BmlTokens.FLOW_EXPR] injection host, up to the
     * tag's `>` (paren/brace/quote-aware, so `>` inside the expression doesn't end the tag early).
     */
    private fun lexFlow() {
        val c = buffer[tokenStartOffset]
        when {
            c.isWhitespace() -> lexWhitespace() // stay in STATE_IN_FLOW
            startsFeatureFlagAttributes(tokenStartOffset) -> {
                // `<if flag="…">` and `<else-if flag="…">` use ordinary attribute tokens so
                // flag/variation are markup and `:when` is a Kotlin-valued directive. A plain
                // `<if flag>` remains a Kotlin FLOW_EXPR for source compatibility.
                currentState = STATE_IN_TAG
                lexInTag()
            }
            c == '>' -> {
                tokenEndOffset = tokenStartOffset + 1
                currentToken = BmlTokens.ANGLE
                currentState = STATE_DEFAULT
            }
            else -> {
                var i = tokenStartOffset
                var depth = 0
                var quote = NO_QUOTE
                while (i < bufferEndOffset) {
                    val ch = buffer[i]
                    if (quote != NO_QUOTE) {
                        if (ch == quote) quote = NO_QUOTE
                    } else when (ch) {
                        '"', '\'' -> quote = ch
                        '(', '{', '[' -> depth++
                        ')', '}', ']' -> depth--
                        '>' -> if (depth <= 0) break
                    }
                    i++
                }
                tokenEndOffset = i
                currentToken = BmlTokens.FLOW_EXPR
                // stay in STATE_IN_FLOW so the trailing `>` is lexed as a closing bracket next.
            }
        }
    }

    private fun startsFeatureFlagAttributes(at: Int): Boolean {
        if (!matches("flag", at)) return false
        var cursor = at + 4
        if (cursor >= bufferEndOffset || (!buffer[cursor].isWhitespace() && buffer[cursor] != '=')) return false
        while (cursor < bufferEndOffset && buffer[cursor].isWhitespace()) cursor++
        return cursor < bufferEndOffset && buffer[cursor] == '=' &&
            (cursor + 1 >= bufferEndOffset || buffer[cursor + 1] != '=')
    }

    /** Consume `{ … }` with brace-depth matching (so lambdas inside don't end it early). */
    private fun lexBraces(type: IElementType) {
        var i = tokenStartOffset + 1
        var depth = 1
        var quote = NO_QUOTE
        while (i < bufferEndOffset && depth > 0) {
            val ch = buffer[i]
            if (quote != NO_QUOTE) {
                if (ch == quote) quote = NO_QUOTE
            } else when (ch) {
                '"', '\'' -> quote = ch
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        tokenEndOffset = i
        currentToken = type
    }

    private fun lexWhitespace() {
        var i = tokenStartOffset
        while (i < bufferEndOffset && buffer[i].isWhitespace()) i++
        tokenEndOffset = i
        currentToken = TokenType.WHITE_SPACE
    }

    private fun peek(at: Int): Char = if (at < bufferEndOffset) buffer[at] else ' '

    /** Tag/attribute name characters: anything that isn't whitespace or tag punctuation (a `<` ends
     *  the current name so a new tag can start — recovery from an unterminated tag). */
    private fun isNameChar(c: Char): Boolean = !c.isWhitespace() && c !in "<>/=\"'{"
    private fun isAttrNameChar(c: Char): Boolean = isNameChar(c)

    private fun matches(s: String, at: Int): Boolean {
        if (at + s.length > bufferEndOffset) return false
        for (k in s.indices) if (buffer[at + k] != s[k]) return false
        return true
    }

    private fun indexOf(s: String, from: Int): Int {
        var i = from
        outer@ while (i + s.length <= bufferEndOffset) {
            for (k in s.indices) if (buffer[i + k] != s[k]) { i++; continue@outer }
            return i
        }
        return -1
    }

    companion object {
        const val STATE_DEFAULT = 0
        const val STATE_IN_OPEN_NAME = 1
        const val STATE_IN_TAG = 2
        const val STATE_IN_TAG_SCRIPT = 3
        const val STATE_SCRIPT_SERVER_BODY = 4
        const val STATE_SCRIPT_CLIENT_BODY = 5
        const val STATE_IN_TAG_SCRIPT_SERVER = 6
        const val STATE_IN_TAG_SCRIPT_CLIENT = 7
        const val STATE_IN_CLOSE_NAME = 8
        const val STATE_IN_CLOSE_TAG = 9
        const val STATE_IN_FLOW = 10
        const val STATE_IN_TAG_STYLE = 11
        const val STATE_STYLE_BODY = 12

        /** In a tag, after a `:bound`/`@event` name: the next quoted value is a Kotlin expression. */
        const val STATE_IN_TAG_DIRECTIVE = 13

        /** BML's special/control/structural tags — highlighted as keywords, distinct from plain HTML. */
        val SPECIAL_TAGS = setOf(
            "page", "route", "template", "component", "slot", "island", "prop", "data", "inject",
            "for", "if", "else", "else-if", "contract", "script", "style",
            // Localization plural forms: `:` is a name char here, so these lex whole.
            "t:zero", "t:one", "t:two", "t:few", "t:many", "t:other",
        )

        /** Control-flow tags whose content (after the name) is a single Kotlin expression to inject. */
        private val FLOW_TAGS = setOf("for", "if", "else-if")
        private const val NO_QUOTE = ' '
    }
}
