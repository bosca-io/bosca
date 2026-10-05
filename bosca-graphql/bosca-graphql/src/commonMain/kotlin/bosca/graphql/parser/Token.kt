package bosca.graphql.parser

import bosca.graphql.language.SourceLocation

/** The lexical token kinds of GraphQL. [display] is used in error messages. */
enum class TokenKind(val display: String) {
    BANG("!"),
    DOLLAR("$"),
    AMP("&"),
    PAREN_L("("),
    PAREN_R(")"),
    SPREAD("..."),
    COLON(":"),
    EQUALS("="),
    AT("@"),
    BRACKET_L("["),
    BRACKET_R("]"),
    BRACE_L("{"),
    PIPE("|"),
    BRACE_R("}"),
    NAME("Name"),
    INT("Int"),
    FLOAT("Float"),
    STRING("String"),
    BLOCK_STRING("BlockString"),
    EOF("<EOF>"),
}

/**
 * A single lexical token. [value] holds: the symbol for punctuators, the raw source text for
 * NAME/INT/FLOAT, and the fully *decoded* string for STRING/BLOCK_STRING (escapes resolved, block
 * indentation removed). [location] is the token's start.
 */
data class Token(val kind: TokenKind, val value: String, val location: SourceLocation)
