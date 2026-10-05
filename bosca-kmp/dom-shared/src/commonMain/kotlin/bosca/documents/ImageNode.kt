package bosca.documents

import bosca.documents.marks.Mark
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ImageAttributes(
    @SerialName("class")
    override val classes: String? = null,
    val alt: String? = null,
    val src: String? = null,
    val title: String? = null,
    val metadataId: String? = null,
) : DocumentAttributes {

    override fun withClasses(classes: String?): ImageAttributes {
        return copy(classes = classes)
    }
}

@Serializable
@SerialName("image")
data class ImageNode(
    @SerialName("attrs")
    override val attributes: ImageAttributes,
    override var content: List<DocumentNode> = emptyList(),
    override val marks: List<Mark> = emptyList(),
) : DocumentNode