package bosca.graphql.validation

import bosca.graphql.parser.Parser
import bosca.graphql.schema.GraphQLSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OperationValidatorTest {

    private val schema = GraphQLSchema.fromSdl(
        """
        type Query {
          me: User
          user(id: ID!): User
          search: SearchResult
          node: Node
        }
        type User { id: ID! name: String! friends: [User!] }
        interface Node { id: ID! }
        union SearchResult = User
        """.trimIndent(),
    )

    private val validator = OperationValidator(schema)

    private fun errors(doc: String) = validator.validate(Parser.parse(doc))

    @Test
    fun `a well-formed operation produces no errors`() {
        assertEquals(
            emptyList(),
            errors(
                """
                query {
                  me { id name friends { id } }
                  user(id: "1") { name }
                  __typename
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `flags a field that does not exist`() {
        val e = errors("{ me { nope } }")
        assertEquals(1, e.size)
        assertTrue(e.single().message.contains("'nope' does not exist on type 'User'"), e.toString())
    }

    @Test
    fun `flags a leaf field with a selection set`() {
        val e = errors("{ me { name { x } } }")
        assertTrue(e.any { it.message.contains("leaf type 'String'") }, e.toString())
    }

    @Test
    fun `flags a composite field without a selection set`() {
        val e = errors("{ me }")
        assertTrue(e.any { it.message.contains("composite type 'User'") }, e.toString())
    }

    @Test
    fun `flags an unknown argument`() {
        val e = errors("""{ user(wrong: "1") { id } }""")
        assertTrue(e.any { it.message.contains("Unknown argument 'wrong'") }, e.toString())
    }

    @Test
    fun `flags an unknown fragment spread`() {
        val e = errors("{ me { ...Missing } }")
        assertTrue(e.any { it.message.contains("Unknown fragment '...Missing'") }, e.toString())
    }

    @Test
    fun `validates a defined fragment and inline fragment`() {
        assertEquals(
            emptyList(),
            errors(
                """
                query { me { ...UserFields } search { ... on User { name } } }
                fragment UserFields on User { id name }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `flags an inline fragment on an unknown type`() {
        val e = errors("{ search { ... on Ghost { id } } }")
        assertTrue(e.any { it.message.contains("unknown type 'Ghost'") }, e.toString())
    }

    @Test
    fun `flags a selecting a field directly on a union`() {
        // unions expose no fields directly — you must use inline fragments (or __typename)
        val e = errors("{ search { name } }")
        assertTrue(e.any { it.message.contains("'name' does not exist on type 'SearchResult'") }, e.toString())
    }

    @Test
    fun `flags an operation whose root type is undefined`() {
        val e = errors("mutation { doThing }")
        assertTrue(e.any { it.message.contains("does not define a mutation root type") }, e.toString())
    }

    @Test
    fun `validateOrThrow raises on an invalid document`() {
        assertFailsWith<IllegalArgumentException> { validator.validateOrThrow(Parser.parse("{ me { nope } }")) }
        // and is silent on a valid one
        validator.validateOrThrow(Parser.parse("{ me { id } }"))
    }

    @Test
    fun `flags __typename carrying a selection set`() {
        val e = errors("{ __typename { x } }")
        assertTrue(e.any { it.message.contains("__typename") }, e.toString())
    }

    @Test
    fun `flags a fragment defined on an unknown type`() {
        val e = errors("query { me { id } }\nfragment F on Ghost { x }")
        assertTrue(e.any { it.message.contains("unknown type 'Ghost'") }, e.toString())
    }

    @Test
    fun `allows an inline fragment with no type condition`() {
        assertEquals(emptyList(), errors("{ me { ... { id } } }"))
    }

    @Test
    fun `rejects type-system definitions mixed into an executable document`() {
        // §5.1.1: a document with executable definitions must not contain type-system definitions.
        val problems = errors("type Extra { x: Int }\nquery { me { id } }")
        assertTrue(problems.any { it.message.contains("must not contain type-system definitions") }, problems.toString())
    }
}
