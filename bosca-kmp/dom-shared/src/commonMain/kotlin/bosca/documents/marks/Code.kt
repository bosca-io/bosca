package bosca.documents.marks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("code")
data class Code(
    @SerialName("attrs")
    override val attributes: MarkAttributes? = null
) : Mark
