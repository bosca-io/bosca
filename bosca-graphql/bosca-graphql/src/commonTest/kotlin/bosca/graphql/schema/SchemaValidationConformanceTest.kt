package bosca.graphql.schema

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * conformance corpus: one valid SDL exercising the full type-system surface, plus SDL violating each
 * rule asserting the precise [SchemaException]. Complements [SchemaTest] / [SchemaExtensionsTest] (the original
 * ref/dup/root checks) and [RealComposedSchemaTest] (no false positives on the real schema).
 */
class SchemaValidationConformanceTest {

    private fun build(sdl: String) = GraphQLSchema.fromSdl(sdl)
    private fun rejects(sdl: String, fragment: String) {
        val ex = assertFailsWith<SchemaException> { build(sdl) }
        assertTrue(ex.message!!.contains(fragment), "expected \"$fragment\", got: ${ex.message}")
    }

    @Test
    fun `a full valid schema builds`() {
        build(
            """
            schema { query: Query mutation: Mutation }
            type Query {
              node(id: ID!): Node
              search(filter: Filter = { term: "x" }): [Item!]
            }
            type Mutation { save(input: SaveInput!): Boolean }
            interface Node { id: ID! }
            type User implements Node { id: ID! name: String! }
            type Post implements Node { id: ID! title: String! }
            union Item = User | Post
            input Filter { term: String! limit: Int = 10 status: Status = ACTIVE }
            input SaveInput { title: String! }
            enum Status { ACTIVE ARCHIVED }
            scalar DateTime
            directive @audit(level: Int = 1) on FIELD_DEFINITION
            """.trimIndent(),
        )
    }

    @Test
    fun `unique names within scopes`() {
        rejects("type Query { a: Int a: String }", "Field 'a' is declared more than once")
        rejects("type Query { f(a: Int, a: Int): Int }", "Argument 'a' is declared more than once")
        rejects("type Query { x: E } enum E { A A }", "Enum value 'A' is declared more than once")
        rejects("type Query { x: Int } input I { a: Int a: Int }", "Field 'a' is declared more than once")
        rejects("type Query { x: Int } union U = A | A type A { y: Int }", "Member 'A' is declared more than once")
    }

    @Test
    fun `reserved double-underscore names`() {
        rejects("type Query { x: Int } type __Bad { y: Int }", "reserved '__' prefix")
        rejects("type Query { __x: Int }", "reserved '__' prefix")
        rejects("type Query { x: Int } directive @__d on FIELD", "reserved '__' prefix")
    }

    @Test
    fun `input and output type-position correctness`() {
        rejects("type Query { f: In } input In { x: Int }", "must be an output type")
        rejects("type Query { f(x: Out): Int } type Out { y: Int }", "must be an input type")
        rejects("type Query { x: Int } input I { f: Out } type Out { y: Int }", "must be an input type")
        rejects("type Query { x: Int } directive @d(a: Out) on FIELD type Out { y: Int }", "must be an input type")
        rejects("type Query { x: Int } union U = I interface I { a: Int }", "must be an object type")
    }

