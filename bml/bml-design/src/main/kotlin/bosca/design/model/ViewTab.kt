package bosca.design.model

import kotlinx.serialization.Serializable

/** A local view's stable value and display label; applications own its associated panel. */
@Serializable
data class ViewTab(val value: String, val label: String, val disabled: Boolean = false)
