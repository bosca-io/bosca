package bosca.design.model

import kotlinx.serialization.Serializable

/** A subsystem's presentation and native destination, supplied by the hosting application. */
@Serializable
data class SubsystemNavigationItem(
    val id: String,
    val label: String,
    val description: String,
    val icon: String,
    val accent: String,
    val href: String,
    val category: String,
)
