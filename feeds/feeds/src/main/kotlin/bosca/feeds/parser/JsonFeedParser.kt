package bosca.feeds.parser

import bosca.feeds.model.RawFeedItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JSON Feed (1.1) parser — https://jsonfeed.org. Items are `items[]`; the unique id is `id`. The
 * body is `content_html` (falling back to `content_text`). Uses the DI-provided [Json] (which
 * ignores unknown keys) and explicit serializers (native-safe).
 */
class JsonFeedParser(
    private val json: Json,
) : FeedParser {

    override fun parse(body: String): List<RawFeedItem> {
        val document = json.decodeFromString(JsonFeedDocument.serializer(), body)
        return document.items.map { item ->
            RawFeedItem(
                guid = item.id,
                title = item.title.orEmpty(),
                link = item.url,
                author = item.author?.name,
                publishedAt = item.datePublished,
                summary = item.summary,
                content = item.contentHtml ?: item.contentText,
                imageUrl = item.image ?: item.bannerImage,
            )
        }
    }

    @Serializable
    private data class JsonFeedDocument(val items: List<JsonFeedItem> = emptyList())

    @Serializable
    private data class JsonFeedItem(
        val id: String,
        val title: String? = null,
        val url: String? = null,
        @SerialName("content_html") val contentHtml: String? = null,
        @SerialName("content_text") val contentText: String? = null,
        val summary: String? = null,
        @SerialName("date_published") val datePublished: String? = null,
        val author: JsonFeedAuthor? = null,
        val image: String? = null,
        @SerialName("banner_image") val bannerImage: String? = null,
    )

    @Serializable
    private data class JsonFeedAuthor(val name: String? = null)
}
