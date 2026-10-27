package bosca.design.catalog

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import bosca.design.model.DashboardLayoutItem

/** Local layout form outcomes; widget content and data belong to the catalog. */
@Serializable
data class CatalogDashboard(
    val id: String,
    var value: List<DashboardLayoutItem> = initial,
    var readOnly: Boolean = false,
    var disabled: Boolean = false,
    var reject: Boolean = false,
    var visible: Boolean = true,
    var locked: Boolean = true,
    var error: String = "",
    var notice: String = "",
    var noticeSequence: Int = 0,
) {
    suspend fun save(layout: String) {
        delay(150)
        val submitted = Json.decodeFromString(ListSerializer(DashboardLayoutItem.serializer()), layout)
        if (!reject) value = submitted
        error = if (reject) "This example rejected the save. Your layout is retained." else ""
        notice = if (reject) "Layout save rejected." else "Layout saved."
        noticeSequence++
    }
    fun refresh() { notice = "Example refreshed."; noticeSequence++ }
    fun replace() { value = replacement; error = "" }
    fun toggleReadOnly() { readOnly = !readOnly }
    fun toggleDisabled() { disabled = !disabled }
    fun toggleRejection() { reject = !reject }
    fun toggleVisible() { visible = !visible }
    fun toggleLocked() { locked = !locked }

    companion object {
        val initial = listOf(
            DashboardLayoutItem("visits", 0, 0, 8, 6),
            DashboardLayoutItem("notes", 8, 0, 4, 6), DashboardLayoutItem("summary", 0, 6, 12, 2),
        )
        val replacement = listOf(
            DashboardLayoutItem("visits", 0, 0, 6, 7),
            DashboardLayoutItem("notes", 6, 0, 6, 7), DashboardLayoutItem("summary", 0, 7, 12, 2),
        )
    }
}
