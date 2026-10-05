package bosca.cli.documents.marks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("underline")
data class Underline(
    @SerialName("attrs")
    override val attributes: MarkAttributes? = null
) : Mark