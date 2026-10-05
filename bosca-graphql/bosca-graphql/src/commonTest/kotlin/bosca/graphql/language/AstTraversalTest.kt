package bosca.graphql.language

import bosca.graphql.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Exercises the AST traversal/transform API: [preOrder] visits every node kind, [children] yields
 * only direct children, and [transform] produces a structurally-new tree while preserving the identity of
 * untouched subtrees.
 */
class AstTraversalTest {

    // A document touching every node kind (executable + type-system + extensions, all value/type wrappers).
    private val comprehensive = Parser.parse(
        """
        query Q(${'$'}v: [Int!] = [1], ${'$'}w: String @vd) {
          a: f(i: 1, fl: 1.5, s: "x", b: true, n: null, e: E1, l: [1], o: {k: ${'$'}v}) @fd(x: 1) { x }
          ...Frag @sp
          ... on T { y }
          ... { lone }
        }
        fragment Frag on N { z @zd }
        schema @sd { query: Query }
        scalar DT @scd
        type O implements I @od { fld(arg: Int = 0 @ad): [O!]! @fldd }
        interface I @id2 { ifld: Int }
        union U @ud = O | P
        type P { p: Int }
        enum E @ed { E1 @evd E2 }
        input In @ind { inf: Int = 1 @ifd }
        directive @dir(a: Int) repeatable on FIELD
        extend schema @se { mutation: M }
        extend scalar DT @sce
        extend type O @oe { extra: Int }
        extend interface I @ie { extra2: Int }
        extend union U @ue = P
        extend enum E @ee { E3 }
        extend input In @ine { g: Int }
        """.trimIndent(),
    )

    @Test
    fun `preOrder visits every node kind`() {
        val kinds = comprehensive.preOrder().mapNotNull { it::class.simpleName }.toSet()
        val expected = listOf(
            "Document", "OperationDefinition", "FragmentDefinition", "VariableDefinition", "SelectionSet",
            "Field", "FragmentSpread", "InlineFragment", "Argument", "Directive",
            "Variable", "IntValue", "FloatValue", "StringValue", "BooleanValue", "NullValue", "EnumValue",
            "ListValue", "ObjectValue", "ObjectField", "NamedType", "ListType", "NonNullType",
            "SchemaDefinition", "OperationTypeDefinition", "ScalarTypeDefinition", "ObjectTypeDefinition",
            "InterfaceTypeDefinition", "UnionTypeDefinition", "EnumTypeDefinition", "InputObjectTypeDefinition",
            "DirectiveDefinition", "FieldDefinition", "InputValueDefinition", "EnumValueDefinition",
            "SchemaExtension", "ScalarTypeExtension", "ObjectTypeExtension", "InterfaceTypeExtension",
            "UnionTypeExtension", "EnumTypeExtension", "InputObjectTypeExtension",
        )
        val missing = expected.filter { it !in kinds }
        assertTrue(missing.isEmpty(), "preOrder did not visit: $missing")
    }

    @Test
    fun `children yields only direct children`() {
        val operation = Parser.parse("{ a(x: 1) { b } }").definitions.single() as OperationDefinition
        val field = operation.selectionSet.selections.single() as Field
        val children = field.children()
        assertTrue(children.any { it is Argument }, "expected the argument as a direct child")
        assertTrue(children.any { it is SelectionSet }, "expected the sub-selection set as a direct child")
        assertTrue(children.none { it is Field }, "the grandchild field 'b' must not be a direct child")
        assertTrue(children.none { it is IntValue }, "the argument's value is a grandchild, not a direct child")
    }

    @Test
    fun `transform with identity returns the same instance (nothing reallocated)`() {
        assertSame(comprehensive, comprehensive.transform { it })
    }

    @Test
    fun `transform preserves identity of untouched subtrees`() {
        val document = Parser.parse("query A { f(x: 1) } query B { g }")
        val out = document.transform { if (it is IntValue) IntValue("99") else it } as Document

        // Operation B contains no IntValue → returned by reference; operation A is rebuilt.
        assertSame(document.definitions[1], out.definitions[1])
        assertNotSame(document.definitions[0], out.definitions[0])
        assertEquals("99", out.preOrder().filterIsInstance<IntValue>().single().value)
        // original is untouched (immutability)
        assertEquals("1", document.preOrder().filterIsInstance<IntValue>().single().value)
    }

    @Test
    fun `transform rebuilds a new tree across all node kinds`() {
        val out = comprehensive.transform { node ->
            when (node) {
                is NamedType -> node.copy(name = node.name + "X")
                is Variable -> node.copy(name = node.name + "X")
                is EnumValue -> node.copy(value = node.value + "X")
                is Directive -> node.copy(name = node.name + "X")
                is EnumValueDefinition -> node.copy(name = node.name + "X")
                is FieldDefinition -> node.copy(name = node.name + "X")
                is InputValueDefinition -> node.copy(name = node.name + "X")
                is IntValue -> IntValue((node.value.toInt() + 1).toString())
                is FloatValue -> node.copy(value = "9.9")
                is StringValue -> node.copy(value = node.value + "X")
                is BooleanValue -> node.copy(value = !node.value)
                else -> node
            }
        } as Document

        assertNotSame(comprehensive, out)
        // representative rewrites reached deep nodes through every container
        assertTrue(out.preOrder().filterIsInstance<NamedType>().all { it.name.endsWith("X") })
        assertTrue(out.preOrder().filterIsInstance<Directive>().all { it.name.endsWith("X") })
        assertEquals("2", out.preOrder().filterIsInstance<IntValue>().first().value)
        // the original is unchanged
        assertTrue(comprehensive.preOrder().filterIsInstance<NamedType>().none { it.name.endsWith("X") })
    }

    @Test
    fun `transform rebuilds every type-system definition and extension when a child changes`() {
        fun rebuilds(source: String): Boolean {
            val document = Parser.parse(source)
            val out = document.transform { node ->
                when (node) {
                    is NamedType -> node.copy(name = node.name + "X")
                    is EnumValueDefinition -> node.copy(name = node.name + "X")
                    is InputValueDefinition -> node.copy(name = node.name + "X")
                    else -> node
                }
            } as Document
            return out !== document && out.definitions.single() !== document.definitions.single()
        }
        assertTrue(rebuilds("schema { query: Query }"))   // SchemaDefinition (operationTypes)
        assertTrue(rebuilds("union U = A | B"))           // UnionTypeDefinition (members)
        assertTrue(rebuilds("enum E { A B }"))            // EnumTypeDefinition (values)
        assertTrue(rebuilds("input I { f: Int }"))        // InputObjectTypeDefinition (fields)
        assertTrue(rebuilds("extend schema { query: Q }"))
        assertTrue(rebuilds("extend union U = A"))
        assertTrue(rebuilds("extend enum E { A }"))
    }
}
