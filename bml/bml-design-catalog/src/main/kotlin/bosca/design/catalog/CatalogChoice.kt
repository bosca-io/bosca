package bosca.design.catalog

import kotlinx.serialization.Serializable

/** A local example offered by the catalog picker. */
@Serializable
data class CatalogChoice(val value: String, val label: String, val disabled: Boolean = false)
