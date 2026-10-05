package bosca.bml.ide

import com.intellij.lang.ASTNode
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Finds `<component tag="…">` declarations across the project's `.bml` files. Backs Cmd-click
 * navigation from a custom tag (`<badge>`) to its declaration, and tag completion. The BML PSI is
 * flat (one leaf per lexer token), so this walks each file's top-level nodes and reads the literal
 * `tag="…"` that follows a `component` keyword.
 */
object BmlComponentDecls {

    /** A component declaration: its `tag` and the PSI element to navigate to (the tag value). */
    data class Decl(val tag: String, val navElement: PsiElement)

    /** Every component declared in any `.bml` file in [project]. */
    fun all(project: Project): List<Decl> {
        val result = mutableListOf<Decl>()
        val psiManager = PsiManager.getInstance(project)
        for (file in FileTypeIndex.getFiles(BmlFileType, GlobalSearchScope.allScope(project))) {
            val psiFile = psiManager.findFile(file) ?: continue
            var node: ASTNode? = psiFile.node.firstChildNode
            while (node != null) {
                if (node.elementType == BmlTokens.TAG_KEYWORD && node.text == "component") {
                    tagAttrAfter(node)?.let { result += it }
                }
                node = node.treeNext
            }
        }
        return result
    }

    /** The declaration element for [tag], or null if no component declares it. */
    fun find(project: Project, tag: String): PsiElement? =
        all(project).firstOrNull { it.tag == tag }?.navElement

    /** The `<prop name="…">` names declared by component [tag] (for attribute completion on `<tag …>`). */
    fun propsOf(project: Project, tag: String): List<String> {
        val psiManager = PsiManager.getInstance(project)
        for (file in FileTypeIndex.getFiles(BmlFileType, GlobalSearchScope.allScope(project))) {
            val psiFile = psiManager.findFile(file) ?: continue
            var node: ASTNode? = psiFile.node.firstChildNode
            var inMatch = false
            var lastAngle = ""
            val props = mutableListOf<String>()
            while (node != null) {
                when (node.elementType) {
                    BmlTokens.ANGLE -> lastAngle = node.text
                    BmlTokens.TAG_KEYWORD -> {
                        val close = lastAngle == "</"
                        when (node.text) {
                            "component" -> if (close) { if (inMatch) return props } else inMatch = tagAttrAfter(node)?.tag == tag
                            "prop" -> if (inMatch && !close) literalAttrAfter(node, "name")?.let { props += it }
                        }
                    }
                }
                node = node.treeNext
            }
            if (inMatch) return props
        }
        return emptyList()
    }

    /** Scan forward from a `component` keyword for its `tag="…"` value (stopping at the tag's `>`). */
    private fun tagAttrAfter(componentKeyword: ASTNode): Decl? {
        var node: ASTNode? = componentKeyword.treeNext
        var sawTagName = false
        while (node != null) {
            when (node.elementType) {
                BmlTokens.ANGLE -> return null // reached `>` / `/>` — no tag attribute
                BmlTokens.ATTR_NAME -> sawTagName = node.text == "tag"
                BmlElementTypes.ATTR_VALUE_HOST ->
                    if (sawTagName) return Decl(unquote(node.text), node.psi)
            }
            node = node.treeNext
        }
        return null
    }

    /** Scan forward from a tag keyword for the literal value of attribute [attrName] (stopping at `>`). */
    private fun literalAttrAfter(keyword: ASTNode, attrName: String): String? {
        var node: ASTNode? = keyword.treeNext
        var sawAttr = false
        while (node != null) {
            when (node.elementType) {
                BmlTokens.ANGLE -> return null
                BmlTokens.ATTR_NAME -> sawAttr = node.text == attrName
                BmlElementTypes.ATTR_VALUE_HOST -> if (sawAttr) return unquote(node.text)
            }
            node = node.treeNext
        }
        return null
    }

    private fun unquote(raw: String): String {
        val s = raw.trim()
        return if (s.length >= 2 && (s.first() == '"' || s.first() == '\'') && s.last() == s.first()) {
            s.substring(1, s.length - 1)
        } else {
            s
        }
    }
}
