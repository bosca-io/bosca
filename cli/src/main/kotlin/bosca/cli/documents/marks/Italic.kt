package bosca.cli.documents.marks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("italic")
data class Italic(
    @SerialName("attrs")
    override val attributes: MarkAttributes? = null
) : Mark