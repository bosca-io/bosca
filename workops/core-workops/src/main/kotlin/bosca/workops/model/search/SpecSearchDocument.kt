package bosca.workops.model.search

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class SpecSearchDocument(
    val id: String,
    val key: String,
    val title: String? = null,
    @Contextual val metadataId: UUID,
    @Contextual val projectId: UUID? = null,
    val projectKey: String? = null,
    @Contextual val programId: UUID? = null,
    @Contextual val ownerProfileId: UUID,
    @Contextual val statusId: UUID,
    val statusName: String,
    val statusCategory: String,
    @Contextual val parentSpecId: UUID? = null,
    val labelIds: List<String> = emptyList(),
    val watcherProfileIds: List<String> = emptyList(),
    @Contextual val createdAt: OffsetDateTime,
    @Contextual val modifiedAt: OffsetDateTime,
    val deleted: Boolean = false,
)
