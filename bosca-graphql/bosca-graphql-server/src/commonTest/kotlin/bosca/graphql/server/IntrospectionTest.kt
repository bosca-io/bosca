package bosca.graphql.server

import bosca.graphql.parser.Parser
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * the introspection system. Runs the standard introspection query through the engine and checks the
 * result matches the source SDL — the shape the gateway's schema stitching and the client codegen consume.
 */
class IntrospectionTest {

    private val sdl = """
        type Query {
          me: User
          user(id: ID!): User
          search(filter: Filter): [User!]!
          legacy: String @deprecated(reason: "use me")
          status: Status
          pet: Pet
        }
        type User implements Node { id: ID! name: String! }
        type Admin implements Node { id: ID! level: Int! }
        interface Node { id: ID! }
        union Pet = Cat | Dog
        type Cat { meow: String! }
        type Dog { bark: String! }
        enum Status { ACTIVE ARCHIVED }
        input Filter { term: String limit: Int = 10 }
    """.trimIndent()

    private fun executor() = GraphQLExecutor(ExecutableSchema.fromSdl(sdl, runtimeWiring { }))

    // The standard introspection query (fragments + `ofType` recursion) the gateway/client codegen issue.
    private val introspectionQuery = """
        query IntrospectionQuery {
          __schema {
            queryType { name }
            mutationType { name }
            subscriptionType { name }
            types { ...FullType }
            directives { name locations args { ...InputValue } isRepeatable }
          }
        }
        fragment FullType on __Type {
          kind name description
          fields(includeDeprecated: true) {
            name args { ...InputValue } type { ...TypeRef } isDeprecated deprecationReason
          }
          inputFields { ...InputValue }
          interfaces { ...TypeRef }
          enumValues(includeDeprecated: true) { name isDeprecated }
          possibleTypes { ...TypeRef }
        }
        fragment InputValue on __InputValue { name type { ...TypeRef } defaultValue }
        fragment TypeRef on __Type {
          kind name
          ofType { kind name ofType { kind name ofType { kind name } } }
        }
    """.trimIndent()

    private suspend fun introspect(): JsonObject {
        val result = executor().execute(Parser.parse(introspectionQuery))
        assertTrue(result.errors.isEmpty(), "introspection produced errors: ${result.errors}")
        return (result.data as JsonObject)["__schema"]!!.jsonObject
    }

    private fun JsonObject.typeNamed(name: String): JsonObject =
        this["types"]!!.jsonArray.map { it.jsonObject }.first { it["name"]?.jsonPrimitive?.content == name }

    private fun JsonObject.field(name: String): JsonObject =
        this["fields"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == name }

