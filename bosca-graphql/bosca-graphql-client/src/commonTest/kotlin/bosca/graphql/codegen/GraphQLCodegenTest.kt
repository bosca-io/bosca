package bosca.graphql.codegen

import bosca.graphql.schema.GraphQLSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Drives the project-level [GraphQLCodegen] orchestrator: many `.graphql` sources (operations + shared
 * fragments scattered across files) → one self-contained generated file per operation. The single-operation
 * emission itself is covered by [KotlinClientGeneratorTest]; this pins the multi-file stitching.
 */
class GraphQLCodegenTest {

    private val schema = GraphQLSchema.fromSdl(
        """
        type Query { user(id: ID!): User node: Node }
        interface Node { id: ID! }
        type User implements Node { id: ID! name: String! email: String }
        """.trimIndent(),
    )

    @Test
    fun `emits one named file per operation across multiple sources`() {
        val files = GraphQLCodegen(schema).generate(
            listOf(
                "query GetUser(\$id: ID!) { user(id: \$id) { id name } }",
                "query GetEmail(\$id: ID!) { user(id: \$id) { email } }",
            ),
            "gen",
        )

        // Neither operation spreads a named fragment, so no interface files are emitted.
        assertEquals(listOf("GetEmail.kt", "GetUser.kt"), files.map { it.name }.sorted())
        val getUser = files.single { it.name == "GetUser.kt" }
        assertTrue("object GetUser : BoscaOperation" in getUser.content, getUser.content)
        assertTrue("package gen" in getUser.content, getUser.content)
    }

    @Test
    fun `a shared fragment in its own source is carried only into the operations that use it`() {
        val files = GraphQLCodegen(schema).generate(
            listOf(
                "fragment UserFields on User { id name }",
                "query WithFragment(\$id: ID!) { user(id: \$id) { ...UserFields } }",
                "query WithoutFragment(\$id: ID!) { user(id: \$id) { email } }",
            ),
            "gen",
        )

        val withFragment = files.single { it.name == "WithFragment.kt" }
        val withoutFragment = files.single { it.name == "WithoutFragment.kt" }
        // the consuming operation's document carries the fragment definition AND flattens its fields
        assertTrue("fragment UserFields on User { id name }" in withFragment.content, withFragment.content)
        assertTrue("val id: String," in withFragment.content && "val name: String," in withFragment.content, withFragment.content)
        // the unrelated operation does not drag the fragment in
        assertTrue("UserFields" !in withoutFragment.content, withoutFragment.content)
    }

    @Test
    fun `transitive fragment references are resolved`() {
        val files = GraphQLCodegen(schema).generate(
            listOf(
                "fragment Outer on User { id ...Inner }",
                "fragment Inner on User { name }",
                "query Q(\$id: ID!) { user(id: \$id) { ...Outer } }",
            ),
            "gen",
        )
        val content = files.single { it.name == "Q.kt" }.content // plus an IOuter.kt interface file
        assertTrue("fragment Outer on User" in content, content)
        assertTrue("fragment Inner on User" in content, content) // pulled in transitively
        assertTrue("override val id: String," in content && "override val name: String," in content, content) // implements IOuter
    }

    @Test
    fun `a duplicate operation name fails fast`() {
        assertFailsWith<IllegalArgumentException> {
            GraphQLCodegen(schema).generate(
                listOf(
                    "query Q(\$id: ID!) { user(id: \$id) { id } }",
                    "query Q(\$id: ID!) { user(id: \$id) { name } }",
                ),
                "gen",
            )
        }
    }

    @Test
    fun `a duplicate fragment name fails fast`() {
        assertFailsWith<IllegalArgumentException> {
            GraphQLCodegen(schema).generate(
                listOf(
                    "fragment F on User { id }",
                    "fragment F on User { name }",
                    "query Q(\$id: ID!) { user(id: \$id) { ...F } }",
                ),
                "gen",
            )
        }
    }

    @Test
    fun `an operation referencing an undefined fragment fails fast`() {
        assertFailsWith<IllegalStateException> {
            GraphQLCodegen(schema).generate(listOf("query Q(\$id: ID!) { user(id: \$id) { ...Missing } }"), "gen")
        }
    }

    @Test
    fun `an anonymous operation fails fast`() {
        assertFailsWith<IllegalStateException> {
            GraphQLCodegen(schema).generate(listOf("query Q(\$id: ID!) { user(id: \$id) { id } }\n{ node { id } }"), "gen")
        }
    }

    @Test
    fun `the SDL constructor parses the schema text`() {
        val files = GraphQLCodegen("type Query { now: String! }").generate(listOf("query Now { now }"), "gen")
        assertTrue("object Now : BoscaOperation<Unit, NowData>" in files.single().content, files.single().content)
    }

    private val sharedTypeSchema = GraphQLSchema.fromSdl(
        """
        type Query { search(filter: NoteFilter!): Note findOne(filter: NoteFilter!): Note }
        type Note { id: ID! visibility: Visibility }
        enum Visibility { PUBLIC PRIVATE }
        input NoteFilter { term: String! visibility: Visibility }
        """.trimIndent(),
    )

    @Test
    fun `shared enums and input objects are hoisted into their own files exactly once`() {
        val files = GraphQLCodegen(sharedTypeSchema).generate(
            listOf(
                "query Search(\$filter: NoteFilter!) { search(filter: \$filter) { id visibility } }",
                "query FindOne(\$filter: NoteFilter!) { findOne(filter: \$filter) { id visibility } }",
            ),
            "gen",
        )

        // No named fragment is spread, so no interface files; only the shared enum + input are hoisted once.
        assertEquals(listOf("FindOne.kt", "NoteFilter.kt", "Search.kt", "Visibility.kt"), files.map { it.name }.sorted())
        assertTrue("enum class Visibility {" in files.single { it.name == "Visibility.kt" }.content)
        assertTrue("data class NoteFilter(" in files.single { it.name == "NoteFilter.kt" }.content)
        // operation files REFERENCE the shared types by name but never re-declare them (no package collision)
        val search = files.single { it.name == "Search.kt" }.content
        assertTrue("enum class Visibility" !in search && "data class NoteFilter" !in search, search)
        assertTrue("val visibility: Visibility?," in search, search)
        assertTrue("val filter: NoteFilter," in search, search)
    }

    @Test
    fun `the TypeScript target emits one ts file per operation from the same stitched sources`() {
        val files = GraphQLCodegen(schema).generateTypeScript(
            listOf(
                "fragment UserFields on User { id name }",
                "query WithFragment(\$id: ID!) { user(id: \$id) { ...UserFields } }",
                "query GetEmail(\$id: ID!) { user(id: \$id) { email } }",
            ),
        )
        assertEquals(listOf("GetEmail.ts", "WithFragment.ts"), files.map { it.name }.sorted())
        val withFragment = files.single { it.name == "WithFragment.ts" }.content
        assertTrue("export function withFragment(variables: WithFragmentVariables): Promise<WithFragmentData>" in withFragment, withFragment)
        assertTrue("fragment UserFields on User { id name }" in withFragment, withFragment) // transitive fragment carried
    }
}
