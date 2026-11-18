package bosca.design.catalog

import kotlinx.serialization.Serializable

/** A sequence belongs to each caller-owned notification instance. */
@Serializable
data class CatalogNotice(val id: String, var sequence: Int = 0) {
    suspend fun notify() { sequence++ }
}
