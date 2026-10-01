package bosca.ai.models.model

import kotlinx.serialization.Serializable

@Serializable
data class SearchResponse(
    val hits: List<Hit>,
    val query: String,
    val limit: Int,
    val offset: Int,
    val estimatedHits: Long,
    val page: Int? = null
)

@Serializable
data class Hit(
    val id: String,
    val slug: String,
    val name: String,
    val itemType: ItemType,
    val published: Long,
    val created: Long,
    val modified: Long,
    val collections: List<Collection>,
    val type: String? = null,
    val description: String? = null,
    val contentType: String? = null,
    val categories: List<Category>? = null,
    val content: String? = null,
)

@Serializable
enum class ItemType {
    COLLECTION,
    METADATA,
    PROFILE
}

@Serializable
data class Category(
    val id: String,
    val name: String,
)


@Serializable
data class Collection(
    val id: String,
    val type: String,
    val name: String
)
