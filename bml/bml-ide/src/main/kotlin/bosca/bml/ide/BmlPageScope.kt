package bosca.bml.ide

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * Produces the Kotlin declarations a `.bml` file's hosts need to resolve cross-references —
 * `<script server provides="x">` values, `<inject name type>` bindings, and component props — as a prefix
 * spliced into each host's PER-HOST injection.
 *
 * Why per-host + a declarations prefix (not one combined fragment): IntelliJ reliably injects each
 * host on its own, but a single multi-host fragment keyed on a whole-file container does NOT fire in
 * the IDE (it leaves the embedded Kotlin un-highlighted). So instead every host is injected
 * independently, and we prepend `val <provides> = run { <body> }` / `val <prop>: <type> = TODO()` so a
 * use like `{ fruits.size }` or `{ items.size }` resolves with the right type. (Cross-reference
 * Go-to-Declaration lands on this synthetic declaration; the declared-here symbols themselves resolve.)
 *
 * Declarations are collected per file and deduped by name; `provides` copy the script body so the
 * inferred type is correct, props use their declared `type`.
 */
object BmlPageScope {

    /**
     * A `provides` value, injected dependency, or component prop visible to embedded Kotlin.
     * [initializer] carries the real `provides` body when its type could NOT be inferred textually —
     * the declaration then keeps the live expression (`val page = run { … }`) so the module-anchored
     * resolution fragment (goto / member completion) infers the true type from the called code.
     */
    data class Symbol(val name: String, val type: String, val initializer: String? = null)

    /** The provides/prop symbols of [file] (deduped, document order); cached. */
    fun symbols(file: PsiFile): List<Symbol> =
        CachedValuesManager.getCachedValue(file) { CachedValueProvider.Result.create(build(file, null), file) }

    /** Symbols owned by the page/component/message/template containing [host]. */
    fun symbols(host: PsiElement): List<Symbol> {
        val file = host.containingFile
        return build(file, containingRenderScope(file, host.textOffset))
    }

    /** Those symbols as declarations, prepended to each host's injection. */
    fun declarations(file: PsiFile): String =
        symbols(file).joinToString("\n") {
            if (it.initializer != null) {
                "val ${it.name} = run {\n${it.initializer}\n}"
            } else {
                "val ${it.name}: ${it.type} = TODO()"
            }
        }

    /** Declarations visible specifically to [host], without leaking from sibling render units. */
    fun declarations(host: PsiElement): String =
        symbols(host).joinToString("\n") {
            if (it.initializer != null) {
                "val ${it.name} = run {\n${it.initializer}\n}"
            } else {
                "val ${it.name}: ${it.type} = TODO()"
            }
        }

    /**
     * The `<for VAR in EXPR>` loops enclosing [host], outermost first — each becomes a real
     * `for (VAR in EXPR) {` wrapper line in the host's injection so the loop variable exists WITH
     * its inferred element type. A for's own header host sees only the loops OUTSIDE it.
     */
    fun enclosingForLoops(host: PsiElement): List<Pair<String, String>> {
        val hostNode = host.node ?: return emptyList()
        val stack = mutableListOf<Pair<String, String>>()
        var node: ASTNode? = host.containingFile.node.firstChildNode
        var lastAngle = ""
        var lastKeyword: String? = null
        while (node != null && node != hostNode) {
            when (node.elementType) {
                BmlTokens.ANGLE -> lastAngle = node.text
                BmlTokens.TAG_KEYWORD -> {
                    if (node.text == "for" && lastAngle == "</" && stack.isNotEmpty()) stack.removeLast()
                    lastKeyword = node.text
                }
                BmlElementTypes.FLOW_EXPR_HOST -> if (lastKeyword == "for" && lastAngle == "<") {
                    parseForBinding(node.text)?.let { stack.add(it) }
                }
            }
            node = node.treeNext
        }
        return stack
    }

