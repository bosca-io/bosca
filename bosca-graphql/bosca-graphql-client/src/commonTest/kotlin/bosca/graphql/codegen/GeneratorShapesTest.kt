package bosca.graphql.codegen

import bosca.graphql.parser.Parser
import bosca.graphql.schema.GraphQLSchema
import kotlin.test.Test
import kotlin.test.assertTrue

/** Drives every type shape through both generators: nullable vars, lists, input objects, enums, custom scalars, polymorphism. */
class GeneratorShapesTest {

    private val schema = GraphQLSchema.fromSdl(
        """
        scalar DateTime
        type Query {
          search(filter: Filter, first: Int): SearchResult
          node(id: ID!): Node
          pet: Pet
          tags: [String!]!
          maybe: [Int]
        }
        type SearchResult { id: ID! when: DateTime tags: [String!] }
        interface Node { id: ID! }
        type User implements Node { id: ID! name: String! }
        type Org implements Node { id: ID! title: String! }
        union Pet = Cat | Dog
        type Cat { id: ID! meow: String! }
        type Dog { id: ID! bark: String! }
        enum Color { RED GREEN }
        input Inner { x: Int }
        input Filter { term: String color: Color limit: Int = 10 nested: Inner }
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
    fun `nullable + input + enum + custom-scalar + list shapes generate`() {
        val q = "query Q(\$filter: Filter, \$first: Int) { search(filter: \$filter, first: \$first) { id when tags } }"
        val ts = ts(q)
        assertTrue("interface Filter" in ts && "Color" in ts, ts) // input object + nested enum emitted
        assertTrue("first?:" in ts || "first?" in ts, ts) // nullable variable → optional
        assertTrue("""from "./scalars"""" in ts, ts) // custom-scalar import line
        val kt = kt(q)
        assertTrue("data class Filter" in kt && "enum class Color" in kt, kt)
        assertTrue("= null" in kt, kt) // nullable variable → default null
        assertTrue("Instant" in kt, kt) // custom scalar mapped type
    }

    @Test
    fun `list and non-null-list return types generate`() {
        val q = "query Lists { tags maybe }"
        assertTrue("Array<string>" in ts(q), ts(q)) // [String!]!
        assertTrue("List<" in kt(q), kt(q))
    }

    @Test
    fun `an interface selection with inline fragments generates polymorphic output`() {
        val q = "query N(\$id: ID!) { node(id: \$id) { id ... on User { name } ... on Org { title } } }"
        val ts = ts(q)
        assertTrue("__typename" in ts, ts) // discriminator injected
        assertTrue("User" in ts && "Org" in ts, ts)
        val kt = kt(q)
        assertTrue("sealed" in kt, kt) // sealed hierarchy for the interface
        assertTrue("User" in kt && "Org" in kt, kt)
    }

    @Test
    fun `a union selection with inline fragments generates polymorphic output`() {
        val q = "query P { pet { ... on Cat { meow } ... on Dog { bark } } }"
        val ts = ts(q)
        assertTrue("Cat" in ts && "Dog" in ts, ts)
        val kt = kt(q)
        assertTrue("sealed" in kt && "Cat" in kt && "Dog" in kt, kt)
    }
}
