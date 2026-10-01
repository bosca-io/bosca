package bosca.graphql.language

/** `schema { query: … mutation: … }`. */
data class SchemaDefinition(
    val description: String?,
    val directives: List<Directive>,
    val operationTypes: List<OperationTypeDefinition>,
    override val location: SourceLocation? = null,
) : TypeSystemDefinition

/** One `query: Query` entry inside a schema definition/extension. */
data class OperationTypeDefinition(
    val operation: OperationType,
    val type: NamedType,
    override val location: SourceLocation? = null,
) : Node

/** Common supertype of the six named type definitions (`scalar`/`type`/`interface`/`union`/`enum`/`input`). */
sealed interface TypeDefinition : TypeSystemDefinition {
    val name: String
    val description: String?
}

data class ScalarTypeDefinition(
    override val description: String?,
    override val name: String,
    val directives: List<Directive>,
    override val location: SourceLocation? = null,
) : TypeDefinition

data class ObjectTypeDefinition(
    override val description: String?,
    override val name: String,
    val interfaces: List<NamedType>,
    val directives: List<Directive>,
    val fields: List<FieldDefinition>,
    override val location: SourceLocation? = null,
) : TypeDefinition

data class InterfaceTypeDefinition(
    override val description: String?,
    override val name: String,
    val interfaces: List<NamedType>,
    val directives: List<Directive>,
    val fields: List<FieldDefinition>,
    override val location: SourceLocation? = null,
) : TypeDefinition

data class UnionTypeDefinition(
    override val description: String?,
    override val name: String,
    val directives: List<Directive>,
    val types: List<NamedType>,
    override val location: SourceLocation? = null,
) : TypeDefinition

data class EnumTypeDefinition(
    override val description: String?,
    override val name: String,
    val directives: List<Directive>,
    val values: List<EnumValueDefinition>,
    override val location: SourceLocation? = null,
) : TypeDefinition

data class InputObjectTypeDefinition(
    override val description: String?,
    override val name: String,
    val directives: List<Directive>,
    val fields: List<InputValueDefinition>,
    override val location: SourceLocation? = null,
) : TypeDefinition

/** `directive @name(args) [repeatable] on LOCATIONS`. */
data class DirectiveDefinition(
    val description: String?,
    val name: String,
    val arguments: List<InputValueDefinition>,
    val repeatable: Boolean,
    val locations: List<String>,
    override val location: SourceLocation? = null,
) : TypeSystemDefinition

/** A field on an object or interface type. */
data class FieldDefinition(
    val description: String?,
    val name: String,
    val arguments: List<InputValueDefinition>,
    val type: Type,
    val directives: List<Directive>,
    override val location: SourceLocation? = null,
) : Node

/** A field argument, or a field on an input object type. */
data class InputValueDefinition(
    val description: String?,
    val name: String,
    val type: Type,
    val defaultValue: Value?,
    val directives: List<Directive>,
    override val location: SourceLocation? = null,
) : Node

/** A single value of an enum type. */
data class EnumValueDefinition(
    val description: String?,
    val name: String,
    val directives: List<Directive>,
    override val location: SourceLocation? = null,
) : Node
