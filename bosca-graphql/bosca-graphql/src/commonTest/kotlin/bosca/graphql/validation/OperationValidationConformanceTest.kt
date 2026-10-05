package bosca.graphql.validation

import bosca.graphql.language.FieldDefinition
import bosca.graphql.language.InputValueDefinition
import bosca.graphql.language.NamedType
import bosca.graphql.language.ObjectTypeDefinition
import bosca.graphql.language.ScalarTypeDefinition
import bosca.graphql.parser.Parser
import bosca.graphql.schema.GraphQLSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * conformance corpus: one valid document that exercises the full executable surface, plus one
 * document violating each rule, asserting the expected [ValidationError]. Complements [OperationValidatorTest]
 * (which pins the original subset).
 */
class OperationValidationConformanceTest {

    private val schema = GraphQLSchema.fromSdl(
        """
        type Query {
          user(id: ID!): User
          search(filter: Filter, status: Status, tags: [String!]): [User!]
          node: Node
          pet: Pet
          calc(weight: Float, flag: Boolean): Int
          at(t: DateTime): Int
        }
        scalar DateTime
        type User implements Node { id: ID! name: String! }
        type Admin implements Node { id: ID! level: Int! }
        interface Node { id: ID! }
        union Pet = Dog
        type Dog { id: ID! barks: Boolean! }
        enum Status { ACTIVE ARCHIVED }
        input Filter { term: String! limit: Int }
        directive @upper on FIELD
        directive @tag(name: String!) repeatable on FIELD
        directive @onQuery on QUERY
        """.trimIndent(),
    )

    private val validator = OperationValidator(schema)
    private fun errors(doc: String) = validator.validate(Parser.parse(doc))
    private fun assertError(doc: String, fragment: String) {
        val found = errors(doc)
        assertTrue(found.any { it.message.contains(fragment) }, "expected an error containing \"$fragment\", got: $found")
    }

