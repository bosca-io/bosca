package bosca.content.metadata.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable


@Serializable
data class MetadataProfile(
    @Contextual
    @ColumnName("metadata_id")
    val metadataId: UUID,
    @Contextual
    @ColumnName("profile_id")
    val profileId: UUID,
    val relationship: String,
    val sort: Int
)