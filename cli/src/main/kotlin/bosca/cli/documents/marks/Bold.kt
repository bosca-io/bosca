package bosca.cli.documents.marks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("bold")
data class Bold(
    @SerialName("attrs")
    override val attributes: MarkAttributes? = null
) : Mark