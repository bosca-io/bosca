package bosca.bml.ide

import com.intellij.psi.tree.IElementType

/** A lexer token type belonging to the BML language. */
class BmlTokenType(debugName: String) : IElementType(debugName, BmlLanguage)

/** A composite (parser) element type belonging to the BML language. */
class BmlElementType(debugName: String) : IElementType(debugName, BmlLanguage)

/** Lexer tokens produced by [BmlLexer]. Tags are tokenized into their parts (brackets, name,
 *  attribute name/`=`/value) for highlighting, while the injectable regions (script bodies +
 *  interpolation) stay as single tokens so the parser can wrap them as injection hosts. */
object BmlTokens {
    val ANGLE = BmlTokenType("BML_ANGLE")                      // < </ > /> tag brackets
    val TAG_NAME = BmlTokenType("BML_TAG_NAME")                // a plain element name (div, h1, …)
    val TAG_KEYWORD = BmlTokenType("BML_TAG_KEYWORD")          // a BML special/control tag (page/for/if/island/…)
    val ATTR_NAME = BmlTokenType("BML_ATTR_NAME")              // a plain attribute name
    val ATTR_DIRECTIVE = BmlTokenType("BML_ATTR_DIRECTIVE")    // a :bound / @event attribute
    val ATTR_EQ = BmlTokenType("BML_ATTR_EQ")                  // =
    val ATTR_VALUE = BmlTokenType("BML_ATTR_VALUE")            // "…" or '…'
    val TAG_EXPR = BmlTokenType("BML_TAG_EXPR")                // { … } inside a tag (spread/bound shorthand)
    val FLOW_EXPR = BmlTokenType("BML_FLOW_EXPR")              // <for … in EXPR> iterable / <if EXPR> condition (Kotlin)
    val TEXT = BmlTokenType("BML_TEXT")                        // markup text run
    val COMMENT = BmlTokenType("BML_COMMENT")                  // {# ... #} or <!-- ... -->
    val INTERPOLATION = BmlTokenType("BML_INTERPOLATION")      // { kotlinExpr } (injection host)
    val SCRIPT_SERVER_BODY = BmlTokenType("BML_SCRIPT_SERVER_BODY")  // <script server> body (Kotlin)
    val SCRIPT_CLIENT_BODY = BmlTokenType("BML_SCRIPT_CLIENT_BODY")  // <script client> body (TS)
    val STYLE_BODY = BmlTokenType("BML_STYLE_BODY")                  // <style> body (CSS; raw, never interpolated)
}

/** Composite element types whose PSI nodes implement `PsiLanguageInjectionHost`. */
object BmlElementTypes {
    val INTERPOLATION_HOST = BmlElementType("BML_INTERPOLATION_HOST")
    val FLOW_EXPR_HOST = BmlElementType("BML_FLOW_EXPR_HOST")
    val SCRIPT_SERVER_HOST = BmlElementType("BML_SCRIPT_SERVER_HOST")
    val SCRIPT_CLIENT_HOST = BmlElementType("BML_SCRIPT_CLIENT_HOST")
    val ATTR_VALUE_HOST = BmlElementType("BML_ATTR_VALUE_HOST")
    val STYLE_HOST = BmlElementType("BML_STYLE_HOST")

    /** Wraps a plain tag name so it can carry a reference to its `<component>` declaration. */
    val TAG_NAME_REF = BmlElementType("BML_TAG_NAME_REF")
}
