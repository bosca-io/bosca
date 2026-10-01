package bosca.bml.ide

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiFile

/**
 * Drops tsconfig-membership errors inside BML-injected fragments.
 *
 * The TypeScript accessibility checker (`TypeScriptConfigAccessibilityChecker`) flags references
 * whose declaration lives in a tsconfig-scoped file when the REFERENCING file isn't part of that
 * config — "Corresponding file is not included in tsconfig.json". For a `<script client>` fragment
 * the referencing "file" is the host `.bml`, which can NEVER be included in a tsconfig (tsc only
 * includes TS extensions), so inside these fragments that complaint is noise by construction, in
 * ANY project: it red-flags perfectly valid code whenever a referenced declaration happens to sit
 * in tsconfig-scoped source — typical in monorepos where a library exists both as live source
 * (config-scoped) and as its published node_modules copy.
 *
 * Everything else — real type errors, unresolved names — passes through untouched.
 */
class BmlInjectedTsHighlightFilter : HighlightInfoFilter {
    override fun accept(highlightInfo: HighlightInfo, file: PsiFile?): Boolean {
        if (file == null) return true
        val description = highlightInfo.description ?: return true
        if (!description.contains("tsconfig")) return true
        val host = InjectedLanguageManager.getInstance(file.project).getInjectionHost(file)
        return host !is BmlInjectionHost
    }
}
