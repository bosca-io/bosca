package bosca.graphql.parser

/**
 * Resource limits for parsing untrusted GraphQL source. Defaults are deliberately permissive abuse backstops;
 * callers serving constrained environments can supply smaller values explicitly.
 */
data class ParserLimits(
    val maxTokens: Int = 1_000_000,
    val maxNestingDepth: Int = 512,
) {
    init {
        require(maxTokens > 0) { "maxTokens must be positive" }
        require(maxNestingDepth > 0) { "maxNestingDepth must be positive" }
    }

    companion object {
        /** Permissive abuse backstops used by every parser entry point unless the caller supplies stricter limits. */
        val DEFAULT = ParserLimits()
    }
}
