package bosca.content.metadata.model

import bosca.db.annotation.ColumnName
import bosca.documents.Content
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
class Document(
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID,
    val version: Int,
    @Contextual
    @ColumnName("template_metadata_id")
    val templateMetadataId: UUID? = null,
    @ColumnName("template_metadata_version")
    val templateMetadataVersion: Int? = null,
    val title: String,
    @Contextual
    val content: Content? = null,
) {
    fun toInput() = DocumentInput(templateMetadataId, templateMetadataVersion, title, content)
}

data class LocaleAwareDocument(
    val locale: Locale,
    val document: Document
)
