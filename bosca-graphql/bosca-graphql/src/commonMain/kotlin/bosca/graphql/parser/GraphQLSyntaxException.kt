package bosca.graphql.parser

import bosca.graphql.language.SourceLocation

/** Thrown by the lexer/parser on invalid GraphQL syntax, carrying the offending [location]. */
class GraphQLSyntaxException(
    val reason: String,
    val location: SourceLocation,
) : IllegalArgumentException("$reason (line ${location.line}, column ${location.column})")
