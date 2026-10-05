package bosca.graphql.persistedqueries

import kotlinx.serialization.Serializable

@Serializable
data class PersistedQuery(
    val application: String? = null,
    val query: String,
    val sha256: String
)
