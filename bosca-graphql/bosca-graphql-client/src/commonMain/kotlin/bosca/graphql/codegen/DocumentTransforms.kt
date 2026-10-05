package bosca.graphql.codegen

import bosca.graphql.language.Document
import bosca.graphql.language.Field
import bosca.graphql.language.FragmentSpread
import bosca.graphql.language.InlineFragment
import bosca.graphql.language.SelectionSet
import bosca.graphql.language.transform

/**
 * Returns a copy of this document with `__typename` injected at the front of every selection set that contains
 * a fragment spread or inline fragment and doesn't already select it. The generated typed client needs the
 * discriminator on the wire to decode polymorphic selections into the right subtype. This is a codegen concern,
 * kept out of the (faithful) AST printer.
 *
 * Built on the foundation's [transform]: the traversal is handled there, so this only states the
 * per-selection-set rule. Untouched selection sets keep their identity.
 */
internal fun Document.withInjectedTypenames(): Document =
    transform { node -> if (node is SelectionSet) node.withTypenameIfPolymorphic() else node } as Document

private fun SelectionSet.withTypenameIfPolymorphic(): SelectionSet {
    val hasFragments = selections.any { it is InlineFragment || it is FragmentSpread }
    val hasTypename = selections.any { it is Field && it.name == "__typename" }
    return if (hasFragments && !hasTypename) SelectionSet(listOf(typenameField()) + selections) else this
}

private fun typenameField(): Field =
    Field(alias = null, name = "__typename", arguments = emptyList(), directives = emptyList(), selectionSet = null)
