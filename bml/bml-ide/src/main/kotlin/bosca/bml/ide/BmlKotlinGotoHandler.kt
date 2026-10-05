package bosca.bml.ide

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.lang.Language
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement

/**
 * Cmd-click / Go-to-Declaration for Kotlin embedded in `.bml`.
 *
 * The language injection gives `<script server>` / `{ … }` / bound-attribute Kotlin its highlighting and
 * structure, but K2 doesn't resolve references inside the injected fragment in place — so the platform's
 * own goto there finds no target. This handler re-resolves the symbol under the caret through a
 * module-anchored code fragment (see [BmlFragmentResolver]) and returns the real declaration.
 *
 * Gated on the Kotlin plugin being present; [BmlFragmentResolver] (which references Kotlin-plugin classes)
 * is only touched after that check, so the plugin still loads in IDEs without Kotlin.
 */
class BmlKotlinGotoHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(element: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        if (element == null || Language.findLanguageByID("kotlin") == null) return null
        val ilm = InjectedLanguageManager.getInstance(element.project)
        // Only act when the caret is inside a BML-injected Kotlin fragment.
        val host = ilm.getInjectionHost(element) as? BmlInjectionHost ?: return null
        val injectedFile = element.containingFile ?: return null
        // `element` is the leaf in the injected Kotlin file; its offset there is 1:1 with the fragment text.
        val at = (element.textRange.startOffset + element.textRange.endOffset) / 2
        val target = BmlFragmentResolver.resolve(host, injectedFile.text, at) ?: return null
        return arrayOf(target)
    }
}
