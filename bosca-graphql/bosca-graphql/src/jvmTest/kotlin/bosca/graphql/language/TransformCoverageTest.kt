package bosca.graphql.language

import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Exhaustive coverage of [transform]'s identity-preservation in [replaceChildren] for every multi-child node:
 * a no-op rewrite returns the node by reference, and changing each child slot independently forces a copy. Targets
 * children by reference so exactly one slot changes per case, exercising each operand of the identity check.
 */
class TransformCoverageTest {

    /** Replace [target] (by reference) with [replacement] wherever it appears. */
    private fun swap(target: Node, replacement: Node): (Node) -> Node = { if (it === target) replacement else it }

    /** A no-op rewrite returns the same instance; each per-child rewrite returns a different instance. */
    private fun assertIdentityThenCopies(node: Node, vararg childChanges: (Node) -> Node) {
        assertSame(node, node.transform { it }, "a no-op transform must return the same node by reference")
        childChanges.forEachIndexed { index, change ->
            assertNotSame(node, node.transform(change), "changing child slot #$index must produce a copy")
        }
    }

    private val field = Field(null, "f", emptyList(), emptyList(), null)
    private val selectionSet = SelectionSet(listOf(field))
    private val variable = Variable("v")
    private val variableDefinition = VariableDefinition(variable, NamedType("Int"), IntValue("1"), emptyList())
    private val directive = Directive("d", emptyList())
    private val namedType = NamedType("T")
    private val fieldArg = InputValueDefinition(null, "a", NamedType("Int"), IntValue("0"), emptyList())
    private val fieldDef = FieldDefinition(null, "g", listOf(fieldArg), NamedType("String"), emptyList())
    private val iface = NamedType("Node")

    @Test
    fun `OperationDefinition copies on each child change`() {
        val node = OperationDefinition(OperationType.QUERY, "Q", listOf(variableDefinition), listOf(directive), selectionSet)
        assertIdentityThenCopies(
            node,
            swap(variable, Variable("v2")), // variableDefinitions
            swap(directive, Directive("d2", emptyList())), // directives
            swap(field, field.copy(name = "f2")), // selectionSet
        )
    }

    @Test
    fun `FragmentDefinition copies on each child change`() {
        val node = FragmentDefinition("F", namedType, listOf(directive), selectionSet)
        assertIdentityThenCopies(
            node,
            swap(namedType, NamedType("U")), // typeCondition
            swap(directive, Directive("d2", emptyList())), // directives
            swap(field, field.copy(name = "f2")), // selectionSet
        )
    }

    @Test
    fun `VariableDefinition copies on each child change`() {
        val type = NamedType("Int")
        val default = IntValue("1")
        val node = VariableDefinition(variable, type, default, listOf(directive))
        assertIdentityThenCopies(
            node,
            swap(variable, Variable("v2")), // variable
            swap(type, NamedType("Float")), // type
            swap(default, IntValue("2")), // defaultValue
            swap(directive, Directive("d2", emptyList())), // directives
        )
    }

    @Test
    fun `Field copies on each child change`() {
        val argument = Argument("x", IntValue("1"))
        val node = Field("alias", "f", listOf(argument), listOf(directive), selectionSet)
        assertIdentityThenCopies(
            node,
            swap(argument, Argument("y", IntValue("1"))), // arguments
            swap(directive, Directive("d2", emptyList())), // directives
            swap(field, field.copy(name = "f2")), // selectionSet
        )
    }

    @Test
    fun `InlineFragment copies on each child change`() {
        val node = InlineFragment(namedType, listOf(directive), selectionSet)
        assertIdentityThenCopies(
            node,
            swap(namedType, NamedType("U")), // typeCondition
            swap(directive, Directive("d2", emptyList())), // directives
            swap(field, field.copy(name = "f2")), // selectionSet
        )
        // A type-condition-less inline fragment (the null branch of the optional child).
        val anon = InlineFragment(null, listOf(directive), selectionSet)
        assertSame(anon, anon.transform { it })
        assertNotSame(anon, anon.transform(swap(field, field.copy(name = "f2"))))
    }

    @Test
    fun `ObjectTypeDefinition copies on each child change`() {
        val node = ObjectTypeDefinition(null, "T", listOf(iface), listOf(directive), listOf(fieldDef))
        assertIdentityThenCopies(
            node,
            swap(iface, NamedType("Other")), // interfaces
            swap(directive, Directive("d2", emptyList())), // directives
            swap(fieldDef, fieldDef.copy(name = "g2")), // fields
        )
    }

    @Test
    fun `InterfaceTypeDefinition copies on each child change`() {
        val node = InterfaceTypeDefinition(null, "I", listOf(iface), listOf(directive), listOf(fieldDef))
        assertIdentityThenCopies(
            node,
            swap(iface, NamedType("Other")),
            swap(directive, Directive("d2", emptyList())),
            swap(fieldDef, fieldDef.copy(name = "g2")),
        )
    }

    @Test
    fun `FieldDefinition copies on each child change`() {
        val type = NamedType("String")
        val node = FieldDefinition(null, "g", listOf(fieldArg), type, listOf(directive))
        assertIdentityThenCopies(
            node,
            swap(fieldArg, fieldArg.copy(name = "b")), // arguments
            swap(type, NamedType("Int")), // type
            swap(directive, Directive("d2", emptyList())), // directives
        )
    }

    @Test
    fun `InputValueDefinition copies on each child change`() {
        val type = NamedType("Int")
        val default = IntValue("0")
        val node = InputValueDefinition(null, "a", type, default, listOf(directive))
        assertIdentityThenCopies(
            node,
            swap(type, NamedType("Float")), // type
            swap(default, IntValue("9")), // defaultValue
            swap(directive, Directive("d2", emptyList())), // directives
        )
    }

    @Test
    fun `ObjectTypeExtension copies on each child change`() {
        val node = ObjectTypeExtension("T", listOf(iface), listOf(directive), listOf(fieldDef))
        assertIdentityThenCopies(
            node,
            swap(iface, NamedType("Other")),
            swap(directive, Directive("d2", emptyList())),
            swap(fieldDef, fieldDef.copy(name = "g2")),
        )
    }

    @Test
    fun `InterfaceTypeExtension copies on each child change`() {
        val node = InterfaceTypeExtension("I", listOf(iface), listOf(directive), listOf(fieldDef))
        assertIdentityThenCopies(
            node,
            swap(iface, NamedType("Other")),
            swap(directive, Directive("d2", emptyList())),
            swap(fieldDef, fieldDef.copy(name = "g2")),
        )
    }

    @Test
    fun `optional children left null stay null`() {
        // a field with no sub-selection: the optional selectionSet child is null on both read and rebuild
        val leaf = Field(null, "x", emptyList(), emptyList(), null)
        assertSame(leaf, leaf.transform { it })
        assertTrue(leaf.children().isEmpty())
    }
}
