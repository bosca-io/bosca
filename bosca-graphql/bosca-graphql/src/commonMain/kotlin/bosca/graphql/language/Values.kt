package bosca.graphql.language

/** A GraphQL input value (literal or variable reference). */
sealed interface Value : Node

data class Variable(val name: String, override val location: SourceLocation? = null) : Value
data class IntValue(val value: String, override val location: SourceLocation? = null) : Value
data class FloatValue(val value: String, override val location: SourceLocation? = null) : Value
data class StringValue(val value: String, val block: Boolean = false, override val location: SourceLocation? = null) : Value
data class BooleanValue(val value: Boolean, override val location: SourceLocation? = null) : Value
data class NullValue(override val location: SourceLocation? = null) : Value
data class EnumValue(val value: String, override val location: SourceLocation? = null) : Value
data class ListValue(val values: List<Value>, override val location: SourceLocation? = null) : Value
data class ObjectValue(val fields: List<ObjectField>, override val location: SourceLocation? = null) : Value

/** A `name: value` entry in an [ObjectValue]. */
data class ObjectField(val name: String, val value: Value, override val location: SourceLocation? = null) : Node
