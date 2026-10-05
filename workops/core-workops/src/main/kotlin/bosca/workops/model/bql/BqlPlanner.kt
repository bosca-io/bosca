package bosca.workops.model.bql

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Side-table the planner uses to resolve [BqlFieldType.NAME_REFERENCE]
 * literals (e.g. `priority = High`) into the UUID the SQL column
 * actually stores. The Phase 6 service registers a fixed-size set of
 * resolvers (priority, status, task type, resolution) loaded once
 * from the seeded lookup tables.
 */
fun interface BqlNameResolver {
    suspend fun resolve(field: BqlField, name: String): UUID?
}

/** Function-call evaluator surface — the planner asks for `currentUser()` / `now()` substitutions. */
interface BqlFunctionResolver {
    /**
     * The acting profile id. Returns null only when the caller is
     * truly anonymous (no principal at all); the planner translates
     * null into `IS NULL` so `assignee = currentUser()` returns
     * unassigned tasks for anonymous callers. Authenticated callers
     * should always resolve to a profile — the controller falls back
     * to the first profile on the principal when `primaryProfileId`
     * is unset.
     */
    suspend fun currentUserProfileId(): UUID?

    /** Server-side now timestamp. */
    suspend fun now(): OffsetDateTime
}

/**
 * The planner's output. The planner separates the structural
 * Postgres `WHERE` fragment from the free-text fragments that need
 * to route through Meilisearch (Phase 23 fully wires the
 * intersection; Phase 6 falls back to `ILIKE` against summary /
 * description so the search works without Meilisearch on day one).
 */
data class BqlQueryPlan(
    /**
     * SQL `WHERE` fragment in standard prepared-statement form;
     * `?` is the placeholder marker. Empty when the query is
     * purely free-text.
     */
    val whereSql: String,

    /** Bound parameters paired with their ordinal positions in [whereSql]. */
    val parameters: List<BqlBoundParameter>,

    /**
     * Free-text fragments that the search service routes through
     * Meilisearch. Phase 6 also embeds a Postgres `ILIKE` clause
     * in [whereSql] so the search returns results even when
     * Meilisearch isn't reachable.
     */
    val freeTextTerms: List<String>,

    /** SQL `ORDER BY` fragment, empty when the query carried no `ORDER BY`. */
    val orderBySql: String,
)

/** One bound parameter with the Postgres column type marker. */
data class BqlBoundParameter(
    val ordinal: Int,
    val value: Any?,
    val sqlType: BqlBoundType,
)

enum class BqlBoundType { TEXT, UUID, NUMBER, BOOLEAN, TIMESTAMP, UUID_ARRAY, TEXT_ARRAY }

/**
 * Hand-rolled BQL planner. Walks the validated AST, emits a Postgres
 * `WHERE` fragment with `?` placeholders + a parallel list of bound
 * parameters, and threads any free-text fragments out to the
 * search service.
 *
 * The planner assumes the validator already accepted the query —
 * unknown fields / mistyped operators raise `IllegalStateException`
 * here because they should have been caught earlier.
 */
