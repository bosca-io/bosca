package bosca.graphql.language

/** A query/mutation/subscription operation (named or shorthand). */
data class OperationDefinition(
    val operation: OperationType,
    val name: String?,
    val variableDefinitions: List<VariableDefinition>,
    val directives: List<Directive>,
    val selectionSet: SelectionSet,
    override val location: SourceLocation? = null,
) : ExecutableDefinition

/** A named fragment definition (`fragment Name on Type { … }`). */
data class FragmentDefinition(
    val name: String,
    val typeCondition: NamedType,
    val directives: List<Directive>,
    val selectionSet: SelectionSet,
    override val location: SourceLocation? = null,
) : ExecutableDefinition

/** A variable declaration in an operation's variable definitions (`$x: Int = 0`). */
data class VariableDefinition(
    val variable: Variable,
    val type: Type,
    val defaultValue: Value?,
    val directives: List<Directive>,
    override val location: SourceLocation? = null,
) : Node
