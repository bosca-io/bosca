package bosca.design.model

import kotlinx.serialization.Serializable

/** A dashboard widget's whole-cell position and size. */
@Serializable
data class DashboardLayoutItem(
    val id: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
)
