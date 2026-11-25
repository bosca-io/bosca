package bosca.content.metadata.model

import bosca.serialization.OffsetDateTime
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Provenance and retention stored with a memory's content document. ACLs remain group grants. */
@Serializable
data class MemoryAttributes(
    val scope: MemoryScope,
    @Serializable(with = UUIDSerializer::class) val ownerId: UUID,
    @Serializable(with = UUIDSerializer::class) val collectionId: UUID? = null,
    @Serializable(with = UUIDSerializer::class) val sourceMetadataId: UUID? = null,
    val sourceVersion: Int? = null,
    val sourceRevision: String? = null,
    @Serializable(with = UUIDSerializer::class) val conversationId: UUID? = null,
    @Serializable(with = OffsetDateTimeSerializer::class) val expires: OffsetDateTime? = null,
) {
    companion object {
        const val CONTENT_TYPE = "bosca/v-document-memory"
        const val TRAIT = "ai.memory"

        fun from(metadata: Metadata): MemoryAttributes? {
            if (metadata.contentType != CONTENT_TYPE) return null
            val attributes = metadata.systemAttributes ?: return null
            return try { Json.decodeFromJsonElement(serializer(), attributes) }
            catch (_: SerializationException) { null }
            catch (_: IllegalArgumentException) { null }
        }
    }
}
