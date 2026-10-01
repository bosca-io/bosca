package bosca.graphql.server

import bosca.graphql.language.FragmentDefinition
import bosca.graphql.language.OperationDefinition
import bosca.graphql.parser.Parser
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**static depth + complexity analysis of an operation (fragment-expanding, `@skip`/`@include`-aware). */
class QueryComplexityTest {

    private fun parse(q: String): Pair<OperationDefinition, Map<String, FragmentDefinition>> {
        val document = Parser.parse(q)
        val operation = document.definitions.filterIsInstance<OperationDefinition>().first()
        val fragments = document.definitions.filterIsInstance<FragmentDefinition>().associateBy { it.name }
        return operation to fragments
    }

    private fun depth(q: String, variables: JsonObject = JsonObject(emptyMap())): Int {
        val (operation, fragments) = parse(q)
        return QueryComplexity.depth(operation, fragments, variables)
    }

    private fun complexity(q: String, calculator: FieldComplexityCalculator = FieldComplexityCalculator.Default, variables: JsonObject = JsonObject(emptyMap())): Int {
        val (operation, fragments) = parse(q)
        return QueryComplexity.complexity(operation, fragments, variables, calculator)
    }

    @Test
    fun `depth counts field nesting`() {
        assertEquals(1, depth("{ a }"))
        assertEquals(2, depth("{ a { b } }"))
        assertEquals(3, depth("{ a { b { c } } }"))
        assertEquals(3, depth("{ a { b { c } } x { y } }")) // max of the two branches
    }

    @Test
    fun `depth expands fragments without adding a level`() {
        assertEquals(3, depth("{ a { ...F } } fragment F on A { b { c } }"))
        assertEquals(3, depth("{ a { ... on A { b { c } } } }"))
    }

    @Test
    fun `depth ignores typename and skipped selections`() {
        assertEquals(0, depth("{ __typename }"))
        assertEquals(1, depth("{ a { b @skip(if: true) { c } } }")) // b removed → only a counts
        assertEquals(3, depth("{ a { b @include(if: true) { c } } }"))
    }

    @Test
    fun `introspection fields consume depth and complexity budgets`() {
        val query = "{ __schema { types { fields { name } } } }"
        assertEquals(4, depth(query))
        assertEquals(4, complexity(query))
        assertEquals(1, depth("""{ __type(name: "Query") { __typename } }"""))
        assertEquals(1, complexity("""{ __type(name: "Query") { __typename } }"""))
    }

    @Test
    fun `complexity is one per field plus its children by default`() {
        assertEquals(1, complexity("{ a }"))
        assertEquals(3, complexity("{ a { b { c } } }")) // c=1, b=2, a=3
        assertEquals(4, complexity("{ a { b { c } } x }")) // a-branch 3 + x 1
    }

    @Test
    fun `complexity honors skip include via a variable`() {
        val skip = buildJsonObject { put("s", JsonPrimitive(true)) }
        assertEquals(1, complexity("query Q(\$s: Boolean!) { a { b @skip(if: \$s) { c } } }", variables = skip)) // b+c dropped → just a
        val keep = buildJsonObject { put("s", JsonPrimitive(false)) }
        assertEquals(3, complexity("query Q(\$s: Boolean!) { a { b @skip(if: \$s) { c } } }", variables = keep))
    }

    @Test
    fun `a custom calculator can weight list fields`() {
        val multiplyLists = FieldComplexityCalculator { field, _, child ->
            if (field.name == "list") 1 + child * 10 else 1 + child
        }
        // c=1, b=2, list = 1 + 2*10 = 21
        assertEquals(21, complexity("{ list { b { c } } }", multiplyLists))
        assertEquals(3, complexity("{ list { b { c } } }")) // default: no multiplier
    }

    @Test
    fun `complexity handles fragments, inline fragments, meta-fields, and the default calculator`() {
        // the 3-arg overload exercises the default calculator
        val (operation, fragments) = parse("{ a { b { c } } }")
        assertEquals(3, QueryComplexity.complexity(operation, fragments, JsonObject(emptyMap())))
        assertEquals(1, complexity("{ a __typename }")) // meta-field contributes 0
        assertEquals(3, complexity("{ a { ...F } } fragment F on A { b { c } }")) // fragment spread
        assertEquals(3, complexity("{ a { ... on A { b { c } } } }")) // inline fragment
    }

    @Test
    fun `a non-boolean skip-if is treated as absent`() {
        // an `if` that is neither a boolean nor a variable is ignored, so the field is kept
        assertEquals(3, depth("{ a { b @skip(if: 5) { c } } }"))
        assertEquals(3, complexity("{ a { b @skip(if: 5) { c } } }"))
    }

    @Test
    fun `a self-referential fragment spread terminates`() {
        // A malformed cyclic fragment must not loop forever; the spread is entered once.
        assertEquals(2, depth("{ a { ...F } } fragment F on A { b ...F }"))
        assertEquals(2, complexity("{ a { ...F } } fragment F on A { b ...F }"))
    }

    @Test
    fun `fragment DAGs are memoized and limit-aware analysis saturates`() {
        val fragments = buildString {
            appendLine("query Q { ...F20 }")
            appendLine("fragment F0 on Query { a }")
            for (index in 1..20) {
                appendLine("fragment F$index on Query { ...F${index - 1} ...F${index - 1} }")
            }
        }
        val (operation, definitions) = parse(fragments)
        assertEquals(1, QueryComplexity.depth(operation, definitions, emptyMap()))
        assertEquals(1_048_576, QueryComplexity.complexity(operation, definitions, emptyMap()))
        assertEquals(101, QueryComplexity.complexityUpTo(operation, definitions, emptyMap(), FieldComplexityCalculator.Default, 100))
    }

    @Test
    fun `complexity arithmetic saturates instead of overflowing`() {
        val negative = FieldComplexityCalculator { _, _, _ -> -1 }
        assertEquals(Int.MAX_VALUE, complexity("{ a }", negative))
        assertEquals(Int.MAX_VALUE, complexity("{ a b }", FieldComplexityCalculator { _, _, _ -> Int.MAX_VALUE }))
        assertEquals(
            Int.MAX_VALUE,
            complexity("{ a b }", FieldComplexityCalculator { field, _, _ -> if (field.name == "a") Int.MAX_VALUE - 1 else 2 }),
        )
        assertEquals(0, complexity("{ ...Missing }"))
        assertEquals(0, depth("{ ...Missing }"))
    }

    @Test
    fun `limit-aware depth handles zero and maximum integer bounds`() {
        val (nested, fragments) = parse("{ a { b } }")
        assertEquals(1, QueryComplexity.depthUpTo(nested, fragments, emptyMap(), 0))
        val (leaf, noFragments) = parse("{ a }")
        assertEquals(1, QueryComplexity.depthUpTo(leaf, noFragments, emptyMap(), Int.MAX_VALUE))
    }

    @Test
    fun `query instrumentation limits cannot be negative`() {
        assertFailsWith<IllegalArgumentException> { MaxQueryDepthInstrumentation(-1) }
        assertFailsWith<IllegalArgumentException> { MaxQueryComplexityInstrumentation(-1) }
    }
}
