package bosca.design.model

import kotlinx.serialization.Serializable

/** Presentation for a stored template container; applications supply authorized choices. */
@Serializable
data class DocumentContainerOption(
    val id: String,
    val name: String,
    val description: String = "",
    val type: String = "STANDARD",
    val filters: List<String> = emptyList(),
)
