package bosca.search.model

/**
 * Type-safe representation of a Meilisearch filter expression that prevents filter injection
 * attacks by escaping values before embedding them in the filter string.
 *
 * Instead of constructing raw filter strings via string interpolation (which is vulnerable to
 * injection if any interpolated value contains special characters like double quotes), callers
 * build filters using [Eq] for equality conditions and [And] for conjunctions. The
 * [toFilterString] method produces a properly escaped Meilisearch filter expression.
 */
sealed class SearchFilter {

    /**
     * An equality condition that matches documents where [field] equals [value].
     * The [value] is automatically escaped when converted to a filter string.
     * The [field] name is validated to contain only alphanumeric characters, underscores,
     * and dots (for nested field access) to prevent filter injection via field names.
     */
    data class Eq(val field: String, val value: String) : SearchFilter() {
        init {
            require(VALID_FIELD_NAME.matches(field)) { "Invalid filter field name: $field" }
        }
    }

    /**
     * A conjunction of multiple filter conditions joined with AND.
     */
    data class And(val conditions: List<SearchFilter>) : SearchFilter()

    /**
     * Converts this filter to a Meilisearch filter expression string with all values
     * properly escaped to prevent injection.
     */
    fun toFilterString(): String = when (this) {
        is Eq -> "$field = \"${value.escapeFilterValue()}\""
        is And -> conditions.joinToString(" AND ") { it.toFilterString() }
    }

    companion object {

        /**
         * Pattern for valid Meilisearch field names: must start with a letter or underscore,
         * followed by alphanumeric characters, underscores, or dots (for nested field access).
         */
        private val VALID_FIELD_NAME = Regex("^[a-zA-Z_][a-zA-Z0-9_.]*$")

        /**
         * Creates an equality filter condition: `field = "value"`.
         */
        fun eq(field: String, value: String): SearchFilter = Eq(field, value)

        /**
         * Creates a conjunction of multiple filter conditions joined with AND.
         */
        fun and(vararg conditions: SearchFilter): SearchFilter = And(conditions.toList())
    }
}

/**
 * Escapes special characters in a filter value to prevent Meilisearch filter injection.
 * Backslashes are escaped first (to avoid double-escaping), then double quotes.
 */
private fun String.escapeFilterValue(): String = replace("\\", "\\\\").replace("\"", "\\\"")
