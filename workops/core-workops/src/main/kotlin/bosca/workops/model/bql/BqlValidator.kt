package bosca.workops.model.bql

/**
 * Validates a parsed [BqlQuery] against the field and function
 * catalogs. The validator runs after the parser and before the
 * planner; it catches every "this field doesn't exist" /
 * "this operator isn't supported on a UUID" issue with a precise
 * byte span so the planner can assume a well-formed query.
 *
 * Validation produces a list of [BqlError]s; an empty list means
 * the query is safe to plan.
 */
class BqlValidator(
    private val fields: BqlFieldCatalog = BqlFieldCatalog.default(),
    private val functions: BqlFunctionCatalog = BqlFunctionCatalog.default(),
) {

    fun validate(query: BqlQuery): List<BqlError> {
        val errors = mutableListOf<BqlError>()
        if (query.where != null) validateExpr(query.where, errors)
        for (key in query.orderBy) {
            val field = fields.lookup(key.fieldKey)
            if (field == null) {
                errors += BqlError(
                    "ORDER BY references unknown field '${key.fieldKey}'",
                    start = -1,
                    end = -1,
                    hint = "valid fields: ${fields.all().joinToString { it.key }}",
                )
            } else if (!field.sortable) {
                errors += BqlError(
                    "field '${key.fieldKey}' is not sortable",
                    start = -1,
                    end = -1,
                )
            }
        }
        return errors
    }

    private fun validateExpr(expr: BqlExpr, errors: MutableList<BqlError>) {
        when (expr) {
            is BqlExpr.And -> {
                validateExpr(expr.left, errors)
                validateExpr(expr.right, errors)
            }
            is BqlExpr.Or -> {
                validateExpr(expr.left, errors)
                validateExpr(expr.right, errors)
            }
            is BqlExpr.Not -> validateExpr(expr.inner, errors)
            is BqlExpr.Compare -> validateCompare(expr, errors)
            is BqlExpr.InList -> validateInList(expr, errors)
            is BqlExpr.IsNull -> validateField(expr.fieldKey, expr.start, expr.end, errors)
        }
    }

    private fun validateCompare(expr: BqlExpr.Compare, errors: MutableList<BqlError>) {
        val field = validateField(expr.fieldKey, expr.start, expr.end, errors) ?: return
        if (expr.operator == BqlComparisonOperator.LIKE && field.type != BqlFieldType.TEXT) {
            errors += BqlError(
                "operator '~' (substring) only applies to text fields, not '${field.key}' (${field.type})",
                expr.start,
                expr.end,
            )
        } else if (field.type == BqlFieldType.ARRAY_UUID && expr.operator !in setOf(
                BqlComparisonOperator.EQ, BqlComparisonOperator.NEQ,
            )
        ) {
            errors += BqlError(
                "operator '${expr.operator}' is not valid on array field '${field.key}'",
                expr.start, expr.end,
                hint = "use `=` to test membership or `field in (a, b)` for set match",
            )
        } else if (field.type in setOf(BqlFieldType.BOOLEAN, BqlFieldType.UUID, BqlFieldType.NAME_REFERENCE) &&
            expr.operator !in setOf(BqlComparisonOperator.EQ, BqlComparisonOperator.NEQ)
        ) {
            errors += BqlError(
                "operator '${expr.operator}' is not valid on ${field.type} field '${field.key}'",
                expr.start, expr.end,
                hint = "use `=` or `!=` for ${field.type} fields",
            )
        }
        validateValue(expr.value, errors)
    }

    private fun validateInList(expr: BqlExpr.InList, errors: MutableList<BqlError>) {
        validateField(expr.fieldKey, expr.start, expr.end, errors)
        for (value in expr.values) validateValue(value, errors)
    }

    private fun validateField(
        key: String,
        start: Int,
        end: Int,
        errors: MutableList<BqlError>,
    ): BqlField? {
        val field = fields.lookup(key)
        if (field == null) {
            val suggestion = fields.all().firstOrNull {
                it.key.startsWith(key.take(2), ignoreCase = true)
            }?.key
            errors += BqlError(
                "unknown field '$key'",
                start,
                end,
                hint = if (suggestion != null) "did you mean '$suggestion'?" else null,
            )
        }
        return field
    }

    private fun validateValue(value: BqlValue, errors: MutableList<BqlError>) {
        when (value) {
            is BqlValue.Function -> {
                val descriptor = functions.lookup(value.name)
                if (descriptor == null) {
                    errors += BqlError(
                        "unknown function '${value.name}()'",
                        start = -1,
                        end = -1,
                        hint = "valid functions: currentUser(), now(), startOfDay(), endOfDay(), startOfWeek(), endOfWeek()",
                    )
                } else if (!descriptor.acceptsArity(value.args.size)) {
                    val arity = if (descriptor.minArity == descriptor.maxArity) descriptor.minArity.toString()
                    else "${descriptor.minArity}..${descriptor.maxArity}"
                    errors += BqlError(
                        "function '${value.name}()' takes $arity arg(s), got ${value.args.size}",
                        start = -1,
                        end = -1,
                    )
                }
                value.args.forEach { validateValue(it, errors) }
            }
            is BqlValue.ListLiteral -> value.values.forEach { validateValue(it, errors) }
            is BqlValue.Literal -> Unit
        }
    }
}
