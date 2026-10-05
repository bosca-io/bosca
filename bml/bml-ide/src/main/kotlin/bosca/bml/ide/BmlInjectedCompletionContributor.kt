package bosca.bml.ide

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.injection.InjectedLanguageManager

/**
 * Offers a `.bml` file's `provides` values + component props as completions **inside the injected
 * Kotlin** (interpolation, `<script server>`, bound attributes). IntelliJ resolves these names when
 * typed out (they're declared in the injection prefix) but doesn't always *suggest* them, so a use
 * like `greeting` inside another script wouldn't pop up — this fills that in. Registered for all
 * languages and gated to BML injection hosts, so it never affects ordinary Kotlin files.
 */
class BmlInjectedCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val host = InjectedLanguageManager.getInstance(parameters.position.project)
            .getInjectionHost(parameters.position) as? BmlInjectionHost ?: return
        for (symbol in BmlPageScope.symbols(host)) {
            result.addElement(
                LookupElementBuilder.create(symbol.name).withTypeText(symbol.type).withBoldness(true),
            )
        }
        // The `<for>` variables in scope at this host (`item` inside `<for item in page.items>`).
        for ((binding, iterable) in BmlPageScope.enclosingForLoops(host)) {
            for (name in binding.removeSurrounding("(", ")").split(',')) {
                result.addElement(
                    LookupElementBuilder.create(name.trim()).withTypeText("in $iterable").withBoldness(true),
                )
            }
        }
    }
}