    /**
     * The submittable fields of the `<form>` enclosing [host] — name → Kotlin type, mirroring the
     * live-island runtime's coercion (checkboxes submit as Boolean, everything else as String).
     * Fields are collected from the WHOLE form (an `@submit` sits in the form's open tag, before
     * its fields), innermost form wins, and only runtime-referencable names (`form.<ident>`) are
     * kept. Empty when [host] isn't inside a form.
     */
    fun enclosingFormFields(host: PsiElement): List<Pair<String, String>> {
        val hostNode = host.node ?: return emptyList()
        val openForms = ArrayDeque<LinkedHashMap<String, String>>() // innermost last
        var hostForms: List<LinkedHashMap<String, String>>? = null // forms open AT the host
        var node: ASTNode? = host.containingFile.node.firstChildNode
        var lastAngle = ""
        var currentTag: String? = null
        var lastAttr: String? = null
        var fieldName: String? = null
        var fieldType: String? = null
        while (node != null) {
            if (node == hostNode) hostForms = openForms.toList()
            when (node.elementType) {
                BmlTokens.ANGLE -> {
                    val text = node.text
                    if (text == ">" || text == "/>") {
                        // End of an open tag: commit a pending field; a self-closed <form/> never opened.
                        if (currentTag in FIELD_TAGS && fieldName != null && IDENT.matches(fieldName ?: "")) {
                            val type = if (fieldType == "checkbox") "Boolean" else "String"
                            openForms.lastOrNull()?.putIfAbsent(fieldName ?: "", type)
                        }
                        if (text == "/>" && currentTag == "form") openForms.removeLastOrNull()
                        currentTag = null; lastAttr = null; fieldName = null; fieldType = null
                    }
                    lastAngle = text
                }
                BmlTokens.TAG_KEYWORD, BmlElementTypes.TAG_NAME_REF -> when (lastAngle) {
                    "<" -> {
                        currentTag = node.text
                        if (node.text == "form") openForms.addLast(LinkedHashMap())
                        lastAngle = ""
                    }
                    "</" -> {
                        if (node.text == "form") openForms.removeLastOrNull()
                        lastAngle = ""
                    }
                }
                BmlTokens.ATTR_NAME -> lastAttr = node.text
                BmlElementTypes.ATTR_VALUE_HOST -> if (currentTag in FIELD_TAGS) {
                    when (lastAttr) {
                        "name" -> fieldName = unquote(node.text)
                        "type" -> fieldType = unquote(node.text)
                    }
                }
            }
            node = node.treeNext
        }
        return hostForms?.lastOrNull()?.toList() ?: emptyList()
    }

    /**
     * `item in page.items` → ("item", "page.items"); splits on the first TOP-LEVEL ` in ` (bracket-
     * and quote-aware, so `x in xs.filter { it in other }` binds `x`). Null when there is no ` in `.
     */
    internal fun parseForBinding(flowExpr: String): Pair<String, String>? {
        var depth = 0
        var quote = ' '
        var i = 0
        while (i + 4 <= flowExpr.length) {
            val c = flowExpr[i]
            when {
                quote != ' ' -> if (c == quote) quote = ' '
                c == '"' || c == '\'' -> quote = c
                c == '(' || c == '{' || c == '[' -> depth++
                c == ')' || c == '}' || c == ']' -> depth--
                depth == 0 && flowExpr.regionMatches(i, " in ", 0, 4) -> {
                    val binding = flowExpr.take(i).trim()
                    val iterable = flowExpr.substring(i + 4).trim()
                    return if (binding.isNotEmpty() && iterable.isNotEmpty()) binding to iterable else null
                }
            }
            i++
        }
        return null
    }

    /**
     * The `.bml` element that declares [name] — a `<script server provides="name">` value or a
     * `<prop name="name">` value — for Go-to-Declaration. A `provides`/`prop` use resolves (in the
     * injected fragment) to a synthetic prefix `val`; this maps that back to the real source so Cmd-click
     * lands on the declaration in the `.bml` instead of an in-memory fragment.
     */
    fun findDeclarationElement(file: PsiFile, name: String, contextOffset: Int? = null): PsiElement? {
        val scope = contextOffset?.let { containingRenderScope(file, it) }
        var node: ASTNode? = file.node.firstChildNode
        var lastAttr: String? = null
        var lastKeyword: String? = null
        var lastTagName: String? = null
        var lastAngle = ""
        while (node != null) {
            when (node.elementType) {
                BmlTokens.ANGLE -> lastAngle = node.text
                BmlTokens.TAG_KEYWORD -> lastKeyword = if (lastAngle == "</") null else node.text
                BmlElementTypes.TAG_NAME_REF -> lastTagName = if (lastAngle == "</") null else node.text
                BmlTokens.ATTR_NAME -> lastAttr = node.text
                BmlElementTypes.ATTR_VALUE_HOST -> {
                    val match = (lastAttr == "provides" ||
                        (lastKeyword in setOf("prop", "inject") && lastAttr == "name")) &&
                        unquote(node.text) == name && (scope == null || scope.contains(node.startOffset))
                    if (match) return node.psi
                    // A form field's name attribute declares the `form.<name>` action argument.
                    if (lastTagName in FIELD_TAGS && lastAttr == "name" && unquote(node.text) == name) {
                        return node.psi
                    }
                }
                // A `<for VAR in …>` header declares VAR (or a `(k, v)` destructuring's components).
                BmlElementTypes.FLOW_EXPR_HOST -> if (lastKeyword == "for") {
                    val binding = parseForBinding(node.text)?.first
                    val names = binding?.removeSurrounding("(", ")")?.split(',')?.map { it.trim() }
                    if (names != null && name in names) return node.psi
                }
            }
            node = node.treeNext
        }
        return null
    }

