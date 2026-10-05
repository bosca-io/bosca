package bosca.bml.ide

import com.intellij.application.options.CodeStyle
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor
import com.intellij.psi.impl.source.tree.LeafElement

/**
 * TRUE formatting for the embedded `<script server>` / `<script client>` / `<style>` bodies.
 *
 * The block-based pass (BmlFormattingModelBuilder) can only re-base a body — it's one flat token,
 * and the real language formatters can't be delegated to through the injection (their fragments
 * live inside synthetic wrappers, so their indent math nests bodies phantom levels deep). So after
 * the block pass, each body is formatted OUT-OF-BAND: its content goes into a synthetic standalone
 * file of the body's language (Kotlin bodies get a plain `fun __bml() { … }` shell we control),
 * that file is reformatted with the project's code style for that language, and the result is
 * spliced back re-based to the body's indent. A language that isn't installed (TypeScript in
 * Community) leaves its bodies untouched.
 */
class BmlEmbeddedBodyFormatter : PostFormatProcessor {

    override fun processElement(source: PsiElement, settings: CodeStyleSettings): PsiElement {
        if (source.isValid) processText(source.containingFile, source.textRange, settings)
        return source
    }

    override fun processText(source: PsiFile, rangeToReformat: TextRange, settings: CodeStyleSettings): TextRange {
        if (source !is BmlPsiFile) return rangeToReformat
        var delta = 0
        var node: ASTNode? = source.node.firstChildNode
        while (node != null) {
            val next = node.treeNext
            if (node.elementType in BODY_HOSTS && rangeToReformat.grown(delta).contains(node.textRange)) {
                delta += try {
                    formatBody(source, node, settings)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    0 // a body the language formatter chokes on stays as written — never break reformat
                }
            }
            node = next
        }
        return rangeToReformat.grown(delta)
    }

    /** Formats one body host in place; returns the length delta. */
    private fun formatBody(file: BmlPsiFile, host: ASTNode, settings: CodeStyleSettings): Int {
        val language = when (host.elementType) {
            BmlElementTypes.SCRIPT_SERVER_HOST -> Language.findLanguageByID("kotlin")
            BmlElementTypes.SCRIPT_CLIENT_HOST ->
                listOf("TypeScript", "ECMAScript 6", "JavaScript").firstNotNullOfOrNull { Language.findLanguageByID(it) }
            else -> Language.findLanguageByID("CSS")
        } ?: return 0
        val leaf = host.firstChildNode as? LeafElement ?: return 0
        val text = leaf.text
        if (text.isBlank()) return 0

        // lead (through the last newline before content) | content | trail (from the last content char).
        val contentStart = text.indexOfFirst { !it.isWhitespace() }
        val contentEnd = text.indexOfLast { !it.isWhitespace() } + 1
        val lastLead = text.lastIndexOf('\n', contentStart)
        val lead = if (lastLead >= 0) text.substring(0, lastLead + 1) else ""
        val base = if (lastLead >= 0) text.substring(lastLead + 1, contentStart) else ""
        val trail = text.substring(contentEnd)
        val content = text.substring(contentStart, contentEnd)

        val formatted = formatStandalone(file, language, dedent(content), settings, host.elementType) ?: return 0
        val rebased = formatted.lines().joinToString("\n") { if (it.isBlank()) "" else base + it }

        val newText = lead + rebased + trail
        if (newText == text) return 0
        leaf.replaceWithText(newText)
        return newText.length - text.length
    }

    /**
     * The body formatted as a standalone file of [language] with the project's code style. Kotlin
     * bodies are statements, not a file — they format inside a `fun __bml() { … }` shell whose one
     * indent level is stripped back off the result.
     */
    private fun formatStandalone(
        file: BmlPsiFile,
        language: Language,
        body: String,
        settings: CodeStyleSettings,
        hostType: com.intellij.psi.tree.IElementType,
    ): String? {
        val kotlin = hostType == BmlElementTypes.SCRIPT_SERVER_HOST
        val sourceText = if (kotlin) "fun __bml() {\n$body\n}" else body
        val synthetic = PsiFileFactory.getInstance(file.project)
            .createFileFromText("__bmlBody", language, sourceText, false, false)
        CodeStyle.doWithTemporarySettings(
            file.project,
            settings,
            Runnable { CodeStyleManager.getInstance(file.project).reformat(synthetic) },
        )
        val result = synthetic.text
        if (!kotlin) return result.trim('\n')
        // Strip the `fun __bml() {` / `}` shell lines and their one indent level.
        val inner = result.lines().drop(1).dropLast(1)
        return dedent(inner.joinToString("\n")).trim('\n').ifBlank { null }
    }

    /** [text] with the common leading indentation of its non-blank lines removed. */
    private fun dedent(text: String): String {
        val lines = text.lines()
        val common = lines.filter { it.isNotBlank() }
            .minOfOrNull { line -> line.takeWhile { it == ' ' || it == '\t' }.length } ?: 0
        if (common == 0) return text
        return lines.joinToString("\n") { if (it.isBlank()) "" else it.drop(common) }
    }

    private companion object {
        val BODY_HOSTS = setOf(
            BmlElementTypes.SCRIPT_SERVER_HOST,
            BmlElementTypes.SCRIPT_CLIENT_HOST,
            BmlElementTypes.STYLE_HOST,
        )
    }
}
