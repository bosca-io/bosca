package bosca.cli.documents

import bosca.cli.documents.marks.Mark
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BibleReference(val usfm: String? = null, val human: String? = null)

@Serializable
data class BibleAttributes(
    @SerialName("class")
    override val classes: String? = null,
    val references: List<BibleReference> = emptyList()
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