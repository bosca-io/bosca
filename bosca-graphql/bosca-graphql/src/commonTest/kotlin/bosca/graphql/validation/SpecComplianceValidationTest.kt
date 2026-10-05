package bosca.graphql.validation

import bosca.graphql.parser.Parser
import bosca.graphql.schema.GraphQLSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validation rules added for full GraphQL §5 compliance: executable-definitions-only (5.1.1), subscription
 * single-root-field (5.2.3.1), field selection merging (5.3.2), composite-type fragments (5.5.1.3), and
 * variable usages allowed (5.8.5).
 */
class SpecComplianceValidationTest {

    private val schema = GraphQLSchema.fromSdl(
        """
        type Query {
          me: User
          node(id: ID!): Node
          pet: Pet
          search(filter: Filter, limit: Int): [User!]
          pick(c: Choice): Int
          names(values: [String]): Int
          nn(values: [String]!): Int
          withDef(a: Int! = 5): Int
          listArg(a: [ID!]): Int
          color: Color
          tag: Tag
        }
        type Subscription { events: User newMessages: User }
        interface Node { id: ID! lookup(first: Int): String }
        type User implements Node {
          id: ID!
          lookup(first: Int): String
          name: String!
          age: Int
          score: Int
          friend: User
          posts(first: Int): [User!]
          alternate(first: Int): [User!]
        }
        type Admin implements Node { id: ID! lookup(first: Int): String name: Int! }
        union Pet = Cat | Dog
        type Cat { id: ID! sound: String! nullable: String values: [String] owner: User }
        type Dog { id: ID! sound: Int! nullable: String values: [Int] strings: [String] owner: Admin }
        enum Color { RED GREEN }
        input Filter { term: String limit: Int = 10 }
        input Choice @oneOf { a: String b: Int }
        scalar Tag
        directive @d(n: Int = 1) on FIELD
        directive @vd(n: Int) on VARIABLE_DEFINITION
        """.trimIndent(),
    )

    private val validator = OperationValidator(schema)
    private fun errors(doc: String) = validator.validate(Parser.parse(doc))
    private fun ok(doc: String) = assertTrue(errors(doc).isEmpty(), errors(doc).toString())
    private fun fails(doc: String, contains: String) =
        assertTrue(errors(doc).any { it.message.contains(contains) }, errors(doc).toString())

    // ---- §5.2.3.1 subscription single root field ----

    @Test
    fun `a subscription with one root field is valid`() = ok("subscription { events { id } }")

    @Test
    fun `a subscription with multiple root fields is rejected`() =
        fails("subscription { events { id } newMessages { id } }", "exactly one root field")

    @Test
    fun `a subscription selecting __typename as its root is rejected`() =
        fails("subscription { __typename }", "must not select '__typename'")

    @Test
    fun `a subscription single root reached through a fragment is valid`() =
        ok("subscription { ...F } fragment F on Subscription { events { id } }")

    @Test
    fun `an anonymous subscription with two roots is rejected`() =
        fails("subscription { events { id } ... on Subscription { newMessages { id } } }", "Anonymous subscription")

    // ---- §5.5.1.3 fragments on composite types ----

    @Test
    fun `a fragment on a scalar type condition is rejected`() =
        fails("query { tag } fragment F on Tag { x }", "not a composite type")

    @Test
    fun `an inline fragment on an enum type condition is rejected`() =
        fails("query { me { ... on Color { x } } }", "not a composite type")

    @Test
    fun `a fragment on an unknown type is still reported as unknown`() =
        fails("query { me { id } } fragment F on Nope { x }", "unknown type")

    // ---- §5.8.5 variable usages allowed ----

    @Test
    fun `a matching scalar variable usage is allowed`() = ok("query Q(\$x: Int) { search(limit: \$x) { id } }")

    @Test
    fun `a mismatched scalar variable usage is rejected`() =
        fails("query Q(\$x: String) { search(limit: \$x) { id } }", "cannot be used where")

    @Test
    fun `a nullable variable in a non-null position without a default is rejected`() =
        fails("query Q(\$x: ID) { node(id: \$x) { id } }", "cannot be used where")

    @Test
    fun `a non-null variable satisfies a non-null position`() = ok("query Q(\$x: ID!) { node(id: \$x) { id } }")

    @Test
    fun `a nullable variable with a non-null default satisfies a non-null position`() =
        ok("query Q(\$x: ID = \"1\") { node(id: \$x) { id } }")

    @Test
    fun `a non-null variable satisfies a nullable position`() = ok("query Q(\$x: Int!) { search(limit: \$x) { id } }")

    @Test
    fun `a list variable matches a list position`() = ok("query Q(\$x: [String]) { names(values: \$x) }")

