package bosca.bml.ide

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase

/**
 * A tag name in the BML tree. It overrides [getReference] directly (rather than relying on a
 * `PsiReferenceContributor`, whose references don't attach to leaf tokens) so a custom tag
 * (`<badge>`) Cmd-clicks to its `<component tag="badge">` declaration. The reference is **soft**:
 * a plain HTML tag (`<div>`) resolves to nothing, with no unresolved-reference error.
 */
class BmlTagNameElement(node: ASTNode) : ASTWrapperPsiElement(node) {
    override fun getReference(): PsiReference = BmlComponentReference(this)
    override fun getReferences(): Array<PsiReference> = arrayOf(reference)
}

private class BmlComponentReference(element: PsiElement) :
    PsiReferenceBase<PsiElement>(element, TextRange(0, element.textLength), /* soft = */ true) {

    override fun resolve(): PsiElement? = BmlComponentDecls.find(element.project, element.text)
}
