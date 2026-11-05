package bosca.ai.kit.agents

import kotlinx.serialization.Serializable

/**
 * A user-facing Kit reply recorded in canonical conversation history, independent of the disposable
 * native harness. Explicit sealed serializers support native images.
 */
@Serializable
sealed interface KitResponse {

    /** A plain-text answer — a chat reply, a status notice, a delegated result summary, … */
    @Serializable
    data class Text(val text: String) : KitResponse
}
