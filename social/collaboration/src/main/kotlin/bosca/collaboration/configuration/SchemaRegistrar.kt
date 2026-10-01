package bosca.collaboration.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

/**
 * Loads the collaboration module's GraphQL SDL from the classpath. The
 * `collaboration.graphqls` file declares the [Collaboration]/[CollaborationMutation]
 * namespace under which the bridge and federation administrative trees live.
 */
@Schemas
interface SchemaRegistrar {

    @Schema("collaboration.graphqls")
    val collaboration: String

    @Schema("bridge.graphqls")
    val bridge: String

    @Schema("federation.graphqls")
    val federation: String
}
