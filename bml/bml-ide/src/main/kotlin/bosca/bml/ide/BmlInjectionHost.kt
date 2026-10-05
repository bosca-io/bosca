package bosca.bml.ide

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.psi.LiteralTextEscaper
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.impl.source.tree.injected.InjectionBackgroundSuppressor

/**
 * A PSI node that hosts an injected language (Kotlin or TypeScript). The platform asks this
 * for its escaper + accepts text edits made inside the injected fragment via [updateText].
 * The injected sub-range (e.g. skipping the `{ }` of an interpolation) is supplied by
 * [BmlHostManipulator.getRangeInElement], which both the injector and the simple escaper use.
 *
 * Implements [InjectionBackgroundSuppressor] so the platform does NOT paint its default "injected
 * language fragment" background tint over our regions — on a dark theme that tint is dark enough to
 * make embedded code (`{ greeting }`, `<script server>` bodies, etc.) low-contrast and hard to read.
 * The embedded code is highlighted entirely by the injected Kotlin/TS; no BML background is wanted.
 */
class BmlInjectionHost(node: ASTNode) :
    ASTWrapperPsiElement(node), PsiLanguageInjectionHost, InjectionBackgroundSuppressor {

    override fun isValidHost(): Boolean = true

    /**
     * A `class="…"` value contributes references from each class name to its CSS-class declaration
     * (see [BmlCssClassRefs]) — so the selectors show as used + are navigable. Gated on the CSS plugin
     * being present so [BmlCssClassRefs] (which references CSS-plugin classes) isn't loaded without it.
     */
    override fun getReferences(): Array<PsiReference> {
        if (node.elementType == BmlElementTypes.ATTR_VALUE_HOST &&
            Language.findLanguageByID("CSS") != null &&
            isClassAttributeValue()
        ) {
            return BmlCssClassRefs.referencesFor(this)
        }
        return super.getReferences()
    }

    private fun isClassAttributeValue(): Boolean = attributeName() == "class"

    /** The name of the attribute this value belongs to (`class`, `id`, `href`, …), or null. */
    fun attributeName(): String? {
        var sib = prevSibling
        while (sib is PsiWhiteSpace || sib?.node?.elementType == BmlTokens.ATTR_EQ) {
            sib = sib.prevSibling ?: return null
        }
        return if (sib?.node?.elementType == BmlTokens.ATTR_NAME ||
            sib?.node?.elementType == BmlTokens.ATTR_DIRECTIVE
        ) {
            sib.text
        } else {
            null
        }
    }

    override fun updateText(text: String): PsiLanguageInjectionHost {
        val leaf = node.firstChildNode
        if (leaf is LeafElement) {
            leaf.replaceWithText(text)
        }
        return this
    }

    override fun createLiteralTextEscaper(): LiteralTextEscaper<BmlInjectionHost> =
        LiteralTextEscaper.createSimple(this)
}
