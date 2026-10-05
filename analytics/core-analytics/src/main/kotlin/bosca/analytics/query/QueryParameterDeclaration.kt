package bosca.analytics.query

import bosca.analytics.model.QueryParameterType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A parameter declaration carried by a SQL file's `@bosca-query` metadata block.
 *
 * Shaped to mirror [bosca.analytics.model.AnalyticsQueryParameterInput] (the GraphQL
 * input type) but with `description` defaulted to empty and `sort` optional — when
 * absent, the parameter's index in the declaration array is used.
 */
@Serializable
data class QueryParameterDeclaration(
    val parameter: String,
    val name: String,
    val description: String = "",
    val type: QueryParameterType,
    val arrayType: QueryParameterType = QueryParameterType.NONE,
    @SerialName("defaultValue")
    val defaultValue: JsonElement? = null,
    val required: Boolean = false,
    val sort: Int? = null,
)

/**
 * Top-level shape of the JSON payload inside a `@bosca-query` block.
 */
@Serializable
data class QuerySourceMetadata(
    val parameters: List<QueryParameterDeclaration> = emptyList(),
)

/**
 * Result of parsing a SQL file that may carry a `@bosca-query` metadata block.
 *
 * @property cleanSql SQL with the metadata block (and its trailing newline) removed.
 *                    Identical to the input when no block was present.
 * @property parameters Parameter declarations from the block, or `null` if no block
 *                      was present. An empty list means a block was present but
 *                      declared zero parameters — semantically distinct from `null`.
 */
data class ParsedQuerySource(
    val cleanSql: String,
    val parameters: List<QueryParameterDeclaration>?,
)

/**
 * Thrown when a SQL file has a `@bosca-query` block that cannot be parsed —
 * unterminated comment, malformed JSON, or unknown parameter type.
 *
 * Callers should surface this as a sync failure rather than silently storing the SQL
 * without parameters.
 */
class QuerySourceParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
