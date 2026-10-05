package bosca.bml.render

import bosca.bml.project.ContentDigest
import java.time.Instant
import java.util.Locale

/** Public request dimensions available when resolving a shared shell's current data revision. */
data class BmlSharedCacheRequest(
    val pageRoute: String,
    val path: String,
    val params: Map<String, String>,
    val query: Map<String, String>,
    val locale: Locale,
)

/**
 * The current revision of every public value that can affect one shared shell variant.
 *
 * [etag] is the entity tag already carried by the Bosca metadata or collection that supplies the
 * shell. BML combines it, [lastModified], the public request dimensions, and the compiled project
 * version to produce the shell's entity tag. [lastModified] is optional and strengthens that
 * combined entity tag and newest-item ordering. BML does not expose it as a standalone HTTP
 * validator because it does not describe changes to the compiled representation.
 * The revision must describe the exact public cache snapshot used by the page renderer and must be
 * advanced atomically with that snapshot. Return no revision when the cache cannot provide that
 * consistency; BML will derive a strong entity tag from the completed response body instead.
 */
data class BmlSharedCacheRevision(
    val etag: String,
    val lastModified: Instant? = null,
) {
    init {
        require(etag.isNotBlank()) { "A BML shared-cache ETag must not be blank" }
    }

    companion object {
        /** Uses one Bosca metadata or collection result as the shell's source validator. */
        fun fromMetadata(etag: String?, lastModified: Instant?): BmlSharedCacheRevision? =
            etag?.takeIf(String::isNotBlank)?.let { BmlSharedCacheRevision(it, lastModified) }

        /**
         * Uses the most recently modified item as a list shell's source validator. When multiple
         * items share that time, their entity tags are combined so changing any tied item advances
         * the revision.
         *
         * Every item must provide a nonblank entity tag and modified time. If any item is missing
         * either value, this returns null so BML validates the completed response body instead.
         *
         * This is valid when every list-content or membership change advances the `modified` time
         * and entity tag of a visible item. Use the containing collection's entity tag instead when
         * that invariant does not hold (for example, removing an older item without touching the
         * collection or any remaining item).
         */
        fun <T> fromNewestModified(
            items: Iterable<T>,
            etag: (T) -> String?,
            lastModified: (T) -> Instant?,
        ): BmlSharedCacheRevision? {
            var newestModified: Instant? = null
            val newestEtags = mutableListOf<String>()
            for (item in items) {
                // Ignoring an item would allow its rendered content to change without advancing the
                // list validator. Fall back to a body hash unless every visible item is versioned.
                val itemEtag = etag(item)?.takeIf(String::isNotBlank) ?: return null
                val itemModified = lastModified(item) ?: return null
                val current = newestModified
                when {
                    current == null || itemModified > current -> {
                        newestModified = itemModified
                        newestEtags.clear()
                        newestEtags += itemEtag
                    }
                    itemModified == current -> newestEtags += itemEtag
                }
            }
            val modified = newestModified ?: return null
            val revisionEtag = if (newestEtags.size == 1) {
                newestEtags.single()
            } else {
                val material = buildString {
                    newestEtags.sorted().forEach { itemEtag ->
                        append(itemEtag.length).append(':').append(itemEtag).append('|')
                    }
                }
                ContentDigest.sha256(material)
            }
            return BmlSharedCacheRevision(revisionEtag, modified)
        }
    }
}

/**
 * Resolves the current public-data revision before BML renders a shared shell.
 *
 * Applications normally read this from the same in-memory cache whose revision is advanced after
 * a Bosca change event. Returning null keeps shared caching enabled but falls back to a body-based
 * validator, which requires rendering before BML can answer a conditional request. BML also uses
 * the body-based validator when a localization source is installed so catalog changes are covered.
 */
fun interface BmlSharedCacheRevisionProvider {
    /** Return the current revision for [request], or null when no cheap pre-render revision exists. */
    suspend fun revision(request: BmlSharedCacheRequest): BmlSharedCacheRevision?
}
