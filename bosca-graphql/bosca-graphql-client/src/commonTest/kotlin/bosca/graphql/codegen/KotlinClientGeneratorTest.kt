package bosca.graphql.codegen

import bosca.graphql.parser.Parser
import bosca.graphql.schema.GraphQLSchema
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Asserts the generator emits the expected typed shape for a query against a schema. The matching fixture
 * `bosca.graphql.client.generated.GetUser` (compiled by the build, exercised by [bosca.graphql.client.RuntimeContractTest])
 * proves that shape compiles and round-trips; this test pins that the generator actually produces it.
 */
class KotlinClientGeneratorTest {

    private val schema = GraphQLSchema.fromSdl(
        """
        type Query { user(id: ID!): User }
        type User { id: ID! name: String! email: String }
        """.trimIndent(),
    )

    private fun generate(query: String): String =
        KotlinClientGenerator(schema).generate(Parser.parse(query), "gen")

    @Test
    fun `emits typed Data, nested object, Variables, document, and explicit codecs`() {
        val src = generate("query GetUser(\$id: ID!) { user(id: \$id) { id name email } }")

        assertTrue("package gen" in src, src)
        assertTrue("import bosca.graphql.client.BoscaOperation" in src, src)

        // Data shape with correct nullability derived from the schema
        assertTrue("data class GetUserData(" in src, src)
        assertTrue("val user: User?" in src, src)     // returns User (nullable)
        assertTrue("data class User(" in src, src)     // nested, per-selection class
        assertTrue("val id: String," in src, src)      // ID! -> non-null String
        assertTrue("val name: String," in src, src)    // String! -> non-null
        assertTrue("val email: String?," in src, src)  // String -> nullable

        // operation object + variables + explicit codecs
        assertTrue("object GetUser : BoscaOperation<GetUser.Variables, GetUserData>" in src, src)
        assertTrue("data class Variables(" in src, src)
        assertTrue("override val operationName: String = \"GetUser\"" in src, src)
        assertTrue("query GetUser(" in src && "{ id name email }" in src, src) // printed document
        assertTrue("decodeFromJsonElement(GetUserData.serializer(), data)" in src, src)
        assertTrue("encodeToJsonElement(Variables.serializer(), variables)" in src, src)
    }

    @Test
    fun `supports an operation with no variables`() {
        val schema2 = GraphQLSchema.fromSdl("type Query { now: String! }")
        val src = KotlinClientGenerator(schema2).generate(Parser.parse("query Now { now }"), "gen")
        assertTrue("object Now : BoscaOperation<Unit, NowData>" in src, src)
        assertTrue("override fun encodeVariables(variables: Unit): JsonObject = JsonObject(emptyMap())" in src, src)
    }

    @Test
    fun `rejects an unnamed query`() {
        assertFailsWith<IllegalStateException> { generate("{ user(id: \"1\") { id } }") }
    }

    @Test
    fun `a mutation against a schema with no mutation root is rejected`() {
        assertFailsWith<IllegalStateException> { generate("mutation M { user(id: \"1\") { id } }") }
    }

    // ---- enums, input objects, lists, custom scalars ----

    private val richSchema = GraphQLSchema.fromSdl(
        """
        type Query { search(filter: SearchFilter!): SearchResult }
        type SearchResult { id: ID! status: Status tags: [String!]! score: Long }
        enum Status { ACTIVE ARCHIVED }
        input SearchFilter { term: String! status: Status limit: Int }
        scalar Long
        """.trimIndent(),
    )

    @Test
    fun `generates enums, recursive input objects, lists, and mapped custom scalars`() {
        val generator = KotlinClientGenerator(richSchema, mapOf("Long" to ScalarMapping("Long")))
        val src = generator.generate(
            Parser.parse("query Search(\$filter: SearchFilter!) { search(filter: \$filter) { id status tags score } }"),
            "gen",
        )

        // enum: shared, top-level, @Serializable
        assertTrue("@Serializable\nenum class Status {" in src, src)
        assertTrue("    ACTIVE," in src && "    ARCHIVED," in src, src)

        // input object: required field vs nullable-default fields
        assertTrue("data class SearchFilter(" in src, src)
        assertTrue("    val term: String," in src, src)            // String! -> required (no default)
        assertTrue("    val status: Status? = null," in src, src)  // nullable -> default null
        assertTrue("    val limit: Int? = null," in src, src)

        // output field types: enum, non-null list, mapped custom scalar
        assertTrue("val status: Status?," in src, src)
        assertTrue("val tags: List<String>," in src, src)          // [String!]! -> non-null List<String>
        assertTrue("val score: Long?," in src, src)                // custom scalar Long -> kotlin.Long

        // variables reference the generated input object
        assertTrue("val filter: SearchFilter," in src, src)
    }

