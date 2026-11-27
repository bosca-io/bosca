package bosca.workops.ui

import kotlinx.serialization.Serializable

/** Catalog presentation and its independent edit draft retain the inspected row identity/version. */
@Serializable
data class WorkOpsGlobalCatalogEntry(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val color: String = "",
    val icon: String = "",
    val category: String = "",
    val order: String = "0",
    val inward: String = "",
    val outward: String = "",
    val version: Long = 0,
)
