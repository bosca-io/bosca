package bosca.workops.model.bql

/**
 * Hand-rolled BQL tokenizer. Maintains a byte-offset cursor so every
 * emitted [BqlToken] carries the precise source span it covers; the
 * parser propagates those windows onto AST nodes and any
 * downstream error.
 *
 * Whitespace is skipped silently. Reserved words (`AND`, `OR`,
 * `NOT`, `IN`, `IS`, `ORDER`, `BY`, `ASC`, `DESC`, `true`,
 * `false`, `null`) come back as plain identifiers — the parser
 * picks them out by their text content. Comparison operators
 * (`=`, `!=`, `<`, `<=`, `>`, `>=`, `~`) come back as
 * [BqlTokenKind.OPERATOR] tokens whose text is the operator
 * symbol.
 */
class BqlTokenizer(private val source: String) {

    private var cursor = 0

    fun tokenize(): List<BqlToken> {
        val out = mutableListOf<BqlToken>()
        while (true) {
            val token = nextToken()
            out += token
            if (token.kind == BqlTokenKind.EOF) break
        }
        return out
    }

    private fun nextToken(): BqlToken {
        skipWhitespace()
        if (cursor >= source.length) {
            return BqlToken(BqlTokenKind.EOF, "", source.length, source.length)
        }
        val start = cursor
        val ch = source[cursor]
        return when {
            ch == '(' -> single(BqlTokenKind.LPAREN, start)
            ch == ')' -> single(BqlTokenKind.RPAREN, start)
            ch == ',' -> single(BqlTokenKind.COMMA, start)
            ch == '"' -> readString(start)
            ch == '\'' -> readSingleQuoted(start)
            ch == '~' -> single(BqlTokenKind.OPERATOR, start)
            ch == '=' -> single(BqlTokenKind.OPERATOR, start)
            ch == '!' && peek(1) == '=' -> twoChar(BqlTokenKind.OPERATOR, start)
            ch == '<' && peek(1) == '=' -> twoChar(BqlTokenKind.OPERATOR, start)
            ch == '>' && peek(1) == '=' -> twoChar(BqlTokenKind.OPERATOR, start)
            ch == '<' -> single(BqlTokenKind.OPERATOR, start)
            ch == '>' -> single(BqlTokenKind.OPERATOR, start)
            ch == '-' && peek(1)?.isDigit() == true -> readNumber(start)
            ch.isDigit() -> readNumber(start)
            ch.isLetter() || ch == '_' -> readIdentOrKeyword(start)
            else -> {
                // Unknown character — emit it as a one-char token of
                // OPERATOR kind so the parser surfaces a "did not
                // expect …" error with the precise byte offset.
                cursor += 1
                BqlToken(BqlTokenKind.OPERATOR, ch.toString(), start, cursor)
            }
        }
    }

    private fun skipWhitespace() {
        while (cursor < source.length && source[cursor].isWhitespace()) cursor++
    }

    private fun peek(offset: Int): Char? =
        if (cursor + offset < source.length) source[cursor + offset] else null

    private fun single(kind: BqlTokenKind, start: Int): BqlToken {
        cursor += 1
        return BqlToken(kind, source.substring(start, cursor), start, cursor)
    }

    private fun twoChar(kind: BqlTokenKind, start: Int): BqlToken {
        cursor += 2
        return BqlToken(kind, source.substring(start, cursor), start, cursor)
    }

    private fun readString(start: Int): BqlToken {
        // Caller verified source[cursor] == '"'.
        cursor += 1
        val builder = StringBuilder()
        while (cursor < source.length) {
            val ch = source[cursor]
            if (ch == '\\' && cursor + 1 < source.length) {
                builder.append(source[cursor + 1])
                cursor += 2
                continue
            }
            if (ch == '"') {
                cursor += 1
                return BqlToken(BqlTokenKind.STRING, builder.toString(), start, cursor)
            }
            builder.append(ch)
            cursor += 1
        }
        // Unterminated string — emit what we have; the parser surfaces
        // the missing-quote error with the correct span.
        return BqlToken(BqlTokenKind.STRING, builder.toString(), start, cursor)
    }

    private fun readSingleQuoted(start: Int): BqlToken {
        cursor += 1
        val builder = StringBuilder()
        while (cursor < source.length) {
            val ch = source[cursor]
            if (ch == '\\' && cursor + 1 < source.length) {
                builder.append(source[cursor + 1])
                cursor += 2
                continue
            }
            if (ch == '\'') {
                cursor += 1
                return BqlToken(BqlTokenKind.STRING, builder.toString(), start, cursor)
            }
            builder.append(ch)
            cursor += 1
        }
        return BqlToken(BqlTokenKind.STRING, builder.toString(), start, cursor)
    }

    private fun readNumber(start: Int): BqlToken {
        if (source[cursor] == '-') cursor += 1
        while (cursor < source.length && source[cursor].isDigit()) cursor += 1
        if (cursor < source.length && source[cursor] == '.' && peek(1)?.isDigit() == true) {
            cursor += 1
            while (cursor < source.length && source[cursor].isDigit()) cursor += 1
        }
        return BqlToken(BqlTokenKind.NUMBER, source.substring(start, cursor), start, cursor)
    }

    private fun readIdentOrKeyword(start: Int): BqlToken {
        while (cursor < source.length) {
            val ch = source[cursor]
            if (ch.isLetterOrDigit() || ch == '_' || ch == '.') cursor += 1
            else break
        }
        val text = source.substring(start, cursor)
        val kind = when (text.lowercase()) {
            "true", "false" -> BqlTokenKind.BOOL
            "null" -> BqlTokenKind.NULL
            else -> BqlTokenKind.IDENT
        }
        return BqlToken(kind, text, start, cursor)
    }
}
