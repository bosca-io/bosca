package bosca.documents.marks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class LinkAttributes(
    val href: String? = null,
    val url: String? = null,
    val rel: String? = null,
    val target: String? = null,
) : MarkAttributes

@Serializable
@SerialName("link")
class Link(
    @SerialName("attrs")
    override val attributes: LinkAttributes? = null
) : Mark