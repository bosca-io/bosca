package bosca.graphql.language

/** `extend schema …`. */
data class SchemaExtension(
    val directives: List<Directive>,
    val operationTypes: List<OperationTypeDefinition>,
    override val location: SourceLocation? = null,
) : TypeSystemExtension

/** `extend scalar Name …`. */
data class ScalarTypeExtension(
    val name: String,
    val directives: List<Directive>,
    override val location: SourceLocation? = null,
) : TypeSystemExtension

/** `extend type Name …`. */
data class ObjectTypeExtension(
    val name: String,
    val interfaces: List<NamedType>,
    val directives: List<Directive>,
    val fields: List<FieldDefinition>,
    override val location: SourceLocation? = null,
) : TypeSystemExtension

/** `extend interface Name …`. */
data class InterfaceTypeExtension(
    val name: String,
    val interfaces: List<NamedType>,
    val directives: List<Directive>,
    val fields: List<FieldDefinition>,
    override val location: SourceLocation? = null,
) : TypeSystemExtension

/** `extend union Name …`. */
data class UnionTypeExtension(
    val name: String,
    val directives: List<Directive>,
    val types: List<NamedType>,
    override val location: SourceLocation? = null,
) : TypeSystemExtension

/** `extend enum Name …`. */
data class EnumTypeExtension(
    val name: String,
    val directives: List<Directive>,
    val values: List<EnumValueDefinition>,
    override val location: SourceLocation? = null,
) : TypeSystemExtension

/** `extend input Name …`. */
data class InputObjectTypeExtension(
    val name: String,
    val directives: List<Directive>,
    val fields: List<InputValueDefinition>,
    override val location: SourceLocation? = null,
) : TypeSystemExtension
