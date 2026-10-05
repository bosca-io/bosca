package bosca.bml.ide

import com.intellij.psi.PsiElement
import com.intellij.psi.css.CssSelectorSuffix
import com.intellij.psi.css.CssSelectorSuffixType
import com.intellij.psi.css.usages.CssClassOrIdUsagesProvider
import com.intellij.psi.util.PsiTreeUtil

/**
 * Tells the CSS "unused selector" inspection that a `.bml` `class="…"` / `id="…"` value USES the CSS
 * class/id. This is the extension point the CSS plugin searches through: `CssSearchHelper.isClassOrIdUsed`
 * walks from the injected `<style>` CSS to the top-level `.bml`, finds the selector name as a word, then
 * asks each `CssClassOrIdUsagesProvider` whether that occurrence is a real usage. Without this, selectors
 * in a `<style>` block are falsely reported unused — the usages live in the BML markup, outside the
 * isolated injected CSS fragment, so the inspection's own search can't see them.
 *
 * Only LITERAL names match (the name must appear in the source as a word). A dynamic value like
 * `class="badge-{ tone }"` therefore can't mark `.badge-info` / `.badge-hot` used — the same limitation
 * any tooling (including HTML) has with computed class names.
 *
 * Uses the CSS plugin API, so it's registered only when the CSS plugin is present (bml-css.xml).
 */
class BmlCssClassUsagesProvider : CssClassOrIdUsagesProvider {
    override fun isUsage(suffix: CssSelectorSuffix, element: PsiElement, offset: Int): Boolean {
        val wantedAttr = when (suffix.type) {
            CssSelectorSuffixType.CLASS -> "class"
            CssSelectorSuffixType.ID -> "id"
            else -> return false
        }
        val host = PsiTreeUtil.getParentOfType(element, BmlInjectionHost::class.java, false) ?: return false
        return host.node.elementType == BmlElementTypes.ATTR_VALUE_HOST && host.attributeName() == wantedAttr
    }
}
