package bosca.meilisearch.client.model

import kotlinx.serialization.Serializable

/**
 * Faceting settings for a Meilisearch index controlling the
 * maximum number of values returned per facet field.
 */
@Serializable
data class Faceting(val maxValuesPerFacet: Int? = null)
