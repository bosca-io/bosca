package bosca.graphql.server

import bosca.graphql.language.BooleanValue
import bosca.graphql.language.Directive
import bosca.graphql.language.Document
import bosca.graphql.language.Field
import bosca.graphql.language.FragmentDefinition
import bosca.graphql.language.FragmentSpread
import bosca.graphql.language.InlineFragment
import bosca.graphql.language.OperationDefinition
import bosca.graphql.language.Selection
import bosca.graphql.language.Variable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Static query-cost analysis: the depth and complexity of an operation, computed by walking its
 * selection tree with fragment spreads/inline fragments expanded and `@skip`/`@include` honored. Used by the
 * [MaxQueryDepthInstrumentation] / [MaxQueryComplexityInstrumentation] limits to reject abusive queries before
 * execution. The leaf `__typename` meta-field is free; introspection roots and all of their children are counted.
 */
object QueryComplexity {

    /** The maximum field-nesting depth of [operation]. `{ a }` is 1, `{ a { b } }` is 2. */
    fun depth(operation: OperationDefinition, fragments: Map<String, FragmentDefinition>, variables: Map<String, Any?>): Int =
        DepthAnalyzer(fragments, variables, Int.MAX_VALUE).selections(operation.selectionSet.selections)

    /** The total complexity of [operation], summing each field via [calculator] (default: `1 + childComplexity`). */
    fun complexity(
        operation: OperationDefinition,
        fragments: Map<String, FragmentDefinition>,
        variables: Map<String, Any?>,
        calculator: FieldComplexityCalculator = FieldComplexityCalculator.Default,
    ): Int = ComplexityAnalyzer(fragments, variables, calculator, Int.MAX_VALUE).selections(operation.selectionSet.selections)

    /** Limit-aware depth evaluation used by request instrumentation; returns at most `maximum + 1`. */
    internal fun depthUpTo(
        operation: OperationDefinition,
        fragments: Map<String, FragmentDefinition>,
        variables: Map<String, Any?>,
        maximum: Int,
    ): Int = DepthAnalyzer(fragments, variables, capAbove(maximum)).selections(operation.selectionSet.selections)

    /** Limit-aware complexity evaluation used by request instrumentation; returns at most `maximum + 1`. */
    internal fun complexityUpTo(
        operation: OperationDefinition,
        fragments: Map<String, FragmentDefinition>,
        variables: Map<String, Any?>,
        calculator: FieldComplexityCalculator,
        maximum: Int,
    ): Int = ComplexityAnalyzer(fragments, variables, calculator, capAbove(maximum)).selections(operation.selectionSet.selections)

    /**
     * Memoizes each fragment's depth so an acyclic fragment DAG is analyzed in linear time rather than expanded into
     * an exponential tree. [cap] also lets enforcement stop descending once the configured limit is known to fail.
     */
    private class DepthAnalyzer(
        private val fragments: Map<String, FragmentDefinition>,
        private val variables: Map<String, Any?>,
        private val cap: Int,
    ) {
        private val fragmentDepths = mutableMapOf<String, Int>()
        private val activeFragments = mutableSetOf<String>()

        fun selections(selections: List<Selection>): Int {
            var maximum = 0
            for (selection in selections) {
                if (!included(selection, variables)) continue
                val depth = when (selection) {
                    is Field -> {
                        if (selection.name == "__typename") {
                            0
                        } else {
                            val child = selection.selectionSet?.let { selections(it.selections) } ?: 0
                            increment(child, cap)
                        }
                    }
                    is FragmentSpread -> fragment(selection.name)
                    is InlineFragment -> selections(selection.selectionSet.selections)
                }
                maximum = maxOf(maximum, depth)
                if (maximum >= cap) return cap
            }
            return maximum
        }

        private fun fragment(name: String): Int {
            fragmentDepths[name]?.let { return it }
            val fragment = fragments[name] ?: return 0
            if (!activeFragments.add(name)) return 0
            val depth = try {
                selections(fragment.selectionSet.selections)
            } finally {
                activeFragments.remove(name)
            }
            fragmentDepths[name] = depth
            return depth
        }
    }

