package bosca.graphql.server

import bosca.graphql.language.Field
import bosca.graphql.language.OperationDefinition
import bosca.graphql.parser.Parser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Exhaustive branch coverage of [Coercion] — variable (JSON) and argument (AST) coercion, every kind and failure. */
class CoercionCoverageTest {

    private val executable = ExecutableSchema.fromSdl(
        """
        type Query {
          g: String
          f(i: Int!, opt: Int, withDef: Int = 5, e: E, list: [Int], inp: In, v: Int): String
        }
        type User { id: ID! }
        enum E { ONE TWO }
        input In { req: Int! opt: Int withDef: Int = 9 }
        """.trimIndent(),
        runtimeWiring { },
    )
    private val coercion = Coercion(executable)

    private fun operation(varsDecl: String): OperationDefinition =
        Parser.parse("query Q($varsDecl) { g }").definitions.filterIsInstance<OperationDefinition>().first()

    private fun coerceVars(varsDecl: String, json: String): Map<String, Any?> =
        coercion.coerceVariables(operation(varsDecl), Json.parseToJsonElement(json).jsonObject as JsonObject).values

    private fun coerceVars(varsDecl: String, values: Map<String, Any?>): Map<String, Any?> =
        coercion.coerceVariables(operation(varsDecl), values).values

    private fun field(query: String): Field =
        (Parser.parse(query).definitions.first() as OperationDefinition).selectionSet.selections.first() as Field

    // ---- coerceVariables (JSON) ----

    @Test
    fun `variable coercion handles provided, default, required-missing, and nullable-absent`() {
        assertEquals(mapOf("a" to 1), coerceVars("\$a: Int", """{"a":1}"""))
        assertEquals(mapOf("a" to 5), coerceVars("\$a: Int = 5", "{}")) // default applied
        assertEquals(emptyMap(), coerceVars("\$a: Int", "{}")) // nullable, no default → absent
        assertFailsWith<CoercionException> { coerceVars("\$a: Int!", "{}") } // required missing
    }

    @Test
    fun `variable coercion handles non-null, lists, enums, and explicit null`() {
        assertFailsWith<CoercionException> { coerceVars("\$a: Int!", """{"a":null}""") } // non-null given null
        assertEquals(mapOf("a" to null), coerceVars("\$a: [Int]", """{"a":null}""")) // null list
        assertEquals(mapOf("a" to listOf(1, 2)), coerceVars("\$a: [Int]", """{"a":[1,2]}"""))
        assertEquals(mapOf("a" to listOf(7)), coerceVars("\$a: [Int]", """{"a":7}""")) // single coerces to list
        assertEquals(mapOf("a" to null), coerceVars("\$a: Int", """{"a":null}""")) // named null
        assertEquals(mapOf("a" to "ONE"), coerceVars("\$a: E", """{"a":"ONE"}"""))
        assertFailsWith<CoercionException> { coerceVars("\$a: E", """{"a":"NOPE"}""") } // invalid enum
        assertFailsWith<CoercionException> { coerceVars("\$a: E", """{"a":1}""") } // non-string primitive enum
        assertFailsWith<CoercionException> { coerceVars("\$a: E", """{"a":{}}""") } // non-primitive enum value
    }

    @Test
    fun `variable coercion handles input objects and their failures`() {
        assertEquals(mapOf("a" to mapOf("req" to 1, "withDef" to 9)), coerceVars("\$a: In", """{"a":{"req":1}}"""))
        assertFailsWith<CoercionException> { coerceVars("\$a: In", """{"a":1}""") } // not an object
        assertFailsWith<CoercionException> { coerceVars("\$a: In", """{"a":{"bogus":1}}""") } // unknown field
        assertFailsWith<CoercionException> { coerceVars("\$a: In", """{"a":{}}""") } // missing required
    }

    @Test
    fun `multipart-style plain maps lists strings and custom scalar values are preserved`() {
        assertEquals(mapOf("a" to listOf(1, 2)), coerceVars("\$a: [Int!]!", mapOf("a" to listOf(1, 2))))
        assertEquals(mapOf("a" to "ONE"), coerceVars("\$a: E", mapOf("a" to "ONE")))
        assertEquals(
            mapOf("a" to mapOf("req" to 1, "withDef" to 9)),
            coerceVars("\$a: In", mapOf("a" to mapOf("req" to 1))),
        )

        val upload = Any()
        val uploadExecutable = ExecutableSchema.fromSdl(
            "scalar Upload\ntype Query { upload(file: Upload): String }",
            runtimeWiring {
                scalar("Upload", object : Coercing {
                    override fun serialize(value: Any?) = JsonPrimitive(value.toString())
                    override fun parseValue(input: Any?) = input
                    override fun parseLiteral(literal: bosca.graphql.language.Value) = literal
                })
            },
        )
        val uploadCoercion = Coercion(uploadExecutable)
        val coerced = uploadCoercion.coerceVariables(operation("\$file: Upload"), mapOf("file" to upload))
        assertEquals(upload, coerced.values["file"])
    }

