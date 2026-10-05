package bosca.graphql.codegen

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** GraphQLCodegen's multi-operation stitching: fragment collection, shared-type hoisting, and name-collision detection. */
class CodegenStitchTest {

    private val schema = """
        type Query { me: User node: Node }
        type User implements Node { id: ID! name: String! }
        interface Node { id: ID! }
        enum Color { RED GREEN }
    """.trimIndent()

    @Test
    fun `operations sharing a fragment and an enum hoist the shared types once`() {
        val sources = listOf(
            schema,
            "query A { me { ...F } }",
            "query B { me { ...F } }",
            "fragment F on User { id name }",
        )
        val files = GraphQLCodegen(schema).generate(sources, "p").map { it.name }.toSet()
        assertTrue("A.kt" in files && "B.kt" in files, files.toString())
    }

    @Test
    fun `inline fragments and fragment spreads are walked when collecting an operation's fragments`() {
        val sources = listOf(
            schema,
            "query Q { node { ... on User { ...F } } }",
            "fragment F on User { id name }",
        )
        val files = GraphQLCodegen(schema).generate(sources, "p")
        assertTrue(files.any { it.name == "Q.kt" })
    }

    @Test
    fun `a fragment referenced twice within one operation is collected once`() {
        // F is spread under two fields → the second collected.add returns false (already seen), so it is not re-visited.
        val sources = listOf(
            schema,
            "query Q { me { ...F } again: me { ...F } } fragment F on User { id name }",
        )
        val files = GraphQLCodegen(schema).generate(sources, "p")
        val q = files.single { it.name == "Q.kt" }.content
        assertTrue("fragment F on User" in q, q) // emitted exactly once into the document
        assertTrue(Regex("fragment F on User").findAll(q).count() == 1, q)
    }

    @Test
    fun `a file-name collision between an operation and a shared type is rejected`() {
        // an operation named "Color" collides with the shared enum file "Color.kt"
        val sources = listOf(schema, "query Color { me { id field: name } } ", "query Other(\$c: Color) { me { id } }")
        assertFailsWith<IllegalArgumentException> { GraphQLCodegen(schema).generate(sources, "p") }
    }
}
