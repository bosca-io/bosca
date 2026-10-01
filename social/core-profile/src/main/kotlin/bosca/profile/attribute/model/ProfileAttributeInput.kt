package bosca.profile.attribute.model

import bosca.profile.model.ProfileVisibility
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.time.OffsetDateTime

@Serializable
data class ProfileAttributeInput(
    @Contextual
    val id: UUID = UUID.NIL,
    val typeId: String,
    val visibility: ProfileVisibility,
    val confidence: Int,
    val priority: Int,
    val source: String,
    @Contextual
    val attributes: JsonElement? = null,
    @Contextual
    val metadataId: UUID? = null,
    val metadataSupplementary: String? = null,
    @Contextual
    val expiration: OffsetDateTime? = null
)