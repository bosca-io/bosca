package bosca.category.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import bosca.serialization.UUID

@Serializable
data class Category(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
)
