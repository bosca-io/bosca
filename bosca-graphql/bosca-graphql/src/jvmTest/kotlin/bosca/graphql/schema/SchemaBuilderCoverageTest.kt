package bosca.graphql.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Edge-case coverage of [SchemaBuilder]: interface-implementation covariance, default-value coercion, scalars. */
class SchemaBuilderCoverageTest {

    private fun build(sdl: String) = GraphQLSchema.fromSdl(sdl)
    private fun buildFails(sdl: String) = assertFailsWith<SchemaException> { build(sdl) }

    @Test
    fun `the built-in scalars are exposed and recognized`() {
        assertEquals(setOf("Int", "Float", "String", "Boolean", "ID"), GraphQLSchema.BUILT_IN_SCALARS)
        val schema = build("type Query { x: Int }")
        assertTrue(schema.isBuiltInScalar("Int"))
        assertFalse(schema.isBuiltInScalar("DateTime"))
    }

    @Test
    fun `a custom scalar definition is accepted`() {
        build("scalar DateTime type Query { now: DateTime }")
    }

    @Test
    fun `covariant interface implementations are accepted`() {
        // non-null satisfies a nullable interface field
        build("interface I { x: Int } type T implements I { x: Int! } type Query { t: T }")
        // matching non-null and list shapes
        build("interface I { id: ID! tags: [String] } type T implements I { id: ID! tags: [String] } type Query { t: T }")
        // a concrete object is a valid subtype of an interface return position
        build(
            """
            interface Node { id: ID! }
            type User implements Node { id: ID! }
            interface Container { item: Node }
            type Box implements Container { item: User }
            type Query { box: Box }
            """.trimIndent(),
        )
        // matching interface arguments are accepted, and an implementation may add an optional argument
        build("interface I { f(x: Int): String } type T implements I { f(x: Int): String } type Query { t: T }")
        build("interface I { f: String } type T implements I { f(extra: Int): String } type Query { t: T }")
        // an implementation may even add a non-null argument as long as it has a default
        build("interface I { f: String } type T implements I { f(extra: Int! = 5): String } type Query { t: T }")
    }

    @Test
    fun `incompatible interface implementations are rejected`() {
        buildFails("interface I { id: ID! } type T implements I { id: ID } type Query { t: T }") // nullable for non-null
        buildFails("interface I { tags: [String] } type T implements I { tags: String } type Query { t: T }") // scalar for list
        buildFails("interface I { item: Int } type T implements I { item: [Int] } type Query { t: T }") // list for scalar
        buildFails("interface I { x: Int! } type T implements I { x: String! } type Query { t: T }") // non-null inner mismatch
        buildFails("interface I { xs: [Int] } type T implements I { xs: [String] } type Query { t: T }") // list inner mismatch
    }

    @Test
    fun `interface argument incompatibilities are rejected`() {
        buildFails("interface I { f(x: Int): String } type T implements I { f(x: String): String } type Query { t: T }") // arg type differs
        buildFails("interface I { f: String } type T implements I { f(extra: Int!): String } type Query { t: T }") // adds a required arg
        buildFails("interface I { f(x: Int): String } type T implements I { f: String } type Query { t: T }") // drops a required arg
    }

    @Test
    fun `valid default values are accepted across kinds`() {
        build("enum E { ONE TWO } input In { e: E = ONE } type Query { f(input: In): String }")
        build("scalar DateTime input In { d: DateTime = \"2020\" } type Query { f(input: In): String }") // custom scalar default
        build("input Inner { n: Int } input Outer { i: Inner = { n: 1 } } type Query { f(input: Outer): String }")
        build("type Query { f(n: Int = 5, s: String = \"x\", b: Boolean = true): String }")
        // an input-object default that omits a required field which itself has a default is valid
        build("input Inner { req: Int! d: Int! = 5 } input Outer { i: Inner = { req: 1 } } type Query { f(input: Outer): String }")
        build("input Inner { ld: Float = 1, idv: ID = 7 } input Outer { i: Inner = { ld: 2, idv: \"x\" } } type Query { f(input: Outer): String }")
    }

    @Test
    fun `invalid default values are rejected`() {
        buildFails("enum E { ONE } input In { e: E = NOPE } type Query { f(input: In): String }") // bad enum value
        buildFails("enum E { ONE } input In { e: E = 5 } type Query { f(input: In): String }") // non-enum literal for an enum
        buildFails("input Inner { req: Int! } input Outer { i: Inner = {} } type Query { f(input: Outer): String }") // missing required field
        buildFails("input Inner { n: Int } input Outer { i: Inner = { n: \"bad\" } } type Query { f(input: Outer): String }") // nested type mismatch
        buildFails("input Inner { n: Int } input Outer { i: Inner = { bogus: 1 } } type Query { f(input: Outer): String }") // unknown field
        buildFails("type Query { f(n: Int = \"x\"): String }") // scalar mismatch
        buildFails("input Outer { i: Inner = 1 } input Inner { n: Int } type Query { f(input: Outer): String }") // non-object for input object
        buildFails("type Query { f(xs: [Int] = [\"bad\"]): String }") // list element mismatch
        buildFails("type Query { f(n: Int! = null): String }") // null for non-null
    }

    @Test
    fun `a reserved argument name is rejected`() {
        buildFails("type Query { f(__x: Int): String }")
    }
}
