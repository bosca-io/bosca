package bosca.documents.marks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("hidden")
data class Hidden(
    @SerialName("attrs")
    override val attributes: MarkAttributes? = null
) : Mark