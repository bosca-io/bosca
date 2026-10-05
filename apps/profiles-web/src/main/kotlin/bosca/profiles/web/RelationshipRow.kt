package bosca.profiles.web

import kotlinx.serialization.Serializable

@Serializable
data class RelationshipRow(
    val profileId: String,
    val name: String,
    val slug: String,
    val type: String,
) {
    val typeLabel: String get() = type.replaceFirstChar(Char::uppercase)
}
