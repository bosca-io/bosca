package bosca.core.security.model

import bosca.core.security.type.ProfileType
import bosca.core.security.type.ProfileVisibility
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.Uuid

/**
 * A user profile containing display information and typed attributes. A
 * [Principal] may own multiple profiles (e.g. personal and organization).
 *
 * Port of `Profile` from types.ts. The `type`/`visibility` enums reuse the
 * Apollo-generated [ProfileType]/[ProfileVisibility] rather than re-declaring
 * them, per the project's "never mirror generated GraphQL types" rule.
 */
@Serializable
data class Profile(
    val id: Uuid,
    val name: String,
    val type: ProfileType,
    val visibility: ProfileVisibility,
    val slug: String?,
    val isPrimary: Boolean,
    val attributes: List<ProfileAttribute>,
)

/**
 * A single typed attribute on a [Profile], carrying a flexible JSON value
 * along with metadata about its origin and reliability.
 *
 * Port of `ProfileAttribute` from types.ts. [attributes] is the backend `JSON`
 * scalar (nullable), surfaced as a [JsonElement].
 */
@Serializable
data class ProfileAttribute(
    val typeId: String,
    val attributes: JsonElement?,
    val source: String,
    val priority: Int,
    val confidence: Int,
    val visibility: ProfileVisibility,
)
