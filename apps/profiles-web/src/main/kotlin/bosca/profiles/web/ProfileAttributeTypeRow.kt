package bosca.profiles.web

import bosca.profiles.web.graphql.ProfileVisibility
import kotlinx.serialization.Serializable

@Serializable
data class ProfileAttributeTypeRow(
    val id: String,
    val name: String,
    val description: String,
    val visibility: ProfileVisibility,
    val protected: Boolean,
    val field: ProfileAttributeFieldRow,
) {
    val hasField: Boolean get() = this.field.key.isNotBlank()
    val selectable: Boolean get() = !protected && hasField
}
