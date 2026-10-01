package bosca.graphql.parser

import bosca.graphql.language.SourceLocation

/**
 * A hand-written GraphQL lexer (no graphql-java) implementing the spec's lexical grammar: punctuators,
 * names, int/float values, and string + block-string values. Commas, whitespace, line terminators, the
 * BOM, and `#` comments are insignificant and skipped. Pure Kotlin (no platform APIs) so it runs on
 * every Bosca target.
 */
class Lexer(private val source: String) {
    private var offset = 0
    private var line = 1
    private var lineStart = 0 // offset of the current line's first character (for column math)
    private var tokenLine = 1
    private var tokenColumn = 1
    private var tokenOffset = 0

    internal var tokenKind: TokenKind = TokenKind.EOF
        private set
    internal var tokenValue: String = TokenKind.EOF.display
        private set

    private fun locationAt(at: Int) = SourceLocation(line, at - lineStart + 1, at)
    internal fun currentLocation() = SourceLocation(tokenLine, tokenColumn, tokenOffset)

    private fun peek(ahead: Int = 0): Char {
        val i = offset + ahead
        return if (i < source.length) source[i] else ' '
    }

    private fun isNameStart(c: Char) = c == '_' || c in 'A'..'Z' || c in 'a'..'z'
    private fun isNameContinue(c: Char) = isNameStart(c) || c in '0'..'9'
    private fun isDigit(c: Char) = c in '0'..'9'

    /** Returns the next significant token, or an EOF token at end of input. */
    fun nextToken(): Token {
        advanceToken()
        return Token(tokenKind, tokenValue, currentLocation())
    }

    /**
     * Advances the lexer-owned token cursor without allocating a [Token] or [SourceLocation]. The parser uses this
     * path and materializes locations only for AST nodes and errors; [nextToken] retains the public snapshot API.
     */
    internal fun advanceToken() {
        skipIgnored()
        val start = offset
        tokenLine = line
        tokenColumn = start - lineStart + 1
        tokenOffset = start
        if (offset >= source.length) {
            setToken(TokenKind.EOF)
            return
        }

        when (val c = source[offset]) {
            '!' -> punctuator(TokenKind.BANG)
            '$' -> punctuator(TokenKind.DOLLAR)
            '&' -> punctuator(TokenKind.AMP)
            '(' -> punctuator(TokenKind.PAREN_L)
            ')' -> punctuator(TokenKind.PAREN_R)
            ':' -> punctuator(TokenKind.COLON)
            '=' -> punctuator(TokenKind.EQUALS)
            '@' -> punctuator(TokenKind.AT)
            '[' -> punctuator(TokenKind.BRACKET_L)
            ']' -> punctuator(TokenKind.BRACKET_R)
            '{' -> punctuator(TokenKind.BRACE_L)
            '|' -> punctuator(TokenKind.PIPE)
            '}' -> punctuator(TokenKind.BRACE_R)
            '.' -> readSpread()
            '"' -> readString()
            else -> when {
                isNameStart(c) -> readName(start)
                c == '-' || isDigit(c) -> readNumber(start)
                else -> throw GraphQLSyntaxException("Unexpected character '$c'", currentLocation())
            }
        }
    }

    private fun setToken(kind: TokenKind, value: String = kind.display) {
        tokenKind = kind
        tokenValue = value
    }

    private fun punctuator(kind: TokenKind) {
        offset++
        setToken(kind)
    }

    private fun readSpread() {
        // The caller dispatched on a leading '.', so only the next two characters matter.
        if (peek(1) == '.' && peek(2) == '.') {
            offset += 3
            setToken(TokenKind.SPREAD)
            return
        }
        throw GraphQLSyntaxException("Unexpected character '.'", currentLocation())
    }

    private fun readName(start: Int) {
        offset++
        while (offset < source.length && isNameContinue(source[offset])) offset++
        setToken(TokenKind.NAME, source.substring(start, offset))
    }

