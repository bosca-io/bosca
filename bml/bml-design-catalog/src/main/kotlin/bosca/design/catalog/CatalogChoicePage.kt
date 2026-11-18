package bosca.design.catalog

import kotlinx.serialization.Serializable

/** Caller-owned offset navigation in the design library's select-page wire format. */
@Serializable
data class CatalogChoicePage(
    val options: List<CatalogChoice>,
    val previousOffset: Int? = null,
    val nextOffset: Int? = null,
)
