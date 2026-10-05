package bosca.feeds.model

import kotlinx.serialization.Serializable

/**
 * A single feed entry as parsed from a source's envelope, normalized across RSS / ATOM /
 * JSON Feed. This is the *raw* item —upserts it as content `Metadata` (keyed by [guid]) and a
 * later pipeline normalizes [content] to TipTap. [publishedAt] is the source's raw timestamp string
 * (RFC-822 / ISO-8601); it is parsed downstream.
 */
@Serializable
data class RawFeedItem(
    val guid: String,
    val title: String,
    val link: String? = null,
    val author: String? = null,
    val publishedAt: String? = null,
    val summary: String? = null,
    val content: String? = null,
    /**
     * The feed-declared prominent image (`media:content`/`media:thumbnail`/image enclosure, or the
     * JSON Feed `image`/`banner_image`), when the source names one. Ingestion relates it to the item
     * as `image.featured`, falling back to the first image in [content] when absent.
     */
    val imageUrl: String? = null,
    /** The feed-declared image credit (`media:credit`), when the source names one. */
    val imageCredit: String? = null,
)
