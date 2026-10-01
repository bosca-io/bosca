package bosca.graphql.schema

import bosca.graphql.language.Document
import bosca.graphql.language.EnumTypeDefinition
import bosca.graphql.language.FieldDefinition
import bosca.graphql.language.InputObjectTypeDefinition
import bosca.graphql.language.InterfaceTypeDefinition
import bosca.graphql.language.ListType
import bosca.graphql.language.NamedType
import bosca.graphql.language.NonNullType
import bosca.graphql.language.ObjectTypeDefinition
import bosca.graphql.language.OperationType
import bosca.graphql.language.ScalarTypeDefinition
import bosca.graphql.language.Type
import bosca.graphql.language.TypeDefinition
import bosca.graphql.language.UnionTypeDefinition
import bosca.graphql.language.DirectiveDefinition
import bosca.graphql.parser.Parser

/** Peels NonNull/List wrappers off a type reference to reach its underlying named-type name. */
fun underlyingTypeName(type: Type): String = when (type) {
    is NamedType -> type.name
    is NonNullType -> underlyingTypeName(type.type)
    is ListType -> underlyingTypeName(type.type)
}

/**
 * An assembled GraphQL type system: every named type (including the five built-in scalars) and directive,
 * with `extend` extensions merged in and the root operation types resolved. Build it from SDL via [fromSdl]
 * or a parsed document via [fromDocument]. This is the substrate the typed-client generator and (later)
 * validation + execution build on; it reuses the [bosca.graphql.language] AST nodes as its type model.
 */
class GraphQLSchema internal constructor(
    val types: Map<String, TypeDefinition>,
    val directives: Map<String, DirectiveDefinition>,
    val queryTypeName: String?,
    val mutationTypeName: String?,
    val subscriptionTypeName: String?,
    val description: String? = null,
) {
    private val fieldsByName: Map<String, Map<String, FieldDefinition>> =
        types.mapNotNull { (typeName, type) ->
            val fields = when (type) {
                is ObjectTypeDefinition -> type.fields
                is InterfaceTypeDefinition -> type.fields
                else -> return@mapNotNull null
            }
            typeName to fields.associateBy { it.name }
        }.toMap()

    val queryType: ObjectTypeDefinition? get() = queryTypeName?.let { types[it] as? ObjectTypeDefinition }
    val mutationType: ObjectTypeDefinition? get() = mutationTypeName?.let { types[it] as? ObjectTypeDefinition }
    val subscriptionType: ObjectTypeDefinition? get() = subscriptionTypeName?.let { types[it] as? ObjectTypeDefinition }

    fun type(name: String): TypeDefinition? = types[name]

    fun rootType(operation: OperationType): ObjectTypeDefinition? = when (operation) {
        OperationType.QUERY -> queryType
        OperationType.MUTATION -> mutationType
        OperationType.SUBSCRIPTION -> subscriptionType
    }

    /** Output fields of an object or interface type, or null for any other (or unknown) type. */
    fun fields(typeName: String): List<FieldDefinition>? = when (val t = types[typeName]) {
        is ObjectTypeDefinition -> t.fields
        is InterfaceTypeDefinition -> t.fields
        else -> null
    }

    fun field(typeName: String, fieldName: String): FieldDefinition? =
        fieldsByName[typeName]?.get(fieldName)

    fun isBuiltInScalar(name: String): Boolean = name in BUILT_IN_SCALARS

    /** A type usable as an input (scalar, enum, or input object). */
    fun isInputType(name: String): Boolean = when (types[name]) {
        is ScalarTypeDefinition, is EnumTypeDefinition, is InputObjectTypeDefinition -> true
        else -> false
    }

    /** A type usable as an output (scalar, enum, object, interface, or union). */
    fun isOutputType(name: String): Boolean = when (types[name]) {
        is ScalarTypeDefinition, is EnumTypeDefinition, is ObjectTypeDefinition,
        is InterfaceTypeDefinition, is UnionTypeDefinition,
        -> true

        else -> false
    }

    /** The concrete object types a union (its members) or interface (its implementors) can resolve to. */
    fun possibleTypes(abstractTypeName: String): List<ObjectTypeDefinition> = when (val t = types[abstractTypeName]) {
        is UnionTypeDefinition -> t.types.mapNotNull { types[it.name] as? ObjectTypeDefinition }
        is InterfaceTypeDefinition ->
            types.values.filterIsInstance<ObjectTypeDefinition>()
                .filter { obj -> obj.interfaces.any { it.name == abstractTypeName } }

        else -> emptyList()
    }

    companion object {
        /** The five spec built-in scalar type names, always present in an assembled schema. */
        val BUILT_IN_SCALARS = setOf("Int", "Float", "String", "Boolean", "ID")

        fun fromDocument(document: Document): GraphQLSchema = SchemaBuilder.build(document)

        fun fromSdl(sdl: String): GraphQLSchema = fromDocument(Parser.parse(sdl))
    }
}
