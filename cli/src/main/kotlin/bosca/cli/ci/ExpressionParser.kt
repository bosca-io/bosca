package bosca.cli.ci

class ExpressionParser {

    fun evaluate(expression: String, context: ExpressionContext): Any? {
        val tokens = tokenize(expression)
        val parser = Parser(tokens, context)
        return parser.parseExpression()
    }

    fun evaluateBoolean(expression: String, context: ExpressionContext): Boolean {
        return toBool(evaluate(expression, context))
    }

    fun interpolate(template: String, context: ExpressionContext): String {
        val pattern = """\$\{\{\s*(.*?)\s*}}""".toRegex()
        return pattern.replace(template) { match ->
            val expr = match.groupValues[1]
            evaluate(expr, context)?.toString() ?: ""
        }
    }

    private fun toBool(value: Any?): Boolean {
        return when (value) {
            null -> false
            is Boolean -> value
            is String -> value.isNotEmpty() && value != "false"
            is Number -> value.toDouble() != 0.0
            else -> true
        }
    }

    private fun tokenize(input: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < input.length) {
            when {
                input[i].isWhitespace() -> i++
                input[i] == '(' -> { tokens.add(Token(TokenType.LPAREN, "(")); i++ }
                input[i] == ')' -> { tokens.add(Token(TokenType.RPAREN, ")")); i++ }
                input[i] == ',' -> { tokens.add(Token(TokenType.COMMA, ",")); i++ }
                input[i] == '.' -> { tokens.add(Token(TokenType.DOT, ".")); i++ }
                input[i] == '!' && i + 1 < input.length && input[i + 1] == '=' -> {
                    tokens.add(Token(TokenType.NE, "!=")); i += 2
                }
                input[i] == '!' -> { tokens.add(Token(TokenType.NOT, "!")); i++ }
                input[i] == '=' && i + 1 < input.length && input[i + 1] == '=' -> {
                    tokens.add(Token(TokenType.EQ, "==")); i += 2
                }
                input[i] == '<' && i + 1 < input.length && input[i + 1] == '=' -> {
                    tokens.add(Token(TokenType.LE, "<=")); i += 2
                }
                input[i] == '>' && i + 1 < input.length && input[i + 1] == '=' -> {
                    tokens.add(Token(TokenType.GE, ">=")); i += 2
                }
                input[i] == '<' -> { tokens.add(Token(TokenType.LT, "<")); i++ }
                input[i] == '>' -> { tokens.add(Token(TokenType.GT, ">")); i++ }
                input[i] == '&' && i + 1 < input.length && input[i + 1] == '&' -> {
                    tokens.add(Token(TokenType.AND, "&&")); i += 2
                }
                input[i] == '|' && i + 1 < input.length && input[i + 1] == '|' -> {
                    tokens.add(Token(TokenType.OR, "||")); i += 2
                }
                input[i] == '\'' -> {
                    val start = ++i
                    while (i < input.length && input[i] != '\'') i++
                    tokens.add(Token(TokenType.STRING, input.substring(start, i)))
                    if (i < input.length) i++
                }
                input[i] == '"' -> {
                    val start = ++i
                    while (i < input.length && input[i] != '"') i++
                    tokens.add(Token(TokenType.STRING, input.substring(start, i)))
                    if (i < input.length) i++
                }
                input[i].isDigit() || (input[i] == '-' && i + 1 < input.length && input[i + 1].isDigit()) -> {
                    val start = i
                    if (input[i] == '-') i++
                    while (i < input.length && (input[i].isDigit() || input[i] == '.')) i++
                    tokens.add(Token(TokenType.NUMBER, input.substring(start, i)))
                }
                input[i].isLetter() || input[i] == '_' -> {
                    val start = i
                    while (i < input.length && (input[i].isLetterOrDigit() || input[i] == '_' || input[i] == '-')) i++
                    val word = input.substring(start, i)
                    val type = when (word) {
                        "true", "false" -> TokenType.BOOLEAN
                        else -> TokenType.IDENT
                    }
                    tokens.add(Token(type, word))
                }
                else -> i++
            }
        }
        tokens.add(Token(TokenType.EOF, ""))
        return tokens
    }

    private class Parser(private val tokens: List<Token>, private val context: ExpressionContext) {
        private var pos = 0

        fun parseExpression(): Any? = parseOr()

        private fun parseOr(): Any? {
            var left = parseAnd()
            while (current().type == TokenType.OR) {
                advance()
                val right = parseAnd()
                left = toBool(left) || toBool(right)
            }
            return left
        }

        private fun parseAnd(): Any? {
            var left = parseNot()
            while (current().type == TokenType.AND) {
                advance()
                val right = parseNot()
                left = toBool(left) && toBool(right)
            }
            return left
        }

        private fun parseNot(): Any? {
            if (current().type == TokenType.NOT) {
                advance()
                return !toBool(parseNot())
            }
            return parseComparison()
        }

        private fun parseComparison(): Any? {
            val left = parsePrimary()
            val op = current().type
            if (op in listOf(TokenType.EQ, TokenType.NE, TokenType.LT, TokenType.GT, TokenType.LE, TokenType.GE)) {
                advance()
                val right = parsePrimary()
                return when (op) {
                    TokenType.EQ -> left?.toString() == right?.toString()
                    TokenType.NE -> left?.toString() != right?.toString()
                    TokenType.LT -> toNum(left) < toNum(right)
                    TokenType.GT -> toNum(left) > toNum(right)
                    TokenType.LE -> toNum(left) <= toNum(right)
                    TokenType.GE -> toNum(left) >= toNum(right)
                    else -> false
                }
            }
            return left
        }

        private fun parsePrimary(): Any? {
            val token = current()
            return when (token.type) {
                TokenType.STRING -> { advance(); token.value }
                TokenType.NUMBER -> { advance(); token.value.toDoubleOrNull() ?: 0.0 }
                TokenType.BOOLEAN -> { advance(); token.value == "true" }
                TokenType.IDENT -> parseIdentOrCall()
                TokenType.LPAREN -> {
                    advance()
                    val result = parseExpression()
                    expect(TokenType.RPAREN)
                    result
                }
                else -> { advance(); null }
            }
        }

        private fun parseIdentOrCall(): Any? {
            val name = current().value
            advance()

            if (current().type == TokenType.LPAREN) {
                advance()
                val args = mutableListOf<Any?>()
                if (current().type != TokenType.RPAREN) {
                    args.add(parseExpression())
                    while (current().type == TokenType.COMMA) {
                        advance()
                        args.add(parseExpression())
                    }
                }
                expect(TokenType.RPAREN)
                return callFunction(name, args)
            }

            var path = name
            while (current().type == TokenType.DOT) {
                advance()
                path += ".${current().value}"
                advance()
            }
            return context.resolve(path)
        }

        private fun callFunction(name: String, args: List<Any?>): Any? {
            return when (name) {
                "success" -> context.jobStatus == "success"
                "failure" -> context.jobStatus == "failure"
                "cancelled" -> context.jobStatus == "cancelled"
                "always" -> true
                "startsWith" -> {
                    val str = args.getOrNull(0)?.toString() ?: ""
                    val prefix = args.getOrNull(1)?.toString() ?: ""
                    str.startsWith(prefix)
                }
                "endsWith" -> {
                    val str = args.getOrNull(0)?.toString() ?: ""
                    val suffix = args.getOrNull(1)?.toString() ?: ""
                    str.endsWith(suffix)
                }
                "contains" -> {
                    val str = args.getOrNull(0)?.toString() ?: ""
                    val sub = args.getOrNull(1)?.toString() ?: ""
                    str.contains(sub)
                }
                "hashFiles" -> {
                    val globs = args.mapNotNull { it?.toString() }
                    context.hashFiles(globs)
                }
                else -> null
            }
        }

        private fun current(): Token = tokens.getOrElse(pos) { Token(TokenType.EOF, "") }
        private fun advance() { pos++ }
        private fun expect(type: TokenType) {
            if (current().type == type) advance()
        }

        private fun toBool(value: Any?): Boolean {
            return when (value) {
                null -> false
                is Boolean -> value
                is String -> value.isNotEmpty() && value != "false"
                is Number -> value.toDouble() != 0.0
                else -> true
            }
        }

        private fun toNum(value: Any?): Double {
            return when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
        }
    }

    private enum class TokenType {
        STRING, NUMBER, BOOLEAN, IDENT,
        LPAREN, RPAREN, COMMA, DOT,
        EQ, NE, LT, GT, LE, GE,
        AND, OR, NOT,
        EOF
    }

    private data class Token(val type: TokenType, val value: String)
}

