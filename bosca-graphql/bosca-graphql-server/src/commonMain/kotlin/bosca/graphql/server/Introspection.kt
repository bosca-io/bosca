package bosca.graphql.server

import bosca.graphql.language.Directive
import bosca.graphql.language.DirectiveDefinition
import bosca.graphql.language.EnumTypeDefinition
import bosca.graphql.language.EnumValueDefinition
import bosca.graphql.language.FieldDefinition
import bosca.graphql.language.InputObjectTypeDefinition
import bosca.graphql.language.InputValueDefinition
import bosca.graphql.language.InterfaceTypeDefinition
import bosca.graphql.language.ListType
import bosca.graphql.language.NamedType
import bosca.graphql.language.NonNullType
import bosca.graphql.language.ObjectTypeDefinition
import bosca.graphql.language.ScalarTypeDefinition
import bosca.graphql.language.StringValue
import bosca.graphql.language.Type
import bosca.graphql.language.TypeDefinition
import bosca.graphql.language.UnionTypeDefinition
import bosca.graphql.printer.GraphQLPrinter
import bosca.graphql.schema.GraphQLSchema

/**
 * The runtime wiring for the introspection type system, auto-merged into every [ExecutableSchema].
 * It reads the schema's own type model: each `__Type` is sourced from an AST [Type] (so wrapper kinds and `ofType`
 * fall out naturally), `__Field` from a [FieldDefinition], `__InputValue` from an [InputValueDefinition], and so on.
 * Together with the `__typename` meta-field (synthesized by the executor) this makes the engine fully introspectable,
 * so standard introspection queries — and the gateway's schema stitching — work unchanged.
 */
internal fun introspectionWiring(schema: GraphQLSchema): RuntimeWiring = runtimeWiring {
    schema.queryTypeName?.let { queryType ->
        type(queryType) {
            field("__schema") { schema }
            field("__type") {
                val name = it.arg<String>("name")
                if (name != null && schema.type(name) != null) NamedType(name) else null
            }
        }
    }

    type("__Schema") {
        field("description") { schema.description }
        field("types") { schema.types.values.map { NamedType(it.name) } }
        field("queryType") { schema.queryTypeName?.let { NamedType(it) } }
        field("mutationType") { schema.mutationTypeName?.let { NamedType(it) } }
        field("subscriptionType") { schema.subscriptionTypeName?.let { NamedType(it) } }
        field("directives") { schema.directives.values.toList() }
    }

    type("__Type") {
        field("kind") { kindOf(it.source as Type, schema) }
        field("name") { (it.source as? NamedType)?.name }
        field("description") {
            val def = it.namedTypeDefinition(schema)
            if (def == null) null else def.description
        }
        field("ofType") {
            when (val t = it.source as Type) {
                is ListType -> t.type
                is NonNullType -> t.type
                is NamedType -> null
            }
        }
        field("specifiedByURL") {
            val def = it.namedTypeDefinition(schema)
            if (def is ScalarTypeDefinition) def.directives.specifiedByUrl() else null
        }
        field("isOneOf") {
            // Non-null for input objects, null for every other type kind (spec introspection schema).
            (it.namedTypeDefinition(schema) as? InputObjectTypeDefinition)?.directives?.any { d -> d.name == "oneOf" }
        }
        field("fields") {
            val includeDeprecated = it.includeDeprecated()
            when (val def = it.namedTypeDefinition(schema)) {
                is ObjectTypeDefinition -> def.fields.publicFields(includeDeprecated)
                is InterfaceTypeDefinition -> def.fields.publicFields(includeDeprecated)
                else -> null
            }
        }
        field("interfaces") {
            when (val def = it.namedTypeDefinition(schema)) {
                is ObjectTypeDefinition -> def.interfaces.map { i -> NamedType(i.name) }
                is InterfaceTypeDefinition -> def.interfaces.map { i -> NamedType(i.name) }
                else -> null
            }
        }
        field("possibleTypes") {
            when (val def = it.namedTypeDefinition(schema)) {
                is InterfaceTypeDefinition, is UnionTypeDefinition -> schema.possibleTypes((it.source as NamedType).name).map { o -> NamedType(o.name) }
                else -> null
            }
        }
        field("enumValues") {
            val includeDeprecated = it.includeDeprecated()
            val def = it.namedTypeDefinition(schema)
            if (def is EnumTypeDefinition) def.values.filter { v -> includeDeprecated || !v.directives.isDeprecated() } else null
        }
        field("inputFields") {
            val includeDeprecated = it.includeDeprecated()
            val def = it.namedTypeDefinition(schema)
            if (def is InputObjectTypeDefinition) def.fields.filter { f -> includeDeprecated || !f.directives.isDeprecated() } else null
        }
    }

    type("__Field") {
        field("name") { (it.source as FieldDefinition).name }
        field("description") { (it.source as FieldDefinition).description }
        field("type") { (it.source as FieldDefinition).type }
        field("args") {
            val includeDeprecated = it.includeDeprecated()
            (it.source as FieldDefinition).arguments.filter { a -> includeDeprecated || !a.directives.isDeprecated() }
        }
        field("isDeprecated") { (it.source as FieldDefinition).directives.isDeprecated() }
        field("deprecationReason") { (it.source as FieldDefinition).directives.deprecationReason() }
    }

    type("__InputValue") {
        field("name") { (it.source as InputValueDefinition).name }
        field("description") { (it.source as InputValueDefinition).description }
        field("type") { (it.source as InputValueDefinition).type }
        field("defaultValue") { (it.source as InputValueDefinition).defaultValue?.let { v -> GraphQLPrinter.printCompact(v) } }
        field("isDeprecated") { (it.source as InputValueDefinition).directives.isDeprecated() }
        field("deprecationReason") { (it.source as InputValueDefinition).directives.deprecationReason() }
    }

    type("__EnumValue") {
        field("name") { (it.source as EnumValueDefinition).name }
        field("description") { (it.source as EnumValueDefinition).description }
        field("isDeprecated") { (it.source as EnumValueDefinition).directives.isDeprecated() }
        field("deprecationReason") { (it.source as EnumValueDefinition).directives.deprecationReason() }
    }

    type("__Directive") {
        field("name") { (it.source as DirectiveDefinition).name }
        field("description") { (it.source as DirectiveDefinition).description }
        field("locations") { (it.source as DirectiveDefinition).locations }
        field("isRepeatable") { (it.source as DirectiveDefinition).repeatable }
        field("args") {
            val includeDeprecated = it.includeDeprecated()
            (it.source as DirectiveDefinition).arguments.filter { a -> includeDeprecated || !a.directives.isDeprecated() }
        }
    }
}

