package bosca.graphql.schema

import bosca.graphql.language.FieldDefinition
import bosca.graphql.language.InputValueDefinition
import bosca.graphql.language.NamedType
import bosca.graphql.language.NonNullType
import bosca.graphql.language.ObjectTypeDefinition
import bosca.graphql.language.TypeDefinition
import bosca.graphql.parser.Parser

/**
 * The spec-mandated introspection type system (`__Schema`, `__Type`, `__Field`, `__InputValue`, `__EnumValue`,
 * `__Directive`, `__TypeKind`, `__DirectiveLocation`) plus the `__schema` / `__type` meta-fields on the query root.
 *
 * These types are part of the GraphQL type system, so they live with [GraphQLSchema]. They carry the reserved `__`
 * prefix that [SchemaBuilder] forbids for user types — which is precisely why [withIntrospection] merges the parsed
 * AST nodes directly instead of routing them through the builder's validation.
 */
object IntrospectionSchema {
    /** The standard introspection types. `specifiedByURL` is included for spec completeness. */
    val SDL: String = standardIntrospectionSdl()

    private fun standardIntrospectionSdl(): String = """
        type __Schema {
          description: String
          types: [__Type!]!
          queryType: __Type!
          mutationType: __Type
          subscriptionType: __Type
          directives: [__Directive!]!
        }
        type __Type {
          kind: __TypeKind!
          name: String
          description: String
          fields(includeDeprecated: Boolean = false): [__Field!]
          interfaces: [__Type!]
          possibleTypes: [__Type!]
          enumValues(includeDeprecated: Boolean = false): [__EnumValue!]
          inputFields(includeDeprecated: Boolean = false): [__InputValue!]
          ofType: __Type
          specifiedByURL: String
          isOneOf: Boolean
        }
        type __Field {
          name: String!
          description: String
          args(includeDeprecated: Boolean = false): [__InputValue!]!
          type: __Type!
          isDeprecated: Boolean!
          deprecationReason: String
        }
        type __InputValue {
          name: String!
          description: String
          type: __Type!
          defaultValue: String
          isDeprecated: Boolean!
          deprecationReason: String
        }
        type __EnumValue {
          name: String!
          description: String
          isDeprecated: Boolean!
          deprecationReason: String
        }
        type __Directive {
          name: String!
          description: String
          locations: [__DirectiveLocation!]!
          args(includeDeprecated: Boolean = false): [__InputValue!]!
          isRepeatable: Boolean!
        }
        enum __TypeKind {
          SCALAR
          OBJECT
          INTERFACE
          UNION
          ENUM
          INPUT_OBJECT
          LIST
          NON_NULL
        }
        enum __DirectiveLocation {
          QUERY
          MUTATION
          SUBSCRIPTION
          FIELD
          FRAGMENT_DEFINITION
          FRAGMENT_SPREAD
          INLINE_FRAGMENT
          VARIABLE_DEFINITION
          SCHEMA
          SCALAR
          OBJECT
          FIELD_DEFINITION
          ARGUMENT_DEFINITION
          INTERFACE
          UNION
          ENUM
          ENUM_VALUE
          INPUT_OBJECT
          INPUT_FIELD_DEFINITION
        }
    """.trimIndent()

    /** The introspection type definitions, parsed once. */
    val types: List<TypeDefinition> = Parser.parse(SDL).definitions.filterIsInstance<TypeDefinition>()

    /** The two meta-fields added to the query root: `__schema: __Schema!` and `__type(name: String!): __Type`. */
    val queryMetaFields: List<FieldDefinition> = listOf(
        FieldDefinition(null, "__schema", emptyList(), NonNullType(NamedType("__Schema")), emptyList()),
        FieldDefinition(
            null,
            "__type",
            listOf(InputValueDefinition(null, "name", NonNullType(NamedType("String")), null, emptyList())),
            NamedType("__Type"),
            emptyList(),
        ),
    )
}

/**
 * Returns a copy of this schema with the introspection type system merged in and the query root extended with the
 * `__schema` / `__type` meta-fields. Existing types win on a name clash and the meta-fields are only added if absent,
 * so calling this twice is a no-op. (`__typename` needs no schema entry — the executor synthesizes it per object.)
 */
fun GraphQLSchema.withIntrospection(): GraphQLSchema {
    val merged = LinkedHashMap(types)
    for (introspectionType in IntrospectionSchema.types) {
        if (introspectionType.name !in merged) merged[introspectionType.name] = introspectionType
    }

    val queryName = queryTypeName
    val query = queryName?.let { merged[it] as? ObjectTypeDefinition }
    if (query != null) {
        val existing = query.fields.mapTo(mutableSetOf()) { it.name }
        val additions = IntrospectionSchema.queryMetaFields.filter { it.name !in existing }
        if (additions.isNotEmpty()) merged[queryName] = query.copy(fields = query.fields + additions)
    }

    return GraphQLSchema(merged, directives, queryTypeName, mutationTypeName, subscriptionTypeName, description)
}
