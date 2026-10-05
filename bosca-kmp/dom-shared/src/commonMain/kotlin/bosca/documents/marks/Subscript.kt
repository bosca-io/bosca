package bosca.documents.marks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("subscript")
data class Subscript(
    @SerialName("attrs")
    override val attributes: MarkAttributes? = null
) : Mark