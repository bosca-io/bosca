package bosca.documents

import bosca.documents.marks.Mark
import kotlin.uuid.Uuid
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ContainerAttributes(
    @SerialName("class")
    override val classes: String? = null,
    val name: String? = null,
    @Contextual
    var metadataId: Uuid? = null,
    var references: List<String>? = null,
    val renderer: String? = null
) : DocumentAttributes {

    override fun withClasses(classes: String?): ContainerAttributes {
        return copy(classes = classes)
    }

    fun withReferences(metadataId: Uuid?, references: List<String>?): ContainerAttributes {
        if (references.isNullOrEmpty()) return copy(metadataId = null, references = null)
        return copy(metadataId = metadataId, references = references)
    }
}

@Serializable
@SerialName("container")
data class ContainerNode(
    @SerialName("attrs")
    override val attributes: ContainerAttributes,
    override var content: List<DocumentNode> = emptyList(),
    override val marks: List<Mark> = emptyList(),
) : DocumentNode