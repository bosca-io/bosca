package bosca.graphql.codegen

import bosca.graphql.client.GraphQLJson
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Edge cases of the introspection → SDL printer: envelope shapes, all roots, missing/defaulted fields. */
class IntrospectionEdgeTest {

    private fun toSdl(json: String) = Introspection.toSdl(GraphQLJson.parseToJsonElement(json))

    @Test
    fun `accepts a bare __schema envelope and a bare types envelope`() {
        val types = """[{"kind":"OBJECT","name":"Query","interfaces":[],"fields":[{"name":"a","args":[],"type":{"kind":"SCALAR","name":"String","ofType":null}}]}]"""
        // bare {"__schema": {...}} (no "data" wrapper)
        assertTrue("type Query {" in toSdl("""{"__schema":{"queryType":{"name":"Query"},"mutationType":null,"subscriptionType":null,"types":$types}}"""))
        // bare {"types": [...], "queryType": ...} (the schema object itself)
        assertTrue("type Query {" in toSdl("""{"queryType":{"name":"Query"},"mutationType":null,"subscriptionType":null,"types":$types}"""))
    }

    @Test
    fun `an error response with no schema is rejected, as is a non-object data field`() {
        assertFailsWith<IllegalStateException> { toSdl("""{"errors":[{"message":"nope"}]}""") }
        assertFailsWith<IllegalStateException> { toSdl("""{"data":"not-an-object"}""") } // data present but not an object
    }

    @Test
    fun `a data object without __schema falls back to a root-level types envelope`() {
        // data IS a JsonObject but carries no __schema → the && short-circuits to false and the root-level
        // "types" key is used as the schema object instead.
        val types = """[{"kind":"OBJECT","name":"Query","interfaces":[],"fields":[{"name":"a","args":[],"type":{"kind":"SCALAR","name":"String","ofType":null}}]}]"""
        val sdl = toSdl("""{"data":{"unrelated":1},"queryType":{"name":"Query"},"types":$types}""")
        assertTrue("type Query {" in sdl, sdl)
    }

    @Test
    fun `a schema with no types array emits only the schema block`() {
        val sdl = toSdl("""{"__schema":{"queryType":{"name":"Query"}}}""") // no "types" key at all → orEmpty()
        assertTrue("schema {\n  query: Query\n}" in sdl, sdl)
    }

    @Test
    fun `a root whose type node has no name is skipped`() {
        // queryType is an object with no "name" → the str("name") ?: continue arm; mutation still renders.
        val sdl = toSdl(
            """{"__schema":{"queryType":{},"mutationType":{"name":"Mutation"},"types":[
              {"kind":"OBJECT","name":"Mutation","interfaces":[],"fields":[{"name":"b","args":[],"type":{"kind":"SCALAR","name":"String","ofType":null}}]}
            ]}}""",
        )
        assertTrue("query:" !in sdl, sdl) // the nameless query root was skipped
        assertTrue("schema {\n  mutation: Mutation\n}" in sdl, sdl)
    }

    @Test
    fun `an input field whose defaultValue key is entirely absent renders without a default`() {
        val sdl = toSdl(
            """{"__schema":{"queryType":{"name":"Query"},"types":[
              {"kind":"OBJECT","name":"Query","interfaces":[],"fields":[{"name":"a","args":[],"type":{"kind":"SCALAR","name":"String","ofType":null}}]},
              {"kind":"INPUT_OBJECT","name":"Filter","inputFields":[{"name":"f","type":{"kind":"SCALAR","name":"Int","ofType":null}}]}
            ]}}""",
        )
        assertTrue("f: Int" in sdl && "f: Int =" !in sdl, sdl) // no defaultValue key → no default clause
    }

    @Test
    fun `a schema with all roots null emits no schema block, and an unknown kind is skipped`() {
        val sdl = toSdl(
            """
            {"data":{"__schema":{"queryType":null,"mutationType":null,"subscriptionType":null,"types":[
              {"kind":"OBJECT","name":"Query","fields":[{"name":"a","type":{"kind":"SCALAR","name":"String"}}]},
              {"kind":"FUTURE_KIND","name":"Mystery"}
            ]}}}
            """.trimIndent(),
        )
        assertTrue("schema {" !in sdl, sdl) // no roots → no schema block
        assertTrue("Mystery" !in sdl, sdl) // an unrecognized kind is skipped
        assertTrue("type Query {" in sdl, sdl)
    }

    @Test
    fun `a type reference missing its node or its name is rejected`() {
        // a field with no "type" key at all → the missing-reference guard
        val missingType = """{"data":{"__schema":{"queryType":{"name":"Query"},"types":[{"kind":"OBJECT","name":"Query","fields":[{"name":"a"}]}]}}}"""
        assertFailsWith<IllegalStateException> { toSdl(missingType) }
        // a named type reference with no "name" → the missing-name guard
        val namedNoName = """{"data":{"__schema":{"queryType":{"name":"Query"},"types":[{"kind":"OBJECT","name":"Query","fields":[{"name":"a","type":{"kind":"OBJECT"}}]}]}}}"""
        assertFailsWith<IllegalStateException> { toSdl(namedNoName) }
    }

