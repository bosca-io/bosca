package bosca.graphql.codegen

import bosca.graphql.parser.Parser
import bosca.graphql.schema.GraphQLSchema
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Asserts the TypeScript generator emits the expected typed shape for an operation — the BML-island emit target.
 * A real `tsc --noEmit` (strict) check over a verbatim sample + a `@bosca/bml` vitest test prove
 * the emitted shape type-checks and runs; this pins that the generator produces it.
 */
class TypeScriptClientGeneratorTest {

    private val schema = GraphQLSchema.fromSdl(
        """
        type Query { user(id: ID!): User }
        type Mutation { createUser(name: String!): User }
        type User { id: ID! name: String! email: String }
        """.trimIndent(),
    )

    private fun generate(query: String): String =
        TypeScriptClientGenerator(schema).generate(Parser.parse(query))

    @Test
    fun `emits typed interfaces, the operation const, and a typed query function over the bosca transport`() {
        val ts = generate("query GetUser(\$id: ID!) { user(id: \$id) { id name email } }")

        assertTrue("""import { bosca } from "@bosca/bml"""" in ts, ts)
        assertTrue("export interface GetUserVariables {\n  id: string\n}" in ts, ts)
        assertTrue("export interface GetUserData {\n  user: GetUserDataUser | null\n}" in ts, ts)
        // nested object as a named interface with nullability from the schema
        assertTrue("export interface GetUserDataUser {" in ts, ts)
        assertTrue("  id: string" in ts && "  name: string" in ts && "  email: string | null" in ts, ts)
        // operation const + printed document
        assertTrue("""export const GetUser = {""" in ts, ts)
        assertTrue("""  operationName: "GetUser" as const,""" in ts, ts)
        assertTrue("query GetUser(\$id: ID!) { user(id: \$id) { id name email } }" in ts, ts)
        // typed, end-to-end query call site (no unknown)
        assertTrue("export function getUser(variables: GetUserVariables): Promise<GetUserData> {" in ts, ts)
        assertTrue("return bosca.query<GetUserData, GetUserVariables>({ query: GetUser.query, operationName: GetUser.operationName, variables })" in ts, ts)
    }

    @Test
    fun `an operation with no variables emits a no-arg function`() {
        val schema2 = GraphQLSchema.fromSdl("type Query { now: String! }")
        val ts = TypeScriptClientGenerator(schema2).generate(Parser.parse("query Now { now }"))
        assertTrue("export function now(): Promise<NowData> {" in ts, ts)
        assertTrue("return bosca.query<NowData>({ query: Now.query, operationName: Now.operationName })" in ts, ts)
        assertTrue("Variables" !in ts, ts)
    }

    @Test
    fun `a mutation rides the mutate transport`() {
        val ts = generate("mutation CreateUser(\$name: String!) { createUser(name: \$name) { id } }")
        assertTrue("export function createUser(variables: CreateUserVariables): Promise<CreateUserData> {" in ts, ts)
        assertTrue("return bosca.mutate<CreateUserData, CreateUserVariables>(" in ts, ts)
    }

    @Test
    fun `a subscription fails fast`() {
        val schema2 = GraphQLSchema.fromSdl("type Query { a: String } type Subscription { ticks: Int! }")
        assertFailsWith<IllegalArgumentException> {
            TypeScriptClientGenerator(schema2).generate(Parser.parse("subscription S { ticks }"))
        }
    }

    // ---- enums, inputs, lists, custom scalars ----

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
    fun `emits string-literal-union enums, input interfaces, lists, and mapped custom scalars`() {
        val ts = TypeScriptClientGenerator(richSchema, mapOf("Long" to TsScalarMapping("number"))).generate(
            Parser.parse("query Search(\$filter: SearchFilter!) { search(filter: \$filter) { id status tags score } }"),
        )

        assertTrue("""export type Status = "ACTIVE" | "ARCHIVED"""" in ts, ts)
        // input object: required field vs optional (nullable) fields
        assertTrue("export interface SearchFilter {" in ts, ts)
        assertTrue("  term: string" in ts, ts)
        assertTrue("  status?: Status | null" in ts, ts)
        assertTrue("  limit?: number | null" in ts, ts)
        // output field types: enum, non-null list of non-null, mapped custom scalar
        assertTrue("  status: Status | null" in ts, ts)
        assertTrue("  tags: Array<string>" in ts, ts)   // [String!]! -> Array<string> (no | null)
        assertTrue("  score: number | null" in ts, ts)  // custom scalar Long -> number
        // variables reference the input interface (non-null -> required)
        assertTrue("  filter: SearchFilter" in ts, ts)
    }

    @Test
    fun `a mapped custom scalar can add a named import`() {
        val schema2 = GraphQLSchema.fromSdl("type Query { at: DateTime } scalar DateTime")
        val ts = TypeScriptClientGenerator(
            schema2,
            mapOf("DateTime" to TsScalarMapping("Instant", importName = "Instant", importFrom = "@js-joda/core")),
        ).generate(Parser.parse("query Q { at }"))
        assertTrue("""import { Instant } from "@js-joda/core"""" in ts, ts)
        assertTrue("  at: Instant | null" in ts, ts)
    }

    @Test
    fun `fails fast on an unmapped custom scalar`() {
        val schema2 = GraphQLSchema.fromSdl("type Query { at: DateTime } scalar DateTime")
        assertFailsWith<IllegalStateException> {
            TypeScriptClientGenerator(schema2).generate(Parser.parse("query Q { at }"))
        }
    }

    // ---- fragments + aliases ----

    @Test
    fun `aliases name properties and a named fragment is flattened into the consuming interface`() {
        val ts = generate(
            "query GetAccount(\$id: ID!) { account: user(id: \$id) { ...UserFields contact: email } }\n" +
                "fragment UserFields on User { id name }",
        )
        assertTrue("export interface GetAccountData {\n  account: GetAccountDataAccount | null\n}" in ts, ts)
        assertTrue("export interface GetAccountDataAccount {" in ts, ts)
        assertTrue("  id: string" in ts && "  name: string" in ts && "  contact: string | null" in ts, ts)
        assertTrue("fragment UserFields on User { id name }" in ts, ts) // carried in the document
    }

    // ---- unions / interfaces → discriminated unions ----

    private val polymorphicSchema = GraphQLSchema.fromSdl(
        """
        type Query { node: Node result: SearchResult }
        interface Node { id: ID! }
        union SearchResult = User | Post
        type User implements Node { id: ID! name: String! }
        type Post implements Node { id: ID! title: String! }
        """.trimIndent(),
    )

    @Test
    fun `an interface selection becomes a discriminated union with the common field repeated per member`() {
        val ts = TypeScriptClientGenerator(polymorphicSchema).generate(
            Parser.parse("query GetNode { node { id ... on User { name } ... on Post { title } } }"),
        )
        assertTrue("export type GetNodeDataNode =\n  | GetNodeDataNodeUser\n  | GetNodeDataNodePost" in ts, ts)
        assertTrue("""export interface GetNodeDataNodeUser {
  __typename: "User"
  id: string
  name: string
}""" in ts, ts)
        assertTrue("""export interface GetNodeDataNodePost {
  __typename: "Post"
  id: string
  title: string
}""" in ts, ts)
        assertTrue("  node: GetNodeDataNode | null" in ts, ts)
        assertTrue("node { __typename id" in ts, ts) // __typename auto-injected
    }

    @Test
    fun `a union selection becomes a discriminated union`() {
        val ts = TypeScriptClientGenerator(polymorphicSchema).generate(
            Parser.parse("query Find { result { ... on User { id name } ... on Post { id title } } }"),
        )
        assertTrue("export type FindDataResult =\n  | FindDataResultUser\n  | FindDataResultPost" in ts, ts)
        assertTrue("""  __typename: "User"""" in ts && """  __typename: "Post"""" in ts, ts)
        assertTrue("  result: FindDataResult | null" in ts, ts)
    }
}
