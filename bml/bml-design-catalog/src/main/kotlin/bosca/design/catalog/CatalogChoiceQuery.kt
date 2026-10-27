package bosca.design.catalog

import kotlinx.serialization.Serializable

/** Search parameters, including the catalog's explicit error demonstration. */
@Serializable
data class CatalogChoiceQuery(val query: String, val offset: Int, val fail: Boolean = false)
