package bosca.design.catalog

import kotlinx.serialization.Serializable

@Serializable
data class CatalogBoardCard(val id: String, val title: String, val column: String, val movable: Boolean = true)