    private fun build(file: PsiFile, scope: TextRange?): List<Symbol> {
        val syms = LinkedHashMap<String, Symbol>() // name -> symbol (dedup by name, document order)
        var node: ASTNode? = file.node.firstChildNode
        var lastAngle = ""
        while (node != null) {
            if (scope == null || scope.contains(node.startOffset)) when (node.elementType) {
                BmlTokens.ANGLE -> lastAngle = node.text
                BmlTokens.TAG_KEYWORD -> if (lastAngle != "</") when (node.text) {
                    "prop" -> readNamedType(node, typeRequired = false)?.let { (name, type) ->
                        syms.putIfAbsent(name, Symbol(name, type))
                    }
                    "inject" -> readNamedType(node, typeRequired = true)?.let { (name, type) ->
                        syms.putIfAbsent(name, Symbol(name, type))
                    }
                    else -> Unit
                }
                BmlElementTypes.SCRIPT_SERVER_HOST -> providesName(node)?.let { name ->
                    // The raw injection does NOT resolve the initializer expression inside a prefix
                    // declaration, so an inferred-type val (`val x = listOf(...)`) collapses to the raw
                    // callee ("listOf"). Give an EXPLICIT type when it's textually inferable. When it
                    // isn't (a call into project code — `librarySaved(ctx)`), keep the REAL body as the
                    // initializer instead of `Any?`: the module-anchored resolution fragment (goto /
                    // member completion) then infers the true return type from the called code.
                    val body = node.text.trim()
                    val inferred = inferType(body)
                    syms.putIfAbsent(
                        name,
                        if (inferred != null) Symbol(name, inferred) else Symbol(name, "Any?", initializer = body),
                    )
                }
            }
            node = node.treeNext
        }
        return syms.values.toList()
    }

    /** Flat-PSI render-unit ranges; the smallest range containing an embedded-code host owns it. */
    private fun containingRenderScope(file: PsiFile, offset: Int): TextRange? {
        data class OpenScope(val tag: String, val start: Int)

        val open = mutableListOf<OpenScope>()
        val ranges = mutableListOf<TextRange>()
        var node: ASTNode? = file.node.firstChildNode
        var lastAngle = ""
        var angleStart = 0
        while (node != null) {
            when (node.elementType) {
                BmlTokens.ANGLE -> if (node.text == "<" || node.text == "</") {
                    lastAngle = node.text
                    angleStart = node.startOffset
                }
                BmlTokens.TAG_KEYWORD, BmlElementTypes.TAG_NAME_REF -> if (node.text in RENDER_UNIT_TAGS) {
                    if (lastAngle == "<") {
                        open += OpenScope(node.text, angleStart)
                    } else if (lastAngle == "</") {
                        val index = open.indexOfLast { it.tag == node.text }
                        if (index >= 0) {
                            val started = open.removeAt(index)
                            ranges += TextRange(started.start, node.textRange.endOffset)
                        }
                    }
                }
            }
            node = node.treeNext
        }
        open.forEach { ranges += TextRange(it.start, file.textLength) }
        return ranges.filter { it.contains(offset) }.minByOrNull { it.length }
    }

    /** A `<prop>` or `<inject>` declaration's (name, type), scanning to the tag's `>`. */
    private fun readNamedType(keyword: ASTNode, typeRequired: Boolean): Pair<String, String>? {
        var p: ASTNode? = keyword.treeNext
        var name: String? = null
        var type: String? = null
        var lastAttr: String? = null
        while (p != null && p.elementType != BmlTokens.ANGLE) {
            when (p.elementType) {
                BmlTokens.ATTR_NAME -> lastAttr = p.text
                BmlElementTypes.ATTR_VALUE_HOST -> when (lastAttr) {
                    "name" -> name = unquote(p.text)
                    "type" -> type = unquote(p.text)
                }
            }
            p = p.treeNext
        }
        return name?.let { it to (type ?: if (typeRequired) return null else "Any?") }
    }