    private fun readNumber(start: Int) {
        var isFloat = false
        if (peek() == '-') offset++
        if (peek() == '0') {
            offset++
            if (isDigit(peek())) {
                throw GraphQLSyntaxException("Invalid number, unexpected digit after leading 0: '${peek()}'", locationAt(offset))
            }
        } else {
            requireDigit()
            while (isDigit(peek())) offset++
        }
        if (peek() == '.') {
            isFloat = true
            offset++
            requireDigit()
            while (isDigit(peek())) offset++
        }
        if (peek() == 'e' || peek() == 'E') {
            isFloat = true
            offset++
            if (peek() == '+' || peek() == '-') offset++
            requireDigit()
            while (isDigit(peek())) offset++
        }
        // A number must not run straight into a name or a second '.' (e.g. `1abc`, `1.2.3`).
        val after = peek()
        if (after == '.' || isNameStart(after)) {
            throw GraphQLSyntaxException("Invalid number, expected end of number but got '$after'", locationAt(offset))
        }
        val text = source.substring(start, offset)
        setToken(if (isFloat) TokenKind.FLOAT else TokenKind.INT, text)
    }

    private fun requireDigit() {
        if (!isDigit(peek())) {
            val got = if (offset >= source.length) "<EOF>" else "'${peek()}'"
            throw GraphQLSyntaxException("Invalid number, expected digit but got $got", locationAt(offset))
        }
    }

    private fun readString() {
        // The caller dispatched on a leading '"'; a triple-quote opens a block string.
        if (peek(1) == '"' && peek(2) == '"') return readBlockString()
        offset++ // opening quote
        val sb = StringBuilder()
        while (offset < source.length) {
            when (val c = source[offset]) {
                '"' -> {
                    offset++
                    setToken(TokenKind.STRING, sb.toString())
                    return
                }
                '\\' -> {
                    offset++
                    sb.append(readEscape())
                }
                '\n', '\r' ->
                    throw GraphQLSyntaxException("Unterminated string (line break in string)", currentLocation())
                else -> {
                    // SourceCharacter in a string excludes control characters below U+0020 except tab (§2.9.4).
                    if (c < ' ' && c != '\t') {
                        throw GraphQLSyntaxException(
                            "Invalid character U+${c.code.toString(16).uppercase().padStart(4, '0')} in string",
                            currentLocation(),
                        )
                    }
                    sb.append(c)
                    offset++
                }
            }
        }
        throw GraphQLSyntaxException("Unterminated string", currentLocation())
    }

    private fun readEscape(): String {
        if (offset >= source.length) {
            throw GraphQLSyntaxException("Unterminated escape sequence", currentLocation())
        }
        val c = source[offset]
        offset++
        return when (c) {
            '"' -> "\""
            '\\' -> "\\"
            '/' -> "/"
            'b' -> "\b"
            'f' -> "\u000C"
            'n' -> "\n"
            'r' -> "\r"
            't' -> "\t"
            'u' -> readUnicodeEscape()
            else -> throw GraphQLSyntaxException("Invalid escape sequence '\\$c'", currentLocation())
        }
    }

    private fun readUnicodeEscape(): String {
        // Variable-width form: \u{1F600}
        if (peek() == '{') {
            offset++
            val s = offset
            while (offset < source.length && source[offset] != '}') offset++
            if (offset >= source.length) throw GraphQLSyntaxException("Unterminated unicode escape", currentLocation())
            val hex = source.substring(s, offset)
            offset++ // }
            val code = hex.toIntOrNull(16)
                ?: throw GraphQLSyntaxException("Invalid unicode escape '\\u{$hex}'", currentLocation())
            return codePointToString(code)
        }
        // Fixed-width form: \uXXXX. A leading (high) surrogate combines with an immediately following \uXXXX
        // trailing (low) surrogate into one code point; a lone surrogate is invalid (§2.9.4 StringValue).
        val code = readFixedUnicode()
        if (code in 0xD800..0xDBFF) {
            if (peek() == '\\' && peek(1) == 'u') {
                offset += 2 // consume the trailing "\u"
                val low = readFixedUnicode()
                if (low in 0xDC00..0xDFFF) {
                    return codePointToString(0x10000 + ((code - 0xD800) shl 10) + (low - 0xDC00))
                }
                throw GraphQLSyntaxException(
                    "Invalid surrogate pair: '\\u${low.toString(16)}' is not a trailing surrogate",
                    currentLocation(),
                )
            }
            throw GraphQLSyntaxException(
                "Invalid lone leading surrogate '\\u${code.toString(16)}'",
                currentLocation(),
            )
        }
        if (code in 0xDC00..0xDFFF) {
            throw GraphQLSyntaxException(
                "Invalid lone trailing surrogate '\\u${code.toString(16)}'",
                currentLocation(),
            )
        }
        return code.toChar().toString()
    }