    @Test
    fun `fails fast on an unmapped custom scalar`() {
        val schema2 = GraphQLSchema.fromSdl("type Query { at: DateTime } scalar DateTime")
        assertFailsWith<IllegalStateException> {
            KotlinClientGenerator(schema2).generate(Parser.parse("query Q { at }"), "gen")
        }
    }

    @Test
    fun `renders SDL default values as Kotlin defaults (incl on non-null fields)`() {
        val defaultsSchema = GraphQLSchema.fromSdl(
            """
            type Query { search(filter: DefaultsInput!): Result }
            type Result { id: ID! }
            enum Color { RED GREEN BLUE }
            scalar Long
            input DefaultsInput {
                color: Color! = GREEN
                count: Int! = 5
                size: Long! = 0
                label: String! = "hi"
                active: Boolean! = true
                ratio: Float! = 1.5
                nick: String = null
                ids: [Int!]! = [1, 2]
                grid: [[Int!]!]! = [[1], [2, 3]]
            }
            """.trimIndent(),
        )
        val src = KotlinClientGenerator(defaultsSchema, mapOf("Long" to ScalarMapping("kotlin.Long")))
            .generate(Parser.parse("query Q(\$filter: DefaultsInput!) { search(filter: \$filter) { id } }"), "gen")

        assertTrue("val color: Color = Color.GREEN," in src, src)        // enum default → EnumType.VALUE (non-null)
        assertTrue("val count: Int = 5," in src, src)                    // Int literal
        assertTrue("val size: kotlin.Long = 0L," in src, src)            // Long gets the L suffix
        assertTrue("val label: String = \"hi\"," in src, src)            // quoted string
        assertTrue("val active: Boolean = true," in src, src)            // boolean
        assertTrue("val ratio: Double = 1.5," in src, src)               // float
        assertTrue("val nick: String? = null," in src, src)              // explicit null default
        assertTrue("val ids: List<Int> = listOf(1, 2)," in src, src)     // list
        assertTrue("val grid: List<List<Int>> = listOf(listOf(1), listOf(2, 3))," in src, src) // nested list (recursion)
    }

    @Test
    fun `fails fast on an unsupported object default value`() {
        val schema2 = GraphQLSchema.fromSdl(
            """
            type Query { ok(arg: ObjIn): Boolean }
            input ObjIn { a: Int }
            """.trimIndent(),
        )
        assertFailsWith<IllegalStateException> {
            KotlinClientGenerator(schema2).generate(Parser.parse("query Q(\$o: ObjIn = {}) { ok(arg: \$o) }"), "gen")
        }
    }

    // ---- fragments + aliases ----

    @Test
    fun `aliases name properties and a named fragment is flattened into the consuming type`() {
        val src = generate(
            "query GetAccount(\$id: ID!) { account: user(id: \$id) { ...UserFields contact: email } }\n" +
                "fragment UserFields on User { id name }",
        )
        // alias on the object field -> property + nested class named by the alias
        assertTrue("val account: Account?" in src, src)
        assertTrue("data class Account(" in src, src)
        // fragment fields flattened in, plus the aliased leaf
        assertTrue("val id: String," in src, src)
        assertTrue("val name: String," in src, src)
        assertTrue("val contact: String?," in src, src) // alias of email
        // the document carries the operation AND the fragment definition
        assertTrue("...UserFields" in src, src)
        assertTrue("fragment UserFields on User { id name }" in src, src)
    }

    @Test
    fun `a non-narrowing inline fragment is flattened`() {
        val src = generate("query GetUser(\$id: ID!) { user(id: \$id) { id ... on User { name email } } }")
        assertTrue("val name: String," in src, src)
        assertTrue("val email: String?," in src, src)
    }

    @Test
    fun `a fragment on an implemented interface is flattened (widening)`() {
        val s = GraphQLSchema.fromSdl(
            """
            type Query { user: User }
            interface Node { id: ID! }
            type User implements Node { id: ID! name: String! }
            """.trimIndent(),
        )
        val src = KotlinClientGenerator(s).generate(
            Parser.parse("query Q { user { ...NodeFields name } }\nfragment NodeFields on Node { id }"),
            "gen",
        )
        assertTrue("val id: String," in src, src)
        assertTrue("val name: String," in src, src)
    }

