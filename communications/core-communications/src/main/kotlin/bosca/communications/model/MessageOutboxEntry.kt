package bosca.communications.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.JsonbMapper
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Durable, idempotently keyed request to deliver one isolated [Message] batch. */
@Serializable
data class MessageOutboxEntry(
    @Contextual
    val id: UUID,
    @ColumnName("source_id")
    @Contextual
    val sourceId: UUID,
    val position: Int,
    @property:DbMapper(JsonbMapper::class)
    val message: Message,
    val attempts: Int = 0,
    @ColumnName("last_error")
    val lastError: String? = null,
    @ColumnName("sent_at")
    @Contextual
    val sentAt: OffsetDateTime? = null,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)