    @Test
    fun `a fully-featured valid document produces no errors`() {
        assertEquals(
            emptyList(),
            errors(
                """
                query GetUser(${'$'}id: ID!, ${'$'}f: Filter) {
                  user(id: ${'$'}id) @upper { id name }
                  search(filter: ${'$'}f, status: ACTIVE, tags: ["a"]) { ...Fields }
                  node { ... on User { name } ... on Admin { level } }
                }
                fragment Fields on User { id name @tag(name: "x") @tag(name: "y") }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `unique names`() {
        assertError("query A { node { id } } query A { node { id } }", "Duplicate operation name 'A'")
        assertError("query B { node { id } } { node { id } }", "anonymous operation must be the only")
        assertError("query Q { user(id: \"1\") { ...F } } fragment F on User { id } fragment F on User { name }", "Duplicate fragment name 'F'")
        assertError("{ user(id: \"1\", id: \"2\") { id } }", "Duplicate argument 'id'")
        assertError("query Q(\$x: ID!, \$x: ID!) { user(id: \$x) { id } }", "Duplicate variable '\$x'")
    }

    @Test
    fun `fragment cycles and unused fragments`() {
        assertError(
            "query Q { user(id: \"1\") { ...A } } fragment A on User { ...B } fragment B on User { ...A }",
            "(cycle)",
        )
        assertError("query Q { user(id: \"1\") { id } } fragment Unused on User { id }", "Fragment 'Unused' is never used")
    }

    @Test
    fun `variable usage and types`() {
        assertError("query Q(\$x: ID!) { user(id: \"1\") { id } }", "Variable '\$x' is defined but never used")
        assertError("query Q { user(id: \$x) { id } }", "Variable '\$x' is used but not defined")
        assertError("query Q(\$x: User) { user(id: \"1\") { id } }", "non-input type 'User'")
    }

    @Test
    fun `arguments — unknown, required, value type`() {
        assertError("{ user(wrong: \"1\") { id } }", "Unknown argument 'wrong'")
        assertError("{ user { id } }", "Missing required argument 'id'")
        assertError("{ user(id: true) { id } }", "is not a valid 'ID'")
        assertError("{ search(status: NOPE) { id } }", "is not a valid 'Status'")
    }

    @Test
    fun `input object values`() {
        assertError("{ search(filter: { term: \"x\", bogus: 1 }) { id } }", "Unknown input field 'bogus' on 'Filter'")
        assertError("{ search(filter: { limit: 1 }) { id } }", "Missing required input field 'term' on 'Filter'")
        assertError("{ search(filter: { term: \"x\", term: \"y\" }) { id } }", "Duplicate input field 'term'")
        assertError("{ search(filter: { term: 1 }) { id } }", "input field 'Filter.term' is not a valid 'String'")
    }

    @Test
    fun `fragment spread must be possible (type overlap)`() {
        assertError("{ pet { ...DogFields } } fragment DogFields on User { id }", "cannot be spread on 'Pet'")
        assertError("{ pet { ... on User { id } } }", "Inline fragment on 'User' cannot be spread on 'Pet'")
    }

    @Test
    fun `value coercion and scalar edge cases`() {
        // explicit null into a non-null position
        assertError("{ user(id: null) { id } }", "Expected a non-null value for argument 'id'")
        // single value coerces into a list position (valid)
        assertEquals(emptyList(), errors("{ search(tags: \"a\") { id } }"))
        // built-in scalar mismatches
        assertError("{ calc(weight: \"x\") }", "is not a valid 'Float'")
        assertError("{ calc(flag: 1) }", "is not a valid 'Boolean'")
        assertError("{ search(filter: { term: \"x\", limit: \"no\" }) { id } }", "is not a valid 'Int'")
        // a non-enum literal in an enum position
        assertError("{ search(status: \"ACTIVE\") { id } }", "is not a valid 'Status'")
        // a non-object literal in an input-object position
        assertError("{ search(filter: \"x\") { id } }", "Expected an input object 'Filter'")
        // a custom scalar literal cannot be validated structurally (accepted)
        assertEquals(emptyList(), errors("{ at(t: \"anything\") }"))
        // Int coerces into a Float position (valid)
        assertEquals(emptyList(), errors("{ calc(weight: 1) }"))
    }

    @Test
    fun `an argument typed as an output type is flagged (defensive — only reachable via a malformed schema)`() {
        // The hardened SchemaBuilder rejects an output-typed argument, so build the schema directly to reach
        // the operation validator's defensive branch.
        val malformed = GraphQLSchema(
            types = mapOf(
                "Query" to ObjectTypeDefinition(
                    null, "Query", emptyList(), emptyList(),
                    listOf(
                        FieldDefinition(
                            null, "f",
                            listOf(InputValueDefinition(null, "u", NamedType("Out"), null, emptyList())),
                            NamedType("Int"), emptyList(),
                        ),
                    ),
                ),
                "Out" to ObjectTypeDefinition(
                    null, "Out", emptyList(), emptyList(),
                    listOf(FieldDefinition(null, "y", emptyList(), NamedType("Int"), emptyList())),
                ),
                "Int" to ScalarTypeDefinition(null, "Int", emptyList()),
            ),
            directives = emptyMap(),
            queryTypeName = "Query",
            mutationTypeName = null,
            subscriptionTypeName = null,
        )
        val found = OperationValidator(malformed).validate(Parser.parse("{ f(u: { x: 1 }) }"))
        assertTrue(found.any { it.message.contains("is not an input type") }, found.toString())
    }

    @Test
    fun `directive location, uniqueness, and arguments`() {
        assertError("{ user(id: \"1\") @nope { id } }", "Unknown directive '@nope'")
        assertError("{ user(id: \"1\") @onQuery { id } }", "Directive '@onQuery' is not allowed on FIELD")
        assertError("{ user(id: \"1\") @upper @upper { id } }", "Directive '@upper' is not repeatable")
        assertError("{ user(id: \"1\") @tag { id } }", "Missing required argument 'name' on directive '@tag'")
    }
}
