package bosca.bml.codegen

/**
 * Lexical scanning of embedded client TypeScript. Like [KotlinCodeScanner] it is not a parser: it
 * only distinguishes executable code from comments, strings, and regexp literals, so discovery
 * does not act on prose such as `// TODO: renderFragment("admin-panel")`.
 *
 * Regular-expression literals are masked as well. Whether `/` starts a regexp or divides an
 * expression is inferred from the preceding token; this remains a lexical scan, not a TS parser.
 */
internal class ClientScriptScanner(private val source: String) {
    private val out = StringBuilder(source)
    private var index = 0

    /**
     * Component tags passed as a literal first argument to `renderFragment(...)` in executable code.
     * A template literal containing `${…}` is dynamic and therefore not a literal tag.
     */
    fun renderFragmentTargets(): List<String> {
        val code = maskNonCode()
        return RENDER_FRAGMENT_CALL.findAll(code).mapNotNull { match ->
            val quote = source[match.range.last]
            val start = match.range.last + 1
            val end = closingQuote(start, quote) ?: return@mapNotNull null
            source.substring(start, end).takeIf { tag ->
                tag.isNotEmpty() && '\\' !in tag && (quote != '`' || "\${" !in tag)
            }
        }.toList()
    }

    /**
     * Returns the source with comment text and string-literal contents replaced by spaces. Every
     * character keeps its position (and each literal keeps its opening quote), so a match in the
     * masked text maps back to the original. Template-literal `${…}` expressions remain code.
     */
    fun maskNonCode(): String {
        out.setLength(0)
        out.append(source)
        index = 0
        code(stopAtClosingBrace = false)
        return out.toString()
    }

    private fun code(stopAtClosingBrace: Boolean) {
        var depth = 0
        var canStartRegex = true
        var lastWord: String? = null
        var memberAccess = false
        var lastPunctuation: Char? = null
        var lastWasArrow = false
        // A `${...}` body starts in expression position, even when its first token is `function`.
        var atStatementStart = !stopAtClosingBrace
        var declarationPrefix = false
        var functionDeclarationHeader = false
        var functionDeclarationBody = false
        var functionReturnType = false
        var returnTypeBraceDepth = 0
        var classDeclarationDepth: Int? = null
        val controlParens = ArrayDeque<Boolean>()
        val functionDeclarationParens = ArrayDeque<Boolean>()
        val statementBlocks = ArrayDeque<Boolean>()
        while (index < source.length) {
            val char = source[index]
            when {
                source.startsWith("//", index) -> {
                    val end = source.indexOf('\n', index).let { if (it < 0) source.length else it }
                    blank(index, end)
                    index = end
                }
                source.startsWith("/*", index) -> {
                    val close = source.indexOf("*/", index + 2)
                    val end = if (close < 0) source.length else close + 2
                    blank(index, end)
                    index = end
                }
                char == '"' || char == '\'' || char == '`' -> {
                    quoted(char)
                    canStartRegex = false
                    lastWord = null
                    memberAccess = false
                    lastPunctuation = null
                    lastWasArrow = false
                    atStatementStart = false
                    declarationPrefix = false
                }
                char == '/' && canStartRegex && regexp() -> {
                    canStartRegex = false
                    lastWord = null
                    memberAccess = false
                    lastPunctuation = null
                    lastWasArrow = false
                    atStatementStart = false
                    declarationPrefix = false
                }
                char.isLetter() || char == '_' || char == '$' -> {
                    val start = index++
                    while (index < source.length &&
                        (source[index].isLetterOrDigit() || source[index] == '_' || source[index] == '$')
                    ) index++
                    val word = source.substring(start, index)
                    if (!memberAccess) {
                        when (word) {
                            "function" -> {
                                functionDeclarationHeader = atStatementStart || declarationPrefix
                                declarationPrefix = false
                            }
                            "class" -> {
                                if (atStatementStart || declarationPrefix) classDeclarationDepth = controlParens.size
                                declarationPrefix = false
                            }
                            in DECLARATION_PREFIX_KEYWORDS ->
                                declarationPrefix = atStatementStart || declarationPrefix
                            else -> declarationPrefix = false
                        }
                    } else {
                        declarationPrefix = false
                    }
                    canStartRegex = !memberAccess && word in REGEXP_PREFIX_KEYWORDS
                    lastWord = word.takeUnless { memberAccess }
                    memberAccess = false
                    lastPunctuation = null
                    lastWasArrow = false
                    atStatementStart = false
                }
                char.isDigit() -> {
                    index++
                    canStartRegex = false
                    lastWord = null
                    memberAccess = false
                    lastPunctuation = null
                    lastWasArrow = false
                    atStatementStart = false
                    declarationPrefix = false
                }
                char == '{' -> {
                    // A statement block can be followed by a regexp; an object or function
                    // expression can be followed by division instead.
                    val isReturnTypeLiteral = functionDeclarationBody && functionReturnType &&
                        (returnTypeBraceDepth > 0 || lastPunctuation in TYPE_LITERAL_PREFIXES || lastWasArrow)
                    val isFunctionBody = functionDeclarationBody && !isReturnTypeLiteral
                    val isClassBody = classDeclarationDepth == controlParens.size
                    val isStatementBlock = !isReturnTypeLiteral && (
                        isFunctionBody || isClassBody || lastPunctuation == ')' && canStartRegex ||
                            lastWasArrow || lastPunctuation == ';' || lastPunctuation == '{' ||
                            lastPunctuation == '}' && canStartRegex ||
                            lastWord in BLOCK_PREFIX_KEYWORDS ||
                            lastPunctuation == null && lastWord == null && !stopAtClosingBrace
                        )
                    if (isReturnTypeLiteral) returnTypeBraceDepth++
                    if (isFunctionBody) {
                        functionDeclarationBody = false
                        functionReturnType = false
                    }
                    if (isClassBody) classDeclarationDepth = null
                    statementBlocks.addLast(isStatementBlock)
                    depth++
                    index++
                    canStartRegex = true
                    lastWord = null
                    memberAccess = false
                    lastPunctuation = '{'
                    lastWasArrow = false
                    atStatementStart = isStatementBlock
                    declarationPrefix = false
                }
                char == '}' -> {
                    if (stopAtClosingBrace && depth == 0) {
                        blank(index, index + 1)
                        index++
                        return
                    }
                    depth--
                    index++
                    if (returnTypeBraceDepth > 0) returnTypeBraceDepth--
                    canStartRegex = statementBlocks.removeLastOrNull() == true
                    lastWord = null
                    memberAccess = false
                    lastPunctuation = '}'
                    lastWasArrow = false
                    atStatementStart = canStartRegex
                    declarationPrefix = false
                }
                char.isWhitespace() -> index++
                else -> {
                    index++
                    canStartRegex = when (char) {
                        '(' -> {
                            controlParens.addLast(lastWord in CONTROL_PAREN_KEYWORDS || functionDeclarationHeader)
                            functionDeclarationParens.addLast(functionDeclarationHeader)
                            functionDeclarationHeader = false
                            true
                        }
                        ')' -> {
                            if (functionDeclarationParens.removeLastOrNull() == true) functionDeclarationBody = true
                            controlParens.removeLastOrNull() == true
                        }
                        ']', '.' -> false
                        '+', '-' -> if (source.getOrNull(index) == char) {
                            index++
                            canStartRegex
                        } else true
                        else -> true
                    }
                    memberAccess = char == '.'
                    lastWord = null
                    lastWasArrow = char == '>' && source.getOrNull(index - 2) == '='
                    lastPunctuation = char
                    if (char == ':' && functionDeclarationBody) functionReturnType = true
                    if (char == ';' && returnTypeBraceDepth == 0) {
                        functionDeclarationHeader = false
                        functionDeclarationBody = false
                        functionReturnType = false
                        classDeclarationDepth = null
                    }
                    atStatementStart = char == ';' && returnTypeBraceDepth == 0
                    declarationPrefix = false
                }
            }
        }
    }

