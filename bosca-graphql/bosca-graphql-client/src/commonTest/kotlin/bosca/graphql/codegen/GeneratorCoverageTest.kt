package bosca.graphql.codegen

import bosca.graphql.parser.Parser
import bosca.graphql.schema.GraphQLSchema
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Drives every reachable generator path: applying vs narrowing fragments, unions, object variables, list scalars. */
class GeneratorCoverageTest {

    private val schema = GraphQLSchema.fromSdl(
        """
        scalar DateTime
        type Query {
          me: User
          node(id: ID!): Node
          pet: Pet
          nnTags: [String!]!
          now: DateTime
          dates: [DateTime]
        }
        type User implements Node { id: ID! name: String! related: Related }
        type Org implements Node { id: ID! title: String! related: Related }
        interface Node { id: ID! related: Related }
        type Related { x: String! }
        union Pet = Cat | Dog
        type Cat { id: ID! meow: String! }
        type Dog { id: ID! bark: String! }
        enum Color { RED GREEN }
        input Filter { term: String color: Color }
        """.trimIndent(),
    )

    private fun ts(query: String) = TypeScriptClientGenerator(
        schema,
        mapOf("DateTime" to TsScalarMapping("string", importName = "DateTime", importFrom = "./scalars")),
    ).generate(Parser.parse(query))

    private fun kt(query: String) = KotlinClientGenerator(
        schema,
        mapOf("DateTime" to ScalarMapping("kotlinx.datetime.Instant", imports = setOf("kotlinx.datetime.Instant"), serializerWith = "InstantSerializer")),
    ).generate(Parser.parse(query), "p")

    @Test
    fun `a concrete type with an applying inline fragment flattens`() {
        val q = "query Q { me { id ... on User { name } } }"
        assertTrue("name" in ts(q), ts(q))
        assertTrue("name" in kt(q), kt(q))
    }

    @Test
    fun `a concrete type with an applying named fragment flattens`() {
        val q = "query Q { me { id ...UserFields } } fragment UserFields on User { name }"
        assertTrue("name" in ts(q), ts(q))
        assertTrue("name" in kt(q), kt(q))
    }

    @Test
    fun `an interface selection mixes an applying fragment with inline and named narrowing fragments`() {
        val q = """
            query Q { node(id: "1") { ... on Node { id } ... on User { name } ...OrgFields } }
            fragment OrgFields on Org { title }
        """.trimIndent()
        val ts = ts(q)
        assertTrue("User" in ts && "Org" in ts, ts) // narrowing branches
        val kt = kt(q)
        assertTrue("sealed" in kt && "User" in kt && "Org" in kt, kt)
    }

    @Test
    fun `a union selection generates branches`() {
        val q = "query Q { pet { ... on Cat { meow } ... on Dog { bark } } }"
        assertTrue("Cat" in ts(q) && "Dog" in ts(q), ts(q))
        assertTrue("sealed" in kt(q), kt(q))
    }

    @Test
    fun `an input + enum variable is emitted`() {
        val q = "query Q(\$f: Filter) { me { id } }"
        assertTrue("interface Filter" in ts(q) && "Color" in ts(q), ts(q))
        assertTrue("data class Filter" in kt(q) && "enum class Color" in kt(q), kt(q))
    }

    @Test
    fun `an object-typed variable is rejected by both generators`() {
        val q = "query Q(\$x: User) { me { id } }"
        assertFailsWith<IllegalStateException> { ts(q) }
        assertFailsWith<IllegalStateException> { kt(q) }
    }

    @Test
    fun `a non-null list return type renders`() {
        assertTrue("Array<string>" in ts("query Q { nnTags }"), ts("query Q { nnTags }"))
        assertTrue("List<String>" in kt("query Q { nnTags }"), kt("query Q { nnTags }"))
    }

    @Test
    fun `a custom scalar generates as a value and inside a list`() {
        assertTrue("Instant" in kt("query Q { now }"), kt("query Q { now }")) // non-list custom scalar
        assertTrue("val dates: List<" in kt("query Q { dates }"), kt("query Q { dates }")) // list of custom scalars (via @file:UseSerializers)
    }

    @Test
    fun `a polymorphic selection with an object-typed common field is a follow-on in Kotlin`() {
        // `related` (object) is common across the interface while `... on User` narrows → not yet supported
        val q = "query Q { node(id: \"1\") { related { x } ... on User { name } } }"
        assertFailsWith<IllegalArgumentException> { kt(q) }
    }
}
