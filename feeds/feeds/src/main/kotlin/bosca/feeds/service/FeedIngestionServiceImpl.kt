package bosca.feeds.service

import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.model.MetadataRelationshipInput
import bosca.content.metadata.model.MetadataSourceInput
import bosca.content.metadata.model.MetadataSupplementaryInput
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.db.transaction
import bosca.documents.Content
import bosca.documents.HtmlNode
import bosca.documents.NodeConverter
import bosca.feeds.model.FeedItem
import bosca.feeds.model.RawFeedItem
import bosca.feeds.repository.FeedItemRepository
import bosca.security.service.SecurityService
import bosca.security.service.impersonate
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.parser.Parser
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Ingests feed items as content `Metadata`, deduped by GUID via [FeedItemRepository].
 *
 * - **New GUID**: create the `Metadata` (external-source attribution + raw fields in `attributes`),
 *   record the dedup mapping, then `setReady` it as the system principal — emitting the standard
 *   `MetadataSetReady` event the recommendations domain reacts to (no feeds-specific event).
 * - **Known GUID**: `edit` the existing `Metadata` in place (emitting `MetadataUpdated`) and touch the
 *   mapping. Re-fetching an unchanged item is therefore idempotent and does not re-fire readiness.
 *
 * One transaction per item, then the raw HTML body is normalized to a TipTap document inline (so every
 * item has a readable body for serving + preview) and the original, un-normalized source HTML is
 * preserved as a supplementary (key `original`). Text fields are HTML-entity-decoded.
 */