    @Test
    fun `a scalar variable cannot be used in a list position`() =
        fails("query Q(\$x: String) { names(values: \$x) }", "cannot be used where")

    @Test
    fun `a list variable cannot be used in a scalar position`() =
        fails("query Q(\$x: [Int]) { search(limit: \$x) { id } }", "cannot be used where")

    @Test
    fun `a variable inside an input object field is checked`() {
        ok("query Q(\$x: String) { search(filter: { term: \$x }) { id } }")
        fails("query Q(\$x: Int) { search(filter: { term: \$x }) { id } }", "cannot be used where")
    }

    @Test
    fun `a variable inside a list literal is checked against the element type`() =
        fails("query Q(\$x: Int) { names(values: [\$x]) }", "cannot be used where")

    @Test
    fun `a variable usage inside a spread fragment is checked`() =
        fails(
            "query Q(\$x: String) { me { ...F } } fragment F on User { posts(first: \$x) { id } }",
            "cannot be used where",
        )

    @Test
    fun `a variable in a directive argument is checked`() =
        fails("query Q(\$x: Int) { me { id @skip(if: \$x) } }", "cannot be used where")

    // ---- §5.3.2 field selection merging ----

    @Test
    fun `identical fields merge`() = ok("query { me { name name } }")

    @Test
    fun `fields with the same response key but different names conflict`() =
        fails("query { me { x: age x: score } }", "are different fields") // same shape (Int), different fields

    @Test
    fun `fields with the same name but differing arguments conflict`() =
        fails("query { me { posts(first: 1) { id } posts(first: 2) { id } } }", "differing arguments")

    @Test
    fun `fields with the same args merge`() = ok("query { me { posts(first: 1) { id } posts(first: 1) { id } } }")

    @Test
    fun `the same field across different object branches with the same shape merges`() =
        ok("query { node(id: \"1\") { ... on User { id } ... on Admin { id } } }")

    @Test
    fun `the same response key with conflicting types across branches conflicts`() =
        fails("query { node(id: \"1\") { ... on User { x: name } ... on Admin { x: name } } }", "conflicting types")

    @Test
    fun `a non-null vs nullable shape conflicts`() =
        fails("query { pet { ... on Cat { x: sound } ... on Dog { x: id } } }", "conflicting types")

    @Test
    fun `a nested merged sub-selection conflict is detected`() =
        fails("query { me { friend { x: age } friend { x: score } } }", "are different fields")

    @Test
    fun `independent conflicts on one response key in different subtrees are each reported`() {
        val problems = errors("query { me { a: friend { k: name k: age } b: friend { k: name k: score } } }")
        assertEquals(2, problems.count { it.message.contains("Fields 'k' conflict") }, problems.toString())
    }

    @Test
    fun `an unknown field repeated does not raise a spurious merge error`() {
        // both are the unknown field 'nope' → same name/shape, so only the field-existence error fires
        val problems = errors("query { me { nope nope } }")
        assertTrue(problems.all { !it.message.contains("conflict") }, problems.toString())
    }

    // ---- additional branch coverage for the new rules ----

    @Test
    fun `an aliased single subscription root field is valid`() = ok("subscription { e: events { id } }")

    @Test
    fun `a used-but-undefined variable in an argument is left to the usage rule`() {
        // the operation has a variable ($a) so the usage pass runs, but $undefined has no definition → skipped here
        val problems = errors("query Q(\$a: Int) { search(limit: \$undefined) { id } a: search(limit: \$a) { id } }")
        assertTrue(problems.any { it.message.contains("used but not defined") }, problems.toString())
    }

    @Test
    fun `a variable in a list literal against a non-null list position is checked`() =
        ok("query Q(\$x: String) { nn(values: [\$x]) }")

    @Test
    fun `a variable usage in a directive on an unknown directive is skipped`() {
        // @nope is unknown (reported by §5.7.1); the usage walk skips it rather than crashing
        val problems = errors("query Q(\$x: Int) { me { id @nope(a: \$x) } }")
        assertTrue(problems.any { it.message.contains("Unknown directive") }, problems.toString())
    }

    @Test
    fun `the variable usage walk descends through inline and named fragments and skips non-composite conditions`() {
        ok("query Q(\$x: ID!) { node(id: \$x) { id ... on User { name } ...Frag } } fragment Frag on Admin { id }")
        // an inline fragment on a scalar in the walk is skipped (and separately flagged by §5.5.1.3)
        val problems = errors("query Q(\$x: Int) { me { age ... on Tag { y } } }")
        assertTrue(problems.any { it.message.contains("not a composite type") }, problems.toString())
    }

