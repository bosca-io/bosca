package bosca.graphql.language

/** A `{ … }` set of selections. */
data class SelectionSet(val selections: List<Selection>, override val location: SourceLocation? = null) : Node

/** One entry in a selection set: a field, a fragment spread, or an inline fragment. */
sealed interface Selection : Node

/** A field selection, optionally aliased, with arguments, directives, and a sub-selection. */
data class Field(
    val alias: String?,
    val name: String,
    val arguments: List<Argument>,
    val directives: List<Directive>,
    val selectionSet: SelectionSet?,
    override val location: SourceLocation? = null,
) : Selection

/** A `...FragmentName` spread. */
data class FragmentSpread(
    val name: String,
    val directives: List<Directive>,
    override val location: SourceLocation? = null,
) : Selection

/** An inline fragment (`... on Type { … }` or `... @dir { … }`). */
data class InlineFragment(
    val typeCondition: NamedType?,
    val directives: List<Directive>,
    val selectionSet: SelectionSet,
    override val location: SourceLocation? = null,
) : Selection

/** A `name: value` argument. */
data class Argument(val name: String, val value: Value, override val location: SourceLocation? = null) : Node

/** A `@name(args…)` directive application. */
data class Directive(
    val name: String,
    val arguments: List<Argument>,
    override val location: SourceLocation? = null,
) : Node