    @Test
    fun `a narrowing inline fragment now generates a sealed type`() {
        val s = GraphQLSchema.fromSdl(
            """
            type Query { node: Node }
            interface Node { id: ID! }
            type User implements Node { id: ID! name: String! }
            """.trimIndent(),
        )
        val src = KotlinClientGenerator(s).generate(Parser.parse("query Q { node { id ... on User { name } } }"), "gen")
        assertTrue("sealed interface Node {" in src, src)
    }

    @Test
    fun `an unknown fragment spread fails fast`() {
        assertFailsWith<IllegalStateException> {
            generate("query GetUser(\$id: ID!) { user(id: \$id) { ...Missing } }")
        }
    }

    @Test
    fun `emits a file-level UseSerializers header and import for a custom scalar with a serializer`() {
        val schema2 = GraphQLSchema.fromSdl("type Query { at: DateTime stamps: [DateTime!] } scalar DateTime")
        val generator = KotlinClientGenerator(
            schema2,
            mapOf(
                "DateTime" to ScalarMapping(
                    kotlinType = "Instant",
                    imports = setOf("kotlinx.datetime.Instant"),
                    serializerWith = "kotlinx.datetime.serializers.InstantIso8601Serializer",
                ),
            ),
        )
        val src = generator.generate(Parser.parse("query Q { at stamps }"), "gen")
        assertTrue("import kotlinx.datetime.Instant" in src, src)
        // file-level header applies the serializer everywhere — including inside List<…> (the `stamps` field)
        assertTrue(src.startsWith("@file:kotlinx.serialization.UseSerializers(kotlinx.datetime.serializers.InstantIso8601Serializer::class)"), src)
        assertTrue("val at: Instant?" in src, src) // no per-property annotation
        assertTrue("val stamps: List<Instant>?" in src, src) // custom scalar inside a list now works
    }

    // ---- unions / interfaces → sealed hierarchies ----

    private val polymorphicSchema = GraphQLSchema.fromSdl(
        """
        type Query { node: Node result: SearchResult nodes: [Node!]! }
        interface Node { id: ID! }
        union SearchResult = User | Post
        type User implements Node { id: ID! name: String! }
        type Post implements Node { id: ID! title: String! }
        """.trimIndent(),
    )

    @Test
    fun `an interface selection generates a sealed hierarchy with hoisted common fields and an Other branch`() {
        val src = KotlinClientGenerator(polymorphicSchema).generate(
            Parser.parse("query GetNode { node { id ... on User { name } ... on Post { title } } }"),
            "gen",
        )
        assertTrue("sealed interface Node {" in src, src)
        assertTrue("val id: String" in src, src) // common interface field hoisted (abstract)
        assertTrue("data class User(" in src && "override val id: String," in src && "val name: String," in src, src)
        assertTrue("data class Post(" in src && "val title: String," in src, src)
        assertTrue("data class Other(" in src, src) // forward-compatible fallback
        // the serializer is declared on the sealed type, not the property, so it also works inside List<…>
        assertTrue("val node: Node?" in src, src)
        assertTrue("@Serializable(with = GetNodeDataNodeSerializer::class) val node" !in src, src)
        assertTrue("@Serializable(with = GetNodeDataNodeSerializer::class)" in src, src)
        // explicit __typename-dispatch serializer + auto-injected __typename in the document
        assertTrue("object GetNodeDataNodeSerializer : KSerializer<GetNodeData.Node>" in src, src)
        assertTrue("\"User\" -> GraphQLJson.decodeFromJsonElement(GetNodeData.Node.User.serializer(), element)" in src, src)
        assertTrue("node { __typename id" in src, src)
    }

    @Test
    fun `a list of a polymorphic type generates List of the sealed type without a per-property serializer`() {
        val src = KotlinClientGenerator(polymorphicSchema).generate(
            Parser.parse("query GetNodes { nodes { id ... on User { name } } }"),
            "gen",
        )
        assertTrue("val nodes: List<Nodes>" in src, src) // list element is the generated sealed type
        assertTrue("sealed interface Nodes {" in src, src)
        assertTrue("@Serializable(with = GetNodesDataNodesSerializer::class)" in src, src) // serializer on the type
        assertTrue("::class) val nodes" !in src, src) // NOT a per-property annotation (would break inside List<>)
    }

    @Test
    fun `a union selection generates a sealed hierarchy with an Other object`() {
        val src = KotlinClientGenerator(polymorphicSchema).generate(
            Parser.parse("query Find { result { ... on User { id name } ... on Post { id title } } }"),
            "gen",
        )
        assertTrue("sealed interface Result {" in src, src)
        assertTrue("data class User(" in src && "data class Post(" in src, src)
        assertTrue("object Other : Result" in src, src) // union has no common fields → object
        assertTrue("object FindDataResultSerializer : KSerializer<FindData.Result>" in src, src)
    }
}