    /** The `provides="x"` name preceding a `<script server>` body, or null. */
    private fun providesName(serverBody: ASTNode): String? {
        var n: ASTNode? = serverBody.treePrev
        var lastValue: String? = null
        while (n != null) {
            when (n.elementType) {
                BmlTokens.TAG_KEYWORD -> return null // reached `script` with no provides
                BmlElementTypes.ATTR_VALUE_HOST -> lastValue = unquote(n.text)
                BmlTokens.ATTR_NAME -> if (n.text == "provides") return lastValue
            }
            n = n.treePrev
        }
        return null
    }

    /**
     * Best-effort Kotlin type for a `provides` body, so the value gets a real type in the IDE despite
     * IntelliJ not resolving injected-prefix initializers. Covers common builders + literals; returns
     * null when it can't tell (caller falls back to `Any?`). Not a type checker — a pragmatic heuristic.
     */
    private fun inferType(body: String): String? {
        val b = body.trim()
        typeArg(b, "listOf")?.let { return "List<$it>" }
        typeArg(b, "mutableListOf")?.let { return "MutableList<$it>" }
        typeArg(b, "setOf")?.let { return "Set<$it>" }
        typeArg(b, "emptyList")?.let { return "List<$it>" }
        constructorType(b)?.let { return it } // `bosca.bml.sample.CounterModel()` → that type
        return when {
            b.startsWith("listOf(") -> "List<${elementType(b)}>"
            b.startsWith("mutableListOf(") -> "MutableList<${elementType(b)}>"
            b.startsWith("setOf(") -> "Set<${elementType(b)}>"
            b.startsWith("emptyList(") -> "List<Any?>"
            b.startsWith("mapOf(") || b.startsWith("mutableMapOf(") -> "Map<Any?, Any?>"
            b.startsWith("\"") || b.startsWith("\"\"\"") -> "String"
            b == "true" || b == "false" -> "Boolean"
            b.toIntOrNull() != null -> "Int"
            b.toLongOrNull() != null -> "Long"
            b.toDoubleOrNull() != null -> "Double"
            else -> null
        }
    }

    /**
     * A constructor-call body like `CounterModel()` / `bosca.bml.sample.CounterModel(a, b)` → its type
     * (`CounterModel` / `bosca.bml.sample.CounterModel`). Heuristic: the callee (text before the first
     * `(`) is a dotted identifier path whose last segment is PascalCase (Kotlin classes are capitalized,
     * functions are not — so `listOf(...)` is correctly excluded). Generic constructors aren't handled.
     */
    private fun constructorType(body: String): String? {
        val paren = body.indexOf('(')
        if (paren <= 0) return null
        val callee = body.substring(0, paren).trim()
        if (callee.isEmpty() || !callee.all { it.isLetterOrDigit() || it == '.' || it == '_' }) return null
        val lastSegment = callee.substringAfterLast('.')
        return if (lastSegment.firstOrNull()?.isUpperCase() == true) callee else null
    }

    /** Explicit type argument of a call, e.g. `listOf<Foo>(…)` -> `Foo` (for [fn] == "listOf"). */
    private fun typeArg(body: String, fn: String): String? {
        if (!body.startsWith("$fn<")) return null
        val open = body.indexOf('<')
        var depth = 0
        for (i in open until body.length) {
            when (body[i]) {
                '<' -> depth++
                '>' -> { depth--; if (depth == 0) return body.substring(open + 1, i).trim().ifEmpty { null } }
            }
        }
        return null
    }

    /** Element type guessed from the first argument of a builder call: `"…"` -> String, digits -> Int/Double. */
    private fun elementType(body: String): String {
        val firstArg = body.substring(body.indexOf('(') + 1).trimStart()
        return when {
            firstArg.startsWith("\"") -> "String"
            firstArg.startsWith("-") || firstArg.firstOrNull()?.isDigit() == true ->
                if (firstArg.takeWhile { it.isDigit() || it == '.' || it == '-' }.contains('.')) "Double" else "Int"
            firstArg.startsWith("true") || firstArg.startsWith("false") -> "Boolean"
            else -> "Any?"
        }
    }

    private fun unquote(raw: String): String {
        val s = raw.trim()
        return if (s.length >= 2 && (s.first() == '"' || s.first() == '\'') && s.last() == s.first()) {
            s.substring(1, s.length - 1)
        } else {
            s
        }
    }

    /** The submittable form-field tags whose `name` becomes a `form.<name>` action argument. */
    private val FIELD_TAGS = setOf("input", "select", "textarea")

    /** Mirrors the compiler's `isIdent` gate on `form.<field>` references. */
    private val IDENT = Regex("""[A-Za-z0-9_]+""")

    private val RENDER_UNIT_TAGS = setOf("page", "route", "component", "message", "template")
}
