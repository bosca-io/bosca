package bosca.graphql.server

import bosca.graphql.language.InterfaceTypeDefinition
import bosca.graphql.language.ScalarTypeDefinition
import bosca.graphql.language.UnionTypeDefinition
import bosca.graphql.schema.GraphQLSchema
import bosca.graphql.schema.withIntrospection

/** Raised when a [RuntimeWiring] cannot be bound onto a schema (unknown type/field, wrong kind, missing Coercing). */
class ExecutableSchemaException(message: String) : IllegalArgumentException(message)

/**
 * A schema bound to its resolvers — the graphql-java `SchemaGenerator` result. Combine a validated
 * [GraphQLSchema] type system with a [RuntimeWiring] via [from]; the wiring is checked at build time so the
 * execution engine can resolve any field, abstract type, or scalar without further checks.
 *
 * Lookups fall back to sensible defaults: an unwired field reads from a `Map` source, and an unwired abstract
 * type reads `__typename` from a `Map` value — so map-shaped data resolves with no wiring at all.
 */
class ExecutableSchema private constructor(
    val schema: GraphQLSchema,
    private val fieldResolvers: Map<String, Map<String, FieldResolver>>,
    private val typeResolvers: Map<String, TypeResolver>,
    private val coercings: Map<String, Coercing>,
) {
    /** The resolver for `typeName.fieldName`, or the default map-reading resolver if none is wired. */
    fun resolver(typeName: String, fieldName: String): FieldResolver =
        fieldResolvers[typeName]?.get(fieldName) ?: DEFAULT_FIELD_RESOLVER

    /** The type resolver for an abstract [typeName], or the default `__typename`-reading resolver if none is wired. */
    fun typeResolver(typeName: String): TypeResolver =
        typeResolvers[typeName] ?: DEFAULT_TYPE_RESOLVER

    /** The [Coercing] for the scalar named [scalarName] (built-in or wired), or null if it is not a known scalar. */
    fun coercing(scalarName: String): Coercing? = coercings[scalarName]

    companion object {
        fun fromSdl(sdl: String, wiring: RuntimeWiring): ExecutableSchema =
            from(GraphQLSchema.fromSdl(sdl), wiring)

        /**
         * Bind [wiring] onto [schema], validating it; throws [ExecutableSchemaException] on any wiring error. The
         * introspection type system is merged in automatically, so every executable schema answers
         * `__schema` / `__type` without the caller wiring anything.
         */
        fun from(schema: GraphQLSchema, wiring: RuntimeWiring): ExecutableSchema {
            val introspected = schema.withIntrospection()
            val full = wiring.mergedWith(introspectionWiring(introspected))
            validate(introspected, full)
            return ExecutableSchema(
                schema = introspected,
                fieldResolvers = full.fieldResolvers,
                typeResolvers = full.typeResolvers,
                coercings = Scalars.coercings + full.scalarCoercings,
            )
        }

        private fun validate(schema: GraphQLSchema, wiring: RuntimeWiring) {
            wiring.fieldResolvers.forEach { (typeName, byField) ->
                val fields = schema.fields(typeName)
                    ?: throw ExecutableSchemaException("Cannot wire fields on '$typeName': it is not an object or interface type")
                byField.keys.forEach { fieldName ->
                    if (fields.none { it.name == fieldName }) {
                        throw ExecutableSchemaException("Cannot wire a resolver for unknown field '$typeName.$fieldName'")
                    }
                }
            }
            wiring.typeResolvers.keys.forEach { typeName ->
                val type = schema.type(typeName)
                if (type !is InterfaceTypeDefinition && type !is UnionTypeDefinition) {
                    throw ExecutableSchemaException("Cannot register a type resolver for '$typeName': only interfaces and unions have one")
                }
            }
            wiring.scalarCoercings.keys.forEach { name ->
                if (schema.type(name) !is ScalarTypeDefinition) {
                    throw ExecutableSchemaException("Cannot register a Coercing for '$name': it is not a scalar type")
                }
            }
            schema.types.values.filterIsInstance<ScalarTypeDefinition>().forEach { scalar ->
                if (scalar.name !in Scalars.coercings && scalar.name !in wiring.scalarCoercings) {
                    throw ExecutableSchemaException("No Coercing registered for custom scalar '${scalar.name}'")
                }
            }
        }

        /** Reads the field straight off a `Map` source — enough to resolve map-shaped data with no wiring. */
        private val DEFAULT_FIELD_RESOLVER = FieldResolver { context ->
            (context.source as? Map<*, *>)?.get(context.fieldName)
        }

        /** Reads the runtime type from a `Map` value's `__typename` entry. */
        private val DEFAULT_TYPE_RESOLVER = TypeResolver { value ->
            (value as? Map<*, *>)?.get("__typename") as? String
        }
    }
}
