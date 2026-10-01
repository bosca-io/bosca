package bosca.workops.model.bql

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One token in a BQL source string. Carries the matched text and the
 * byte-offset window within the original source so parser errors can
 * point a caller at the exact location — the "BQL parser produces
 * actionable errors" non-negotiable from the spec's Excellence Bar.
 *
 * The kind enumerates every shape the lexer recognizes. Future
 * extensions (history operators `was`, `changed`, dates, durations)
 * land as new kinds without altering the core grammar.
 */
@Serializable
data class BqlToken(
    val kind: BqlTokenKind,
    val text: String,
    /** Byte offset of [text]'s first character in the source. */
    val start: Int,
    /** Byte offset just past [text]'s last character. */
    val end: Int,
)

@Serializable
enum class BqlTokenKind {
    /** A bare identifier — field name, function name, keyword. */
    IDENT,

    /** Quoted string literal (`"foo bar"`). */
    STRING,

    /** Numeric literal — integer or decimal. */
    NUMBER,

    /** Boolean literal — `true` / `false`. */
    BOOL,

    /** `null` literal. */
    NULL,

    /** Comparison operator (`=`, `!=`, `<`, `<=`, `>`, `>=`, `~`). */
    OPERATOR,

    /** `(` */
    LPAREN,

    /** `)` */
    RPAREN,

    /** `,` */
    COMMA,

    /** End of source. */
    EOF,
}

/**
 * The comparison operators BQL supports. `LIKE` corresponds to BQL's
 * `~` operator (substring match). `IN` / `NOT_IN` are list membership.
 * `IS` / `IS_NOT` paired with `null` literals do null-checks.
 */
@Serializable
enum class BqlComparisonOperator {
    EQ, NEQ, LT, LTE, GT, GTE,
    LIKE,
    IN, NOT_IN,
    IS, IS_NOT,
}

/**
 * Sort direction on `ORDER BY` clauses.
 */
@Serializable
enum class BqlSortDirection { ASC, DESC }

/**
 * One key in an `ORDER BY` clause.
 */
@Serializable
data class BqlSortKey(val fieldKey: String, val direction: BqlSortDirection = BqlSortDirection.ASC)

/**
 * A literal value as parsed from BQL. Wrapping in a sealed type lets
 * the validator and planner treat function literals (`currentUser()`,
 * `now()`) uniformly with primitives.
 */
@Serializable
sealed class BqlValue {
    /** Static JSON-typed primitive — strings, numbers, booleans, null. */
    @Serializable
    @kotlinx.serialization.SerialName("Literal")
    data class Literal(val value: JsonElement) : BqlValue()

    /** A list literal — used by IN / NOT IN. */
    @Serializable
    @kotlinx.serialization.SerialName("ListLiteral")
    data class ListLiteral(val values: List<BqlValue>) : BqlValue()

    /**
     * A function call without arguments resolved at execution time
     * (`currentUser()`, `now()`, `startOfDay()`). The validator
     * checks the function name against the registered catalog;
     * the planner emits the appropriate SQL substitution.
     */
    @Serializable
    @kotlinx.serialization.SerialName("Function")
    data class Function(val name: String, val args: List<BqlValue> = emptyList()) : BqlValue()
}

/**
 * The BQL AST. A query is one [BqlExpr] tree plus an optional
 * `ORDER BY` clause. Every node carries the byte-offset window of
 * the source range it covers so the planner can reproduce the
 * caller's source span when a planning-time check fails.
 */
@Serializable
sealed class BqlExpr {

    /** Source byte-offset window the expression covers. */
    abstract val start: Int
    abstract val end: Int

    /**
     * `field op value` — the load-bearing leaf shape that all the
     * structural filters land on.
     */
    @Serializable
    @kotlinx.serialization.SerialName("Compare")
    data class Compare(
        val fieldKey: String,
        val operator: BqlComparisonOperator,
        val value: BqlValue,
        override val start: Int,
        override val end: Int,
    ) : BqlExpr()

    /** `field in (a, b, c)` / `field not in (…)`. */
    @Serializable
    @kotlinx.serialization.SerialName("InList")
    data class InList(
        val fieldKey: String,
        val negated: Boolean,
        val values: List<BqlValue>,
        override val start: Int,
        override val end: Int,
    ) : BqlExpr()

    /** `field is null` / `field is not null`. */
    @Serializable
    @kotlinx.serialization.SerialName("IsNull")
    data class IsNull(
        val fieldKey: String,
        val negated: Boolean,
        override val start: Int,
        override val end: Int,
    ) : BqlExpr()

    /** Boolean conjunction. Short-circuits on the first false. */
    @Serializable
    @kotlinx.serialization.SerialName("And")
    data class And(
        val left: BqlExpr,
        val right: BqlExpr,
        override val start: Int,
        override val end: Int,
    ) : BqlExpr()

    /** Boolean disjunction. */
    @Serializable
    @kotlinx.serialization.SerialName("Or")
    data class Or(
        val left: BqlExpr,
        val right: BqlExpr,
        override val start: Int,
        override val end: Int,
    ) : BqlExpr()

    /** Boolean negation. */
    @Serializable
    @kotlinx.serialization.SerialName("Not")
    data class Not(
        val inner: BqlExpr,
        override val start: Int,
        override val end: Int,
    ) : BqlExpr()
}

/**
 * The top-level parsed query — the expression tree plus its order-by
 * keys.
 */
@Serializable
data class BqlQuery(
    val where: BqlExpr? = null,
    val orderBy: List<BqlSortKey> = emptyList(),
)
