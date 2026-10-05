package bosca.feeds.parser

import com.fleeksoft.ksoup.nodes.Element

/**
 * Shared Media-RSS image extraction for the XML parsers. The "most prominent" image is the one the
 * source declares for the item: `media:content` with image semantics first (it names the item's main
 * media), then `media:thumbnail`.
 */
internal object MediaImages {

    fun fromXmlItem(item: Element): String? =
        item.select("media|content").firstOrNull(::isImageMedia)?.attr("url")?.ifBlank { null }
            ?: item.selectFirst("media|thumbnail")?.attr("url")?.ifBlank { null }

    /** The declared `media:credit` (item-level or nested under `media:content`), when present. */
    fun creditFromXmlItem(item: Element): String? =
        item.selectFirst("media|credit")?.text()?.trim()?.ifBlank { null }

    /**
     * `media:content` may carry video/audio; accept it as an image when `medium`/`type` say so — or
     * when neither is declared (common for publishers that attach a single article image).
     */
    private fun isImageMedia(element: Element): Boolean {
        val type = element.attr("type")
        val medium = element.attr("medium")
        return medium == "image" || type.startsWith("image/") || (medium.isBlank() && type.isBlank())
    }
}
