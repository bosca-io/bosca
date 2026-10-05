package bosca.graphql.language

/**
 * A position in GraphQL source: 1-based [line] and [column], plus the 0-based character [offset].
 * Attached to every [Node] (and token) so diagnostics and source maps can point back at the input.
 */
data class SourceLocation(val line: Int, val column: Int, val offset: Int)
