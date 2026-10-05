package bosca.bml.ide

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/**
 * The site-level client scope a `.bml` file renders against, discovered from the BML source
 * layout convention: markup under `src/main/bml`, the site's global client assets in the
 * sibling `src/main/client` (for example `site.ts` + `site.css`).
 *
 *  - `*.css` there is the every-page app.css tier (`BmlServer(globalCss = …)`). Its custom
 *    properties are prepended to every `<style>` injection (as a `:root { … }` block) so
 *    `var(--divider)` resolves instead of flagging "unresolved custom property".
 *  - Browser GLOBALS (site.ts's `auth`) are deliberately NOT re-declared in the `<script client>`
 *    injection: IntelliJ's JS index resolves `declare global { var auth: BoscaAuth }` project-wide
 *    — including from injected fragments — and a per-fragment duplicate turns every use into a
 *    multi-resolve ("multiple implementations") that breaks member navigation. The client entry's
 *    own `declare global` is the single source of truth.
 */
object BmlClientScope {

    /** The client-dir contribution to a `.bml` file's injections; [EMPTY] outside the convention. */
    data class Scope(
        /** A `:root { --x: v; … }` block for the `<style>` CSS prefix, or null when there is none. */
        val cssPrefix: String?,
    )

    private val EMPTY = Scope(null)

    /**
     * [file]'s client scope; cached until any PSI changes (the source files live OUTSIDE [file],
     * so the file itself is not a sufficient dependency).
     */
    fun scope(file: PsiFile): Scope = CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(build(file), PsiModificationTracker.MODIFICATION_COUNT)
    }

    /**
     * The page-script function names referenced by standard `on<event>="name(…)"` attributes in
     * [file] — mirrors the compiler's `BmlClientCodeGenerator.clientHandlerNames`, which publishes
     * exactly these to `window` (`Object.assign(window, { … })`) so plain HTML handlers work.
     * The injector appends the same statement to the `<script client>` injection, so the IDE sees
     * what the browser sees and stops marking the handlers "unused".
     */
    fun windowHandlerNames(file: PsiFile): List<String> = CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(collectHandlerNames(file), file)
    }

    private fun build(file: PsiFile): Scope {
        val virtualFile = file.originalFile.virtualFile ?: return EMPTY
        val clientDir = clientDir(virtualFile) ?: return EMPTY
        val psi = PsiManager.getInstance(file.project)
        val properties = LinkedHashMap<String, String>() // --name -> value
        for (child in clientDir.children.sortedBy { it.name }) {
            if (child.isDirectory) continue
            if (!child.name.endsWith(".css")) continue
            collectCustomProperties(psi.findFile(child)?.text ?: continue, properties)
        }
        val cssPrefix = properties.takeIf { it.isNotEmpty() }?.entries
            ?.joinToString("", prefix = ":root {\n", postfix = "}\n") { (name, value) -> "  $name: $value;\n" }
        return Scope(cssPrefix)
    }

    /** `src/main/client` for a file under `src/main/bml` — the nearest `bml` ancestor's sibling. */
    private fun clientDir(file: VirtualFile): VirtualFile? {
        var dir = file.parent
        while (dir != null) {
            if (dir.name == "bml") {
                dir.parent?.findChild("client")?.takeIf { it.isDirectory }?.let { return it }
            }
            dir = dir.parent
        }
        return null
    }

    /** Custom property declarations (`--name: value`) of a stylesheet, first one wins. Visible for testing. */
    internal fun collectCustomProperties(text: String, into: LinkedHashMap<String, String>) {
        CUSTOM_PROPERTY.findAll(text).forEach { into.putIfAbsent(it.groupValues[1], it.groupValues[2].trim()) }
    }

    private fun collectHandlerNames(file: PsiFile): List<String> {
        val names = LinkedHashSet<String>()
        var node = file.node.firstChildNode
        var lastAttr: String? = null
        while (node != null) {
            when (node.elementType) {
                BmlTokens.ATTR_NAME -> lastAttr = node.text
                // Bound directives (`@click` / `:value`) are Kotlin-side, never window handlers.
                BmlTokens.ATTR_DIRECTIVE -> lastAttr = null
                BmlElementTypes.ATTR_VALUE_HOST -> {
                    val attr = lastAttr
                    // Same gate as the compiler: a static `on<event>` attribute (name length >= 4)
                    // whose value is pure text (no `{ … }` interpolation) and shaped `name(…)`.
                    if (attr != null && attr.startsWith("on") && attr.length >= 4) {
                        val value = unquote(node.text)
                        if (!value.contains('{')) {
                            HANDLER_CALL.find(value)?.groupValues?.get(1)?.let { names += it }
                        }
                    }
                    lastAttr = null
                }
            }
            node = node.treeNext
        }
        return names.toList()
    }

    private fun unquote(raw: String): String {
        val s = raw.trim()
        return if (s.length >= 2 && (s.first() == '"' || s.first() == '\'') && s.last() == s.first()) {
            s.substring(1, s.length - 1)
        } else {
            s
        }
    }

    /** A custom-property DECLARATION `--name: value` — a `var(--name)` use has no `:` after the name. */
    private val CUSTOM_PROPERTY = Regex("""(--[A-Za-z0-9_-]+)\s*:\s*([^;{}]+)""")

    /** Mirrors the compiler: `createAccount(event)` → `createAccount`; anything fancier is assumed global. */
    private val HANDLER_CALL = Regex("""^\s*([A-Za-z_$][A-Za-z0-9_$]*)\s*\(""")
}
