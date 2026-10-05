package bosca.workops.model.bql

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit assertions for the BQL parser. These pin the grammar against
 * concrete source strings — the planner / validator tests build on
 * top of this contract.
 */
class BqlParserTest {

    private fun parse(src: String): BqlQuery {
        val result = BqlParser(src).parse()
        assertTrue(result.isSuccess, "expected success; errors = ${result.errors}")
        return result.query!!
    }

    @Test
    fun `simple equality`() {
        val q = parse("status = Done")
        val cmp = q.where as BqlExpr.Compare
        assertEquals("status", cmp.fieldKey)
        assertEquals(BqlComparisonOperator.EQ, cmp.operator)
        assertEquals(BqlValue.Literal(JsonPrimitive("Done")), cmp.value)
    }

    @Test
    fun `quoted string value preserves spaces`() {
        val q = parse("summary ~ \"frob the widget\"")
        val cmp = q.where as BqlExpr.Compare
        assertEquals(BqlComparisonOperator.LIKE, cmp.operator)
        assertEquals(JsonPrimitive("frob the widget"), (cmp.value as BqlValue.Literal).value)
    }

    @Test
    fun `AND has tighter precedence than OR`() {
        val q = parse("a = 1 OR b = 2 AND c = 3")
        val or = q.where as BqlExpr.Or
        val left = or.left as BqlExpr.Compare
        assertEquals("a", left.fieldKey)
        // RHS is `b = 2 AND c = 3`
        val rightAnd = or.right as BqlExpr.And
        assertEquals("b", (rightAnd.left as BqlExpr.Compare).fieldKey)
        assertEquals("c", (rightAnd.right as BqlExpr.Compare).fieldKey)
    }

    @Test
    fun `parens override precedence`() {
        val q = parse("(a = 1 OR b = 2) AND c = 3")
        val and = q.where as BqlExpr.And
        // LHS is the parenthesized OR.
        assertTrue(and.left is BqlExpr.Or)
        assertEquals("c", (and.right as BqlExpr.Compare).fieldKey)
    }

    @Test
    fun `NOT prefix and parsing`() {
        val q = parse("NOT status = Done")
        val not = q.where as BqlExpr.Not
        val inner = not.inner as BqlExpr.Compare
        assertEquals("status", inner.fieldKey)
    }

    @Test
    fun `IN list and NOT IN`() {
        val pos = parse("priority in (High, Critical)").where as BqlExpr.InList
        assertEquals("priority", pos.fieldKey)
        assertEquals(false, pos.negated)
        assertEquals(2, pos.values.size)

        val neg = parse("priority not in (Low, Lowest)").where as BqlExpr.InList
        assertEquals(true, neg.negated)
    }

    @Test
    fun `IS NULL and IS NOT NULL`() {
        val isNull = parse("assignee is null").where as BqlExpr.IsNull
        assertEquals("assignee", isNull.fieldKey)
        assertEquals(false, isNull.negated)

        val isNotNull = parse("assignee is not null").where as BqlExpr.IsNull
        assertEquals(true, isNotNull.negated)
    }

    @Test
    fun `function literals - currentUser`() {
        val q = parse("assignee = currentUser()")
        val cmp = q.where as BqlExpr.Compare
        val fn = cmp.value as BqlValue.Function
        assertEquals("currentUser", fn.name)
        assertEquals(0, fn.args.size)
    }

    @Test
    fun `numeric literal int and decimal`() {
        val intCmp = parse("storyPoints > 5").where as BqlExpr.Compare
        assertEquals(JsonPrimitive(5L), (intCmp.value as BqlValue.Literal).value)

        val floatCmp = parse("velocity > 12.5").where as BqlExpr.Compare
        assertEquals(JsonPrimitive(12.5), (floatCmp.value as BqlValue.Literal).value)
    }

    @Test
    fun `null literal`() {
        val cmp = parse("resolution = null").where as BqlExpr.Compare
        assertEquals(JsonNull, (cmp.value as BqlValue.Literal).value)
    }

    @Test
    fun `ORDER BY with mixed directions`() {
        val q = parse("status = Done ORDER BY priority DESC, created ASC")
        assertEquals(2, q.orderBy.size)
        assertEquals(BqlSortKey("priority", BqlSortDirection.DESC), q.orderBy[0])
        assertEquals(BqlSortKey("created", BqlSortDirection.ASC), q.orderBy[1])
    }

    @Test
    fun `bare ORDER BY without WHERE clause`() {
        val q = parse("ORDER BY modifiedAt DESC")
        assertEquals(null, q.where)
        assertEquals(1, q.orderBy.size)
        assertEquals(BqlSortKey("modifiedAt", BqlSortDirection.DESC), q.orderBy[0])
    }

    @Test
    fun `empty source produces null where and empty orderBy`() {
        val result = BqlParser("").parse()
        assertTrue(result.isSuccess)
        val q = result.query!!
        assertEquals(null, q.where)
        assertEquals(emptyList(), q.orderBy)
    }

    @Test
    fun `error on missing operator carries byte offsets and a hint`() {
        val src = "summary"
        val result = BqlParser(src).parse()
        assertEquals(1, result.errors.size)
        val err = result.errors.single()
        assertEquals(7, err.start)
        assertTrue(err.hint != null, "operator-missing error should carry a hint")
    }

    @Test
    fun `error on unterminated paren`() {
        val src = "(status = Done"
        val result = BqlParser(src).parse()
        assertTrue(result.errors.isNotEmpty())
        val err = result.errors.first()
        assertTrue(err.message.contains("')"), err.message)
    }

    @Test
    fun `error on missing field name carries the location of the bad token`() {
        val src = "= Done"
        val result = BqlParser(src).parse()
        assertEquals(1, result.errors.size)
        val err = result.errors.single()
        assertEquals(0, err.start)
        assertTrue(err.message.contains("expected a field name"))
    }
}
