package bosca.graphql.codegen

import bosca.graphql.schema.GraphQLSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the fragment-anchored interface feature: each named fragment that is spread becomes an
 * `I<Fragment>` interface exposing all its fields — leaves directly and object fields as recursive nested
 * interfaces — and every selection that spreads it implements the interface with `override`s (object fields are
 * covariant overrides). So results from different operations spreading the same fragment unify under `I<Fragment>`.
 */
class InterfaceGenerationTest {

    private val schema = GraphQLSchema.fromSdl(
        """
        type Query { spec: Spec specByKey: Spec plain: Spec }
        type Spec { id: ID! key: String! status: Status! project: Project }
        type Status { name: String! category: String! }
        type Project { key: String! name: String! }
        """.trimIndent(),
    )

    private fun gen(vararg sources: String) = GraphQLCodegen(schema).generate(sources.toList(), "gen")

    private val specFields = "fragment SpecFields on Spec { id key status { name category } project { key name } }"

    @Test
    fun `a spread fragment generates an interface exposing leaves and object fields as nested interfaces`() {
        val iface = gen(specFields, "query GetSpec { spec { ...SpecFields } }").single { it.name == "ISpecFields.kt" }.content
        assertTrue("interface ISpecFields {" in iface, iface)
        assertTrue("val id: String" in iface && "val key: String" in iface, iface)
        assertTrue("val status: Status" in iface, iface)                      // object field → nested interface (in-scope name)
        assertTrue("val project: Project?" in iface, iface)                   // nullable object field
        // object-field interfaces are NESTED inside ISpecFields (so they can't collide with same-spelled fragments)
        assertTrue("    interface Status {" in iface && "val category: String" in iface, iface)
        assertTrue("    interface Project {" in iface, iface)
    }

    @Test
    fun `a data class spreading the fragment implements it with covariant nested overrides`() {
        val data = gen(specFields, "query GetSpec { spec { ...SpecFields } }").single { it.name == "GetSpec.kt" }.content
        assertTrue(") : ISpecFields {" in data, data)
        assertTrue("override val id: String," in data, data)
        assertTrue("override val status: Status," in data, data)              // covariant: Status : ISpecFields.Status
        assertTrue("override val project: Project?," in data, data)
        assertTrue("data class Status(" in data && ") : ISpecFields.Status" in data, data)
        assertTrue("data class Project(" in data && ") : ISpecFields.Project" in data, data)
    }

    @Test
    fun `two operations spreading the same fragment both implement the one interface`() {
        val files = gen(
            specFields,
            "query GetSpec { spec { ...SpecFields } }",
            "query GetSpecByKey { specByKey { ...SpecFields } }",
        )
        assertEquals(1, files.count { it.name == "ISpecFields.kt" }) // emitted exactly once
        assertTrue(") : ISpecFields {" in files.single { it.name == "GetSpec.kt" }.content)
        assertTrue(") : ISpecFields {" in files.single { it.name == "GetSpecByKey.kt" }.content)
    }

    @Test
    fun `a selection that spreads a fragment and adds an extra field overrides the fragment fields and keeps the extra plain`() {
        val s = GraphQLSchema.fromSdl(
            """
            type Query { spec: Spec }
            type Spec { id: ID! key: String! extra: String }
            """.trimIndent(),
        )
        val data = GraphQLCodegen(s).generate(
            listOf("fragment F on Spec { id key }", "query Q { spec { ...F extra } }"),
            "gen",
        ).single { it.name == "Q.kt" }.content
        assertTrue("override val id: String," in data && "override val key: String," in data, data) // from F
        assertTrue("val extra: String?," in data && "override val extra" !in data, data)            // extra is plain
    }

    @Test
    fun `an object field that spreads a fragment references that fragment's interface, not a synthetic nested one`() {
        // `project { __typename ...ProjectFields }` inside SpecFields: the child references the top-level
        // IProjectFields (so it can never collide with a synthetic I<Spec>Project), and no nested `Project`
        // interface is emitted inside ISpecFields. The `__typename` injected before the spread is ignored.
        val s = GraphQLSchema.fromSdl(
            """
            type Query { spec: Spec }
            type Spec { id: ID! project: Project }
            type Project { key: String! name: String! }
            """.trimIndent(),
        )
        val files = GraphQLCodegen(s).generate(
            listOf(
                "fragment ProjectFields on Project { key name }",
                "fragment SpecFields on Spec { id project { __typename ...ProjectFields } }",
                "query Q { spec { ...SpecFields } }",
            ),
            "gen",
        )
        val iface = files.single { it.name == "ISpecFields.kt" }.content
        assertTrue("val project: IProjectFields?" in iface, iface)   // references the fragment interface directly
        assertTrue("interface Project" !in iface, iface)             // no synthetic nested interface
        assertTrue(files.any { it.name == "IProjectFields.kt" })     // the referenced fragment interface is emitted
        // the implementer's nested type satisfies IProjectFields via its own spread (no cascade)
        val data = files.single { it.name == "Q.kt" }.content
        assertTrue("data class Project(" in data && ") : IProjectFields" in data, data)
    }

