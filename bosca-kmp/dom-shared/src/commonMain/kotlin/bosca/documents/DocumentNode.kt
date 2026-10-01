package bosca.documents

import bosca.documents.marks.Mark
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface DocumentNode {
    @SerialName("attrs")
    val attributes: DocumentAttributes
    var content: List<DocumentNode>
    val marks: List<Mark>
}

@Serializable
sealed interface DocumentAttributes {
    @SerialName("class")
    val classes: String?

    fun withClasses(classes: String?): DocumentAttributes
}

@Serializable
@SerialName("empty")
data class EmptyDocumentAttributes(@SerialName("class") override val classes: String? = null) : DocumentAttributes {

    override fun withClasses(classes: String?): DocumentAttributes {
        return copy(classes = classes)
    }
}
