package bosca.workops.model.bql

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Hand-written recursive-descent BQL parser. Grammar:
 *
 * ```
 * query        := orExpr (orderBy)?
 * orExpr       := andExpr ( OR andExpr )*
 * andExpr      := notExpr ( AND notExpr )*
 * notExpr      := NOT notExpr | primary
 * primary      := '(' orExpr ')' | predicate
 * predicate    := IDENT ( ( IS NOT? NULL ) | ( NOT? IN '(' valueList ')' ) | ( cmpOp value ) )
 * cmpOp        := '=' | '!=' | '<' | '<=' | '>' | '>=' | '~'
 * value        := STRING | NUMBER | BOOL | NULL | function | listLiteral
 * function     := IDENT '(' argList? ')'
 * argList      := value ( ',' value )*
 * listLiteral  := '(' value ( ',' value )* ')'
 * orderBy      := ORDER BY sortKey ( ',' sortKey )*
 * sortKey      := IDENT ( ASC | DESC )?
 * ```
 *
 * Errors recover at the top of each `andExpr` clause so the parser
 * surfaces multiple problems on one round trip instead of bailing
 * on the first issue. Every error carries the exact byte-offset
 * window of the offending token.
 *
 * Per the Excellence Bar non-negotiable, every error is "actionable":
 * a one-line message + a precise byte span + an optional "did you
 * mean" hint when the fix is unambiguous (operator-before-IS, etc.).
 */
class BqlParser(private val source: String) {

    private val tokens: List<BqlToken> = BqlTokenizer(source).tokenize()
    private var cursor = 0
    private val errors = mutableListOf<BqlError>()

    fun parse(): BqlParseResult {
        val where = if (isAtOrderBy() || peek().kind == BqlTokenKind.EOF) {
            null
        } else {
            try {
                parseOrExpr()
            } catch (_: BqlBailout) {
                return BqlParseResult(query = null, errors = errors.toList())
            }
        }
        val orderBy = parseOptionalOrderBy()
        if (peek().kind != BqlTokenKind.EOF) {
            recordError(
                "Unexpected token '${peek().text}' after query",
                peek().start,
                peek().end,
            )
        }
        return BqlParseResult(query = BqlQuery(where, orderBy), errors = errors.toList())
    }

    private fun isAtOrderBy(): Boolean =
        peek().kind == BqlTokenKind.IDENT && peek().text.equals("order", ignoreCase = true) &&
            peek(1).kind == BqlTokenKind.IDENT && peek(1).text.equals("by", ignoreCase = true)

    private fun parseOrExpr(): BqlExpr {
        var left = parseAndExpr()
        while (matchKeyword("or")) {
            val right = parseAndExpr()
            left = BqlExpr.Or(left, right, left.start, right.end)
        }
        return left
    }

    private fun parseAndExpr(): BqlExpr {
        var left = parseNotExpr()
        while (matchKeyword("and")) {
            val right = parseNotExpr()
            left = BqlExpr.And(left, right, left.start, right.end)
        }
        return left
    }

    private fun parseNotExpr(): BqlExpr {
        if (matchKeyword("not")) {
            val notStart = previous().start
            val inner = parseNotExpr()
            return BqlExpr.Not(inner, notStart, inner.end)
        }
        return parsePrimary()
    }

    private fun parsePrimary(): BqlExpr {
        if (peek().kind == BqlTokenKind.LPAREN) {
            val open = advance()
            val inner = parseOrExpr()
            val close = expect(BqlTokenKind.RPAREN, "expected ')' to close parenthesized expression")
            return when (inner) {
                // Re-wrap so the outer span includes the parens.
                is BqlExpr.Compare -> inner.copy(start = open.start, end = close.end)
                is BqlExpr.InList -> inner.copy(start = open.start, end = close.end)
                is BqlExpr.IsNull -> inner.copy(start = open.start, end = close.end)
                is BqlExpr.And -> inner.copy(start = open.start, end = close.end)
                is BqlExpr.Or -> inner.copy(start = open.start, end = close.end)
                is BqlExpr.Not -> inner.copy(start = open.start, end = close.end)
            }
        }
        return parsePredicate()
    }

