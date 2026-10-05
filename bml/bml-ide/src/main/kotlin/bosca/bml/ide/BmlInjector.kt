package bosca.bml.ide

import com.intellij.lang.Language
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace

/**
 * Injects real languages into BML's embedded-code regions:
 *  - `<script server>` body and `{ … }` interpolation → Kotlin
 *  - `<script client>` body → TypeScript (falling back to JavaScript / ES6)
 *  - attribute values → Kotlin, with the shape depending on the attribute:
 *      - bound (`:href="u"` / `@click="f"`): the whole inner value is one expression
 *      - interpolated (`class="btn-{ v }"`): each `{ … }` region is an independent expression
 *      - plain (`class="c"`): nothing
 *
 * Languages are looked up by ID so this has no compile-time dependency on the Kotlin or
 * JavaScript plugins; if a language isn't present in the running IDE, that region is simply
 * not injected (TypeScript/JavaScript ship only in paid IDEs).
 */
class BmlInjector : MultiHostInjector {

    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(BmlInjectionHost::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        if (context !is BmlInjectionHost || !context.isValidHost) return

        when (context.node.elementType) {
            BmlElementTypes.SCRIPT_CLIENT_HOST -> {
                val ts = firstAvailable("TypeScript", "ECMAScript 6", "JavaScript") ?: return
                // Only the BML runtime API is declared here. Site globals (site.ts's `auth`) resolve
                // through the client entry's own `declare global` — the JS index reaches it from
                // injected fragments, and re-declaring per fragment turns every use into a
                // multi-resolve ("multiple implementations") that breaks member navigation.
                //
                // The suffix mirrors the compiled module's `Object.assign(window, { … })` for the
                // handlers this script declares that `on<event>="…"` attributes reference — the IDE
                // then sees the same usage the browser does instead of marking them "unused". Names
                // declared in a DIFFERENT <script client> of the file are left to that host's own
                // injection.
                val published = publishedHandlers(context)
                val handlerPublish = published.takeIf { it.isNotEmpty() }
                    ?.joinToString(", ", prefix = "\n;Object.assign(window, { ", postfix = " });") ?: ""
                // `export {}` makes the fragment a MODULE (matching the compiled page script): the
                // prefix's runtime-API declares stay fragment-scoped instead of becoming N indexed
                // global duplicates — which the JS plugin renders as bogus "multiple implementations"
                // relations across every <script client> in the project. Globals (site.ts's `auth`)
                // stay visible; modules always see the global scope.
                injectWhole(registrar, ts, context, TS_CTX_PREFIX, handlerPublish + MODULE_SUFFIX)
            }
            BmlElementTypes.STYLE_HOST -> {
                // `<style>` body is plain CSS (matches the compiler's raw RawKind.Style). Inject CSS so it
                // highlights + checks as CSS; skipped (no injection, no false errors) when CSS isn't present.
                // The app.css tier's custom properties (src/main/client/*.css) are prepended as a `:root`
                // block so `var(--divider)` resolves against the real theme values.
                val css = firstAvailable("CSS") ?: return
                injectWhole(registrar, css, context, BmlClientScope.scope(context.containingFile).cssPrefix, null)
            }
            else -> {
                // Each Kotlin host is injected on its own (reliable), with the file's `provides`/prop
                // declarations prepended so cross-references (`{ fruits.size }`, `{ items.size }`) resolve,
                // and the host's enclosing `<for>` loops re-opened as REAL Kotlin for-loops so the loop
                // variables exist with their inferred element types (`{ item.href }`).
                val kotlin = firstAvailable("kotlin") ?: return
                val (prefix, loopCloses) = kotlinScope(context)
                when (context.node.elementType) {
                    BmlElementTypes.ATTR_VALUE_HOST -> injectAttributeValue(registrar, kotlin, context, prefix, loopCloses)
                    BmlElementTypes.FLOW_EXPR_HOST -> injectWhole(
                        registrar, kotlin, context,
                        prefix + (if (isForFlow(context)) "for (" else "if ("),
                        FLOW_CLOSE + loopCloses + FUN_CLOSE,
                    )
                    // `<script server>` body + `{ … }` interpolation are VALUE expressions: wrap so the value
                    // is consumed (`__emit(run { … })`), else Kotlin flags the bare expression "unused" and
                    // dims it to an unreadable color.
                    else -> injectWhole(registrar, kotlin, context, prefix + EMIT_OPEN, EMIT_CLOSE + loopCloses + FUN_CLOSE)
                }
            }
        }
    }

    /**
     * The `on<event>` handler names [host]'s own body declares (`function`/`const`/`let`/`var`) —
     * the subset of the file's window-published handlers this fragment's suffix may reference
     * without introducing unresolved names.
     */
    private fun publishedHandlers(host: BmlInjectionHost): List<String> {
        val names = BmlClientScope.windowHandlerNames(host.containingFile)
        if (names.isEmpty()) return emptyList()
        val body = host.text
        return names.filter {
            Regex("""\b(?:function|const|let|var)\s+${Regex.escape(it)}\b""").containsMatchIn(body)
        }
    }

