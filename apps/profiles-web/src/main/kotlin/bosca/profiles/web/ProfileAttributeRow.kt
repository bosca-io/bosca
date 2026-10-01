package bosca.profiles.web

import bosca.profiles.web.graphql.ProfileVisibility
import kotlinx.serialization.Serializable

@Serializable
data class ProfileAttributeRow(
    val id: String,
    val typeId: String,
    val typeName: String,
    val description: String,
    val value: String,
    val source: String,
    val priority: Int,
    val confidence: Int,
    val visibility: ProfileVisibility,
    val expires: String,
    val protected: Boolean,
    val verified: Boolean,
    val field: ProfileAttributeFieldRow,
) {
    val hasField: Boolean get() = this.field.key.isNotBlank()
}
