package bosca.documents.marks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("superscript")
data class Superscript(
    @SerialName("attrs")
    override val attributes: MarkAttributes? = null
) : Mark