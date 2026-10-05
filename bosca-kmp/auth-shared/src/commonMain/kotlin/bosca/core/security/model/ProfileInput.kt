package bosca.core.security.model

import bosca.core.security.type.ProfileVisibility
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Input for creating or updating a [Profile] (port of types.ts `ProfileInput`).
 *
 * It is [Serializable] because the hand-rolled GraphQL layer encodes it directly
 * as the `ProfileInput` variable — its field names (`name`, `slug`, `visibility`,
 * `attributes`) match the schema's input type verbatim, so no separate mapping
 * to a generated input type is needed.
 */
@Serializable
data class ProfileInput(
    val name: String,
    val slug: String? = null,
    val visibility: ProfileVisibility,
    val attributes: List<ProfileAttributeInput> = emptyList(),
)

/** Input for a single typed attribute set during profile creation/update. */
@Serializable
data class ProfileAttributeInput(
    val typeId: String,
    val attributes: JsonElement?,
    val source: String,
    val priority: Int,
    val confidence: Int,
    val visibility: ProfileVisibility,
)