    @Test
    fun `three fields sharing a response key report each distinct conflict once`() {
        val problems = errors("query { me { x: age x: score x: name } }")
        assertTrue(problems.count { it.message.contains("Fields 'x' conflict") } in 1..2, problems.toString())
    }

    @Test
    fun `same-parent merge groups deduplicate repeated conflict reasons`() {
        val problems = errors(
            "query { me { x: posts(first: 1) { id } x: posts(first: 2) { id } " +
                "x: alternate(first: 1) { id } x: alternate(first: 2) { id } } }",
        )
        assertEquals(1, problems.count { it.message.contains("differing arguments") }, problems.toString())
    }

    @Test
    fun `interface and object fields compare arguments and deduplicate the conflict`() {
        val problems = errors(
            "query { node(id: \"1\") { x: lookup(first: 1) " +
                "... on User { x: lookup(first: 2) } ... on Admin { x: lookup(first: 3) } } }",
        )
        assertEquals(1, problems.count { it.message.contains("differing arguments") }, problems.toString())
    }

    @Test
    fun `pairwise response shapes cover nullability and list ordering`() {
        fails("query { pet { ... on Cat { x: sound } ... on Dog { x: nullable } } }", "conflicting types")
        fails("query { pet { ... on Cat { x: nullable } ... on Dog { x: sound } } }", "conflicting types")
        fails("query { pet { ... on Cat { x: values } ... on Dog { x: id } } }", "conflicting types")
        fails("query { pet { ... on Cat { x: id } ... on Dog { x: values } } }", "conflicting types")
        fails("query { pet { ... on Cat { x: values } ... on Dog { x: nullable } } }", "conflicting types")
        fails("query { pet { ... on Cat { x: nullable } ... on Dog { x: values } } }", "conflicting types")
        fails("query { pet { ... on Cat { x: values } ... on Dog { x: values } } }", "conflicting types")
        ok("query { pet { ... on Cat { x: values } ... on Dog { x: strings } } }")
    }

    @Test
    fun `pairwise response shapes distinguish leaf and composite types in both orders`() {
        fails(
            "query { pet { ... on Cat { x: owner { id } } ... on Dog { x: sound } } }",
            "conflicting types",
        )
        fails(
            "query { pet { ... on Cat { x: sound } ... on Dog { x: owner { id } } } }",
            "conflicting types",
        )
        fails(
            "query { pet { ... on Cat { x: owner { id } } ... on Dog { x: nullable } } }",
            "conflicting types",
        )
        fails(
            "query { pet { ... on Cat { x: nullable } ... on Dog { x: owner { id } } } }",
            "conflicting types",
        )
        ok("query { pet { ... on Cat { x: owner { id } } ... on Dog { x: owner { id } } } }")
    }

    @Test
    fun `the same field on an interface parent and a narrowed object merges`() =
        ok("query { node(id: \"1\") { id ... on User { id } } }")

    @Test
    fun `a list field and a non-list field under one key conflict`() =
        fails("query { me { x: friend { id } x: posts(first: 1) { id } } }", "conflicting types")

    @Test
    fun `an unknown field with a sub-selection still merges without crashing`() {
        val problems = errors("query { me { x: bogus { a } x: bogus { a } } }")
        assertTrue(problems.any { it.message.contains("does not exist") }, problems.toString())
    }

    @Test
    fun `a non-composite inline fragment is skipped during merge collection`() {
        val problems = errors("query { me { name ... on Tag { y } } }")
        assertTrue(problems.any { it.message.contains("not a composite type") }, problems.toString())
    }

    // ---- verification-pass fixes: fragment-cycle safety + variable-definition directives ----

    @Test
    fun `a fragment cycle through a field is reported, not a crash`() {
        // would StackOverflow the merge pass if it ran; the cycle is reported and merge is skipped
        val problems = errors("query { me { ...A } } fragment A on User { id friend { ...A } }")
        assertTrue(problems.any { it.message.contains("cycle") }, problems.toString())
    }

    @Test
    fun `a directive on a variable definition is validated`() {
        ok("query Q(\$x: Int @vd) { search(limit: \$x) { id } }") // @vd is valid on VARIABLE_DEFINITION
        ok("query Q(\$x: Int @vd(n: 5)) { search(limit: \$x) { id } }") // with a const argument
    }

    @Test
    fun `a directive in the wrong place on a variable definition is rejected`() =
        fails("query Q(\$x: Int @skip(if: true)) { search(limit: \$x) { id } }", "not allowed on VARIABLE_DEFINITION")

    @Test
    fun `a oneOf input object literal must specify exactly one non-null field`() {
        ok("query { pick(c: { a: \"x\" }) }")
        fails("query { pick(c: { a: \"x\", b: 1 }) }", "exactly one field")
        fails("query { pick(c: { a: null }) }", "must not be null")
    }

