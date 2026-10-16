package bosca.bml.ide

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/**
 * Folds paired tag bodies in the flat BML PSI without resolving component declarations.
 * Opening/closing tags remain visible; injected bodies, attributes, and comments are opaque
 * to tag matching. This also works for namespaced components and read-only library sources.
 */
class BmlFoldingBuilder : FoldingBuilderEx() {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val regions = mutableListOf<FoldingDescriptor>()
        val stack = mutableListOf<OpenTag>()
        var head: ASTNode? = null
        var name: String? = null
        var closing = false
        var token = root.node.firstChildNode
        while (token != null) {
            ProgressManager.checkCanceled()
            when (token.elementType) {
                BmlTokens.ANGLE -> when (token.text) {
                    "<", "</" -> {
                        head = token
                        name = null
                        closing = token.text == "</"
                    }
                    ">", "/>" -> {
                        val tagHead = head
                        val tagName = name
                        if (tagHead != null && tagName != null && token.text == ">") {
                            if (closing) {
                                val index = stack.indexOfLast { it.name == tagName }
                                if (index >= 0) {
                                    val open = stack[index]
                                    val end = tagHead.startOffset
                                    if (open.bodyStart < end &&
                                        document.getLineNumber(open.bodyStart) < document.getLineNumber(end)
                                    ) {
                                        regions += FoldingDescriptor(open.node, TextRange(open.bodyStart, end))
                                    }
                                    // Discard unclosed descendants so malformed edits never create crossing folds.
                                    stack.subList(index, stack.size).clear()
                                }
                            } else if (tagName != "else" && tagName != "else-if") {
                                // Even a native void name can be a paired library component (e.g. <input>).
                                // Unpaired void tags are discarded when their enclosing tag closes.
                                stack += OpenTag(tagName, tagHead, token.textRange.endOffset)
                            }
                        }
                        head = null
                        name = null
                    }
                }
                BmlTokens.TAG_KEYWORD, BmlElementTypes.TAG_NAME_REF -> if (head != null && name == null) {
                    name = token.text
                }
            }
            token = token.treeNext
        }
        return regions.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String = "…"

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false

    private data class OpenTag(val name: String, val node: ASTNode, val bodyStart: Int)
}