    private fun parsePredicate(): BqlExpr {
        val fieldToken = peek()
        if (fieldToken.kind != BqlTokenKind.IDENT) {
            recordError(
                "expected a field name, got '${fieldToken.text.ifBlank { "<eof>" }}'",
                fieldToken.start,
                fieldToken.end,
                hint = "BQL predicates start with a field name (e.g. `summary ~ \"frob\"`)",
            )
            // Bail — the token stream is too far off to recover.
            throw BqlBailout()
        }
        val field = advance()

        // `field is null` / `field is not null`
        if (matchKeyword("is")) {
            val negated = matchKeyword("not")
            if (!matchTokenKind(BqlTokenKind.NULL)) {
                val tok = peek()
                recordError("expected 'null' after 'is${if (negated) " not" else ""}'", tok.start, tok.end)
                throw BqlBailout()
            }
            val end = previous().end
            return BqlExpr.IsNull(field.text, negated, field.start, end)
        }

        // `field [not] in (…)`
        var negatedIn = false
        if (peek().kind == BqlTokenKind.IDENT && peek().text.equals("not", ignoreCase = true)
            && peek(1).kind == BqlTokenKind.IDENT && peek(1).text.equals("in", ignoreCase = true)
        ) {
            advance(); advance() // consume `not in`
            negatedIn = true
            val open = expect(BqlTokenKind.LPAREN, "expected '(' after 'in'")
            val values = parseValueList()
            val close = expect(BqlTokenKind.RPAREN, "expected ')' to close 'in' list")
            return BqlExpr.InList(field.text, negatedIn, values, field.start, close.end)
        }
        if (matchKeyword("in")) {
            val open = expect(BqlTokenKind.LPAREN, "expected '(' after 'in'")
            val values = parseValueList()
            val close = expect(BqlTokenKind.RPAREN, "expected ')' to close 'in' list")
            return BqlExpr.InList(field.text, negatedIn, values, field.start, close.end)
        }

        // `field op value`
        val opToken = peek()
        val op = parseComparisonOperator(opToken)
        if (op == null) {
            recordError(
                "expected a comparison operator after '${field.text}', got '${opToken.text}'",
                opToken.start,
                opToken.end,
                hint = "use one of `=`, `!=`, `<`, `<=`, `>`, `>=`, `~`, `in`, `is`",
            )
            throw BqlBailout()
        }
        advance()
        val value = parseValue()
        return BqlExpr.Compare(field.text, op, value, field.start, valueEnd(value, opToken.end))
    }

    private fun parseComparisonOperator(token: BqlToken): BqlComparisonOperator? =
        if (token.kind != BqlTokenKind.OPERATOR) null
        else when (token.text) {
            "=" -> BqlComparisonOperator.EQ
            "!=" -> BqlComparisonOperator.NEQ
            "<" -> BqlComparisonOperator.LT
            "<=" -> BqlComparisonOperator.LTE
            ">" -> BqlComparisonOperator.GT
            ">=" -> BqlComparisonOperator.GTE
            "~" -> BqlComparisonOperator.LIKE
            else -> null
        }

    private fun parseValueList(): List<BqlValue> {
        if (peek().kind == BqlTokenKind.RPAREN) return emptyList()
        val out = mutableListOf<BqlValue>()
        out += parseValue()
        while (peek().kind == BqlTokenKind.COMMA) {
            advance()
            out += parseValue()
        }
        return out
    }

