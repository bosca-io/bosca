package bosca.bml.ide

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.util.ParenthesesInsertHandler
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.psi.KtExpression

/**
 * Member completion for Kotlin embedded in `.bml` — `item.<caret>` offering `href`/`name`/…
 *
 * K2 never completes this in place: the injected fragment has no library/module resolve scope (the
 * same wall the goto handler works around). So on a dot-qualified position this re-anchors the
 * injected text in a module code fragment ([BmlFragmentResolver.withAnchoredFragment] — where
 * `provides` initializers and the re-opened `<for>` loops give receivers their REAL types), types
 * the receiver with the Analysis API, and offers its members.
 *
 * Registered in bml-kotlin.xml (touches org.jetbrains.kotlin.* — must not load without the Kotlin
 * plugin).
 */
class BmlKotlinMemberCompletionContributor : CompletionContributor() {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val position = parameters.position
        val host = InjectedLanguageManager.getInstance(position.project)
            .getInjectionHost(position) as? BmlInjectionHost ?: return
        // The ORIGINAL injected text (no completion dummy identifier) — offsets before the caret
        // are identical, and the receiver ends before the dot.
        val text = parameters.originalFile.text
        val offset = minOf(parameters.offset, text.length)
        var i = offset
        while (i > 0 && (text[i - 1].isLetterOrDigit() || text[i - 1] == '_')) i--
        if (i == 0 || text[i - 1] != '.') return // only dot-qualified positions
        val dot = i - 1

        for (member in membersAt(host, text, dot)) {
            var lookup = LookupElementBuilder.create(member.name).withTypeText(member.typeText, true)
            if (member.isFunction) {
                lookup = lookup
                    .withTailText("()", true)
                    .withInsertHandler(ParenthesesInsertHandler.getInstance(member.hasParameters))
            }
            result.addElement(lookup)
        }
    }

    private data class Member(val name: String, val typeText: String, val isFunction: Boolean, val hasParameters: Boolean)

    /** The members of the type of the expression ending at [dot], via an anchored fragment. */
    private fun membersAt(host: PsiElement, fragmentText: String, dot: Int): List<Member> =
        BmlFragmentResolver.withAnchoredFragment(host, fragmentText) { fragment ->
            val leaf = fragment.findElementAt(dot - 1) ?: return@withAnchoredFragment null
            // The WIDEST expression ending exactly at the dot is the receiver (`page.items`, not `items`).
            var receiver = PsiTreeUtil.getParentOfType(leaf, KtExpression::class.java, false)
                ?: return@withAnchoredFragment null
            while (true) {
                val parent = PsiTreeUtil.getParentOfType(receiver, KtExpression::class.java, true) ?: break
                if (parent.textRange.endOffset == receiver.textRange.endOffset) receiver = parent else break
            }
            if (receiver.textRange.endOffset != dot) return@withAnchoredFragment null
            typeMembers(receiver).takeIf { it.isNotEmpty() }
        } ?: emptyList()

    private fun typeMembers(receiver: KtExpression): List<Member> = analyze(receiver) {
        val type = receiver.expressionType ?: return@analyze emptyList()
        val classSymbol = type.expandedSymbol ?: return@analyze emptyList()
        val out = LinkedHashMap<String, Member>()
        for (callable in classSymbol.memberScope.callables) {
            val name = (callable as? KaNamedSymbol)?.name?.asString() ?: continue
            if (name.startsWith("<") || name.startsWith("component") && name.drop(9).toIntOrNull() != null) continue
            val isFunction = callable is KaFunctionSymbol
            val hasParameters = (callable as? KaFunctionSymbol)?.valueParameters?.isNotEmpty() == true
            val typeText = runCatching { callable.returnType.toString() }.getOrDefault("")
            out.putIfAbsent(name + if (isFunction) "()" else "", Member(name, typeText, isFunction, hasParameters))
        }
        out.values.toList()
    }
}
