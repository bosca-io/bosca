package bosca.profile.persona.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/** A named bundle of Studio subsystem access that can be assigned to profiles. */
@Serializable
data class StudioPersona(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    val description: String? = null,
    @ColumnName("subsystem_ids")
    val subsystemIds: List<String>,
    val enabled: Boolean = true,
    @Contextual
    val created: OffsetDateTime,
    @Contextual
    val modified: OffsetDateTime,
)