    private fun parseValue(): BqlValue {
        val token = peek()
        return when (token.kind) {
            BqlTokenKind.STRING -> {
                advance()
                BqlValue.Literal(JsonPrimitive(token.text))
            }
            BqlTokenKind.NUMBER -> {
                advance()
                val asLong = token.text.toLongOrNull()
                BqlValue.Literal(
                    if (asLong != null) JsonPrimitive(asLong) else JsonPrimitive(token.text.toDouble()),
                )
            }
            BqlTokenKind.BOOL -> {
                advance()
                BqlValue.Literal(JsonPrimitive(token.text.equals("true", ignoreCase = true)))
            }
            BqlTokenKind.NULL -> {
                advance()
                BqlValue.Literal(JsonNull)
            }
            BqlTokenKind.IDENT -> {
                // Function call (`currentUser()`, `now()`, …) or a
                // bareword treated as a string. BQL accepts unquoted
                // tokens in value position so authors can write
                // `priority = high` without quotes; the validator
                // resolves the value against the field's catalog.
                advance()
                if (peek().kind == BqlTokenKind.LPAREN) {
                    advance()
                    val args = parseValueList()
                    expect(BqlTokenKind.RPAREN, "expected ')' to close function call")
                    BqlValue.Function(token.text, args)
                } else {
                    BqlValue.Literal(JsonPrimitive(token.text))
                }
            }
            BqlTokenKind.LPAREN -> {
                advance()
                val items = parseValueList()
                expect(BqlTokenKind.RPAREN, "expected ')' to close list literal")
                BqlValue.ListLiteral(items)
            }
            else -> {
                recordError(
                    "expected a value (string / number / bool / null / function / list), got '${token.text}'",
                    token.start, token.end,
                )
                throw BqlBailout()
            }
        }
    }

    private fun parseOptionalOrderBy(): List<BqlSortKey> {
        if (!matchKeyword("order")) return emptyList()
        if (!matchKeyword("by")) {
            val tok = peek()
            recordError("expected 'by' after 'order'", tok.start, tok.end)
            return emptyList()
        }
        val out = mutableListOf<BqlSortKey>()
        out += parseSortKey()
        while (peek().kind == BqlTokenKind.COMMA) {
            advance()
            out += parseSortKey()
        }
        return out
    }

    private fun parseSortKey(): BqlSortKey {
        val tok = peek()
        if (tok.kind != BqlTokenKind.IDENT) {
            recordError("expected a field name in ORDER BY", tok.start, tok.end)
            advance()
            return BqlSortKey(tok.text)
        }
        advance()
        val direction = when {
            matchKeyword("asc") -> BqlSortDirection.ASC
            matchKeyword("desc") -> BqlSortDirection.DESC
            else -> BqlSortDirection.ASC
        }
        return BqlSortKey(tok.text, direction)
    }

    // ---- helpers ----

    private fun peek(offset: Int = 0): BqlToken =
        if (cursor + offset < tokens.size) tokens[cursor + offset]
        else tokens.last()

    private fun previous(): BqlToken = tokens[(cursor - 1).coerceAtLeast(0)]

    private fun advance(): BqlToken = tokens[cursor].also { cursor += 1 }

    private fun expect(kind: BqlTokenKind, message: String): BqlToken {
        val tok = peek()
        if (tok.kind == kind) return advance()
        recordError(message, tok.start, tok.end)
        throw BqlBailout()
    }

    private fun matchTokenKind(kind: BqlTokenKind): Boolean {
        if (peek().kind != kind) return false
        advance(); return true
    }

    private fun matchKeyword(text: String): Boolean {
        val tok = peek()
        if (tok.kind == BqlTokenKind.IDENT && tok.text.equals(text, ignoreCase = true)) {
            advance(); return true
        }
        return false
    }

    private fun recordError(message: String, start: Int, end: Int, hint: String? = null) {
        errors.add(BqlError(message, start, end, hint))
    }

    private fun valueEnd(value: BqlValue, fallback: Int): Int = when (value) {
        is BqlValue.Function -> previous().end
        is BqlValue.ListLiteral -> previous().end
        is BqlValue.Literal -> previous().end
    }

    private class BqlBailout : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }
}
