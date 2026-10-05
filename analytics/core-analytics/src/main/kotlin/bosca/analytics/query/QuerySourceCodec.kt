package bosca.analytics.query

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Reads and writes the optional `@bosca-query` metadata block carried at the start of
 * an analytics query's SQL file.
 *
 * The block is a single SQL block-comment whose first token is `@bosca-query`. The
 * body of the comment is a JSON object with a `parameters` array describing each
 * query parameter (see [QueryParameterDeclaration]). The SQL begins on the line
 * after the comment's closing delimiter.
 *
 * The block must appear at the very start of the file (after optional whitespace).
 * Anchoring at the start avoids false matches against marker substrings embedded
 * inside SQL string literals further down the file.
 *
 * Round-trip contract:
 *
 * - [parse] strips the block (plus the immediately-following newline, if any) from
 *   the SQL it returns. The DB stores that clean SQL.
 * - [render] is the inverse: given clean SQL and a parameter set, it prepends a
 *   fresh block. Calling `parse(render(sql, params))` returns the same clean SQL,
 *   and the same parameters with `sort` populated from declaration order.
 */
object QuerySourceCodec {

    private const val MARKER = "@bosca-query"
    private const val OPEN = "/*"
    private const val CLOSE = "*/"

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = false
        encodeDefaults = false
        explicitNulls = false
    }

    /**
     * Parse a SQL file that may begin with a `@bosca-query` block.
     *
     * Returns [ParsedQuerySource] with `parameters = null` when no block is present
     * (left as-is, no destructive sync). When a block is present but malformed,
     * throws [QuerySourceParseException].
     */
    fun parse(sql: String): ParsedQuerySource {
        val markerStart = sql.indexOfFirst { !it.isWhitespace() }
        if (markerStart < 0 || markerStart + OPEN.length > sql.length) {
            return ParsedQuerySource(sql, null)
        }
        if (!sql.regionMatches(markerStart, OPEN, 0, OPEN.length)) {
            return ParsedQuerySource(sql, null)
        }

        // After the `/*`, the next non-whitespace token must be the marker for this
        // to be a `@bosca-query` block. If it isn't, it's just a regular comment —
        // leave the file untouched and report no parameters.
        var cursor = markerStart + OPEN.length
        while (cursor < sql.length && sql[cursor].isWhitespace()) cursor++
        if (!sql.regionMatches(cursor, MARKER, 0, MARKER.length)) {
            return ParsedQuerySource(sql, null)
        }
        val afterMarker = cursor + MARKER.length

        val closeIdx = sql.indexOf(CLOSE, startIndex = afterMarker)
        if (closeIdx < 0) {
            throw QuerySourceParseException(
                "Unterminated $MARKER comment block: missing `$CLOSE`."
            )
        }

        val jsonText = sql.substring(afterMarker, closeIdx).trim()
        val metadata = try {
            json.decodeFromString(QuerySourceMetadata.serializer(), jsonText)
        } catch (e: SerializationException) {
            throw QuerySourceParseException(
                "Invalid JSON in $MARKER block: ${e.message}", e
            )
        }

        var tail = closeIdx + CLOSE.length
        if (tail < sql.length && sql[tail] == '\r') tail++
        if (tail < sql.length && sql[tail] == '\n') tail++

        val cleanSql = sql.substring(0, markerStart) + sql.substring(tail)
        val parameters = metadata.parameters.mapIndexed { index, p ->
            if (p.sort == null) p.copy(sort = index) else p
        }
        return ParsedQuerySource(cleanSql, parameters)
    }

    /**
     * Prepend a `@bosca-query` block describing [parameters] to [sql].
     *
     * If [parameters] is empty, [sql] is returned unchanged — the absence of a block
     * in git is how "this query has no parameters" is encoded.
     *
     * If [sql] already contains a leading block, it is stripped before the new block
     * is written, so calling `render(render(sql, p), p)` is equivalent to
     * `render(sql, p)` (idempotency). Production callers always pass clean DB-stored
     * SQL; the defensive strip exists to keep the operation a safe primitive.
     */
    fun render(sql: String, parameters: List<QueryParameterDeclaration>): String {
        val cleanSql = parse(sql).cleanSql
        if (parameters.isEmpty()) return cleanSql
        val normalized = parameters.mapIndexed { index, p ->
            if (p.sort == null) p.copy(sort = index) else p
        }
        val body = json.encodeToString(
            QuerySourceMetadata.serializer(),
            QuerySourceMetadata(normalized),
        )
        return buildString {
            append(OPEN).append(' ').append(MARKER).append('\n')
            append(body).append('\n')
            append(CLOSE).append('\n')
            append(cleanSql)
        }
    }
}
