package bosca.cli.documents.marks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface Mark {

    @SerialName("attrs")
    val attributes: MarkAttributes?
}

@Serializable
sealed interface MarkAttributes