package bosca.graphql.language

/** A type reference: a named type, a list type, or a non-null wrapper. */
sealed interface Type : Node

data class NamedType(val name: String, override val location: SourceLocation? = null) : Type
data class ListType(val type: Type, override val location: SourceLocation? = null) : Type
data class NonNullType(val type: Type, override val location: SourceLocation? = null) : Type