    private fun readFixedUnicode(): Int {
        if (offset + 4 > source.length) throw GraphQLSyntaxException("Invalid unicode escape", currentLocation())
        val hex = source.substring(offset, offset + 4)
        offset += 4
        return hex.toIntOrNull(16)
            ?: throw GraphQLSyntaxException("Invalid unicode escape '\\u$hex'", currentLocation())
    }

    private fun codePointToString(code: Int): String {
        if (code > 0x10FFFF || code in 0xD800..0xDFFF) {
            throw GraphQLSyntaxException("Invalid unicode code point", currentLocation())
        }
        if (code <= 0xFFFF) return code.toChar().toString()
        val c = code - 0x10000
        val high = (0xD800 + (c shr 10)).toChar()
        val low = (0xDC00 + (c and 0x3FF)).toChar()
        return charArrayOf(high, low).concatToString()
    }

    private fun readBlockString() {
        offset += 3 // opening triple quote
        val raw = StringBuilder()
        while (offset < source.length) {
            if (peek() == '"' && peek(1) == '"' && peek(2) == '"') {
                offset += 3
                setToken(TokenKind.BLOCK_STRING, dedentBlockString(raw.toString()))
                return
            }
            if (peek() == '\\' && peek(1) == '"' && peek(2) == '"' && peek(3) == '"') {
                raw.append("\"\"\"")
                offset += 4
                continue
            }
            val c = source[offset]
            if (c == '\n' || c == '\r') {
                // normalize CR, LF, and CRLF to a single '\n'
                raw.append('\n')
                offset++
                if (c == '\r' && peek() == '\n') offset++
                line++
                lineStart = offset
            } else {
                // Like normal strings, block strings exclude control characters below U+0020 except tab (§2.9.5).
                if (c < ' ' && c != '\t') {
                    throw GraphQLSyntaxException(
                        "Invalid character U+${c.code.toString(16).uppercase().padStart(4, '0')} in block string",
                        currentLocation(),
                    )
                }
                raw.append(c)
                offset++
            }
        }
        throw GraphQLSyntaxException("Unterminated block string", currentLocation())
    }

    /** The spec's BlockStringValue algorithm: strip common leading indentation and blank edge lines. */
    private fun dedentBlockString(raw: String): String {
        val lines = raw.split("\n").toMutableList()
        var commonIndent = Int.MAX_VALUE
        for (i in 1 until lines.size) {
            val indent = leadingWhitespace(lines[i])
            if (indent < lines[i].length) commonIndent = minOf(commonIndent, indent)
        }
        if (commonIndent != Int.MAX_VALUE) {
            for (i in 1 until lines.size) {
                lines[i] = if (lines[i].length >= commonIndent) lines[i].substring(commonIndent) else ""
            }
        }
        while (lines.isNotEmpty() && isBlank(lines.first())) lines.removeAt(0)
        while (lines.isNotEmpty() && isBlank(lines.last())) lines.removeAt(lines.size - 1)
        return lines.joinToString("\n")
    }

    private fun leadingWhitespace(s: String): Int {
        var i = 0
        while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
        return i
    }

    private fun isBlank(s: String): Boolean = leadingWhitespace(s) == s.length

    private fun skipIgnored() {
        while (offset < source.length) {
            when (source[offset]) {
                '\uFEFF', ' ', '\t', ',' -> offset++
                '\n' -> {
                    offset++
                    line++
                    lineStart = offset
                }
                '\r' -> {
                    offset++
                    if (peek() == '\n') offset++
                    line++
                    lineStart = offset
                }
                '#' -> {
                    offset++
                    while (offset < source.length && source[offset] != '\n' && source[offset] != '\r') offset++
                }
                else -> return
            }
        }
    }
}
