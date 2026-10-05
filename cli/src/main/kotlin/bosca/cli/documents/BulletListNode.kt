package bosca.cli.documents

import bosca.cli.documents.marks.Mark
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BulletListAttributes(
    @SerialName("class")
    override val classes: String? = null,
) : DocumentAttributes {

    override fun withClasses(classes: String?): BulletListAttributes {
        return copy(classes = classes)
    }
}

@Serializable
@SerialName("bulletList")
data class BulletListNode(
    @SerialName("attrs")
    override val attributes: BulletListAttributes = BulletListAttributes(),
    override var content: List<DocumentNode> = emptyList(),
    override val marks: List<Mark> = emptyList(),
) : DocumentNode