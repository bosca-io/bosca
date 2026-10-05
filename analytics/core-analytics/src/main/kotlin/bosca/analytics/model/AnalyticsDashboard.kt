package bosca.analytics.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.security.model.PermissibleEntity
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@BatchKey("id")
@Serializable
data class AnalyticsDashboard(
    @Contextual
    override val id: UUID = UUID.NIL,
    val key: String,
    val name: String,
    val description: String,
    val configuration: JsonElement,
    val parameters: JsonElement? = null
) : PermissibleEntity<UUID> {

    override val public: Boolean = false
    override val publicContent: Boolean = false
    override val publicList: Boolean = false
    override val publicSupplementary: Boolean = false
    override val isPublished: Boolean = true
    override val isAdvertised: Boolean = false
    override val isDeleted: Boolean = false
}
