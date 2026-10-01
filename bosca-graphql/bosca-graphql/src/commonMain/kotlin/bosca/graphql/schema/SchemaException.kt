package bosca.graphql.schema

/** Thrown while assembling a [GraphQLSchema] from SDL (duplicate types, dangling references, bad roots). */
class SchemaException(message: String) : IllegalArgumentException(message)
