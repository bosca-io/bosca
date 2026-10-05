package bosca.profiles.web

import kotlinx.serialization.Serializable

@Serializable
data class ProfileAttributeFieldRow(
    val key: String = "",
    val label: String = "",
    val description: String = "",
    val placeholder: String = "",
    val inputType: String = "text",
    val value: String = "",
    val required: Boolean = false,
    val textarea: Boolean = false,
    val boolean: Boolean = false,
    val checked: Boolean = false,
    val rows: Int = 4,
    val options: List<String> = emptyList(),
) {
    val select: Boolean get() = options.isNotEmpty()
}