    /** Memoized, saturating complexity analysis; custom-calculator overflow is treated as maximum complexity. */
    private class ComplexityAnalyzer(
        private val fragments: Map<String, FragmentDefinition>,
        private val variables: Map<String, Any?>,
        private val calculator: FieldComplexityCalculator,
        private val cap: Int,
    ) {
        private val fragmentComplexities = mutableMapOf<String, Int>()
        private val activeFragments = mutableSetOf<String>()

        fun selections(selections: List<Selection>): Int {
            var total = 0
            for (selection in selections) {
                if (!included(selection, variables)) continue
                val contribution = when (selection) {
                    is Field -> {
                        if (selection.name == "__typename") {
                            0
                        } else {
                            val child = selection.selectionSet?.let { selections(it.selections) } ?: 0
                            calculator.calculate(selection, variables, child).let { calculated ->
                                if (calculated < 0) cap else minOf(calculated, cap)
                            }
                        }
                    }
                    is FragmentSpread -> fragment(selection.name)
                    is InlineFragment -> selections(selection.selectionSet.selections)
                }
                total = add(total, contribution, cap)
                if (total >= cap) return cap
            }
            return total
        }

        private fun fragment(name: String): Int {
            fragmentComplexities[name]?.let { return it }
            val fragment = fragments[name] ?: return 0
            if (!activeFragments.add(name)) return 0
            val complexity = try {
                selections(fragment.selectionSet.selections)
            } finally {
                activeFragments.remove(name)
            }
            fragmentComplexities[name] = complexity
            return complexity
        }
    }

    private fun included(selection: Selection, variables: Map<String, Any?>): Boolean {
        val directives = when (selection) {
            is Field -> selection.directives
            is FragmentSpread -> selection.directives
            is InlineFragment -> selection.directives
        }
        val skip = directives.firstOrNull { it.name == "skip" }
        if (skip != null && ifArgument(skip, variables) == true) return false
        val include = directives.firstOrNull { it.name == "include" }
        if (include != null && ifArgument(include, variables) == false) return false
        return true
    }

    private fun ifArgument(directive: Directive, variables: Map<String, Any?>): Boolean? =
        when (val value = directive.arguments.firstOrNull { it.name == "if" }?.value) {
            is BooleanValue -> value.value
            is Variable -> {
                val resolved = variables[value.name]
                when (resolved) {
                    is Boolean -> resolved
                    is JsonPrimitive -> resolved.booleanOrNull
                    else -> null
                }
            }
            else -> null
        }
}

/**
 * Computes the complexity contributed by one field given its already-computed [childComplexity] — the seam for
 * cost models that weight list fields by a pagination argument (graphql-java's `FieldComplexityCalculator`).
 */
fun interface FieldComplexityCalculator {
    fun calculate(field: Field, variables: Map<String, Any?>, childComplexity: Int): Int

    companion object {
        /** Each field costs 1 plus the cost of its sub-selection. */
        val Default = FieldComplexityCalculator { _, _, childComplexity -> 1 + childComplexity }
    }
}

/** Rejects operations whose nesting depth exceeds [maxDepth] before they execute. */
class MaxQueryDepthInstrumentation(private val maxDepth: Int) : SimpleInstrumentation() {
    init {
        require(maxDepth >= 0) { "maxDepth must not be negative" }
    }

    override suspend fun instrumentExecution(parameters: ExecutionParameters): List<GraphQLError> {
        val operation = parameters.operation() ?: return emptyList()
        val depth = QueryComplexity.depthUpTo(operation, parameters.fragments(), parameters.variables, maxDepth)
        return if (depth > maxDepth) {
            listOf(GraphQLError("Query depth $depth exceeds the maximum allowed depth of $maxDepth"))
        } else {
            emptyList()
        }
    }
}

/** Rejects operations whose [calculator]-computed complexity exceeds [maxComplexity] before they execute. */
class MaxQueryComplexityInstrumentation(
    private val maxComplexity: Int,
    private val calculator: FieldComplexityCalculator = FieldComplexityCalculator.Default,
) : SimpleInstrumentation() {
    init {
        require(maxComplexity >= 0) { "maxComplexity must not be negative" }
    }

    override suspend fun instrumentExecution(parameters: ExecutionParameters): List<GraphQLError> {
        val operation = parameters.operation() ?: return emptyList()
        val complexity = QueryComplexity.complexityUpTo(
            operation,
            parameters.fragments(),
            parameters.variables,
            calculator,
            maxComplexity,
        )
        return if (complexity > maxComplexity) {
            listOf(GraphQLError("Query complexity $complexity exceeds the maximum allowed complexity of $maxComplexity"))
        } else {
            emptyList()
        }
    }
}

/** The operation these parameters refer to (by name, or the single operation), or null if it can't be identified. */
private fun ExecutionParameters.operation(): OperationDefinition? {
    val operations = document.definitions.filterIsInstance<OperationDefinition>()
    return if (operationName != null) operations.firstOrNull { it.name == operationName } else operations.singleOrNull()
}

private fun ExecutionParameters.fragments(): Map<String, FragmentDefinition> =
    document.definitions.filterIsInstance<FragmentDefinition>().associateBy { it.name }

private fun capAbove(maximum: Int): Int = if (maximum == Int.MAX_VALUE) Int.MAX_VALUE else maximum + 1

private fun increment(value: Int, cap: Int): Int = if (value >= cap) cap else value + 1

private fun add(left: Int, right: Int, cap: Int): Int =
    if (left >= cap || right >= cap || left > cap - right) cap else left + right