data class ExpressionContext(
    val ref: String = "",
    val branch: String = "",
    val event: String = "",
    val jobStatus: String = "success",
    val matrix: Map<String, String> = emptyMap(),
    val env: Map<String, String> = emptyMap(),
    val secrets: Map<String, String> = emptyMap(),
    private val extra: Map<String, String> = emptyMap(),
    private val fileHasher: (List<String>) -> String = { "" },
) {
    fun resolve(path: String): Any? {
        return when {
            path == "ref" -> ref
            path == "branch" -> branch
            // The bare tag name of a tag-triggered run ("1.4.0"), empty otherwise — and the tag with any
            // leading "v" stripped. The release relay tags the workops version name verbatim, so the strip
            // is a no-op for relay tags and only normalizes manually pushed `v`-prefixed tags. Same
            // tokens the server resolves in artifact coordinates, so steps and declarations line up.
            path == "tag" -> tagName()
            path == "version" -> tagName().removePrefix("v")
            path == "event" -> event
            path.startsWith("matrix.") -> matrix[path.removePrefix("matrix.")]
            path.startsWith("env.") -> env[path.removePrefix("env.")]
            path.startsWith("secrets.") -> secrets[path.removePrefix("secrets.")]
            else -> extra[path]
        }
    }

    fun hashFiles(globs: List<String>): String = fileHasher(globs)

    private fun tagName(): String = if (ref.startsWith("refs/tags/")) ref.removePrefix("refs/tags/") else ""
}
