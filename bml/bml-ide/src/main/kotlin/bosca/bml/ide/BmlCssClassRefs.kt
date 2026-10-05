package bosca.bml.ide

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveResult
import com.intellij.psi.css.CssClass
import com.intellij.psi.util.PsiTreeUtil

/**
 * Turns each class name in a `class="…"` value into a reference to its CSS-class declaration in the
 * file's `<style>` blocks. A fully static name (`badge`) resolves exactly; a name with an interpolation
 * (`badge-{ tone }`) resolves by its static prefix to every `badge-*` rule (the value is dynamic, but the
 * intent is clearly that family). This makes the selectors show as USED (no false "unused" warning) and
 * provides Cmd-click + class-name completion — the wiring HTML's class attribute gets for free.
 *
 * Uses the CSS plugin API (CssClass); callers must gate on the CSS language being present so this class
 * isn't loaded when the CSS plugin is absent.
 */
object BmlCssClassRefs {

    /** One reference per class name in the `class="…"` host value. */
    fun referencesFor(host: BmlInjectionHost): Array<PsiReference> {
        val text = host.text
        val refs = ArrayList<PsiReference>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isWhitespace() || c == '"' || c == '\'') { i++; continue }
            val start = i
            var braceAt = -1
            while (i < text.length && !text[i].isWhitespace() && text[i] != '"' && text[i] != '\'') {
                if (text[i] == '{') {
                    if (braceAt < 0) braceAt = i
                    var depth = 0
                    while (i < text.length) {
                        when (text[i]) { '{' -> depth++; '}' -> depth-- }
                        i++
                        if (depth == 0) break
                    }
                } else {
                    i++
                }
            }
            when {
                braceAt < 0 -> // fully static class name → exact reference
                    refs.add(BmlCssClassReference(host, TextRange(start, i), text.substring(start, i), exact = true))
                braceAt > start -> // dynamic class with a static prefix (badge-{ … }) → prefix reference
                    refs.add(BmlCssClassReference(host, TextRange(start, braceAt), text.substring(start, braceAt), exact = false))
            }
        }
        return refs.toTypedArray()
    }

    /** All CSS class declarations in the file's `<style>` blocks (their injected CSS). */
    fun cssClasses(context: PsiElement): List<CssClass> {
        val file = context.containingFile ?: return emptyList()
        val ilm = InjectedLanguageManager.getInstance(context.project)
        return PsiTreeUtil.findChildrenOfType(file, BmlInjectionHost::class.java)
            .filter { it.node.elementType == BmlElementTypes.STYLE_HOST }
            .flatMap { styleHost ->
                (ilm.getInjectedPsiFiles(styleHost) ?: emptyList()).flatMap { pair ->
                    PsiTreeUtil.findChildrenOfType(pair.first, CssClass::class.java)
                }
            }
    }

    fun nameOf(c: CssClass): String {
        val n = c.name
        return (if (!n.isNullOrEmpty()) n else c.text).trim().removePrefix(".")
    }
}

class BmlCssClassReference(
    host: BmlInjectionHost,
    range: TextRange,
    private val name: String,
    private val exact: Boolean,
) : PsiPolyVariantReferenceBase<BmlInjectionHost>(host, range) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        BmlCssClassRefs.cssClasses(element)
            .filter { val cn = BmlCssClassRefs.nameOf(it); if (exact) cn == name else cn.startsWith(name) }
            .map { PsiElementResolveResult(it) }
            .toTypedArray()

    override fun getVariants(): Array<Any> =
        BmlCssClassRefs.cssClasses(element).map { BmlCssClassRefs.nameOf(it) }.distinct().toTypedArray()
}
