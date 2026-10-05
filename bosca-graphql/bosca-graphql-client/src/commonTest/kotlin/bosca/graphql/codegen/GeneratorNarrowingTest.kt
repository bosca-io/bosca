package bosca.graphql.codegen

import bosca.graphql.parser.Parser
import bosca.graphql.schema.GraphQLSchema
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Generator handling of narrowing fragments on a concrete type, nested lists, and enum-typed variables. */
class GeneratorNarrowingTest {

    private val schema = GraphQLSchema.fromSdl(
        """
        type Query { me: User board: [[Int]] }
        type User { id: ID! name: String! }
        type Admin { id: ID! level: Int! }
        enum Color { RED GREEN }
        """.trimIndent(),
    )

    private fun ts(query: String) = TypeScriptClientGenerator(schema).generate(Parser.parse(query))
    private fun kt(query: String) = KotlinClientGenerator(schema).generate(Parser.parse(query), "p")

    @Test
    fun `nested list return types render as nested wrappers`() {
        assertTrue("Array<Array<" in ts("query B { board }"), ts("query B { board }"))
        assertTrue("List<List<" in kt("query B { board }"), kt("query B { board }"))
    }

    @Test
    fun `an enum-typed variable emits the enum and renders its variable type`() {
        val q = "query E(\$c: Color) { me { id } }"
        assertTrue("Color" in ts(q), ts(q))
        assertTrue("enum class Color" in kt(q), kt(q))
    }
}
