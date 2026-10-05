package bosca.security.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class Group(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    val description: String,
    val type: GroupType,
)