@ServiceImplementation
class FeedIngestionServiceImpl(
    private val metadataService: MetadataService,
    private val feedItemRepository: FeedItemRepository,
    private val securityService: SecurityService,
    private val bibleService: BibleService,
) : FeedIngestionService {

    override suspend fun ingest(sourceId: UUID, item: RawFeedItem): Metadata {
        val (metadata, isNew) = transaction {
            val input = toInput(sourceId, item)
            val existing = feedItemRepository.get(sourceId, item.guid)
            if (existing != null) {
                feedItemRepository.touch(sourceId, item.guid)
                metadataService.edit(existing.metadataId, input) to false
            } else {
                val created = metadataService.add(parent = null, collectionItemAttributes = null, input = input)
                feedItemRepository.add(FeedItem(sourceId = sourceId, guid = item.guid, metadataId = created.id))
                created to true
            }
        }
        // A new item is brought to ready as the system account ("sa") — emitting the standard
        // MetadataSetReady (same idiom as ImportUrlJob); a deduped re-fetch updates it in place without
        // re-firing readiness.
        val ready = if (isNew) {
            metadataService.setReady(metadata, securityService.impersonate("sa").principal().asPrincipal())
        } else {
            metadata
        }
        setBodyDocument(ready, item)
        setOriginalSupplementary(ready, item)
        setFeedItemTemplate(ready)
        setFeaturedImage(sourceId, ready, item)
        return ready
    }

    /**
     * Binds the seeded "Feed Item" document template, merging its default attributes
     * (`type: "Feed Item"`) into the item. Re-applied on every ingest because a deduped re-fetch's
     * `edit` rebuilds the attributes from the feed fields, dropping merged template attributes.
     */
    private suspend fun setFeedItemTemplate(metadata: Metadata) {
        metadataService.setDocumentTemplate(metadata, FEED_ITEM_TEMPLATE_ID, FEED_ITEM_TEMPLATE_VERSION)
    }

    /**
     * Relates the item's most prominent image to it as [FEATURED_IMAGE_RELATIONSHIP]: the
     * feed-declared image (`media:content`/`media:thumbnail`/enclosure/JSON Feed `image`) when the
     * source names one, else the first `<img>` in the content HTML. The image becomes its own
     * `Metadata` — labeled [FEATURED_IMAGE_LABEL], carrying attribution (the feed-declared
     * `media:credit`, else the publisher host, plus the article link), and **imported into Bosca
     * storage** via the platform import job (which resolves the real content type and readies it as
     * the system principal once the bytes land). Idempotent: an existing relationship is left in
     * place across re-fetches (featured images rarely churn), so a force-fetch backfills older items.
     */
    private suspend fun setFeaturedImage(sourceId: UUID, metadata: Metadata, item: RawFeedItem) {
        val url = item.imageUrl?.ifBlank { null } ?: firstContentImage(item) ?: return
        if (metadataService.getRelationships(metadata.id).any { it.relationship == FEATURED_IMAGE_RELATIONSHIP }) {
            return
        }
        val attribution = item.imageCredit?.let { decode(it) }
            ?: publisherHost(item) ?: url
        val image = transaction {
            val created = metadataService.add(
                parent = null,
                collectionItemAttributes = null,
                input = MetadataInput(
                    name = "${decode(item.title).ifBlank { "(untitled)" }} — featured image",
                    languageTag = "en",
                    contentType = imageContentType(url),
                    labels = listOf(FEATURED_IMAGE_LABEL),
                    attributes = buildJsonObject {
                        put("attribution", attribution)
                        item.link?.let { put("articleUrl", it) }
                        item.imageCredit?.let { put("credit", decode(it)) }
                    },
                    source = MetadataSourceInput(
                        id = sourceId,
                        identifier = "${item.guid}$FEATURED_IMAGE_IDENTIFIER_SUFFIX",
                        sourceUrl = url,
                    ),
                ),
            )
            metadataService.addRelationship(
                MetadataRelationshipInput(id1 = metadata.id, id2 = created.id, relationship = FEATURED_IMAGE_RELATIONSHIP),
            )
            created
        }
        // Import the bytes into Bosca storage; the job resolves the real content type and readies
        // the image (as the system account) once the upload completes.
        metadataService.importFromUrl(image.id, url, imageContentType(url), ready = true)
    }

    /** The publisher's host (from the article link) — the attribution fallback when no credit is declared. */
    private fun publisherHost(item: RawFeedItem): String? =
        item.link?.substringAfter("://", "")?.substringBefore('/')?.substringBefore(':')
            ?.removePrefix("www.")?.ifBlank { null }

    /** The first `<img src>` in the item's content HTML — the fallback "most prominent" image. */
    private fun firstContentImage(item: RawFeedItem): String? {
        val html = (item.content ?: item.summary)?.takeIf { it.isNotBlank() } ?: return null
        return Ksoup.parse(html).selectFirst("img[src]")?.attr("src")?.ifBlank { null }
    }

    /** Content type guessed from the URL's extension; feeds rarely declare one for images. */
    private fun imageContentType(url: String): String =
        when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            else -> "image/jpeg"
        }

    /**
     * Normalizes the item's raw HTML body to a Bosca TipTap document and stores it as the metadata's
     * body, so every item has a readable body for serving + preview — the work the normalize
     * pipeline node performs, applied inline here. No body (no content/summary) is a no-op.
     */
    private suspend fun setBodyDocument(metadata: Metadata, item: RawFeedItem) {
        val html = (item.content ?: item.summary)?.takeIf { it.isNotBlank() } ?: return
        // BibleService only satisfies NodeConverter's constructor; the plain-HTML path never invokes it.
        val document = NodeConverter(bibleService).convertDocument(HtmlNode(html = html))
        metadataService.setDocument(
            metadata,
            DocumentInput(title = decode(item.title).ifBlank { "(untitled)" }, content = Content(document = document)),
        )
    }

    /**
     * Preserves the item's original (un-normalized) source HTML as a supplementary attachment under
     * [ORIGINAL_KEY], so the unprocessed text is retained for re-normalization or audit while the
     * metadata body holds the cleaned TipTap document. Idempotent across re-fetches (updates in place).
     */
    private suspend fun setOriginalSupplementary(metadata: Metadata, item: RawFeedItem) {
        val html = (item.content ?: item.summary)?.takeIf { it.isNotBlank() } ?: return
        val id = metadataService.getSupplementaryByMetadataAndKey(metadata.id, ORIGINAL_KEY)?.id
            ?: metadataService.addSupplementary(
                MetadataSupplementaryInput(
                    metadataId = metadata.id,
                    key = ORIGINAL_KEY,
                    name = "Original source",
                    contentType = ORIGINAL_CONTENT_TYPE,
                    sourceIdentifier = item.guid,
                ),
            ).id
        metadataService.updateSupplementaryContent(metadata, id, html, ORIGINAL_CONTENT_TYPE)
    }

    private fun toInput(sourceId: UUID, item: RawFeedItem): MetadataInput = MetadataInput(
        name = decode(item.title).ifBlank { "(untitled)" },
        languageTag = "en",
        contentType = "bosca/v-document",
        source = MetadataSourceInput(id = sourceId, identifier = item.guid, sourceUrl = item.link),
        attributes = buildJsonObject {
            item.summary?.let { put("summary", decode(it)) }
            item.author?.let { put("author", decode(it)) }
            item.publishedAt?.let { put("publishedAt", it) }
            // The original source HTML is preserved as the `original` supplementary, not an attribute.
        },
    )

    /** Decodes HTML/XML entities (e.g. `&#8217;` → ') in a feed text field, leaving any markup intact. */
    private fun decode(value: String): String = Parser.unescapeEntities(value, false)

    private companion object {
        /** Supplementary key under which each item's original, un-normalized source HTML is preserved. */
        private const val ORIGINAL_KEY = "original"
        private const val ORIGINAL_CONTENT_TYPE = "text/html"

        /** Relationship from a feed item to its most prominent image. */
        private const val FEATURED_IMAGE_RELATIONSHIP = "image.featured"

        /** Label stamped on imported featured-image metadata so they are identifiable/queryable. */
        private const val FEATURED_IMAGE_LABEL = "featured-image"

        /** Appended to the item GUID to source-attribute the featured-image metadata. */
        private const val FEATURED_IMAGE_IDENTIFIER_SUFFIX = "#featured-image"

        /** The "Feed Item" document template seeded by V4__feed_item_template.sql. */
        private val FEED_ITEM_TEMPLATE_ID: UUID = UUID.parse("f0000000-0000-0000-0000-000000000001")
        private const val FEED_ITEM_TEMPLATE_VERSION = 1
    }
}