    @Test
    fun `an output-typed variable fails coercion for a provided value and a default`() {
        // coercion runs before validation, so an output-typed variable reaches the not-an-input-type branch
        assertFailsWith<CoercionException> { coerceVars("\$a: User", """{"a":{"id":"1"}}""") } // provided JSON value (coerceJson path)
        assertFailsWith<CoercionException> { coerceVars("\$a: User = { id: \"1\" }", "{}") } // default literal (coerceLiteral path)
    }

    @Test
    fun `a scalar coercing failure becomes a coercion exception with a path`() {
        val e = assertFailsWith<CoercionException> { coerceVars("\$a: Int", """{"a":"notanint"}""") }
        assertEquals(listOf("a"), e.path)
    }

    // ---- coerceArguments (AST) ----

    @Test
    fun `argument coercion handles unknown field, provided, default, and required-missing`() {
        assertEquals(emptyMap(), coercion.coerceArguments("Query", field("{ bogus }"), CoercedVariables.EMPTY))
        assertEquals(5, coercion.coerceArguments("Query", field("{ f(i: 1) }"), CoercedVariables.EMPTY)["withDef"]) // default
        assertEquals(2, coercion.coerceArguments("Query", field("{ f(i: 2) }"), CoercedVariables.EMPTY)["i"])
        assertFailsWith<CoercionException> { coercion.coerceArguments("Query", field("{ f(opt: 1) }"), CoercedVariables.EMPTY) } // i required
    }

    @Test
    fun `argument coercion resolves variables`() {
        val vars = CoercedVariables(mapOf("v" to 7))
        assertEquals(7, coercion.coerceArguments("Query", field("{ f(i: \$v) }"), vars)["i"])
        // a nullable argument backed by an absent variable resolves to null
        assertEquals(null, coercion.coerceArguments("Query", field("{ f(i: 1, opt: \$missing) }"), CoercedVariables.EMPTY)["opt"])
        // a required argument backed by an absent variable fails
        assertFailsWith<CoercionException> { coercion.coerceArguments("Query", field("{ f(i: \$missing) }"), CoercedVariables.EMPTY) }
        // a required argument backed by an explicitly-null variable fails
        assertFailsWith<CoercionException> { coercion.coerceArguments("Query", field("{ f(i: \$v) }"), CoercedVariables(mapOf("v" to null))) }
        // a nullable argument backed by an explicitly-null variable resolves to null (not an error)
        assertEquals(null, coercion.coerceArguments("Query", field("{ f(i: 1, opt: \$x) }"), CoercedVariables(mapOf("x" to null)))["opt"])
    }

    @Test
    fun `argument literal coercion handles null, lists, enums, input objects, and failures`() {
        assertEquals(null, coercion.coerceArguments("Query", field("{ f(i: 1, opt: null) }"), CoercedVariables.EMPTY)["opt"]) // named null
        assertFailsWith<CoercionException> { coercion.coerceArguments("Query", field("{ f(i: null) }"), CoercedVariables.EMPTY) } // non-null null
        assertEquals(null, coercion.coerceArguments("Query", field("{ f(i: 1, list: null) }"), CoercedVariables.EMPTY)["list"]) // null list
        assertEquals(listOf(1, 2), coercion.coerceArguments("Query", field("{ f(i: 1, list: [1, 2]) }"), CoercedVariables.EMPTY)["list"])
        assertEquals(listOf(3), coercion.coerceArguments("Query", field("{ f(i: 1, list: 3) }"), CoercedVariables.EMPTY)["list"]) // single→list
        assertEquals("ONE", coercion.coerceArguments("Query", field("{ f(i: 1, e: ONE) }"), CoercedVariables.EMPTY)["e"])
        assertFailsWith<CoercionException> { coercion.coerceArguments("Query", field("{ f(i: 1, e: NOPE) }"), CoercedVariables.EMPTY) } // invalid enum
        assertFailsWith<CoercionException> { coercion.coerceArguments("Query", field("{ f(i: 1, e: 5) }"), CoercedVariables.EMPTY) } // non-enum literal
    }

    @Test
    fun `argument input-object literal coercion handles every branch`() {
        assertEquals(
            mapOf("req" to 1, "withDef" to 9),
            coercion.coerceArguments("Query", field("{ f(i: 1, inp: { req: 1 }) }"), CoercedVariables.EMPTY)["inp"],
        )
        assertFailsWith<CoercionException> { coercion.coerceArguments("Query", field("{ f(i: 1, inp: 5) }"), CoercedVariables.EMPTY) } // not an object
        assertFailsWith<CoercionException> { coercion.coerceArguments("Query", field("{ f(i: 1, inp: { bogus: 1 }) }"), CoercedVariables.EMPTY) } // unknown field
        assertFailsWith<CoercionException> { coercion.coerceArguments("Query", field("{ f(i: 1, inp: {}) }"), CoercedVariables.EMPTY) } // missing required
    }
}
