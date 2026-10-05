@file:OptIn(ExperimentalUuidApi::class)

package bosca.graphql.server

import bosca.graphql.language.Field
import bosca.graphql.language.OperationDefinition
import bosca.graphql.parser.Parser
import bosca.graphql.schema.GraphQLSchema
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi

/**
 * coercing request variables (JSON) + argument literals (AST) into internal values — defaults,
 * null-vs-absent, nested input objects, enums, lists, non-null violations, and path/location on errors.
 */
class CoercionTest {

    private val executable = ExecutableSchema.fromSdl(
        """
        type Query {
          search(filter: Filter!, status: Status, tags: [String!], limit: Int = 10): [String!]
          at(t: DateTime): Int
        }
        input Filter { term: String! min: Int max: Int = 100 nested: Nested }
        input Nested { flag: Boolean! }
        enum Status { ACTIVE ARCHIVED }
        scalar DateTime
        """.trimIndent(),
        runtimeWiring { scalar("DateTime", ExtendedScalars.DateTime) },
    )

    private val coercion = Coercion(executable)
    private fun op(query: String) = Parser.parse(query).definitions.filterIsInstance<OperationDefinition>().first()
    private fun field(query: String) = op(query).selectionSet.selections.first() as Field
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `coerces variables — nested input object, enum, list, applied defaults`() {
        val vars = coercion.coerceVariables(
            op("query Q(\$filter: Filter!, \$status: Status, \$tags: [String!]) { search(filter: \$filter, status: \$status, tags: \$tags) }"),
            json("""{"filter":{"term":"x","min":5},"status":"ACTIVE","tags":["a","b"]}"""),
        )
        val filter = vars.values["filter"] as Map<*, *>
        assertEquals("x", filter["term"])
        assertEquals(5, filter["min"])
        assertEquals(100, filter["max"]) // input-field default applied
        assertEquals(false, filter.containsKey("nested")) // nullable + absent → omitted
        assertEquals("ACTIVE", vars.values["status"]) // enum internal = its name
        assertEquals(listOf("a", "b"), vars.values["tags"])
    }

    @Test
    fun `a variable default is applied when absent, and a nullable variable is omitted`() {
        val vars = coercion.coerceVariables(
            op("query Q(\$n: Int = 7, \$opt: String) { search(filter: {term: \"x\"}) }"),
            json("{}"),
        )
        assertEquals(7, vars.values["n"])
        assertEquals(false, vars.values.containsKey("opt")) // absent, no default → omitted
    }

    @Test
    fun `coerces arguments — literals, applied arg default, nested input, custom scalar`() {
        val args = coercion.coerceArguments(
            "Query",
            field("query { search(filter: {term: \"x\"}, status: ACTIVE, tags: [\"a\"]) }"),
            CoercedVariables.EMPTY,
        )
        assertEquals(10, args["limit"]) // argument default applied
        assertEquals("ACTIVE", args["status"])
        assertEquals(listOf("a"), args["tags"])
        assertEquals("x", (args["filter"] as Map<*, *>)["term"])
        assertEquals(100, (args["filter"] as Map<*, *>)["max"])

        val at = coercion.coerceArguments("Query", field("query { at(t: \"2026-01-01T00:00:00Z\") }"), CoercedVariables.EMPTY)
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), at["t"])
    }

    @Test
    fun `resolves a variable referenced as an argument`() {
        val args = coercion.coerceArguments(
            "Query",
            field("query Q(\$s: Status) { search(filter: {term: \"x\"}, status: \$s) }"),
            CoercedVariables(mapOf("s" to "ARCHIVED")),
        )
        assertEquals("ARCHIVED", args["status"])
    }

    @Test
    fun `a single value coerces into a list, for both variables and arguments`() {
        val vars = coercion.coerceVariables(op("query Q(\$tags: [String!]) { at }"), json("""{"tags":"solo"}"""))
        assertEquals(listOf("solo"), vars.values["tags"])

        val args = coercion.coerceArguments("Query", field("query { search(filter: {term: \"x\"}, tags: \"solo\") }"), CoercedVariables.EMPTY)
        assertEquals(listOf("solo"), args["tags"])
    }

    @Test
    fun `a nullable variable referenced as an argument but not provided resolves to null`() {
        val args = coercion.coerceArguments(
            "Query",
            field("query Q(\$s: Status) { search(filter: {term: \"x\"}, status: \$s) }"),
            CoercedVariables.EMPTY, // $s was never provided
        )
        assertNull(args["status"])
    }

    @Test
    fun `a null literal coerces to null in a nullable position`() {
        val args = coercion.coerceArguments(
            "Query",
            field("query { search(filter: {term: \"x\"}, status: null) }"),
            CoercedVariables.EMPTY,
        )
        assertNull(args["status"])
    }

    // ---- errors carry path + location ----

    @Test
    fun `missing required variable fails with its path`() {
        val e = assertFailsWith<CoercionException> {
            coercion.coerceVariables(op("query Q(\$x: Filter!) { search(filter: \$x) }"), json("{}"))
        }
        assertEquals(listOf("x"), e.path)
    }

    @Test
    fun `a non-null variable given null is rejected`() {
        val e = assertFailsWith<CoercionException> {
            coercion.coerceVariables(op("query Q(\$t: String!) { at }"), json("""{"t":null}"""))
        }
        assertEquals(listOf("t"), e.path)
    }

    @Test
    fun `a missing required nested input field fails with a nested path`() {
        val e = assertFailsWith<CoercionException> {
            coercion.coerceVariables(op("query Q(\$f: Filter!) { search(filter: \$f) }"), json("""{"f":{"min":1}}"""))
        }
        assertEquals(listOf("f", "term"), e.path)
    }

    @Test
    fun `an invalid enum value and an unknown input field are rejected`() {
        assertFailsWith<CoercionException> {
            coercion.coerceVariables(op("query Q(\$s: Status) { at }"), json("""{"s":"NOPE"}"""))
        }
        assertFailsWith<CoercionException> {
            coercion.coerceVariables(op("query Q(\$f: Filter!) { at }"), json("""{"f":{"term":"x","bogus":1}}"""))
        }
    }

    @Test
    fun `a missing required argument is rejected`() {
        assertFailsWith<CoercionException> {
            coercion.coerceArguments("Query", field("query { search(status: ACTIVE) }"), CoercedVariables.EMPTY)
        }
    }
}