    /** Masks a regexp including its delimiters; an unclosed `/` is left for ordinary code scanning. */
    private fun regexp(): Boolean {
        var end = index + 1
        var inCharacterClass = false
        while (end < source.length && source[end] != '\n') {
            when (source[end]) {
                '\\' -> end += 2
                '[' -> { inCharacterClass = true; end++ }
                ']' -> { inCharacterClass = false; end++ }
                '/' -> if (!inCharacterClass) {
                    end++
                    while (end < source.length && source[end].isLetter()) end++ // regexp flags
                    blank(index, end)
                    index = end
                    return true
                } else end++
                else -> end++
            }
        }
        return false
    }

    /** Masks one string literal; [index] is at its opening quote, which stays visible. */
    private fun quoted(quote: Char) {
        index++
        while (index < source.length) {
            val char = source[index]
            when {
                char == '\\' -> {
                    blank(index, index + 2)
                    index += 2
                }
                char == quote -> {
                    index++
                    return
                }
                quote == '`' && source.startsWith("\${", index) -> {
                    blank(index, index + 2)
                    index += 2
                    code(stopAtClosingBrace = true)
                }
                quote != '`' && char == '\n' -> return // an unterminated ordinary string ends at the line
                else -> {
                    blank(index, index + 1)
                    index++
                }
            }
        }
    }

    private fun blank(from: Int, to: Int) {
        for (position in from until minOf(to, source.length)) {
            if (out[position] != '\n') out.setCharAt(position, ' ')
        }
    }

    private fun closingQuote(start: Int, quote: Char): Int? {
        var position = start
        while (position < source.length) {
            when (source[position]) {
                '\\' -> position += 2
                quote -> return position
                '\n' -> if (quote == '`') position++ else return null
                else -> position++
            }
        }
        return null
    }

    private companion object {
        val RENDER_FRAGMENT_CALL = Regex("""\brenderFragment\s*\(\s*(["'`])""")
        val REGEXP_PREFIX_KEYWORDS = setOf(
            "await", "case", "delete", "do", "else", "in", "instanceof", "new", "of",
            "return", "throw", "typeof", "void", "yield",
        )
        val CONTROL_PAREN_KEYWORDS = setOf("if", "while", "for", "with", "switch", "catch")
        val BLOCK_PREFIX_KEYWORDS = setOf("catch", "do", "else", "finally", "static", "try")
        val DECLARATION_PREFIX_KEYWORDS = setOf("export", "default", "async", "declare", "abstract")
        val TYPE_LITERAL_PREFIXES = setOf(':', '<', ',', '&', '|')
    }
}
