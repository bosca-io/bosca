package bosca.bml.ide

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType

/**
 * A flat parser: every lexer token becomes a leaf, except the injectable tokens
 * (interpolation + script bodies), which are wrapped in a composite host node so the
 * injector can attach a real language to them.
 */
class BmlParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val file = builder.mark()
        while (!builder.eof()) {
            // Injectable regions become injection-host composites; a plain tag name becomes a
            // reference composite (so it can navigate to its <component> declaration).
            val composite = hostTypeFor(builder.tokenType)
                ?: if (builder.tokenType == BmlTokens.TAG_NAME) BmlElementTypes.TAG_NAME_REF else null
            if (composite != null) {
                val marker = builder.mark()
                builder.advanceLexer()
                marker.done(composite)
            } else {
                builder.advanceLexer()
            }
        }
        file.done(root)
        return builder.treeBuilt
    }

    private fun hostTypeFor(token: IElementType?): IElementType? = when (token) {
        BmlTokens.INTERPOLATION -> BmlElementTypes.INTERPOLATION_HOST
        BmlTokens.FLOW_EXPR -> BmlElementTypes.FLOW_EXPR_HOST
        BmlTokens.SCRIPT_SERVER_BODY -> BmlElementTypes.SCRIPT_SERVER_HOST
        BmlTokens.SCRIPT_CLIENT_BODY -> BmlElementTypes.SCRIPT_CLIENT_HOST
        BmlTokens.STYLE_BODY -> BmlElementTypes.STYLE_HOST
        BmlTokens.ATTR_VALUE -> BmlElementTypes.ATTR_VALUE_HOST
        else -> null
    }
}