class BqlPlanner(
    private val fields: BqlFieldCatalog = BqlFieldCatalog.default(),
    private val nameResolver: BqlNameResolver,
    private val functionResolver: BqlFunctionResolver,
) {

    suspend fun plan(query: BqlQuery): BqlQueryPlan {
        val params = mutableListOf<BqlBoundParameter>()
        val freeText = mutableListOf<String>()
        val whereSql = if (query.where != null) walk(query.where, params, freeText) else ""
        val orderBySql = if (query.orderBy.isEmpty()) "" else {
            "order by " + query.orderBy.joinToString(", ") { key ->
                val field = fields.lookup(key.fieldKey)
                    ?: error("validator missed unknown sort field '${key.fieldKey}'")
                "${field.column} ${if (key.direction == BqlSortDirection.DESC) "desc" else "asc"}"
            }
        }
        return BqlQueryPlan(whereSql, params.toList(), freeText.toList(), orderBySql)
    }

    private suspend fun walk(
        expr: BqlExpr,
        params: MutableList<BqlBoundParameter>,
        freeText: MutableList<String>,
    ): String = when (expr) {
        is BqlExpr.And -> "(${walk(expr.left, params, freeText)} and ${walk(expr.right, params, freeText)})"
        is BqlExpr.Or -> "(${walk(expr.left, params, freeText)} or ${walk(expr.right, params, freeText)})"
        is BqlExpr.Not -> "(not ${walk(expr.inner, params, freeText)})"
        is BqlExpr.IsNull -> {
            val field = lookup(expr.fieldKey)
            "${field.column} ${if (expr.negated) "is not null" else "is null"}"
        }
        is BqlExpr.InList -> planInList(expr, params)
        is BqlExpr.Compare -> planCompare(expr, params, freeText)
    }

    private suspend fun planCompare(
        expr: BqlExpr.Compare,
        params: MutableList<BqlBoundParameter>,
        freeText: MutableList<String>,
    ): String {
        val field = lookup(expr.fieldKey)
        return when (field.type) {
            BqlFieldType.TEXT -> planText(field, expr, params, freeText)
            BqlFieldType.NAME_REFERENCE -> planNameRef(field, expr, params)
            BqlFieldType.UUID -> planUuid(field, expr, params)
            BqlFieldType.NUMBER -> planNumber(field, expr, params)
            BqlFieldType.BOOLEAN -> planBoolean(field, expr, params)
            BqlFieldType.TIMESTAMP -> planTimestamp(field, expr, params)
            BqlFieldType.ARRAY_UUID -> planArrayUuid(field, expr, params)
        }
    }

    private fun planText(
        field: BqlField,
        expr: BqlExpr.Compare,
        params: MutableList<BqlBoundParameter>,
        freeText: MutableList<String>,
    ): String {
        val raw = (expr.value as? BqlValue.Literal)?.value
        val text = when (raw) {
            is JsonPrimitive -> raw.content
            else -> error("validator should reject non-literal text comparisons")
        }
        return when (expr.operator) {
            BqlComparisonOperator.LIKE -> {
                freeText += text
                params += bind(params.size + 1, "%$text%", BqlBoundType.TEXT)
                "${field.column} ilike ?"
            }
            BqlComparisonOperator.EQ -> {
                params += bind(params.size + 1, text, BqlBoundType.TEXT)
                "${field.column} = ?"
            }
            BqlComparisonOperator.NEQ -> {
                params += bind(params.size + 1, text, BqlBoundType.TEXT)
                "${field.column} <> ?"
            }
            else -> error("validator should reject text op ${expr.operator}")
        }
    }

    private suspend fun planNameRef(
        field: BqlField,
        expr: BqlExpr.Compare,
        params: MutableList<BqlBoundParameter>,
    ): String {
        // Resolve the value (literal name or function call) into a UUID.
        val resolved = resolveValueToUuid(field, expr.value)
        return when (expr.operator) {
            BqlComparisonOperator.EQ -> {
                if (resolved == null) "${field.column} is null"
                else {
                    params += bind(params.size + 1, resolved, BqlBoundType.UUID)
                    "${field.column} = ?"
                }
            }
            BqlComparisonOperator.NEQ -> {
                if (resolved == null) "${field.column} is not null"
                else {
                    params += bind(params.size + 1, resolved, BqlBoundType.UUID)
                    "${field.column} <> ?"
                }
            }
            else -> error("validator should reject name-reference op ${expr.operator}")
        }
    }

    private suspend fun planUuid(
        field: BqlField,
        expr: BqlExpr.Compare,
        params: MutableList<BqlBoundParameter>,
    ): String {
        val resolved = resolveValueToUuid(field, expr.value)
        return when (expr.operator) {
            BqlComparisonOperator.EQ ->
                if (resolved == null) "${field.column} is null"
                else {
                    params += bind(params.size + 1, resolved, BqlBoundType.UUID)
                    "${field.column} = ?"
                }
            BqlComparisonOperator.NEQ ->
                if (resolved == null) "${field.column} is not null"
                else {
                    params += bind(params.size + 1, resolved, BqlBoundType.UUID)
                    "${field.column} <> ?"
                }
            else -> error("validator should reject UUID op ${expr.operator}")
        }
    }

    private fun planNumber(
        field: BqlField,
        expr: BqlExpr.Compare,
        params: MutableList<BqlBoundParameter>,
    ): String {
        val raw = (expr.value as? BqlValue.Literal)?.value as? JsonPrimitive
            ?: error("validator should reject non-literal number comparisons")
        val number = raw.content.toLongOrNull()?.let { it as Number } ?: raw.content.toDouble()
        params += bind(params.size + 1, number, BqlBoundType.NUMBER)
        return "${field.column} ${sqlOp(expr.operator)} ?"
    }

    private fun planBoolean(
        field: BqlField,
        expr: BqlExpr.Compare,
        params: MutableList<BqlBoundParameter>,
    ): String {
        val raw = (expr.value as? BqlValue.Literal)?.value as? JsonPrimitive
            ?: error("validator should reject non-literal boolean comparisons")
        val bool = raw.content.equals("true", ignoreCase = true)
        params += bind(params.size + 1, bool, BqlBoundType.BOOLEAN)
        return "${field.column} ${sqlOp(expr.operator)} ?"
    }

    private suspend fun planTimestamp(
        field: BqlField,
        expr: BqlExpr.Compare,
        params: MutableList<BqlBoundParameter>,
    ): String {
        val ts: OffsetDateTime = when (val v = expr.value) {
            is BqlValue.Function -> resolveTimestampFunction(v.name)
            is BqlValue.Literal -> {
                val raw = (v.value as? JsonPrimitive)?.content
                    ?: error("validator should reject non-string timestamp literal")
                OffsetDateTime.parse(raw)
            }
            else -> error("validator should reject timestamp value of shape ${v::class.simpleName}")
        }
        params += bind(params.size + 1, ts, BqlBoundType.TIMESTAMP)
        return "${field.column} ${sqlOp(expr.operator)} ?"
    }

    private suspend fun planArrayUuid(
        field: BqlField,
        expr: BqlExpr.Compare,
        params: MutableList<BqlBoundParameter>,
    ): String {
        val resolved = resolveValueToUuid(field, expr.value)
            ?: error("validator should reject null array-uuid comparisons")
        return when (expr.operator) {
            BqlComparisonOperator.EQ -> {
                params += bind(params.size + 1, resolved, BqlBoundType.UUID)
                "? = any(${field.column})"
            }
            BqlComparisonOperator.NEQ -> {
                params += bind(params.size + 1, resolved, BqlBoundType.UUID)
                "not (? = any(${field.column}))"
            }
            else -> error("validator should reject array op ${expr.operator}")
        }
    }

    private suspend fun planInList(
        expr: BqlExpr.InList,
        params: MutableList<BqlBoundParameter>,
    ): String {
        val field = lookup(expr.fieldKey)
        return when (field.type) {
            BqlFieldType.TEXT -> {
                val values = expr.values.map { v ->
                    val raw = (v as? BqlValue.Literal)?.value as? JsonPrimitive
                        ?: error("validator should reject non-literal text IN values")
                    raw.content
                }
                if (values.isEmpty()) return if (expr.negated) "true" else "false"
                params += bind(params.size + 1, values.toTypedArray(), BqlBoundType.TEXT_ARRAY)
                if (expr.negated) "not (${field.column} = any(?))" else "${field.column} = any(?)"
            }
            BqlFieldType.ARRAY_UUID, BqlFieldType.UUID, BqlFieldType.NAME_REFERENCE -> {
                val resolved = expr.values.mapNotNull { resolveValueToUuid(field, it) }
                if (resolved.isEmpty()) return if (expr.negated) "true" else "false"
                params += bind(params.size + 1, resolved.toTypedArray(), BqlBoundType.UUID_ARRAY)
                when (field.type) {
                    BqlFieldType.ARRAY_UUID -> if (expr.negated) "not (${field.column} && ?)" else "${field.column} && ?"
                    else -> if (expr.negated) "not (${field.column} = any(?))" else "${field.column} = any(?)"
                }
            }
            else -> {
                val resolved = expr.values.mapNotNull { resolveValueToUuid(field, it) }
                if (resolved.isEmpty()) return if (expr.negated) "true" else "false"
                params += bind(params.size + 1, resolved.toTypedArray(), BqlBoundType.UUID_ARRAY)
                if (expr.negated) "not (${field.column} = any(?))" else "${field.column} = any(?)"
            }
        }
    }

    private suspend fun resolveValueToUuid(field: BqlField, value: BqlValue): UUID? = when (value) {
        is BqlValue.Function -> when (value.name.lowercase()) {
            "currentuser" -> functionResolver.currentUserProfileId()
            else -> error("function ${value.name}() not valid as a UUID literal here")
        }
        is BqlValue.Literal -> {
            val raw = (value.value as? JsonPrimitive)?.content
            when {
                raw == null || value.value == JsonNull -> null
                runCatching { UUID.parse(raw) }.isSuccess -> UUID.parse(raw)
                else -> nameResolver.resolve(field, raw)
            }
        }
        is BqlValue.ListLiteral -> error("list literals belong in BqlExpr.InList, not Compare")
    }

    private suspend fun resolveTimestampFunction(name: String): OffsetDateTime {
        val now = functionResolver.now()
        return when (name.lowercase()) {
            "now" -> now
            "startofday" -> now.toLocalDate().atStartOfDay().atOffset(now.offset)
            "endofday" ->
                now.toLocalDate().atTime(23, 59, 59, 999_999_999).atOffset(now.offset)
            "startofweek" -> {
                val dow = now.dayOfWeek.value - 1 // Mon=0, Sun=6
                now.toLocalDate().minusDays(dow.toLong()).atStartOfDay().atOffset(now.offset)
            }
            "endofweek" -> {
                val dow = now.dayOfWeek.value - 1
                now.toLocalDate().plusDays((6 - dow).toLong())
                    .atTime(23, 59, 59, 999_999_999).atOffset(now.offset)
            }
            else -> error("unknown timestamp function $name()")
        }
    }

    private fun sqlOp(op: BqlComparisonOperator): String = when (op) {
        BqlComparisonOperator.EQ -> "="
        BqlComparisonOperator.NEQ -> "<>"
        BqlComparisonOperator.LT -> "<"
        BqlComparisonOperator.LTE -> "<="
        BqlComparisonOperator.GT -> ">"
        BqlComparisonOperator.GTE -> ">="
        else -> error("operator $op has no direct SQL form")
    }

    private fun lookup(key: String): BqlField =
        fields.lookup(key) ?: error("validator missed unknown field '$key'")

    private fun bind(ordinal: Int, value: Any?, type: BqlBoundType): BqlBoundParameter =
        BqlBoundParameter(ordinal, value, type)
}
