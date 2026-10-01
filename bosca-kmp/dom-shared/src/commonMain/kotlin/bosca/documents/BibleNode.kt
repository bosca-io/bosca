package bosca.documents

import bosca.documents.marks.Mark
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BibleAttributes(
    @SerialName("class")
    override val classes: String? = null,
    val metadataId: String? = null,
    val references: List<String> = emptyList()
) : DocumentAttributes {

    override fun withClasses(classes: String?): BibleAttributes {
        return copy(classes = classes)
    }
}

@Serializable
@SerialName("bible")
data class BibleNode(
    @SerialName("attrs")
    override val attributes: BibleAttributes = BibleAttributes(),
    override var content: List<DocumentNode> = emptyList(),
    override val marks: List<Mark> = emptyList(),
) : DocumentNode