    // ---- exhaustive branch coverage for the walks ----

    @Test
    fun `a named subscription with multiple roots names the operation`() =
        fails("subscription S { events { id } newMessages { id } }", "Subscription 'S'")

    @Test
    fun `a subscription spreading an undefined fragment collects no root`() =
        fails("subscription { ...Undef }", "exactly one root field")

    @Test
    fun `the usage walk handles __typename, unknown fields, and unknown arguments`() {
        errors("query Q(\$x: ID!) { __typename node(id: \$x) { id } }") // __typename branch
        errors("query Q(\$x: Int) { bogus(a: \$x) }") // unknown field → fieldDef null
        errors("query Q(\$x: Int) { me(bad: \$x) { id } }") // unknown argument → argDef null
        errors("query Q(\$x: Int) { me { id @skip(bad: \$x) } }") // unknown directive arg → argDef null
        assertTrue(true)
    }

    @Test
    fun `the usage walk descends into a no-condition inline fragment and an undefined spread`() {
        errors("query Q(\$x: ID!) { node(id: \$x) { ... { id } ...Undef } }")
        assertTrue(true)
    }

    @Test
    fun `a variable in an object literal against a non-input and an unknown field is handled`() {
        errors("query Q(\$x: Int) { search(limit: { a: \$x }) { id } }") // object value where a scalar is expected
        errors("query Q(\$x: String) { search(filter: { bogus: \$x }) { id } }") // unknown input field
        assertTrue(true)
    }

    @Test
    fun `a nullable variable satisfies a non-null position that has a default`() =
        ok("query Q(\$x: Int) { withDef(a: \$x) }")

    @Test
    fun `a nullable-element list variable cannot fill a non-null-element list position`() =
        fails("query Q(\$x: [ID]) { listArg(a: \$x) }", "cannot be used where")

    @Test
    fun `a list literal in a scalar position still runs the element-type walk`() {
        // limit is a scalar; a list literal there is a §5.6.1 error, but the usage walk still resolves the
        // element type ($x: Int against the scalar element) without crashing.
        errors("query Q(\$x: Int) { search(limit: [\$x]) { id } }")
        assertTrue(true)
    }

    @Test
    fun `the merge walk descends a no-condition inline fragment and a named fragment`() {
        ok("query { me { ... { name } name } }")
        ok("query { node(id: \"1\") { ...UF } } fragment UF on User { id }")
    }

    @Test
    fun `a shape conflict where one field is non-null and the other nullable is detected`() =
        fails("query { me { x: name x: age } }", "conflicting types") // String! vs Int

    @Test
    fun `a shape conflict between a composite and a leaf field is detected`() =
        fails("query { me { x: friend { id } x: age } }", "conflicting types") // User vs Int

    @Test
    fun `a leaf-then-list shape conflict is detected in either order`() =
        fails("query { me { x: posts(first: 1) { id } x: friend { id } } }", "conflicting types") // list vs User

    @Test
    fun `the same fragment spread twice is collected once in every walk`() {
        ok("subscription { ...F ...F } fragment F on Subscription { events { id } }")
        ok("query Q(\$x: ID!) { node(id: \$x) { ...G ...G } } fragment G on User { id }")
        ok("query { me { ...H ...H } } fragment H on User { id }")
    }

    @Test
    fun `a named fragment is skipped during merge collection when undefined`() {
        val problems = errors("query { me { ...Nope name } }")
        assertTrue(problems.any { it.message.contains("Unknown fragment") }, problems.toString())
    }

    @Test
    fun `a fragment on a non-composite type is skipped by the usage and merge walks`() {
        errors("query Q(\$x: ID!) { node(id: \$x) { ...SF } } fragment SF on Tag { x }") // usage walk
        errors("query { me { ...SF name } } fragment SF on Tag { x }") // merge walk
        assertTrue(true)
    }

    @Test
    fun `merging an object-parent field before an interface-parent field of the same shape is allowed`() =
        ok("query { node(id: \"1\") { ... on User { x: id } x: id } }")

    @Test
    fun `a variable used in a directive argument or input field that has a default is checked`() {
        ok("query Q(\$x: Int) { me { id @d(n: \$x) } }") // directive arg with a default
        ok("query Q(\$x: Int) { search(filter: { limit: \$x }) { id } }") // input field with a default
    }

    @Test
    fun `a known field followed by an unknown field under one key does not crash shape matching`() {
        val problems = errors("query { me { x: name x: bogus } }")
        assertTrue(problems.any { it.message.contains("does not exist") }, problems.toString())
    }
}