    @Test
    fun `a type without a name key is skipped`() {
        val sdl = toSdl(
            """{"data":{"__schema":{"queryType":{"name":"Query"},"types":[
              {"kind":"OBJECT","name":"Query","fields":[{"name":"a","type":{"kind":"SCALAR","name":"String"}}]},
              {"kind":"OBJECT","fields":[]}
            ]}}}""",
        )
        assertTrue("type Query {" in sdl, sdl)
    }

    @Test
    fun `the schema block lists query, mutation, and subscription roots`() {
        val sdl = toSdl(
            """
            {"data":{"__schema":{
              "queryType":{"name":"Query"},"mutationType":{"name":"Mutation"},"subscriptionType":{"name":"Sub"},
              "types":[
                {"kind":"OBJECT","name":"Query","interfaces":[],"fields":[{"name":"a","args":[],"type":{"kind":"SCALAR","name":"String","ofType":null}}]},
                {"kind":"OBJECT","name":"Mutation","interfaces":[],"fields":[{"name":"b","args":[],"type":{"kind":"SCALAR","name":"String","ofType":null}}]},
                {"kind":"OBJECT","name":"Sub","interfaces":[],"fields":[{"name":"c","args":[],"type":{"kind":"SCALAR","name":"Int","ofType":null}}]}
              ]
            }}}
            """.trimIndent(),
        )
        assertTrue("schema {\n  query: Query\n  mutation: Mutation\n  subscription: Sub\n}" in sdl, sdl)
    }

    @Test
    fun `sparse types with absent member arrays and a null-named type are handled`() {
        // exercises the orEmpty()/str() guard arms: a type missing interfaces/fields/args, an enum/union/input with
        // no members, a custom scalar, and a JsonNull-named type that is skipped
        val sdl = toSdl(
            """
            {"data":{"__schema":{
              "queryType":{"name":"Query"},"mutationType":null,"subscriptionType":null,
              "types":[
                {"kind":"OBJECT","name":"Query","fields":[{"name":"a","type":{"kind":"SCALAR","name":"String","ofType":null}}]},
                {"kind":"INTERFACE","name":"Empty"},
                {"kind":"ENUM","name":"NoValues"},
                {"kind":"UNION","name":"NoMembers"},
                {"kind":"INPUT_OBJECT","name":"NoInputs"},
                {"kind":"SCALAR","name":"DateTime"},
                {"kind":"OBJECT","name":null}
              ]
            }}}
            """.trimIndent(),
        )
        assertTrue("a: String" in sdl, sdl) // a field with no "args" key
        assertTrue("interface Empty {" in sdl, sdl) // no interfaces/fields keys
        assertTrue("enum NoValues {" in sdl && "union NoMembers = " in sdl && "input NoInputs {" in sdl, sdl)
        assertTrue("scalar DateTime" in sdl, sdl)
    }

    @Test
    fun `scalars emit specifiedBy and input objects emit oneOf when reported`() {
        val sdl = toSdl(
            """
            {"data":{"__schema":{"queryType":{"name":"Query"},"types":[
              {"kind":"OBJECT","name":"Query","interfaces":[],"fields":[{"name":"a","args":[],"type":{"kind":"SCALAR","name":"String","ofType":null}}]},
              {"kind":"SCALAR","name":"URL","specifiedByURL":"https://example.com/url"},
              {"kind":"SCALAR","name":"Plain"},
              {"kind":"INPUT_OBJECT","name":"Choice","isOneOf":true,"inputFields":[{"name":"x","type":{"kind":"SCALAR","name":"String","ofType":null}}]},
              {"kind":"INPUT_OBJECT","name":"Filter","inputFields":[{"name":"y","type":{"kind":"SCALAR","name":"String","ofType":null}}]}
            ]}}}
            """.trimIndent(),
        )
        assertTrue("scalar URL @specifiedBy(url: \"https://example.com/url\")" in sdl, sdl)
        assertTrue("scalar Plain\n" in sdl && "scalar Plain @" !in sdl, sdl) // no directive clause
        assertTrue("input Choice @oneOf {" in sdl, sdl)
        assertTrue("input Filter {" in sdl, sdl) // no @oneOf
    }

    @Test
    fun `input fields render with and without a default, and a type without a name is skipped`() {
        val sdl = toSdl(
            """
            {"data":{"__schema":{
              "queryType":{"name":"Query"},"mutationType":null,"subscriptionType":null,
              "types":[
                {"kind":"OBJECT","name":"Query","interfaces":[],"fields":[{"name":"a","args":[{"name":"f","type":{"kind":"SCALAR","name":"Int","ofType":null},"defaultValue":null}],"type":{"kind":"SCALAR","name":"String","ofType":null}}]},
                {"kind":"INPUT_OBJECT","name":"Filter","inputFields":[
                  {"name":"withDefault","type":{"kind":"SCALAR","name":"Int","ofType":null},"defaultValue":"10"},
                  {"name":"noDefault","type":{"kind":"SCALAR","name":"String","ofType":null},"defaultValue":null}
                ]},
                {"kind":"OBJECT","name":null,"fields":[]}
              ]
            }}}
            """.trimIndent(),
        )
        assertTrue("withDefault: Int = 10" in sdl, sdl)
        assertTrue("noDefault: String" in sdl && "noDefault: String =" !in sdl, sdl) // no default clause
        assertTrue("a(f: Int): String" in sdl, sdl) // arg with no default
    }
}
