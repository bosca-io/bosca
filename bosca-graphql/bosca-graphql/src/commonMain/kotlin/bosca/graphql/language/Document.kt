package bosca.graphql.language

/** A parsed GraphQL document: a list of executable and/or type-system [definitions][Definition]. */
data class Document(val definitions: List<Definition>, override val location: SourceLocation? = null) : Node

/** Marker for any top-level definition. */
sealed interface Definition : Node

/** An executable definition: an operation or a fragment. */
sealed interface ExecutableDefinition : Definition

/** A type-system (SDL) definition: schema, a named type, or a directive. */
sealed interface TypeSystemDefinition : Definition

/** A type-system extension: `extend …`. */
sealed interface TypeSystemExtension : Definition

/** The three GraphQL operation kinds. */
enum class OperationType { QUERY, MUTATION, SUBSCRIPTION }
