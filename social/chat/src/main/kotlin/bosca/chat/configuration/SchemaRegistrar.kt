package bosca.chat.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

/**
 * Loads the chat module's GraphQL SDL from the classpath. The file is named
 * `messaging.graphqls` rather than `chat.graphqls` so it doesn't collide with
 * the AI module's `chat.graphqls` (which describes AI chat sessions, not
 * channels) when both end up on the same classpath.
 */
@Schemas
interface SchemaRegistrar {

    @Schema("messaging.graphqls")
    val messaging: String
}