    @Test
    fun `a literal __typename in a fragment is not exposed as an interface field`() {
        val iface = gen("fragment F on Spec { __typename id key }", "query Q { spec { ...F } }")
            .single { it.name == "IF.kt" }.content
        assertTrue("val id: String" in iface && "val key: String" in iface, iface)
        assertTrue("__typename" !in iface, iface) // the meta-field is skipped, not a property
    }

    @Test
    fun `a polymorphic object field on a fragment is not exposed on its interface`() {
        val s = GraphQLSchema.fromSdl(
            """
            type Query { holder: Holder }
            type Holder { id: ID! node: Node }
            interface Node { id: ID! }
            type A implements Node { id: ID! a: String }
            type B implements Node { id: ID! b: String }
            """.trimIndent(),
        )
        val files = GraphQLCodegen(s).generate(
            listOf(
                "fragment HolderFields on Holder { id node { __typename ... on A { a } ... on B { b } } }",
                "query Q { holder { ...HolderFields } }",
            ),
            "gen",
        )
        val iface = files.single { it.name == "IHolderFields.kt" }.content
        assertTrue("val id: String" in iface, iface)
        assertTrue("node" !in iface, iface) // polymorphic field is kept off the interface (v1)
        // the implementer still models `node` as its own sealed type
        val data = files.single { it.name == "Q.kt" }.content
        assertTrue("sealed interface Node {" in data, data)
    }

    @Test
    fun `an operation that spreads no fragment produces no interface`() {
        val files = gen("query Plain { plain { id key } }")
        assertEquals(listOf("Plain.kt"), files.map { it.name })
    }

    @Test
    fun `an aliased fragment field is exposed and overridden by its alias`() {
        val files = gen("fragment F on Spec { renamed: key }", "query Q { spec { ...F } }")
        assertTrue("val renamed: String" in files.single { it.name == "IF.kt" }.content)
        assertTrue("override val renamed: String," in files.single { it.name == "Q.kt" }.content)
    }

    @Test
    fun `an enum leaf on a fragment interface needs no import`() {
        val s = GraphQLSchema.fromSdl(
            """
            type Query { e: E }
            type E { id: ID! kind: Kind }
            enum Kind { A B }
            """.trimIndent(),
        )
        val files = GraphQLCodegen(s).generate(listOf("fragment EF on E { id kind }", "query Q { e { ...EF } }"), "gen")
        val iface = files.single { it.name == "IEF.kt" }.content
        assertTrue("val kind: Kind?" in iface && "import" !in iface, iface) // enum is same-package → no import line
        assertTrue(files.any { it.name == "Kind.kt" }) // enum still hoisted
    }

    @Test
    fun `emitting an interface for an unknown fragment fails fast`() {
        kotlin.test.assertFailsWith<IllegalStateException> {
            KotlinClientGenerator(schema).emitFragmentInterfaceFile("DoesNotExist", "gen")
        }
    }

    @Test
    fun `a custom-scalar leaf on a fragment interface carries its import`() {
        val s = GraphQLSchema.fromSdl(
            """
            type Query { a: Event b: Event }
            type Event { id: ID! at: DateTime! }
            scalar DateTime
            """.trimIndent(),
        )
        val iface = GraphQLCodegen(s, mapOf("DateTime" to ScalarMapping("Instant", imports = setOf("kotlinx.datetime.Instant"))))
            .generate(listOf("fragment EventFields on Event { id at }", "query A { a { ...EventFields } }"), "gen")
            .single { it.name == "IEventFields.kt" }.content
        assertEquals(
            """
            package gen

            import kotlinx.datetime.Instant

            interface IEventFields {
                val id: String
                val at: Instant
            }
            """.trimIndent() + "\n",
            iface,
        )
    }
}