    @Test
    fun `interface implementation completeness`() {
        rejects(
            "type Query { x: Int } interface N { id: ID! } type T implements N { name: String }",
            "does not implement field 'id'",
        )
        rejects(
            "type Query { x: Int } interface N { id: ID! } type T implements N { id: Int }",
            "is not compatible with interface 'N'",
        )
        rejects(
            "type Query { x: Int } interface N { f(a: Int): Int } type T implements N { f: Int }",
            "is missing argument 'a'",
        )
        rejects(
            "type Query { x: Int } interface N { f(a: Int): Int } type T implements N { f(a: String): Int }",
            "type differs from interface 'N'",
        )
        rejects(
            "type Query { x: Int } interface N { f: Int } type T implements N { f(extra: Int!): Int }",
            "adds required argument 'extra'",
        )
        rejects(
            "type Query { x: Int } interface N { f(a: [Int!]): Int } type T implements N { f(a: [String]): Int }",
            "type differs from interface 'N'",
        )
        // an implementation may add an OPTIONAL argument the interface doesn't declare
        build("type Query { x: Int } interface N { f: Int } type T implements N { f(extra: Int): Int }")
        // a differing scalar field type, and a non-subtype where the interface field is itself an interface
        rejects("type Query { x: Int } interface N { f: Int } type T implements N { f: String }", "is not compatible")
        rejects("type Query { x: Int } interface N { f: I } interface I { a: Int } type T implements N { f: Int }", "is not compatible")
        // covariance is accepted: implementing with a non-null where the interface is nullable
        build("type Query { x: Int } interface N { id: ID } type T implements N { id: ID! }")
        // a concrete object subtype of an interface-typed field, inside a list
        build(
            """
            type Query { x: Int }
            interface HasItems { items: [Node] }
            interface Node { id: ID! }
            type User implements Node { id: ID! }
            type Box implements HasItems { items: [User] }
            """.trimIndent(),
        )
        // a union-member subtype, and an interface that refines another interface
        build(
            """
            type Query { x: Int }
            interface HasPet { pet: Pet }
            interface HasNode { node: Node }
            interface Node { id: ID! }
            interface Special implements Node { id: ID! }
            union Pet = Dog
            type Dog { id: ID! }
            type Owner implements HasPet { pet: Dog }
            type Wrapper implements HasNode { node: Special }
            """.trimIndent(),
        )
    }

    @Test
    fun `default values must coerce to their type`() {
        rejects("type Query { f(x: Int = \"no\"): Int }", "expected an Int")
        rejects("type Query { x: Int } directive @d(f: Float = \"no\") on FIELD", "expected a Float")
        rejects("type Query { x: Int } directive @d(b: Boolean = 1) on FIELD", "expected a Boolean")
        rejects("type Query { x: Int } directive @d(i: ID = true) on FIELD", "expected an ID")
        rejects("type Query { x: Int } enum Color { RED } input I { c: Color = NOPE }", "not a valid 'Color' enum value")
        rejects("type Query { x: Int } input I { n: Int! = null }", "expected a non-null value")
        rejects("type Query { x: Int } input I { f: Sub = { a: 1 } } input Sub { a: String }", "field 'a': expected a String")
        rejects("type Query { x: Int } input I { f: Sub = { bogus: 1 } } input Sub { a: String }", "unknown field 'bogus'")
        rejects("type Query { x: Int } input I { f: Sub = { } } input Sub { a: String! }", "missing required field 'a'")
        rejects("type Query { x: Int } input I { f: Sub = 1 } input Sub { a: String }", "expected input object 'Sub'")
        rejects("type Query { x: Int } input I { tags: [Int!] = [\"a\"] }", "expected an Int")
        // valid defaults build: list literal, single→list coercion, nullable-list null, object, enum, custom scalar,
        // explicit null on a nullable scalar, and Float/ID coercions (from both literal forms).
        build(
            """
            type Query { x: Int }
            input I {
              tags: [String!] = ["a", "b"]
              one: [String!] = "a"
              maybe: [String!] = null
              sub: Sub = { a: "y" }
              status: Status = ACTIVE
              at: DateTime = "2026-01-01"
              s: String = null
              f: Float = 1.5
              g: Float = 2
              id1: ID = "x"
              id2: ID = 7
            }
            input Sub { a: String }
            enum Status { ACTIVE }
            scalar DateTime
            """.trimIndent(),
        )
    }

    @Test
    fun `unknown types in input positions are reported`() {
        rejects("type Query { x: Int } input I { f: Missing }", "Unknown type 'Missing'")
        rejects("type Query { x: Int } directive @d(a: Missing) on FIELD", "Unknown type 'Missing'")
    }

    // ---- §3: transitive interfaces, non-empty types, directive locations + applications ----