    /**
     * The Kotlin wrapper for a host: (prefix, loop-closing braces). The prefix gives the fragment
     * `ctx`/`w` scope, the file's provides/prop declarations, and one real `for (VAR in EXPR) {`
     * per enclosing `<for>`; the returned closes balance those loops (spliced before the fun's `}`).
     */
    private fun kotlinScope(host: BmlInjectionHost): Pair<String, String> {
        val decls = BmlPageScope.declarations(host)
        val loops = BmlPageScope.enclosingForLoops(host)
        val formFields = BmlPageScope.enclosingFormFields(host)
        val prefix = buildString {
            append(KOTLIN_CTX_PREFIX)
            if (decls.isNotEmpty()) { append(decls); append('\n') }
            // The enclosing <form>'s submittable fields as a typed `form` object, so an action's
            // `form.<field>` arguments (`@submit="m.create(form.name, …)"`) resolve, complete, and
            // Cmd-click back to their <input name="…">. Types mirror the live-island runtime:
            // checkboxes submit Boolean, everything else String. A user symbol named `form` wins.
            if (formFields.isNotEmpty() && BmlPageScope.symbols(host).none { it.name == "form" }) {
                append("class __BmlForm {")
                formFields.forEach { (name, type) -> append(" val `$name`: $type = TODO();") }
                append(" }\nval form = __BmlForm()\n")
            }
            loops.forEach { (binding, iterable) -> append("for ($binding in $iterable) {\n") }
        }
        return prefix to "}".repeat(loops.size)
    }

    /**
     * Inject one fragment over the host's injectable range. Computes the range directly (NOT via
     * `ElementManipulators.getManipulator`, whose lookup can come up empty in the running IDE and
     * silently drop the injection — that's why `<script server>` bodies weren't being injected while
     * attributes, which compute their own range, were): the whole element for script/style/flow bodies,
     * the inner text for `{ … }` interpolation.
     */
    private fun injectWhole(
        registrar: MultiHostRegistrar,
        language: Language,
        host: BmlInjectionHost,
        prefix: String? = null,
        suffix: String? = null,
    ) {
        val len = host.textLength
        val range = if (host.node.elementType == BmlElementTypes.INTERPOLATION_HOST && len >= 2) {
            TextRange(1, len - 1) // exclude the { }
        } else {
            TextRange(0, len) // whole body: script server/client, style, flow header
        }
        if (range.isEmpty) return
        inject(registrar, language, host, range, prefix, suffix)
    }

    private fun injectAttributeValue(
        registrar: MultiHostRegistrar,
        kotlin: Language,
        host: BmlInjectionHost,
        prefix: String,
        loopCloses: String,
    ) {
        val text = host.text
        if (text.length < 2) return
        val suffix = EMIT_CLOSE + loopCloses + FUN_CLOSE

        if (isBoundAttribute(host)) {
            // whole inner value (sans quotes) is a single Kotlin VALUE expression — consume it so it's not
            // flagged "unused" (which dims it to an unreadable color).
            val featureFlagBinding = if (isFeatureFlagPredicate(host)) FEATURE_FLAG_BINDING else ""
            inject(
                registrar,
                kotlin,
                host,
                TextRange(1, text.length - 1),
                prefix + featureFlagBinding + EMIT_OPEN,
                suffix,
            )
            return
        }

        // interpolated: each `{ … }` region is an independent value expression
        var i = 1
        val limit = text.length - 1
        while (i < limit) {
            if (text[i] == '{') {
                val close = matchingBrace(text, i, limit)
                if (close > i + 1) {
                    inject(registrar, kotlin, host, TextRange(i + 1, close), prefix + EMIT_OPEN, suffix)
                }
                i = close + 1
            } else {
                i++
            }
        }
    }

    private fun inject(
        registrar: MultiHostRegistrar,
        language: Language,
        host: BmlInjectionHost,
        range: TextRange,
        prefix: String? = null,
        suffix: String? = null,
    ) {
        registrar.startInjecting(language)
            .addPlace(prefix, suffix, host, range)
            .doneInjecting()
    }

    /** True if the flow host belongs to a `<for>` tag (vs `<if>`/`<else-if>`) — picks the wrapper. */
    private fun isForFlow(host: BmlInjectionHost): Boolean {
        var sib: PsiElement? = host.prevSibling
        while (sib != null && sib.node?.elementType != BmlTokens.TAG_KEYWORD) sib = sib.prevSibling
        return sib?.text == "for"
    }

