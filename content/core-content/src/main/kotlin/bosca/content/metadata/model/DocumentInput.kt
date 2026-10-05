package bosca.content.metadata.model

import bosca.documents.Content
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
class DocumentInput(
    @Contextual
    val templateMetadataId: UUID? = null,
    val templateMetadataVersion: Int? = null,
    val title: String,
    @Contextual
    val content: Content?
) {

    fun toDocument(metadata: Metadata) = toDocument(metadata.id, metadata.version)

    fun toDocument(id: UUID, version: Int) = Document(
        metadataId = id,
        version = version,
        templateMetadataId = templateMetadataId,
        templateMetadataVersion = templateMetadataVersion,
        title = title,
        content = content
    )
}