/** The `__TypeKind` of an AST type: wrappers map to `LIST`/`NON_NULL`, a named type to its definition's kind. */
private fun kindOf(type: Type, schema: GraphQLSchema): String = when (type) {
    is NonNullType -> "NON_NULL"
    is ListType -> "LIST"
    is NamedType -> when (schema.type(type.name)) {
        is ObjectTypeDefinition -> "OBJECT"
        is InterfaceTypeDefinition -> "INTERFACE"
        is UnionTypeDefinition -> "UNION"
        is EnumTypeDefinition -> "ENUM"
        is InputObjectTypeDefinition -> "INPUT_OBJECT"
        // ScalarTypeDefinition, or (defensively) a name not in the type map: a scalar. Folded into one arm so the
        // always-reached scalar case keeps the line live and there is no dead null branch.
        else -> "SCALAR"
    }
}

/** The `includeDeprecated` argument (defaulted to false in the SDL, so always present), as a plain Boolean. */
private fun ResolverContext.includeDeprecated(): Boolean = arg<Boolean>("includeDeprecated") == true

/** The type definition a `__Type` source refers to, or null when the source is a list/non-null wrapper. */
private fun ResolverContext.namedTypeDefinition(schema: GraphQLSchema): TypeDefinition? =
    (source as? NamedType)?.let { schema.type(it.name) }

/** Fields visible to introspection: the `__`-prefixed meta-fields are hidden, and deprecated fields unless requested. */
private fun List<FieldDefinition>.publicFields(includeDeprecated: Boolean): List<FieldDefinition> =
    filterNot { it.name.startsWith("__") }.filter { includeDeprecated || !it.directives.isDeprecated() }

private fun List<Directive>.isDeprecated(): Boolean = any { it.name == "deprecated" }

private fun List<Directive>.deprecationReason(): String? {
    val deprecated = firstOrNull { it.name == "deprecated" } ?: return null
    val reason = deprecated.arguments.firstOrNull { it.name == "reason" }?.value
    return if (reason is StringValue) reason.value else "No longer supported"
}

/** The `url` of a scalar's `@specifiedBy` directive, surfaced as `__Type.specifiedByURL`. */
private fun List<Directive>.specifiedByUrl(): String? {
    val specifiedBy = firstOrNull { it.name == "specifiedBy" } ?: return null
    val url = specifiedBy.arguments.firstOrNull { it.name == "url" }?.value
    return if (url is StringValue) url.value else null
}
