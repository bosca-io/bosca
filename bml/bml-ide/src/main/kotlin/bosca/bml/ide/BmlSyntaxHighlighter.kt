package bosca.bml.ide

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType

class BmlSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = BmlLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = when (tokenType) {
        BmlTokens.ANGLE -> BRACKET_KEYS
        BmlTokens.TAG_NAME -> TAG_KEYS
        BmlTokens.TAG_KEYWORD -> KEYWORD_KEYS
        BmlTokens.ATTR_NAME -> ATTR_KEYS
        BmlTokens.ATTR_DIRECTIVE -> DIRECTIVE_KEYS
        BmlTokens.ATTR_EQ -> OPERATOR_KEYS
        BmlTokens.ATTR_VALUE -> VALUE_KEYS
        BmlTokens.COMMENT -> COMMENT_KEYS
        // Injection hosts: the injected Kotlin/TS provides the colors. Painting a BML
        // background here just boxes already-highlighted code, so contribute nothing.
        BmlTokens.TAG_EXPR, BmlTokens.INTERPOLATION, BmlTokens.FLOW_EXPR,
        BmlTokens.SCRIPT_SERVER_BODY, BmlTokens.SCRIPT_CLIENT_BODY, BmlTokens.STYLE_BODY,
        -> EMPTY_KEYS
        else -> EMPTY_KEYS
    }

    companion object {
        // Keys fall back to the generic language keys, and the dark-tuned colors that used to be
        // hardcoded here live in colorSchemes/BmlDarcula.xml (additionalTextAttributes in plugin.xml)
        // — the MARKUP_TAG / MARKUP_ATTRIBUTE fallbacks alone render near the default text color in
        // some dark themes, which made BML structure look like plain text. Still overridable in
        // Settings > Editor > Color Scheme.
        val BRACKET: TextAttributesKey =
            createTextAttributesKey("BML_ANGLE", DefaultLanguageHighlighterColors.MARKUP_TAG)
        val TAG: TextAttributesKey =
            createTextAttributesKey("BML_TAG_NAME", DefaultLanguageHighlighterColors.MARKUP_TAG)
        val KEYWORD: TextAttributesKey =
            createTextAttributesKey("BML_TAG_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
        val ATTR: TextAttributesKey =
            createTextAttributesKey("BML_ATTR_NAME", DefaultLanguageHighlighterColors.MARKUP_ATTRIBUTE)
        val DIRECTIVE: TextAttributesKey =
            createTextAttributesKey("BML_ATTR_DIRECTIVE", DefaultLanguageHighlighterColors.METADATA)
        val OPERATOR: TextAttributesKey =
            createTextAttributesKey("BML_ATTR_EQ", DefaultLanguageHighlighterColors.OPERATION_SIGN)
        val VALUE: TextAttributesKey =
            createTextAttributesKey("BML_ATTR_VALUE", DefaultLanguageHighlighterColors.STRING)
        val COMMENT: TextAttributesKey =
            createTextAttributesKey("BML_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT)

        private val BRACKET_KEYS = arrayOf(BRACKET)
        private val TAG_KEYS = arrayOf(TAG)
        private val KEYWORD_KEYS = arrayOf(KEYWORD)
        private val ATTR_KEYS = arrayOf(ATTR)
        private val DIRECTIVE_KEYS = arrayOf(DIRECTIVE)
        private val OPERATOR_KEYS = arrayOf(OPERATOR)
        private val VALUE_KEYS = arrayOf(VALUE)
        private val COMMENT_KEYS = arrayOf(COMMENT)
        private val EMPTY_KEYS = emptyArray<TextAttributesKey>()
    }
}
