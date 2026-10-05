package bosca.cli.documents.marks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("strike")
data class Strike(
    @SerialName("attrs")
    override val attributes: MarkAttributes? = null
) : Mark
