package bosca.graphql.codegen

import bosca.graphql.client.GraphQLJson
import bosca.graphql.language.EnumTypeDefinition
import bosca.graphql.language.InputObjectTypeDefinition
import bosca.graphql.language.InterfaceTypeDefinition
import bosca.graphql.language.ObjectTypeDefinition
import bosca.graphql.language.UnionTypeDefinition
import bosca.graphql.schema.GraphQLSchema
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies the introspection → SDL printer: the emitted SDL contains the expected declarations, drops the
 * built-in scalars / `__*` meta-types, and — the real contract — parses back through our own
 * [GraphQLSchema.fromSdl] and drives [GraphQLCodegen]. That round-trip proves a refreshed `schema.graphqls`
 * is usable offline by the generator.
 */
class IntrospectionTest {

    // A hand-built introspection result covering every type kind, list/non-null wrappers, an arg, and a default.
    private val result = GraphQLJson.parseToJsonElement(
        """
        {"data":{"__schema":{
          "queryType":{"name":"Query"},"mutationType":null,"subscriptionType":null,
          "types":[
            {"kind":"OBJECT","name":"Query","interfaces":[],"fields":[
              {"name":"user","args":[{"name":"id","type":{"kind":"NON_NULL","name":null,"ofType":{"kind":"SCALAR","name":"ID","ofType":null}},"defaultValue":null}],"type":{"kind":"OBJECT","name":"User","ofType":null}},
              {"name":"pet","args":[],"type":{"kind":"UNION","name":"Pet","ofType":null}}
            ]},
            {"kind":"INTERFACE","name":"Node","interfaces":[],"fields":[
              {"name":"id","args":[],"type":{"kind":"NON_NULL","name":null,"ofType":{"kind":"SCALAR","name":"ID","ofType":null}}}
            ],"possibleTypes":[{"kind":"OBJECT","name":"User","ofType":null}]},
            {"kind":"OBJECT","name":"User","interfaces":[{"kind":"INTERFACE","name":"Node","ofType":null}],"fields":[
              {"name":"id","args":[],"type":{"kind":"NON_NULL","name":null,"ofType":{"kind":"SCALAR","name":"ID","ofType":null}}},
              {"name":"name","args":[],"type":{"kind":"NON_NULL","name":null,"ofType":{"kind":"SCALAR","name":"String","ofType":null}}},
              {"name":"tags","args":[],"type":{"kind":"LIST","name":null,"ofType":{"kind":"NON_NULL","name":null,"ofType":{"kind":"SCALAR","name":"String","ofType":null}}}},
              {"name":"color","args":[],"type":{"kind":"ENUM","name":"Color","ofType":null}}
            ]},
            {"kind":"UNION","name":"Pet","possibleTypes":[{"kind":"OBJECT","name":"User","ofType":null}]},
            {"kind":"ENUM","name":"Color","enumValues":[{"name":"RED"},{"name":"GREEN"}]},
            {"kind":"INPUT_OBJECT","name":"Filter","inputFields":[
              {"name":"term","type":{"kind":"NON_NULL","name":null,"ofType":{"kind":"SCALAR","name":"String","ofType":null}},"defaultValue":null},
              {"name":"limit","type":{"kind":"SCALAR","name":"Int","ofType":null},"defaultValue":"10"}
            ]},
            {"kind":"SCALAR","name":"DateTime","ofType":null},
            {"kind":"SCALAR","name":"String","ofType":null},
            {"kind":"OBJECT","name":"__Type","fields":[]}
          ]
        }}}
        """.trimIndent(),
    )

    @Test
    fun `prints SDL for every type kind and drops built-ins and meta-types`() {
        val sdl = Introspection.toSdl(result)

        assertTrue("schema {\n  query: Query\n}" in sdl, sdl)
        assertTrue("type Query {" in sdl && "user(id: ID!): User" in sdl && "pet: Pet" in sdl, sdl)
        assertTrue("interface Node {" in sdl && "id: ID!" in sdl, sdl)
        assertTrue("type User implements Node {" in sdl, sdl)
        assertTrue("tags: [String!]" in sdl, sdl)        // LIST of NON_NULL String
        assertTrue("color: Color" in sdl, sdl)
        assertTrue("union Pet = User" in sdl, sdl)
        assertTrue("enum Color {" in sdl && "  RED" in sdl && "  GREEN" in sdl, sdl)
        assertTrue("input Filter {" in sdl, sdl)
        assertTrue("term: String!" in sdl, sdl)
        assertTrue("limit: Int = 10" in sdl, sdl)        // default value preserved
        assertTrue("scalar DateTime" in sdl, sdl)
        assertTrue("scalar String" !in sdl, sdl)         // built-in scalar dropped
        assertTrue("__Type" !in sdl, sdl)                // introspection meta-type dropped
    }

    @Test
    fun `the printed SDL parses back through our own schema builder`() {
        val schema = GraphQLSchema.fromSdl(Introspection.toSdl(result))
        assertIs<ObjectTypeDefinition>(schema.type("User"))
        assertIs<InterfaceTypeDefinition>(schema.type("Node"))
        assertIs<UnionTypeDefinition>(schema.type("Pet"))
        assertIs<EnumTypeDefinition>(schema.type("Color"))
        assertIs<InputObjectTypeDefinition>(schema.type("Filter"))
    }

    @Test
    fun `a refreshed schema drives the generator end-to-end`() {
        val files = GraphQLCodegen(Introspection.toSdl(result)).generate(
            listOf("query GetUser(\$id: ID!) { user(id: \$id) { id name tags color } }"),
            "gen",
        )
        val operation = files.single { it.name == "GetUser.kt" }.content
        val colorEnum = files.single { it.name == "Color.kt" }.content // enum hoisted to its own file
        assertTrue("val tags: List<String>?," in operation, operation) // [String!] -> nullable list of non-null
        assertTrue("val color: Color?," in operation, operation)
        assertTrue("enum class Color {" in colorEnum, colorEnum)
    }

    @Test
    fun `an error response without a schema fails fast rather than emitting empty SDL`() {
        val errors = GraphQLJson.parseToJsonElement("""{"errors":[{"message":"unauthorized"}]}""")
        assertFailsWith<IllegalStateException> { Introspection.toSdl(errors) }
    }
}
