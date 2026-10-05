package bosca.graphql.client.generated

import kotlinx.serialization.Serializable

@Serializable
data class NoteFilter(
    val term: String,
    val visibility: Visibility? = null,
)
