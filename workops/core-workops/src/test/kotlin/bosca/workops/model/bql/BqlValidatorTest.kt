package bosca.workops.model.bql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validator behavior against the seeded field + function catalogs.
 * Pins the catalog membership rules and the operator-on-type
 * matrix so Phase 6's downstream planner can assume a clean AST.
 */
class BqlValidatorTest {

    private val validator = BqlValidator()

    private fun validate(src: String): List<BqlError> {
        val parsed = BqlParser(src).parse()
        check(parsed.errors.isEmpty()) { "parser failed: ${parsed.errors}" }
        return validator.validate(parsed.query!!)
    }

    @Test
    fun `valid query has no errors`() {
        val errors = validate("status = Done AND assignee = currentUser()")
        assertTrue(errors.isEmpty(), "errors: $errors")
    }

    @Test
    fun `unknown field carries a hint when an obvious near-match exists`() {
        val errors = validate("statoos = Done")
        assertEquals(1, errors.size)
        val err = errors.single()
        assertTrue(err.message.contains("unknown field"))
        assertTrue(err.hint != null && err.hint.contains("status"), "hint: ${err.hint}")
    }

    @Test
    fun `tilde operator is rejected on non-text fields`() {
        val errors = validate("priority ~ \"high\"")
        assertEquals(1, errors.size)
        assertTrue(errors.first().message.contains("only applies to text"))
    }

    @Test
    fun `lt and gt are rejected on array fields`() {
        val errors = validate("label > foo")
        assertEquals(1, errors.size)
        assertTrue(errors.first().message.contains("not valid on array"))
    }

    @Test
    fun `unknown function is rejected`() {
        val errors = validate("created < unknownFunction()")
        assertEquals(1, errors.size)
        assertTrue(errors.first().message.contains("unknown function"))
    }

    @Test
    fun `function arity mismatch is rejected`() {
        // currentUser is a 0-arg function; passing one arg fails arity.
        val errors = validate("assignee = currentUser(\"foo\")")
        assertEquals(1, errors.size)
        assertTrue(errors.first().message.contains("arg(s)"))
    }
}
