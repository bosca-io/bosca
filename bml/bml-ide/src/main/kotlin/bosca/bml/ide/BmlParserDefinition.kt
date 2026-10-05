package bosca.bml.ide

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class BmlParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = BmlLexer()
    override fun createParser(project: Project?): PsiParser = BmlParser()
    override fun getFileNodeType(): IFileElementType = FILE
    override fun getCommentTokens(): TokenSet = COMMENTS
    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY

    override fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
        BmlElementTypes.INTERPOLATION_HOST,
        BmlElementTypes.FLOW_EXPR_HOST,
        BmlElementTypes.SCRIPT_SERVER_HOST,
        BmlElementTypes.SCRIPT_CLIENT_HOST,
        BmlElementTypes.STYLE_HOST,
        BmlElementTypes.ATTR_VALUE_HOST,
        -> BmlInjectionHost(node)
        BmlElementTypes.TAG_NAME_REF -> BmlTagNameElement(node)
        else -> ASTWrapperPsiElement(node)
    }

    override fun createFile(viewProvider: FileViewProvider): PsiFile = BmlPsiFile(viewProvider)

    companion object {
        val FILE = IFileElementType(BmlLanguage)
        val COMMENTS = TokenSet.create(BmlTokens.COMMENT)
    }
}
