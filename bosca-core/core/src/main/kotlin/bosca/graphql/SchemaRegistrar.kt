package bosca.graphql

/**
 * Provides GraphQL schema definition language (SDL) content to be merged into the
 * overall GraphQL schema at startup.
 *
 * Implementations typically load `.graphqls` files from the classpath and return
 * their concatenated contents. Multiple registrars can contribute schema fragments
 * that are combined into the final executable schema.
 */
interface SchemaRegistrar {

    /**
     * Loads and returns the GraphQL SDL content contributed by this registrar.
     *
     * @return the GraphQL schema definition text
     */
    suspend fun load(): String
}
