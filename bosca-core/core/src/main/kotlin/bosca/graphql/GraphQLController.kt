package bosca.graphql

/**
 * Marker interface for GraphQL type controllers.
 *
 * Classes implementing this interface and annotated with
 * [@TypeController][bosca.graphql.annotations.TypeController] have their field-resolver
 * methods discovered by KSP, which generates the corresponding [Dispatcher] wiring
 * to connect them to the GraphQL schema at runtime.
 *
 * @param T the GraphQL object type this controller resolves fields for
 */
interface GraphQLController<T>