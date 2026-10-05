package bosca.graphql

import bosca.graphql.schema.GraphQLSchema
import org.slf4j.LoggerFactory

object SchemaRegistry {

    private val log = LoggerFactory.getLogger(SchemaRegistry::class.java)

    lateinit var registry: GraphQLSchema
        private set

    lateinit var sdl: String
        private set

    suspend fun initialize(vararg registrars: SchemaRegistrar) {
        try {
            sdl = buildString {
                for (registrar in registrars) {
                    append("# ${registrar::class.simpleName}\n\n")
                    append(registrar.load())
                    append("\n\n")
                }
            }
            registry = GraphQLSchema.fromSdl(sdl)
        } catch (e: Exception) {
            log.error("Failed to load schema registry", e)
            throw e
        }
    }
}
