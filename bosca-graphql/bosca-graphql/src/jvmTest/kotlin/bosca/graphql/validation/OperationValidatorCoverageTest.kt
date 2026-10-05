package bosca.graphql.validation

import bosca.graphql.parser.Parser
import bosca.graphql.schema.GraphQLSchema
import kotlin.test.Test
import kotlin.test.assertTrue

/** Edge-case coverage of [OperationValidator]: fragment cycles, inline fragments, argument + literal type checks. */
class OperationValidatorCoverageTest {

    private val schema = GraphQLSchema.fromSdl(
        """
        type Query {
          a: A
          scalars(i: Int, f: Float, b: Boolean, id: ID, s: String): String
          listArg(xs: [Int]): String
          enumArg(e: E): String
          inputArg(input: In): String
          required(x: Int!, withDefault: Int! = 9): String
        }
        type A { b: B }
        type B { c: String }
        enum E { ONE TWO }
        input In { required: Int! optional: Int withDefault: Int! = 5 }
        """.trimIndent(),
    )

    private fun errors(query: String): List<ValidationError> = OperationValidator(schema).validate(Parser.parse(query))

    private fun assertError(query: String, fragment: String) {
        val found = errors(query)
        assertTrue(found.any { it.message.contains(fragment) }, "expected an error containing '$fragment', got ${found.map { it.message }}")
    }

    private fun assertValid(query: String) {
        val found = errors(query)
        assertTrue(found.isEmpty(), "expected no errors, got ${found.map { it.message }}")
    }

    @Test
    fun `a fragment spreading an undefined fragment is handled in cycle detection`() {
        // the spread of an undefined fragment is skipped by cycle detection and flagged elsewhere
        assertError("{ a { ...F } } fragment F on A { b { c } ...Missing }", "Missing")
    }

    @Test
    fun `a non-cyclic fragment chain is valid`() {
        assertValid("{ a { ...F } } fragment F on A { b { ...G } } fragment G on B { c }")
    }

    @Test
    fun `a self-referential fragment is reported as a cycle`() {
        assertError("{ a { ...F } } fragment F on A { b { c } ...F }", "cycle")
    }

    @Test
    fun `cycle detection handles a back-edge reported twice and a spread of an already-finished fragment`() {
        // F spreads G, H, I. G and H both cycle back to F (so F's cycle is reported once, then deduped).
        // I spreads G, which is already finished by the time I is visited (the not-on-stack, already-done path).
        val query = """
            { a { ...F } }
            fragment F on A { b { c } ...G ...H ...I }
            fragment G on A { b { c } ...F }
            fragment H on A { b { c } ...F }
            fragment I on A { b { c } ...G }
        """.trimIndent()
        assertError(query, "cycle")
    }

    @Test
    fun `an inline fragment with and without a type condition both validate`() {
        assertValid("{ a { ... { b { c } } } }") // no type condition → enclosing type
        assertValid("{ a { ... on A { b { c } } } }") // explicit type condition
    }

    @Test
    fun `a missing required argument is reported but one with a default is not`() {
        assertError("{ required }", "x") // x is required, no default → error
        assertValid("{ required(x: 1) }") // withDefault omitted but has a default → fine
    }

    @Test
    fun `a missing required input field is reported but one with a default is not`() {
        assertError("{ inputArg(input: { optional: 1 }) }", "required") // `required` missing
        assertValid("{ inputArg(input: { required: 1 }) }") // `withDefault` omitted but has a default → fine
    }

    @Test
    fun `scalar literal type mismatches are reported`() {
        assertError("{ scalars(f: true) }", "Float")
        assertError("{ scalars(b: 1) }", "Boolean")
        assertError("{ scalars(id: true) }", "ID")
        assertError("{ scalars(i: \"x\") }", "Int")
        assertError("{ scalars(s: 1) }", "String")
    }

    @Test
    fun `scalar literals of the right type are valid`() {
        assertValid("{ scalars(i: 1, f: 1.5, b: true, id: \"x\", s: \"y\") }")
        assertValid("{ scalars(f: 2, id: 7) }") // Int coerces to Float; Int is a valid ID
    }

    @Test
    fun `a null literal is accepted for a nullable argument and inside a nullable list`() {
        assertValid("{ scalars(i: null) }")
        assertValid("{ listArg(xs: null) }")
        assertValid("{ listArg(xs: [1, null, 3]) }")
    }

    @Test
    fun `an enum literal is checked against its values`() {
        assertValid("{ enumArg(e: ONE) }")
        assertError("{ enumArg(e: NOPE) }", "E")
    }

    @Test
    fun `input object required-field and unknown-field checks fire`() {
        assertValid("{ inputArg(input: { required: 1 }) }")
        assertError("{ inputArg(input: { required: 1, bogus: 2 }) }", "bogus")
    }
}
