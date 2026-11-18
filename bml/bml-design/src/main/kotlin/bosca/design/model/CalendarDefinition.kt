package bosca.design.model

import kotlinx.serialization.Serializable

/** A selectable calendar. Applications own its permissions and persistence. */
@Serializable
data class CalendarDefinition(val id: String, val name: String, val color: String = "#268e71", val description: String = "", val editable: Boolean = true)