    @Test
    fun `a type implementing an interface must also declare its transitive interfaces`() {
        rejects(
            """
            type Query { n: C }
            interface A { x: Int }
            interface B implements A { x: Int }
            type C implements B { x: Int }
            """.trimIndent(),
            "must also declare it implements 'A'",
        )
        // declaring both is valid
        build(
            """
            type Query { n: C }
            interface A { x: Int }
            interface B implements A { x: Int }
            type C implements B & A { x: Int }
            """.trimIndent(),
        )
    }

    @Test
    fun `objects, interfaces, and input objects must define at least one field`() {
        rejects("type Query { e: Empty } type Empty", "Type 'Empty' must define one or more fields")
        rejects("type Query { x(f: EmptyInput): Int } input EmptyInput", "Input object 'EmptyInput' must define one or more fields")
    }

    @Test
    fun `a directive definition with an unknown location is rejected`() {
        rejects("type Query { x: Int } directive @d on BOGUS_LOCATION", "unknown location 'BOGUS_LOCATION'")
    }

    @Test
    fun `a directive applied at the wrong location or that is undefined is rejected`() {
        rejects("type Query { x: Int @d } directive @d on OBJECT", "is not allowed on field 'Query.x'")
        rejects("type Query { x: Int @nope }", "Unknown directive '@nope'")
    }

    @Test
    fun `oneOf input objects require nullable, default-free fields`() {
        build("type Query { x: Int } input Choice @oneOf { a: String b: Int }") // valid: all nullable, no defaults
        rejects("type Query { x: Int } input Bad @oneOf { a: String! }", "must be nullable")
        rejects("""type Query { x: Int } input Bad @oneOf { a: String = "x" }""", "must not have a default value")
    }

    @Test
    fun `a type implementing the same interface twice is rejected`() {
        rejects("type Query { n: T } interface I { x: Int } type T implements I & I { x: Int }", "Interface 'I' is declared more than once")
    }

    @Test
    fun `schema-level directive applications are validated`() {
        build("directive @sd on SCHEMA\nschema @sd { query: Query }\ntype Query { x: Int }")
        rejects("schema @deprecated { query: Query } type Query { x: Int }", "not allowed on the schema")
    }

    @Test
    fun `an input object that references itself through only non-null fields is rejected`() {
        rejects("type Query { x: Int } input A { b: B! } input B { a: A! }", "non-null reference cycle")
        // a nullable field anywhere in the chain breaks the cycle and is allowed
        build("type Query { x: Int } input A { b: B } input B { a: A }")
    }

    @Test
    fun `a required argument or input field must not be deprecated`() {
        rejects("type Query { f(a: Int! @deprecated): Int }", "Required argument 'Query.f.a' must not be deprecated")
        rejects("type Query { x(i: In): Int } input In { a: Int! @deprecated }", "Required input field 'In.a' must not be deprecated")
        build("type Query { f(a: Int @deprecated): Int }") // nullable + deprecated is fine
        build("type Query { f(a: Int! = 1 @deprecated): Int }") // required-with-default + deprecated is fine
    }

    @Test
    fun `a Float default value must be finite`() {
        build("type Query { f(x: Float = 1.5): Int }") // finite float
        build("type Query { f(x: Float = 5): Int }") // an Int coerces to Float
        rejects("type Query { f(x: Float = 1e400): Int }", "not a finite Float")
        rejects("type Query { f(x: Float = \"no\"): Int }", "expected a Float")
    }

    @Test
    fun `a directive may be applied at every declared location`() {
        build(
            """
            directive @x on OBJECT | INTERFACE | UNION | SCALAR | ENUM | ENUM_VALUE | INPUT_OBJECT | INPUT_FIELD_DEFINITION | FIELD_DEFINITION | ARGUMENT_DEFINITION
            type Query @x { f(a: Int @x): Int @x s: S i: I u: U e: E inp(g: In): Int }
            interface I @x { f: Int }
            type Impl implements I @x { f: Int }
            union U @x = Impl
            scalar S @x
            enum E @x { V @x }
            input In @x { g: Int @x }
            """.trimIndent(),
        )
    }
}