    @Test
    fun `reports the root operation types`() = runTest {
        val schema = introspect()
        assertEquals("Query", schema["queryType"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, schema["mutationType"])
        assertEquals(JsonNull, schema["subscriptionType"])
    }

    @Test
    fun `lists user types, built-in scalars, and the introspection types`() = runTest {
        val names = introspect()["types"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertTrue(names.containsAll(setOf("Query", "User", "Admin", "Node", "Pet", "Cat", "Dog", "Status", "Filter")))
        assertTrue(names.containsAll(setOf("Int", "String", "Boolean", "ID"))) // built-in scalars
        assertTrue(names.containsAll(setOf("__Schema", "__Type", "__Field", "__TypeKind", "__DirectiveLocation")))
    }

    @Test
    fun `an object type reports its kind and fields, hiding the meta-fields`() = runTest {
        val query = introspect().typeNamed("Query")
        assertEquals("OBJECT", query["kind"]!!.jsonPrimitive.content)
        val fieldNames = query["fields"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(fieldNames.containsAll(listOf("me", "user", "search", "status", "pet")))
        assertTrue(fieldNames.none { it.startsWith("__") }) // __schema/__type are hidden from introspection
    }

    @Test
    fun `a non-null argument is reported as a wrapped type ref`() = runTest {
        val arg = introspect().typeNamed("Query").field("user")["args"]!!.jsonArray.single().jsonObject
        assertEquals("id", arg["name"]!!.jsonPrimitive.content)
        val type = arg["type"]!!.jsonObject
        assertEquals("NON_NULL", type["kind"]!!.jsonPrimitive.content)
        assertNull(type["name"]?.takeUnless { it == JsonNull })
        assertEquals("SCALAR", type["ofType"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals("ID", type["ofType"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a list-of-non-null return type nests three type-ref wrappers`() = runTest {
        // search: [User!]!  →  NON_NULL( LIST( NON_NULL( User ) ) )
        val type = introspect().typeNamed("Query").field("search")["type"]!!.jsonObject
        assertEquals("NON_NULL", type["kind"]!!.jsonPrimitive.content)
        val list = type["ofType"]!!.jsonObject
        assertEquals("LIST", list["kind"]!!.jsonPrimitive.content)
        val nonNullUser = list["ofType"]!!.jsonObject
        assertEquals("NON_NULL", nonNullUser["kind"]!!.jsonPrimitive.content)
        assertEquals("User", nonNullUser["ofType"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `deprecation is reported on fields`() = runTest {
        val legacy = introspect().typeNamed("Query").field("legacy")
        assertTrue(legacy["isDeprecated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("use me", legacy["deprecationReason"]!!.jsonPrimitive.content)
        val me = introspect().typeNamed("Query").field("me")
        assertTrue(!me["isDeprecated"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `an interface reports its implementors and an object its interfaces`() = runTest {
        val schema = introspect()
        val node = schema.typeNamed("Node")
        assertEquals("INTERFACE", node["kind"]!!.jsonPrimitive.content)
        val possible = node["possibleTypes"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("User", "Admin"), possible)
        val userInterfaces = schema.typeNamed("User")["interfaces"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("Node"), userInterfaces)
    }

    @Test
    fun `a union reports its members`() = runTest {
        val pet = introspect().typeNamed("Pet")
        assertEquals("UNION", pet["kind"]!!.jsonPrimitive.content)
        val members = pet["possibleTypes"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("Cat", "Dog"), members)
    }

    @Test
    fun `an enum reports its values and an input object its fields with defaults`() = runTest {
        val schema = introspect()
        val status = schema.typeNamed("Status")
        assertEquals("ENUM", status["kind"]!!.jsonPrimitive.content)
        assertEquals(listOf("ACTIVE", "ARCHIVED"), status["enumValues"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })

        val filter = schema.typeNamed("Filter")
        assertEquals("INPUT_OBJECT", filter["kind"]!!.jsonPrimitive.content)
        val limit = filter["inputFields"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "limit" }
        assertEquals("10", limit["defaultValue"]!!.jsonPrimitive.content)
    }

    @Test
    fun `built-in directives are reported`() = runTest {
        val directives = introspect()["directives"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertTrue(directives.containsAll(setOf("skip", "include", "deprecated")))
    }

    @Test
    fun `__type looks up a single type by name and returns null for an unknown name`() = runTest {
        val found = executor().execute(Parser.parse("""{ __type(name: "User") { kind name fields { name } } }"""))
        val type = (found.data as JsonObject)["__type"]!!.jsonObject
        assertEquals("OBJECT", type["kind"]!!.jsonPrimitive.content)
        assertEquals("User", type["name"]!!.jsonPrimitive.content)

        val missing = executor().execute(Parser.parse("""{ __type(name: "Nope") { name } }"""))
        assertEquals(JsonNull, (missing.data as JsonObject)["__type"])
    }

    @Test
    fun `__typename resolves on objects, interfaces, and unions`() = runTest {
        val wired = GraphQLExecutor(
            ExecutableSchema.fromSdl(
                sdl,
                runtimeWiring {
                    type("Query") {
                        field("me") { mapOf("id" to "1", "name" to "Ada") }
                        field("pet") { mapOf("__typename" to "Cat", "meow" to "mrow") }
                    }
                    type("Pet") { resolveType { (it as Map<*, *>)["__typename"] as String? } }
                },
            ),
        )
        val data = wired.execute(Parser.parse("{ me { __typename } pet { __typename } }")).data as JsonObject
        assertEquals("User", data["me"]!!.jsonObject["__typename"]!!.jsonPrimitive.content)
        assertEquals("Cat", data["pet"]!!.jsonObject["__typename"]!!.jsonPrimitive.content)
    }
}
