package bosca.graphql.server

import bosca.graphql.language.Document
import bosca.graphql.language.Field
import bosca.graphql.language.FragmentDefinition
import bosca.graphql.language.FragmentSpread
import bosca.graphql.language.InlineFragment
import bosca.graphql.language.OperationDefinition
import bosca.graphql.language.SelectionSet
import bosca.graphql.parser.ParserLimits

/**
 * Structural work limits applied to every untrusted GraphQL request before semantic validation.
 *
 * These bounds protect parser recursion and validation algorithms independently of operation depth/complexity,
 * which are evaluated later against the selected operation and its variables. Defaults are intentionally generous
 * abuse backstops; deployments with tighter resource budgets can supply smaller limits explicitly.
 */
data class GraphQLRequestLimits(
    val maxQueryCharacters: Int = 8_000_000,
    val parserLimits: ParserLimits = ParserLimits.DEFAULT,
    val maxDefinitions: Int = 10_000,
    val maxSelections: Int = 250_000,
    val maxSelectionSetWidth: Int = 100_000,
    val maxSelectionDepth: Int = 256,
    val maxFragmentSpreads: Int = 100_000,
) {
    init {
        require(maxQueryCharacters > 0) { "maxQueryCharacters must be positive" }
        require(maxDefinitions > 0) { "maxDefinitions must be positive" }
        require(maxSelections > 0) { "maxSelections must be positive" }
        require(maxSelectionSetWidth > 0) { "maxSelectionSetWidth must be positive" }
        require(maxSelectionDepth > 0) { "maxSelectionDepth must be positive" }
        require(maxFragmentSpreads > 0) { "maxFragmentSpreads must be positive" }
    }

    companion object {
        val DEFAULT = GraphQLRequestLimits()
    }
}

/** Returns the first structural-budget failure, before the more expensive schema validator runs. */
internal fun Document.limitError(limits: GraphQLRequestLimits): GraphQLError? {
    if (definitions.size > limits.maxDefinitions) {
        return GraphQLError("Document exceeds the maximum definition count of ${limits.maxDefinitions}")
    }

    val pending = ArrayDeque<Pair<SelectionSet, Int>>()
    definitions.forEach { definition ->
        when (definition) {
            is OperationDefinition -> pending.addLast(definition.selectionSet to 1)
            is FragmentDefinition -> pending.addLast(definition.selectionSet to 1)
            else -> Unit
        }
    }

    var selections = 0
    var fragmentSpreads = 0
    while (pending.isNotEmpty()) {
        val (selectionSet, depth) = pending.removeFirst()
        if (depth > limits.maxSelectionDepth) {
            return GraphQLError("Document exceeds the maximum selection depth of ${limits.maxSelectionDepth}")
        }
        if (selectionSet.selections.size > limits.maxSelectionSetWidth) {
            return GraphQLError("Selection set exceeds the maximum width of ${limits.maxSelectionSetWidth}")
        }

        selections += selectionSet.selections.size
        if (selections > limits.maxSelections) {
            return GraphQLError("Document exceeds the maximum selection count of ${limits.maxSelections}")
        }

        selectionSet.selections.forEach { selection ->
            when (selection) {
                is Field -> selection.selectionSet?.let { pending.addLast(it to depth + 1) }
                is InlineFragment -> pending.addLast(selection.selectionSet to depth)
                is FragmentSpread -> {
                    fragmentSpreads++
                    if (fragmentSpreads > limits.maxFragmentSpreads) {
                        return GraphQLError("Document exceeds the maximum fragment-spread count of ${limits.maxFragmentSpreads}")
                    }
                }
            }
        }
    }
    return null
}
