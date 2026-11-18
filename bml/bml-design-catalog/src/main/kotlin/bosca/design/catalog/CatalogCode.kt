package bosca.design.catalog

import kotlinx.serialization.Serializable

@Serializable
class CatalogCode(
    var value: String = "profile.name", var saved: String = "", var readOnly: Boolean = false,
    var disabled: Boolean = false, var refreshes: Int = 0,
) {
    fun save(text: String) { value = text; saved = text }
    fun refresh() { refreshes++ }
    fun replace() { value = "profile.id" }
    fun toggleReadOnly() { readOnly = !readOnly }
    fun toggleDisabled() { disabled = !disabled }
}
