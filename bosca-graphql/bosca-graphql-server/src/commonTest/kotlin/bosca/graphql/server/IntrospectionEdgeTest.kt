package bosca.graphql.server

import bosca.graphql.parser.Parser
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Introspection edge cases: descriptions, deprecation (with/without reason), includeDeprecated, mutation/subscription roots, wrappers. */
class IntrospectionEdgeTest {

    private val sdl = """
        "The root." type Query { me: User legacy: String obj(filter: Filter): [User!]! }
        "A user." type User implements Node { id: ID! "the name" name: String! gone: Int withReason: Int }
        interface Node { id: ID! }
        union Pet = Cat | Dog
        type Cat { id: ID! }
        type Dog { id: ID! }
        enum Color { RED STALE }
        input Filter { term: String dead: Int }
        type Mutation { noop: Boolean }
        type Subscription { tick: Int }
    """.trimIndent()

    // applied via the wiring-free schema; deprecation is declared with directives parsed from SDL below
    private val deprecatedSdl = sdl
        .replace("gone: Int", "gone: Int @deprecated")
        .replace("withReason: Int", "withReason: Int @deprecated(reason: \"use name\")")
        .replace("STALE", "STALE @deprecated")
        .replace("dead: Int", "dead: Int @deprecated")

    private fun executor() = GraphQLExecutor(ExecutableSchema.fromSdl(deprecatedSdl, runtimeWiring { }))

    private suspend fun typeNamed(name: String, selection: String): JsonObject {
        val result = executor().execute(Parser.parse("""{ __type(name: "$name") { $selection } }"""))
        assertTrue(result.errors.isEmpty(), "${result.errors}")
        return (result.data as JsonObject)["__type"]!!.jsonObject
    }

    @Test
    fun `descriptions are reported, and absent ones are null`() = runTest {
        assertEquals("A user.", typeNamed("User", "description")["description"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, typeNamed("Node", "description")["description"]) // no description
        val nameField = typeNamed("User", "fields { name description }")["fields"]!!.jsonArray
            .map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "name" }
        assertEquals("the name", nameField["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun `deprecation is reported with an explicit reason and a default reason`() = runTest {
        val fields = typeNamed("User", "fields(includeDeprecated: true) { name isDeprecated deprecationReason }")["fields"]!!.jsonArray.map { it.jsonObject }
        val gone = fields.first { it["name"]!!.jsonPrimitive.content == "gone" }
        assertTrue(gone["isDeprecated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("No longer supported", gone["deprecationReason"]!!.jsonPrimitive.content) // default
        val withReason = fields.first { it["name"]!!.jsonPrimitive.content == "withReason" }
        assertEquals("use name", withReason["deprecationReason"]!!.jsonPrimitive.content) // explicit
    }

    @Test
    fun `includeDeprecated false hides deprecated fields, enum values, and input fields`() = runTest {
        val fieldNames = typeNamed("User", "fields(includeDeprecated: false) { name }")["fields"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue("gone" !in fieldNames && "name" in fieldNames)
        val enumValues = typeNamed("Color", "enumValues(includeDeprecated: false) { name }")["enumValues"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("RED"), enumValues) // STALE hidden
        val inputFields = typeNamed("Filter", "inputFields { name }")["inputFields"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("term"), inputFields) // dead hidden (default false)
        // includeDeprecated: true brings the enum value back
        val all = typeNamed("Color", "enumValues(includeDeprecated: true) { name }")["enumValues"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("RED", "STALE"), all)
    }

    @Test
    fun `the schema reports mutation and subscription roots and the input-object kind`() = runTest {
        val schema = executor().execute(Parser.parse("{ __schema { mutationType { name } subscriptionType { name } } }")).data as JsonObject
        val s = schema["__schema"]!!.jsonObject
        assertEquals("Mutation", s["mutationType"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("Subscription", s["subscriptionType"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("INPUT_OBJECT", typeNamed("Filter", "kind")["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a wrapped return type reports null name and chained ofType`() = runTest {
        // obj: [User!]! → NON_NULL( LIST( NON_NULL( User ) ) )
        val type = typeNamed("Query", "fields { name type { kind name ofType { kind ofType { kind ofType { kind name } } } } }")["fields"]!!.jsonArray
            .map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "obj" }["type"]!!.jsonObject
        assertEquals("NON_NULL", type["kind"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, type["name"]) // a wrapper type has no name
        assertEquals("LIST", type["ofType"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun `wrapper types report null for name, description, and all member lists`() = runTest {
        // obj: [User!]! → type.ofType is the LIST wrapper; every named-type field is null on a wrapper
        val wrapper = typeNamed(
            "Query",
            "fields { name type { ofType { name description kind fields { name } interfaces { name } possibleTypes { name } enumValues { name } inputFields { name } } } }",
        )["fields"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "obj" }["type"]!!.jsonObject["ofType"]!!.jsonObject
        assertEquals("LIST", wrapper["kind"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, wrapper["name"])
        assertEquals(JsonNull, wrapper["description"])
        assertEquals(JsonNull, wrapper["fields"])
        assertEquals(JsonNull, wrapper["interfaces"])
        assertEquals(JsonNull, wrapper["possibleTypes"])
        assertEquals(JsonNull, wrapper["enumValues"])
        assertEquals(JsonNull, wrapper["inputFields"])
    }

    @Test
    fun `includeDeprecated true includes deprecated input fields and directive args`() = runTest {
        val inputFields = typeNamed("Filter", "inputFields(includeDeprecated: true) { name }")["inputFields"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(setOf("term", "dead"), inputFields.toSet()) // dead included
        // a directive's args via includeDeprecated: true (the || short-circuit's true side)
        val directives = executor().execute(Parser.parse("{ __schema { directives { name args(includeDeprecated: true) { name } } } }")).data as JsonObject
        assertTrue(directives["__schema"]!!.jsonObject["directives"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun `an interface reports implementors and a union reports members`() = runTest {
        val node = typeNamed("Node", "kind possibleTypes { name }")
        assertEquals(setOf("User"), node["possibleTypes"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet())
        val pet = typeNamed("Pet", "kind possibleTypes { name }")
        assertEquals(setOf("Cat", "Dog"), pet["possibleTypes"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet())
        // a scalar has no fields/interfaces/enumValues/inputFields (the null branches)
        val scalar = typeNamed("String", "fields { name } interfaces { name } enumValues { name } inputFields { name }")
        assertEquals(JsonNull, scalar["fields"])
        assertEquals(JsonNull, scalar["interfaces"])
        assertEquals(JsonNull, scalar["enumValues"])
        assertEquals(JsonNull, scalar["inputFields"])
    }
}