    /** True if the value's attribute name is a bound directive (`:name` / `@name`) — the lexer
     *  emits those as ATTR_DIRECTIVE, so the whole inner value is one Kotlin expression. */
    private fun isBoundAttribute(host: BmlInjectionHost): Boolean {
        var sibling: PsiElement? = host.prevSibling
        while (sibling is PsiWhiteSpace || sibling?.node?.elementType == BmlTokens.ATTR_EQ) {
            sibling = sibling.prevSibling ?: break
        }
        return sibling?.node?.elementType == BmlTokens.ATTR_DIRECTIVE
    }

    /** `:when` on `<if flag="…">` receives the same typed `flag` local as generated Kotlin. */
    private fun isFeatureFlagPredicate(host: BmlInjectionHost): Boolean {
        if (host.attributeName() != ":when") return false
        var sibling: PsiElement? = host.prevSibling
        var hasFlagAttribute = false
        while (sibling != null) {
            when (sibling.node?.elementType) {
                BmlTokens.ATTR_NAME -> if (sibling.text == "flag") hasFlagAttribute = true
                BmlTokens.TAG_KEYWORD -> return hasFlagAttribute && sibling.text in setOf("if", "else-if")
                BmlTokens.ANGLE -> return false
            }
            sibling = sibling.prevSibling
        }
        return false
    }

    /** Index of the `}` matching the `{` at [open], honoring nesting; [limit] if unbalanced. */
    private fun matchingBrace(text: String, open: Int, limit: Int): Int {
        var depth = 0
        var i = open
        while (i < limit) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
            i++
        }
        return limit
    }

    private fun firstAvailable(vararg ids: String): Language? =
        ids.firstNotNullOfOrNull { Language.findLanguageByID(it) }

    private companion object {
        // Per-host fallback wrapper (component bodies / non-`provides` <script server>): gives the
        // fragment `ctx`/`w` scope so they resolve. Page-level hosts instead use the combined
        // BmlPageScope fragment, where `provides=` values + `<for>` vars resolve. Resolution still needs
        // the page's module to have core-bml on its path.
        // `currentRenderContext()` mirrors the generated file's hoisted import: embedded server
        // code calls it bare, so the fragment declares a local equivalent with the real type.
        private const val KOTLIN_CTX_PREFIX =
            "suspend fun __bml(ctx: bosca.bml.render.RenderContext) {\nval w = ctx.writer\nfun __emit(v: Any?) {}\n" +
                "suspend fun currentRenderContext(): bosca.bml.render.RenderContext = ctx\n" +
                "suspend fun client(): bosca.bml.graphql.GraphQLClient = ctx.gql\n"

        private const val FEATURE_FLAG_BINDING =
            "val flag: bosca.bml.features.FeatureFlagEvaluation = TODO()\n"

        // Closes the `suspend fun __bml(…) {` opened in KOTLIN_CTX_PREFIX — always the suffix's tail,
        // after any enclosing-for closes.
        private const val FUN_CLOSE = "\n}"

        // Wraps a VALUE expression/body so its value is consumed — `__emit(run { <body> })` — instead of
        // sitting as a bare statement that Kotlin dims as "Expression is unused". `run { }` handles both a
        // single expression and a multi-statement body (its last expression is the value).
        private const val EMIT_OPEN = "__emit(run {\n"
        private const val EMIT_CLOSE = "\n})"

        // Closes the `for (`/`if (` opened in the flow prefix: `… EXPR) {}`.
        private const val FLOW_CLOSE = ") {}"

        // Makes the injected TypeScript a module — see the SCRIPT_CLIENT_HOST branch.
        private const val MODULE_SUFFIX = "\nexport {};\n"

        // `ctx` plus the `@bosca/bml` runtime API a `<script client>` calls (the generated module imports
        // these; the editor needs them declared so they don't show as unresolved). The reference-lib
        // directives give the fragment modern TS libs (Array.includes & co) — fragments belong to no
        // tsconfig (a config that claimed .bml files would flag them "not included"), and the no-config
        // default lib is ES5+DOM.
        private const val TS_CTX_PREFIX =
            "/// <reference lib=\"es2020\" />\n" +
                "/// <reference lib=\"dom\" />\n" +
                "/// <reference lib=\"dom.iterable\" />\n" +
                "declare const ctx: { root: HTMLElement; id: string; props: Record<string, unknown>; " +
                "scoped(name: string): string; replace(target: Element | string, html: string): void; " +
                "onUpdate(cb: () => void): void; onUnmount(cb: () => void): void };\n" +
                "declare function renderFragment(component: string, props?: Record<string, unknown>): Promise<string>;\n" +
                "declare function defineIsland(name: string, setup: (ctx: unknown) => unknown): void;\n" +
                "declare function mountAll(scope?: unknown): Promise<void>;\n" +
                "declare const bosca: { query<T = unknown>(op: unknown, vars?: Record<string, unknown>): Promise<T>; " +
                "mutate<T = unknown>(op: unknown, vars?: Record<string, unknown>): Promise<T> };\n" +
                "declare function bmlContractCall<T = unknown>(name: string, method: string, args?: unknown): Promise<T>;\n"
    }
}
