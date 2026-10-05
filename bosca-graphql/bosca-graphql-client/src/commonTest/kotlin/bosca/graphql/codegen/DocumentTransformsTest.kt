package bosca.graphql.codegen

import bosca.graphql.language.Field
import bosca.graphql.language.FragmentDefinition
import bosca.graphql.language.OperationDefinition
import bosca.graphql.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals

/** The codegen `__typename` injection rule: inject into polymorphic sets that lack it, leave others untouched. */
class DocumentTransformsTest {

    private fun typenameCount(query: String): Int {
        val injected = Parser.parse(query).withInjectedTypenames()
        var count = 0
        fun walk(selections: List<bosca.graphql.language.Selection>) {
            for (s in selections) when (s) {
                is Field -> {
                    if (s.name == "__typename") count++
                    s.selectionSet?.let { walk(it.selections) }
                }
                is bosca.graphql.language.InlineFragment -> walk(s.selectionSet.selections)
                is bosca.graphql.language.FragmentSpread -> {}
            }
        }
        injected.definitions.forEach {
            when (it) {
                is OperationDefinition -> walk(it.selectionSet.selections)
                is FragmentDefinition -> walk(it.selectionSet.selections)
                else -> {}
            }
        }
        return count
    }

    @Test
    fun `a polymorphic selection without __typename gets exactly one injected`() {
        // hasFragments && !hasTypename → inject
        assertEquals(1, typenameCount("query Q { node { ... on User { name } } }"))
    }

    @Test
    fun `a polymorphic selection that already selects __typename is left untouched`() {
        // hasFragments && !hasTypename → right operand false → no injection (still exactly one)
        assertEquals(1, typenameCount("query Q { node { __typename ... on User { name } } }"))
    }

    @Test
    fun `a plain object selection with no fragments gets none`() {
        // hasFragments is false → short-circuits, nothing injected
        assertEquals(0, typenameCount("query Q { me { id name } }"))
    }
}
