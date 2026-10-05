package bosca.bml.ide

import com.intellij.lang.Language
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Tools → "BML: Show Injection at Caret". Dumps what the plugin actually injects for the BML host under
 * the caret — the injected language, the full fragment text (prefix declarations + body), and whether
 * a few key symbols actually *resolve* inside the injected fragment (the same operation Cmd-click runs).
 * Lets a resolution/navigation problem be diagnosed from hard data instead of guesswork.
 */
class BmlInjectionStatusAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.PSI_FILE)
        if (editor == null || file == null) return
        val project = file.project

        val host = PsiTreeUtil.findElementOfClassAtOffset(
            file, editor.caretModel.offset, BmlInjectionHost::class.java, false,
        )
        val kotlinAvailable = Language.findLanguageByID("kotlin") != null

        val message = when {
            host == null ->
                "No injectable BML host at the caret.\n\nKotlin language available: $kotlinAvailable"
            else -> {
                val injected = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host)?.firstOrNull()?.first
                if (injected == null) {
                    "Host: ${host.node.elementType}\nNO injection at this host.\n\nKotlin available: $kotlinAvailable"
                } else {
                    "Host: ${host.node.elementType}\nInjected language: ${injected.language.id}\nFile module: " +
                        "${com.intellij.openapi.module.ModuleUtilCore.findModuleForPsiElement(file)?.name ?: "<none>"}\n\n" +
                        "----- injected fragment -----\n${injected.text}\n\n" +
                        "----- resolution probe (what Cmd-click sees) -----\n${resolutionProbe(injected)}"
                }
            }
        }
        // The dialog renders HTML, which would swallow generic types like `List<String>`. Escape and
        // wrap in <pre> so the fragment shows verbatim.
        val escaped = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        Messages.showInfoMessage(project, "<html><body><pre>$escaped</pre></body></html>", "BML Injection at Caret")
    }

    /**
     * For each interesting symbol, find its reference in the injected file and call `resolve()` — the
     * exact path Cmd-click / completion use. If a library symbol like `listOf` comes back UNRESOLVED
     * while a local (`greeting`) resolves, that pins the failure to injected-fragment library scope.
     */
    private fun resolutionProbe(injected: PsiElement): String {
        val text = injected.text
        return listOf("listOf", "String", "RenderContext", "greeting", "fruits").joinToString("\n") { sym ->
            val idx = text.indexOf(sym)
            if (idx < 0) return@joinToString "$sym: (not in fragment)"
            val ref = runCatching { injected.findReferenceAt(idx + 1) }.getOrNull()
            val resolved = runCatching { ref?.resolve() }
            val r = resolved.getOrNull()
            "$sym: " + when {
                resolved.isFailure -> "resolve() threw ${resolved.exceptionOrNull()?.javaClass?.simpleName}"
                ref == null -> "no reference at offset"
                r == null -> "UNRESOLVED"
                else -> "resolves -> ${r.containingFile?.name ?: r.javaClass.simpleName}"
            }
        }
    }
}
