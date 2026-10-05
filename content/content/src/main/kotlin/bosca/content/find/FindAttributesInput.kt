package bosca.content.find

import kotlinx.serialization.Serializable

@Serializable
data class FindAttributesInput(
    val attributes: List<FindAttributeInput>
)