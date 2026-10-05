package bosca.graphql.client

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A generated, typed GraphQL operation. The codegen emits one `object` per `.graphql` operation
 * implementing this: the operation [document] text, its [operationName], and **explicit** codecs for the
 * typed [V] variables and [D] data (no reflective serialization — GraalVM-native-safe).
 *
 * @param V the operation's typed `Variables` (a generated `@Serializable` data class, or [Unit] when the
 *   operation has no variables).
 * @param D the operation's typed `Data` (a generated `@Serializable` data class).
 */
interface BoscaOperation<V, D> {
    /** The operation name, e.g. `GetUser`. */
    val operationName: String

    /** The GraphQL document text sent to the server. */
    val document: String

    /** Encode the typed variables into the JSON object sent as `variables`. */
    fun encodeVariables(variables: V): JsonObject

    /** Decode the response's `data` element into the typed result. */
    fun decodeData(data: JsonElement): D
}
