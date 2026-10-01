package bosca.profile.persona.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Input for creating or updating a Studio persona. */
@Serializable
data class StudioPersonaInput(
    @Contextual
    val id: UUID? = null,
    val name: String,
    val description: String? = null,
    @ColumnName("subsystem_ids")
    val subsystemIds: List<String>,
    val enabled: Boolean = true,
)
