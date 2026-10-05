package bosca.graphql.codegen

import bosca.graphql.parser.Parser
import bosca.graphql.schema.GraphQLSchema
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Targets the last reachable generator branches: `__typename` skip, multi-nested formatting, response-name dedup,
 * inline fragments with no type condition, undefined fragments in polymorphic selections, scalar imports that are
 * absent, and a non-null custom scalar (the `typeHasList` NonNull recursion).
 */
class GeneratorBranchTest {

    private val schema = GraphQLSchema.fromSdl(
        """
        scalar DateTime
        type Query {
          me: User
          node(id: ID!): Node
          now2: DateTime!
        }
        type User implements Node { id: ID! name: String! related: Related other: Related }
        type Org implements Node { id: ID! title: String! }
        interface Node { id: ID! }
        type Related { x: String! }
        """.trimIndent(),
    )

    private val tsScalars = mapOf("DateTime" to TsScalarMapping("string", importName = "DateTime", importFrom = "./scalars"))
    private val ktScalars = mapOf("DateTime" to ScalarMapping("kotlinx.datetime.Instant", imports = setOf("kotlinx.datetime.Instant"), serializerWith = "InstantSerializer"))

    private fun ts(query: String, scalars: Map<String, TsScalarMapping> = tsScalars) =
        TypeScriptClientGenerator(schema, scalars).generate(Parser.parse(query))

    private fun kt(query: String) = KotlinClientGenerator(schema, ktScalars).generate(Parser.parse(query), "p")

    @Test
    fun `__typename in an object selection is dropped from the generated type`() {
        // __typename appears in the embedded query document literal, so assert it is not declared as a *property*.
        val q = "query Q { me { __typename id name } }"
        assertTrue(Regex("\\b__typename\\s*:").findAll(ts(q)).none(), ts(q)) // no TS field
        assertTrue(Regex("val\\s+__typename").findAll(kt(q)).none(), kt(q)) // Kotlin L185 skip — no property
    }

    @Test
    fun `a selection with two nested object fields formats both`() {
        val q = "query Q { me { related { x } other { x } } }"
        val kt = kt(q)
        assertTrue("data class Related" in kt && "data class Other" in kt, kt) // Kotlin L222 blank-line-between-nested
        val ts = ts(q)
        assertTrue("QDataMeRelated" in ts && "QDataMeOther" in ts, ts)
    }

    @Test
    fun `a field repeated by response name is emitted once`() {
        val q = "query Q { me { id id name } }"
        assertTrue(ts(q).count { it == '\n' } > 0 && Regex("\\bid:").findAll(ts(q)).count() == 1, ts(q)) // TS L230 dedup
        assertTrue(Regex("\\bval id:").findAll(kt(q)).count() == 1, kt(q)) // Kotlin L353 dedup
    }

    @Test
    fun `an inline fragment with no type condition is treated as applying`() {
        val q = "query Q { me { id ... { name } } }"
        assertTrue("name" in ts(q), ts(q)) // TS L212 + L253 condition == null
        assertTrue("name" in kt(q), kt(q)) // Kotlin L363 + L376 condition == null
    }

    @Test
    fun `an undefined fragment in a polymorphic selection is rejected`() {
        val q = "query Q { node(id: \"1\") { ...Nope ... on User { name } } }"
        assertFailsWith<IllegalStateException> { ts(q) } // TS L204
        assertFailsWith<IllegalStateException> { kt(q) } // Kotlin analyzeSelection
    }

    @Test
    fun `an undefined fragment in an object selection is rejected`() {
        val q = "query Q { me { ...Nope } }"
        assertFailsWith<IllegalStateException> { ts(q) }
        assertFailsWith<IllegalStateException> { kt(q) }
    }

    @Test
    fun `a scalar mapping without an import emits no import line`() {
        val q = "query Q { now2 }"
        val generated = ts(q, mapOf("DateTime" to TsScalarMapping("string"))) // no importName/importFrom → TS L288 &&
        assertTrue("now2: string" in generated, generated)
        assertTrue("import { DateTime }" !in generated, generated) // no scalar import was added
    }

    @Test
    fun `a non-null custom scalar binds its serializer`() {
        val q = "query Q { now2 }"
        assertTrue("InstantSerializer" in kt(q), kt(q)) // Kotlin L522 typeHasList(NonNull) recursion → not a list → ok
        assertTrue("DateTime" in ts(q), ts(q))
    }

    @Test
    fun `a mutation against a schema without a mutation root is rejected by the TypeScript generator`() {
        // the schema (validly) has a query root but no mutation root → rootType(MUTATION) is null
        assertFailsWith<IllegalStateException> { ts("mutation M { unused }") } // TS L70
    }

    @Test
    fun `an aliased common field on a polymorphic selection uses its alias`() {
        // `tag: id` is a common (interface) field with an alias → Kotlin L236/L246 `alias ?: name` alias branch
        val q = "query Q { node(id: \"1\") { tag: id ... on User { name } ... on Org { title } } }"
        val kt = kt(q)
        assertTrue("val tag:" in kt, kt)
    }

    @Test
    fun `a scalar mapping with an import name but no import-from adds no import`() {
        // importName != null but importFrom == null → TS L288 && right operand false
        val q = "query Q { now2 }"
        val generated = ts(q, mapOf("DateTime" to TsScalarMapping("string", importName = "DateTime")))
        assertTrue("now2: string" in generated, generated)
        assertTrue("import { DateTime }" !in generated, generated)
    }

    // ---- nested fragments reach the flatten-time guards (analyzeSelection only resolves the top level) ----

    @Test
    fun `a nested narrowing fragment spread is rejected while flattening`() {
        // Outer applies to User (so analyzeSelection keeps it common); flattening recurses into it and finds Inner,
        // which narrows to Org → the flatten-time require fires for both generators.
        val q = "query Q { me { ...Outer } } fragment Outer on User { id ...Inner } fragment Inner on Org { title }"
        assertFailsWith<IllegalArgumentException> { ts(q) } // TS require in flattenFields (fragment spread)
        assertFailsWith<IllegalArgumentException> { kt(q) } // Kotlin require in flattenFields (fragment spread)
    }

    @Test
    fun `a nested narrowing inline fragment is rejected while flattening`() {
        val q = "query Q { me { ...Outer } } fragment Outer on User { id ... on Org { title } }"
        assertFailsWith<IllegalArgumentException> { ts(q) } // TS require in flattenFields (inline fragment)
        assertFailsWith<IllegalArgumentException> { kt(q) } // Kotlin require in flattenFields (inline fragment)
    }

    @Test
    fun `a nested undefined fragment spread is rejected while flattening`() {
        val q = "query Q { me { ...Outer } } fragment Outer on User { id ...Ghost }"
        assertFailsWith<IllegalStateException> { ts(q) } // TS fragments[...] ?: error in flattenFields
        assertFailsWith<IllegalStateException> { kt(q) } // Kotlin fragments[...] ?: error in flattenFields
    }

    @Test
    fun `a common field absent from the interface is rejected`() {
        // `ghost` is selected directly on the Node interface (so it is a common field) but Node has no such field.
        val q = "query Q { node(id: \"1\") { ghost ... on User { name } } }"
        assertFailsWith<IllegalStateException> { kt(q) } // Kotlin commonVals schema.field(interface, ...) ?: error
    }
}
