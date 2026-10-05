package bosca.profiles.web

import kotlinx.serialization.Serializable

@Serializable
data class RelationshipRequestRow(
    val id: String,
    val profileId: String,
    val name: String,
    val slug: String,
    val type: String,
    val created: String,
) {
    val typeLabel: String get() = type.replaceFirstChar(Char::uppercase)
}
